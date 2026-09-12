package org.qortium.controller.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataFolderSizeEstimator;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.list.ResourceListManager;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.TransactionRepository;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ArbitraryDataRetentionTests {
    private Path root;
    private Object originalCapacity;
    private Object originalSize;
    private long originalEstimate;
    private ArbitraryDataStorageManager storage;
    private ArbitraryDataCleanupManager cleanup;

    @Before
    public void before() throws Exception {
        Common.useDefaultSettings();
        storage = ArbitraryDataStorageManager.getInstance();
        cleanup = ArbitraryDataCleanupManager.getInstance();
        root = Files.createTempDirectory("qdn-retention-");
        FieldUtils.writeField(Settings.getInstance(), "dataPath", root.resolve("data").toString(), true);
        FieldUtils.writeField(Settings.getInstance(), "tempDataPath", root.resolve("temp").toString(), true);
        FieldUtils.writeField(Settings.getInstance(), "listsPath", root.resolve("lists").toString(), true);
        FieldUtils.writeField(Settings.getInstance(), "qdnRetainedSignatures", Collections.emptyList(), true);
        ResourceListManager.reset();
        originalCapacity = FieldUtils.readField(storage, "storageCapacity", true);
        originalSize = FieldUtils.readField(storage, "totalDirectorySize", true);
        originalEstimate = ArbitraryDataFolderSizeEstimator.getInstance().get();
        FieldUtils.writeField(storage, "storageCapacity", 1_000L, true);
        FieldUtils.writeField(storage, "totalDirectorySize", 0L, true);
    }

    @After
    public void after() throws Exception {
        if (storage != null && originalSize != null) {
            FieldUtils.writeField(storage, "storageCapacity", originalCapacity, true);
            FieldUtils.writeField(storage, "totalDirectorySize", originalSize, true);
            ArbitraryDataFolderSizeEstimator.getInstance().set(originalEstimate);
        }
        ResourceListManager.reset();
        if (root != null) FileUtils.deleteDirectory(root.toFile());
        Common.useDefaultSettings();
    }

    @Test
    public void olderPutSurvivesReplacementOnlyWhileRetained() throws Exception {
        ArbitraryTransactionData older = transaction(1);
        ArbitraryTransactionData newer = transaction(2);
        Path oldFile = persistedFile(new byte[] {1}, older.getSignature()).getFilePath();
        Path newFile = persistedFile(new byte[] {2}, newer.getSignature()).getFilePath();
        retain(older);

        // Use the same newest-first resource identity tracking as the cleanup sweep.
        Set<ArbitraryTransactionDataHashWrapper> processed = new HashSet<>();
        assertFalse(cleanup.deleteSupersededData(newer, processed.add(new ArbitraryTransactionDataHashWrapper(newer))));
        assertFalse(cleanup.deleteSupersededData(older, processed.add(new ArbitraryTransactionDataHashWrapper(older))));
        assertTrue(Files.exists(oldFile));
        assertTrue(Files.exists(newFile));

        FieldUtils.writeField(Settings.getInstance(), "qdnRetainedSignatures", Collections.emptyList(), true);
        assertTrue(cleanup.deleteSupersededData(older, false));
        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(newFile));
    }

    @Test
    public void retainedChunkOnlyPayloadCanBeRebuiltAfterReplacement() throws Exception {
        ArbitraryTransactionData older = transaction(1);
        byte[] payload = new byte[] {10, 20, 30, 40};
        ArbitraryDataFile file = persistedFile(payload, older.getSignature());
        assertEquals(2, file.split(2));
        org.json.JSONArray chunks = new org.json.JSONArray();
        for (byte[] hash : file.getChunkHashes()) chunks.put(Base58.encode(hash));
        byte[] metadata = new org.json.JSONObject().put("chunks", chunks).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ArbitraryDataFile metadataFile = persistedFile(metadata, older.getSignature());
        FieldUtils.writeField(older, "data", file.getHash(), true);
        FieldUtils.writeField(older, "metadataHash", metadataFile.getHash(), true);
        assertTrue(file.delete());
        retain(older);
        assertFalse(cleanup.deleteSupersededData(older, false));
        ArbitraryDataFile reopened = ArbitraryDataFile.fromTransactionData(older);
        assertTrue(reopened.allChunksExist());
        assertTrue(reopened.join());
        assertArrayEquals(payload, Files.readAllBytes(reopened.getFilePath()));

        FieldUtils.writeField(Settings.getInstance(), "qdnRetainedSignatures", Collections.emptyList(), true);
        assertTrue(cleanup.deleteSupersededData(older, false));
        assertFalse(Files.exists(metadataFile.getFilePath()));
        assertFalse(ArbitraryDataFile.fromTransactionData(older).allChunksExist());
    }

    @Test
    public void retainedFilesSurviveCapacityEvictionButUnretainedFilesDoNot() throws Exception {
        ArbitraryTransactionData retained = transaction(1);
        Path file = persistedFile(new byte[] {1}, retained.getSignature()).getFilePath();
        retain(retained);
        Repository repository = repository(retained, false);
        assertFalse(cleanup.deleteRandomFile(repository, file.getParent().toFile(), null));
        assertTrue(Files.exists(file));
        FieldUtils.writeField(Settings.getInstance(), "qdnRetainedSignatures", Collections.emptyList(), true);
        assertTrue(cleanup.deleteRandomFile(repository, file.getParent().toFile(), null));
        assertFalse(Files.exists(file));
    }

    @Test
    public void repositoryFailureDoesNotEvictConfiguredSignature() throws Exception {
        ArbitraryTransactionData retained = transaction(1);
        Path file = persistedFile(new byte[] {1}, retained.getSignature()).getFilePath();
        retain(retained);
        assertFalse(cleanup.deleteRandomFile(repository(retained, true), file.getParent().toFile(), null));
        assertTrue(Files.exists(file));
    }

    @Test
    public void explicitRetentionOverridesFollowPolicyButNotCapacityOrBlocks() throws Exception {
        ArbitraryTransactionData retained = transaction(1);
        FieldUtils.writeField(Settings.getInstance(), "storagePolicy", "FOLLOWED", true);
        assertFalse(storage.canStoreData(retained));
        retain(retained);
        assertTrue(storage.canStoreData(retained));
        assertTrue(storage.shouldPreFetchData(null, retained).isPass());
        FieldUtils.writeField(storage, "totalDirectorySize", 1_000L, true);
        assertTrue(storage.canStoreData(retained));
        assertFalse(storage.shouldPreFetchData(null, retained).isPass());
        FieldUtils.writeField(storage, "totalDirectorySize", 0L, true);
        assertTrue(ResourceListManager.getInstance().addToList("blockedQdn", "*/retention-test", false));
        assertFalse(storage.canStoreData(retained));
        assertFalse(storage.isRetained(retained));
        assertFalse(storage.shouldPreFetchData(null, retained).isPass());
    }

    @Test
    public void publicDataRestrictionStillAppliesToRetainedSignature() throws Exception {
        ArbitraryTransactionData retained = transaction(1);
        retain(retained);
        FieldUtils.writeField(Settings.getInstance(), "publicDataEnabled", false, true);
        assertFalse(storage.canStoreData(retained));
        assertFalse(storage.isRetained(retained));
        assertFalse(storage.shouldPreFetchData(null, retained).isPass());
    }

    @Test
    public void blockedRetainedPayloadCanStillBeDeletedByCleanup() throws Exception {
        ArbitraryTransactionData retained = transaction(1);
        Path file = persistedFile(new byte[] {1}, retained.getSignature()).getFilePath();
        retain(retained);
        assertTrue(ResourceListManager.getInstance().addToList("blockedQdn", "*/retention-test", false));
        assertTrue(cleanup.deleteRandomFile(repository(retained, false), file.getParent().toFile(), null));
        assertFalse(Files.exists(file));
    }

    private void retain(ArbitraryTransactionData transaction) throws Exception {
        FieldUtils.writeField(Settings.getInstance(), "qdnRetainedSignatures",
                Collections.singletonList(Base58.encode(transaction.getSignature())), true);
    }

    private ArbitraryDataFile persistedFile(byte[] data, byte[] signature) throws Exception {
        Path source = Files.createTempFile(root, "fixture-", ".bin");
        Files.write(source, data);
        ArbitraryDataFile file = ArbitraryDataFile.fromPath(source, signature);
        assertNotNull(file);
        assertNotNull(file.getFilePath());
        assertTrue("fixture must be inside this test's data directory",
                file.getFilePath().toAbsolutePath().normalize().startsWith(root.resolve("data").toAbsolutePath().normalize()));
        assertEquals(ArbitraryDataFile.ValidationResult.OK, file.isValid());
        return file;
    }

    private ArbitraryTransactionData transaction(int value) throws Exception {
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) value);
        ArbitraryDataFile file = persistedFile(new byte[] {(byte) value}, signature);
        BaseTransactionData base = new BaseTransactionData(value, 0, new byte[32], 0L, signature);
        return new ArbitraryTransactionData(base, 5, Service.ARBITRARY_DATA.value, 0, 1,
                "retention-test", "bundle", ArbitraryTransactionData.Method.PUT, new byte[32],
                ArbitraryTransactionData.Compression.NONE, file.getHash(), ArbitraryTransactionData.DataType.DATA_HASH,
                null, Collections.emptyList());
    }

    private Repository repository(ArbitraryTransactionData transaction, boolean fail) {
        TransactionRepository transactions = (TransactionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {TransactionRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("fromSignature")) {
                        if (fail) throw new DataException("test repository unavailable");
                        return transaction;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Repository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Repository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getTransactionRepository")) return transactions;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
