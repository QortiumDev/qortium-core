package org.qortium.crosschain.monero;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Internal journal contracts only. No REST, native or bridge send capability. */
final class MoneroSendContracts {
    static final BigInteger MAX_ATOMIC = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    static final int MAX_ENTRIES = 4096;
    enum State { PREPARING, PREPARED, CANCELLED, EXPIRED, PREPARE_FAILED, RELAYING, BROADCAST, UNKNOWN, CONFIRMED_WAIT, CONFIRMED }
    static boolean tombstone(State s) { return s == State.CANCELLED || s == State.EXPIRED || s == State.PREPARE_FAILED; }
    static boolean holds(State s) { return !tombstone(s) && s != State.CONFIRMED; }
    static void require(boolean b) { if (!b) throw new IllegalArgumentException("Invalid XMR send contract"); }
    static String uuid(String s) { require(s != null && s.equals(UUID.fromString(s).toString())); return s; }
    static String hex(String s, int length) { require(s != null && s.length() == length && s.matches("[0-9a-f]+")); return s; }
    static BigInteger atomic(String s, boolean positive) {
        require(s != null && s.matches("0|[1-9][0-9]{0,19}"));
        BigInteger n = new BigInteger(s); require(n.compareTo(MAX_ATOMIC) <= 0 && (!positive || n.signum() > 0)); return n;
    }
    static String digest(String... values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
                Arrays.fill(bytes, (byte) 0);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    record Request(String operationId, String address, String amountAtomic) {
        Request {
            uuid(operationId);
            // Native/checksum/network validation is a later adapter gate; do not mistake this syntax for acceptance.
            require(address != null && address.matches("[1-9A-HJ-NP-Za-km-z]{95}"));
            atomic(amountAtomic, true);
        }
        String digest() { return MoneroSendContracts.digest("XMR/mainnet/send-v1/NORMAL", operationId, address, amountAtomic); }
        @Override public String toString() { return "XMR send request [redacted]"; }
    }
    record Candidate(String address, String amountAtomic, String feeAtomic, String txid,
                     String metadata, String fullHex, List<String> inputKeyImages) {
        Candidate {
            require(address != null && address.matches("[1-9A-HJ-NP-Za-km-z]{95}"));
            BigInteger amount = atomic(amountAtomic, true), fee = atomic(feeAtomic, true);
            require(amount.add(fee).compareTo(MAX_ATOMIC) <= 0); hex(txid, 64);
            require(metadata != null && !metadata.isEmpty() && metadata.length() <= 2 * 1024 * 1024
                    && metadata.length() % 2 == 0 && metadata.matches("[0-9a-f]+"));
            require(fullHex != null && !fullHex.isEmpty() && fullHex.length() <= 512 * 1024
                    && fullHex.length() % 2 == 0 && fullHex.matches("[0-9a-f]+"));
            require(inputKeyImages != null && !inputKeyImages.isEmpty() && inputKeyImages.size() <= 1024);
            inputKeyImages = List.copyOf(inputKeyImages);
            inputKeyImages.forEach(s -> hex(s, 64));
            require(new HashSet<>(inputKeyImages).size() == inputKeyImages.size());
        }
        String digest() { return MoneroSendContracts.digest(address, amountAtomic, feeAtomic, txid, metadata, fullHex, String.join(",", inputKeyImages)); }
        @Override public String toString() { return "XMR signed candidate [redacted]"; }
    }
    record Entry(String operationId, State state, String requestDigest, Request request, Candidate candidate,
                 String artifactDigest, String session, long createdAt, long expiresAt, State cancellation, long confirmations, boolean unlocked) {
        Entry {
            uuid(operationId); require(state != null); hex(requestDigest, 64); uuid(session);
            require(createdAt >= 0 && expiresAt >= createdAt);
            if (artifactDigest != null) hex(artifactDigest, 64);
            if (tombstone(state)) require(request == null && candidate == null && cancellation == null);
            else {
                require(request != null && operationId.equals(request.operationId()) && requestDigest.equals(request.digest()));
                if (state == State.PREPARING) require(candidate == null && artifactDigest == null);
                else require(candidate != null && candidate.address().equals(request.address())
                        && candidate.amountAtomic().equals(request.amountAtomic()) && candidate.digest().equals(artifactDigest));
            }
            require(cancellation == null || (state == State.PREPARING && (cancellation == State.CANCELLED || cancellation == State.EXPIRED)));
            require(confirmations >= 0);
            if (state == State.CONFIRMED) require(confirmations >= 10 && unlocked);
            else if (state == State.CONFIRMED_WAIT) require(confirmations > 0 && !(confirmations >= 10 && unlocked));
            else require(confirmations == 0 && !unlocked);
        }
        Entry state(State next) {
            return new Entry(operationId, next, requestDigest, tombstone(next) ? null : request,
                    tombstone(next) ? null : candidate, artifactDigest, session, createdAt, expiresAt, null, 0, false);
        }
        Entry observed(State next, long depth, boolean unlocked) {
            return new Entry(operationId, next, requestDigest, request, candidate, artifactDigest, session,
                    createdAt, expiresAt, null, depth, unlocked);
        }
        String quoteDigest() { return candidate == null ? null : digest(requestDigest, artifactDigest, session, Long.toString(expiresAt)); }
        @Override public String toString() { return "XMR operation " + state; }
    }
    record Ledger(int version, long sequence, Map<String, Entry> entries, boolean nativePaired) {
        Ledger(int version, long sequence, Map<String, Entry> entries) { this(version, sequence, entries, false); }
        Ledger {
            require(version == 1 && sequence >= 0 && entries != null && entries.size() <= MAX_ENTRIES);
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            entries.forEach((id, entry) -> require(entry != null && id.equals(entry.operationId())));
            long estimatedBytes = 1024;
            for (Entry entry : entries.values()) {
                estimatedBytes += 2048;
                if (entry.candidate() != null) estimatedBytes += entry.candidate().metadata().length()
                        + entry.candidate().fullHex().length() + entry.candidate().inputKeyImages().size() * 68L;
            }
            require(estimatedBytes <= 16 * 1024 * 1024 - 64); // bound before JSON serialization allocates
            // Reorgs can turn previously confirmed operations back into multiple historical holds.
            require(entries.values().stream().filter(e -> e.state == State.PREPARING || e.state == State.PREPARED).count() <= 1);
        }
        @Override public String toString() { return "XMR encrypted ledger [redacted]"; }
    }
    record View(String operationId, State state, String quoteDigest, String address,
                String amountAtomic, String feeAtomic, String txid, boolean walletHeld) {
        @Override public String toString() { return "XMR send view [redacted]"; }
    }
}
