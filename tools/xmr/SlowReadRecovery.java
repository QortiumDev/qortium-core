package org.qortium.crosschain.monero;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Packaged production JNI/service with public synthetic offline regtest only. */
public final class SlowReadRecovery {
    public static void main(String[] args) throws Exception {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        Path root = Path.of(args[0]);
        byte[] seed = new byte[32]; seed[0] = 1;
        MoneroWalletBackend nativeWallet;
        try (var keys = new MoneroKeys(seed)) {
            nativeWallet = MoneroJniWallet.open(root.resolve("wallets"), args[1], keys, 0, true);
        }
        AtomicInteger reads = new AtomicInteger(), opens = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean(), delayedReadSucceeded = new AtomicBoolean();
        MoneroWalletBackend wrapper = new MoneroWalletBackend() {
            public Snapshot read() throws Exception { return read(ignored -> {}, ignored -> {}); }
            public Snapshot read(Consumer<ScanProgress> progress, Consumer<ReadPhase> phase) throws Exception {
                int read = reads.incrementAndGet();
                if (read == 2) Files.writeString(root.resolve("arm-delay"), "armed");
                Snapshot next = nativeWallet.read(progress, phase);
                if (read == 2) delayedReadSucceeded.set(true);
                return next;
            }
            public void close() throws Exception { nativeWallet.close(); closed.set(true); }
        };
        MoneroWalletService.Failure incident;
        try (var service = new MoneroWalletService((keys, height) -> {
            opens.incrementAndGet(); return wrapper;
        }, Duration.ofMillis(1500), Duration.ofMillis(200))) {
            var session = service.activate(seed, 0, null);
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (service.overdueRead() == null) {
                service.session(); // observe elapsed inactivity without making native calls
                if (System.nanoTime() > until) throw new AssertionError("Read did not become overdue");
                Thread.sleep(10);
            }
            incident = service.overdueRead();
            if (!"UNAVAILABLE".equals(service.status(session.sessionId()).state())
                    || service.status(session.sessionId()).wallet() != null
                    || service.status(session.sessionId()).progress() != null || closed.get()
                    || incident.phase() != MoneroWalletService.Phase.SCAN || incident.advances() != 0
                    || incident.readPhase() != MoneroWalletBackend.ReadPhase.SYNC)
                throw new AssertionError("Unexpected overdue state");
            while (!delayedReadSucceeded.get() || !"READY".equals(service.status(session.sessionId()).state())) {
                if (System.nanoTime() > until) throw new AssertionError("Read did not recover");
                Thread.sleep(10);
            }
            if (service.failure() != null || service.overdueRead() != null || opens.get() != 1
                    || closed.get() || service.status(session.sessionId()).wallet() == null)
                throw new AssertionError("Recovery violated worker/owner availability");
        }
        if (!closed.get()) throw new AssertionError("Serialized shutdown did not close backend");
        System.out.println("{\"productionJni\":true,\"offlineRegtest\":true,\"reads\":" + reads.get()
                + ",\"deadlineMillis\":1500,\"readPhase\":\"" + incident.readPhase()
                + "\",\"overdueState\":\"UNAVAILABLE\",\"recoveredState\":\"READY\",\"opens\":" + opens.get()
                + ",\"nativeReadSucceeded\":true,\"serializedShutdownClosed\":true}");
    }
}
