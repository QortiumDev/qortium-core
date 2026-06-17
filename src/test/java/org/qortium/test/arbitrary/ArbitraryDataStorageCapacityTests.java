package org.qortium.test.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataFolderSizeEstimator;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataCleanupManager;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.controller.arbitrary.ArbitraryDataStorageManager;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.list.ResourceListManager;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.ListUtils;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class ArbitraryDataStorageCapacityTests extends Common {

    @Before
    public void beforeTest() throws DataException, InterruptedException, IllegalAccessException {
        Common.useDefaultSettings();
        this.deleteDataDirectories();
        this.deleteListsDirectory();

        // Set difficulty to 1 to speed up the tests
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);
    }

    @After
    public void afterTest() throws DataException {
        this.deleteDataDirectories();
        this.deleteListsDirectory();
        ArbitraryDataStorageManager.getInstance().shutdown();
    }


    @Test
    public void testCalculateTotalStorageCapacity() {
        ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();
        double storageFullThreshold = 0.9; // 90%
        Long now = NTP.getTime();
        assertNotNull("NTP time must be synced", now);
        long expectedTotalStorageCapacity = Settings.getInstance().getMaxStorageCapacity();

        // Capacity isn't initially calculated
        assertNull(storageManager.getStorageCapacity());
        assertEquals(0L, storageManager.getTotalDirectorySize());
        assertFalse(storageManager.isStorageCapacityCalculated());

        // We need to calculate the directory size because we haven't yet
        assertTrue(storageManager.shouldCalculateDirectorySize(now));
        storageManager.getDataDirectorySize(now);
        assertTrue(storageManager.isStorageCapacityCalculated());

        // Storage capacity should equal the value specified in settings
        assertNotNull(storageManager.getStorageCapacity());
        assertEquals(expectedTotalStorageCapacity, storageManager.getStorageCapacity().longValue());

        // We shouldn't calculate storage capacity again so soon
        now += 9 * 60 * 1000L;
        assertFalse(storageManager.shouldCalculateDirectorySize(now));

        // ... but after 10 minutes we should recalculate
        now += 1 * 60 * 1000L + 1L;
        assertTrue(storageManager.shouldCalculateDirectorySize(now));
    }

    @Test
    public void testCalculateStorageCapacityPerName() {
        ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();
        ResourceListManager resourceListManager = ResourceListManager.getInstance();
        double storageFullThreshold = 0.9; // 90%
        Long now = NTP.getTime();
        assertNotNull("NTP time must be synced", now);

        // Capacity isn't initially calculated
        assertNull(storageManager.getStorageCapacity());
        assertEquals(0L, storageManager.getTotalDirectorySize());
        assertFalse(storageManager.isStorageCapacityCalculated());

        // We need to calculate the total directory size because we haven't yet
        assertTrue(storageManager.shouldCalculateDirectorySize(now));
        storageManager.getDataDirectorySize(now);
        assertTrue(storageManager.isStorageCapacityCalculated());

        // Storage capacity should initially equal the total
        assertEquals(0, resourceListManager.getItemCountForList("followedQdn"));
        assertEquals(0, ListUtils.followedQdnCount());
        long totalStorageCapacity = storageManager.getStorageCapacityIncludingThreshold(storageFullThreshold);
        assertEquals(totalStorageCapacity, storageManager.storageCapacityPerName(storageFullThreshold));

        // Follow some names
        assertTrue(resourceListManager.addToList("followedQdn", "Test1", false));
        assertTrue(resourceListManager.addToList("followedQdn", "Test2", false));
        assertTrue(resourceListManager.addToList("followedQdn", "Test3", false));
        assertTrue(resourceListManager.addToList("followedQdn", "Test4", false));
        assertTrue(resourceListManager.addToList("followedQdn", "Test5", false));
        assertTrue(resourceListManager.addToList("followedQdn", "Test6", false));

        // Ensure the followed name count is correct
        assertEquals(6, resourceListManager.getItemCountForList("followedQdn"));
        assertEquals(6, ListUtils.followedQdnCount());

        // Storage space per name should be the total storage capacity divided by the number of names
        // then multiplied by 4, to allow for names that don't use much space
        long expectedStorageCapacityPerName = (long)(totalStorageCapacity / 6.0f) * 4L;
        assertEquals(expectedStorageCapacityPerName, storageManager.storageCapacityPerName(storageFullThreshold));
    }

    @Test
    public void testCalculateDirectorySizeIncludesSeparateTempDirectory() throws IOException, IllegalAccessException {
        // A full scan must count the data directory and the separate temp directory once each (16 + 24 = 40),
        // not double-count the data directory or omit the temp directory.
        Path testRoot = Files.createTempDirectory("qdn-storage-capacity");
        Path dataPath = testRoot.resolve("data");
        Path tempDataPath = testRoot.resolve("temp");

        try {
            Files.createDirectories(dataPath);
            Files.createDirectories(tempDataPath);
            Files.write(dataPath.resolve("data.bin"), new byte[16]);
            Files.write(tempDataPath.resolve("temp.bin"), new byte[24]);

            FieldUtils.writeField(Settings.getInstance(), "dataPath", dataPath.toString(), true);
            FieldUtils.writeField(Settings.getInstance(), "tempDataPath", tempDataPath.toString(), true);

            ArbitraryDataStorageManager.calculateDirectorySize();

            assertEquals(40L, ArbitraryDataFolderSizeEstimator.getInstance().get());
        } finally {
            FileUtils.deleteQuietly(testRoot.toFile());
        }
    }

    @Test
    public void testCalculateDirectorySizeFailurePreservesPreviousValues() throws IOException, IllegalAccessException {
        // If a recalculation scan fails part way through, the previously estimated size must be preserved.
        Path testRoot = Files.createTempDirectory("qdn-storage-capacity-failure");
        Path dataPath = testRoot.resolve("data");
        Path tempDataPath = testRoot.resolve("temp");
        Path invalidTempDataPath = testRoot.resolve("temp-file");

        try {
            Files.createDirectories(dataPath);
            Files.createDirectories(tempDataPath);
            Files.write(dataPath.resolve("data.bin"), new byte[16]);
            Files.write(tempDataPath.resolve("temp.bin"), new byte[24]);
            Files.write(invalidTempDataPath, new byte[5]);

            FieldUtils.writeField(Settings.getInstance(), "dataPath", dataPath.toString(), true);
            FieldUtils.writeField(Settings.getInstance(), "tempDataPath", tempDataPath.toString(), true);

            // Establish a known-good estimate
            ArbitraryDataStorageManager.calculateDirectorySize();
            long previousSize = ArbitraryDataFolderSizeEstimator.getInstance().get();
            assertEquals(40L, previousSize);

            // Point the temp path at a regular file so the next scan fails (a file cannot be sized as a directory)
            FieldUtils.writeField(Settings.getInstance(), "tempDataPath", invalidTempDataPath.toString(), true);

            try {
                ArbitraryDataStorageManager.calculateDirectorySize();
                fail("expected directory size calculation to fail for a non-directory temp path");
            } catch (RuntimeException expected) {
                // expected - the estimator is only updated once the scan completes successfully
            }

            // The previous estimate must be preserved because the failed scan never updated the estimator
            assertEquals(previousSize, ArbitraryDataFolderSizeEstimator.getInstance().get());
        } finally {
            FileUtils.deleteQuietly(testRoot.toFile());
        }
    }


    private void deleteDataDirectories() {
        // Delete data directory if exists
        Path dataPath = Paths.get(Settings.getInstance().getDataPath());
        try {
            FileUtils.deleteDirectory(dataPath.toFile());
        } catch (IOException e) {

        }

        // Delete temp data directory if exists
        Path tempDataPath = Paths.get(Settings.getInstance().getTempDataPath());
        try {
            FileUtils.deleteDirectory(tempDataPath.toFile());
        } catch (IOException e) {

        }
    }

    private void deleteListsDirectory() {
        // Delete lists directory if exists
        Path listsPath = Paths.get(Settings.getInstance().getListsPath());
        try {
            FileUtils.deleteDirectory(listsPath.toFile());
        } catch (IOException e) {

        }
    }

}
