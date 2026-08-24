package org.qortium.crosschain;

import com.rust.litewalletjni.LiteWalletJni;

import java.nio.file.Path;

final class LiteWalletJniAdapter implements ZcashFamilyNativeAdapter {

	@Override
	public boolean isLoaded() {
		return LiteWalletJni.isLoaded();
	}

	@Override
	public void loadLibrary(Path path) {
		LiteWalletJni.loadLibrary(path);
	}

	@Override
	public void initLogging() {
		LiteWalletJni.initlogging();
	}

	@Override
	public String getSeedPhraseFromEntropyB64(String entropy64) {
		return LiteWalletJni.getseedphrasefromentropyb64(entropy64);
	}

	@Override
	public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
			String saplingOutput64, String saplingSpend64) {
		return LiteWalletJni.initfromseed(serverUri, params, seedPhrase, birthday, saplingOutput64, saplingSpend64);
	}

	@Override
	public String initFromB64(String serverUri, String params, String wallet64,
			String saplingOutput64, String saplingSpend64) {
		return LiteWalletJni.initfromb64(serverUri, params, wallet64, saplingOutput64, saplingSpend64);
	}

	@Override
	public String save() {
		return LiteWalletJni.save();
	}

	@Override
	public String execute(String command, String arguments) {
		return LiteWalletJni.execute(command, arguments);
	}
}
