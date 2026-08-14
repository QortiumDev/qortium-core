package org.qortium.test;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.controller.BlockMinter;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.MintingAccountData;
import org.qortium.data.block.BlockData;
import org.qortium.repository.*;
import org.qortium.settings.Settings;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class BootstrapTests extends Common {

    @Before
    public void beforeTest() throws DataException, IOException {
        Common.useSettingsAndDb(Common.testSettingsFilename, false);
        NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
        this.deleteBootstraps();
    }

    @After
    public void afterTest() throws DataException, IOException {
        this.deleteBootstraps();
        this.deleteExportDirectory();
    }

    @Test
    public void testCheckRepositoryState() throws DataException, InterruptedException, TransformationException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            Bootstrap bootstrap = new Bootstrap(repository);
            assertTrue(bootstrap.checkRepositoryState());

        }
    }

    @Test
    public void testValidateBlockchain() throws DataException, InterruptedException, TransformationException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            Bootstrap bootstrap = new Bootstrap(repository);
            assertTrue(bootstrap.validateBlockchain());

        }
    }


    @Test
    public void testCreateBootstrapPreservesLiveRepository() throws DataException, InterruptedException, TransformationException, IOException {

        Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive", "2-900.dat");
        try (final Repository repository = RepositoryManager.getRepository()) {
            this.buildDummyBlockchain(repository);

            PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
            MintingAccountData mintingAccountData = new MintingAccountData(mintingAccount.getPrivateKey(), mintingAccount.getPublicKey());
            repository.getAccountRepository().save(mintingAccountData);
            repository.saveChanges();

            Bootstrap bootstrap = new Bootstrap(repository);
            Path bootstrapPath = bootstrap.getBootstrapOutputPath();

            // Ensure the compressed bootstrap doesn't exist
            assertFalse(Files.exists(bootstrapPath));

            // Create bootstrap
            assertEquals(bootstrapPath.toAbsolutePath().toString(), bootstrap.create());

            // Ensure the compressed bootstrap exists
            assertTrue(Files.exists(bootstrapPath));

            // Ensure the original block archive file exists
            assertTrue(Files.exists(archivePath));
            byte[] originalArchiveContents = Files.readAllBytes(archivePath);

            // Ensure block 1000 exists in the repository
            BlockData block1000 = repository.getBlockRepository().fromHeight(1000);
            assertNotNull(block1000);

            // Ensure we can retrieve block 10 from the archive
            assertNotNull(repository.getBlockArchiveRepository().fromHeight(10));

            Path checksumPath = Paths.get(bootstrapPath.toString() + ".sha256");
            assertTrue(Files.exists(checksumPath));
            assertEquals(Crypto.digestHexString(bootstrapPath.toFile(), 1024 * 1024), Files.readString(checksumPath).trim());

            assertArrayEquals(block1000.getSignature(), repository.getBlockRepository().fromHeight(1000).getSignature());
            assertArrayEquals(originalArchiveContents, Files.readAllBytes(archivePath));
            assertNotNull(repository.getBlockArchiveRepository().fromHeight(10));
            MintingAccountData restoredMintingAccount = repository.getAccountRepository().getMintingAccount(mintingAccount.getPrivateKey());
            assertNotNull(restoredMintingAccount);
            assertArrayEquals(mintingAccount.getPublicKey(), restoredMintingAccount.getPublicKey());
        }
    }


    private void buildDummyBlockchain(Repository repository) throws DataException, InterruptedException, TransformationException, IOException {
        // Alice self share online
        List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();
        PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
        mintingAndOnlineAccounts.add(aliceSelfShare);

        // Deploy an AT so that we have AT state data
        PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
        byte[] creationBytes = AtUtils.buildSimpleAT();
        long fundingAmount = 1_00000000L;
        AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

        // Mint some blocks so that we are able to archive them later
        for (int i = 0; i < 1000; i++)
            BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

        // Assume 900 blocks are trimmed (this specifies the first untrimmed height)
        repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(901);
        repository.getATRepository().setAtTrimHeight(901);

        // Check the max archive height - this should be one less than the first untrimmed height
        final int maximumArchiveHeight = BlockArchiveWriter.getMaxArchiveHeight(repository);

        // Write blocks 2-900 to the archive
        BlockArchiveWriter writer = new BlockArchiveWriter(0, maximumArchiveHeight, repository);
        writer.setShouldEnforceFileSizeTarget(false); // To avoid the need to pre-calculate file sizes
        BlockArchiveWriter.BlockArchiveWriteResult result = writer.write();

        // Increment block archive height
        repository.getBlockArchiveRepository().setBlockArchiveHeight(901);

        // Prune all the archived blocks
        repository.getBlockRepository().pruneBlocks(0, 900);
        repository.getBlockRepository().setBlockPruneHeight(901);

        // Prune the AT states for the archived blocks
        repository.getATRepository().rebuildLatestAtStates(900);
        repository.saveChanges();
        repository.getATRepository().pruneAtStates(0, 900);
        repository.getATRepository().setAtPruneHeight(901);

        // Refill cache, used by Controller.getMinimumLatestBlockTimestamp() and other methods
        Controller.getInstance().refillLatestBlocksCache();

        repository.saveChanges();
    }

    private void deleteBootstraps() throws IOException {
        try {
            Path archivePath = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-archive.7z"));
            Files.delete(archivePath);

            Path sha256Path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-archive.7z.sha256"));
            Files.delete(sha256Path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }

        try {
            Path path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-toponly.7z"));
            Files.delete(path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }

        try {
            Path path = Paths.get(String.format("%s%s", Settings.getInstance().getBootstrapFilenamePrefix(), "bootstrap-full.7z"));
            Files.delete(path);

        } catch (NoSuchFileException e) {
            // Nothing to delete
        }
    }

    private void deleteExportDirectory() {
        // Delete archive directory if exists
        Path archivePath = Paths.get(Settings.getInstance().getExportPath());
        try {
            FileUtils.deleteDirectory(archivePath.toFile());
        } catch (IOException e) {

        }
    }

}
