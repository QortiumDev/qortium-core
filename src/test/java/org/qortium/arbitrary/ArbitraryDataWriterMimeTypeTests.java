package org.qortium.arbitrary;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ArbitraryDataWriterMimeTypeTests {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testNamedTextFileBeatsMisleadingMagicBytes() throws IOException {
        // "P5" opens the portable-greymap magic; the name says this is plain text.
        File file = writeFile("notes.txt", "P5 stands for the fifth PGM format\n".getBytes(StandardCharsets.UTF_8));

        assertEquals("text/plain", ArbitraryDataWriter.determineSingleFileMimeType(file));
    }

    @Test
    public void testKnownExtensionAgreesWithContents() throws IOException {
        File file = writeFile("picture.png", pngBytes());

        assertEquals("image/png", ArbitraryDataWriter.determineSingleFileMimeType(file));
    }

    @Test
    public void testSniffingStillCoversExtensionlessFiles() throws IOException {
        File file = writeFile("picture", pngBytes());

        assertEquals("image/png", ArbitraryDataWriter.determineSingleFileMimeType(file));
    }

    @Test
    public void testUnknownNameAndContentsGiveNoType() throws IOException {
        File file = writeFile("blob.qdndata", new byte[] { 0x00, 0x01, 0x02, 0x03 });

        assertNull(ArbitraryDataWriter.determineSingleFileMimeType(file));
    }

    private File writeFile(String name, byte[] contents) throws IOException {
        File file = tempFolder.newFile(name);
        Files.write(file.toPath(), contents);
        return file;
    }

    private static byte[] pngBytes() {
        return new byte[] {
                (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
                0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0,
        };
    }
}
