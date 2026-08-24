package org.qortium.crosschain;

import java.nio.file.Path;

/**
 * Adapter for the process-global LiteWallet JNI state.
 *
 * Keeping the static native surface behind this interface lets the coordinator
 * enforce one operation lane and lets tests use a deterministic fake without
 * loading native code.
 */
public interface ZcashFamilyNativeAdapter {

	boolean isLoaded();

	void loadLibrary(Path path);

	void initLogging();

	String getSeedPhraseFromEntropyB64(String entropy64);

	String getSeedPhraseFromEntropy(String entropy);

	String configureStorage(String baseDirectory, String passphrase);

	String invokeJson(String requestJson, boolean pretty);

	String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
			String saplingOutput64, String saplingSpend64);

	String initFromB64(String serverUri, String params, String wallet64,
			String saplingOutput64, String saplingSpend64);

	String save();

	String execute(String command, String arguments);
}
