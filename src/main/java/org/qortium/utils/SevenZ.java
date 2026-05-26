//
// Code originally written by memorynotfound
// https://memorynotfound.com/java-7z-seven-zip-example-compress-decompress-file/
// Modified Sept 2021 by Qortal Core dev team
//

package org.qortium.utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class SevenZ {

    private SevenZ() {

    }

    public static void compress(String outputPath, File... files) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(new File(outputPath))){
            for (File file : files){
                addToArchiveCompression(out, file, ".");
            }
        }
    }

    public static void decompress(String in, File destination) throws IOException {
        try (SevenZFile sevenZFile = SevenZFile.builder().setFile(new File(in)).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null){
                if (entry.isDirectory()){
                    continue;
                }
                File curfile = newFile(destination, entry);
                File parent = curfile.getParentFile();
                if (parent == null) {
                    throw new IOException("Entry has no parent directory: " + entry.getName());
                }
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }
                long fileSize = entry.getSize();

                try (FileOutputStream out = new FileOutputStream(curfile)) {
                    byte[] b = new byte[1024 * 1024];
                    int count;
                    long extracted = 0;

                    while ((count = sevenZFile.read(b)) > 0) {
                        out.write(b, 0, count);
                        extracted += count;

                        int progress = (int)((double)extracted / (double)fileSize * 100);
                        StartupStatus.update(String.format("Extracting %s... (%d%%)", curfile.getName(), progress));
                    }
                }
            }
        }
    }

    private static File newFile(File destinationDir, SevenZArchiveEntry entry) throws IOException {
        String entryName = entry.getName();
        if (entryName == null || entryName.isEmpty() || new File(entryName).isAbsolute()) {
            throw new IOException("Entry is outside of the target dir: " + entryName);
        }

        File destFile = new File(destinationDir, entryName);

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + entryName);
        }

        return destFile;
    }

    private static void addToArchiveCompression(SevenZOutputFile out, File file, String dir) throws IOException {
        String name = dir + File.separator + file.getName();
        if (file.isFile()){
            SevenZArchiveEntry entry = out.createArchiveEntry(file, name);
            out.putArchiveEntry(entry);

            try {
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] b = new byte[8192];
                    int count = 0;
                    while ((count = in.read(b)) > 0) {
                        out.write(b, 0, count);
                    }
                }
            } finally {
                out.closeArchiveEntry();
            }

        } else if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null){
                for (File child : children){
                    addToArchiveCompression(out, child, name);
                }
            }
        } else {
            System.out.println(file.getName() + " is not supported");
        }
    }
}
