package org.qortium.crosschain.monero;

import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWalletFull;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.MoneroWalletListener;
import java.math.BigInteger;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;

/** Mainnet full-wallet custody. Daemon sees view/scan traffic, never wallet keys. */
public final class MoneroJniWallet implements MoneroWalletBackend {
    private final MoneroWalletFull wallet;
    private MoneroJniWallet(MoneroWalletFull wallet) { this.wallet = wallet; }

    public static Factory factory(Path root, String daemon) {
        validateDaemon(daemon);
        return (keys, height) -> open(root, daemon, keys, height, false);
    }

    static void validateDaemon(String daemon) {
        URI uri;
        try { uri = URI.create(daemon); } catch (RuntimeException e) { throw new IllegalArgumentException("XMR daemon URI required"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                     && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost())))))
            throw new IllegalArgumentException("XMR daemon requires HTTPS, or numeric loopback HTTP, without credentials or a path");
    }

    // regtest is package-private for isolated acceptance; never controlled by API or Settings.
    static MoneroJniWallet open(Path configuredRoot, String daemon, MoneroKeys keys, long restoreHeight, boolean regtest) throws Exception {
        validateDaemon(daemon);
        if (restoreHeight < 0) throw new IllegalArgumentException("Explicit restore height required");
        MoneroNativeLoader.load();
        Files.createDirectories(configuredRoot);
        Path root = configuredRoot.toRealPath();
        Path store = privateDirectory(root.resolve(regtest ? "xmr-regtest-v1" : "xmr-mainnet-v1"));
        Path dir = privateDirectory(store.resolve(keys.walletId));
        Path walletPath = dir.resolve("wallet");
        Path marker = dir.resolve("identity");
        String identity = "derivation=1\nnetwork=" + (regtest ? "regtest" : "mainnet") + "\nwallet=" + keys.walletId + "\nrestoreHeight=" + restoreHeight + "\n";
        // A partial creation or corrupt checkpoint is an operator recovery task, never an implicit reset.
        boolean existing = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
        try (var children = Files.list(dir)) {
            if (children.anyMatch(Files::isSymbolicLink)) throw new IllegalStateException("Symlink in XMR wallet directory");
        }
        if (existing) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 1024
                    || !identity.equals(Files.readString(marker))
                    || !Files.isRegularFile(dir.resolve("wallet.keys"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(walletPath, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("XMR wallet identity or checkpoint mismatch");
        } else {
            try (var children = Files.list(dir)) {
                if (children.findAny().isPresent()) throw new IllegalStateException("Incomplete XMR wallet directory");
            }
        }
        MoneroWalletFull wallet = null;
        try {
            if (existing) {
                // The upstream config overload drops regtest; use the explicit overload.
                wallet = MoneroWalletFull.openWallet(walletPath.toString(), keys.password(), MoneroNetworkType.MAINNET,
                        (MoneroRpcConnection) null, regtest);
            } else {
                wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig()
                        .setPassword(keys.password()).setNetworkType(MoneroNetworkType.MAINNET).setRegtest(regtest)
                        .setPrivateSpendKey(keys.spendHex()) // native derives the view key; verify both below
                        .setRestoreHeight(restoreHeight).setLanguage("English"));
            }
            if (!keys.spendHex().equals(wallet.getPrivateSpendKey()) || !keys.viewHex().equals(wallet.getPrivateViewKey()))
                throw new IllegalStateException("XMR native identity mismatch");
            wallet.setDaemonConnection(new MoneroRpcConnection(daemon), false); // never auto-trust a local daemon
            if (!existing) {
                long tip;
                try { tip = wallet.getDaemonHeight(); }
                catch (Exception e) { throw new AdmissionRejected("XMR_DAEMON_UNAVAILABLE"); }
                if (tip <= 0) throw new AdmissionRejected("XMR_DAEMON_UNAVAILABLE");
                if (restoreHeight > tip) throw new AdmissionRejected("XMR_RESTORE_HEIGHT_ABOVE_TIP");
                // Validate the restore bound on an in-memory wallet before any durable identity/checkpoint.
                Files.writeString(marker, identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
                try (var channel = FileChannel.open(marker, StandardOpenOption.WRITE)) { channel.force(true); }
                try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
                wallet.moveTo(walletPath.toString());
            }
            wallet.save();
            try (var children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
                        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rw-------"));
                }
            }
            return new MoneroJniWallet(wallet);
        } catch (Exception | LinkageError e) {
            if (wallet != null) wallet.close(false);
            throw e;
        }
    }

    private static Path privateDirectory(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Invalid XMR storage directory");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        return path;
    }

    @Override public Snapshot read() {
        // Never run upstream's background sync: several getters are not protected by its sync lock.
        // Cooperatively yield at native chunk boundaries; all sync/getters/save stay on Core's lane.
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        wallet.sync(new MoneroWalletListener() {
            @Override public void onSyncProgress(long height, long start, long end, double percent, String message) {
                if (height - start >= 2048 || System.nanoTime() > until) wallet.stopSyncing();
            }
        });
        long height = wallet.getHeight();
        long target = wallet.getDaemonHeight();
        boolean synced = wallet.isConnectedToDaemon() && wallet.isDaemonSynced() && wallet.isSynced() && target > 0 && height >= target;
        var history = new ArrayList<Transaction>();
        for (var tx : wallet.getTxs()) {
            Long timestamp = tx.getBlock() == null ? tx.getReceivedTimestamp() : tx.getBlock().getTimestamp();
            history.add(new Transaction(tx.getHash(), timestamp, tx.getHeight(), Boolean.TRUE.equals(tx.isConfirmed()),
                    atomic(tx.getIncomingAmount()), atomic(tx.getOutgoingAmount()), atomic(tx.getFee())));
        }
        history.sort(Comparator.comparing(Transaction::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Transaction::txid));
        wallet.save();
        return new Snapshot(wallet.getPrimaryAddress(), height, target, synced,
                atomic(wallet.getBalance()), atomic(wallet.getUnlockedBalance()), history.stream().limit(100).toList());
    }

    private static String atomic(BigInteger amount) { return amount == null ? null : amount.toString(); }
    @Override public void close() { wallet.close(true); } // close requests native shutdown; no reuse afterwards
}
