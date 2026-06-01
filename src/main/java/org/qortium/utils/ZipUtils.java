/*
 * MIT License
 *
 * Copyright (c) 2017 Eugen Paraschiv
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * Code modified in 2021 for Qortal Core
 *
 */

package org.qortium.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;

import org.qortium.controller.Controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    public static void zip(String sourcePath, String destFilePath, String enclosingFolderName) throws IOException, InterruptedException {
        File sourceFile = new File(sourcePath);
        boolean isSingleFile = Paths.get(sourcePath).toFile().isFile();

        // 🔧 Use best speed compression level
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(destFilePath))) {
            ZipUtils.zip(sourceFile, enclosingFolderName, zipOutputStream, isSingleFile);
        }
    }
    

    public static void zip(final File fileToZip, final String enclosingFolderName, final ZipOutputStream zipOut, boolean isSingleFile) throws IOException, InterruptedException {
        if (Controller.isStopping()) {
            throw new InterruptedException("Controller is stopping");
        }

        // Handle single file resources slightly differently
        if (isSingleFile) {
            // Create enclosing folder
            zipOut.putNextEntry(new ZipEntry(safeZipEntryName(enclosingFolderName, true)));
            zipOut.closeEntry();
            // Place the supplied file within the folder
            ZipUtils.zip(fileToZip, zipEntryChildName(enclosingFolderName, fileToZip.getName()), zipOut, false);
            return;
        }

        if (fileToZip.isDirectory()) {
            zipOut.putNextEntry(new ZipEntry(safeZipEntryName(enclosingFolderName, true)));
            zipOut.closeEntry();
            final File[] children = fileToZip.listFiles();
            for (final File childFile : children) {
                ZipUtils.zip(childFile, zipEntryChildName(enclosingFolderName, childFile.getName()), zipOut, false);
            }
            return;
        }
        final ZipEntry zipEntry = new ZipEntry(safeZipEntryName(enclosingFolderName, false));
        zipOut.putNextEntry(zipEntry);
        try {
            try (FileInputStream fis = new FileInputStream(fileToZip)) {
                final byte[] bytes = new byte[65536];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
            }
        } finally {
            zipOut.closeEntry();
        }
    }

    /**
     * Unzips a file from the given source path to the destination path.
     * 
     * @param sourcePath Path to the ZIP file
     * @param destPath Destination directory for extracted files
     * @throws IOException If extraction fails
     */
    public static void unzip(String sourcePath, String destPath) throws IOException {
        final File destDir = new File(destPath);
        // Buffer size: 512KB - optimized for large files (reduces syscalls)
        final int BUFFER_SIZE = 512 * 1024;
        final byte[] buffer = new byte[BUFFER_SIZE];
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(sourcePath), BUFFER_SIZE);
             ZipInputStream zis = new ZipInputStream(bis)) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                final File newFile = ZipUtils.newFile(destDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }
    
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(newFile), BUFFER_SIZE)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * Unzips from an InputStream directly to the destination path.
     * This is useful for streaming operations where the ZIP data comes from a stream.
     * 
     * @param zipInputStream The ZipInputStream to read from
     * @param destPath Destination directory for extracted files
     * @throws IOException If extraction fails
     */
    public static void unzipFromStream(ZipInputStream zipInputStream, String destPath) throws IOException {
        final File destDir = new File(destPath);
        // Buffer size: 512KB - optimized for large files
        final int BUFFER_SIZE = 512 * 1024;
        final byte[] buffer = new byte[BUFFER_SIZE];
        
        ZipEntry zipEntry = zipInputStream.getNextEntry();
        while (zipEntry != null) {
            final File newFile = ZipUtils.newFile(destDir, zipEntry);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(newFile), BUFFER_SIZE)) {
                    int len;
                    while ((len = zipInputStream.read(buffer)) > 0) {
                        bos.write(buffer, 0, len);
                    }
                }
            }
            zipEntry = zipInputStream.getNextEntry();
        }
        zipInputStream.closeEntry();
    }

    /**
     * Sanitizes a zip entry name for safe extraction on all supported OS/filesystems.
     * Removes characters that are invalid on Windows, Linux, and common FS (e.g. exFAT),
     * and trims leading/trailing whitespace from each path segment. Preserves path structure
     * (ZIP uses forward slash). Normal names are unchanged; only invalid chars are removed.
     *
     * @param entryName The raw name from the zip entry (e.g. "data/ | file.mp4")
     * @return A safe path for extraction (e.g. "data/file.mp4")
     */
    private static String sanitizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return "_";
        }
        // ZIP spec uses forward slash as path separator
        String[] segments = entryName.split("/", -1);
        boolean trailingSlash = entryName.endsWith("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            String segment = segments[i];
            // Skip empty trailing segment (directory entry like "data/")
            if (segment.isEmpty() && i == segments.length - 1 && trailingSlash) {
                out.setLength(out.length() - 1); // remove the trailing '/' we just added
                break;
            }
            // Same invalid-char set as StringUtils.sanitizeString: invalid on Windows and common FS
            String sanitized = segment.replaceAll("[<>:\"/\\\\|?*]", "");
            // Trim leading/trailing whitespace (e.g. " | file.mp4" -> "file.mp4" after pipe removed)
            sanitized = sanitized.replaceAll("^\\s+|\\s+$", "");
            if (sanitized.isEmpty()) {
                sanitized = "_";
            }
            out.append(sanitized);
        }
        return out.toString();
    }

    /**
     * See: https://snyk.io/research/zip-slip-vulnerability
     * Zip entry names are sanitized so extraction works on all OS/filesystems (e.g. names with | or :).
     */
    public static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        String safeName = sanitizeZipEntryName(zipEntry.getName());
        File destFile = new File(destinationDir, safeName);

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    private static String zipEntryChildName(String parentName, String childName) throws IOException {
        return safeZipEntryName(parentName, false) + "/" + safeZipEntryName(childName, false);
    }

    private static String safeZipEntryName(String entryName, boolean directory) throws IOException {
        String safeName = sanitizeZipEntryName(entryName);
        Path safeRelativePath;
        try {
            safeRelativePath = FilesystemUtils.safeRelativePath(Paths.get(safeName));
        } catch (InvalidPathException e) {
            throw new IOException("Entry is outside of the target dir: " + entryName, e);
        }

        StringBuilder zipEntryName = new StringBuilder();
        for (Path part : safeRelativePath) {
            if (zipEntryName.length() > 0) {
                zipEntryName.append('/');
            }
            zipEntryName.append(part.toString());
        }

        if (directory && zipEntryName.charAt(zipEntryName.length() - 1) != '/') {
            zipEntryName.append('/');
        }

        return zipEntryName.toString();
    }

}
