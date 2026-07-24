package org.qortium.test.arbitrary;

import org.qortium.crypto.AES;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Runs file decryption in a child JVM so tests can enforce a heap limit that is
 * smaller than the encrypted file.
 */
public class AESFileDecryptHarness {

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("Expected key, encrypted path, and decrypted path");

        byte[] keyBytes = Base64.getDecoder().decode(args[0]);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        AES.decryptFile(key, Path.of(args[1]).toString(), Path.of(args[2]).toString());
    }
}
