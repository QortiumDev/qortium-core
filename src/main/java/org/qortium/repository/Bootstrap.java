package org.qortium.repository;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.MintingAccountData;
import org.qortium.data.block.BlockData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.network.Network;
import org.qortium.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;
import org.qortium.utils.SevenZ;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


public class Bootstrap {

    private final Repository repository;

    private static final Logger LOGGER = LogManager.getLogger(Bootstrap.class);

    /** The maximum number of untrimmed blocks allowed to be included in a bootstrap, beyond the trim threshold */
    private static final int MAXIMUM_UNTRIMMED_BLOCKS = 100;

    /** The maximum number of unpruned blocks allowed to be included in a bootstrap, beyond the prune threshold */
    private static final int MAXIMUM_UNPRUNED_BLOCKS = 100;

    public static final String HOSTED_IMPORT_RETIRED_MESSAGE =
            "Hosted whole-database bootstrap has been retired; use checkpoint-anchored archive fast-sync or normal synchronization.";


    public Bootstrap(Repository repository) {
        this.repository = repository;
    }

    /**
     * canCreateBootstrap()
     * Performs basic initial checks to ensure everything is in order
     * @return true if ready for bootstrap creation, or an exception if not
     * All failure reasons are logged and included in the exception
     * @throws DataException
     */
    public boolean checkRepositoryState() throws DataException {
        LOGGER.info("Checking repository state...");

        final boolean isTopOnly = Settings.getInstance().isTopOnly();
        final boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();

        // Make sure we have a repository instance
        if (repository == null) {
            throw new DataException("Repository instance required to check if we can create a bootstrap.");
        }

        // Require that a block archive has been built
        if (!isTopOnly && !archiveEnabled) {
            throw new DataException("Unable to create bootstrap because the block archive isn't enabled. " +
                    "Set {\"archivedEnabled\": true} in settings.json to fix.");
        }

        // Make sure that the block archiver is up to date
        boolean upToDate = BlockArchiveWriter.isArchiverUpToDate(repository);
        if (!upToDate) {
            throw new DataException("Unable to create bootstrap because the block archive isn't fully built yet.");
        }

        // Ensure that this database contains the ATStatesHeightIndex which was missing in some cases
        boolean hasAtStatesHeightIndex = repository.getATRepository().hasAtStatesHeightIndex();
        if (!hasAtStatesHeightIndex) {
            throw new DataException("Unable to create bootstrap due to missing ATStatesHeightIndex. A re-sync from genesis is needed.");
        }

        // Ensure we have synced NTP time
        if (NTP.getTime() == null) {
            throw new DataException("Unable to create bootstrap because the node hasn't synced its time yet.");
        }

        // Ensure the chain is synced
        final BlockData chainTip = Controller.getInstance().getChainTip();
        final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
        if (minLatestBlockTimestamp == null || chainTip.getTimestamp() < minLatestBlockTimestamp) {
            throw new DataException("Unable to create bootstrap because the blockchain isn't fully synced.");
        }

        // FUTURE: ensure trim and prune settings are using default values

        if (!isTopOnly) {
            // We don't trim in top-only mode because we prune the blocks instead
            // If we're not in top-only mode we should make sure that trimming is up to date

            // Ensure that the online account signatures have been fully trimmed
            final int accountsTrimStartHeight = repository.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
            final long accountsUpperTrimmableTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
            final int accountsUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(accountsUpperTrimmableTimestamp);
            final int accountsBlocksRemaining = accountsUpperTrimmableHeight - accountsTrimStartHeight;
            if (accountsBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                throw new DataException(String.format("Blockchain is not fully trimmed. Please allow the node to run for longer, " +
                        "then try again. Blocks remaining (online accounts signatures): %d", accountsBlocksRemaining));
            }

            // Ensure that the AT states data has been fully trimmed
            final int atTrimStartHeight = repository.getATRepository().getAtTrimHeight();
            final long atUpperTrimmableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();
            final int atUpperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(atUpperTrimmableTimestamp);
            final int atBlocksRemaining = atUpperTrimmableHeight - atTrimStartHeight;
            if (atBlocksRemaining > MAXIMUM_UNTRIMMED_BLOCKS) {
                throw new DataException(String.format("Blockchain is not fully trimmed. Please allow the node to run " +
                        "for longer, then try again. Blocks remaining (AT states): %d", atBlocksRemaining));
            }
        }

        // Ensure that blocks have been fully pruned
        final int blockPruneStartHeight = repository.getBlockRepository().getBlockPruneHeight();
        int blockUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
        if (archiveEnabled) {
            blockUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
        }
        final int blocksPruneRemaining = blockUpperPrunableHeight - blockPruneStartHeight;
        if (blocksPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
            throw new DataException(String.format("Blockchain is not fully pruned. Please allow the node to run " +
                    "for longer, then try again. Blocks remaining: %d", blocksPruneRemaining));
        }

        // Ensure that AT states have been fully pruned
        final int atPruneStartHeight = repository.getATRepository().getAtPruneHeight();
        int atUpperPrunableHeight = chainTip.getHeight() - Settings.getInstance().getPruneBlockLimit();
        if (archiveEnabled) {
            atUpperPrunableHeight = repository.getBlockArchiveRepository().getBlockArchiveHeight() - 1;
        }
        final int atPruneRemaining = atUpperPrunableHeight - atPruneStartHeight;
        if (atPruneRemaining > MAXIMUM_UNPRUNED_BLOCKS) {
            throw new DataException(String.format("Blockchain is not fully pruned. Please allow the node to run " +
                    "for longer, then try again. Blocks remaining (AT states): %d", atPruneRemaining));
        }

        LOGGER.info("Repository state checks passed");
        return true;
    }

    /**
     * validateBlockchain
     * Performs quick validation of recent blocks in blockchain, prior to creating a bootstrap
     * @return true if valid, an exception if not
     * @throws DataException
     */
    public boolean validateBlockchain() throws DataException {
        LOGGER.info("Validating blockchain...");

        try {
            BlockChain.validate();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            throw new DataException(String.format("Blockchain validation failed: %s", e.getMessage()));
        }
    }

    /**
     * validateCompleteBlockchain
     * Performs intensive validation of all blocks in blockchain
     * @return true if valid, false if not
     */
    public boolean validateCompleteBlockchain() {
        LOGGER.info("Validating blockchain...");

        try {
            // Perform basic startup validation
            BlockChain.validate();

            // Perform more intensive full-chain validation
            BlockChain.validateAllBlocks();

            LOGGER.info("Blockchain is valid");

            return true;
        } catch (DataException e) {
            LOGGER.info("Blockchain validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String create() throws DataException, InterruptedException, IOException {

        // Make sure we have a repository instance
        if (repository == null) {
            throw new DataException("Repository instance required in order to create a boostrap");
        }

        Path operationTempDirectory = this.createTempDirectory();
        String identifier = operationTempDirectory.getFileName().toString();
        String backupName = "bootstrap-export-" + identifier;
        Path backupDirectory = Paths.get(Settings.getInstance().getRepositoryPath(), backupName).toAbsolutePath();
        Path snapshotDirectory = operationTempDirectory.resolve("bootstrap");

        try {
            BlockData expectedTip = this.createRepositorySnapshot(backupName, backupDirectory);
            this.sanitizeAndValidateSnapshot(backupDirectory, expectedTip);

            LOGGER.info("Moving isolated repository snapshot into export staging...");
            Files.move(backupDirectory, snapshotDirectory, ATOMIC_MOVE);

            Path compressedOutputPath = this.getBootstrapOutputPath().toAbsolutePath();
            Path checksumPath = Paths.get(compressedOutputPath + ".sha256");
            Path stagedArchive = operationTempDirectory.resolve(this.getFilename() + ".new");
            Path stagedChecksum = operationTempDirectory.resolve(this.getFilename() + ".sha256.new");

            LOGGER.info("Compressing isolated repository snapshot...");
            this.compress(stagedArchive, snapshotDirectory);

            LOGGER.info("Generating checksum file...");
            String checksum = Crypto.digestHexString(stagedArchive.toFile(), 1024 * 1024);
            LOGGER.info("checksum: {}", checksum);
            Files.writeString(stagedChecksum, checksum, StandardOpenOption.CREATE_NEW);

            this.publishOutputAtomically(stagedArchive, stagedChecksum, compressedOutputPath, checksumPath,
                    operationTempDirectory);

            LOGGER.info("Bootstrap creation complete. Output file: {}", compressedOutputPath);
            return compressedOutputPath.toString();
        } catch (TimeoutException e) {
            throw new DataException(String.format("Unable to create bootstrap due to timeout: %s", e.getMessage()));
        } finally {
            this.cleanupDirectory(backupDirectory, "isolated repository backup");
            this.cleanupDirectory(operationTempDirectory, "local archive export directory");
        }
    }

    private BlockData createRepositorySnapshot(String backupName, Path backupDirectory)
            throws DataException, InterruptedException, IOException, TimeoutException {
        LOGGER.info("Acquiring blockchain lock for repository snapshot...");
        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        blockchainLock.lockInterruptibly();
        try {
            BlockData liveTip = repository.getBlockRepository().getLastBlock();
            if (liveTip == null)
                throw new DataException("Live repository has no chain tip to export");

            repository.backup(false, backupName, 10 * 1000L);

            if (!Settings.getInstance().isTopOnly() && Settings.getInstance().isArchiveEnabled()) {
                LOGGER.info("Copying block archive into isolated repository snapshot...");
                FileUtils.copyDirectory(
                        Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toFile(),
                        backupDirectory.resolve("archive").toFile());
            }

            return new BlockData(liveTip);
        } finally {
            LOGGER.info("Releasing blockchain lock after repository snapshot...");
            blockchainLock.unlock();
        }
    }

    private void sanitizeAndValidateSnapshot(Path backupDirectory, BlockData expectedTip) throws DataException {
        String connectionUrl = "jdbc:hsqldb:file:" + backupDirectory.resolve("blockchain").toAbsolutePath()
                + ";create=false;hsqldb.full_log_replay=true";
        HSQLDBRepositoryFactory snapshotFactory = new HSQLDBRepositoryFactory(connectionUrl);
        try {
            if (snapshotFactory.wasPristineAtOpen())
                throw new DataException("Isolated repository snapshot unexpectedly opened as pristine");

            try (Repository snapshotRepository = snapshotFactory.getRepository()) {
                this.sanitizeSnapshot(snapshotRepository);
                snapshotRepository.checkConsistency();

                BlockData snapshotTip = snapshotRepository.getBlockRepository().getLastBlock();
                if (snapshotTip == null || snapshotTip.getHeight().intValue() != expectedTip.getHeight().intValue()
                        || !Arrays.equals(snapshotTip.getSignature(), expectedTip.getSignature()))
                    throw new DataException(String.format(
                            "Isolated repository snapshot tip does not match the live repository "
                                    + "(expected height %d sig %s, actual %s)",
                            expectedTip.getHeight(), Base58.encode(expectedTip.getSignature()),
                            snapshotTip == null ? "missing" : String.format("height %d sig %s",
                                    snapshotTip.getHeight(), Base58.encode(snapshotTip.getSignature()))));

                snapshotRepository.discardChanges();
            }
        } finally {
            snapshotFactory.close();
        }
    }

    /** Remove node-local rows from the isolated copy only. */
    protected void sanitizeSnapshot(Repository snapshotRepository) throws DataException {
        for (TradeBotData tradeBotData : snapshotRepository.getCrossChainRepository().getAllTradeBotData())
            snapshotRepository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());

        for (MintingAccountData mintingAccount : snapshotRepository.getAccountRepository().getMintingAccounts())
            snapshotRepository.getAccountRepository().delete(mintingAccount.getPrivateKey());

        snapshotRepository.getNetworkRepository().deleteAllPeers();
        Network.installInitialPeers(snapshotRepository);
        snapshotRepository.saveChanges();
    }

    protected void compress(Path stagedArchive, Path snapshotDirectory) throws IOException {
        SevenZ.compress(stagedArchive.toString(), snapshotDirectory.toFile());
    }

    protected void afterArchivePublished() throws IOException {
        // Failure-injection seam for atomic publication tests.
    }

    private void publishOutputAtomically(Path stagedArchive, Path stagedChecksum, Path outputArchive,
            Path outputChecksum, Path operationTempDirectory) throws IOException {
        this.requireSafeOutputTarget(outputArchive);
        this.requireSafeOutputTarget(outputChecksum);

        Path previousArchive = operationTempDirectory.resolve("previous-archive");
        Path previousChecksum = operationTempDirectory.resolve("previous-checksum");
        boolean hadArchive = this.preserveExistingOutput(outputArchive, previousArchive);
        boolean hadChecksum = this.preserveExistingOutput(outputChecksum, previousChecksum);
        boolean archivePublished = false;
        boolean checksumPublished = false;

        try {
            Files.move(stagedArchive, outputArchive, ATOMIC_MOVE, REPLACE_EXISTING);
            archivePublished = true;
            this.afterArchivePublished();
            Files.move(stagedChecksum, outputChecksum, ATOMIC_MOVE, REPLACE_EXISTING);
            checksumPublished = true;
        } catch (IOException | RuntimeException publishFailure) {
            IOException restoreFailure = null;
            try {
                if (archivePublished)
                    this.restorePreviousOutput(outputArchive, previousArchive, hadArchive);
            } catch (IOException e) {
                restoreFailure = e;
            }

            try {
                if (checksumPublished)
                    this.restorePreviousOutput(outputChecksum, previousChecksum, hadChecksum);
            } catch (IOException e) {
                if (restoreFailure == null)
                    restoreFailure = e;
                else
                    restoreFailure.addSuppressed(e);
            }

            if (restoreFailure != null) {
                restoreFailure.addSuppressed(publishFailure);
                throw restoreFailure;
            }
            throw publishFailure;
        }
    }

    private void requireSafeOutputTarget(Path outputPath) throws IOException {
        if (Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(outputPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to replace unsafe local archive output path: " + outputPath);
    }

    private boolean preserveExistingOutput(Path outputPath, Path backupPath) throws IOException {
        if (!Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS))
            return false;

        try {
            Files.createLink(backupPath, outputPath);
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(outputPath, backupPath);
        }
        return true;
    }

    private void restorePreviousOutput(Path outputPath, Path backupPath, boolean hadPrevious) throws IOException {
        if (hadPrevious)
            Files.move(backupPath, outputPath, ATOMIC_MOVE, REPLACE_EXISTING);
        else
            Files.deleteIfExists(outputPath);
    }

    private void cleanupDirectory(Path directory, String description) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            return;

        LOGGER.info("Cleaning up {} {}", description, directory);
        try {
            FileUtils.deleteDirectory(directory.toFile());
        } catch (IOException e) {
            LOGGER.warn("Unable to delete {} {}", description, directory, e);
        }
    }

    private String getFilename() {
        boolean isTopOnly = Settings.getInstance().isTopOnly();
        boolean archiveEnabled = Settings.getInstance().isArchiveEnabled();
        boolean isTestnet = Settings.getInstance().isTestNet();
        String prefix = isTestnet ? "testnet-" : "";

        if (isTopOnly) {
            return prefix.concat("bootstrap-toponly.7z");
        }
        else if (archiveEnabled) {
            return prefix.concat("bootstrap-archive.7z");
        }
        else {
            return prefix.concat("bootstrap-full.7z");
        }
    }

    private Path createTempDirectory() throws IOException {
        Path initialPath = Paths.get(Settings.getInstance().getRepositoryPath()).toAbsolutePath().getParent();
        String baseDir = Paths.get(initialPath.toString(), "tmp").toFile().getCanonicalPath();
        String identifier = UUID.randomUUID().toString();
        Path tempDir = Paths.get(baseDir, identifier);
        Files.createDirectories(tempDir);
        return tempDir;
    }

    public Path getBootstrapOutputPath() {
        Path initialPath = Paths.get(Settings.getInstance().getRepositoryPath()).toAbsolutePath().getParent();
        String compressedFilename = String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), this.getFilename());
        Path compressedOutputPath = Paths.get(initialPath.toString(), compressedFilename);
        return compressedOutputPath;
    }

}
