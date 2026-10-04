package org.qortium.crosschain.monero;

import org.junit.Test;
import org.qortium.crosschain.WalletScanStart;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MoneroScanStartStorageTests {
    private static final String ID = "ab".repeat(32);
    private void checkpoint(Path dir, long height) throws Exception {
        Files.writeString(dir.resolve("identity"), "derivation=1\nnetwork=regtest\nwallet=" + ID + "\nrestoreHeight=" + height + "\n");
        Files.writeString(dir.resolve("wallet"), "cache"); Files.writeString(dir.resolve("wallet.keys"), "keys");
    }
    @Test public void newTipIntentRetainsExactHeightAcrossLostReplyAndCheckpointReopen() throws Exception {
        Path dir = Files.createTempDirectory("scan-intent"); AtomicInteger calls = new AtomicInteger();
        var first = MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.newAtTip(), () -> { calls.incrementAndGet(); return 200; });
        assertEquals(199, first.height()); assertFalse(Files.exists(dir.resolve("identity")));
        var retry = MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.newAtTip(), () -> { fail("must reuse reserved height"); return 999; });
        assertEquals(first, retry); assertEquals(1, calls.get());
        checkpoint(dir,199);
        assertEquals(first, MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.resume(), () -> { throw new AssertionError(); }));
        assertEquals(first, MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.restore(199), () -> { throw new AssertionError(); }));
        byte[] identity = Files.readAllBytes(dir.resolve("identity"));
        assertThrows(MoneroWalletBackend.AdmissionRejected.class, () -> MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.restore(198), () -> 999));
        assertArrayEquals(identity, Files.readAllBytes(dir.resolve("identity")));
    }
    @Test public void legacyResumeNeverPromotesExistingWalletToKnownNew() throws Exception {
        Path dir = Files.createTempDirectory("scan-legacy"); checkpoint(dir,50);
        assertEquals(50, MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.resume(), () -> { throw new AssertionError(); }).height());
        assertThrows(MoneroWalletBackend.AdmissionRejected.class, () -> MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.newAtTip(), () -> 200));
        assertFalse(Files.exists(dir.resolve("scan-start-v1")));
        Files.delete(dir.resolve("wallet"));
        assertThrows(IllegalStateException.class, () -> MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.resume(), () -> 200));
    }
    @Test public void invalidFreshHeightAndCorruptIntentDoNotCreateWalletOrChangePolicy() throws Exception {
        Path dir = Files.createTempDirectory("scan-bounds");
        assertThrows(MoneroWalletBackend.AdmissionRejected.class, () -> MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.restore(201), () -> 200));
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
        Files.writeString(dir.resolve("scan-start-v1"), "version=1\nmode=NEW_AT_CURRENT_TIP\nheight=oops\n");
        assertThrows(IllegalStateException.class, () -> MoneroScanStartStorage.resolve(dir, ID, true, WalletScanStart.resume(), () -> 200));
        assertFalse(Files.exists(dir.resolve("identity")));
    }
}
