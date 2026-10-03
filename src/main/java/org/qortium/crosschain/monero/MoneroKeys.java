package org.qortium.crosschain.monero;

import org.bouncycastle.crypto.digests.KeccakDigest;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Derivation v1 consumes Home's already ticker-mixed 32-byte XMR seed, never its master seed. */
public final class MoneroKeys implements AutoCloseable {
    private static final BigInteger ORDER = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private final byte[] spend;
    private final byte[] view;
    private final byte[] password;
    public final String walletId;

    public MoneroKeys(byte[] coinSeed) {
        if (coinSeed == null || coinSeed.length != 32) throw new IllegalArgumentException("32-byte XMR seed required");
        spend = reduce(coinSeed);
        if (Arrays.equals(spend, new byte[32])) throw new IllegalArgumentException("Invalid XMR scalar");
        byte[] hash = new byte[32];
        KeccakDigest digest = new KeccakDigest(256); // Keccak, deliberately NOT SHA3-256.
        digest.update(spend, 0, spend.length);
        digest.doFinal(hash, 0);
        view = reduce(hash);
        Arrays.fill(hash, (byte) 0);
        // Key storage and password are domain separated, independent of UI account labels.
        walletId = hex(domainHash("Qortium/XMR/mainnet/derivation-v1/identity", spend));
        password = domainHash("Qortium/XMR/mainnet/derivation-v1/storage-password", spend);
    }

    static byte[] reduce(byte[] littleEndian) {
        byte[] bigEndian = littleEndian.clone();
        reverse(bigEndian);
        byte[] reduced = new BigInteger(1, bigEndian).mod(ORDER).toByteArray();
        byte[] result = new byte[32];
        for (int i = 0; i < Math.min(32, reduced.length); i++) result[i] = reduced[reduced.length - 1 - i];
        Arrays.fill(bigEndian, (byte) 0);
        Arrays.fill(reduced, (byte) 0);
        return result;
    }

    static byte[] domainHash(String domain, byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    static void reverse(byte[] value) {
        for (int i = 0; i < value.length / 2; i++) {
            byte b = value[i]; value[i] = value[value.length - i - 1]; value[value.length - i - 1] = b;
        }
    }

    public static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    public String spendHex() { return hex(spend); }
    public String viewHex() { return hex(view); }
    String password() { return hex(password); }

    @Override public void close() {
        Arrays.fill(spend, (byte) 0); Arrays.fill(view, (byte) 0); Arrays.fill(password, (byte) 0);
    }
}
