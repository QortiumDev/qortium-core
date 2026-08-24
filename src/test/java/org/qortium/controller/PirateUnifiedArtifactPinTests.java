package org.qortium.controller;

import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class PirateUnifiedArtifactPinTests {

	@Test
	public void testStagingPinsMatchLoaderPins() throws Exception {
		Properties pins = new Properties();
		try (InputStream input = Files.newInputStream(Path.of("tools/pirate-unified-artifact.properties"))) {
			pins.load(input);
		}

		assertEquals(PirateUnifiedWalletBundle.RELEASE_TAG, pins.getProperty("release_tag"));
		assertEquals(PirateUnifiedWalletBundle.RELEASE_URL, pins.getProperty("release_url"));
		assertEquals(PirateUnifiedWalletBundle.ARTIFACT_FILENAME, pins.getProperty("artifact_filename"));
		assertEquals(Long.toString(PirateUnifiedWalletBundle.ARTIFACT_SIZE), pins.getProperty("artifact_size"));
		assertEquals(PirateUnifiedWalletBundle.ARTIFACT_SHA256, pins.getProperty("artifact_sha256"));
		assertEquals(5, PirateUnifiedWalletBundle.nativeLibraries().size());
	}
}
