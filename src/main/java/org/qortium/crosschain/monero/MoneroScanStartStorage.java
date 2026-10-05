package org.qortium.crosschain.monero;

import org.qortium.crosschain.WalletScanStart;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;

/** Locked wallet namespace only. Intent survives a lost acknowledgement, never rewrites a checkpoint. */
final class MoneroScanStartStorage {
    record Selected(String mode, long height) { }
    @FunctionalInterface interface Tip { long height() throws Exception; }
    static Selected resolve(Path dir, String walletId, boolean regtest, WalletScanStart request, Tip tip) throws Exception {
        Path identity = dir.resolve("identity"), intent = dir.resolve("scan-start-v1");
        boolean existing = Files.exists(identity, LinkOption.NOFOLLOW_LINKS);
        String prefix = "derivation=1\nnetwork=" + (regtest ? "regtest" : "mainnet") + "\nwallet=" + walletId + "\nrestoreHeight=";
        Long savedHeight = null;
        if (existing) {
            String value = read(identity);
            if (!value.startsWith(prefix) || !value.substring(prefix.length()).matches("(0|[1-9][0-9]{0,8})\n"))
                throw new IllegalStateException("XMR wallet identity mismatch");
            savedHeight = Long.parseLong(value.substring(prefix.length()).trim());
            if (savedHeight > 500_000_000L) throw new IllegalStateException("XMR wallet identity mismatch");
            // Corruption/lost caches stay fatal before a policy mismatch can mask them.
            for (String name : java.util.List.of("wallet", "wallet.keys"))
                if (!Files.isRegularFile(dir.resolve(name), LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("XMR wallet checkpoint missing");
        }
        Selected saved = null;
        if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
            var m = Pattern.compile("version=1\\nmode=(RESUME|RESTORE_FROM_HEIGHT|NEW_AT_CURRENT_TIP)\\nheight=(0|[1-9][0-9]{0,8})\\n").matcher(read(intent));
            if (!m.matches()) throw new IllegalStateException("Invalid XMR scan intent");
            saved = new Selected(m.group(1), Long.parseLong(m.group(2)));
            if (saved.height > 500_000_000L || (savedHeight != null && savedHeight != saved.height))
                throw new IllegalStateException("XMR scan intent mismatch");
        }
        if (existing) {
            Selected recorded = saved == null ? new Selected("RESTORE_FROM_HEIGHT", savedHeight) : saved;
            requireCompatible(request, recorded, saved != null);
            return recorded;
        }
        // Only a pristine, intent-only namespace may retry first-use initialization.
        try (var children = Files.list(dir)) {
            if (children.anyMatch(p -> !p.getFileName().toString().equals("scan-start-v1")))
                throw new IllegalStateException("Incomplete XMR wallet directory");
        }
        if (saved != null) { requireCompatible(request, saved, true); return saved; }
        long count = tip.height();
        if (count < 1 || count > 500_000_000L) throw new MoneroWalletBackend.AdmissionRejected("XMR_DAEMON_UNAVAILABLE");
        long height = request.height() == null ? 0 : request.height();
        if (request.mode() == WalletScanStart.Mode.NEW_AT_CURRENT_TIP)
            height = count - 1; // get_info.height is block count; last existing block index is count - 1.
        if (height > count) throw new MoneroWalletBackend.AdmissionRejected("XMR_RESTORE_HEIGHT_ABOVE_TIP");
        Selected chosen = new Selected(request.mode().name(), height);
        Files.writeString(intent, "version=1\nmode=" + chosen.mode + "\nheight=" + height + "\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(intent, PosixFilePermissions.fromString("rw-------"));
        try (var file = FileChannel.open(intent, StandardOpenOption.WRITE)) { file.force(true); }
        try (var file = FileChannel.open(dir, StandardOpenOption.READ)) { file.force(true); }
        return chosen;
    }
    private static String read(Path p) throws Exception {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > 1024)
            throw new IllegalStateException("Invalid XMR initialization metadata");
        return Files.readString(p);
    }
    private static void requireCompatible(WalletScanStart request, Selected saved, boolean explicitIntent) throws Exception {
        if (request.mode() == WalletScanStart.Mode.RESUME) return;
        if (request.mode() == WalletScanStart.Mode.NEW_AT_CURRENT_TIP) {
            if (explicitIntent && saved.mode.equals("NEW_AT_CURRENT_TIP")) return;
            throw new MoneroWalletBackend.AdmissionRejected("XMR_EXISTING_WALLET");
        }
        if (request.height() != saved.height) throw new MoneroWalletBackend.AdmissionRejected("XMR_RESTORE_HEIGHT_MISMATCH");
    }
}
