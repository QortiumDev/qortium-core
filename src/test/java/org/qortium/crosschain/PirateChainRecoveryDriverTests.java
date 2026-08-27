package org.qortium.crosschain;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PirateChainRecoveryDriverTests {

	private static final int DEFAULT_BIRTHDAY = 2_000_000;
	private Path temporaryDirectory;

	@Before
	public void beforeTest() throws IOException {
		this.temporaryDirectory = Files.createTempDirectory("qortium-pirate-recovery-test-");
	}

	@After
	public void afterTest() throws IOException {
		try (var paths = Files.walk(this.temporaryDirectory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
		}
	}

	private ZcashFamilyWalletConfig config() {
		return new ZcashFamilyWalletConfig("Pirate Chain", "ARRR", "PirateChain", "legacy-signature",
				"ARRRWalletEncryption", "zs", () -> DEFAULT_BIRTHDAY, () -> null,
				() -> true, () -> "unified-signature", () -> false, () -> this.temporaryDirectory);
	}

	private static byte[] entropy(int marker) {
		byte[] entropy = new byte[32];
		for (int i = 0; i < entropy.length; ++i)
			entropy[i] = (byte) (marker + i);
		return entropy;
	}

	/** Scripted fake: responses keyed by JSON method (invokeJson) or command (execute). */
	private static final class ScriptedAdapter implements ZcashFamilyNativeAdapter {
		private final Map<String, String> responsesByMethod = new HashMap<>();
		private final List<JSONObject> invokedPayloads = new ArrayList<>();
		private String syncStatusResponse = "{\"in_progress\":false,\"syncing\":false}";

		ScriptedAdapter respond(String method, String response) {
			this.responsesByMethod.put(method, response);
			return this;
		}

		ScriptedAdapter syncStatus(String response) {
			this.syncStatusResponse = response;
			return this;
		}

		List<JSONObject> payloadsFor(String method) {
			List<JSONObject> matching = new ArrayList<>();
			for (JSONObject payload : this.invokedPayloads)
				if (method.equals(payload.optString("method")))
					matching.add(payload);
			return matching;
		}

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			JSONObject payload = new JSONObject(requestJson);
			this.invokedPayloads.add(payload);
			String response = this.responsesByMethod.get(payload.optString("method"));
			if (response == null)
				throw new IllegalStateException("Unscripted method: " + payload.optString("method"));
			return response;
		}

		@Override
		public String execute(String command, String arguments) {
			if ("syncStatus".equals(command))
				return this.syncStatusResponse;
			throw new IllegalStateException("Unscripted command: " + command);
		}

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void loadLibrary(Path path) {
		}

		@Override
		public void initLogging() {
		}

		@Override
		public String getSeedPhraseFromEntropyB64(String entropy64) {
			return null;
		}

		@Override
		public String getSeedPhraseFromEntropy(String entropy) {
			return null;
		}

		@Override
		public String configureStorage(String baseDirectory, String passphrase) {
			return null;
		}

		@Override
		public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) {
			return null;
		}

		@Override
		public String initFromB64(String serverUri, String params, String wallet64,
				String saplingOutput64, String saplingSpend64) {
			return null;
		}

		@Override
		public String save() {
			return null;
		}
	}

	private PirateWallet walletWithRecord(Long floor) throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(1), false, false);
		PirateUnifiedWalletStorage storage = wallet.getUnifiedStorage();
		storage.write(PirateUnifiedWalletStorage.State.MIGRATING, false, "identity-hash", null, floor);
		return wallet;
	}

	private static final String WALLET_ID_OK = "{\"ok\":true,\"result\":\"wallet-1\"}";
	private static final String SPENDABILITY_GATED =
			"{\"ok\":true,\"result\":{\"spendable\":false,\"rescan_required\":true,\"repair_queued\":false,"
					+ "\"target_height\":100,\"anchor_height\":90,\"validated_anchor_height\":90,\"reason_code\":\"ERR_RESCAN_REQUIRED\"}}";
	private static final String SPENDABILITY_TERMINAL =
			"{\"ok\":true,\"result\":{\"spendable\":true,\"rescan_required\":false,\"repair_queued\":false,"
					+ "\"target_height\":100,\"anchor_height\":100,\"validated_anchor_height\":100,\"reason_code\":\"OK\"}}";
	private static final String ACKNOWLEDGED = "{\"ok\":true,\"result\":{\"acknowledged\":true}}";
	private static final String NOT_ACKNOWLEDGED = "{\"ok\":true,\"result\":{\"acknowledged\":false}}";

	@Test
	public void testRecordRoundTripAndPreservationThroughOtherWrites() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		PirateUnifiedWalletStorage storage = wallet.getUnifiedStorage();
		assertEquals(Long.valueOf(1_999_000L), storage.read().getRecoveryRescanFromHeight());

		// Three- and four-argument writes preserve the record
		storage.write(PirateUnifiedWalletStorage.State.MIGRATING, false, "identity-hash");
		assertEquals(Long.valueOf(1_999_000L), storage.read().getRecoveryRescanFromHeight());
		storage.write(PirateUnifiedWalletStorage.State.MIGRATING, false, "identity-hash", "http://127.0.0.1:9067");
		assertEquals(Long.valueOf(1_999_000L), storage.read().getRecoveryRescanFromHeight());
		assertEquals("http://127.0.0.1:9067", storage.read().getSelectedServerUri());

		// Explicit clear
		storage.write(PirateUnifiedWalletStorage.State.MIGRATING, false, "identity-hash",
				"http://127.0.0.1:9067", null);
		assertNull(storage.read().getRecoveryRescanFromHeight());
	}

	@Test
	public void testCorruptRecoveryHeightFailsClosed() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		PirateUnifiedWalletStorage storage = wallet.getUnifiedStorage();
		Path statePath = storage.getStorageDirectory().resolve(PirateUnifiedWalletStorage.STATE_FILENAME);
		String stateJson = Files.readString(statePath);
		Files.writeString(statePath, stateJson.replace("1999000", "0"));

		assertTrue(storage.read().isCorrupt());
	}

	@Test
	public void testIdleGatedWalletIssuesRescanFromDurableFloor() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		ScriptedAdapter adapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("get_spendability_status", SPENDABILITY_GATED)
				.respond("rescan", ACKNOWLEDGED);

		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERING, wallet.progressRecovery(adapter));

		List<JSONObject> rescans = adapter.payloadsFor("rescan");
		assertEquals(1, rescans.size());
		assertEquals("wallet-1", rescans.get(0).getString("wallet_id"));
		assertEquals(1_999_000L, rescans.get(0).getLong("from_height"));
		// The durable record is retained until the spendability authority is terminal-safe
		assertEquals(Long.valueOf(1_999_000L),
				wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERING, wallet.peekRecoveryProgress());
	}

	@Test
	public void testActiveNativeSyncIsObservedWithoutReissuingRescan() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		ScriptedAdapter adapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("get_spendability_status", SPENDABILITY_GATED)
				.syncStatus("{\"in_progress\":true}");

		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERING, wallet.progressRecovery(adapter));
		assertTrue(adapter.payloadsFor("rescan").isEmpty());
	}

	@Test
	public void testTerminalSpendabilityClearsRecordAndReportsRecovered() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		ScriptedAdapter adapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("get_spendability_status", SPENDABILITY_TERMINAL);

		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERED, wallet.progressRecovery(adapter));
		assertNull(wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
		assertTrue(adapter.payloadsFor("rescan").isEmpty());
		assertFalse(wallet.hasPendingRecovery());

		// After completion the marker persists for this wallet's lifetime
		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERED, wallet.peekRecoveryProgress());
		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERED, wallet.progressRecovery(adapter));
	}

	@Test
	public void testMalformedSpendabilityNeverClearsTheRecord() throws Exception {
		for (String malformed : new String[] {
				"{\"ok\":true,\"result\":{\"spendable\":\"true\",\"rescan_required\":false,\"repair_queued\":false}}",
				"{\"ok\":\"true\",\"result\":{\"spendable\":true,\"rescan_required\":false,\"repair_queued\":false}}",
				"{\"ok\":true,\"result\":{\"spendable\":true}}",
				"{\"ok\":true}",
				"not-json" }) {
			PirateWallet wallet = this.walletWithRecord(1_999_000L);
			ScriptedAdapter adapter = new ScriptedAdapter()
					.respond("get_active_wallet", WALLET_ID_OK)
					.respond("get_spendability_status", malformed)
					.respond("rescan", ACKNOWLEDGED);

			wallet.progressRecovery(adapter);
			assertEquals("record must survive: " + malformed, Long.valueOf(1_999_000L),
					wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
		}
	}

	@Test
	public void testUnacknowledgedRescanStaysPendingForNextPass() throws Exception {
		PirateWallet wallet = this.walletWithRecord(1_999_000L);
		ScriptedAdapter adapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("get_spendability_status", SPENDABILITY_GATED)
				.respond("rescan", NOT_ACKNOWLEDGED);

		assertEquals(ZcashFamilyWallet.RecoveryProgress.PENDING, wallet.progressRecovery(adapter));
		assertEquals(Long.valueOf(1_999_000L),
				wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
	}

	@Test
	public void testReopenedWalletInstanceResumesDrivingTheDurableRecord() throws Exception {
		// Simulates a Core restart: a fresh wallet object over the same namespace must see
		// the durable record and drive the replay again, because the native side never
		// auto-resumes it.
		PirateWallet original = this.walletWithRecord(1_999_000L);
		PirateWallet reopened = new PirateWallet(this.config(), entropy(1), false, false);
		assertEquals(original.getUnifiedStorage().getStorageDirectory(),
				reopened.getUnifiedStorage().getStorageDirectory());
		assertTrue(reopened.hasPendingRecovery());
		assertEquals(ZcashFamilyWallet.RecoveryProgress.PENDING, reopened.peekRecoveryProgress());

		ScriptedAdapter adapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("get_spendability_status", SPENDABILITY_GATED)
				.respond("rescan", ACKNOWLEDGED);
		assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERING, reopened.progressRecovery(adapter));
		assertEquals(1_999_000L, adapter.payloadsFor("rescan").get(0).getLong("from_height"));
	}

	@Test
	public void testWalletWithoutRecordReportsNone() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(2), false, false);
		ScriptedAdapter adapter = new ScriptedAdapter();

		assertEquals(ZcashFamilyWallet.RecoveryProgress.NONE, wallet.progressRecovery(adapter));
		assertNull(wallet.peekRecoveryProgress());
		assertFalse(wallet.hasPendingRecovery());
		assertTrue(adapter.invokedPayloads.isEmpty());
	}

	@Test
	public void testImportResponsePersistsAndClearsTheDriverRecord() throws Exception {
		PirateWallet wallet = new PirateWallet(this.config(), entropy(3), false, false);
		PirateChainVerifiedRecoveryRequest request = new PirateChainVerifiedRecoveryRequest();
		request.entropy58 = org.qortium.utils.Base58.encode(entropy(3));
		request.pool = "sapling";
		request.spendingKey = "secret-extended-key-main1testvector";
		request.expectedAddress = "zs1expectedaddress";
		request.addressIndex = 0;
		request.birthdayHeight = 1_999_000;

		ScriptedAdapter pendingAdapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("import_spending_key_verified",
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1canonical\","
								+ "\"address_index\":0,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":1999000}}");
		PirateChainVerifiedRecoveryResult pending = wallet.importVerifiedSpendingKey(pendingAdapter, request);
		assertEquals(Long.valueOf(1_999_000L), pending.requiredRescanFromHeight);
		assertEquals(Long.valueOf(1_999_000L),
				wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
		assertTrue(wallet.hasPendingRecovery());

		// An exact retry whose native verdict reports no pending floor clears the record
		ScriptedAdapter completedAdapter = new ScriptedAdapter()
				.respond("get_active_wallet", WALLET_ID_OK)
				.respond("import_spending_key_verified",
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1canonical\","
								+ "\"address_index\":0,\"birthday_height\":1999000,\"already_imported\":true,"
								+ "\"rescan_required\":false,\"required_rescan_from_height\":null}}");
		PirateChainVerifiedRecoveryResult completed = wallet.importVerifiedSpendingKey(completedAdapter, request);
		assertNull(completed.requiredRescanFromHeight);
		assertNull(wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
		assertFalse(wallet.hasPendingRecovery());
	}
}
