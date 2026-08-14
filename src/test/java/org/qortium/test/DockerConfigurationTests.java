package org.qortium.test;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DockerConfigurationTests {

	private static final Path PREVIEW_SETTINGS = Path.of("preview/settings-preview.json");
	private static final Path[] PREVIEW_PROFILES = new Path[] {
			PREVIEW_SETTINGS,
			Path.of("preview/settings-preview-seed.json"),
			Path.of("preview/settings-preview-seed-netcup.json")
	};

	@Test
	public void testMissingSettingsAreCopiedByteForByteFromPreviewTemplate() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings");
		Path settingsPath = tempDirectory.resolve("settings.json");

		runInitializer(PREVIEW_SETTINGS, settingsPath);

		assertArrayEquals(Files.readAllBytes(PREVIEW_SETTINGS), Files.readAllBytes(settingsPath));
		assertNoInitializerTemporaryFiles(tempDirectory, settingsPath);
	}

	@Test
	public void testExistingSettingsAreNeverOverwritten() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-existing");
		Path settingsPath = tempDirectory.resolve("settings.json");
		byte[] customSettings = "{\"operatorOwned\":true}\n".getBytes(StandardCharsets.UTF_8);
		Files.write(settingsPath, customSettings);

		runInitializer(PREVIEW_SETTINGS, settingsPath);
		runInitializer(PREVIEW_SETTINGS, settingsPath);

		assertArrayEquals(customSettings, Files.readAllBytes(settingsPath));
	}

	@Test
	public void testEmptyAndMalformedSettingsRemainOperatorOwned() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-owned");
		Path emptySettings = tempDirectory.resolve("empty.json");
		Path malformedSettings = tempDirectory.resolve("malformed.json");
		Files.write(emptySettings, new byte[0]);
		byte[] malformed = "{not-json\n".getBytes(StandardCharsets.UTF_8);
		Files.write(malformedSettings, malformed);

		runInitializer(PREVIEW_SETTINGS, emptySettings);
		runInitializer(PREVIEW_SETTINGS, malformedSettings);

		assertEquals(0, Files.size(emptySettings));
		assertArrayEquals(malformed, Files.readAllBytes(malformedSettings));
	}

	@Test
	public void testExistingSettingsSymlinksRemainOperatorOwned() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-symlinks");
		Path target = tempDirectory.resolve("operator-settings.json");
		Path settingsPath = tempDirectory.resolve("settings.json");
		Path brokenSettingsPath = tempDirectory.resolve("broken-settings.json");
		Path missingTarget = tempDirectory.resolve("missing-target.json");
		byte[] operatorSettings = "{\"network\":\"operator-owned\"}\n".getBytes(StandardCharsets.UTF_8);
		Files.write(target, operatorSettings);
		Files.createSymbolicLink(settingsPath, target.getFileName());
		Files.createSymbolicLink(brokenSettingsPath, missingTarget.getFileName());

		runInitializer(PREVIEW_SETTINGS, settingsPath);
		runInitializer(PREVIEW_SETTINGS, brokenSettingsPath);

		assertTrue(Files.isSymbolicLink(settingsPath));
		assertArrayEquals(operatorSettings, Files.readAllBytes(target));
		assertTrue(Files.isSymbolicLink(brokenSettingsPath));
		assertFalse(Files.exists(missingTarget));
	}

	@Test
	public void testPredictableStagingSymlinkCannotOverwriteAnotherFile() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-staging-symlink");
		Path template = tempDirectory.resolve("template.json");
		Path settingsPath = tempDirectory.resolve("settings.json");
		Path victim = tempDirectory.resolve("victim.txt");
		byte[] templateBytes = "{\"profile\":\"preview\"}\n".getBytes(StandardCharsets.UTF_8);
		byte[] victimBytes = "operator data\n".getBytes(StandardCharsets.UTF_8);
		Files.write(template, templateBytes);
		Files.write(victim, victimBytes);

		String command = "ln -s \"$4\" \"$2.tmp.$$\"; exec sh \"$1\" \"$3\" \"$2\"";
		Process process = new ProcessBuilder("sh", "-c", command, "sh",
				"docker-init-settings.sh", settingsPath.toString(), template.toString(), victim.toString())
				.redirectErrorStream(true)
				.start();
		ProcessResult result = waitFor(process);

		assertEquals(result.output, 0, result.exitCode);
		assertArrayEquals(victimBytes, Files.readAllBytes(victim));
		assertArrayEquals(templateBytes, Files.readAllBytes(settingsPath));
	}

	@Test
	public void testConcurrentInitializersInstallOneCompleteTemplate() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-concurrent");
		Path firstTemplate = tempDirectory.resolve("first-template.json");
		Path secondTemplate = tempDirectory.resolve("second-template.json");
		Path settingsPath = tempDirectory.resolve("settings.json");
		byte[] firstBytes = new byte[1024 * 1024];
		byte[] secondBytes = new byte[1024 * 1024];
		Arrays.fill(firstBytes, (byte) 'A');
		Arrays.fill(secondBytes, (byte) 'B');
		Files.write(firstTemplate, firstBytes);
		Files.write(secondTemplate, secondBytes);

		Process first = startInitializer(firstTemplate, settingsPath);
		Process second = startInitializer(secondTemplate, settingsPath);
		ProcessResult firstResult = waitFor(first);
		ProcessResult secondResult = waitFor(second);
		byte[] installed = Files.readAllBytes(settingsPath);

		assertEquals(firstResult.output, 0, firstResult.exitCode);
		assertEquals(secondResult.output, 0, secondResult.exitCode);
		assertTrue(Arrays.equals(firstBytes, installed) || Arrays.equals(secondBytes, installed));
		assertNoInitializerTemporaryFiles(tempDirectory, settingsPath);
	}

	@Test
	public void testMissingTemplateFailsWithoutCreatingSettings() throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-docker-settings-missing-template");
		Path settingsPath = tempDirectory.resolve("settings.json");
		Path missingTemplate = tempDirectory.resolve("missing-template.json");

		ProcessResult result = runInitializerExpectingResult(missingTemplate, settingsPath);

		assertTrue(result.output.contains("template is not readable"));
		assertTrue(result.exitCode != 0);
		assertFalse(Files.exists(settingsPath));
		assertNoInitializerTemporaryFiles(tempDirectory, settingsPath);
	}

	@Test
	public void testDockerLaunchersUseSharedPreviewInitializer() throws Exception {
		String dockerfile = Files.readString(Path.of("Dockerfile"));
		String entrypoint = Files.readString(Path.of("docker-entrypoint.sh"));
		String startScript = Files.readString(Path.of("docker-start.sh"));

		assertTrue(dockerfile.contains("COPY ./preview/settings-preview.json /usr/local/qortium/settings-preview.json"));
		assertTrue(dockerfile.contains("COPY ./docker-init-settings.sh /usr/local/bin/docker-init-settings.sh"));
		assertTrue(entrypoint.contains("/usr/local/bin/docker-init-settings.sh"));
		assertTrue(startScript.contains("/usr/local/bin/docker-init-settings.sh"));
		assertTrue(entrypoint.contains("QORTIUM_SETTINGS_FILE:-/qortium/settings.json"));
		assertTrue(startScript.contains("QORTIUM_SETTINGS_FILE:-/qortium/settings.json"));
		assertFalse(entrypoint.contains("printf '{}"));
		assertFalse(startScript.contains("printf '{}"));
	}

	@Test
	public void testDockerPortsMatchPreviewProfile() throws Exception {
		JSONObject previewSettings = new JSONObject(Files.readString(PREVIEW_SETTINGS));
		assertEquals(24891, previewSettings.getInt("apiPort"));
		assertEquals(24892, previewSettings.getInt("listenPort"));
		assertEquals(24894, previewSettings.getInt("listenDataPort"));
		assertTrue(previewSettings.getBoolean("isTestNet"));
		assertEquals("previewchain.json", previewSettings.getString("blockchainConfig"));

		String dockerfile = Files.readString(Path.of("Dockerfile"));
		String publicCompose = Files.readString(Path.of("docker-compose.yml"));
		String internalCompose = Files.readString(Path.of("docker-compose.internal.yml"));
		String exampleEnvironment = Files.readString(Path.of(".env.example"));

		assertTrue(dockerfile.contains("EXPOSE 24891 24892 24894"));
		assertTrue(dockerfile.contains("http://127.0.0.1:${QORTIUM_API_PORT:-24891}/admin/info"));
		assertTrue(publicCompose.contains("QORTIUM_API_PORT: \"${QORTIUM_API_PORT:-24891}\""));
		assertTrue(internalCompose.contains("QORTIUM_API_PORT: \"${QORTIUM_API_PORT:-24891}\""));
		assertTrue(publicCompose.contains("${QORTIUM_API_BIND_HOST:-127.0.0.1}:${QORTIUM_API_PORT:-24891}:${QORTIUM_API_PORT:-24891}"));
		assertTrue(publicCompose.contains("${QORTIUM_P2P_BIND_HOST:-0.0.0.0}:${QORTIUM_P2P_PORT:-24892}:${QORTIUM_P2P_PORT:-24892}"));
		assertTrue(publicCompose.contains("${QORTIUM_QDN_BIND_HOST:-0.0.0.0}:${QORTIUM_QDN_PORT:-24894}:${QORTIUM_QDN_PORT:-24894}"));
		assertContainsPreviewExposedPorts(publicCompose);
		assertContainsPreviewExposedPorts(internalCompose);
		assertTrue(exampleEnvironment.contains("QORTIUM_API_PORT=24891"));
		assertTrue(exampleEnvironment.contains("QORTIUM_P2P_PORT=24892"));
		assertTrue(exampleEnvironment.contains("QORTIUM_QDN_PORT=24894"));

		for (String content : new String[] { dockerfile, publicCompose, internalCompose, exampleEnvironment }) {
			assertFalse(content.contains("12391"));
			assertFalse(content.contains("12392"));
			assertFalse(content.contains("12394"));
			assertFalse(content.contains("14891"));
			assertFalse(content.contains("14892"));
			assertFalse(content.contains("14894"));
		}
	}

	@Test
	public void testTrackedPreviewProfilesCarryCompleteNetworkIdentity() throws Exception {
		for (Path profile : PREVIEW_PROFILES) {
			JSONObject settings = new JSONObject(Files.readString(profile));

			assertTrue(profile.toString(), settings.getBoolean("isTestNet"));
			assertFalse(profile.toString(), settings.getBoolean("singleNodeTestnet"));
			assertEquals(profile.toString(), "previewchain.json", settings.getString("blockchainConfig"));
			assertEquals(profile.toString(), 24891, settings.getInt("apiPort"));
			assertEquals(profile.toString(), 24892, settings.getInt("listenPort"));
			assertEquals(profile.toString(), 24894, settings.getInt("listenDataPort"));
			assertPeerPorts(profile, settings.getJSONArray("initialPeers"), 24892);
			assertPeerPorts(profile, settings.getJSONArray("initialDataPeers"), 24894);
		}
	}

	private static void assertContainsPreviewExposedPorts(String compose) {
		assertTrue(compose.contains("- \"${QORTIUM_API_PORT:-24891}\""));
		assertTrue(compose.contains("- \"${QORTIUM_P2P_PORT:-24892}\""));
		assertTrue(compose.contains("- \"${QORTIUM_QDN_PORT:-24894}\""));
	}

	private static void assertPeerPorts(Path profile, JSONArray peers, int expectedClearnetPort) {
		assertTrue(profile.toString(), peers.length() > 0);
		for (int index = 0; index < peers.length(); ++index) {
			String peer = peers.getString(index);
			assertTrue(profile + ": " + peer,
					peer.endsWith(".b32.i2p") || peer.endsWith(":" + expectedClearnetPort));
		}
	}

	private static void runInitializer(Path templatePath, Path settingsPath) throws Exception {
		ProcessResult result = runInitializerExpectingResult(templatePath, settingsPath);
		assertEquals(result.output, 0, result.exitCode);
	}

	private static ProcessResult runInitializerExpectingResult(Path templatePath, Path settingsPath) throws Exception {
		return waitFor(startInitializer(templatePath, settingsPath));
	}

	private static Process startInitializer(Path templatePath, Path settingsPath) throws Exception {
		return new ProcessBuilder("sh", "docker-init-settings.sh",
				templatePath.toString(), settingsPath.toString())
				.redirectErrorStream(true)
				.start();
	}

	private static ProcessResult waitFor(Process process) throws Exception {
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail("Docker settings initializer timed out");
		}

		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new ProcessResult(process.exitValue(), output);
	}

	private static void assertNoInitializerTemporaryFiles(Path directory, Path settingsPath) throws Exception {
		String prefix = settingsPath.getFileName() + ".tmp.";
		try (Stream<Path> entries = Files.list(directory)) {
			assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(prefix)));
		}
	}

	private record ProcessResult(int exitCode, String output) {
	}
}
