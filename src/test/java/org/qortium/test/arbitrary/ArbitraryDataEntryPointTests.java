package org.qortium.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortium.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.Assert.*;

public class ArbitraryDataEntryPointTests extends Common {

    @Before
    public void beforeTest() throws DataException, IllegalAccessException {
        Common.useDefaultSettings();

        // Set difficulty to 1 to speed up the publish tests
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);
    }

    // (1) Metadata JSON round-trip: entryPoint present

    @Test
    public void testMetadataEntryPointJsonRoundTripPresent() throws IOException, DataException {
        Path metadataPath = Files.createTempFile("entryPointMetadata", ".json");
        metadataPath.toFile().deleteOnExit();

        ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataPath);
        metadata.setFiles(List.of("index.html", "about.html"));
        metadata.setEntryPoint("about.html");
        metadata.write();

        // The serialized JSON should declare the entryPoint
        assertTrue(metadata.getJsonString().contains("\"entryPoint\""));
        assertTrue(metadata.getJsonString().contains("about.html"));

        // Read it back into a fresh instance
        ArbitraryDataTransactionMetadata readBack = new ArbitraryDataTransactionMetadata(metadataPath);
        readBack.read();
        assertEquals("about.html", readBack.getEntryPoint());
    }

    // (1) Metadata JSON round-trip: entryPoint absent -> null, and not emitted to JSON

    @Test
    public void testMetadataEntryPointJsonRoundTripAbsent() throws IOException, DataException {
        Path metadataPath = Files.createTempFile("entryPointMetadataAbsent", ".json");
        metadataPath.toFile().deleteOnExit();

        ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataPath);
        metadata.setFiles(List.of("index.html", "about.html"));
        // No entryPoint set
        metadata.write();

        // When unset, entryPoint must not appear in the JSON (backward compatible)
        assertFalse(metadata.getJsonString().contains("entryPoint"));

        // Read it back into a fresh instance - entryPoint should be null
        ArbitraryDataTransactionMetadata readBack = new ArbitraryDataTransactionMetadata(metadataPath);
        readBack.read();
        assertNull(readBack.getEntryPoint());
    }

    // (2) Publish-time validation: a valid entryPoint (present in file list) is accepted

    @Test
    public void testPublishWithValidEntryPoint() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            // Build a multi-file resource directory with a valid entryPoint
            Path path = buildMultiFileResource("entryPointValid");
            ArbitraryDataTransactionMetadata metadata = buildMetadata(repository, publicKey58, path, name, service, "about.html");

            // The entryPoint is persisted in the metadata and is one of the resource's files
            assertEquals("about.html", metadata.getEntryPoint());
            assertTrue(metadata.getFiles().size() > 1);
            assertTrue(metadata.getFiles().contains("about.html"));
        }
    }

    // (2) Publish-time validation: a non-existent entryPoint is rejected

    @Test
    public void testPublishWithInvalidEntryPointRejected() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST";
            Service service = Service.ARBITRARY_DATA;

            registerName(repository, alice, name);

            Path path = buildMultiFileResource("entryPointInvalid");

            // Building with an entryPoint that matches no file must fail (validated during build)
            try {
                buildMetadata(repository, publicKey58, path, name, service, "does-not-exist.html");
                fail("Publishing with a non-existent entryPoint should have thrown DataException");
            } catch (DataException e) {
                assertTrue("Error should mention entryPoint: " + e.getMessage(),
                        e.getMessage() != null && e.getMessage().contains("entryPoint"));
            }
        }
    }

    // (3) Download resolution order: single-file resources expose exactly one file (step 1 of resolution)

    @Test
    public void testSingleFileResolution() throws DataException, IOException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST";
            String identifier = null;
            Service service = Service.ARBITRARY_DATA;
            int chunkSize = 100;
            int dataLength = 900;

            registerName(repository, alice, name);

            Path path = ArbitraryUtils.generateRandomDataPath(dataLength);
            ArbitraryDataFile arbitraryDataFile = ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name,
                    identifier, ArbitraryTransactionData.Method.PUT, service, alice, chunkSize);

            // A single-file resource lists exactly one file - this is the step (1) auto-serve case
            assertEquals(1, arbitraryDataFile.getMetadata().getFiles().size());
            assertTrue(arbitraryDataFile.getMetadata().getFiles().contains("file.txt"));
            // No entryPoint declared for a plain single-file publish
            assertNull(arbitraryDataFile.getMetadata().getEntryPoint());
        }
    }

    // Note: the no-filepath resolution logic itself (single file / entryPoint / require-filepath)
    // is unit-tested directly in org.qortium.api.resource.ArbitraryDefaultFilepathTests.

    // Helpers

    private void registerName(Repository repository, PrivateKeyAccount account, String name) throws DataException {
        RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "");
        transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
        TransactionUtils.signAndMint(repository, transactionData, account);
    }

    /**
     * Build a resource through ArbitraryDataTransactionBuilder (which runs ArbitraryDataWriter,
     * including entryPoint validation and metadata creation) and return its metadata, without
     * minting. This keeps entryPoint tests deterministic by avoiding the multi-file publish-then-mint
     * path (whose signature check is separately flaky).
     */
    private ArbitraryDataTransactionMetadata buildMetadata(Repository repository, String publicKey58, Path path,
                                                           String name, Service service, String entryPoint) throws DataException, IOException {
        ArbitraryDataTransactionBuilder txnBuilder = new ArbitraryDataTransactionBuilder(
                repository, publicKey58, 0L, path, name, ArbitraryTransactionData.Method.PUT, service, null,
                null, null, null, null, entryPoint);
        txnBuilder.setChunkSize(10000);
        txnBuilder.build();

        // The writer attaches the on-disk metadata file (not a parsed object), so read it back like
        // ArbitraryDataWriter.validate() does.
        ArbitraryDataFile metadataFile = txnBuilder.getArbitraryDataFile().getMetadataFile();
        if (metadataFile == null)
            return null;
        ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
        metadata.read();
        return metadata;
    }

    private Path buildMultiFileResource(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        dir.toFile().deleteOnExit();
        // Deterministic, distinct content per file (tests must be deterministic; distinct content
        // avoids any identical-file edge cases).
        Files.write(Paths.get(dir.toString(), "index.html"),
                "<html><body>index page</body></html>".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        Files.write(Paths.get(dir.toString(), "about.html"),
                "<html><body>about page with different content</body></html>".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        return dir;
    }
}
