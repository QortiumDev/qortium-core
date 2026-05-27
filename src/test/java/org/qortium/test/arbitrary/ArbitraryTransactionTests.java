package org.qortium.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.misc.Category;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.block.BlockValidationContext;
import org.qortium.controller.arbitrary.ArbitraryDataCacheManager;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.crypto.AES;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.arbitrary.ArbitraryResourceStatus;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryTransactionTests extends Common {

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();
    }

    @Test
    public void testNonceAndFee() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 10000000; // sufficient
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 15, true);

            // Make sure that nonce validation still succeeds, as the fee has allowed us to avoid including a nonce
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testNonceAndLowFee() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction, with a fee that is too low
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 1; // insufficient
            boolean computeNonce = true;
            boolean insufficientFeeDetected = false;
            try {
                ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);
            }
            catch (DataException e) {
                if (e.getMessage().contains("INSUFFICIENT_FEE")) {
                    insufficientFeeDetected = true;
                }
            }

            // Transaction should be invalid due to an insufficient fee
            assertTrue(insufficientFeeDetected);
        }
    }

    @Test
    public void testFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction, with a fee
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 10000000; // sufficient
            boolean computeNonce = false;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds, even though it wasn't computed. This is because we have included a sufficient fee.
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 15, true);

            // Make sure that nonce validation still succeeds, as the fee has allowed us to avoid including a nonce
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testLowFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction with an insufficient fee and no nonce.
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 1; // insufficient

            ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                    repository, publicKey58, fee, path1, name, ArbitraryTransactionData.Method.PUT, service, identifier, null, null, null, null);

            txnBuilder.setChunkSize(chunkSize);
            txnBuilder.build();
            ArbitraryTransactionData transactionData = txnBuilder.getArbitraryTransactionData();
            Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);

            // Transaction should be invalid due to an insufficient fee
            assertEquals(Transaction.ValidationResult.INSUFFICIENT_FEE, result);
        }
    }

    @Test
    public void testZeroFeeNoNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction with zero fee and no nonce.
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 0L;

            ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                    repository, publicKey58, fee, path1, name, ArbitraryTransactionData.Method.PUT, service, identifier, null, null, null, null);

            txnBuilder.setChunkSize(chunkSize);
            txnBuilder.build();
            ArbitraryTransactionData transactionData = txnBuilder.getArbitraryTransactionData();
            ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);

            // Transaction should be invalid
            assertFalse(arbitraryTransaction.isSignatureValid());
        }
    }

    @Test
    public void testZeroFeeNonce() throws IllegalAccessException, DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900; // Actual data length will be longer due to encryption

            // Register the name to Alice
            RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            registerNameTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerNameTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerNameTransactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction with zero fee and a nonce.
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength);
            long fee = 0L;
            boolean computeNonce = true;
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, computeNonce, null, null, null, null);

            // Check that nonce validation succeeds
            byte[] signature = arbitraryDataFile.getSignature();
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            ArbitraryTransaction transaction = new ArbitraryTransaction(repository, transactionData);
            assertTrue(transaction.isSignatureValid());

            // Zero-fee ARBITRARY transactions are valid when they include valid MemoryPoW.
            assertEquals(Transaction.ValidationResult.OK, transaction.isValidUnconfirmed());

            // Increase difficulty to 15
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 15, true);

            // Make sure nonce validation fails once the existing nonce no longer satisfies MemoryPoW.
            // Note: there is a very tiny chance this could succeed due to being extremely lucky
            // and finding a high difficulty nonce in the first couple of cycles. It will be rare
            // enough that we shouldn't need to account for it.
            assertFalse(transaction.isSignatureValid());

            // Reduce difficulty back to 1, to double check
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);
            assertTrue(transaction.isSignatureValid());
        }
    }

    @Test
    public void testInvalidService() {
        byte[] randomHash = new byte[32];
        new Random().nextBytes(randomHash);

        Long now = NTP.getTime();

        final BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
                randomHash, 0L, null);
        final String name = "test";
        final String identifier = "test";
        final ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT;
        final ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.ZIP;
        final int size = 999;
        final int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
        final int nonce = 0;
        final byte[] secret = randomHash;
        final ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
        final byte[] digest = randomHash;
        final byte[] metadataHash = null;
        final List<PaymentData> payments = new ArrayList<>();
        final int validService = Service.IMAGE.value;
        final int invalidService = 99999999;

        // Try with valid service
        ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                version, validService, nonce, size, name, identifier, method,
                secret, compression, digest, dataType, metadataHash, payments);
        assertEquals(Service.IMAGE, transactionData.getService());

        // Try with invalid service
        transactionData = new ArbitraryTransactionData(baseTransactionData,
                version, invalidService, nonce, size, name, identifier, method,
                secret, compression, digest, dataType, metadataHash, payments);
        assertNull(transactionData.getService());
    }

    @Test
    public void testOnChainData() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST-testOnChainData"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = (int) (ArbitraryTransaction.MAX_DATA_SIZE - AES.getEncryptedFileSize(0)); // Max plaintext size for raw encrypted data.

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    null, null, null, null);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.RAW_DATA, arbitraryTransactionData.getDataType());
            assertEquals(arbitraryDataFile.getBytes().length, arbitraryTransactionData.getData().length);
            assertArrayEquals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData());

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have one file total - just the complete file (no chunks or metadata)
            assertEquals(1, arbitraryDataFile.fileCount());

            // Check the metadata isn't present
            assertNull(arbitraryDataFile.getMetadata());

            createUnrelatedFilesNextTo(arbitraryDataFile, 3);
            ArbitraryResourceStatus status = getResourceStatus(repository, name, service, identifier);
            assertEquals(Integer.valueOf(1), status.getLocalChunkCount());
            assertEquals(Integer.valueOf(1), status.getTotalChunkCount());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // Filename will be "data" because it's been held as raw bytes in the transaction,
            // so there is no metadata to store the original filename.
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), "data").toFile();

            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }

    @Test
    public void testOnChainDataWithMetadata() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = (int) (ArbitraryTransaction.MAX_DATA_SIZE - AES.getEncryptedFileSize(0)); // Max plaintext size for raw encrypted data.

            String title = "Test title";
            String description = "Test description";
            List<String> tags = Arrays.asList("Test", "tag", "another tag");
            Category category = Category.NETWORK;

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    title, description, tags, category);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.RAW_DATA, arbitraryTransactionData.getDataType());
            assertEquals(arbitraryDataFile.getBytes().length, arbitraryTransactionData.getData().length);
            assertArrayEquals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData());

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have two files total - one for the complete file, and the other for the metadata
            assertEquals(2, arbitraryDataFile.fileCount());

            // Check the metadata is correct
            assertEquals(title, arbitraryDataFile.getMetadata().getTitle());
            assertEquals(description, arbitraryDataFile.getMetadata().getDescription());
            assertEquals(tags, arbitraryDataFile.getMetadata().getTags());
            assertEquals(category, arbitraryDataFile.getMetadata().getCategory());
            assertEquals("text/plain", arbitraryDataFile.getMetadata().getMimeType());

            createUnrelatedFilesNextTo(arbitraryDataFile, 3);
            ArbitraryResourceStatus status = getResourceStatus(repository, name, service, identifier);
            assertEquals(Integer.valueOf(2), status.getLocalChunkCount());
            assertEquals(Integer.valueOf(2), status.getTotalChunkCount());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // Metadata stores the original filename, so raw on-chain data should be rebuilt with that filename.
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), path1.getFileName().toString()).toFile();

            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }

    @Test
    public void testDeleteRemovesResourceFromCacheAndSurvivesRebuild() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST-delete";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            Path path = ArbitraryUtils.generateRandomDataPath(240, true);
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name, identifier,
                    ArbitraryTransactionData.Method.PUT, service, alice);
            assertNotNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));

            ArbitraryTransactionData deleteTransactionData = createDeleteTransaction(repository, alice, name, identifier, service);
            TransactionUtils.signAndMint(repository, deleteTransactionData, alice);

            assertNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));
            assertEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());

            ArbitraryDataCacheManager.getInstance().buildArbitraryResourcesCache(repository, true);
            assertNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));
            assertEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());
        }
    }

    @Test
    public void testDeleteOrphanRestoresPreviousResource() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST-delete-orphan";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            Path path = ArbitraryUtils.generateRandomDataPath(240, true);
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name, identifier,
                    ArbitraryTransactionData.Method.PUT, service, alice);

            ArbitraryTransactionData deleteTransactionData = createDeleteTransaction(repository, alice, name, identifier, service);
            TransactionUtils.signAndMint(repository, deleteTransactionData, alice);
            assertNotNull(repository.getTransactionRepository().fromSignature(deleteTransactionData.getSignature()).getBlockHeight());
            assertNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));

            BlockUtils.orphanLastBlock(repository);

            assertNotNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));
            assertNotEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());
        }
    }

    @Test
    public void testPutCanRepublishAfterDelete() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST-delete-republish";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            Path path1 = ArbitraryUtils.generateRandomDataPath(240, true);
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name, identifier,
                    ArbitraryTransactionData.Method.PUT, service, alice);

            ArbitraryTransactionData deleteTransactionData = createDeleteTransaction(repository, alice, name, identifier, service);
            TransactionUtils.signAndMint(repository, deleteTransactionData, alice);
            assertEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());

            Path path2 = ArbitraryUtils.generateRandomDataPath(240, true);
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path2, name, identifier,
                    ArbitraryTransactionData.Method.PUT, service, alice);

            assertNotNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));
            assertNotEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());

            ArbitraryDataCacheManager.getInstance().buildArbitraryResourcesCache(repository, true);
            assertNotNull(repository.getArbitraryRepository().getArbitraryResource(service, name, identifier));
            assertNotEquals(ArbitraryResourceStatus.Status.DELETED, getResourceStatus(repository, name, service, identifier).getStatus());
        }
    }

    @Test
    public void testDeleteValidation() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST-delete-validation";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            ArbitraryTransactionData deleteBeforePut = createDeleteTransaction(repository, alice, name, identifier, service);
            assertEquals(Transaction.ValidationResult.RESOURCE_DOES_NOT_EXIST,
                    TransactionUtils.signAndImport(repository, deleteBeforePut, alice));

            Path path = ArbitraryUtils.generateRandomDataPath(240, true);
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name, identifier,
                    ArbitraryTransactionData.Method.PUT, service, alice);

            ArbitraryTransactionData bobDelete = createDeleteTransaction(repository, bob, name, identifier, service);
            assertEquals(Transaction.ValidationResult.INVALID_NAME_OWNER,
                    TransactionUtils.signAndImport(repository, bobDelete, bob));

            ArbitraryTransactionData nonEmptyDelete = createDeleteTransaction(repository, alice, name, identifier, service);
            nonEmptyDelete.setData(new byte[] { 1 });
            assertEquals(Transaction.ValidationResult.INVALID_DATA_LENGTH,
                    TransactionUtils.signAndImport(repository, nonEmptyDelete, alice));
        }
    }

    @Test
    public void testDeleteCanReferenceEarlierPutInSameBlock() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "TEST-delete-same-block";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            ArbitraryTransactionData putTransactionData = createPutTransaction(repository, alice, name, identifier, service);
            ArbitraryTransactionData deleteTransactionData = createDeleteTransaction(repository, alice, name, identifier, service);

            signTransaction(repository, putTransactionData, alice);
            signTransaction(repository, deleteTransactionData, alice);

            assertEquals(Transaction.ValidationResult.RESOURCE_DOES_NOT_EXIST,
                    Transaction.fromData(repository, deleteTransactionData).isValid());

            BlockValidationContext.set(Arrays.asList(putTransactionData, deleteTransactionData));
            BlockValidationContext.setCurrentTransactionIndex(1);
            try {
                assertEquals(Transaction.ValidationResult.OK,
                        Transaction.fromData(repository, deleteTransactionData).isValid());
            } finally {
                BlockValidationContext.clear();
            }
        }
    }

    @Test
    public void testDeleteCannotReferenceLaterPutInSameBlock() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "TEST-delete-later-put";
            String identifier = "resource";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            ArbitraryTransactionData deleteTransactionData = createDeleteTransaction(repository, alice, name, identifier, service);
            ArbitraryTransactionData putTransactionData = createPutTransaction(repository, alice, name, identifier, service);

            signTransaction(repository, deleteTransactionData, alice);
            signTransaction(repository, putTransactionData, alice);

            BlockValidationContext.set(Arrays.asList(deleteTransactionData, putTransactionData));
            BlockValidationContext.setCurrentTransactionIndex(0);
            try {
                assertEquals(Transaction.ValidationResult.RESOURCE_DOES_NOT_EXIST,
                        Transaction.fromData(repository, deleteTransactionData).isValid());
            } finally {
                BlockValidationContext.clear();
            }
        }
    }

    private ArbitraryResourceStatus getResourceStatus(Repository repository, String name, Service service, String identifier) {
        ArbitraryDataResource resource = new ArbitraryDataResource(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
        return resource.getStatus(repository);
    }

    private void registerName(Repository repository, PrivateKeyAccount account, String name) throws DataException {
        RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "");
        transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
        TransactionUtils.signAndMint(repository, transactionData, account);
    }

    private void signTransaction(Repository repository, TransactionData transactionData, PrivateKeyAccount signingAccount) throws DataException {
        Transaction transaction = Transaction.fromData(repository, transactionData);
        transaction.sign(signingAccount);
        assertTrue("Transaction's signature should be valid", transaction.isSignatureValid());
    }

    private ArbitraryTransactionData createPutTransaction(Repository repository, PrivateKeyAccount account,
            String name, String identifier, Service service) throws DataException {
        Long now = TransactionUtils.nextTimestamp(repository);
        long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(now);
        BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
                account.getPublicKey(), fee, null);
        int version = Transaction.getVersionByTimestamp(now);

        return new ArbitraryTransactionData(baseTransactionData, version, service.value, 0, 1,
                name, identifier, ArbitraryTransactionData.Method.PUT, null,
                ArbitraryTransactionData.Compression.NONE, new byte[] { 1 },
                ArbitraryTransactionData.DataType.RAW_DATA, null, new ArrayList<PaymentData>());
    }

	private ArbitraryTransactionData createDeleteTransaction(Repository repository, PrivateKeyAccount account,
			String name, String identifier, Service service) throws DataException {
		Long now = TransactionUtils.nextTimestamp(repository);
		long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(now);
		BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
				account.getPublicKey(), fee, null);
		int version = Transaction.getVersionByTimestamp(now);

        return new ArbitraryTransactionData(baseTransactionData, version, service.value, 0, 0,
                name, identifier, ArbitraryTransactionData.Method.DELETE, null,
                ArbitraryTransactionData.Compression.NONE, new byte[0],
                ArbitraryTransactionData.DataType.RAW_DATA, null, new ArrayList<PaymentData>());
    }

    private void createUnrelatedFilesNextTo(ArbitraryDataFile arbitraryDataFile, int count) throws IOException {
        Path parentPath = arbitraryDataFile.getFilePath().getParent();
        for (int i = 0; i < count; i++) {
            Files.createTempFile(parentPath, "unrelated-qdn-status-", ".tmp").toFile().deleteOnExit();
        }
    }

    @Test
    public void testOffChainData() throws DataException, IOException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Set difficulty to 1 to speed up the tests
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = null; // Not used for this test
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 1000;
            int dataLength = 240; // Min possible size. Becomes 257 bytes after encryption.

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Create PUT transaction
            Path path1 = ArbitraryUtils.generateRandomDataPath(dataLength, true);
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path1, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize, fee, false,
                    null, null, null, null);

            byte[] signature = arbitraryDataFile.getSignature();
            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) repository.getTransactionRepository().fromSignature(signature);

            // Check that the data is published on chain
            assertEquals(ArbitraryTransactionData.DataType.DATA_HASH, arbitraryTransactionData.getDataType());
            assertEquals(TransactionTransformer.SHA256_LENGTH, arbitraryTransactionData.getData().length);
            assertFalse(Arrays.equals(arbitraryDataFile.getBytes(), arbitraryTransactionData.getData()));

            // Check that we have no chunks because the complete file is already less than the chunk size
            assertEquals(0, arbitraryDataFile.chunkCount());

            // Check that we have one file total - just the complete file (no chunks or metadata)
            assertEquals(1, arbitraryDataFile.fileCount());

            // Check the metadata isn't present
            assertNull(arbitraryDataFile.getMetadata());

            // Now build the latest data state for this name
            ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader.loadSynchronously(true);

            // File content should match original file
            File outputFile = Paths.get(arbitraryDataReader.getFilePath().toString(), "file.txt").toFile();
            assertArrayEquals(Crypto.digest(outputFile), Crypto.digest(path1.toFile()));
        }
    }
}
