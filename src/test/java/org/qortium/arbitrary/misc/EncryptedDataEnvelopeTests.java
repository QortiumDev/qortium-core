package org.qortium.arbitrary.misc;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EncryptedDataEnvelopeTests {

    /** Build a v1 envelope: fixed header + headerLen bytes of (zeroed) header + ciphertextLen bytes. */
    private static byte[] envelope(byte version, byte mode, byte cipher, int headerLen, int ciphertextLen) {
        byte[] out = new byte[EncryptedDataEnvelope.FIXED_HEADER_LENGTH + Math.max(headerLen, 0) + Math.max(ciphertextLen, 0)];
        out[0] = 'Q'; out[1] = 'E'; out[2] = 'N'; out[3] = 'C';
        out[4] = version;
        out[5] = mode;
        out[6] = cipher;
        out[7] = 0; // flags
        out[8] = (byte) ((headerLen >> 8) & 0xFF);
        out[9] = (byte) (headerLen & 0xFF);
        return out;
    }

    private static byte[] validSingleRecipientEnvelope() {
        // single-recipient header = ephemeral pubkey(32) + nonce(12) = 44, plus some ciphertext
        return envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 64);
    }

    @Test
    public void acceptsValidSingleRecipientEnvelope() {
        byte[] data = validSingleRecipientEnvelope();
        assertTrue(EncryptedDataEnvelope.isEnvelope(data));
        assertTrue(EncryptedDataEnvelope.isEncrypted(data));
    }

    @Test
    public void acceptsValidGroupEnvelope() {
        // group header = groupKeyId(4) + nonce(12) = 16, plus ciphertext
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_GROUP,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 16, 64);
        assertTrue(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsWrongMagic() {
        byte[] data = validSingleRecipientEnvelope();
        data[1] = 'X';
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
        assertFalse(EncryptedDataEnvelope.isEncrypted(data));
    }

    @Test
    public void rejectsUnknownVersion() {
        byte[] data = envelope((byte) 0x02, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 64);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsUnknownMode() {
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, (byte) 0x09,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 64);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsUnknownCipher() {
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                (byte) 0x09, 44, 64);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsZeroHeaderLength() {
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 0, 64);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsHeaderLongerThanData() {
        // Declare a header far larger than the actual bytes present
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 0);
        // headerLen=44 but no header/ciphertext bytes actually present beyond the fixed header
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsEnvelopeWithNoCiphertext() {
        // Exactly fixed header + header, but no ciphertext byte
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 0);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void rejectsTooShort() {
        assertFalse(EncryptedDataEnvelope.isEnvelope(new byte[] { 'Q', 'E', 'N', 'C' }));
        assertFalse(EncryptedDataEnvelope.isEnvelope(new byte[0]));
        assertFalse(EncryptedDataEnvelope.isEnvelope(null));
    }

    @Test
    public void acceptsLegacyPrefixes() {
        byte[] single = (EncryptedDataEnvelope.LEGACY_PREFIX + "base64ciphertexthere").getBytes(StandardCharsets.UTF_8);
        byte[] group = (EncryptedDataEnvelope.LEGACY_GROUP_PREFIX + "base64ciphertexthere").getBytes(StandardCharsets.UTF_8);
        assertTrue(EncryptedDataEnvelope.hasLegacyPrefix(single));
        assertTrue(EncryptedDataEnvelope.hasLegacyPrefix(group));
        assertTrue(EncryptedDataEnvelope.isEncrypted(single));
        assertTrue(EncryptedDataEnvelope.isEncrypted(group));
    }

    @Test
    public void rejectsPlaintext() {
        byte[] plaintext = "This is just a plain text file, not encrypted at all.".getBytes(StandardCharsets.UTF_8);
        assertFalse(EncryptedDataEnvelope.isEnvelope(plaintext));
        assertFalse(EncryptedDataEnvelope.hasLegacyPrefix(plaintext));
        assertFalse(EncryptedDataEnvelope.isEncrypted(plaintext));
    }
}
