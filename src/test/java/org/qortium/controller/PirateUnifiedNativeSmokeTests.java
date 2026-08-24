package org.qortium.controller;

import org.junit.Test;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Explicitly opt-in host JNI smoke. This test is never part of normal CI. */
public class PirateUnifiedNativeSmokeTests {

	private static final String RUN_PROPERTY = "qortium.runPirateUnifiedNativeSmokeTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";

	@Test
	public void testHostLibraryLoadsAndConvertsDeterministicEntropy() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to execute the staged host native library",
				Boolean.getBoolean(RUN_PROPERTY));
		String artifactPath = System.getProperty(ARTIFACT_PATH_PROPERTY);
		assumeTrue("Set -D" + ARTIFACT_PATH_PROPERTY + "=/absolute/path/to/the/pinned/archive",
				artifactPath != null && !artifactPath.isBlank());
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/staged/bundle",
				bundlePath != null && !bundlePath.isBlank());
		String libraryFilename = ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);

		Path artifact = Path.of(artifactPath).toAbsolutePath().normalize();
		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		PirateUnifiedWalletBundle.FileRecord trustedRecord =
				PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, libraryFilename);
		Path library = bundle.resolve(libraryFilename);
		byte[] entropy = new byte[32];
		Arrays.fill(entropy, (byte) 7);
		String entropy64 = Base64.getEncoder().encodeToString(entropy);

		String response = ZcashFamilyNativeCoordinator.getInstance().execute("Pirate Unified JNI smoke", adapter -> {
			PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
			adapter.loadLibrary(library);
			if (!adapter.isLoaded())
				throw new AssertionError("Pirate Unified JNI library did not load");
			return adapter.getSeedPhraseFromEntropyB64(entropy64);
		});
		assertTrue("Deterministic entropy conversion did not return a seed phrase",
				response != null && response.contains("\"seedPhrase\""));
	}
}
