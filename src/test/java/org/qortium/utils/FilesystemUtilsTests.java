package org.qortium.utils;

import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FilesystemUtilsTests {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testResolveInsideBaseAllowsNestedRelativePath() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path resolvedPath = FilesystemUtils.resolveInsideBase(base, Paths.get("nested/file.txt"));

        assertEquals(base.toAbsolutePath().normalize().resolve("nested/file.txt"), resolvedPath);
    }

    @Test
    public void testResolveInsideBaseRejectsParentTraversal() throws IOException {
        assertUnsafeRelativePath(Paths.get("../outside.txt"));
    }

    @Test
    public void testResolveInsideBaseRejectsAbsolutePath() throws IOException {
        assertUnsafeRelativePath(Paths.get("/tmp/outside.txt"));
    }

    @Test
    public void testResolveInsideBaseRejectsEmptyPath() throws IOException {
        assertUnsafeRelativePath(Paths.get(""));
    }

    @Test
    public void testResolveRelativePathInsideBaseTreatsLeadingSlashAsRelative() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path resolvedPath = FilesystemUtils.resolveRelativePathInsideBase(base, "/nested/file.txt");

        assertEquals(base.toAbsolutePath().normalize().resolve("nested/file.txt"), resolvedPath);
    }

    @Test
    public void testResolveRelativePathInsideBaseRejectsParentTraversal() throws IOException {
        assertUnsafeRequestedPath("/../outside.txt");
    }

    @Test
    public void testResolveRelativePathInsideBaseRejectsBackslashTraversal() throws IOException {
        assertUnsafeRequestedPath("..\\outside.txt");
    }

    @Test
    public void testResolveRelativePathInsideBaseRejectsInvalidPath() throws IOException {
        assertUnsafeRequestedPath("bad\u0000path");
    }

    @Test
    public void testIsChildRejectsNormalizedEscape() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path escapedPath = base.resolve("..").resolve(base.getFileName() + "-outside");

        assertFalse(FilesystemUtils.isChild(escapedPath, base));
    }

    @Test
    public void testPathInsideDataOrTempPathRejectsNormalizedEscape() {
        Path dataPath = Paths.get(Settings.getInstance().getDataPath()).toAbsolutePath();
        Path escapedPath = dataPath.resolve("..").resolve(dataPath.getFileName() + "-outside").resolve("file.txt");

        assertFalse(FilesystemUtils.pathInsideDataOrTempPath(escapedPath));
    }

    private static void assertUnsafeRelativePath(Path relativePath) throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");

        try {
            FilesystemUtils.resolveInsideBase(base, relativePath);
            fail("Expected unsafe relative path to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside"));
        }
    }

    private static void assertUnsafeRequestedPath(String requestedPath) throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");

        try {
            FilesystemUtils.resolveRelativePathInsideBase(base, requestedPath);
            fail("Expected unsafe requested path to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside") || e.getMessage().contains("invalid"));
        }
    }
}
