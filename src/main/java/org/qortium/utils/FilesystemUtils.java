package org.qortium.utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.qortium.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class FilesystemUtils {

    public static boolean isDirectoryEmpty(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> directory = Files.newDirectoryStream(path)) {
                return !directory.iterator().hasNext();
            }
        }

        return false;
    }

    public static void copyAndReplaceDirectory(String sourceDirectoryLocation, String destinationDirectoryLocation) throws IOException {
        // Ensure parent folders exist in the destination
        File destFile = new File(destinationDirectoryLocation);
        if (destFile != null) {
            destFile.mkdirs();
        }
        if (destFile == null || !destFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        // If the destination directory isn't empty, delete its contents
        if (!FilesystemUtils.isDirectoryEmpty(destFile.toPath())) {
            FileUtils.deleteDirectory(destFile);
            destFile.mkdirs();
        }

        Files.walk(Paths.get(sourceDirectoryLocation))
                .forEach(source -> {
                    Path destination = Paths.get(destinationDirectoryLocation, source.toString()
                            .substring(sourceDirectoryLocation.length()));
                    try {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }


    /**
     * moveFile
     * Allows files to be moved between filesystems
     *
     * @param source
     * @param dest
     * @param cleanup
     * @throws IOException
     */
    public static void moveFile(Path source, Path dest, boolean cleanup) throws IOException {
        if (source.compareTo(dest) == 0) {
            // Source path matches destination path already
            return;
        }

        File sourceFile = new File(source.toString());
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("Source file doesn't exist");
        }
        if (!sourceFile.isFile()) {
            throw new IOException("Source isn't a file");
        }

        // Ensure parent folders exist in the destination
        File destFile = new File(dest.toString());
        File destParentFile = destFile.getParentFile();
        if (destParentFile != null) {
            destParentFile.mkdirs();
        }
        if (destParentFile == null || !destParentFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        // Copy to destination
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);

        // Delete existing
        if (FilesystemUtils.pathInsideDataOrTempPath(source)) {
            Files.delete(source);
        }

        if (cleanup) {
            // ... and delete its parent directory if empty
            Path parentDirectory = source.getParent();
            if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
                Files.deleteIfExists(parentDirectory);
            }
        }
    }

    /**
     * moveDirectory
     * Allows directories to be moved between filesystems
     *
     * @param source
     * @param dest
     * @param cleanup
     * @throws IOException
     */
    public static void moveDirectory(Path source, Path dest, boolean cleanup) throws IOException {
        if (source.compareTo(dest) == 0) {
            // Source path matches destination path already
            return;
        }

        File sourceFile = new File(source.toString());
        File destFile = new File(dest.toString());
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("Source directory doesn't exist");
        }
        if (!sourceFile.isDirectory()) {
            throw new IOException("Source isn't a directory");
        }

        // Ensure parent folders exist in the destination
        destFile.mkdirs();
        if (destFile == null || !destFile.exists()) {
            throw new IOException("Destination directory doesn't exist");
        }

        // Copy to destination
        FilesystemUtils.copyAndReplaceDirectory(source.toString(), dest.toString());

        // Delete existing
        if (FilesystemUtils.pathInsideDataOrTempPath(source)) {
            File directory = new File(source.toString());
            System.out.println(String.format("Deleting directory %s", directory.toString()));
            FileUtils.deleteDirectory(directory);
        }

        if (cleanup) {
            // ... and delete its parent directory if empty
            Path parentDirectory = source.getParent();
            if (FilesystemUtils.pathInsideDataOrTempPath(parentDirectory)) {
                Files.deleteIfExists(parentDirectory);
            }
        }
    }

    public static boolean safeDeleteDirectory(Path path, boolean cleanup) throws IOException {
        boolean success = false;

        // Delete path, if it exists in our data/temp directory
        if (FilesystemUtils.pathInsideDataOrTempPath(path)) {
            if (Files.exists(path)) {
                File directory = new File(path.toString());
                FileUtils.deleteDirectory(directory);
                success = true;
            }
        }

        if (success && cleanup) {
            // Delete the parent directories if they are empty (and exist in our data/temp directory)
            FilesystemUtils.safeDeleteEmptyParentDirectories(path);
        }

        return success;
    }

    public static void safeDeleteEmptyParentDirectories(Path path) throws IOException {
        final Path parentPath = path.toAbsolutePath().getParent();
        if (!parentPath.toFile().isDirectory()) {
            return;
        }
        if (!FilesystemUtils.pathInsideDataOrTempPath(parentPath)) {
            return;
        }
        try {
            Files.deleteIfExists(parentPath);

        } catch (DirectoryNotEmptyException e) {
            // We've reached the limits of what we can delete
            return;
        }

        FilesystemUtils.safeDeleteEmptyParentDirectories(parentPath);
    }

    public static boolean pathInsideDataOrTempPath(Path path) {
        if (path == null) {
            return false;
        }
        Path dataPath = Paths.get(Settings.getInstance().getDataPath()).toAbsolutePath().normalize();
        Path tempDataPath = Paths.get(Settings.getInstance().getTempDataPath()).toAbsolutePath().normalize();
        Path absolutePath = path.toAbsolutePath().normalize();
        if (absolutePath.startsWith(dataPath) || absolutePath.startsWith(tempDataPath)) {
            return true;
        }
        return false;
    }

    public static boolean isChild(Path child, Path parent) {
        if (child == null || parent == null) {
            return false;
        }
        return child.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
    }

    /**
     * Returns true if {@code candidate}'s canonical path is contained within (or equal to)
     * {@code base}'s canonical path. Unlike {@link #isChild(Path, Path)} this canonicalizes both
     * paths, so it resolves symlinks and {@code ..} segments. Use it as a path-traversal /
     * symlink-escape guard before performing filesystem operations on enumerated child entries
     * (e.g. iterating a directory's listFiles()).
     */
    public static boolean isWithinCanonical(File base, File candidate) throws IOException {
        if (base == null || candidate == null) {
            return false;
        }
        String basePath = base.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        return candidatePath.equals(basePath)
                || candidatePath.startsWith(basePath + File.separator);
    }

    public static Path safeRelativePath(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Path is null");
        }

        Path normalizedPath = path.normalize();
        if (normalizedPath.isAbsolute() || normalizedPath.toString().isEmpty() || normalizedPath.startsWith("..")) {
            throw new IOException("Path is outside of the target dir: " + path);
        }

        return normalizedPath;
    }

    public static Path resolveInsideBase(Path base, Path relativePath) throws IOException {
        if (base == null) {
            throw new IOException("Base path is null");
        }

        Path normalizedBase = base.toAbsolutePath().normalize();
        Path safeRelativePath = FilesystemUtils.safeRelativePath(relativePath);
        Path resolvedPath = normalizedBase.resolve(safeRelativePath).normalize();

        if (!resolvedPath.startsWith(normalizedBase)) {
            throw new IOException("Path is outside of the target dir: " + relativePath);
        }

        return resolvedPath;
    }

    public static Path resolveRelativePathInsideBase(Path base, String requestedPath) throws IOException {
        if (requestedPath == null) {
            throw new IOException("Requested path is null");
        }

        String resourcePath = requestedPath.replace('\\', '/');
        while (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }

        try {
            return FilesystemUtils.resolveInsideBase(base, Paths.get(resourcePath));
        } catch (InvalidPathException e) {
            throw new IOException("Requested path is invalid", e);
        }
    }

    public static Path resolveFileNameInsideBase(Path base, String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            throw new IOException("Filename is missing");
        }
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0) {
            throw new IOException("Filename is outside of the target dir: " + filename);
        }

        try {
            Path filenamePath = Paths.get(filename).normalize();
            if (filenamePath.isAbsolute() ||
                    filenamePath.getNameCount() != 1 ||
                    filenamePath.toString().isEmpty() ||
                    ".".equals(filenamePath.toString()) ||
                    "..".equals(filenamePath.toString())) {
                throw new IOException("Filename is outside of the target dir: " + filename);
            }

            return FilesystemUtils.resolveInsideBase(base, filenamePath);
        } catch (InvalidPathException e) {
            throw new IOException("Filename is invalid", e);
        }
    }

    public static long getDirectorySize(Path path) throws IOException {
        return getDirectorySize(path, false);
    }

    public static long getDirectorySize(Path path, boolean excludeQdnDirectory) throws IOException {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        return Files.walk(path)
                .filter(p -> p.toFile().isFile())
                .filter(p -> !excludeQdnDirectory || !isQdnMetadataPath(p))
                .mapToLong(p -> p.toFile().length())
                .sum();
    }

    private static boolean isQdnMetadataPath(Path path) {
        for (Path part : path) {
            if (".qdn".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }


    /**
     * getSingleFileContents
     * Return the content of the file at given path.
     * If the path is a directory, the contents will be returned
     * only if it contains a single file.
     *
     * @param path
     * @return
     * @throws IOException
     */
    public static byte[] getSingleFileContents(Path path) throws IOException {
        return getSingleFileContents(path, null);
    }

    public static byte[] getSingleFileContents(Path path, Integer maxLength) throws IOException {
        Path filePath = null;
    
        if (Files.isRegularFile(path)) {
            filePath = path;
        } else if (Files.isDirectory(path)) {
            String[] files = ArrayUtils.removeElement(path.toFile().list(), ".qdn");
            if (files.length == 1) {
                filePath = path.resolve(files[0]);
            }
        }
    
        if (filePath == null || !Files.isRegularFile(filePath)) {
            return null;
        }
    
        long fileSize = Files.size(filePath);
        int length = (maxLength != null) ? Math.min(maxLength, (int) Math.min(fileSize, Integer.MAX_VALUE)) : (int) Math.min(fileSize, Integer.MAX_VALUE);
    
        try (InputStream in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[length];
            int bytesRead = in.read(buffer);
            if (bytesRead < length) {
                // Resize buffer to actual read size
                byte[] trimmed = new byte[bytesRead];
                System.arraycopy(buffer, 0, trimmed, 0, bytesRead);
                return trimmed;
            }
            return buffer;
        }
    }
    

    /**
     * isSingleFileResource
     * Returns true if the path points to a file, or a
     * directory containing a single file only.
     *
     * @param path to file or directory
     * @param excludeQdnDirectory - if true, a directory containing a single file and a .qdn directory is considered a single file resource
     * @return
     * @throws IOException
     */
    public static boolean isSingleFileResource(Path path, boolean excludeQdnDirectory) {
        // If the path is a file, read the contents directly
        if (path.toFile().isFile()) {
            return true;
        }

        // Or if it's a directory, only load file contents if there is a single file inside it
        else if (path.toFile().isDirectory()) {
            String[] files = path.toFile().list();
            if (excludeQdnDirectory) {
                files = ArrayUtils.removeElement(files, ".qdn");
            }
            if (files.length == 1) {
                Path filePath = Paths.get(path.toString(), files[0]);
                if (filePath.toFile().isFile()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static byte[] readFromFile(String filePath, long position, int size) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            file.seek(position);
            byte[] bytes = new byte[size];
            file.read(bytes);
            return bytes;
        }
    }

    public static String readUtf8StringFromFile(String filePath, long position, int size) throws IOException {
        return new String(FilesystemUtils.readFromFile(filePath, position, size), StandardCharsets.UTF_8);
    }

    public static boolean fileEndsWithNewline(Path path) throws IOException {
        long length = Files.size(path);
        String lastCharacter = FilesystemUtils.readUtf8StringFromFile(path.toString(), length-1, 1);
        return (lastCharacter.equals("\n") || lastCharacter.equals("\r"));
    }

    /**
     * Get Disk Usage
     *
     * @param file the file
     *
     * @return the size in bytes, zero if an i/o exception is thrown
     */
    public static long getDiskUsage(File file) {
        try {
            // Logical file size in bytes
            long logicalSize = file.length();

            // Get file store information to estimate actual disk usage
            Path path = Paths.get(file.getAbsolutePath());
            FileStore store = Files.getFileStore(path);

            // Get cluster/allocation unit size
            long blockSize = store.getBlockSize();

            // Calculate approximate actual disk usage
            long blocksUsed = (logicalSize + blockSize - 1) / blockSize; // Ceiling division
            long actualDiskUsage = blocksUsed * blockSize;

            return actualDiskUsage;
        } catch (IOException e) {
            return 0;
        }
    }
}
