package org.qortium.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortium.crypto.AES;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class ArbitraryEncryptionTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testEncryption() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        String enclosingFolderName = "data";
        Path inputFilePath = Files.createTempFile("inputFile", null);
        Path outputDirectory = Files.createTempDirectory("outputDirectory");
        Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
        inputFilePath.toFile().deleteOnExit();
        outputDirectory.toFile().deleteOnExit();

        // Write random data to the input file
        byte[] data = new byte[10];
        new Random().nextBytes(data);
        Files.write(inputFilePath, data, StandardOpenOption.CREATE);

        assertTrue(Files.exists(inputFilePath));
        assertFalse(Files.exists(outputFilePath));

        // Encrypt...
        SecretKey aesKey = AES.generateKey(256);
        AES.encryptFile(aesKey, inputFilePath.toString(), outputFilePath.toString());

        assertTrue(Files.exists(inputFilePath));
        assertTrue(Files.exists(outputFilePath));

        // Ensure encrypted file's hash differs from the original
        assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

        // Create paths for decrypting
        Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
        Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
        decryptedDirectory.toFile().deleteOnExit();
        assertFalse(Files.exists(decryptedFile));

        // Now decrypt...
        AES.decryptFile(aesKey, outputFilePath.toString(), decryptedFile.toString());

        // Ensure resulting file exists
        assertTrue(Files.exists(decryptedFile));

        // And make sure it matches the original input file
        assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
    }

    @Test
    public void testEncryptionSizeOverhead() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        for (int size = 1; size < 256; size++) {
            String enclosingFolderName = "data";
            Path inputFilePath = Files.createTempFile("inputFile", null);
            Path outputDirectory = Files.createTempDirectory("outputDirectory");
            Path outputFilePath = Paths.get(outputDirectory.toString(), enclosingFolderName);
            inputFilePath.toFile().deleteOnExit();
            outputDirectory.toFile().deleteOnExit();

            // Write random data to the input file
            byte[] data = new byte[size];
            new Random().nextBytes(data);
            Files.write(inputFilePath, data, StandardOpenOption.CREATE);

            assertTrue(Files.exists(inputFilePath));
            assertFalse(Files.exists(outputFilePath));

            // Ensure input file is the same size as the data
            assertEquals(size, inputFilePath.toFile().length());

            // Encrypt...
            SecretKey aesKey = AES.generateKey(256);
            AES.encryptFile(aesKey, inputFilePath.toString(), outputFilePath.toString());

            assertTrue(Files.exists(inputFilePath));
            assertTrue(Files.exists(outputFilePath));

            final long expectedSize = AES.getEncryptedFileSize(inputFilePath.toFile().length());

            // Ensure encryption added the AES-GCM nonce and authentication tag.
            assertEquals(expectedSize, outputFilePath.toFile().length());

            // Ensure encrypted file's hash differs from the original
            assertFalse(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(outputFilePath.toFile())));

            // Create paths for decrypting
            Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
            Path decryptedFile = Paths.get(decryptedDirectory.toString(), enclosingFolderName, inputFilePath.getFileName().toString());
            decryptedDirectory.toFile().deleteOnExit();
            assertFalse(Files.exists(decryptedFile));

            // Now decrypt...
            AES.decryptFile(aesKey, outputFilePath.toString(), decryptedFile.toString());

            // Ensure resulting file exists
            assertTrue(Files.exists(decryptedFile));

            // And make sure it matches the original input file
            assertTrue(Arrays.equals(Crypto.digest(inputFilePath.toFile()), Crypto.digest(decryptedFile.toFile())));
        }
    }

    @Test
    public void testTamperedEncryptionFailsAuthentication() throws IOException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException {
        Path inputFilePath = Files.createTempFile("inputFile", null);
        Path outputDirectory = Files.createTempDirectory("outputDirectory");
        Path outputFilePath = Paths.get(outputDirectory.toString(), "encrypted");
        inputFilePath.toFile().deleteOnExit();
        outputDirectory.toFile().deleteOnExit();

        byte[] data = new byte[128];
        new Random().nextBytes(data);
        Files.write(inputFilePath, data, StandardOpenOption.CREATE);

        SecretKey aesKey = AES.generateKey(256);
        AES.encryptFile(aesKey, inputFilePath.toString(), outputFilePath.toString());

        byte[] encryptedBytes = Files.readAllBytes(outputFilePath);
        encryptedBytes[encryptedBytes.length - 1] ^= 1;
        Files.write(outputFilePath, encryptedBytes, StandardOpenOption.TRUNCATE_EXISTING);

        Path decryptedDirectory = Files.createTempDirectory("decryptedDirectory");
        Path decryptedFile = Paths.get(decryptedDirectory.toString(), "decrypted");
        decryptedDirectory.toFile().deleteOnExit();

        try {
            AES.decryptFile(aesKey, outputFilePath.toString(), decryptedFile.toString());
            fail("Tampered AES-GCM ciphertext should fail authentication");
        } catch (BadPaddingException e) {
            assertFalse(Files.exists(decryptedFile));
        }
    }

}
