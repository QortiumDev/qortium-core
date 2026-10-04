package org.qortium.crosschain.monero;

import monero.common.MoneroRpcConnection;
import monero.daemon.model.MoneroNetworkType;
import monero.wallet.MoneroWalletFull;
import monero.wallet.model.MoneroWalletConfig;
import monero.wallet.model.*;
import monero.common.MoneroUtils;
import java.util.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;

/** Mainnet full-wallet custody. Daemon sees view/scan traffic, never wallet keys. */
public final class MoneroJniWallet implements MoneroSendBackend {
    // An uncertain native close must never unlock another process; retained until JVM exit.
    private static final Set<MoneroSendJournal.Root> UNCERTAIN_ROOTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    enum PairingBarrier { CACHE_SYNCED, JOURNAL_PAIRED, MARKER_WRITTEN, MARKER_SYNCED }
    private final MoneroWalletFull wallet;
    private MoneroDaemonPool daemons;
    private final MoneroSendJournal journal;
    private final MoneroSendJournal.Root root;
    private final boolean ownsRoot;
    private final MoneroSendMachine machine;
    private MoneroJniWallet(MoneroWalletFull wallet, MoneroSendJournal journal, MoneroSendJournal.Root root, boolean ownsRoot, MoneroSendMachine machine) {
        this.wallet = wallet; this.journal = journal; this.root = root; this.ownsRoot = ownsRoot; this.machine = machine;
    }

    public static Factory factory(Path root, String daemon) {
        return factory(root, daemon, false);
    }
    static Factory factory(Path configuredRoot, String daemon, boolean regtest) {
        return factory(configuredRoot, daemon, regtest, barrier -> { });
    }
    public static Factory factory(Path root, List<String> daemons) {
        return factory(root, daemons, false, barrier -> { });
    }
    static Factory factory(Path configuredRoot, String daemon, boolean regtest, java.util.function.Consumer<PairingBarrier> pairingFault) {
        validateDaemon(daemon);
        return factory(configuredRoot, List.of(daemon), regtest, pairingFault);
    }
    static Factory factory(Path configuredRoot, List<String> endpoints, boolean regtest, java.util.function.Consumer<PairingBarrier> pairingFault) {
        // Validate once before acquiring storage/native resources; health is shared across account switches.
        var daemons = new MoneroDaemonPool(endpoints, regtest);
        return new Factory() {
            private MoneroSendJournal.Root lock;
            public MoneroWalletBackend open(MoneroKeys keys, long height) throws Exception {
                if (lock == null) lock = lockRoot(configuredRoot, regtest);
                lock.check();
                return openLocked(daemons, keys, height, regtest, lock, false, pairingFault);
            }
            public void close() { if (lock != null && !UNCERTAIN_ROOTS.contains(lock)) lock.close(); }
        };
    }
    private static MoneroSendJournal.Root lockRoot(Path configuredRoot, boolean regtest) throws Exception {
        Path absolute = configuredRoot.toAbsolutePath().normalize();
        for (Path path = absolute; path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("Invalid XMR storage root");
        Files.createDirectories(absolute);
        Path namespace = privateDirectory(absolute.resolve(regtest ? "xmr-regtest-v1" : "xmr-mainnet-v1"));
        return MoneroSendJournal.Root.open(namespace.resolve(".coordination"), regtest ? "regtest" : "mainnet");
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
        var lock = lockRoot(configuredRoot, regtest);
        try { return openLocked(new MoneroDaemonPool(List.of(daemon), regtest), keys, restoreHeight, regtest, lock, true, barrier -> { }); }
        catch (Exception | LinkageError e) { if (!UNCERTAIN_ROOTS.contains(lock)) lock.close(); throw e; }
    }
    private static void checkPair(boolean valid) { if (!valid) throw new MoneroSendJournal.Failure(); }
    private static MoneroJniWallet openLocked(MoneroDaemonPool daemons, MoneroKeys keys, long restoreHeight,
                                              boolean regtest, MoneroSendJournal.Root lock, boolean ownsRoot, java.util.function.Consumer<PairingBarrier> pairingFault) throws Exception {
        lock.check();
        if (restoreHeight < 0) throw new IllegalArgumentException("Explicit restore height required");
        Path paired = lock.path.getParent().resolve(keys.walletId).resolve("send-journal-v1");
        if (Files.exists(paired, LinkOption.NOFOLLOW_LINKS)) {
            checkPair(Files.isRegularFile(paired, LinkOption.NOFOLLOW_LINKS) && Files.size(paired) == 10
                    && Files.readString(paired).equals("journal=1\n"));
            checkPair(Files.isRegularFile(lock.path.resolve(keys.walletId).resolve("ledger.aesgcm"), LinkOption.NOFOLLOW_LINKS));
        }
        byte[] journalKey = keys.sendJournalKey();
        MoneroSendJournal journal;
        try { journal = lock.openWallet(keys.walletId, journalKey); }
        finally { Arrays.fill(journalKey, (byte) 0); }
        try {
            boolean marker = Files.exists(paired, LinkOption.NOFOLLOW_LINKS);
            checkPair(journal.read().nativePaired() == marker);
            if (marker) {
                Path nativeDirectory = paired.getParent();
                for (String name : List.of("identity", "wallet", "wallet.keys"))
                    checkPair(Files.isRegularFile(nativeDirectory.resolve(name), LinkOption.NOFOLLOW_LINKS));
            } else checkPair(journal.read().entries().isEmpty()); // only pristine first-use/legacy-read adoption
            MoneroSendMachine machine = new MoneroSendMachine(journal, UUID.randomUUID().toString(), System::currentTimeMillis, System::nanoTime);
            return openNative(daemons, keys, restoreHeight, regtest, lock, ownsRoot, journal, machine, pairingFault);
        } catch (Exception | LinkageError e) { journal.close(); throw e; }
    }
    private static MoneroJniWallet openNative(MoneroDaemonPool daemons, MoneroKeys keys, long restoreHeight, boolean regtest,
                                             MoneroSendJournal.Root lock, boolean ownsRoot, MoneroSendJournal journal,
                                             MoneroSendMachine machine, java.util.function.Consumer<PairingBarrier> pairingFault) throws Exception {
        MoneroNativeLoader.load();
        Path store = lock.path.getParent(); // already canonical/locked; never re-resolve configurable paths
        lock.check();
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
            // Reopened checkpoints can wait for provider recovery without resetting their native handle.
            daemons.resetConnection();
            if (!existing) {
                try { daemons.connect(wallet, 0); }
                catch (MoneroDaemonPool.Unavailable e) { throw new AdmissionRejected("XMR_DAEMON_UNAVAILABLE"); }
            }
            if (!existing) {
                long tip;
                try { tip = wallet.getDaemonHeight(); }
                catch (Exception e) { daemons.failed(); throw new AdmissionRejected("XMR_DAEMON_UNAVAILABLE"); }
                if (tip <= 0) { daemons.failed(); throw new AdmissionRejected("XMR_DAEMON_UNAVAILABLE"); }
                if (restoreHeight > tip) throw new AdmissionRejected("XMR_RESTORE_HEIGHT_ABOVE_TIP");
                // Validate the restore bound on an in-memory wallet before any durable identity/checkpoint.
                Files.writeString(marker, identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-------"));
                try (var channel = FileChannel.open(marker, StandardOpenOption.WRITE)) { channel.force(true); }
                try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
                wallet.moveTo(walletPath.toString());
            }
            wallet.save();
            Path paired = dir.resolve("send-journal-v1");
            if (!Files.exists(paired, LinkOption.NOFOLLOW_LINKS)) {
                // Cache must be durable before the authenticated pair bit. A partial pair fails closed on reopen.
                for (String name : List.of("identity", "wallet", "wallet.keys"))
                    try (var channel = FileChannel.open(dir.resolve(name), StandardOpenOption.WRITE)) { channel.force(true); }
                try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
                pairingFault.accept(PairingBarrier.CACHE_SYNCED);
                journal.markNativePaired();
                pairingFault.accept(PairingBarrier.JOURNAL_PAIRED);
                Files.writeString(paired, "journal=1\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(paired, PosixFilePermissions.fromString("rw-------"));
                pairingFault.accept(PairingBarrier.MARKER_WRITTEN);
                try (var channel = FileChannel.open(paired, StandardOpenOption.WRITE)) { channel.force(true); }
                try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
                pairingFault.accept(PairingBarrier.MARKER_SYNCED);
            }
            try (var children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
                        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rw-------"));
                }
            }
            var backend = new MoneroJniWallet(wallet, journal, lock, ownsRoot, machine);
            backend.daemons = daemons;
            return backend;
        } catch (Exception | LinkageError e) {
            if (wallet != null) {
                try { wallet.close(false); }
                catch (Exception | LinkageError closeError) { UNCERTAIN_ROOTS.add(lock); throw closeError; }
            }
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

    @Override public Snapshot read() throws Exception { return read(ignored -> { }); }

    @Override public Snapshot read(java.util.function.Consumer<ScanProgress> progress) throws Exception {
        return read(progress, ignored -> { });
    }

    @Override public Snapshot read(java.util.function.Consumer<ScanProgress> progress,
                                   java.util.function.Consumer<ReadPhase> phase) throws Exception {
        ReadPhase[] current = { ReadPhase.CHECK };
        try {
            var result = readConnected(progress, step -> { current[0] = step; phase.accept(step); });
            daemons.succeeded(); return result;
        } catch (MoneroDaemonPool.Unavailable e) { throw e;
        } catch (MoneroSendJournal.Failure e) { throw e;
        } catch (Exception e) {
            if (current[0] == ReadPhase.SYNC || current[0] == ReadPhase.DAEMON) throw daemons.failed();
            throw e;
        }
    }
    @Override public org.qortium.crosschain.WalletServerPool.Status servers() { return daemons.pool.status(); }
    private Snapshot readConnected(java.util.function.Consumer<ScanProgress> progress,
                                   java.util.function.Consumer<ReadPhase> phase) throws Exception {
        phase.accept(ReadPhase.CHECK);
        root.check();
        // Never run upstream's background sync: several getters are not protected by its sync lock.
        // Cooperatively yield at native chunk boundaries; all sync/getters/save stay on Core's lane.
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        phase.accept(ReadPhase.DAEMON);
        daemons.connect(wallet, wallet.getHeight());
        phase.accept(ReadPhase.SYNC);
        wallet.sync(new MoneroWalletListener() {
            @Override public void onSyncProgress(long height, long start, long end, double percent, String message) {
                progress.accept(new ScanProgress(height, end));
                if (height - start >= 2048 || System.nanoTime() > until) wallet.stopSyncing();
            }
        });
        phase.accept(ReadPhase.DAEMON);
        long height = wallet.getHeight();
        long target = wallet.getDaemonHeight();
        boolean synced = wallet.isConnectedToDaemon() && wallet.isDaemonSynced() && wallet.isSynced() && target > 0 && height >= target;
        phase.accept(ReadPhase.HISTORY);
        var history = new ArrayList<Transaction>();
        for (var tx : wallet.getTxs()) {
            Long timestamp = tx.getBlock() == null ? tx.getReceivedTimestamp() : tx.getBlock().getTimestamp();
            history.add(new Transaction(tx.getHash(), timestamp, tx.getHeight(), Boolean.TRUE.equals(tx.isConfirmed()),
                    atomic(tx.getIncomingAmount()), atomic(tx.getOutgoingAmount()), atomic(tx.getFee())));
        }
        history.sort(Comparator.comparing(Transaction::timestamp, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Transaction::txid));
        phase.accept(ReadPhase.SAVE);
        wallet.save();
        phase.accept(ReadPhase.BALANCE);
        return new Snapshot(wallet.getPrimaryAddress(), height, target, synced,
                atomic(wallet.getBalance()), atomic(wallet.getUnlockedBalance()), history.stream().limit(100).toList());
    }

    private static String atomic(BigInteger amount) { return amount == null ? null : amount.toString(); }
    @Override public void close() {
        try { wallet.close(true); }
        catch (Exception | LinkageError e) { UNCERTAIN_ROOTS.add(root); throw e; }
        journal.close();
        if (ownsRoot) root.close();
    }

    @Override public MoneroSendJournal journal() { return journal; }
    @Override public MoneroSendMachine sendMachine(String session) { machine.changeSession(session); return machine; }
    private void ready() {
        root.check();
        long target = wallet.getDaemonHeight();
        require(target > 0 && wallet.isConnectedToDaemon() && wallet.isDaemonSynced() && wallet.isSynced() && wallet.getHeight() >= target);
    }
    @Override public Candidate prepare(Request request) {
        ready();
        MoneroUtils.validateAddress(request.address(), MoneroNetworkType.MAINNET);
        require(request.address().length() == 95); // integrated addresses/payment IDs excluded
        BigInteger amount = MoneroSendContracts.atomic(request.amountAtomic(), true);
        require(wallet.getUnlockedBalance(0).compareTo(amount) > 0);
        var txs = wallet.createTxs(new MoneroTxConfig().setAccountIndex(0).setAddress(request.address())
                .setAmount(amount).setPriority(MoneroTxPriority.NORMAL).setCanSplit(false).setRelay(false));
        require(txs != null && txs.size() == 1);
        var tx = txs.get(0);
        require(Boolean.FALSE.equals(tx.isRelayed()) && !Boolean.TRUE.equals(tx.isFailed())
                && !Boolean.TRUE.equals(tx.isConfirmed()) && !Boolean.TRUE.equals(tx.inTxPool()));
        require(tx.getUnlockTime() != null && tx.getUnlockTime().signum() == 0);
        var transfer = tx.getOutgoingTransfer();
        require(transfer != null && Integer.valueOf(0).equals(transfer.getAccountIndex())
                && transfer.getDestinations() != null && transfer.getDestinations().size() == 1);
        var destination = transfer.getDestinations().get(0);
        require(request.address().equals(destination.getAddress()) && amount.equals(destination.getAmount())
                && amount.equals(tx.getOutgoingAmount()));
        require(tx.getInputsWallet() != null && !tx.getInputsWallet().isEmpty());
        Candidate candidate = new Candidate(request.address(), request.amountAtomic(), tx.getFee().toString(),
                tx.getHash(), tx.getMetadata(), tx.getFullHex(),
                tx.getInputsWallet().stream().map(input -> input.getKeyImage().getHex()).toList());
        validateRelay(candidate);
        wallet.save();
        return candidate;
    }
    @Override public void validateRelay(Candidate candidate) {
        ready();
        require(wallet.getUnlockedBalance(0).compareTo(MoneroSendContracts.atomic(candidate.amountAtomic(), true).add(MoneroSendContracts.atomic(candidate.feeAtomic(), true))) >= 0);
        Set<String> available = new HashSet<>();
        for (var output : wallet.getOutputs(new MoneroOutputQuery().setAccountIndex(0).setIsSpent(false).setIsFrozen(false))) {
            if (output.getKeyImage() != null && output.getTx() != null && Boolean.FALSE.equals(output.getTx().isLocked()))
                available.add(output.getKeyImage().getHex());
        }
        require(available.containsAll(candidate.inputKeyImages()));
    }
    @Override public String relay(Candidate candidate) {
        root.check();
        String hash = wallet.relayTx(candidate.metadata());
        wallet.save(); // a checkpoint failure after relay is still an unknown outcome
        return hash;
    }
    @Override public Map<String, MoneroSendMachine.Observation> observe(Map<String, String> operationHashes) {
        root.check();
        wallet.sync(); // untrusted ordinary sync; no upstream background worker or scanTxs
        ready();
        Map<String, MoneroSendMachine.Observation> result = new LinkedHashMap<>();
        for (var entry : operationHashes.entrySet()) {
            var tx = wallet.getTx(entry.getValue()); // exact local lookup, independent of capped UI history
            if (tx == null || !entry.getValue().equals(tx.getHash()) || Boolean.TRUE.equals(tx.isFailed())) continue;
            boolean confirmed = Boolean.TRUE.equals(tx.isConfirmed());
            long depth = confirmed && tx.getNumConfirmations() != null ? tx.getNumConfirmations() : 0;
            if (confirmed && depth <= 0) continue;
            result.put(entry.getKey(), new MoneroSendMachine.Observation(tx.getHash(), confirmed, depth,
                    confirmed && Boolean.FALSE.equals(tx.isLocked()), !confirmed && Boolean.TRUE.equals(tx.inTxPool())));
        }
        wallet.save();
        return result;
    }
}
