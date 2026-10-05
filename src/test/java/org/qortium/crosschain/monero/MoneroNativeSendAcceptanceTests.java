package org.qortium.crosschain.monero;

import com.fasterxml.jackson.databind.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

/** Explicitly enabled public-fixture, offline regtest tests through Core's internal service path. */
public class MoneroNativeSendAcceptanceTests {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    String daemon;
    static JsonNode rpc(String daemon, String method, Map<String, Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(daemon + "/json_rpc")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", "send-acceptance", "method", method, "params", params)))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); var body = JSON.readTree(response.body());
        assertFalse(body.has("error")); return body.get("result");
    }
    @Before public void offlineOnly() throws Exception {
        daemon = System.getProperty("qortium.xmr.regtestDaemon"); Assume.assumeNotNull(daemon);
        assertEquals("127.0.0.1", URI.create(daemon).getHost()); assertEquals("http", URI.create(daemon).getScheme());
        assertTrue(rpc(daemon, "get_info", Map.of()).get("offline").asBoolean());
    }
    static byte[] seed(int index) throws Exception { return HexFormat.of().parseHex(MoneroKeysTests.fixtures().get(index).get("coinSeed").asText()); }
    static String address(int index) throws Exception { return MoneroKeysTests.fixtures().get(index).get("address").asText(); }
    static String activate(MoneroWalletService service, int index, String old) throws Exception {
        String session = service.activate(seed(index), 0, old).sessionId();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"READY".equals(service.status(session).state())) {
            if (System.nanoTime() > until) throw new AssertionError("native activation timeout");
            if ("RESTART_REQUIRED".equals(service.status(session).state())) throw new AssertionError("native activation failed");
            Thread.sleep(100);
        }
        return session;
    }
    static void awaitReady(MoneroWalletService service, String session) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!"READY".equals(service.status(session).state())) {
            if (System.nanoTime() > until || "RESTART_REQUIRED".equals(service.status(session).state()))
                throw new AssertionError("native scan-start activation: " + service.status(session).state());
            Thread.sleep(100);
        }
    }
    static class FaultFactory implements MoneroWalletBackend.Factory {
        final MoneroWalletBackend.Factory delegate;
        final String fault;
        final AtomicInteger relays = new AtomicInteger();
        FaultFactory(Path root, String daemon, String fault) { delegate = MoneroJniWallet.factory(root, daemon, true); this.fault = fault; }
        public MoneroWalletBackend open(MoneroKeys keys, long height) throws Exception {
            var wallet = (MoneroSendBackend) delegate.open(keys, height);
            return new MoneroSendBackend() {
                public MoneroSendJournal journal() { return wallet.journal(); }
                public MoneroSendMachine sendMachine(String session) { return wallet.sendMachine(session); }
                public Snapshot read() throws Exception { return wallet.read(); }
                public Candidate prepare(Request request) throws Exception { return wallet.prepare(request); }
                public void validateRelay(Candidate candidate) throws Exception { wallet.validateRelay(candidate); }
                public Map<String, MoneroSendMachine.Observation> observe(Map<String, String> hashes) throws Exception { return wallet.observe(hashes); }
                public String relay(Candidate candidate) throws Exception {
                    relays.incrementAndGet();
                    if ("before".equals(fault)) Runtime.getRuntime().halt(24);
                    String hash = wallet.relay(candidate);
                    if ("after".equals(fault)) Runtime.getRuntime().halt(25);
                    if ("lost".equals(fault)) throw new IllegalStateException("synthetic lost native response");
                    return hash;
                }
                public void close() throws Exception { wallet.close(); }
            };
        }
        public void close() throws Exception { delegate.close(); }
    }
    static MoneroWalletService service(MoneroWalletBackend.Factory factory) {
        return new MoneroWalletService(factory, Duration.ofSeconds(60), Duration.ofMillis(200));
    }
    @Test public void knownNewAtTipSeesFutureReceiptsAndResumeRetainsSelectedBoundary() throws Exception {
        Path root = temp.getRoot().toPath(); long count = rpc(daemon, "get_info", Map.of()).get("height").asLong();
        String session;
        long chosen;
        try (var service = service(MoneroJniWallet.factory(root, daemon, true))) {
            session = service.activate(seed(1), org.qortium.crosschain.WalletScanStart.newAtTip(), null).sessionId();
            awaitReady(service, session);
            chosen = service.status(session).restoreHeight(); assertEquals(count - 1, chosen);
            assertEquals(address(1), service.status(session).wallet().address());
            rpc(daemon, "generateblocks", Map.of("wallet_address", address(1), "amount_of_blocks", 65));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (new java.math.BigInteger(service.status(session).wallet().balanceAtomic()).signum() == 0) {
                if (System.nanoTime() > until) fail("Future receipt not scanned"); Thread.sleep(100);
            }
        }
        try (var service = service(MoneroJniWallet.factory(root, daemon, true))) {
            String next = service.activate(seed(1), org.qortium.crosschain.WalletScanStart.resume(), null).sessionId();
            awaitReady(service, next);
            assertEquals(chosen, service.status(next).restoreHeight().longValue());
            assertTrue(new java.math.BigInteger(service.status(next).wallet().balanceAtomic()).signum() > 0);
            assertEquals(address(1), service.status(next).wallet().address());
        }
    }
    @Test public void legacyMarkerCannotLieAboutNativeRestoreHeightOnResume() throws Exception {
        Path root = temp.getRoot().toPath();
        rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 20));
        String id;
        try (var keys = new MoneroKeys(seed(1)); var factory = MoneroJniWallet.factory(root, daemon, true)) {
            id = keys.walletId;
            try (var wallet = factory.open(keys, 10)) { assertEquals(10, wallet.restoreHeight()); }
        }
        Path dir = root.resolve("xmr-regtest-v1").resolve(id);
        Files.delete(dir.resolve("scan-start-v1"));
        Path identity = dir.resolve("identity"); Files.writeString(identity, Files.readString(identity).replace("restoreHeight=10", "restoreHeight=0"));
        try (var keys = new MoneroKeys(seed(1)); var factory = MoneroJniWallet.factory(root, daemon, true)) {
            assertThrows(IllegalStateException.class, () -> factory.open(keys, org.qortium.crosschain.WalletScanStart.resume()));
        }
    }

    @Test public void exactNativePreparationLostResponseRecoveryConfirmationAndRecipientBalance() throws Exception {
        rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 160));
        Path root = temp.getRoot().toPath(); FaultFactory factory = new FaultFactory(root, daemon, "lost");
        try (var service = service(factory)) {
            String sender = activate(service, 0, null);
            String recipient = activate(service, 1, sender);
            java.math.BigInteger initial = new java.math.BigInteger(service.status(recipient).wallet().balanceAtomic());
            sender = activate(service, 0, recipient);
            String invalid = address(1);
            invalid = invalid.substring(0, invalid.length() - 1) + (invalid.endsWith("1") ? "2" : "1");
            assertEquals(State.PREPARE_FAILED, service.prepareSend(new Request(UUID.randomUUID().toString(), invalid, "1000"), sender).get(60, TimeUnit.SECONDS).state());
            assertEquals(State.PREPARE_FAILED, service.prepareSend(new Request(UUID.randomUUID().toString(), address(1), MAX_ATOMIC.toString()), sender).get(60, TimeUnit.SECONDS).state());
            long poolBefore = rpc(daemon, "get_info", Map.of()).get("tx_pool_size").asLong();
            Request request = new Request(UUID.randomUUID().toString(), address(1), "10000000000");
            View quote = service.prepareSend(request, sender).get(60, TimeUnit.SECONDS);
            assertEquals(State.PREPARED, quote.state()); assertTrue(new java.math.BigInteger(quote.feeAtomic()).signum() > 0);
            assertEquals(0, factory.relays.get());
            assertEquals(poolBefore, rpc(daemon, "get_info", Map.of()).get("tx_pool_size").asLong());
            assertEquals(State.PREPARED, service.prepareSend(request, sender).get(60, TimeUnit.SECONDS).state());
            assertFalse(service.status(sender).send());
            View unknown = service.commitSend(request.operationId(), quote.quoteDigest(), sender).get(60, TimeUnit.SECONDS);
            assertEquals(State.UNKNOWN, unknown.state()); assertEquals(quote.txid(), unknown.txid());
            service.commitSend(request.operationId(), quote.quoteDigest(), sender).get(60, TimeUnit.SECONDS);
            assertEquals(1, factory.relays.get());
            service.reconcileSends(sender).get(60, TimeUnit.SECONDS);
            assertEquals(State.BROADCAST, service.sendStatus(request.operationId(), sender).state());
            rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 12));
            service.reconcileSends(sender).get(60, TimeUnit.SECONDS);
            View confirmed = service.sendStatus(request.operationId(), sender);
            assertEquals(State.CONFIRMED, confirmed.state()); assertFalse(confirmed.walletHeld());
            recipient = activate(service, 1, sender);
            assertEquals(initial.add(new java.math.BigInteger(request.amountAtomic())).toString(), service.status(recipient).wallet().balanceAtomic());
        }
        try (var service = service(MoneroJniWallet.factory(root, daemon, true))) { assertNotNull(activate(service, 0, null)); }
    }
    @Test public void nativeDaemonOutageBeforeCommitDoesNotClaimOrRelay() throws Exception {
        rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 160));
        var available = new java.util.concurrent.atomic.AtomicBoolean(true);
        var proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            try {
                if (!available.get()) {
                    exchange.sendResponseHeaders(503, -1); return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                var builder = HttpRequest.newBuilder(URI.create(daemon + exchange.getRequestURI())).timeout(Duration.ofSeconds(10))
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type != null) builder.header("Content-Type", type);
                var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (Exception e) {
                try { exchange.sendResponseHeaders(503, -1); } catch (Exception ignored) { }
            } finally { exchange.close(); }
        });
        proxy.start();
        try {
            String endpoint = "http://127.0.0.1:" + proxy.getAddress().getPort();
            FaultFactory factory = new FaultFactory(temp.getRoot().toPath(), endpoint, "normal");
            try (var service = service(factory)) {
                String session = activate(service, 0, null);
                Request request = new Request(UUID.randomUUID().toString(), address(1), "10000000000");
                View quote = service.prepareSend(request, session).get(60, TimeUnit.SECONDS);
                assertEquals(State.PREPARED, quote.state());
                long pool = rpc(daemon, "get_info", Map.of()).get("tx_pool_size").asLong();
                available.set(false);
                assertThrows(ExecutionException.class, () -> service.commitSend(request.operationId(), quote.quoteDigest(), session).get(30, TimeUnit.SECONDS));
                assertEquals(State.PREPARED, service.sendStatus(request.operationId(), session).state());
                assertEquals(0, factory.relays.get());
                assertEquals(pool, rpc(daemon, "get_info", Map.of()).get("tx_pool_size").asLong());
                assertEquals(State.CANCELLED, service.cancelSend(request.operationId(), session).state());
            }
        } finally { available.set(true); proxy.stop(0); }
    }
    @Test public void deletedJournalCannotBeRecreatedForPreviouslyPairedNativeWallet() throws Exception {
        Path root = temp.getRoot().toPath();
        try (var service = service(MoneroJniWallet.factory(root, daemon, true))) { activate(service, 0, null); }
        try (var keys = new MoneroKeys(seed(0))) {
            Path directory = root.resolve("xmr-regtest-v1/.coordination").resolve(keys.walletId);
            Files.delete(directory.resolve("ledger.aesgcm")); Files.delete(directory);
            try (var factory = MoneroJniWallet.factory(root, daemon, true)) {
                assertThrows(MoneroSendJournal.Failure.class, () -> factory.open(keys, 0));
                assertFalse(Files.exists(directory));
            }
        }
    }
    @Test public void missingNativeSideAndIncompletePairNeverResetTheJournal() throws Exception {
        for (String missing : List.of("identity", "wallet", "wallet.keys", "send-journal-v1")) {
            Path root = temp.getRoot().toPath().resolve(missing); Files.createDirectory(root);
            try (var service = service(MoneroJniWallet.factory(root, daemon, true))) { activate(service, 0, null); }
            try (var keys = new MoneroKeys(seed(0))) {
                Files.delete(root.resolve("xmr-regtest-v1").resolve(keys.walletId).resolve(missing));
                try (var factory = MoneroJniWallet.factory(root, daemon, true)) {
                    assertThrows(MoneroSendJournal.Failure.class, () -> factory.open(keys, 0));
                }
            }
        }
    }
    @Test public void failuresAtPairAdoptionBarriersNeverEnableAnUnpairedWallet() throws Exception {
        for (var barrier : MoneroJniWallet.PairingBarrier.values()) {
            Path root = temp.getRoot().toPath().resolve(barrier.name()); Files.createDirectory(root);
            try (var keys = new MoneroKeys(seed(0))) {
                try (var factory = MoneroJniWallet.factory(root, daemon, true, at -> {
                    if (at == barrier) throw new IllegalStateException("injected pairing failure");
                })) { assertThrows(IllegalStateException.class, () -> factory.open(keys, 0)); }
                try (var factory = MoneroJniWallet.factory(root, daemon, true)) {
                    if (barrier == MoneroJniWallet.PairingBarrier.JOURNAL_PAIRED)
                        assertThrows(MoneroSendJournal.Failure.class, () -> factory.open(keys, 0));
                    else try (var wallet = (MoneroSendBackend) factory.open(keys, 0)) {
                        assertTrue(wallet.journal().read().nativePaired());
                        assertTrue(wallet.journal().read().entries().isEmpty());
                    }
                }
            }
        }
    }
    @Test public void wholeJvmDeathBeforeAndAfterNativeRelayNeverGrantsRetry() throws Exception {
        rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 160));
        for (String fault : List.of("before", "after")) {
            Path root = temp.getRoot().toPath().resolve(fault); Files.createDirectory(root);
            String id = UUID.randomUUID().toString();
            List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), getClass().getName(), daemon, root.toString(), fault, id);
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
            try {
                assertTrue(child.waitFor(120, TimeUnit.SECONDS)); assertEquals(fault.equals("before") ? 24 : 25, child.exitValue());
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS); }
            assertEquals("", Files.readString(root.resolve("child.log")));
            FaultFactory factory = new FaultFactory(root, daemon, "normal");
            try (var service = service(factory)) {
                String session = activate(service, 0, null);
                assertEquals(State.UNKNOWN, service.sendStatus(id, session).state());
                service.reconcileSends(session).get(60, TimeUnit.SECONDS);
                View state = service.sendStatus(id, session);
                assertEquals(fault.equals("before") ? State.UNKNOWN : State.BROADCAST, state.state());
                Request same = new Request(id, address(1), "10000000000");
                assertEquals(state.state(), service.prepareSend(same, session).get(60, TimeUnit.SECONDS).state());
                assertEquals(0, factory.relays.get()); assertTrue(state.walletHeld());
            }
            if (fault.equals("after")) rpc(daemon, "generateblocks", Map.of("wallet_address", address(0), "amount_of_blocks", 12));
        }
    }
    public static void main(String[] args) throws Exception {
        assertTrue(rpc(args[0], "get_info", Map.of()).get("offline").asBoolean());
        try (var service = service(new FaultFactory(Path.of(args[1]), args[0], args[2]))) {
            String session = activate(service, 0, null);
            Request request = new Request(args[3], address(1), "10000000000");
            View quote = service.prepareSend(request, session).get(60, TimeUnit.SECONDS);
            if (quote.state() != State.PREPARED) throw new AssertionError("native preparation failed");
            service.commitSend(request.operationId(), quote.quoteDigest(), session).get(60, TimeUnit.SECONDS);
            throw new AssertionError("crash did not trigger");
        }
    }
}
