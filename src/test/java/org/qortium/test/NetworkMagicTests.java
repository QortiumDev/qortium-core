package org.qortium.test;

import org.junit.Test;
import org.qortium.block.BlockChain;
import org.qortium.test.common.Common;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NetworkMagicTests extends Common {

	@Test
	public void testConfiguredMessageMagic() throws Exception {
		Common.useDefaultSettings();

		assertArrayEquals(ascii("QRTM"), BlockChain.getInstance().getMessageMagic(false));
		assertArrayEquals(ascii("qrtm"), BlockChain.getInstance().getMessageMagic(true));

		byte[] mutableCopy = BlockChain.getInstance().getMessageMagic(false);
		mutableCopy[0] = 'X';
		assertArrayEquals(ascii("QRTM"), BlockChain.getInstance().getMessageMagic(false));
	}

	@Test
	public void testInvalidMessageMagicRejected() throws Exception {
		String defaultConfig = loadDefaultTestChainConfig();

		assertInvalidConfig(defaultConfig.replace("\t\"mainnetMessageMagic\": \"QRTM\",\n", ""),
				"No \"mainnetMessageMagic\" entry found");

		assertInvalidConfig(defaultConfig.replace("\"mainnetMessageMagic\": \"QRTM\"", "\"mainnetMessageMagic\": \"QRT\""),
				"\"mainnetMessageMagic\" must be exactly 4 ASCII characters");

		assertInvalidConfig(defaultConfig.replace("\"mainnetMessageMagic\": \"QRTM\"", "\"mainnetMessageMagic\": \"QR\u2122M\""),
				"\"mainnetMessageMagic\" must contain only ASCII characters");

		assertInvalidConfig(defaultConfig.replace("\"testnetMessageMagic\": \"qrtm\"", "\"testnetMessageMagic\": \"QRTM\""),
				"\"mainnetMessageMagic\" and \"testnetMessageMagic\" must be different");
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static String loadDefaultTestChainConfig() throws Exception {
		URL testChainUrl = Common.class.getClassLoader().getResource("test-chain-v2.json");
		assertNotNull(testChainUrl);
		return Files.readString(Paths.get(testChainUrl.toURI()), StandardCharsets.UTF_8);
	}

	private static void assertInvalidConfig(String config, String expectedMessageFragment) throws Exception {
		Path tempDir = Files.createTempDirectory("network-magic-config");
		Path configPath = tempDir.resolve("blockchain.json");

		try {
			Files.writeString(configPath, config, StandardCharsets.UTF_8);
			BlockChain.fileInstance(tempDir.toString() + java.io.File.separator, configPath.getFileName().toString());
			fail("Expected invalid blockchain config");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains(expectedMessageFragment));
		} finally {
			Files.deleteIfExists(configPath);
			Files.deleteIfExists(tempDir);
		}
	}

}
