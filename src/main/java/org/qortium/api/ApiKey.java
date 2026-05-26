package org.qortium.api;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;

public class ApiKey {

    private static final Logger LOGGER = LogManager.getLogger(ApiKey.class);
    private static final Set<PosixFilePermission> API_KEY_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private String apiKey;

    public ApiKey() throws IOException {
        this.load();
    }

    public void generate() throws IOException {
        byte[] apiKey = new byte[16];
        new SecureRandom().nextBytes(apiKey);
        this.apiKey = Base58.encode(apiKey);

        this.save();
    }

    public boolean ensureGenerated() throws IOException {
        if (this.generated()) {
            return false;
        }

        this.generate();
        return true;
    }


    /* Filesystem */

    private Path getFilePath() {
        return Paths.get(Settings.getInstance().getApiKeyPath(), "apikey.txt");
    }

    private boolean load() throws IOException {
        Path path = this.getFilePath();
        if (!Files.exists(path)) {
            return false;
        }

        try {
            this.restrictFilePermissions(path);
            this.apiKey = Files.readString(path, StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new IOException(String.format("Couldn't read contents from file %s: %s", path, e.getMessage()), e);
        }

        return true;
    }

    public void save() throws IOException {
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            throw new IllegalStateException("Unable to save a blank API key");
        }

        Path filePath = this.getFilePath();
        Path parentPath = filePath.getParent();
        if (parentPath != null) {
            Files.createDirectories(parentPath);
        }

        this.createApiKeyFileIfMissing(filePath);
        this.restrictFilePermissions(filePath);
        Files.writeString(filePath, this.apiKey, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.restrictFilePermissions(filePath);
    }

    private void createApiKeyFileIfMissing(Path filePath) throws IOException {
        if (Files.exists(filePath)) {
            return;
        }

        try {
            if (this.supportsPosixFilePermissions(filePath)) {
                FileAttribute<Set<PosixFilePermission>> permissions =
                        PosixFilePermissions.asFileAttribute(API_KEY_FILE_PERMISSIONS);
                Files.createFile(filePath, permissions);
            } else {
                Files.createFile(filePath);
            }
        } catch (FileAlreadyExistsException e) {
            // Another thread/process created it after the exists check.
        }
    }

    private void restrictFilePermissions(Path filePath) throws IOException {
        if (this.supportsPosixFilePermissions(filePath)) {
            Files.setPosixFilePermissions(filePath, API_KEY_FILE_PERMISSIONS);
            return;
        }

        this.restrictNonPosixFilePermissions(filePath);
    }

    private boolean supportsPosixFilePermissions(Path filePath) throws IOException {
        Path fileStorePath = Files.exists(filePath) ? filePath : filePath.getParent();
        if (fileStorePath == null) {
            fileStorePath = Paths.get(".").toAbsolutePath().normalize();
        }

        return Files.getFileStore(fileStorePath).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private void restrictNonPosixFilePermissions(Path filePath) {
        File apiKeyFile = filePath.toFile();
        boolean permissionsChanged = true;

        permissionsChanged &= apiKeyFile.setReadable(false, false);
        permissionsChanged &= apiKeyFile.setWritable(false, false);
        permissionsChanged &= apiKeyFile.setExecutable(false, false);
        permissionsChanged &= apiKeyFile.setReadable(true, true);
        permissionsChanged &= apiKeyFile.setWritable(true, true);

        if (!permissionsChanged) {
            LOGGER.warn("Unable to confirm owner-only permissions on API key file {}", filePath);
        }
    }

    public void delete() throws IOException {
        this.apiKey = null;

        Path filePath = this.getFilePath();
        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }
    }


    public boolean generated() {
        return (this.apiKey != null);
    }

    public boolean exists() {
        return this.getFilePath().toFile().exists();
    }

    @Override
    public String toString() {
        return this.apiKey;
    }

}
