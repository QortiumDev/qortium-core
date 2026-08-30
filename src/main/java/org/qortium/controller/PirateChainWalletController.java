package org.qortium.controller;

import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.PirateWallet;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.nio.file.Path;

public class PirateChainWalletController extends ZcashFamilyWalletController<PirateWallet> {

	public record KnownNewInitialization(int birthdayHeight) {
	}

	private static PirateChainWalletController instance;

	private PirateChainWalletController() {
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
		return instance == null || instance.shutdown();
	}

	static synchronized void resetForTesting() {
		if (instance != null)
			instance.shutdown();
		instance = null;
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

	public KnownNewInitialization initializeKnownNewWallet(String entropy58) throws ForeignBlockchainException {
		if (!this.config.isUnifiedWalletEnabled())
			throw new ForeignBlockchainException("Known-new initialization requires the Unified Pirate wallet");

		PirateWallet wallet = this.initializeWalletAtCurrentTip(entropy58);
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
