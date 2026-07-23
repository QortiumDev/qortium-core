package org.qortium.arbitrary;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.arbitrary.ArbitraryDataFile.ValidationResult;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortium.arbitrary.misc.Category;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.AES;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.ArbitraryTransactionData.Compression;
import org.qortium.data.transaction.ArbitraryTransactionData.Method;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;
import org.qortium.utils.FilesystemUtils;
import org.qortium.utils.ZipUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArbitraryDataWriter {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataWriter.class);

    private Path filePath;
    private final String name;
    private final Service service;
    private final String identifier;
    private final Method method;
    private final Compression compression;

    // Metadata
    private final String title;
    private final String description;
    private final List<String> tags;
    private final Category category;
    private final String entryPoint;
    private List<String> files;
    private String mimeType;

    private int chunkSize = ArbitraryDataFile.CHUNK_SIZE;

    private SecretKey aesKey;
    private ArbitraryDataFile arbitraryDataFile;

    // Intermediate paths to cleanup
    private Path workingPath;
    private Path compressedPath;
    private Path encryptedPath;

    public ArbitraryDataWriter(Path filePath, String name, Service service, String identifier, Method method, Compression compression,
                               String title, String description, List<String> tags, Category category) {
        this(filePath, name, service, identifier, method, compression, title, description, tags, category, null);
    }

    public ArbitraryDataWriter(Path filePath, String name, Service service, String identifier, Method method, Compression compression,
                               String title, String description, List<String> tags, Category category, String entryPoint) {
        this.filePath = filePath;
        this.name = name;
        this.service = service;
        this.method = method;
        this.compression = compression;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.isEmpty() || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;

        // Metadata (optional)
        this.title = ArbitraryDataTransactionMetadata.limitTitle(title);
        this.description = ArbitraryDataTransactionMetadata.limitDescription(description);
        this.tags = ArbitraryDataTransactionMetadata.limitTags(tags);
        this.category = category;

        // entryPoint (optional). Normalize empty/blank to null and standardise path separators.
        if (entryPoint == null || entryPoint.isBlank()) {
            this.entryPoint = null;
        } else {
            this.entryPoint = entryPoint.replace('\\', '/');
        }

        this.files = new ArrayList<>(); // Populated in buildFileList()
        this.mimeType = null; // Populated in buildFileList()
    }

    public void save() throws IOException, DataException, InterruptedException, MissingDataException {
        boolean success = false;
        try {
            this.preExecute();
            this.validateService();
            this.buildFileList();
            this.validateEntryPoint();
            this.process();
            this.compress();
            this.encrypt();
            this.validateEncryptedSize();
            this.split();
            this.createMetadataFile();
            this.validate();
            success = true;

        } finally {
            this.postExecute();
            if (!success) {
                // split()/createMetadataFile() write pre-broadcast chunks into
                // data/_misc, which nothing else deletes on a failed save — the
                // transaction builder never sees this writer's ArbitraryDataFile
                // when save() throws. Without this, every rejected publish
                // orphans up to the full resource size on disk.
                this.cleanupDataFile();
            }
        }
    }

    private void cleanupDataFile() {
        if (this.arbitraryDataFile == null) {
            return;
        }

        try {
            this.arbitraryDataFile.deleteAll(true);
        } catch (Exception e) {
            LOGGER.warn("Unable to clean up arbitrary data file after failed save: {}", e.getMessage());
        }
    }

    private void preExecute() throws DataException {
        this.checkEnabled();

        // Enforce compression when uploading multiple files
        if (!FilesystemUtils.isSingleFileResource(this.filePath, false) && compression == Compression.NONE) {
            throw new DataException("Unable to publish multiple files without compression");
        }

        // Create temporary working directory
        this.createWorkingDirectory();
    }

    private void postExecute() throws IOException {
        this.cleanupFilesystem();
    }

    private void checkEnabled() throws DataException {
        if (!Settings.getInstance().isQdnEnabled()) {
            throw new DataException("QDN is disabled in settings");
        }
    }

    private void createWorkingDirectory() throws DataException {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        String identifier = Base58.encode(Crypto.digest(this.filePath.toString().getBytes()));
        Path tempDir = Paths.get(baseDir, "writer", identifier);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new DataException("Unable to create temp directory");
        }
        this.workingPath = tempDir;
    }

    private void validateService() throws IOException, DataException {
        if (this.service.isValidationRequired()) {
            Service.ValidationResult result = this.service.validate(this.filePath);
            if (result != Service.ValidationResult.OK) {
                if (result == Service.ValidationResult.EXCEEDS_SIZE_LIMIT) {
                    long measuredSize = FilesystemUtils.getDirectorySize(this.filePath, true);
                    long fileCount;
                    try (Stream<Path> stream = Files.walk(this.filePath)) {
                        fileCount = stream.filter(Files::isRegularFile).count();
                    }

                    LOGGER.warn("Service validation size failure for {}: path={}, exists={}, isFile={}, isDirectory={}, fileCount={}, measuredSize={}, maxSize={}",
                            this.service,
                            this.filePath,
                            Files.exists(this.filePath),
                            Files.isRegularFile(this.filePath),
                            Files.isDirectory(this.filePath),
                            fileCount,
                            measuredSize,
                            this.service.getMaxSize());

                    throw new DataException(String.format("Validation of %s failed: %s (path=%s, measuredSize=%d, maxSize=%d, fileCount=%d)",
                            this.service,
                            result,
                            this.filePath,
                            measuredSize,
                            this.service.getMaxSize(),
                            fileCount));
                }

                throw new DataException(String.format("Validation of %s failed: %s", this.service, result.toString()));
            }
        }
    }

    private void buildFileList() throws IOException {
        // Check if the path already points to a single file
        boolean isSingleFile = this.filePath.toFile().isFile();
        Path singleFilePath = null;
        if (isSingleFile) {
            this.files.add(this.filePath.getFileName().toString());
            singleFilePath = this.filePath;
        }
        else {
            // Multi file resources (or a single file in a directory) require a walk through the directory tree
            try (Stream<Path> stream = Files.walk(this.filePath)) {
                this.files = stream
                        .filter(Files::isRegularFile)
                        .map(p -> this.filePath.relativize(p).toString())
                        .filter(s -> !s.isEmpty())
                        // Sort for a deterministic file order, so publishing the same directory is
                        // reproducible (stable metadata file list, independent of filesystem walk order).
                        .sorted()
                        .collect(Collectors.toList());

                if (this.files.size() == 1) {
                    singleFilePath = Paths.get(this.filePath.toString(), this.files.get(0));

                    // Update filePath to point to the single file (instead of the directory containing the file)
                    this.filePath = singleFilePath;
                }
            }
        }

        if (singleFilePath != null) {
            // Single file resource, so try and determine the MIME type
            this.mimeType = determineSingleFileMimeType(singleFilePath.toFile());
        }
    }

    /**
     * The MIME type a single-file resource should be published with.
     * <p>
     * The publisher-chosen filename is the stronger signal: content sniffing is a
     * heuristic meant for unnamed byte streams, and trusting it first mislabels
     * real files whose leading bytes happen to match some magic signature (a .txt
     * that starts with "P5" is not a greymap image). Sniffing is only consulted
     * when the filename's extension is missing or unrecognized.
     */
    static String determineSingleFileMimeType(File file) throws IOException {
        FileNameMap fileNameMap = URLConnection.getFileNameMap();
        String nameMimeType = fileNameMap.getContentTypeFor(file.getName());
        if (nameMimeType != null && !nameMimeType.isBlank()) {
            return nameMimeType;
        }

        ContentInfoUtil util = new ContentInfoUtil();
        ContentInfo info = util.findMatch(file);
        String detectedMimeType = info == null ? null : info.getMimeType();
        if (detectedMimeType != null && !detectedMimeType.isBlank()) {
            return detectedMimeType;
        }

        return null;
    }

    private void validateEntryPoint() throws DataException {
        // entryPoint is optional. When supplied it must reference a file present in the resource's file list.
        if (this.entryPoint == null) {
            return;
        }

        // Compare using normalised (forward-slash) path separators, since buildFileList()
        // may store OS-dependent separators for directory publishes.
        boolean found = false;
        if (this.files != null) {
            for (String file : this.files) {
                if (file != null && file.replace('\\', '/').equals(this.entryPoint)) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            throw new DataException(String.format("entryPoint '%s' does not match any file in the resource", this.entryPoint));
        }
    }

    private void process() throws DataException, IOException, MissingDataException {
        switch (this.method) {

            case PUT:
                // Nothing to do
                break;

            case PATCH:
                throw new DataException(String.format("Unsupported method specified: %s", method.toString()));

            default:
                throw new DataException(String.format("Unknown method specified: %s", method.toString()));
        }
    }

    private void processPatch() throws DataException, IOException, MissingDataException {

        // Build the existing state using past transactions
        ArbitraryDataBuilder builder = new ArbitraryDataBuilder(this.name, this.service, this.identifier);
        builder.build();
        Path builtPath = builder.getFinalPath();

        // Obtain the latest signature, so this can be included in the patch
        byte[] latestSignature = builder.getLatestSignature();

        // Compute a diff of the latest changes on top of the previous state
        // Then use only the differences as our data payload
        ArbitraryDataCreatePatch patch = new ArbitraryDataCreatePatch(builtPath, this.filePath, latestSignature);
        patch.create();
        this.filePath = patch.getFinalPath();

        // Delete the input directory
        if (FilesystemUtils.pathInsideDataOrTempPath(builtPath)) {
            File directory = new File(builtPath.toString());
            FileUtils.deleteDirectory(directory);
        }

        // Validate the patch
        this.validatePatch();
    }

    private void validatePatch() throws DataException {
        if (this.filePath == null) {
            throw new DataException("Null path after creating patch");
        }

        File qdnMetadataDirectoryFile = Paths.get(this.filePath.toString(), ".qdn").toFile();
        if (!qdnMetadataDirectoryFile.exists()) {
            throw new DataException("QDN metadata folder doesn't exist in patch");
        }
        if (!qdnMetadataDirectoryFile.isDirectory()) {
            throw new DataException("QDN metadata folder isn't a directory");
        }

        File qdnPatchMetadataFile = Paths.get(this.filePath.toString(), ".qdn", "patch").toFile();
        if (!qdnPatchMetadataFile.exists()) {
            throw new DataException("QDN patch metadata file doesn't exist in patch");
        }
        if (!qdnPatchMetadataFile.isFile()) {
            throw new DataException("QDN patch metadata file isn't a file");
        }
    }

    private void compress() throws InterruptedException, DataException {
        // Compress the data if requested
        if (this.compression != Compression.NONE) {
            this.compressedPath = Paths.get(this.workingPath.toString(), "data.zip");
            try {

                if (this.compression == Compression.ZIP) {
                    LOGGER.info("Compressing...");
                    String enclosingFolderName = "data";
                    ZipUtils.zip(this.filePath.toString(), this.compressedPath.toString(), enclosingFolderName);
                }
                else {
                    throw new DataException(String.format("Unknown compression type specified: %s", compression.toString()));
                }
                // FUTURE: other compression types

                // Delete the input path. The normalize + prefix check is inlined
                // (equivalent to pathInsideDataOrTempPath) so the guard on this
                // user-influenced path is visible to static analysis.
                Path dataPath = Paths.get(Settings.getInstance().getDataPath()).toAbsolutePath().normalize();
                Path tempDataPath = Paths.get(Settings.getInstance().getTempDataPath()).toAbsolutePath().normalize();
                Path inputPath = this.filePath.toAbsolutePath().normalize();
                if (inputPath.startsWith(dataPath) || inputPath.startsWith(tempDataPath)) {
                    if (Files.isDirectory(inputPath)) {
                        FileUtils.deleteDirectory(inputPath.toFile());
                    }
                    else {
                        Files.deleteIfExists(inputPath);
                    }
                }
                // Replace filePath pointer with the zipped file path
                this.filePath = this.compressedPath;

            } catch (IOException | DataException e) {
                throw new DataException("Unable to zip directory", e);
            }
        }
    }

    private void encrypt() throws DataException {
        this.encryptedPath = Paths.get(this.workingPath.toString(), "data.zip.encrypted");
        try {
            // Encrypt the file with AES
            LOGGER.info("Encrypting...");
            this.aesKey = AES.generateKey(256);
            AES.encryptFile(this.aesKey, this.filePath.toString(), this.encryptedPath.toString());

            // Delete the input file
            if (FilesystemUtils.pathInsideDataOrTempPath(this.filePath)) {
                Files.delete(this.filePath);
            }
            // Replace filePath pointer with the encrypted file path
            this.filePath = this.encryptedPath;

        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException
                | BadPaddingException | IllegalBlockSizeException | IOException | InvalidKeyException e) {
            throw new DataException(String.format("Unable to encrypt file %s: %s", this.filePath, e.getMessage()));
        }
    }

    private void validateEncryptedSize() throws IOException, DataException {
        // The final payload size is known once compression + encryption are done,
        // so reject an over-limit resource here — before split() writes its chunks
        // to data/_misc. isValid() re-checks this after splitting as a backstop.
        long encryptedSize = Files.size(this.filePath);
        if (encryptedSize > ArbitraryDataFile.MAX_FILE_SIZE) {
            throw new DataException(String.format("Resource is too large after compression and encryption: %d bytes (max size: %d bytes)",
                    encryptedSize, ArbitraryDataFile.MAX_FILE_SIZE));
        }
    }

    private void split() throws IOException, DataException {
        // We don't have a signature yet, so use null to put the file in a generic folder
        this.arbitraryDataFile = ArbitraryDataFile.fromPath(this.filePath, null);
        if (this.arbitraryDataFile == null) {
            throw new IOException("No file available when trying to split");
        }

        int chunkCount = this.arbitraryDataFile.split(this.chunkSize);
        if (chunkCount > 0) {
            LOGGER.info(String.format("Successfully split into %d chunk%s", chunkCount, (chunkCount == 1 ? "" : "s")));
        }
    }

    private void createMetadataFile() throws IOException, DataException {
        // If we have at least one chunk, we need to create an index file containing their hashes
        if (this.needsMetadataFile()) {
            // Create the JSON file
            Path chunkFilePath = Paths.get(this.workingPath.toString(), "metadata.json");
            ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(chunkFilePath);
            metadata.setTitle(this.title);
            metadata.setDescription(this.description);
            metadata.setTags(this.tags);
            metadata.setCategory(this.category);
            metadata.setChunks(this.arbitraryDataFile.chunkHashList());
            metadata.setFiles(this.files);
            metadata.setMimeType(this.mimeType);
            metadata.setEntryPoint(this.entryPoint);
            metadata.write();

            // Create an ArbitraryDataFile from the JSON file (we don't have a signature yet)
            ArbitraryDataFile metadataFile = ArbitraryDataFile.fromPath(chunkFilePath, null);
            this.arbitraryDataFile.setMetadataFile(metadataFile);
        }
    }

    private void validate() throws IOException, DataException {
        if (this.arbitraryDataFile == null) {
            throw new DataException("No file available when validating");
        }
        this.arbitraryDataFile.setSecret(this.aesKey.getEncoded());

        // Validate the file
        ValidationResult validationResult = this.arbitraryDataFile.isValid();
        if (validationResult != ValidationResult.OK) {
            throw new DataException(String.format("File %s failed validation: %s", this.arbitraryDataFile, validationResult));
        }
        LOGGER.info("Whole file hash is valid: {}", this.arbitraryDataFile.digest58());

        // Validate each chunk
        for (ArbitraryDataFileChunk chunk : this.arbitraryDataFile.getChunks()) {
            validationResult = chunk.isValid();
            if (validationResult != ValidationResult.OK) {
                throw new DataException(String.format("Chunk %s failed validation: %s", chunk, validationResult));
            }
        }
        LOGGER.info("Chunk hashes are valid");

        // Validate chunks metadata file
        if (this.arbitraryDataFile.chunkCount() > 1) {
            ArbitraryDataFile metadataFile = this.arbitraryDataFile.getMetadataFile();
            if (metadataFile == null || !metadataFile.exists()) {
                throw new DataException("No metadata file available, but there are multiple chunks");
            }
            // Read the file
            ArbitraryDataTransactionMetadata metadata = new ArbitraryDataTransactionMetadata(metadataFile.getFilePath());
            metadata.read();
            // Check all chunks exist
            for (byte[] chunk : this.arbitraryDataFile.chunkHashList()) {
                if (!metadata.containsChunk(chunk)) {
                    throw new DataException(String.format("Missing chunk %s in metadata file", Base58.encode(chunk)));
                }
            }

            // Check that the metadata is correct
            if (!Objects.equals(metadata.getTitle(), this.title)) {
                throw new DataException("Metadata mismatch: title");
            }
            if (!Objects.equals(metadata.getDescription(), this.description)) {
                throw new DataException("Metadata mismatch: description");
            }
            if (!Objects.equals(metadata.getTags(), this.tags)) {
                throw new DataException("Metadata mismatch: tags");
            }
            if (!Objects.equals(metadata.getCategory(), this.category)) {
                throw new DataException("Metadata mismatch: category");
            }
            if (!Objects.equals(metadata.getEntryPoint(), this.entryPoint)) {
                throw new DataException("Metadata mismatch: entryPoint");
            }
        }
    }

    private void cleanupFilesystem() throws IOException {
        // Clean up
        if (FilesystemUtils.pathInsideDataOrTempPath(this.compressedPath)) {
            File zippedFile = new File(this.compressedPath.toString());
            if (zippedFile.exists()) {
                zippedFile.delete();
            }
        }
        if (FilesystemUtils.pathInsideDataOrTempPath(this.encryptedPath)) {
            File encryptedFile = new File(this.encryptedPath.toString());
            if (encryptedFile.exists()) {
                encryptedFile.delete();
            }
        }
        if (FilesystemUtils.pathInsideDataOrTempPath(this.workingPath)) {
            FileUtils.deleteDirectory(new File(this.workingPath.toString()));
        }
    }

    private boolean needsMetadataFile() {
        if (this.arbitraryDataFile.chunkCount() > 1) {
            return true;
        }
        // A multi-file (directory) resource needs a metadata file so consumers can
        // enumerate its files. Larger resources already get one via the chunk-count
        // check above, but a small directory that fits in a single chunk would
        // otherwise be published with no file manifest, leaving viewers unable to
        // list its contents.
        if (this.files != null && this.files.size() > 1) {
            return true;
        }
        if (this.title != null || this.description != null || this.tags != null || this.category != null) {
            return true;
        }
        if (this.entryPoint != null) {
            return true;
        }
        return false;
    }


    public ArbitraryDataFile getArbitraryDataFile() {
        return this.arbitraryDataFile;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

}
