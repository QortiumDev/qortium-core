package org.qortium.controller;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Opt-in validation of an already staged local artifact. Never downloads or loads native code. */
public class PirateUnifiedArtifactAcceptanceTests {

	private static final String RUN_PROPERTY = "qortium.runPirateUnifiedArtifactAcceptanceTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";

	@Test
	public void testStagedOfficialArtifact() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to validate a staged local bundle",
				Boolean.getBoolean(RUN_PROPERTY));
		String artifactPath = System.getProperty(ARTIFACT_PATH_PROPERTY);
		assumeTrue("Set -D" + ARTIFACT_PATH_PROPERTY + "=/absolute/path/to/the/pinned/archive",
				artifactPath != null && !artifactPath.isBlank());
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/staged/bundle",
				bundlePath != null && !bundlePath.isBlank());

		Path artifact = Path.of(artifactPath).toAbsolutePath().normalize();
		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		PirateUnifiedWalletBundle.validateArtifact(artifact, bundle);
		for (String nativeLibrary : PirateUnifiedWalletBundle.nativeLibraries())
			assertTrue("Missing staged target: " + nativeLibrary, bundle.resolve(nativeLibrary).toFile().isFile());
	}
}
