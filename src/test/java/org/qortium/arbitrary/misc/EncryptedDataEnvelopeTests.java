package org.qortium.arbitrary.misc;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EncryptedDataEnvelopeTests {

    /** Build an envelope: fixed header + headerLen bytes of (zeroed) header + ciphertextLen bytes. */
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
        // Historical v1 only enforced an opaque nonempty header.
        return envelope(EncryptedDataEnvelope.VERSION_1, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 64);
    }

    private static byte[] validVersion2RecipientsEnvelope(int recipientCount) {
        int headerLength = EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH
                + recipientCount * EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH;
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_2, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, headerLength,
                EncryptedDataEnvelope.AUTH_TAG_LENGTH);
        int offset = EncryptedDataEnvelope.FIXED_HEADER_LENGTH;
        data[offset] = (byte) (recipientCount >>> 8);
        data[offset + 1] = (byte) recipientCount;
        data[offset + 2 + EncryptedDataEnvelope.CONTENT_NONCE_LENGTH] = 1;
        int entryOffset = offset + EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH;
        for (int index = 0; index < recipientCount; ++index) {
            data[entryOffset + EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH - 1] = (byte) (index + 1);
            entryOffset += EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH;
        }
        return data;
    }

    private static byte[] validVersion2GroupEnvelope() {
        byte[] data = envelope(EncryptedDataEnvelope.VERSION_2, EncryptedDataEnvelope.MODE_GROUP,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, EncryptedDataEnvelope.GROUP_HEADER_LENGTH,
                EncryptedDataEnvelope.AUTH_TAG_LENGTH);
        int offset = EncryptedDataEnvelope.FIXED_HEADER_LENGTH;
        data[offset + 3] = 12;
        data[offset + EncryptedDataEnvelope.GROUP_ID_LENGTH] = 1;
        data[offset + EncryptedDataEnvelope.GROUP_ID_LENGTH + EncryptedDataEnvelope.EPOCH_ID_LENGTH] = 1;
        return data;
    }

    @Test
    public void acceptsValidSingleRecipientEnvelope() {
        byte[] data = validSingleRecipientEnvelope();
        assertTrue(EncryptedDataEnvelope.isEnvelope(data));
        assertTrue(EncryptedDataEnvelope.isEncrypted(data));
    }

    @Test
    public void acceptsValidGroupEnvelope() {
        // Historical v1 only enforced an opaque nonempty header.
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
        byte[] data = envelope((byte) 0x03, EncryptedDataEnvelope.MODE_RECIPIENTS,
                EncryptedDataEnvelope.CIPHER_AES_256_GCM, 44, 64);
        assertFalse(EncryptedDataEnvelope.isEnvelope(data));
    }

    @Test
    public void acceptsStrictVersion2Modes() {
        assertTrue(EncryptedDataEnvelope.isEnvelope(validVersion2RecipientsEnvelope(2)));
        assertTrue(EncryptedDataEnvelope.isEnvelope(validVersion2GroupEnvelope()));
    }

    @Test
    public void rejectsVersion2ReservedFlagsAndShortTag() {
        byte[] reservedFlags = validVersion2RecipientsEnvelope(2);
        reservedFlags[7] = 1;
        assertFalse(EncryptedDataEnvelope.isEnvelope(reservedFlags));

        byte[] shortTag = validVersion2RecipientsEnvelope(2);
        byte[] truncated = java.util.Arrays.copyOf(shortTag, shortTag.length - 1);
        assertFalse(EncryptedDataEnvelope.isEnvelope(truncated));
    }

    @Test
    public void rejectsVersion2RecipientCountMismatchAndNonCanonicalOrder() {
        byte[] wrongCount = validVersion2RecipientsEnvelope(2);
        wrongCount[EncryptedDataEnvelope.FIXED_HEADER_LENGTH + 1] = 1;
        assertFalse(EncryptedDataEnvelope.isEnvelope(wrongCount));

        byte[] duplicateKeyId = validVersion2RecipientsEnvelope(2);
        int firstEntry = EncryptedDataEnvelope.FIXED_HEADER_LENGTH
                + EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH;
        int secondEntry = firstEntry + EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH;
        System.arraycopy(duplicateKeyId, firstEntry, duplicateKeyId, secondEntry,
                EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH);
        assertFalse(EncryptedDataEnvelope.isEnvelope(duplicateKeyId));
    }

    @Test
    public void rejectsVersion2InvalidGroupContext() {
        byte[] zeroGroupId = validVersion2GroupEnvelope();
        zeroGroupId[EncryptedDataEnvelope.FIXED_HEADER_LENGTH + 3] = 0;
        assertFalse(EncryptedDataEnvelope.isEnvelope(zeroGroupId));

        byte[] shortHeader = envelope(EncryptedDataEnvelope.VERSION_2,
                EncryptedDataEnvelope.MODE_GROUP, EncryptedDataEnvelope.CIPHER_AES_256_GCM,
                EncryptedDataEnvelope.GROUP_HEADER_LENGTH - 1, EncryptedDataEnvelope.AUTH_TAG_LENGTH);
        assertFalse(EncryptedDataEnvelope.isEnvelope(shortHeader));
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
        // Start from a valid envelope, then over-declare headerLen without enlarging the array so the
        // declared header runs past the bytes actually present (FIXED_HEADER_LENGTH + headerLen > length).
        byte[] data = validSingleRecipientEnvelope(); // length 118, real headerLen 44
        int overLen = 200; // 118 - FIXED_HEADER_LENGTH(10) = 108 bytes available, far less than 200
        data[8] = (byte) ((overLen >> 8) & 0xFF);
        data[9] = (byte) (overLen & 0xFF);
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
