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
 * Two formats are accepted:
 * <ul>
 *   <li><b>v1 binary envelope</b> (preferred) — a fixed header that clients prepend to the ciphertext.
 *       See {@code docs/qdn/encrypted-data-envelope.md} for the full specification.</li>
 *   <li><b>legacy text prefix</b> — the original {@code qdnEncryptedData} / {@code qdnGroupEncryptedData}
 *       ASCII markers, still accepted so existing resources keep working.</li>
 * </ul>
 * This is purely a publish/read-time check; it is not part of consensus (arbitrary data is an opaque
 * hash to the chain), so it can evolve without a coordinated network upgrade.
 */
public final class EncryptedDataEnvelope {

    // --- v1 binary envelope ---
    // Layout (big-endian):
    //   [0:4]   magic      = "QENC"
    //   [4]     version    = 0x01
    //   [5]     mode       = 0x01 recipients (PUBLISHER/ACCOUNTS) | 0x02 group
    //   [6]     cipher     = 0x01 AES-256-GCM
    //   [7]     flags      = reserved (0)
    //   [8:10]  headerLen  = uint16, length of the mode-specific header that follows
    //   [10 : 10+headerLen]   header (key material + nonce; opaque to Core)
    //   [10+headerLen : ]     ciphertext incl. AEAD tag (opaque to Core)
    public static final byte[] MAGIC = { 'Q', 'E', 'N', 'C' };
    public static final byte VERSION_1 = 0x01;

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

    // --- legacy text prefixes (still accepted) ---
    public static final String LEGACY_PREFIX = "qdnEncryptedData";
    public static final String LEGACY_GROUP_PREFIX = "qdnGroupEncryptedData";

    private EncryptedDataEnvelope() {
    }

    /** True if the data is encrypted by either the v1 envelope or a legacy prefix. */
    public static boolean isEncrypted(byte[] data) {
        return isEnvelope(data) || hasLegacyPrefix(data);
    }

    /**
     * True if {@code data} begins with a structurally-valid v1 encrypted envelope. {@code data} may be
     * a truncated leading window of a larger file (Core only inspects the first portion); the header is
     * small and always fits within that window.
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

        if (data[4] != VERSION_1) {
            return false;
        }

        byte mode = data[5];
        if (mode != MODE_RECIPIENTS && mode != MODE_GROUP) {
            return false;
        }

        if (data[6] != CIPHER_AES_256_GCM) {
            return false;
        }

        // data[7] = flags, reserved/ignored

        int headerLen = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        if (headerLen <= 0 || headerLen > MAX_VARIABLE_HEADER_LENGTH) {
            return false;
        }

        // The fixed + variable header must fit within the inspected window, and there must be at least
        // one byte of ciphertext after it (an envelope with no ciphertext is not valid encrypted data).
        return data.length > FIXED_HEADER_LENGTH + headerLen;
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
