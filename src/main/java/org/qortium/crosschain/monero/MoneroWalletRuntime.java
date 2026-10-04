package org.qortium.crosschain.monero;

import org.qortium.settings.Settings;
import java.nio.file.Path;

/** Lazy runtime: disabled nodes never load JNI or create XMR wallet files/threads. */
public final class MoneroWalletRuntime {
    private static MoneroWalletService instance;
    private static boolean stopped;
    private MoneroWalletRuntime() { }
    public static synchronized MoneroWalletService get() {
        if (stopped) throw new MoneroWalletService.Rejected(503, "XMR_STOPPED");
        Settings settings = Settings.getInstance();
        if (!settings.isMoneroWalletEnabled()) throw new MoneroWalletService.Rejected(503, "XMR_DISABLED");
        if (!MoneroNativeLoader.supported()) throw new MoneroWalletService.Rejected(503, "XMR_UNSUPPORTED_PLATFORM");
        if (instance == null) {
            try {
                instance = new MoneroWalletService(MoneroJniWallet.factory(Path.of(settings.getWalletsPath()), settings.getMoneroDaemonUris()));
            } catch (IllegalArgumentException e) { throw new MoneroWalletService.Rejected(503, "XMR_INVALID_DAEMON_CONFIG"); }
        }
        return instance;
    }
    public static synchronized void shutdown() {
        stopped = true;
        if (instance != null) instance.close();
    }
}
