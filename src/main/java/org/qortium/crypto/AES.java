/*
 * MIT License
 *
 * Copyright (c) 2017 Eugen Paraschiv
 * Modified in 2021 by CalDescent
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
 */

package org.qortium.crypto;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;

public class AES {

    public static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH * 8;
    private static final int BUFFER_SIZE = 256 * 1024;

    public static SecretKey generateKey(int n) throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(n);
        return keyGenerator.generateKey();
    }

    public static SecretKey getKeyFromPassword(String password, String salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), 65536, 256);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static byte[] encryptBytes(SecretKey key, byte[] input) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException,
            BadPaddingException, IllegalBlockSizeException {
        byte[] nonce = generateNonce();
        Cipher cipher = createGcmCipher(Cipher.ENCRYPT_MODE, key, nonce);
        byte[] cipherText = cipher.doFinal(input);

        byte[] output = new byte[nonce.length + cipherText.length];
        System.arraycopy(nonce, 0, output, 0, nonce.length);
        System.arraycopy(cipherText, 0, output, nonce.length, cipherText.length);
        return output;
    }

    public static byte[] decryptBytes(SecretKey key, byte[] encryptedBytes) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException,
            BadPaddingException, IllegalBlockSizeException, IOException {
        if (encryptedBytes.length < GCM_NONCE_LENGTH + GCM_TAG_LENGTH)
            throw new IOException("Encrypted data is too short to contain AES-GCM nonce and tag");

        byte[] nonce = Arrays.copyOfRange(encryptedBytes, 0, GCM_NONCE_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(encryptedBytes, GCM_NONCE_LENGTH, encryptedBytes.length);
        Cipher cipher = createGcmCipher(Cipher.DECRYPT_MODE, key, nonce);
        return cipher.doFinal(cipherText);
    }

    public static void encryptFile(SecretKey key, String inputFilePath, String outputFilePath) throws IOException,
            NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException,
            BadPaddingException, IllegalBlockSizeException {
        byte[] nonce = generateNonce();
        Cipher cipher = createGcmCipher(Cipher.ENCRYPT_MODE, key, nonce);

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(inputFilePath), BUFFER_SIZE);
             BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFilePath), BUFFER_SIZE)) {

            outputStream.write(nonce);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null)
                    outputStream.write(output);
            }

            byte[] outputBytes = cipher.doFinal();
            if (outputBytes != null)
                outputStream.write(outputBytes);
        }
    }

    public static void decryptFile(SecretKey key, String encryptedFilePath, String decryptedFilePath) throws IOException,
            NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException,
            BadPaddingException, IllegalBlockSizeException {
        Path decryptedPath = Path.of(decryptedFilePath);
        Path parent = decryptedPath.getParent();
        if (parent != null && !Files.isDirectory(parent))
            Files.createDirectories(parent);

        Path tempPath = parent != null
                ? Files.createTempFile(parent, decryptedPath.getFileName().toString(), ".tmp")
                : Files.createTempFile(decryptedPath.getFileName().toString(), ".tmp");

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(encryptedFilePath), BUFFER_SIZE);
             BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempPath.toFile()), BUFFER_SIZE)) {

            byte[] nonce = inputStream.readNBytes(GCM_NONCE_LENGTH);
            if (nonce.length != GCM_NONCE_LENGTH)
                throw new IOException("Failed to read AES-GCM nonce");

            Cipher cipher = createGcmCipher(Cipher.DECRYPT_MODE, key, nonce);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null)
                    outputStream.write(output);
            }

            byte[] output = cipher.doFinal();
            if (output != null)
                outputStream.write(output);
        } catch (IOException | NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException
                 | InvalidKeyException | BadPaddingException | IllegalBlockSizeException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }

        try {
            Files.move(tempPath, decryptedPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    public static long getEncryptedFileSize(long inFileSize) {
        return inFileSize + GCM_NONCE_LENGTH + GCM_TAG_LENGTH;
    }

    private static byte[] generateNonce() {
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static Cipher createGcmCipher(int mode, SecretKey key, byte[] nonce) throws NoSuchPaddingException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException {
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        return cipher;
    }

}
