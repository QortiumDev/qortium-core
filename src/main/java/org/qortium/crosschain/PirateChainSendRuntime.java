package org.qortium.crosschain;

import org.qortium.controller.PirateChainWalletController;
import org.qortium.settings.Settings;
import java.io.IOException;

/** Process-owned durable send service. Native wallet recovery cannot archive its reservations away. */
public final class PirateChainSendRuntime {
    private static PirateChainSendService service;
    private static boolean stopping;
    private static java.nio.file.Path openedRoot;
    private static String openedNetwork;
    public static synchronized void shutdown() {
        stopping = true;
        if (service != null) service.stopAdmissions();
        // Keep the file lock until JVM exit: a timed-out native worker can still return.
    }
    public static synchronized PirateChainSendService service() throws IOException {
        if (stopping) throw new PirateChainSendJournal.StorageException();
        String network = Settings.getInstance().getPirateChainNet().name();
        var root = PirateChain.WALLET_CONFIG.getUnifiedWalletsDirectory().getParent()
                .resolve("send-operations").resolve(network).toAbsolutePath().normalize();
        if (service != null && (!root.equals(openedRoot) || !network.equals(openedNetwork)))
            throw new PirateChainSendJournal.StorageException();
        if (service == null) {
            var journal = new PirateChainSendJournal(root, network);
            openedRoot = root; openedNetwork = network;
            service = new PirateChainSendService(journal, (request, lifecycle) -> {
                PirateChain chain = PirateChain.getInstance();
                if (chain == null) throw new ForeignBlockchainException.WalletNotReadyException("ARRR_WALLET_DISABLED");
                chain.sendCoins(request.entropy58(), request.recipient(), request.amount(), request.memo(), lifecycle);
            });
        }
        return service;
    }
    public static void requireUnreserved(String entropy58) throws ForeignBlockchainException {
        try {
            if (service().blocking(PirateChainSendJournal.walletIdentity(entropy58)) != null)
                throw new ForeignBlockchainException.WalletBusyException("ARRR_SEND_UNRESOLVED_OR_PENDING");
        } catch (IOException e) {
            throw new ForeignBlockchainException.WalletNotReadyException("ARRR_SEND_STORAGE_UNAVAILABLE");
        }
    }
    public static void checkAdmission(String entropy58) throws ForeignBlockchainException { checkAdmission(entropy58, false); }
    public static void checkReservedAdmission(String entropy58) throws ForeignBlockchainException { checkAdmission(entropy58, true); }
    private static void checkAdmission(String entropy58, boolean reserved) throws ForeignBlockchainException {
        if (!Settings.getInstance().isPirateChainWalletUnified() || !Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE))
            throw new ForeignBlockchainException.WalletNotReadyException("ARRR_WALLET_DISABLED");
        var session = PirateChainWalletController.walletSession(entropy58);
        if (!"SELF".equals(session.relation) || !"RUNNING".equals(session.lifecycle))
            throw new ForeignBlockchainException.WalletBusyException("ARRR_WALLET_NOT_ACTIVE");
        var coordinator = ZcashFamilyNativeCoordinator.getInstance();
        if (coordinator.isDegraded()) throw new ForeignBlockchainException.WalletNotReadyException("ARRR_NATIVE_RESTART_REQUIRED");
        if (coordinator.isBusy()) throw new ForeignBlockchainException.WalletBusyException("ARRR_WALLET_BUSY");
        if (!reserved) requireUnreserved(entropy58);
        var controller = PirateChainWalletController.getInstance();
        var status = controller.getSyncStatusDetails(entropy58);
        if (status.getState() != org.qortium.controller.ZcashFamilyWalletController.WalletSyncState.READY
                || status.isStale() || status.isRestartRequired() || status.getVerifiedBalanceAtomic() == null)
            throw new ForeignBlockchainException.WalletNotReadyException("ARRR_WALLET_NOT_READY");
    }
}
