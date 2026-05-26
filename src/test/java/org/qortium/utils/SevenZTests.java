package org.qortium.utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SevenZTests {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testNestedEntryExtractsInsideDestination() throws IOException {
        File archive = temporaryFolder.newFile("valid.7z");
        File destination = temporaryFolder.newFolder("destination");

        createArchive(archive, "bootstrap/nested/file.txt", "hello");

        SevenZ.decompress(archive.toString(), destination);

        File extracted = new File(destination, "bootstrap/nested/file.txt");
        assertTrue(extracted.isFile());
        assertEquals("hello", Files.readString(extracted.toPath()));
    }

    @Test
    public void testCompressedDirectoryRoundTripExtractsInsideDestination() throws IOException {
        File source = temporaryFolder.newFolder("bootstrap");
        File nestedDirectory = new File(source, "nested");
        assertTrue(nestedDirectory.mkdirs());
        Files.writeString(new File(nestedDirectory, "file.txt").toPath(), "hello");

        File archive = temporaryFolder.newFile("round-trip.7z");
        File destination = temporaryFolder.newFolder("round-trip-destination");

        SevenZ.compress(archive.toString(), source);
        SevenZ.decompress(archive.toString(), destination);

        File extracted = new File(destination, "bootstrap/nested/file.txt");
        assertTrue(extracted.isFile());
        assertEquals("hello", Files.readString(extracted.toPath()));
    }

    @Test
    public void testParentTraversalIsRejected() throws IOException {
        File archive = temporaryFolder.newFile("traversal.7z");
        File destination = temporaryFolder.newFolder("destination");
        File outside = new File(destination.getParentFile(), "outside.txt");

        createArchive(archive, "../outside.txt", "bad");

        assertExtractionRejected(archive, destination);
        assertFalse(outside.exists());
    }

    @Test
    public void testSiblingPrefixTraversalIsRejected() throws IOException {
        File archive = temporaryFolder.newFile("sibling-prefix.7z");
        File destination = temporaryFolder.newFolder("dest");
        File sibling = new File(destination.getParentFile(), "dest-evil/outside.txt");

        createArchive(archive, "../dest-evil/outside.txt", "bad");

        assertExtractionRejected(archive, destination);
        assertFalse(sibling.exists());
    }

    @Test
    public void testAbsoluteEntryIsRejected() throws IOException {
        File archive = temporaryFolder.newFile("absolute.7z");
        File destination = temporaryFolder.newFolder("destination");
        File outside = new File(temporaryFolder.getRoot(), "absolute-outside.txt");

        createArchive(archive, outside.getAbsolutePath(), "bad");

        assertExtractionRejected(archive, destination);
        assertFalse(outside.exists());
    }

    private static void assertExtractionRejected(File archive, File destination) throws IOException {
        try {
            SevenZ.decompress(archive.toString(), destination);
            fail("Expected unsafe archive entry to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Entry is outside of the target dir"));
        }
    }

    private static void createArchive(File archive, String entryName, String contents) throws IOException {
        byte[] data = contents.getBytes(StandardCharsets.UTF_8);

        try (SevenZOutputFile out = new SevenZOutputFile(archive)) {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName(entryName);
            entry.setSize(data.length);
            out.putArchiveEntry(entry);
            out.write(data);
            out.closeArchiveEntry();
        }
    }
}
