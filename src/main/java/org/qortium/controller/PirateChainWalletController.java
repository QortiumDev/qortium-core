package org.qortium.controller;

import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.PirateWallet;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.nio.file.Path;

public class PirateChainWalletController extends ZcashFamilyWalletController<PirateWallet> {

	private static PirateChainWalletController instance;

	private PirateChainWalletController() {
		super(PirateChain.WALLET_CONFIG);
	}

	public static PirateChainWalletController getInstance() {
		if (!Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE))
			return null;

		if (instance == null)
			instance = new PirateChainWalletController();

		return instance;
	}

	@Override
	protected PirateWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		return new PirateWallet(entropyBytes, isNullSeedWallet);
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
