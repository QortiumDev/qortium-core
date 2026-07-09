package org.qortium.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryDataWriterStagingTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testCompressedSingleFileStagedInsideTempPathIsDeleted() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());

            Path stagingRoot = Paths.get(Settings.getInstance().getTempDataPath(), "staging");
            Files.createDirectories(stagingRoot);
            Path stagingDirectory = Files.createTempDirectory(stagingRoot, "qdn-regression-");

            Path stagedFile = stagingDirectory.resolve("post.json");
            byte[] data = new byte[1024];
            new Random().nextBytes(data);
            Files.write(stagedFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ArbitraryDataTransactionBuilder transactionBuilder = new ArbitraryDataTransactionBuilder(
                    repository, publicKey58, 0L, stagedFile, "TEST", ArbitraryTransactionData.Method.PUT,
                    Service.ARBITRARY_DATA, null, null, null, null, null);

            try {
                transactionBuilder.build();

                assertNotNull(transactionBuilder.getArbitraryTransactionData());
                assertNotNull(transactionBuilder.getArbitraryDataFile());
                assertEquals(ArbitraryTransactionData.Compression.ZIP, transactionBuilder.getArbitraryTransactionData().getCompression());
                assertFalse(Files.exists(stagedFile));
            } finally {
                ArbitraryDataFile arbitraryDataFile = transactionBuilder.getArbitraryDataFile();
                if (arbitraryDataFile != null) {
                    arbitraryDataFile.deleteAll(true);
                }
                Files.deleteIfExists(stagedFile);
                Files.deleteIfExists(stagingDirectory);
            }
        }
    }

}
