package org.qortium.arbitrary.misc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FilenameUtils;
import org.json.JSONObject;
import org.qortium.arbitrary.ArbitraryDataRenderer;
import org.qortium.transaction.Transaction;
import org.qortium.utils.FilesystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public enum Service {
    AUTO_UPDATE(1, false, null, false, false, null),
    AUTO_UPDATE_BINARY(2, false, null, true, false, null),
    ARBITRARY_DATA(100, false, null, false, false, null),
    QCHAT_ATTACHMENT(120, true, 1024*1024L, true, false, null) {
        @Override
        public ValidationResult validate(Path path) throws IOException {
            ValidationResult superclassResult = super.validate(path);
            if (superclassResult != ValidationResult.OK) {
                return superclassResult;
            }

            File[] files = path.toFile().listFiles();
            // If already a single file, replace the list with one that contains that file only
            if (files == null && path.toFile().isFile()) {
                files = new File[] { path.toFile() };
            }
            // Now validate the file's extension
            if (files != null && files[0] != null) {
                final String extension = FilenameUtils.getExtension(files[0].getName()).toLowerCase();
                // We must allow blank file extensions because these are used by data published from a plaintext or base64-encoded string
                final List<String> allowedExtensions = Arrays.asList("qdn", "zip", "pdf", "txt", "odt", "ods", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "");
                if (extension == null || !allowedExtensions.contains(extension)) {
                    return ValidationResult.INVALID_FILE_EXTENSION;
                }
            }
            return ValidationResult.OK;
        }
    },
    QCHAT_ATTACHMENT_PRIVATE(121, true, 1024*1024L, true, true, null),
    ATTACHMENT(130, false, 50*1024*1024L, true, false, null),
    ATTACHMENT_PRIVATE(131, true, 50*1024*1024L, true, true, null),
    FILE(140, false, null, true, false, null),
    FILE_PRIVATE(141, true, null, true, true, null),
    FILES(150, false, null, false, false, null),
    // Private variants store the whole resource as one client-encrypted archive (single blob), so
    // they are single=true and rely on the default validator to enforce the encryption envelope.
    FILES_PRIVATE(151, true, null, true, true, null),
    CHAIN_DATA(160, true, 239L, true, false, null),
    WEBSITE(200, true, null, false, false, null) {
        @Override
        public ValidationResult validate(Path path) throws IOException {
            ValidationResult superclassResult = super.validate(path);
            if (superclassResult != ValidationResult.OK) {
                return superclassResult;
            }

            // Custom validation function to require an index HTML file in the root directory
            List<String> fileNames = ArbitraryDataRenderer.indexFiles();
            List<String> files;

            // single files are paackaged differently
            if( path.toFile().isFile() ) {
                files = new ArrayList<>(1);
                files.add(path.getFileName().toString());
            }
            else {
                files = new ArrayList<>(Arrays.asList(path.toFile().list()));
            }

            if (files != null) {
                for (String file : files) {
                    Path fileName = Paths.get(file).getFileName();
                    if (fileName != null && fileNames.contains(fileName.toString())) {
                        return ValidationResult.OK;
                    }
                }
            }
            return ValidationResult.MISSING_INDEX_FILE;
        }
    },
    WEBSITE_PRIVATE(201, true, null, true, true, null),
    GIT_REPOSITORY(300, false, null, false, false, null),
    GIT_REPOSITORY_PRIVATE(301, true, null, true, true, null),
    IMAGE(400, true, 10*1024*1024L, true, false, null),
    IMAGE_PRIVATE(401, true, 10*1024*1024L, true, true, null),
    THUMBNAIL(410, true, 500*1024L, true, false, null),
    QCHAT_IMAGE(420, true, 500*1024L, true, false, null),
    IMAGE_GALLERY(430, true, 50*1024*1024L, false, false, null) {
        @Override
        public ValidationResult validate(Path path) throws IOException {
            ValidationResult superclassResult = super.validate(path);
            if (superclassResult != ValidationResult.OK) {
                return superclassResult;
            }

            // Custom validation function to require image files only, and at least 1
            final List<String> allowedExtensions = Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "tif", "tiff");
            int imageCount = 0;
            // Canonicalize the resource path up-front; all subsequent filesystem checks operate on
            // this canonical base, which acts as a path-traversal barrier for the validator.
            final File baseDir = path.toFile().getCanonicalFile();
            File[] files = baseDir.listFiles();
            // If already a single file, replace the list with one that contains that file only
            if (files == null && baseDir.isFile()) {
                files = new File[] { baseDir };
            }
            if (files != null) {
                for (File file : files) {
                    if (file.getName().equals(".qdn")) {
                        continue;
                    }
                    // Defense-in-depth: reject any entry that resolves outside the resource
                    // directory (e.g. a symlink), so we never stat an attacker-chosen path.
                    if (!FilesystemUtils.isWithinCanonical(baseDir, file)) {
                        return ValidationResult.INVALID_CONTENT;
                    }
                    if (file.isDirectory()) {
                        return ValidationResult.DIRECTORIES_NOT_ALLOWED;
                    }
                    String extension = FilenameUtils.getExtension(file.getName()).toLowerCase();
                    if (!allowedExtensions.contains(extension)) {
                        return ValidationResult.INVALID_FILE_EXTENSION;
                    }
                    imageCount++;
                }
            }
            if (imageCount == 0) {
                return ValidationResult.MISSING_DATA;
            }
            return ValidationResult.OK;
        }
    },
    IMAGE_GALLERY_PRIVATE(431, true, 50*1024*1024L, true, true, null),
    VIDEO(500, false, null, false, false, null),
    VIDEO_PRIVATE(501, true, null, true, true, null),
    AUDIO(600, false, null, false, false, null),
    AUDIO_PRIVATE(601, true, null, true, true, null),
    QCHAT_AUDIO(610, true, 10*1024*1024L, true, false, null),
    QCHAT_VOICE(620, true, 10*1024*1024L, true, false, null),
    VOICE(630, true, 10*1024*1024L, true, false, null),
    VOICE_PRIVATE(631, true, 10*1024*1024L, true, true, null),
    PODCAST(640, false, null, false, false, null),
    BLOG(700, false, null, false, false, null),
    BLOG_PRIVATE(701, true, null, true, true, null),
    BLOG_POST(777, false, null, true, false, null),
    BLOG_COMMENT(778, true, 500*1024L, true, false, null),
    DOCUMENT(800, false, null, false, false, null),
    DOCUMENT_PRIVATE(801, true, null, true, true, null),
    LIST(900, true, null, true, false, null),
    PLAYLIST(910, true, null, true, false, null),
    APP(1000, true, 50*1024*1024L, false, false, null),
    APP_PRIVATE(1001, true, 50*1024*1024L, true, true, null),
    METADATA(1100, false, null, true, false, null),
    JSON(1110, true, 25*1024L, true, false, null) {
        @Override
        public ValidationResult validate(Path path) throws IOException {
            ValidationResult superclassResult = super.validate(path);
            if (superclassResult != ValidationResult.OK) {
                return superclassResult;
            }

            // Require valid JSON
            byte[] data = FilesystemUtils.getSingleFileContents(path, 25*1024);
            String json = new String(data, StandardCharsets.UTF_8);
            try {
                objectMapper.readTree(json);
                return ValidationResult.OK;
            } catch (IOException e) {
                return ValidationResult.INVALID_CONTENT;
            }
        }
    },
    GIF_REPOSITORY(1200, true, 25*1024*1024L, false, false, null) {
        @Override
        public ValidationResult validate(Path path) throws IOException {
            ValidationResult superclassResult = super.validate(path);
            if (superclassResult != ValidationResult.OK) {
                return superclassResult;
            }

            // Custom validation function to require .gif files only, and at least 1
            int gifCount = 0;
            // Canonicalize the resource path up-front; all subsequent filesystem checks operate on
            // this canonical base, which acts as a path-traversal barrier for the validator.
            final File baseDir = path.toFile().getCanonicalFile();
            File[] files = baseDir.listFiles();
            // If already a single file, replace the list with one that contains that file only
            if (files == null && baseDir.isFile()) {
                files = new File[] { baseDir };
            }
            if (files != null) {
                for (File file : files) {
                    if (file.getName().equals(".qdn")) {
                        continue;
                    }
                    // Defense-in-depth: reject any entry that resolves outside the resource
                    // directory (e.g. a symlink), so we never stat an attacker-chosen path.
                    if (!FilesystemUtils.isWithinCanonical(baseDir, file)) {
                        return ValidationResult.INVALID_CONTENT;
                    }
                    if (file.isDirectory()) {
                        return ValidationResult.DIRECTORIES_NOT_ALLOWED;
                    }
                    String extension = FilenameUtils.getExtension(file.getName()).toLowerCase();
                    if (!Objects.equals(extension, "gif")) {
                        return ValidationResult.INVALID_FILE_EXTENSION;
                    }
                    gifCount++;
                }
            }
            if (gifCount == 0) {
                return ValidationResult.MISSING_DATA;
            }
            return ValidationResult.OK;
        }
    },
    STORE(1300, false, null, true, false, null),
    PRODUCT(1310, false, null, true, false, null),
    OFFER(1330, false, null, true, false, null),
    COUPON(1340, false, null, true, false, null),
    CODE(1400, false, null, true, false, null),
    PLUGIN(1410, false, null, true, false, null),
    EXTENSION(1420, false, null, true, false, null),
    GAME(1500, false, null, false, false, null),
    ITEM(1510, false, null, true, false, null),
    NFT(1600, false, null, true, false, null),
    DATABASE(1700, false, null, false, false, null),
    DATABASE_PRIVATE(1701, true, null, true, true, null),
    SNAPSHOT(1710, false, null, false, false, null),
    SNAPSHOT_PRIVATE(1711, true, null, true, true, null),
    COMMENT(1800, true, 500*1024L, true, false, null),
    CHAIN_COMMENT(1810, true, 239L, true, false, null),
    MAIL(1900, true, 1024*1024L, true, false, null),
    MAIL_PRIVATE(1901, true, 5*1024*1024L, true, true, null),
    MESSAGE(1910, true, 1024*1024L, true, false, null),
    MESSAGE_PRIVATE(1911, true, 1024*1024L, true, true, null);

    public final int value;
    private final boolean requiresValidation;
    private final Long maxSize;
    private final boolean single;
    private final boolean isPrivate;
    private final List<String> requiredKeys;

    private static final Map<Integer, Service> map = stream(Service.values())
            .collect(toMap(service -> service.value, service -> service));

    // For JSON validation
    private static final ObjectMapper objectMapper = new ObjectMapper();

    Service(int value, boolean requiresValidation, Long maxSize, boolean single, boolean isPrivate, List<String> requiredKeys) {
        this.value = value;
        this.requiresValidation = requiresValidation;
        this.maxSize = maxSize;
        this.single = single;
        this.isPrivate = isPrivate;
        this.requiredKeys = requiredKeys;
    }

    public ValidationResult validate(Path path) throws IOException {
        if (!this.isValidationRequired()) {
            return ValidationResult.OK;
        }

        // Load the first 25KB of data. This only needs to be long enough to check the prefix
        // and also to allow for possible additional future validation of smaller files.
        byte[] data = FilesystemUtils.getSingleFileContents(path, 25*1024);
        // Exclude .qdn metadata from size calculation - it's system metadata,
        // not user content, and shouldn't count against service size limits
        long size = FilesystemUtils.getDirectorySize(path, true);

        // Validate max size if needed
        if (this.maxSize != null) {
            if (size > this.maxSize) {
                return ValidationResult.EXCEEDS_SIZE_LIMIT;
            }
        }

        // Validate file count if needed
        if (this.single && data == null) {
            return ValidationResult.INVALID_FILE_COUNT;
        }

        // Validate private data for single file resources. Accept either the v1 encrypted envelope
        // or a legacy encryption prefix; reject plaintext for private services (and reject encrypted
        // data for public services).
        if (this.single) {
            boolean encrypted = EncryptedDataEnvelope.isEncrypted(data);
            if (this.isPrivate && !encrypted) {
                return ValidationResult.DATA_NOT_ENCRYPTED;
            }
            if (!this.isPrivate && encrypted) {
                return ValidationResult.DATA_ENCRYPTED;
            }
        }

        // Validate required keys if needed
        if (this.requiredKeys != null) {
            if (data == null) {
                return ValidationResult.MISSING_KEYS;
            }
            JSONObject json = Service.toJsonObject(data);
            for (String key : this.requiredKeys) {
                if (!json.has(key)) {
                    return ValidationResult.MISSING_KEYS;
                }
            }
        }

        // Validation passed
        return ValidationResult.OK;
    }

    public boolean isValidationRequired() {
        // We must always validate single file resources, to ensure they are actually a single file
        return this.requiresValidation || this.single;
    }

    public Long getMaxSize() {
        return this.maxSize;
    }

    public boolean isPrivate() {
        return this.isPrivate;
    }

    public boolean isSingle() {
        return this.single;
    }

    public static Service valueOf(int value) {
        return map.get(value);
    }

    public static JSONObject toJsonObject(byte[] data) {
        String dataString = new String(data, StandardCharsets.UTF_8);
        return new JSONObject(dataString);
    }

    public static List<Service> publicServices() {
        List<Service> privateServices = new ArrayList<>();
        for (Service service : Service.values()) {
            if (!service.isPrivate) {
                privateServices.add(service);
            }
        }
        return privateServices;
    }

    /**
     * Fetch a list of Service objects that require encrypted data.
     *
     * These can ultimately be used to help inform the cleanup manager
     * on the best order to delete files when the node runs out of space.
     * Public data should be given priority over private data (unless
     * this node is part of a data market contract for that data - this
     * isn't developed yet).
     *
     * @return a list of Service objects that require encrypted data.
     */
    public static List<Service> privateServices() {
        List<Service> privateServices = new ArrayList<>();
        for (Service service : Service.values()) {
            if (service.isPrivate) {
                privateServices.add(service);
            }
        }
        return privateServices;
    }

    public enum ValidationResult {
        OK(1),
        MISSING_KEYS(2),
        EXCEEDS_SIZE_LIMIT(3),
        MISSING_INDEX_FILE(4),
        DIRECTORIES_NOT_ALLOWED(5),
        INVALID_FILE_EXTENSION(6),
        MISSING_DATA(7),
        INVALID_FILE_COUNT(8),
        INVALID_CONTENT(9),
        DATA_NOT_ENCRYPTED(10),
        DATA_ENCRYPTED(10);

        public final int value;

        private static final Map<Integer, Transaction.ValidationResult> map = stream(Transaction.ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

        ValidationResult(int value) {
            this.value = value;
        }

        public static Transaction.ValidationResult valueOf(int value) {
            return map.get(value);
        }
    }
}
