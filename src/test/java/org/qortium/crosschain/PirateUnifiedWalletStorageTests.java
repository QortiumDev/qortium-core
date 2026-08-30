package org.qortium.crosschain;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.qortium.crosschain.PirateWallet.EndpointSelectionOutcome.APPLIED;
import static org.qortium.crosschain.PirateWallet.EndpointSelectionOutcome.ENDPOINT_REJECTED;
import static org.qortium.crosschain.PirateWallet.EndpointSelectionOutcome.RETRYABLE_FAILURE;

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
		assertEquals(APPLIED, first.applyValidatedServerSelection(
				adapter, selection("light.example", DEFAULT_BIRTHDAY, 1)));
		adapter.syncTargetHeight = DEFAULT_BIRTHDAY;
		first.recordSynchronizationAccepted(adapter);
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
	public void testSwitchRequiresCancellationAcknowledgementEvenWhenLegacyStatusLooksIdle() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(23), false, false);
		FakeAdapter adapter = new FakeAdapter();
		adapter.syncInProgress = false;
		adapter.cancelAcknowledged = false;
		assertFalse(wallet.prepareForSwitch(adapter));
		adapter.cancelAcknowledged = true;
		assertTrue(wallet.prepareForSwitch(adapter));
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

	@Test
	public void testPersistentShutdownCancelsNativeSyncAndPreservesStorage() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(this.temporaryDirectory.resolve("persistent-shutdown")),
				entropy(24), false, false);
		FakeAdapter adapter = new FakeAdapter();
		assertTrue(wallet.initializeUnified(adapter, "https://light.example:443/", DEFAULT_BIRTHDAY));
		Path storage = wallet.getUnifiedStorage().getStorageDirectory();
		long cancellationsBefore = adapter.calls.stream().filter("cancel_sync"::equals).count();

		assertTrue(wallet.prepareForShutdown(adapter));
		assertTrue(Files.isDirectory(storage));
		assertEquals(cancellationsBefore + 1L,
				adapter.calls.stream().filter("cancel_sync"::equals).count());

		adapter.cancelAcknowledged = false;
		assertFalse(wallet.prepareForShutdown(adapter));
		assertTrue(Files.isDirectory(storage));
	}

	@Test
	public void testValidatedNativeEndpointCutoverIsOrderedIdempotentAndRequiresFreshSync() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(this.temporaryDirectory.resolve("cutover")),
				entropy(20), false, false);
		FakeAdapter adapter = new FakeAdapter();
		ZcashFamilyLightClient.ValidatedServerSelection first = selection("a.example", DEFAULT_BIRTHDAY, 1);

		assertEquals(APPLIED, wallet.applyValidatedServerSelection(adapter, first));
		assertEquals(List.of("get_active_wallet", "test_node", "cancel_sync", "set_lightd_endpoint",
				"get_lightd_endpoint", "validate_consensus_branch", "get_active_wallet"), adapter.calls);
		assertEquals("https://a.example:443",
				wallet.getUnifiedStorage().read().getSelectedServerUri());
		assertTrue(wallet.requiresFreshSynchronization());

		adapter.syncTargetHeight = DEFAULT_BIRTHDAY; // Persisted pre-cutover target must not count as fresh evidence.
		int callsAfterFirstCutover = adapter.invokeCalls;
		long endpointMutations = adapter.calls.stream().filter("set_lightd_endpoint"::equals).count();
		assertEquals(APPLIED, wallet.applyValidatedServerSelection(adapter, first));
		assertEquals(callsAfterFirstCutover, adapter.invokeCalls);
		assertEquals(endpointMutations,
				adapter.calls.stream().filter("set_lightd_endpoint"::equals).count());
		assertTrue(wallet.requiresFreshSynchronization());

		wallet.recordSynchronizationAccepted(adapter);
		assertFalse(wallet.requiresFreshSynchronization());
		assertEquals(Integer.valueOf(DEFAULT_BIRTHDAY), wallet.getChainTip(adapter));
		long nodeProbes = adapter.calls.stream().filter("test_node"::equals).count();
		adapter.syncTargetHeight = 0L;
		assertEquals(Integer.valueOf(DEFAULT_BIRTHDAY), wallet.getChainTip(adapter));
		assertEquals(nodeProbes + 1L, adapter.calls.stream().filter("test_node"::equals).count());
		assertFalse(adapter.calls.contains("execute:info"));

		ZcashFamilyLightClient.ValidatedServerSelection second = selection("b.example", DEFAULT_BIRTHDAY, 2);
		assertEquals(APPLIED, wallet.applyValidatedServerSelection(adapter, second));
		assertEquals("https://b.example:443",
				wallet.getUnifiedStorage().read().getSelectedServerUri());
		assertTrue(wallet.requiresFreshSynchronization());
	}

	@Test
	public void testEveryNativeEndpointCutoverFailureIsClassifiedAndFailsClosed() throws Exception {
		for (String failure : List.of("node", "chain", "height", "tls", "cancel", "set", "readback", "consensus")) {
			PirateWallet wallet = new PirateWallet(
					this.config(this.temporaryDirectory.resolve("cutover-failure-" + failure)),
					entropy(failure.hashCode()), false, false);
			FakeAdapter adapter = new FakeAdapter();
			switch (failure) {
				case "node" -> adapter.nativeNodeSuccess = false;
				case "chain" -> adapter.nativeChainName = "test";
				case "height" -> adapter.nativeNodeHeight = DEFAULT_BIRTHDAY + 101L;
				case "tls" -> adapter.nativeTlsEnabledOverride = false;
				case "cancel" -> adapter.cancelAcknowledged = false;
				case "set" -> adapter.setAcknowledged = false;
				case "readback" -> adapter.readbackOverride = "https://other.example:443";
				case "consensus" -> adapter.consensusValid = false;
				default -> throw new AssertionError("Unhandled failure");
			}

			PirateWallet.EndpointSelectionOutcome expected = switch (failure) {
				case "chain", "height", "tls", "consensus" -> ENDPOINT_REJECTED;
				case "node", "cancel", "set", "readback" -> RETRYABLE_FAILURE;
				default -> throw new AssertionError("Unhandled failure");
			};
			assertEquals("Unexpected cutover classification at " + failure, expected,
					wallet.applyValidatedServerSelection(adapter, selection("candidate.example", DEFAULT_BIRTHDAY, 1)));
			assertNull(wallet.getUnifiedStorage().read().getSelectedServerUri());
			assertFalse(adapter.calls.contains("execute:sync"));
		}
	}

	@Test
	public void testTransientCutoverFailureRetainsExplicitServerForControllerPacedRetry() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(this.temporaryDirectory.resolve("cutover-retry")),
				entropy(25), false, false);
		FakeAdapter adapter = new FakeAdapter();
		ZcashFamilyLightClient.ValidatedServerSelection serverB =
				selection("b.example", DEFAULT_BIRTHDAY, 1);
		ZcashFamilyLightClient.ValidatedServerSelection serverA =
				selection("a.example", DEFAULT_BIRTHDAY, 2);
		SelectionLightClient lightClient = new SelectionLightClient(serverB, serverA);

		adapter.cancelAcknowledged = false;
		assertFalse(wallet.prepareForSynchronization(adapter, lightClient));
		assertEquals(serverB, lightClient.getValidatedServerSelection());
		assertEquals(0, lightClient.rotationCount);
		assertNull(wallet.getUnifiedStorage().read().getSelectedServerUri());

		adapter.cancelAcknowledged = true;
		assertTrue(wallet.prepareForSynchronization(adapter, lightClient));
		assertEquals(serverB, lightClient.getValidatedServerSelection());
		assertEquals(0, lightClient.rotationCount);
		assertEquals(serverB.getEndpointUri(), wallet.getUnifiedStorage().read().getSelectedServerUri());
		assertEquals(serverB.getEndpointUri(), adapter.endpointReadback);
	}

	@Test
	public void testPermanentWrongChainServerFallsBackToConfiguredAlternative() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(this.temporaryDirectory.resolve("cutover-reject")),
				entropy(26), false, false);
		FakeAdapter adapter = new FakeAdapter();
		ZcashFamilyLightClient.ValidatedServerSelection serverB =
				selection("b.example", DEFAULT_BIRTHDAY, 1);
		ZcashFamilyLightClient.ValidatedServerSelection serverA =
				selection("a.example", DEFAULT_BIRTHDAY, 2);
		SelectionLightClient lightClient = new SelectionLightClient(serverB, serverA);
		adapter.nativeChainNamesByEndpoint.put(serverB.getEndpointUri(), "test");

		assertTrue(wallet.prepareForSynchronization(adapter, lightClient));
		assertEquals(serverA, lightClient.getValidatedServerSelection());
		assertEquals(1, lightClient.rotationCount);
		assertEquals(serverA.getEndpointUri(), wallet.getUnifiedStorage().read().getSelectedServerUri());
		assertEquals(serverA.getEndpointUri(), adapter.endpointReadback);
	}

	@Test
	public void testLateCutoverFailureRequiresNativeRollbackBeforeReusingPriorEndpoint() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(this.temporaryDirectory.resolve("cutover-rollback")),
				entropy(22), false, false);
		FakeAdapter adapter = new FakeAdapter();
		assertEquals(APPLIED,
				wallet.applyValidatedServerSelection(adapter, selection("a.example", DEFAULT_BIRTHDAY, 1)));

		adapter.consensusValid = false;
		assertEquals(ENDPOINT_REJECTED,
				wallet.applyValidatedServerSelection(adapter, selection("b.example", DEFAULT_BIRTHDAY, 2)));
		assertEquals("https://b.example:443", adapter.endpointReadback);

		adapter.consensusValid = true;
		int callsBeforeRollback = adapter.invokeCalls;
		assertEquals(APPLIED,
				wallet.applyValidatedServerSelection(adapter, selection("a.example", DEFAULT_BIRTHDAY, 3)));
		assertTrue(adapter.invokeCalls > callsBeforeRollback);
		assertEquals("https://a.example:443", adapter.endpointReadback);
		assertEquals("https://a.example:443", wallet.getUnifiedStorage().read().getSelectedServerUri());
	}

	@Test
	public void testDisabledUnifiedModeMakesNoEndpointCalls() throws Exception {
		PirateWallet wallet = new PirateWallet(
				this.config(this.temporaryDirectory.resolve("cutover-disabled"), false), entropy(21), false, false);
		FakeAdapter adapter = new FakeAdapter();
		assertEquals(APPLIED,
				wallet.applyValidatedServerSelection(adapter, selection("disabled.example", DEFAULT_BIRTHDAY, 1)));
		assertEquals(0, adapter.invokeCalls);
	}

	@Test
	public void testUnifiedNativeMainServiceCanBackJavaRegtestFixture() throws Exception {
		PirateWallet wallet = new PirateWallet(
				this.config(this.temporaryDirectory.resolve("cutover-regtest")), entropy(24), false, false);
		FakeAdapter adapter = new FakeAdapter();
		ChainableServer server = new ZcashFamilyLightClient.Server(
				"127.0.0.1", ChainableServer.ConnectionType.TCP, 9067);
		ZcashFamilyLightClient.ValidatedServerSelection selection =
				new ZcashFamilyLightClient.ValidatedServerSelection(server, "http://127.0.0.1:9067",
						"regtest", DEFAULT_BIRTHDAY, 1);
		assertEquals(APPLIED, wallet.applyValidatedServerSelection(adapter, selection));
	}

	private static ZcashFamilyLightClient.ValidatedServerSelection selection(String host, long height,
			long generation) {
		ChainableServer server = new ZcashFamilyLightClient.Server(host, ChainableServer.ConnectionType.SSL, 443);
		return new ZcashFamilyLightClient.ValidatedServerSelection(server, "https://" + host + ":443", "main",
				height, generation);
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

	private static final class SelectionLightClient extends ZcashFamilyLightClient {
		private final List<ValidatedServerSelection> selections;
		private ValidatedServerSelection currentSelection;
		private int rotationCount;

		private SelectionLightClient(ValidatedServerSelection... selections) {
			super(new ZcashFamilyWalletConfig("Test", "TEST", "Test", "signature", "encryption", "zs",
					() -> DEFAULT_BIRTHDAY, () -> null), "test", "main",
					servers(selections), Map.of(ChainableServer.ConnectionType.SSL, 443), () -> DEFAULT_BIRTHDAY);
			this.selections = List.of(selections);
			this.currentSelection = this.selections.get(0);
		}

		@Override
		public ValidatedServerSelection getValidatedServerSelection() {
			return this.currentSelection;
		}

		@Override
		public ValidatedServerSelection selectAnyValidatedServer() {
			return this.currentSelection;
		}

		@Override
		public ValidatedServerSelection selectAnotherAfterNativeFailure(ValidatedServerSelection rejectedSelection,
				Set<ChainableServer> rejectedServers, String requestedBy, String notes) {
			if (this.currentSelection != rejectedSelection)
				return this.currentSelection;
			this.rotationCount++;
			this.currentSelection = this.selections.stream()
					.filter(selection -> rejectedServers == null || !rejectedServers.contains(selection.getServer()))
					.findFirst()
					.orElse(null);
			return this.currentSelection;
		}

		private static Set<ChainableServer> servers(ValidatedServerSelection[] selections) {
			Set<ChainableServer> servers = new HashSet<>();
			for (ValidatedServerSelection selection : selections)
				servers.add(selection.getServer());
			return servers;
		}
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
		private boolean setAcknowledged = true;
		private boolean consensusValid = true;
		private boolean nativeNodeSuccess = true;
		private String nativeChainName = "main";
		private final Map<String, String> nativeChainNamesByEndpoint = new HashMap<>();
		private long nativeNodeHeight = DEFAULT_BIRTHDAY;
		private Boolean nativeTlsEnabledOverride;
		private long syncTargetHeight;
		private String endpointReadback;
		private String readbackOverride;
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
			JSONObject request = new JSONObject(requestJson);
			String method = request.optString("method");
			this.calls.add(method);
			if ("get_active_wallet".equals(method))
				return "{\"ok\":true,\"result\":\"wallet-id\"}";
			if ("test_node".equals(method))
				return new JSONObject().put("ok", true).put("result", new JSONObject()
						.put("success", this.nativeNodeSuccess)
						.put("latest_block_height", this.nativeNodeHeight)
						.put("chain_name", this.nativeChainNamesByEndpoint.getOrDefault(
								request.optString("url"), this.nativeChainName))
						.put("tls_enabled", this.nativeTlsEnabledOverride == null
								? request.optString("url").startsWith("https://")
								: this.nativeTlsEnabledOverride)).toString();
			if ("cancel_sync".equals(method))
				return acknowledgement(this.cancelAcknowledged);
			if ("set_lightd_endpoint".equals(method)) {
				if (this.setAcknowledged)
					this.endpointReadback = request.optString("url");
				return acknowledgement(this.setAcknowledged);
			}
			if ("get_lightd_endpoint".equals(method))
				return new JSONObject().put("ok", true).put("result",
						this.readbackOverride == null ? this.endpointReadback : this.readbackOverride).toString();
			if ("validate_consensus_branch".equals(method))
				return new JSONObject().put("ok", true).put("result",
						new JSONObject().put("is_valid", this.consensusValid)).toString();
			if ("sync_status".equals(method))
				return new JSONObject().put("ok", true).put("result",
						new JSONObject().put("target_height", this.syncTargetHeight)).toString();
			return acknowledgement(true);
		}

		private static String acknowledgement(boolean acknowledged) {
			return new JSONObject().put("ok", true).put("result",
					new JSONObject().put("acknowledged", acknowledged)).toString();
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
			this.calls.add("execute:" + command);
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
