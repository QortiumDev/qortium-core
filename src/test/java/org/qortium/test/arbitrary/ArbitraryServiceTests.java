package org.qortium.test.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.misc.EncryptedDataEnvelope;
import org.qortium.arbitrary.misc.Service;
import org.qortium.arbitrary.misc.Service.ValidationResult;
import org.qortium.controller.arbitrary.ArbitraryDataManager;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.rating.ResourceRating;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryServiceTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testDefaultValidation() throws IOException {
        // We don't validate the ARBITRARY_DATA service specifically, so we can use it to test the default validation method
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write to temp path
        Path path = Files.createTempFile("testDefaultValidation", null);
        path.toFile().deleteOnExit();
        Files.write(path, data, StandardOpenOption.CREATE);

        Service service = Service.ARBITRARY_DATA;
        assertFalse(service.isValidationRequired());
        // Test validation anyway to ensure that no exception is thrown
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsite() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsite");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is an index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFile() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "data1.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithIndexFile() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data1.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithIndexFileOnly() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFile");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "index.html"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateWebsiteWithoutIndexFileInRoot() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateWebsiteWithoutIndexFileInRoot");
        path.toFile().deleteOnExit();
        Files.createDirectories(Paths.get(path.toString(), "directory"));
        Files.write(Paths.get(path.toString(), "directory", "index.html"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data2"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "data3"), data, StandardOpenOption.CREATE);

        Service service = Service.WEBSITE;
        assertTrue(service.isValidationRequired());

        // There is no index file in the root
        assertEquals(ValidationResult.MISSING_INDEX_FILE, service.validate(path));
    }

    @Test
    public void testValidateGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateSingleFileGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to a single file in a temp path
        Path path = Files.createTempDirectory("testValidateSingleFileGifRepository");
        path.toFile().deleteOnExit();
        Path imagePath = Paths.get(path.toString(), "image1.gif");
        Files.write(imagePath, data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(imagePath));
    }

    @Test
    public void testValidateMultiLayerGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiLayerGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);

        Path subdirectory = Paths.get(path.toString(), "subdirectory");
        Files.createDirectories(subdirectory);
        Files.write(Paths.get(subdirectory.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(subdirectory.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.DIRECTORIES_NOT_ALLOWED, service.validate(path));
    }

    @Test
    public void testValidateEmptyGifRepository() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyGifRepository");

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.MISSING_DATA, service.validate(path));
    }

    @Test
    public void testValidateInvalidGifRepository() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidGifRepository");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "image3.jpg"), data, StandardOpenOption.CREATE); // Invalid extension

        Service service = Service.GIF_REPOSITORY;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_EXTENSION, service.validate(path));
    }

    @Test
    public void testValidateImageGallery() throws IOException {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        Path path = Files.createTempDirectory("testValidateImageGallery");
        path.toFile().deleteOnExit();
        // A mix of allowed image extensions
        Files.write(Paths.get(path.toString(), "photo1.png"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "photo2.jpg"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "photo3.webp"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "photo4.gif"), data, StandardOpenOption.CREATE);

        Service service = Service.IMAGE_GALLERY;
        assertFalse("IMAGE_GALLERY is a multi-file service", service.isSingle());
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateSingleFileImageGallery() throws IOException {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        Path path = Files.createTempDirectory("testValidateSingleFileImageGallery");
        path.toFile().deleteOnExit();
        Path imagePath = Paths.get(path.toString(), "photo1.png");
        Files.write(imagePath, data, StandardOpenOption.CREATE);

        assertEquals(ValidationResult.OK, Service.IMAGE_GALLERY.validate(imagePath));
    }

    @Test
    public void testValidateImageGalleryRejectsNonImage() throws IOException {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        Path path = Files.createTempDirectory("testValidateImageGalleryRejectsNonImage");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "photo1.png"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "notes.txt"), data, StandardOpenOption.CREATE); // not an image

        assertEquals(ValidationResult.INVALID_FILE_EXTENSION, Service.IMAGE_GALLERY.validate(path));
    }

    @Test
    public void testValidateImageGalleryRejectsSubdirectory() throws IOException {
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        Path path = Files.createTempDirectory("testValidateImageGalleryRejectsSubdirectory");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "photo1.png"), data, StandardOpenOption.CREATE);
        Path subdirectory = Paths.get(path.toString(), "subdirectory");
        Files.createDirectories(subdirectory);
        Files.write(Paths.get(subdirectory.toString(), "photo2.png"), data, StandardOpenOption.CREATE);

        assertEquals(ValidationResult.DIRECTORIES_NOT_ALLOWED, Service.IMAGE_GALLERY.validate(path));
    }

    @Test
    public void testValidateEmptyImageGallery() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyImageGallery");
        path.toFile().deleteOnExit();

        assertEquals(ValidationResult.MISSING_DATA, Service.IMAGE_GALLERY.validate(path));
    }

    @Test
    public void testMediaServicesAreNowMultiFile() throws IOException {
        // The public media/document services were flipped to multi-file (single == false), so a
        // directory of files is accepted. They have no further structural validation.
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        for (Service service : new Service[] { Service.VIDEO, Service.AUDIO, Service.DOCUMENT, Service.PODCAST }) {
            assertFalse(service.name() + " should be multi-file", service.isSingle());

            Path path = Files.createTempDirectory("multiFile-" + service.name());
            path.toFile().deleteOnExit();
            Files.write(Paths.get(path.toString(), "main.bin"), data, StandardOpenOption.CREATE);
            Files.write(Paths.get(path.toString(), "sidecar.srt"), data, StandardOpenOption.CREATE);

            assertEquals(service.name() + " should accept a multi-file directory",
                    ValidationResult.OK, service.validate(path));
        }
    }

    @Test
    public void testPrivateMediaServicesRemainSingleFile() {
        // The private variants deliberately stay single-file: their privacy relies on single-file
        // pre-encryption validation, which does not extend to multi-file resources yet.
        assertTrue(Service.VIDEO_PRIVATE.isSingle());
        assertTrue(Service.AUDIO_PRIVATE.isSingle());
        assertTrue(Service.DOCUMENT_PRIVATE.isSingle());
    }

    /** A minimal structurally-valid v1 encrypted envelope. */
    private static byte[] encryptedEnvelope() {
        byte[] out = new byte[EncryptedDataEnvelope.FIXED_HEADER_LENGTH + 44 + 64];
        out[0] = 'Q'; out[1] = 'E'; out[2] = 'N'; out[3] = 'C';
        out[4] = EncryptedDataEnvelope.VERSION_1;
        out[5] = EncryptedDataEnvelope.MODE_SINGLE_RECIPIENT;
        out[6] = EncryptedDataEnvelope.CIPHER_AES_256_GCM;
        out[8] = 0; out[9] = 44; // headerLen = 44
        return out;
    }

    private static Path singleFileResource(String prefix, byte[] content) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        dir.toFile().deleteOnExit();
        Path file = Paths.get(dir.toString(), "data");
        Files.write(file, content, StandardOpenOption.CREATE);
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    public void testPrivateServiceAcceptsEncryptedEnvelope() throws IOException {
        Path file = singleFileResource("privEnvelope", encryptedEnvelope());
        assertEquals(ValidationResult.OK, Service.IMAGE_PRIVATE.validate(file));
    }

    @Test
    public void testPrivateServiceAcceptsLegacyPrefix() throws IOException {
        byte[] legacy = (EncryptedDataEnvelope.LEGACY_PREFIX + "base64ciphertexthere")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = singleFileResource("privLegacy", legacy);
        assertEquals(ValidationResult.OK, Service.IMAGE_PRIVATE.validate(file));
    }

    @Test
    public void testPrivateServiceRejectsPlaintext() throws IOException {
        byte[] plaintext = "definitely not encrypted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = singleFileResource("privPlaintext", plaintext);
        assertEquals(ValidationResult.DATA_NOT_ENCRYPTED, Service.IMAGE_PRIVATE.validate(file));
    }

    @Test
    public void testPublicServiceRejectsEncryptedData() throws IOException {
        Path file = singleFileResource("pubEncrypted", encryptedEnvelope());
        assertEquals(ValidationResult.DATA_ENCRYPTED, Service.IMAGE.validate(file));
    }

    @Test
    public void testValidatePublishedGifRepository() throws IOException, DataException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Generate some random data
            byte[] data = new byte[1024];
            new Random().nextBytes(data);

            // Write the data to several files in a temp path
            Path path = Files.createTempDirectory("testValidateGifRepository");
            path.toFile().deleteOnExit();
            Files.write(Paths.get(path.toString(), "image1.gif"), data, StandardOpenOption.CREATE);
            Files.write(Paths.get(path.toString(), "image2.gif"), data, StandardOpenOption.CREATE);
            Files.write(Paths.get(path.toString(), "image3.gif"), data, StandardOpenOption.CREATE);

            Service service = Service.GIF_REPOSITORY;
            assertTrue(service.isValidationRequired());

            assertEquals(ValidationResult.OK, service.validate(path));

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = "test_identifier";

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, path, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice);

            // Build the latest data state for this name, and no exceptions should be thrown because validation passes
            ArbitraryDataReader arbitraryDataReader1a = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader1a.loadSynchronously(true);
        }
    }

    @Test
    public void testValidateQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateQChatAttachment");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "document.pdf"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(path));
    }

    @Test
    public void testValidateSingleFileQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateSingleFileQChatAttachment");
        path.toFile().deleteOnExit();
        Path filePath = Paths.get(path.toString(), "document.pdf");
        Files.write(filePath, data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testValidateInvalidQChatAttachmentFileExtension() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidQChatAttachmentFileExtension");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "application.exe"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_EXTENSION, service.validate(path));
    }

    @Test
    public void testValidateEmptyQChatAttachment() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyQChatAttachment");

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidateMultiLayerQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiLayerQChatAttachment");
        path.toFile().deleteOnExit();

        Path subdirectory = Paths.get(path.toString(), "subdirectory");
        Files.createDirectories(subdirectory);
        Files.write(Paths.get(subdirectory.toString(), "file.txt"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidateMultiFileQChatAttachment() throws IOException {
        // Generate some random data
        byte[] data = new byte[1024];
        new Random().nextBytes(data);

        // Write the data to several files in a temp path
        Path path = Files.createTempDirectory("testValidateMultiFileQChatAttachment");
        path.toFile().deleteOnExit();
        Files.write(Paths.get(path.toString(), "file1.txt"), data, StandardOpenOption.CREATE);
        Files.write(Paths.get(path.toString(), "file2.txt"), data, StandardOpenOption.CREATE);

        Service service = Service.QCHAT_ATTACHMENT;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidatePublishedQChatAttachment() throws IOException, DataException, MissingDataException, IllegalAccessException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Generate some random data
            byte[] data = new byte[1024];
            new Random().nextBytes(data);

            // Write the data a single file in a temp path
            Path path = Files.createTempDirectory("testValidateSingleFileQChatAttachment");
            path.toFile().deleteOnExit();
            Path filePath = Paths.get(path.toString(), "document.pdf");
            Files.write(filePath, data, StandardOpenOption.CREATE);

            Service service = Service.QCHAT_ATTACHMENT;
            assertTrue(service.isValidationRequired());

            assertEquals(ValidationResult.OK, service.validate(filePath));

            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String publicKey58 = Base58.encode(alice.getPublicKey());
            String name = "TEST"; // Can be anything for this test
            String identifier = "test_identifier";

            // Register the name to Alice
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "");
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Set difficulty to 1
            FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);

            // Create PUT transaction
            ArbitraryUtils.createAndMintTxn(repository, publicKey58, filePath, name, identifier, ArbitraryTransactionData.Method.PUT, service, alice);

            // Build the latest data state for this name, and no exceptions should be thrown because validation passes
            ArbitraryDataReader arbitraryDataReader1a = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
            arbitraryDataReader1a.loadSynchronously(true);
        }
    }

    @Test
    public void testValidateValidJson() throws IOException {
        String invalidJsonString = "{\"test\": true, \"test2\": \"valid\"}";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateValidJson");
        Path filePath = Paths.get(path.toString(), "test.json");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(invalidJsonString);
        writer.close();

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }
    @Test
    public void testValidateInvalidJson() throws IOException {
        String invalidJsonString = "{\"test\": true, \"test2\": invalid}";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidateInvalidJson");
        Path filePath = Paths.get(path.toString(), "test.json");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(invalidJsonString);
        writer.close();

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_CONTENT, service.validate(filePath));
    }

    @Test
    public void testValidateEmptyJson() throws IOException {
        Path path = Files.createTempDirectory("testValidateEmptyJson");

        Service service = Service.JSON;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.INVALID_FILE_COUNT, service.validate(path));
    }

    @Test
    public void testValidPrivateData() throws IOException {
        String dataString = "qdnEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testValidPrivateGroupData() throws IOException {
        String dataString = "qdnGroupEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());

        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testEncryptedData() throws IOException {
        String dataString = "qdnEncryptedDatabMx4fELNTV+ifJxmv4+GcuOIJOTo+3qAvbWKNY2L1rfla5UBoEcoxbtjgZ9G7FLPb8V/Qfr0bfKWfvMmN06U/pgUdLuv2mGL2V0D3qYd1011MUzGdNG1qERjaCDz8GAi63+KnHHjfMtPgYt6bcqjs4CNV+ZZ4dIt3xxHYyVEBNc=";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testValidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        // Validate a private service
        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(filePath));

        // Validate a regular service
        service = Service.FILE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.DATA_ENCRYPTED, service.validate(filePath));
    }

    @Test
    public void testPlainTextData() throws IOException {
        String dataString = "plaintext";

        // Write the data a single file in a temp path
        Path path = Files.createTempDirectory("testInvalidPrivateData");
        Path filePath = Paths.get(path.toString(), "test");
        filePath.toFile().deleteOnExit();

        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()));
        writer.write(dataString);
        writer.close();

        // Validate a private service
        Service service = Service.FILE_PRIVATE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.DATA_NOT_ENCRYPTED, service.validate(filePath));

        // Validate a regular service
        service = Service.FILE;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(filePath));
    }

    @Test
    public void testGetPrivateServices() {
        List<Service> privateServices = Service.privateServices();
        for (Service service : privateServices) {
            assertTrue(service.isPrivate());
        }
    }

    @Test
    public void testGetPublicServices() {
        List<Service> publicServices = Service.publicServices();
        for (Service service : publicServices) {
            assertFalse(service.isPrivate());
        }
    }

    @Test
    public void testResourceRatingServices() {
        assertTrue(ResourceRating.isRateableService(Service.APP));
        assertTrue(ResourceRating.isRateableService(Service.WEBSITE));
        assertTrue(ResourceRating.isRateableService(Service.PLUGIN));
        assertTrue(ResourceRating.isRateableService(Service.EXTENSION));
        assertTrue(ResourceRating.isRateableService(Service.GAME));
        assertTrue(ResourceRating.isRateableService(Service.DOCUMENT));

        assertFalse(ResourceRating.isRateableService(Service.FILE_PRIVATE));
        assertFalse(ResourceRating.isRateableService(Service.AUTO_UPDATE));
        assertFalse(ResourceRating.isRateableService(Service.AUTO_UPDATE_BINARY));
        assertFalse(ResourceRating.isRateableService(Service.ARBITRARY_DATA));
    }

    @Test
    public void testValidateChainCommentIgnoresQdnMetadata() throws IOException {
        Path path = Files.createTempDirectory("testValidateChainCommentIgnoresQdnMetadata");
        path.toFile().deleteOnExit();

        // Create a 200-byte comment file (well under the 239-byte limit)
        byte[] commentData = new byte[200];
        Arrays.fill(commentData, (byte) 'a');
        Files.write(Paths.get(path.toString(), "comment"), commentData, StandardOpenOption.CREATE);

        // Add .qdn metadata directory with cache file (512 bytes)
        // This simulates the metadata that gets created during the build process
        Path qdnPath = Paths.get(path.toString(), ".qdn");
        Files.createDirectories(qdnPath);
        byte[] metadata = new byte[512];
        Arrays.fill(metadata, (byte) 'b');
        Files.write(Paths.get(qdnPath.toString(), "cache"), metadata, StandardOpenOption.CREATE);

        // Total on disk: 200 + 512 = 712 bytes
        // But validation should only count the 200 bytes of user data
        Service service = Service.CHAIN_COMMENT;
        assertTrue(service.isValidationRequired());
        assertEquals(ValidationResult.OK, service.validate(path));
    }

}
