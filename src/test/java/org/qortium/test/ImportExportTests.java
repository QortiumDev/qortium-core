package org.qortium.test;

import com.google.common.hash.HashCode;
import org.apache.commons.io.FileUtils;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.crypto.ECKey;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PublicKeyAccount;
import org.qortium.controller.tradebot.TradeStates;
import org.qortium.controller.tradebot.TradeBot;
import org.qortium.crosschain.BitcoinyACCTv3;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crypto.Crypto;
import org.qortium.asset.Asset;
import org.qortium.data.account.MintingAccountData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.crosschain.TradeBotFillData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.repository.hsqldb.HSQLDBImportExport;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;
import org.qortium.utils.Triple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ImportExportTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
        this.deleteExportDirectory();
    }

    @After
    public void afterTest() throws DataException {
        this.deleteExportDirectory();
    }


    @Test
    public void testExportAndImportTradeBotStates() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Ensure no trade bots exist
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Create some trade bots
            List<TradeBotData> tradeBots = new ArrayList<>();
            for (int i=0; i<10; i++) {
                TradeBotData tradeBotData = this.createTradeBotData(repository);
                repository.getCrossChainRepository().save(tradeBotData);
                tradeBots.add(tradeBotData);
            }

            // Ensure they have been added
            assertEquals(10, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Export them
            HSQLDBImportExport.backupTradeBotStates(repository, null);

            // Delete them from the repository
            for (TradeBotData tradeBotData : tradeBots) {
                repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            }

            // Ensure they have been deleted
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Import them
            HSQLDBImportExport.importDataFromFile("TradeBotStates.json", repository);

            // Ensure they have been imported
            assertEquals(10, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Ensure all the data matches
            for (TradeBotData tradeBotData : tradeBots) {
                byte[] tradePrivateKey = tradeBotData.getTradePrivateKey();
                TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
                assertNotNull(repositoryTradeBotData);
                assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());
            }

            repository.saveChanges();
        }
    }

    @Test
    public void testExportAndImportCurrentTradeBotStates() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Ensure no trade bots exist
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Create some trade bots
            List<TradeBotData> tradeBots = new ArrayList<>();
            for (int i=0; i<10; i++) {
                TradeBotData tradeBotData = this.createTradeBotData(repository);
                repository.getCrossChainRepository().save(tradeBotData);
                tradeBots.add(tradeBotData);
            }

            // Ensure they have been added
            assertEquals(10, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Export them
            HSQLDBImportExport.backupTradeBotStates(repository, null);

            // Delete them from the repository
            for (TradeBotData tradeBotData : tradeBots) {
                repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            }

            // Ensure they have been deleted
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Add some more trade bots
            List<TradeBotData> additionalTradeBots = new ArrayList<>();
            for (int i=0; i<5; i++) {
                TradeBotData tradeBotData = this.createTradeBotData(repository);
                repository.getCrossChainRepository().save(tradeBotData);
                additionalTradeBots.add(tradeBotData);
            }

            // Export again
            HSQLDBImportExport.backupTradeBotStates(repository, null);

            // Import current states only
            HSQLDBImportExport.importDataFromFile("TradeBotStates.json", repository);

            // Ensure they have been imported
            assertEquals(5, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Ensure that only the additional trade bots have been imported and that the data matches
            for (TradeBotData tradeBotData : additionalTradeBots) {
                byte[] tradePrivateKey = tradeBotData.getTradePrivateKey();
                TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
                assertNotNull(repositoryTradeBotData);
                assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());
            }

            // None of the original trade bots should exist in the repository
            for (TradeBotData tradeBotData : tradeBots) {
                byte[] tradePrivateKey = tradeBotData.getTradePrivateKey();
                TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
                assertNull(repositoryTradeBotData);
            }

            repository.saveChanges();
        }
    }

    @Test
    public void testForeignForeignTradeBotStatePersistsAndExports() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            TradeBotData tradeBotData = this.createForeignForeignTradeBotData(repository);
            repository.getCrossChainRepository().save(tradeBotData);

            TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradeBotData.getTradePrivateKey());
            assertNotNull(repositoryTradeBotData);
            assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());

            HSQLDBImportExport.backupTradeBotStates(repository, null);
            repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            HSQLDBImportExport.importDataFromFile("TradeBotStates.json", repository);

            repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradeBotData.getTradePrivateKey());
            assertNotNull(repositoryTradeBotData);
            assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());

            repository.saveChanges();
        }
    }

    @Test
    public void testExportAndImportAllTradeBotStates() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Ensure no trade bots exist
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Create some trade bots
            List<TradeBotData> tradeBots = new ArrayList<>();
            for (int i=0; i<10; i++) {
                TradeBotData tradeBotData = this.createTradeBotData(repository);
                repository.getCrossChainRepository().save(tradeBotData);
                tradeBots.add(tradeBotData);
            }

            // Ensure they have been added
            assertEquals(10, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Export them
            HSQLDBImportExport.backupTradeBotStates(repository, null);

            // Delete them from the repository
            for (TradeBotData tradeBotData : tradeBots) {
                repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
            }

            // Ensure they have been deleted
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Add some more trade bots
            List<TradeBotData> additionalTradeBots = new ArrayList<>();
            for (int i=0; i<5; i++) {
                TradeBotData tradeBotData = this.createTradeBotData(repository);
                repository.getCrossChainRepository().save(tradeBotData);
                additionalTradeBots.add(tradeBotData);
            }

            // Export again
            HSQLDBImportExport.backupTradeBotStates(repository, null);

            // Import all states from the archive
            HSQLDBImportExport.importDataFromFile("TradeBotStatesArchive.json", repository);

            // Ensure they have been imported
            assertEquals(15, repository.getCrossChainRepository().getAllTradeBotData().size());

            // Ensure that all known trade bots have been imported and that the data matches
            tradeBots.addAll(additionalTradeBots);

            for (TradeBotData tradeBotData : tradeBots) {
                byte[] tradePrivateKey = tradeBotData.getTradePrivateKey();
                TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
                assertNotNull(repositoryTradeBotData);
                assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());
            }

            repository.saveChanges();
        }
    }

    @Test
    public void testArchiveTradeBotStateOnTradeFailure() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Create a trade bot and save it in the repository
            TradeBotData tradeBotData = this.createTradeBotData(repository);

            // Ensure it doesn't exist in the repository
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Export trade bot states, passing in the newly created trade bot as an additional parameter
            // This is needed because it hasn't been saved to the db yet
            HSQLDBImportExport.backupTradeBotStates(repository, Arrays.asList(tradeBotData));

            // Ensure it is still not present in the repository
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // Export all local node data again, but this time without including the trade bot data
            // This simulates the behaviour of a node shutdown
            repository.exportNodeLocalData();

            // The TradeBotStates.json file should contain no entries
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(false);
            Path tradeBotStatesBackup = Paths.get(backupDirectory.toString(), "TradeBotStates.json");
            assertTrue(Files.exists(tradeBotStatesBackup));
            String jsonString = new String(Files.readAllBytes(tradeBotStatesBackup));
            Triple<String, String, JSONArray> parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);
            JSONArray tradeBotDataJson = parsedJSON.getC();
            assertTrue(tradeBotDataJson.isEmpty());

            // .. but the TradeBotStatesArchive.json should contain the trade bot data
            Path tradeBotStatesArchiveBackup = Paths.get(backupDirectory.toString(), "TradeBotStatesArchive.json");
            assertTrue(Files.exists(tradeBotStatesArchiveBackup));
            jsonString = new String(Files.readAllBytes(tradeBotStatesArchiveBackup));
            parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);
            JSONObject tradeBotDataJsonObject = (JSONObject) parsedJSON.getC().get(0);
            assertEquals(tradeBotData.toJson().toString(), tradeBotDataJsonObject.toString());

            // Now try importing local data (to simulate a node startup)
            repository.importDataFromFile("TradeBotStates.json");

            // The trade should be missing since it's not present in TradeBotStates.json
            assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());

            // The user now imports TradeBotStatesArchive.json
            repository.importDataFromFile("TradeBotStatesArchive.json");

            // The trade should be present in the database
            assertEquals(1, repository.getCrossChainRepository().getAllTradeBotData().size());

            // The trade bot data in the repository should match the one that was originally created
            byte[] tradePrivateKey = tradeBotData.getTradePrivateKey();
            TradeBotData repositoryTradeBotData = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
            assertNotNull(repositoryTradeBotData);
            assertEquals(tradeBotData.toJson().toString(), repositoryTradeBotData.toJson().toString());

            repository.saveChanges();
        }
    }

    @Test
    public void testExportAndImportMintingAccountData() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Ensure no minting accounts exist
            assertTrue(repository.getAccountRepository().getMintingAccounts().isEmpty());

            // Create some minting accounts
            List<MintingAccountData> mintingAccounts = new ArrayList<>();
            for (int i=0; i<10; i++) {
                MintingAccountData mintingAccountData = this.createMintingAccountData();
                repository.getAccountRepository().save(mintingAccountData);
                mintingAccounts.add(mintingAccountData);
            }

            // Ensure they have been added
            assertEquals(10, repository.getAccountRepository().getMintingAccounts().size());

            // Export them
            HSQLDBImportExport.backupMintingAccounts(repository);

            // Delete them from the repository
            for (MintingAccountData mintingAccountData : mintingAccounts) {
                repository.getAccountRepository().delete(mintingAccountData.getPrivateKey());
            }

            // Ensure they have been deleted
            assertTrue(repository.getAccountRepository().getMintingAccounts().isEmpty());

            // Import them
            HSQLDBImportExport.importDataFromFile("MintingAccounts.json", repository);

            // Ensure they have been imported
            assertEquals(10, repository.getAccountRepository().getMintingAccounts().size());

            // Ensure all the data matches
            for (MintingAccountData mintingAccountData : mintingAccounts) {
                byte[] privateKey = mintingAccountData.getPrivateKey();
                MintingAccountData repositoryMintingAccountData = repository.getAccountRepository().getMintingAccount(privateKey);
                assertNotNull(repositoryMintingAccountData);
                assertEquals(mintingAccountData.toJson().toString(), repositoryMintingAccountData.toJson().toString());
            }

            repository.saveChanges();
        }
    }

    @Test
    public void testTradeBotFillsPersistAndExport() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            TradeBotFillData fillData = this.createTradeBotFillData();
            repository.getCrossChainRepository().save(fillData);

            TradeBotFillData repositoryFillData = repository.getCrossChainRepository().getTradeBotFillData(fillData.getAtAddress(), fillData.getHashOfSecret());
            assertNotNull(repositoryFillData);
            assertEquals(fillData.toJson().toString(), repositoryFillData.toJson().toString());
            assertEquals(1, repository.getCrossChainRepository().getAllTradeBotFillData().size());

            HSQLDBImportExport.backupTradeBotStates(repository, null);

            Path exportPath = HSQLDBImportExport.getExportDirectory(false);
            Path filePath = Paths.get(exportPath.toString(), "TradeBotFills.json");
            String jsonString = Files.readString(filePath);
            Triple<String, String, JSONArray> parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);

            assertEquals("tradeBotFills", parsedJSON.getA());
            assertEquals("current", parsedJSON.getB());
            assertEquals(1, parsedJSON.getC().length());

            HSQLDBImportExport.importDataFromFile("TradeBotFills.json", repository);
            assertEquals(1, repository.getCrossChainRepository().getAllTradeBotFillData().size());

            repository.saveChanges();
        }
    }

    @Test
    public void testImportRejectsPathsOutsideExportDirectory() throws Exception {
        Path outsideImportFile = Files.createTempFile("qortium-import-outside", ".json");
        Files.writeString(outsideImportFile, "{\"type\":\"mintingAccounts\",\"dataset\":\"current\",\"data\":[]}");

        try (final Repository repository = RepositoryManager.getRepository()) {
            try {
                HSQLDBImportExport.importDataFromFile(outsideImportFile.toString(), repository);
                fail("Expected imports outside the export directory to be rejected");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("outside of the target dir"));
            }
        } finally {
            Files.deleteIfExists(outsideImportFile);
        }
    }


    private TradeBotData createTradeBotData(Repository repository) throws DataException {
        byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();

        byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
        byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
        String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);

        byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
        byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

        String receivingAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

        ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromStringRequired("LITECOIN");

        // Convert Litecoin receiving address into public key hash (we only support P2PKH at this time)
        Address litecoinReceivingAddress;
        try {
            litecoinReceivingAddress = Address.fromString(litecoin.getBitcoinyInstance().getNetworkParameters(), receivingAddress);
        } catch (AddressFormatException e) {
            throw new DataException("Unsupported Litecoin receiving address: " + receivingAddress);
        }

        byte[] litecoinReceivingAccountInfo = litecoinReceivingAddress.getHash();

        byte[] creatorPublicKey = new byte[32];
        PublicKeyAccount creator = new PublicKeyAccount(repository, creatorPublicKey);

        long timestamp = NTP.getTime();
        String atAddress = "AT_ADDRESS";
        long foreignAmount = 1234;
        long localAmount = 5678;

        TradeBotData tradeBotData =  new TradeBotData(tradePrivateKey, BitcoinyACCTv3.NAME,
                TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.name(), TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.value,
                creator.getAddress(), atAddress, timestamp, Asset.NATIVE, localAmount,
                tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
                null, null,
                litecoin.name(),
                tradeForeignPublicKey, tradeForeignPublicKeyHash,
                foreignAmount, null, null, null, litecoinReceivingAccountInfo);

        return tradeBotData;
    }

    private TradeBotData createForeignForeignTradeBotData(Repository repository) throws DataException {
        TradeBotData baseTradeBotData = this.createTradeBotData(repository);

        ECKey offeredForeignKey = new ECKey();
        byte[] offeredTradeForeignPublicKey = offeredForeignKey.getPubKey();
        byte[] offeredTradeForeignPublicKeyHash = Crypto.hash160(offeredTradeForeignPublicKey);

        ECKey requestedForeignKey = new ECKey();
        byte[] requestedTradeForeignPublicKey = requestedForeignKey.getPubKey();
        byte[] requestedTradeForeignPublicKeyHash = Crypto.hash160(requestedTradeForeignPublicKey);

        TradeBotData tradeBotData = new TradeBotData(baseTradeBotData.getTradePrivateKey(), BitcoinyForeignForeignACCTv1.NAME,
                baseTradeBotData.getState(), baseTradeBotData.getStateValue(), baseTradeBotData.getCreatorAddress(),
                baseTradeBotData.getAtAddress(), baseTradeBotData.getTimestamp(), baseTradeBotData.getLocalAssetId(),
                baseTradeBotData.getLocalAmount(), baseTradeBotData.getTradeLocalPublicKey(),
                baseTradeBotData.getTradeLocalPublicKeyHash(), baseTradeBotData.getTradeLocalAddress(),
                baseTradeBotData.getSecret(), baseTradeBotData.getHashOfSecret(), baseTradeBotData.getForeignBlockchain(),
                baseTradeBotData.getTradeForeignPublicKey(), baseTradeBotData.getTradeForeignPublicKeyHash(),
                baseTradeBotData.getForeignAmount(), baseTradeBotData.getForeignKey(),
                baseTradeBotData.getLastTransactionSignature(), baseTradeBotData.getLockTimeA(),
                baseTradeBotData.getFillSlotIndex(), baseTradeBotData.getReceivingAccountInfo());

        tradeBotData.setOfferedForeignBlockchain("BITCOIN");
        tradeBotData.setOfferedTradeForeignPublicKey(offeredTradeForeignPublicKey);
        tradeBotData.setOfferedTradeForeignPublicKeyHash(offeredTradeForeignPublicKeyHash);
        tradeBotData.setOfferedForeignAmount(12345678L);
        tradeBotData.setOfferedForeignKey("offered-wallet-key");
        tradeBotData.setRequestedForeignBlockchain("LITECOIN");
        tradeBotData.setRequestedTradeForeignPublicKey(requestedTradeForeignPublicKey);
        tradeBotData.setRequestedTradeForeignPublicKeyHash(requestedTradeForeignPublicKeyHash);
        tradeBotData.setRequestedForeignAmount(87654321L);
        tradeBotData.setRequestedForeignKey("requested-wallet-key");
        tradeBotData.setLockTimeB(1234567890);
        tradeBotData.setOfferedForeignReceivingAccountInfo(offeredTradeForeignPublicKeyHash);
        tradeBotData.setRequestedForeignReceivingAccountInfo(requestedTradeForeignPublicKeyHash);

        return tradeBotData;
    }

    private TradeBotFillData createTradeBotFillData() {
        return new TradeBotFillData("AT_ADDRESS", 2, "ACTIVE", NTP.getTime(), "PARTNER_ADDRESS",
                HashCode.fromString("0011223344556677889900112233445566778899").asBytes(),
                HashCode.fromString("9988776655443322110099887766554433221100").asBytes(),
                1234567890, 10_00000000L, 12345678L, "P2SH_ADDRESS");
    }

    private MintingAccountData createMintingAccountData() {
        // These don't need to be valid keys - just 32 byte strings for the purposes of testing
        byte[] privateKey = new ECKey().getPrivKeyBytes();
        byte[] publicKey = new ECKey().getPrivKeyBytes();

        return new MintingAccountData(privateKey, publicKey);
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
