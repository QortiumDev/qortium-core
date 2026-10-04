package org.qortium.crosschain.monero;

import org.qortium.crosschain.WalletServerPool;
import monero.common.MoneroRpcConnection;
import monero.wallet.MoneroWalletFull;
import java.util.List;

/** Selection/probing/setDaemonConnection run only on the existing wallet lane, never around sends. */
final class MoneroDaemonPool {
    static final class Unavailable extends Exception {
        final long delayMillis;
        Unavailable(long delayMillis) { super("XMR_DAEMON_UNAVAILABLE"); this.delayMillis = delayMillis; }
    }
    final WalletServerPool pool;
    private final boolean regtest;
    private WalletServerPool.Server connected;
    MoneroDaemonPool(List<String> endpoints, boolean regtest) {
        this(new WalletServerPool(endpoints), regtest);
    }
    MoneroDaemonPool(WalletServerPool pool, boolean regtest) {
        pool.status().servers().forEach(server -> MoneroJniWallet.validateDaemon(server.endpoint()));
        this.pool = pool; this.regtest = regtest;
    }
    @FunctionalInterface interface Connector { void connect(String endpoint) throws Exception; }
    void connect(MoneroWalletFull wallet, long minimumHeight) throws Exception {
        connect(endpoint -> {
            MoneroDaemonProbe.check(endpoint, minimumHeight, regtest);
            // JNI forwards URI/credentials/proxy/trust only: Java SSL/timeout setters do not govern native transport.
            wallet.setDaemonConnection(new MoneroRpcConnection(endpoint), false);
        });
    }
    void connect(Connector connector) throws Exception {
        // A successful current connection stays selected until a completed read reports failure.
        if (connected != null) return;
        for (int attempted = 0; attempted < pool.status().servers().size(); attempted++) {
            var candidate = pool.candidate();
            if (candidate == null) throw new Unavailable(pool.retryDelayMillis());
            try {
                connector.connect(candidate.endpoint());
                connected = candidate; return;
            } catch (Exception e) { pool.failed(candidate); }
        }
        throw new Unavailable(pool.retryDelayMillis());
    }
    long currentHeight() throws Exception {
        resetConnection();
        long[] height = {0};
        try { connect(endpoint -> height[0] = MoneroDaemonProbe.check(endpoint, 0, regtest)); }
        finally { resetConnection(); }
        return height[0];
    }
    void resetConnection() { connected = null; }
    void succeeded() { if (connected != null) pool.succeeded(connected); }
    Unavailable failed() {
        if (connected != null) pool.failed(connected);
        connected = null;
        return new Unavailable(pool.retryDelayMillis());
    }
}
