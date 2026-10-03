package org.qortium.crosschain.monero;

import monero.common.MoneroUtils;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Load only the evaluated native artifact, before MoneroWalletFull's permissive static loader. */
public final class MoneroNativeLoader {
    public static final String SHA256 = "d971fda9d8ff04cdc1d1afa2084b35991cd7bfdc78edd2ffd0c38f1f1ef11715";
    private static boolean loaded;
    private static final Logger WALLET_LOGGER = Logger.getLogger("monero.wallet.MoneroWalletFull");
    private MoneroNativeLoader() { }

    public static boolean supported() {
        return "Linux".equals(System.getProperty("os.name"))
                && ("amd64".equals(System.getProperty("os.arch")) || "x86_64".equals(System.getProperty("os.arch")));
    }

    public static synchronized void load() throws Exception {
        if (loaded) return;
        if (!supported()) throw new IllegalStateException("XMR native platform not yet validated");
        // Reject an earlier, uncontrolled load. Never bless unknown process-native code.
        if (MoneroUtils.isNativeLibraryLoaded()) throw new IllegalStateException("XMR native library already loaded outside adapter");
        Path dir = Files.createTempDirectory("qortium-xmr-native-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = dir.resolve("libmonero-java.so");
        try {
            try (InputStream input = MoneroNativeLoader.class.getResourceAsStream("/linux-x86_64/libmonero-java.so")) {
                if (input == null) throw new IllegalStateException("XMR native resource missing");
                Files.copy(input, file);
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            try (InputStream input = Files.newInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                if (!SHA256.equals(MoneroKeys.hex(digest.digest()))) throw new IllegalStateException("XMR native artifact mismatch");
            }
            System.load(file.toAbsolutePath().toString());
            if (!MoneroUtils.isNativeLibraryLoaded()) throw new IllegalStateException("XMR native load failed");
            // Lowest native level still logs wallet metadata. Linux-only rollout discards it.
            // Do not redirect or reset Core's global logging configuration.
            if (!Files.readAttributes(Path.of("/dev/null"), java.nio.file.attribute.BasicFileAttributes.class).isOther())
                throw new IllegalStateException("Native logging sink unavailable");
            WALLET_LOGGER.setLevel(Level.OFF);
            WALLET_LOGGER.setUseParentHandlers(false);
            MoneroUtils.configureNativeLogging("/dev/null", false);
            MoneroUtils.setLogLevel(0);
            loaded = true;
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(dir);
        }
    }
}
