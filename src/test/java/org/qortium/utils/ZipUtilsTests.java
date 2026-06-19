package org.qortium.utils;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Verifies that {@link ZipUtils#zip} produces byte-reproducible archives: zipping identical content
 * (even from a different source directory or filesystem listing order) yields identical bytes. This
 * underpins reproducible multi-file QDN publishing.
 */
public class ZipUtilsTests {

    /** Create a directory of identical content; file creation order is deliberately not sorted. */
    private static Path makeContentDir(String prefix) throws Exception {
        Path dir = Files.createTempDirectory(prefix);
        dir.toFile().deleteOnExit();
        // Create out of alphabetical order on purpose.
        write(dir, "index.html", "<html><body>index</body></html>");
        write(dir, "about.html", "<html><body>about page</body></html>");
        write(dir, "data.json", "{\"key\":\"value\"}");
        return dir;
    }

    private static void write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
    }

    private static byte[] zipOf(Path sourceDir) throws Exception {
        Path zip = Files.createTempFile("ziputils-test", ".zip");
        zip.toFile().deleteOnExit();
        ZipUtils.zip(sourceDir.toString(), zip.toString(), "data");
        return Files.readAllBytes(zip);
    }

    @Test
    public void testZipIsByteReproducibleAcrossDirectories() throws Exception {
        // Two independent source directories with identical content but different temp paths.
        byte[] zipA = zipOf(makeContentDir("ziprepA"));
        byte[] zipB = zipOf(makeContentDir("ziprepB"));

        assertArrayEquals("Zipping identical content must produce byte-identical archives", zipA, zipB);
    }

    @Test
    public void testZipIsByteReproducibleAcrossRepeatedRuns() throws Exception {
        // Zipping the very same directory twice must also be byte-identical (no embedded current time).
        Path dir = makeContentDir("ziprepSame");
        assertArrayEquals(zipOf(dir), zipOf(dir));
    }

    @Test
    public void testZipRoundTripsContent() throws Exception {
        Path dir = makeContentDir("ziproundtrip");
        Path zip = Files.createTempFile("ziputils-roundtrip", ".zip");
        zip.toFile().deleteOnExit();
        ZipUtils.zip(dir.toString(), zip.toString(), "data");

        Path out = Files.createTempDirectory("ziputils-unzip");
        out.toFile().deleteOnExit();
        ZipUtils.unzip(zip.toString(), out.toString());

        // Content survives the reproducible zip unchanged.
        Path extracted = Paths.get(out.toString(), "data", "about.html");
        assertEquals("<html><body>about page</body></html>",
                new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8));
    }
}
