package org.qortium.controller;

import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.PirateWallet;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.nio.file.Path;

public class PirateChainWalletController extends ZcashFamilyWalletController<PirateWallet> {

	public record KnownNewInitialization(int birthdayHeight) {
	}

	private static volatile PirateChainWalletController instance;
    public static final String SESSION_CONTRACT = "qortium-arrr-wallet-session-v1";
    private static ArrrWalletOwnership ownership = new ArrrWalletOwnership();
    @javax.xml.bind.annotation.XmlAccessorType(javax.xml.bind.annotation.XmlAccessType.FIELD)
    public static class WalletSession {
        public String contract, revision, relation, lifecycle, address;
        public boolean enabled;
        public WalletSession() { }
        public WalletSession(String contract, String revision, boolean enabled, String relation, String lifecycle, String address) {
            this.contract = contract; this.revision = revision; this.enabled = enabled;
            this.relation = relation; this.lifecycle = lifecycle; this.address = address;
        }
    }

    private static String identity(String entropy58) throws ForeignBlockchainException {
        try {
            byte[] bytes = org.qortium.utils.Base58.decode(entropy58);
            if (bytes == null || bytes.length != 32) throw new IllegalArgumentException();
            return org.qortium.utils.Base58.encode(org.qortium.crypto.Crypto.digest(bytes));
        } catch (RuntimeException e) { throw new ForeignBlockchainException("Invalid entropy bytes"); }
    }

    @Override
    protected void requireWalletOwner(String entropy58, boolean isNullSeedWallet) throws ForeignBlockchainException {
        // Explicit redeem/refund operations use an internal transient null-seed wallet.
        // They must not acquire or transfer custody ownership; the next owner read
        // restores its own namespace on the serialized native lane.
        if (!isNullSeedWallet) ownership.requireOwner(identity(entropy58));
    }

    @Override
    protected void walletSelectionPending(String entropy58) throws ForeignBlockchainException {
        ownership.selected(identity(entropy58), null);
    }

    @Override
    protected void walletSelected(PirateWallet wallet) {
        if (wallet.isNullSeedWallet()) return;
        ownership.selected(wallet.getWalletIdentityHash(), null);
        try { ownership.selected(wallet.getWalletIdentityHash(), wallet.getWalletAddress()); }
        catch (RuntimeException e) { /* Address failure must not undo established ownership. */ }
    }

    public static WalletSession walletSession(String entropy58) throws ForeignBlockchainException {
        String requested = identity(entropy58);
        ArrrWalletOwnership.State state = ownership.snapshot();
        PirateChainWalletController current = instance;
        return new WalletSession(SESSION_CONTRACT, state.revision(),
                Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE),
                state.identity() == null ? "NONE" : state.identity().equals(requested) ? "SELF" : "OTHER",
                org.qortium.crosschain.ZcashFamilyNativeCoordinator.getInstance().isDegraded() || (current != null && current.requiresCoreRestart())
                        ? "DEGRADED" : current == null ? "NEW" : current.getLifecycleState().name(),
                ownership.address(requested));
    }

    public static synchronized boolean stopWallet() {
        return Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
    }

    public static synchronized boolean startInstance() {
        Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
        PirateChainWalletController current = getInstance();
        boolean started = current != null && current.startController();
        ownership.lifecycleChanged();
        return started;
    }

    public static synchronized WalletSession activateWallet(String entropy58, String expectedRevision) throws ForeignBlockchainException {
        identity(entropy58);
        ownership.requireRevision(expectedRevision);
        if (!startInstance()) throw new ForeignBlockchainException("ARRR controller could not start");
        String revision = ownership.snapshot().revision();
        PirateChainWalletController current = getInstance();
        current.activateEntropyWallet(entropy58, () -> {
            try { ownership.requireRevision(revision); }
            catch (ForeignBlockchainException e) { throw new IllegalStateException(e.getMessage(), e); }
        });
        return walletSession(entropy58);
    }


	PirateChainWalletController() {
		super(PirateChain.WALLET_CONFIG);
	}

	public static synchronized PirateChainWalletController getInstance() {
		if (!Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE))
			return null;

		if (instance == null || instance.getLifecycleState() == LifecycleState.TERMINATED)
			instance = new PirateChainWalletController();

		return instance;
	}

	/** Stops the existing controller without creating one when the wallet is already idle. */
	public static synchronized boolean stopInstance() {
		boolean stopped = instance == null || instance.shutdown();
		ownership.lifecycleChanged();
		return stopped;
	}

	static synchronized void resetForTesting() {
		if (instance != null)
			instance.shutdown();
		instance = null;
		ownership = new ArrrWalletOwnership();
	}

	@Override
	protected PirateWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		return new PirateWallet(entropyBytes, isNullSeedWallet);
	}

	@Override
	protected PirateWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet,
			boolean initializeAtCurrentTip) throws IOException {
		PirateWallet.InitializationMode initializationMode = initializeAtCurrentTip
				? PirateWallet.InitializationMode.NEW_AT_CURRENT_TIP
				: PirateWallet.InitializationMode.CONSERVATIVE;
		return new PirateWallet(entropyBytes, isNullSeedWallet, initializationMode);
	}

	@Override
	protected boolean isCurrentTipInitializedWallet(PirateWallet wallet) {
		return wallet.isKnownNewInitialization();
	}

	@Override
	protected String getWalletInitializationFailure(PirateWallet wallet) {
		return wallet.getInitializationFailureMessage();
	}

	@Override
	protected ZcashFamilyWalletController.WalletBalanceSnapshot currentWalletBalanceSnapshot(PirateWallet wallet) {
		try {
			PirateChainBalance balances = wallet.getWalletBalances();
			String totalAtomic = Long.toString(balances.zbalance);
			String verifiedAtomic = balances.verifiedBalanceKnown ? Long.toString(balances.verified_zbalance) : null;
			return new ZcashFamilyWalletController.WalletBalanceSnapshot(totalAtomic, verifiedAtomic);
		} catch (ForeignBlockchainException | RuntimeException e) {
			return new ZcashFamilyWalletController.WalletBalanceSnapshot(null, null);
		}
	}

    public static synchronized KnownNewInitialization initializeKnownNewWallet(String entropy58, String expectedRevision) throws ForeignBlockchainException {
        String requested = identity(entropy58);
        ArrrWalletOwnership.State before = ownership.snapshot();
        if (expectedRevision != null) ownership.requireRevision(expectedRevision);
        else if (before.identity() != null && !before.identity().equals(requested))
            throw new ForeignBlockchainException("ARRR_SESSION_CHANGED: explicit revision required to replace another wallet");
        PirateChainWalletController current = getInstance();
        if (current == null) throw new ForeignBlockchainException("Pirate Chain wallet is disabled");
        return current.initializeKnownNewWalletChecked(entropy58, before.revision());
    }

    private KnownNewInitialization initializeKnownNewWalletChecked(String entropy58, String revision) throws ForeignBlockchainException {
		if (!this.config.isUnifiedWalletEnabled())
			throw new ForeignBlockchainException("Known-new initialization requires the Unified Pirate wallet");

		PirateWallet wallet = this.initializeWalletAtCurrentTip(entropy58, () -> {
            try { ownership.requireRevision(revision); }
            catch (ForeignBlockchainException e) { throw new IllegalStateException(e.getMessage(), e); }
        });
		try {
			return new KnownNewInitialization(wallet.getInitializationBirthdayHeight());
		} catch (IOException e) {
			throw new ForeignBlockchainException(e.getMessage());
		}
	}

	public static String getRustLibFilename() {
		return ZcashFamilyWalletController.resolveRustLibFilename();
	}

	public static Path getWalletsLibDirectory() {
		return PirateChain.WALLET_CONFIG.getWalletsLibDirectory();
	}

	public static Path getRustLibOuterDirectory() {
		return PirateChain.WALLET_CONFIG.getRustLibOuterDirectory();
	}
}
