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
    public void testResolveFileNameInsideBaseAllowsSingleFilename() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path resolvedPath = FilesystemUtils.resolveFileNameInsideBase(base, "file.txt");

        assertEquals(base.toAbsolutePath().normalize().resolve("file.txt"), resolvedPath);
    }

    @Test
    public void testResolveFileNameInsideBaseRejectsNestedPath() throws IOException {
        assertUnsafeFilename("nested/file.txt");
    }

    @Test
    public void testResolveFileNameInsideBaseRejectsBackslashPath() throws IOException {
        assertUnsafeFilename("nested\\file.txt");
    }

    @Test
    public void testResolveFileNameInsideBaseRejectsParentTraversal() throws IOException {
        assertUnsafeFilename("../outside.txt");
    }

    @Test
    public void testResolveFileNameInsideBaseRejectsBlankFilename() throws IOException {
        assertUnsafeFilename("");
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

    @Test
    public void testIsWithinCanonicalAcceptsContainedChild() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path child = Files.createFile(base.resolve("file.txt"));

        assertTrue(FilesystemUtils.isWithinCanonical(base.toFile(), child.toFile()));
        // The base directory is "within" itself.
        assertTrue(FilesystemUtils.isWithinCanonical(base.toFile(), base.toFile()));
    }

    @Test
    public void testIsWithinCanonicalRejectsSibling() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path sibling = Files.createTempDirectory("filesystem-utils-sibling");

        // A path whose name is a prefix of the base must not be considered contained.
        assertFalse(FilesystemUtils.isWithinCanonical(base.toFile(), sibling.toFile()));
        assertFalse(FilesystemUtils.isWithinCanonical(base.toFile(),
                base.getParent().resolve(base.getFileName() + "-outside").toFile()));
    }

    @Test
    public void testIsWithinCanonicalRejectsSymlinkEscape() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");
        Path outside = Files.createTempDirectory("filesystem-utils-outside");
        Path target = Files.createFile(outside.resolve("secret.txt"));

        // A symlink living inside the base but pointing outside must resolve as not-contained.
        Path link = base.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem/OS doesn't support symlinks (e.g. unprivileged Windows) — skip.
            return;
        }

        assertFalse(FilesystemUtils.isWithinCanonical(base.toFile(), link.toFile()));
    }

    @Test
    public void testIsWithinCanonicalRejectsNull() throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");

        assertFalse(FilesystemUtils.isWithinCanonical(base.toFile(), null));
        assertFalse(FilesystemUtils.isWithinCanonical(null, base.toFile()));
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

    private static void assertUnsafeFilename(String filename) throws IOException {
        Path base = Files.createTempDirectory("filesystem-utils-base");

        try {
            FilesystemUtils.resolveFileNameInsideBase(base, filename);
            fail("Expected unsafe filename to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside") ||
                    e.getMessage().contains("invalid") ||
                    e.getMessage().contains("missing"));
        }
    }
}
