package org.qortium.arbitrary.misc;

import java.nio.charset.StandardCharsets;

/**
 * Structural recognition of encrypted QDN data, used by {@link Service#validate} to check that a
 * private resource was actually encrypted before publishing.
 * <p>
 * Core never holds the decryption key (that stays client-side in Home/Hub), and securely-encrypted
 * data is indistinguishable from random, so Core cannot <em>cryptographically</em> prove the payload
 * is real ciphertext. What it can do is validate the <em>shape</em> of a well-defined encrypted
 * envelope, which robustly catches the real failure mode: an app accidentally publishing plaintext
 * as "private".
 * <p>
 * The envelope carries an audience, expressed at the API/UX layer as one of three modes:
 * <ul>
 *   <li><b>PUBLISHER</b> — only the publishing account can decrypt;</li>
 *   <li><b>ACCOUNTS</b> — a chosen set of accounts (by public key) can decrypt;</li>
 *   <li><b>GROUP</b> — members of a Qortium group can decrypt.</li>
 * </ul>
 * PUBLISHER and ACCOUNTS share one cryptographic mechanism (a content key wrapped to each recipient's
 * public key — PUBLISHER is simply "the only recipient is the publisher"), so at the wire level there
 * are two envelope modes: {@link #MODE_RECIPIENTS} and {@link #MODE_GROUP}.
 * <p>
 * Three formats are accepted:
 * <ul>
 *   <li><b>v2 binary envelope</b> (preferred for new private chat attachments) — a strict,
 *       interoperable header whose mode-specific framing is validated by Core;</li>
 *   <li><b>v1 binary envelope</b> — the original structurally-recognized format, retained without
 *       reinterpreting its opaque mode header;</li>
 *   <li><b>legacy text prefix</b> — the original {@code qdnEncryptedData} / {@code qdnGroupEncryptedData}
 *       ASCII markers, still accepted so existing resources keep working.</li>
 * </ul>
 * See {@code docs/qdn/encrypted-data-envelope.md} for the complete wire specification.
 * This is purely a publish/read-time check; it is not part of consensus (arbitrary data is an opaque
 * hash to the chain), so it can evolve without a coordinated network upgrade.
 */
public final class EncryptedDataEnvelope {

    // --- binary envelope ---
    // Layout (big-endian):
    //   [0:4]   magic      = "QENC"
    //   [4]     version    = 0x01
    //   [5]     mode       = 0x01 recipients (PUBLISHER/ACCOUNTS) | 0x02 group
    //   [6]     cipher     = 0x01 AES-256-GCM
    //   [7]     flags      = reserved (0)
    //   [8:10]  headerLen  = uint16, length of the mode-specific header that follows
    //   [10 : 10+headerLen]   header (opaque to Core in v1; strictly parsed in v2)
    //   [10+headerLen : ]     ciphertext incl. AEAD tag (opaque to Core)
    public static final byte[] MAGIC = { 'Q', 'E', 'N', 'C' };
    public static final byte VERSION_1 = 0x01;
    public static final byte VERSION_2 = 0x02;

    /** Recipient-wrapped: one content key wrapped to 1..N recipient public keys (PUBLISHER = 1 = self, ACCOUNTS = N). */
    public static final byte MODE_RECIPIENTS = 0x01;
    /** Encrypted with a Qortium group's shared key. */
    public static final byte MODE_GROUP = 0x02;

    public static final byte CIPHER_AES_256_GCM = 0x01;

    /** magic(4) + version(1) + mode(1) + cipher(1) + flags(1) + headerLen(2) */
    public static final int FIXED_HEADER_LENGTH = 10;
    /**
     * Upper bound on the declared variable-header length (full uint16 range). In practice the header
     * (which for ACCOUNTS grows with the recipient count) must also fit within Core's data-inspection
     * window so it can be validated; see {@link #isEnvelope}.
     */
    public static final int MAX_VARIABLE_HEADER_LENGTH = 0xFFFF;

    // --- v2 strict mode headers ---
    public static final int AUTH_TAG_LENGTH = 16;
    public static final int CONTENT_NONCE_LENGTH = 12;
    public static final int EPHEMERAL_PUBLIC_KEY_LENGTH = 32;
    public static final int RECIPIENT_KEY_ID_LENGTH = 8;
    public static final int WRAP_NONCE_LENGTH = 12;
    public static final int WRAPPED_CONTENT_KEY_LENGTH = 48;
    public static final int RECIPIENT_ENTRY_LENGTH = RECIPIENT_KEY_ID_LENGTH
            + WRAP_NONCE_LENGTH + WRAPPED_CONTENT_KEY_LENGTH;
    public static final int RECIPIENTS_HEADER_PREFIX_LENGTH = 2 + CONTENT_NONCE_LENGTH
            + EPHEMERAL_PUBLIC_KEY_LENGTH;
    public static final int MAX_RECIPIENTS = 256;
    public static final int GROUP_ID_LENGTH = 4;
    public static final int EPOCH_ID_LENGTH = 32;
    public static final int GROUP_KEY_ID_LENGTH = 32;
    public static final int GROUP_HEADER_LENGTH = GROUP_ID_LENGTH + EPOCH_ID_LENGTH
            + GROUP_KEY_ID_LENGTH + CONTENT_NONCE_LENGTH;

    // --- legacy text prefixes (still accepted) ---
    public static final String LEGACY_PREFIX = "qdnEncryptedData";
    public static final String LEGACY_GROUP_PREFIX = "qdnGroupEncryptedData";

    private EncryptedDataEnvelope() {
    }

    /** True if the data is encrypted by a supported binary envelope or a legacy prefix. */
    public static boolean isEncrypted(byte[] data) {
        return isEnvelope(data) || hasLegacyPrefix(data);
    }

    /**
     * True if {@code data} begins with a structurally-valid supported encrypted envelope. {@code data}
     * may be a truncated leading window of a larger file (Core only inspects the first portion); every
     * supported header fits within that window.
     */
    public static boolean isEnvelope(byte[] data) {
        if (data == null || data.length < FIXED_HEADER_LENGTH) {
            return false;
        }

        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }

        byte mode = data[5];
        if (mode != MODE_RECIPIENTS && mode != MODE_GROUP) {
            return false;
        }

        if (data[6] != CIPHER_AES_256_GCM) {
            return false;
        }

        int headerLen = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        if (headerLen <= 0 || headerLen > MAX_VARIABLE_HEADER_LENGTH) {
            return false;
        }

        if (data[4] == VERSION_1) {
            // v1 deliberately treated the mode header as opaque and ignored the reserved flags byte.
            // Retain that exact recognition behavior so existing resources are not reinterpreted.
            return data.length > FIXED_HEADER_LENGTH + headerLen;
        }

        if (data[4] != VERSION_2 || data[7] != 0) {
            return false;
        }

        // v2 requires at least a complete AES-GCM authentication tag after the exact mode header.
        if (data.length < FIXED_HEADER_LENGTH + headerLen + AUTH_TAG_LENGTH) {
            return false;
        }

        if (mode == MODE_RECIPIENTS) {
            return isVersion2RecipientsHeader(data, headerLen);
        }

        return isVersion2GroupHeader(data, headerLen);
    }

    private static boolean isVersion2RecipientsHeader(byte[] data, int headerLen) {
        if (headerLen < RECIPIENTS_HEADER_PREFIX_LENGTH + RECIPIENT_ENTRY_LENGTH) {
            return false;
        }

        int offset = FIXED_HEADER_LENGTH;
        int recipientCount = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        if (recipientCount < 1 || recipientCount > MAX_RECIPIENTS) {
            return false;
        }

        int expectedHeaderLength = RECIPIENTS_HEADER_PREFIX_LENGTH
                + recipientCount * RECIPIENT_ENTRY_LENGTH;
        if (headerLen != expectedHeaderLength) {
            return false;
        }

        int ephemeralOffset = offset + 2 + CONTENT_NONCE_LENGTH;
        if (isAllZero(data, ephemeralOffset, EPHEMERAL_PUBLIC_KEY_LENGTH)) {
            return false;
        }

        int entryOffset = offset + RECIPIENTS_HEADER_PREFIX_LENGTH;
        byte[] previousKeyId = null;
        for (int index = 0; index < recipientCount; ++index) {
            byte[] keyId = new byte[RECIPIENT_KEY_ID_LENGTH];
            System.arraycopy(data, entryOffset, keyId, 0, keyId.length);
            if (isAllZero(keyId, 0, keyId.length)) {
                return false;
            }
            if (previousKeyId != null && compareUnsigned(previousKeyId, keyId) >= 0) {
                return false;
            }
            previousKeyId = keyId;
            entryOffset += RECIPIENT_ENTRY_LENGTH;
        }

        return true;
    }

    private static boolean isVersion2GroupHeader(byte[] data, int headerLen) {
        if (headerLen != GROUP_HEADER_LENGTH) {
            return false;
        }

        int offset = FIXED_HEADER_LENGTH;
        long groupId = ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFFL);
        if (groupId == 0 || groupId > Integer.MAX_VALUE) {
            return false;
        }

        int epochOffset = offset + GROUP_ID_LENGTH;
        int keyIdOffset = epochOffset + EPOCH_ID_LENGTH;
        return !isAllZero(data, epochOffset, EPOCH_ID_LENGTH)
                && !isAllZero(data, keyIdOffset, GROUP_KEY_ID_LENGTH);
    }

    private static boolean isAllZero(byte[] data, int offset, int length) {
        for (int index = offset; index < offset + length; ++index) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int index = 0; index < left.length; ++index) {
            int comparison = Integer.compare(left[index] & 0xFF, right[index] & 0xFF);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /** True if the data carries a legacy ASCII encryption prefix. */
    public static boolean hasLegacyPrefix(byte[] data) {
        if (data == null) {
            return false;
        }
        int window = Math.min(data.length, LEGACY_GROUP_PREFIX.length());
        String start = new String(data, 0, window, StandardCharsets.UTF_8);
        return start.startsWith(LEGACY_PREFIX) || start.startsWith(LEGACY_GROUP_PREFIX);
    }
}
