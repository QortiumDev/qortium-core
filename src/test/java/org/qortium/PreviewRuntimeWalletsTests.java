package org.qortium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

public class PreviewRuntimeWalletsTests {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};

	private Path tempDirectory;
	private Path installDirectory;
	private Path runtimeDirectory;
	private Path settingsPath;

	@Before
	public void createDirectories() throws IOException {
		tempDirectory = Files.createTempDirectory("qortium-preview-wallets-test");
		installDirectory = Files.createDirectories(tempDirectory.resolve("install/preview"));
		runtimeDirectory = Files.createDirectories(tempDirectory.resolve("runtime"));
		settingsPath = runtimeDirectory.resolve("settings-preview-local.json");
	}

	@Test
	public void testFreshRuntimeUsesPersistentWalletPath() throws Exception {
		writeSettings("{\"apiPort\":24891}");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertFalse(result.customPath());
		assertFalse(result.migrated());
		assertTrue(result.legacyCleanupComplete());
		assertEquals(runtimeDirectory.resolve("wallets").toString(), readSettings().get("walletsPath"));
	}

	@Test
	public void testLegacyWalletTreeMovesOnlyAfterVerifiedCopy() throws Exception {
		writeSettings("{\"apiPort\":24891}");
		Path state = installDirectory.resolve("wallets/PirateChain/unified/account/qortium-wallet-state.json");
		Path registry = installDirectory.resolve("wallets/PirateChain/unified/account/wallet.db");
		write(state, "{\"state\":\"UNIFIED_READY\"}");
		write(registry, "registry-bytes");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertTrue(result.migrated());
		assertTrue(result.legacyCleanupComplete());
		assertFalse(Files.exists(installDirectory.resolve("wallets")));
		assertEquals("{\"state\":\"UNIFIED_READY\"}",
				Files.readString(runtimeDirectory.resolve("wallets/PirateChain/unified/account/qortium-wallet-state.json")));
		assertEquals("registry-bytes",
				Files.readString(runtimeDirectory.resolve("wallets/PirateChain/unified/account/wallet.db")));
		assertEquals(runtimeDirectory.resolve("wallets").toString(), readSettings().get("walletsPath"));
	}

	@Test
	public void testPartialIdenticalTargetMergesWithoutClobbering() throws Exception {
		writeSettings("{\"walletsPath\":\"wallets\"}");
		Path legacyShared = installDirectory.resolve("wallets/PirateChain/shared.bin");
		Path targetShared = runtimeDirectory.resolve("wallets/PirateChain/shared.bin");
		write(legacyShared, "same");
		write(targetShared, "same");
		write(installDirectory.resolve("wallets/PirateChain/unified/new.bin"), "new");
		write(runtimeDirectory.resolve("wallets/PirateChain/runtime-only.bin"), "keep");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertTrue(result.migrated());
		assertTrue(result.legacyCleanupComplete());
		assertFalse(Files.exists(installDirectory.resolve("wallets")));
		assertEquals("same", Files.readString(targetShared));
		assertEquals("new", Files.readString(runtimeDirectory.resolve("wallets/PirateChain/unified/new.bin")));
		assertEquals("keep", Files.readString(runtimeDirectory.resolve("wallets/PirateChain/runtime-only.bin")));
	}

	@Test
	public void testConflictFailsBeforeSettingsOrTreesChange() throws Exception {
		writeSettings("{\"apiPort\":24891}");
		Path legacy = installDirectory.resolve("wallets/PirateChain/wallet.db");
		Path target = runtimeDirectory.resolve("wallets/PirateChain/wallet.db");
		write(legacy, "legacy");
		write(target, "runtime");
		byte[] settingsBefore = Files.readAllBytes(settingsPath);

		try {
			prepare();
			fail("Expected conflicting wallet state to fail closed");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("file conflict"));
		}

		assertArrayEquals(settingsBefore, Files.readAllBytes(settingsPath));
		assertEquals("legacy", Files.readString(legacy));
		assertEquals("runtime", Files.readString(target));
	}

	@Test
	public void testCustomWalletPathIsPreservedWithoutMigration() throws Exception {
		Path custom = tempDirectory.resolve("operator-wallets");
		writeSettings("{\"walletsPath\":" + MAPPER.writeValueAsString(custom.toString()) + "}");
		Path legacy = installDirectory.resolve("wallets/PirateChain/wallet.db");
		write(legacy, "legacy");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertTrue(result.customPath());
		assertFalse(result.migrated());
		assertEquals(custom.toAbsolutePath().normalize(), result.walletsPath());
		assertEquals(custom.toString(), readSettings().get("walletsPath"));
		assertEquals("legacy", Files.readString(legacy));
		assertFalse(Files.exists(runtimeDirectory.resolve("wallets")));
	}

	@Test
	public void testRelativeCustomWalletPathIsPreservedWithoutMigration() throws Exception {
		writeSettings("{\"walletsPath\":\"operator/wallets\"}");
		Path legacy = installDirectory.resolve("wallets/PirateChain/wallet.db");
		write(legacy, "legacy");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertTrue(result.customPath());
		assertEquals(installDirectory.resolve("operator/wallets").toAbsolutePath().normalize(), result.walletsPath());
		assertEquals("operator/wallets", readSettings().get("walletsPath"));
		assertEquals("legacy", Files.readString(legacy));
		assertFalse(Files.exists(runtimeDirectory.resolve("wallets")));
	}

	@Test
	public void testLegacySymlinkFailsBeforeSettingsOrTreesChange() throws Exception {
		writeSettings("{\"apiPort\":24891}");
		Path external = Files.createDirectories(tempDirectory.resolve("external-wallets"));
		write(external.resolve("wallet.db"), "external");
		try {
			Files.createSymbolicLink(installDirectory.resolve("wallets"), external);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			assumeNoException("Symbolic links are not available on this test platform", e);
		}
		byte[] settingsBefore = Files.readAllBytes(settingsPath);

		try {
			prepare();
			fail("Expected a legacy wallet symlink to fail closed");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("not a regular directory"));
		}

		assertArrayEquals(settingsBefore, Files.readAllBytes(settingsPath));
		assertTrue(Files.isSymbolicLink(installDirectory.resolve("wallets")));
		assertEquals("external", Files.readString(external.resolve("wallet.db")));
		assertFalse(Files.exists(runtimeDirectory.resolve("wallets")));
	}

	@Test
	public void testManagedRuntimePathIsIdempotent() throws Exception {
		Path target = runtimeDirectory.resolve("wallets").toAbsolutePath().normalize();
		writeSettings("{\"walletsPath\":" + MAPPER.writeValueAsString(target.toString()) + "}");
		write(target.resolve("PirateChain/wallet.db"), "ready");

		PreviewRuntimeWallets.PreparationResult result = prepare();

		assertFalse(result.customPath());
		assertFalse(result.migrated());
		assertEquals("ready", Files.readString(target.resolve("PirateChain/wallet.db")));
		assertEquals(target.toString(), readSettings().get("walletsPath"));
	}

	@Test
	public void testNonStringWalletPathFailsWithoutMutation() throws Exception {
		writeSettings("{\"walletsPath\":7}");
		byte[] before = Files.readAllBytes(settingsPath);

		try {
			prepare();
			fail("Expected invalid walletsPath to fail closed");
		} catch (IOException e) {
			assertTrue(e.getMessage().contains("non-empty string"));
		}

		assertArrayEquals(before, Files.readAllBytes(settingsPath));
	}

	@Test
	public void testUnixAndWindowsLaunchersUseSharedWalletPreparation() throws Exception {
		String unixLauncher = Files.readString(Path.of("preview/start.sh"));
		String windowsLauncher = Files.readString(Path.of("preview/start.ps1"));
		String unixReset = Files.readString(Path.of("preview/reset.sh"));
		String windowsReset = Files.readString(Path.of("preview/reset.ps1"));

		assertTrue(unixLauncher.contains("org.qortium.PreviewRuntimeWallets"));
		assertTrue(windowsLauncher.contains("org.qortium.PreviewRuntimeWallets"));
		assertFalse(unixReset.contains("${RUNTIME_DIR}/wallets"));
		assertFalse(windowsReset.contains("Join-Path $RuntimeDir \"wallets\""));
	}

	private PreviewRuntimeWallets.PreparationResult prepare() throws IOException {
		return PreviewRuntimeWallets.prepare(installDirectory, runtimeDirectory, settingsPath);
	}

	private void writeSettings(String json) throws IOException {
		Files.writeString(settingsPath, json, StandardCharsets.UTF_8);
	}

	private Map<String, Object> readSettings() throws IOException {
		return MAPPER.readValue(Files.readAllBytes(settingsPath), MAP_TYPE);
	}

	private static void write(Path path, String value) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, value, StandardCharsets.UTF_8);
	}
}
