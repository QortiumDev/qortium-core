package org.qortium.arbitrary.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.repository.DataException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ArbitraryDataQdnMetadata
 *
 * This is a base class to handle reading and writing JSON to a .qdn folder
 * within the supplied filePath. This is used when storing data against an existing
 * arbitrary data file structure.
 *
 * It is not usable on its own; it must be subclassed, with three methods overridden:
 *
 * fileName() - the file name to use within the .qdn folder
 * readJson() - code to unserialize the JSON file
 * buildJson() - code to serialize the JSON file
 *
 */
public class ArbitraryDataQdnMetadata extends ArbitraryDataMetadata {

    protected static final Logger LOGGER = LogManager.getLogger(ArbitraryDataQdnMetadata.class);

    protected Path filePath;
    protected Path qdnDirectoryPath;

    protected String jsonString;

    public ArbitraryDataQdnMetadata(Path filePath) {
        super(filePath);

        this.qdnDirectoryPath = Paths.get(filePath.toString(), ".qdn");
    }

    protected String fileName() {
        // To be overridden
        return null;
    }


    @Override
    public void write() throws IOException, DataException {
        this.buildJson();
        this.createParentDirectories();
        this.createQdnDirectory();

        Path patchPath = Paths.get(this.qdnDirectoryPath.toString(), this.fileName());
        try (BufferedWriter writer = Files.newBufferedWriter(patchPath, StandardCharsets.UTF_8)) {
            writer.write(this.jsonString);
            writer.newLine();
        }
    }

    @Override
    protected void loadJson() throws IOException {
        Path path = Paths.get(this.qdnDirectoryPath.toString(), this.fileName());
        File patchFile = new File(path.toString());
        if (!patchFile.exists()) {
            throw new IOException(String.format("Patch file doesn't exist: %s", path.toString()));
        }

        this.jsonString = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }


    protected void createQdnDirectory() throws DataException {
        try {
            Files.createDirectories(this.qdnDirectoryPath);
        } catch (IOException e) {
            throw new DataException("Unable to create .qdn directory");
        }
    }

}
