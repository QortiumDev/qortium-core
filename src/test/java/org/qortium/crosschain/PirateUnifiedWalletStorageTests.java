package org.qortium.crosschain;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PirateUnifiedWalletStorageTests {

	private static final int DEFAULT_BIRTHDAY = 2_000_000;
	private Path temporaryDirectory;

	@Before
	public void beforeTest() throws IOException {
		this.temporaryDirectory = Files.createTempDirectory("qortium-pirate-unified-test-");
	}

	@After
	public void afterTest() throws IOException {
		try (var paths = Files.walk(this.temporaryDirectory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}

	@Test
	public void testEntropyNamespacesAndTransientStorageAreIsolated() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		byte[] entropyA = entropy(1);
		byte[] entropyB = entropy(2);

		PirateWallet walletA = new PirateWallet(config, entropyA, false, false);
		PirateWallet walletB = new PirateWallet(config, entropyB, false, false);
		PirateWallet transientA = new PirateWallet(config, new byte[32], true, false);
		PirateWallet transientB = new PirateWallet(config, new byte[32], true, false);

		Path pathA = walletA.getUnifiedStorage().getStorageDirectory();
		Path pathB = walletB.getUnifiedStorage().getStorageDirectory();
		assertNotEquals(pathA, pathB);
		assertFalse(pathA.toString().contains(org.qortium.utils.Base58.encode(entropyA)));
		assertFalse(pathB.toString().contains(org.qortium.utils.Base58.encode(entropyB)));
		assertNotEquals(transientA.getUnifiedStorage().getStorageDirectory(),
				transientB.getUnifiedStorage().getStorageDirectory());
		assertTrue(transientA.getUnifiedStorage().getStorageDirectory()
				.startsWith(config.getUnifiedWalletsDirectory().resolve("transient")));
		assertFalse(transientA.getUnifiedStorage().getStorageDirectory().equals(pathA));
		assertFalse(transientA.matchesWallet(new byte[32], false));
		assertTrue(transientA.matchesWallet(new byte[32], true));
		FakeAdapter transientAdapter = new FakeAdapter();
		assertTrue(transientA.initializeUnified(transientAdapter, "https://light.example:443/", 4_200_000));
		assertEquals("4200000", transientAdapter.lastBirthday);
		assertFalse(Files.exists(transientA.getUnifiedStorage().getStorageDirectory()
				.resolve(PirateUnifiedWalletStorage.STATE_FILENAME)));
	}

	@Test
	public void testLegacySaveReplacementCannotLeaveTrailingBytes() throws Exception {
		Path walletPath = this.temporaryDirectory.resolve("legacy-wallet.dat");
		ZcashFamilyWallet.writeWalletAtomically(walletPath, "a much longer wallet".getBytes(StandardCharsets.UTF_8));
		byte[] replacement = "short".getBytes(StandardCharsets.UTF_8);
		ZcashFamilyWallet.writeWalletAtomically(walletPath, replacement);
		assertArrayEquals(replacement, Files.readAllBytes(walletPath));
	}

	@Test
	public void testFreshAndCleanRestoreAlwaysUseConservativeBirthday() throws Exception {
		assertEquals(DEFAULT_BIRTHDAY, PirateWallet.chooseUnifiedBirthday(DEFAULT_BIRTHDAY, false, 4_200_000));
		assertEquals(4_200_000, PirateWallet.chooseUnifiedBirthday(DEFAULT_BIRTHDAY, true, 4_200_000));

		PirateWallet wallet = new PirateWallet(this.config(), entropy(3), false, false);
		FakeAdapter adapter = new FakeAdapter();

		assertTrue(wallet.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(Integer.toString(DEFAULT_BIRTHDAY), adapter.lastBirthday);
		assertEquals(PirateUnifiedWalletStorage.State.MIGRATING,
				wallet.getUnifiedStorage().read().getState());
		assertFalse(wallet.getUnifiedStorage().read().isSyncValidated());
	}

	@Test
	public void testMigrationKeepsLegacyReadableAndVerifiesIdentity() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		byte[] entropy = entropy(4);
		PirateWallet wallet = new PirateWallet(config, entropy, false, false);
		byte[] legacyBytes = "legacy-wallet-content".getBytes(StandardCharsets.UTF_8);
		writeLegacyInputs(config, wallet, legacyBytes);
		FakeAdapter adapter = new FakeAdapter();
		assertTrue(wallet.captureLegacyIdentity(adapter));

		assertTrue(wallet.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertFalse(adapter.calls.contains("init-legacy"));
		assertArrayEquals(legacyBytes, Files.readAllBytes(wallet.getUnifiedStorage().getLegacyWalletPath()));
		assertNotNull(wallet.getUnifiedStorage().read().getIdentityHash());
		String stateText = Files.readString(wallet.getUnifiedStorage().getStorageDirectory()
				.resolve(PirateUnifiedWalletStorage.STATE_FILENAME));
		assertFalse(stateText.contains(org.qortium.utils.Base58.encode(entropy)));
		assertFalse(stateText.toLowerCase().contains("seed"));
	}

	@Test
	public void testLegacyCacheRequiresDisabledModeIdentityPreflight() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		PirateWallet wallet = new PirateWallet(config, entropy(11), false, false);
		byte[] legacyBytes = "legacy-needs-preflight".getBytes(StandardCharsets.UTF_8);
		writeLegacyInputs(config, wallet, legacyBytes);

		assertFalse(wallet.initializeUnified(new FakeAdapter(), "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertArrayEquals(legacyBytes, Files.readAllBytes(wallet.getUnifiedStorage().getLegacyWalletPath()));
		assertEquals(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE,
				wallet.getUnifiedStorage().read().getState());
	}

	@Test
	public void testLegacyIdentityPreflightMakesNoUnifiedNativeCalls() throws Exception {
		ZcashFamilyWalletConfig config = this.config(this.temporaryDirectory.resolve("legacy-preflight"), false);
		PirateWallet wallet = new PirateWallet(config, entropy(12), false, false);
		writeLegacyInputs(config, wallet, "legacy".getBytes(StandardCharsets.UTF_8));
		FakeAdapter adapter = new FakeAdapter();

		assertTrue(wallet.captureLegacyIdentity(adapter));
		assertEquals(0, adapter.configureCalls);
		assertEquals(0, adapter.invokeCalls);
		assertEquals(0, adapter.initFromSeedCalls);
		assertNotNull(wallet.getUnifiedStorage().read().getIdentityHash());
		adapter.throwOnBalance = true;
		assertFalse(wallet.captureLegacyIdentity(adapter));
		assertEquals(0, adapter.configureCalls);
	}

	@Test
	public void testValidatedSyncRequiresCleanReopenBeforeReady() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		byte[] entropy = entropy(5);
		FakeAdapter adapter = new FakeAdapter();
		PirateWallet first = new PirateWallet(config, entropy, false, false);

		assertTrue(first.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		first.recordValidatedSync(adapter);
		assertEquals(PirateUnifiedWalletStorage.State.MIGRATING, first.getUnifiedStorage().read().getState());
		assertTrue(first.getUnifiedStorage().read().isSyncValidated());

		PirateWallet reopened = new PirateWallet(config, entropy, false, false);
		assertTrue(reopened.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.UNIFIED_READY,
				reopened.getUnifiedStorage().read().getState());
		assertTrue(reopened.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.UNIFIED_READY,
				reopened.getUnifiedStorage().read().getState());
	}

	@Test
	public void testPersistentUnifiedWalletRequiresExactTipBeforeValidation() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		PirateWallet wallet = new PirateWallet(config, entropy(6), false, false);

		assertEquals(0, wallet.synchronizationLagTolerance());
		assertFalse(ZcashFamilyWallet.isHeightSynchronized(152_857, 152_858, wallet.synchronizationLagTolerance()));
		assertTrue(ZcashFamilyWallet.isHeightSynchronized(152_858, 152_858, wallet.synchronizationLagTolerance()));
		assertTrue(ZcashFamilyWallet.isHeightSynchronized(152_856, 152_858, 2));
	}

	@Test
	public void testPartialMigrationRecoversIdempotentlyButMissingReadyDatabaseFailsClosed() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		PirateWallet partial = new PirateWallet(config, entropy(9), false, false);
		partial.getUnifiedStorage().write(PirateUnifiedWalletStorage.State.MIGRATING, false, null);
		assertFalse(partial.getUnifiedStorage().hasNativeRegistry());
		assertTrue(partial.initializeUnified(new FakeAdapter(), "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.MIGRATING, partial.getUnifiedStorage().read().getState());

		PirateWallet missingReadyDatabase = new PirateWallet(config, entropy(10), false, false);
		missingReadyDatabase.getUnifiedStorage().write(PirateUnifiedWalletStorage.State.UNIFIED_READY, true,
				PirateWallet.hashIdentity("zs-ready-wallet"));
		assertFalse(missingReadyDatabase.initializeUnified(
				new FakeAdapter(), "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE,
				missingReadyDatabase.getUnifiedStorage().read().getState());
	}

	@Test
	public void testEveryInjectedMigrationFailurePreservesLegacyWallet() throws Exception {
		for (String failure : List.of("configure", "seed", "init", "height", "address")) {
			Path root = this.temporaryDirectory.resolve(failure);
			Files.createDirectories(root);
			ZcashFamilyWalletConfig config = this.config(root);
			PirateWallet wallet = new PirateWallet(config, entropy(failure.hashCode()), false, false);
			byte[] legacyBytes = ("legacy-" + failure).getBytes(StandardCharsets.UTF_8);
			writeLegacyInputs(config, wallet, legacyBytes);
			assertTrue(wallet.captureLegacyIdentity(new FakeAdapter()));
			FakeAdapter adapter = new FakeAdapter();
			adapter.failure = failure;

			assertFalse("Expected failure at " + failure,
					wallet.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
			assertArrayEquals("Legacy changed at " + failure, legacyBytes,
					Files.readAllBytes(wallet.getUnifiedStorage().getLegacyWalletPath()));
			assertEquals(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE,
					wallet.getUnifiedStorage().read().getState());
		}
	}

	@Test
	public void testCorruptStateIsPreservedAndRecovered() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(6), false, false);
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		Files.createDirectories(storage);
		Files.writeString(storage.resolve(PirateUnifiedWalletStorage.STATE_FILENAME), "not-json");

		assertTrue(wallet.initializeUnified(new FakeAdapter(), "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.MIGRATING, wallet.getUnifiedStorage().read().getState());
		try (var files = Files.list(storage)) {
			assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith(
					PirateUnifiedWalletStorage.STATE_FILENAME + ".corrupt-")));
		}
	}

	@Test
	public void testInvalidReadyStateIsRecoverableRatherThanTrusted() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(14), false, false);
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		Files.createDirectories(storage);
		Files.writeString(storage.resolve(PirateUnifiedWalletStorage.STATE_FILENAME),
				"{\"version\":1,\"state\":\"UNIFIED_READY\",\"syncValidated\":false}");
		PirateUnifiedWalletStorage.Snapshot snapshot = wallet.getUnifiedStorage().read();
		assertEquals(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE, snapshot.getState());
		assertTrue(snapshot.isCorrupt());
	}

	@Test
	public void testRejectedNativeRegistryIsArchivedAndRetryRecovers() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(13), false, false);
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		Files.createDirectories(storage);
		Files.writeString(storage.resolve(PirateUnifiedWalletStorage.NATIVE_REGISTRY_FILENAME), "corrupt-native");
		wallet.getUnifiedStorage().write(PirateUnifiedWalletStorage.State.MIGRATING, false,
				PirateWallet.hashIdentity("zs-same-wallet"));
		FakeAdapter rejected = new FakeAdapter();
		rejected.failure = "configure";

		assertFalse(wallet.initializeUnified(rejected, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertFalse(wallet.getUnifiedStorage().hasNativeRegistry());
		try (var siblings = Files.list(storage.getParent())) {
			assertTrue(siblings.anyMatch(path -> path.getFileName().toString()
					.startsWith(storage.getFileName() + ".failed-")));
		}

		assertTrue(wallet.initializeUnified(new FakeAdapter(), "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertEquals(PirateUnifiedWalletStorage.State.MIGRATING, wallet.getUnifiedStorage().read().getState());
		assertTrue(wallet.getUnifiedStorage().hasNativeRegistry());
	}

	@Test
	public void testIdentityMismatchFailsRecoverablyWithoutTouchingLegacy() throws Exception {
		ZcashFamilyWalletConfig config = this.config();
		PirateWallet wallet = new PirateWallet(config, entropy(7), false, false);
		byte[] legacyBytes = "identity-source".getBytes(StandardCharsets.UTF_8);
		writeLegacyInputs(config, wallet, legacyBytes);
		assertTrue(wallet.captureLegacyIdentity(new FakeAdapter()));
		FakeAdapter adapter = new FakeAdapter();
		adapter.legacyAddress = "zs-different";

		assertFalse(wallet.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		assertArrayEquals(legacyBytes, Files.readAllBytes(wallet.getUnifiedStorage().getLegacyWalletPath()));
		assertEquals(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE,
				wallet.getUnifiedStorage().read().getState());
	}

	@Test
	public void testSwitchFailsClosedUntilNativeSyncIsQuiescent() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(8), false, false);
		FakeAdapter adapter = new FakeAdapter();
		adapter.syncInProgress = true;
		assertTrue(wallet.prepareForSwitch(adapter));
		adapter.cancelAcknowledged = false;
		assertFalse(wallet.prepareForSwitch(adapter));
	}

	@Test
	public void testTransientStorageIsRemovedAfterQuiescentSwitch() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), new byte[32], true, false);
		FakeAdapter adapter = new FakeAdapter();
		assertTrue(wallet.initializeUnified(adapter, "https://light.example:443/", 4_200_000));
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		assertTrue(Files.isDirectory(storage));
		assertTrue(wallet.prepareForSwitch(adapter));
		assertTrue(Files.isDirectory(storage));
		wallet.cleanupAfterSwitch();
		assertFalse(Files.exists(storage));
	}

	@Test
	public void testShutdownReselectsStorageBeforeRemovingTransientDatabase() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), new byte[32], true, false);
		FakeAdapter adapter = new FakeAdapter();
		assertTrue(wallet.initializeUnified(adapter, "https://light.example:443/", 4_200_000));
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		assertTrue(wallet.prepareForShutdown(adapter));
		assertFalse(Files.exists(storage));
		assertTrue(adapter.configureCalls >= 2);
	}

	private ZcashFamilyWalletConfig config() {
		return this.config(this.temporaryDirectory);
	}

	private ZcashFamilyWalletConfig config(Path walletsRoot) {
		return this.config(walletsRoot, true);
	}

	private ZcashFamilyWalletConfig config(Path walletsRoot, boolean unified) {
		return new ZcashFamilyWalletConfig("Pirate Chain", "ARRR", "PirateChain", "legacy-signature",
				"ARRRWalletEncryption", "zs", () -> DEFAULT_BIRTHDAY, () -> null,
				() -> unified, () -> "unified-signature", () -> false, () -> walletsRoot);
	}

	private static byte[] entropy(int marker) {
		byte[] entropy = new byte[32];
		for (int i = 0; i < entropy.length; ++i)
			entropy[i] = (byte) (marker + i);
		return entropy;
	}

	private static void writeLegacyInputs(ZcashFamilyWalletConfig config, PirateWallet wallet, byte[] walletBytes)
			throws IOException {
		Path legacyLib = config.getLegacyRustLibOuterDirectory();
		Files.createDirectories(legacyLib);
		Files.writeString(legacyLib.resolve("coinparams.json"), "params");
		Files.writeString(legacyLib.resolve("saplingoutput_base64"), "output");
		Files.writeString(legacyLib.resolve("saplingspend_base64"), "spend");
		Files.createDirectories(wallet.getUnifiedStorage().getLegacyWalletPath().getParent());
		Files.write(wallet.getUnifiedStorage().getLegacyWalletPath(), walletBytes);
	}

	private static final class FakeAdapter implements ZcashFamilyNativeAdapter {
		private final List<String> calls = new ArrayList<>();
		private String failure;
		private String phase = "unified";
		private String legacyAddress = "zs-same-wallet";
		private String unifiedAddress = "zs-same-wallet";
		private String lastBirthday;
		private boolean syncInProgress;
		private boolean cancelAcknowledged = true;
		private int configureCalls;
		private int invokeCalls;
		private int initFromSeedCalls;
		private boolean throwOnBalance;

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void loadLibrary(Path path) {
		}

		@Override
		public void initLogging() {
			this.calls.add("logging");
		}

		@Override
		public String getSeedPhraseFromEntropyB64(String entropy64) {
			this.calls.add("seed");
			return "seed".equals(this.failure) ? "{}" : "{\"seedPhrase\":\"twelve words\"}";
		}

		@Override
		public String getSeedPhraseFromEntropy(String entropy) {
			return null;
		}

		@Override
		public String configureStorage(String baseDirectory, String passphrase) {
			this.calls.add("configure");
			this.configureCalls++;
			if ("configure".equals(this.failure))
				return "{\"initialized\":false}";
			try {
				Files.createDirectories(Path.of(baseDirectory));
				Files.writeString(Path.of(baseDirectory).resolve(PirateUnifiedWalletStorage.NATIVE_REGISTRY_FILENAME),
						"native-registry");
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return "{\"initialized\":true}";
		}

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			this.invokeCalls++;
			if (requestJson.contains("get_active_wallet"))
				return "{\"ok\":true,\"result\":\"wallet-id\"}";
			if (requestJson.contains("cancel_sync"))
				return "{\"ok\":" + this.cancelAcknowledged + "}";
			return "{\"ok\":true}";
		}

		@Override
		public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) {
			this.calls.add("init-unified");
			this.initFromSeedCalls++;
			this.phase = "unified";
			this.lastBirthday = birthday;
			return "init".equals(this.failure) ? "{}" : "{\"seed\":\"twelve words\"}";
		}

		@Override
		public String initFromB64(String serverUri, String params, String wallet64, String saplingOutput64,
				String saplingSpend64) {
			this.calls.add("init-legacy");
			throw new AssertionError("Unified migration must not pass a legacy blob to initFromB64");
		}

		@Override
		public String save() {
			return null;
		}

		@Override
		public String execute(String command, String arguments) {
			if ("export".equals(command)) {
				if ("address".equals(this.failure) && "unified".equals(this.phase))
					return "[]";
				String address = "legacy".equals(this.phase) ? this.legacyAddress : this.unifiedAddress;
				return "[{\"address\":\"" + address + "\"}]";
			}
			if ("balance".equals(command) && this.throwOnBalance)
				throw new IllegalStateException("malformed legacy balance");
			if ("balance".equals(command))
				return "{\"z_addresses\":[{\"address\":\"" + this.legacyAddress + "\"}]}";
			if ("height".equals(command))
				return "{\"height\":" + ("height".equals(this.failure) ? 0 : DEFAULT_BIRTHDAY) + "}";
			if ("info".equals(command))
				return "{\"latest_block_height\":" + DEFAULT_BIRTHDAY + "}";
			if ("syncStatus".equals(command))
				return "{\"in_progress\":" + this.syncInProgress + "}";
			return "{}";
		}
	}
}
