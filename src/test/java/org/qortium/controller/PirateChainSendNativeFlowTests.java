package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.bitcoinj.base.Bech32;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.ApiException;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.api.model.crosschain.PirateChainSendResult;
import org.qortium.api.resource.CrossChainPirateChainResource;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.PirateSendTestWallet;
import org.qortium.crosschain.PirateWallet;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Drives a complete ARRR send from the JSON resource down to the native adapter with a scripted
 * (fake) native library behind the REAL coordinator, controller ownership, synchronized gate and
 * {@link PirateChain#sendCoins} lane logic. Proves a passing request runs synchronization checks,
 * native recipient validation and the verified-funds check before exactly one native {@code send}
 * carrying the exact atomic amount, the fixed fee and the memo verbatim, and returns the JSON
 * result with the native txid. Then proves each in-lane refusal and the outcome-unknown rule.
 */
public class PirateChainSendNativeFlowTests {

	private static final String ENTROPY = entropy(9);
	private static final String INPUT_ADDRESS =
			"zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";
	private static final String RECIPIENT;
	private static final String BAD_POINT_RECIPIENT; // 43 x 0xff: valid Bech32, not a Sapling point
	static {
		byte[] payload = new byte[43];
		for (int i = 0; i < payload.length; i++)
			payload[i] = (byte) (i * 11 + 5);
		RECIPIENT = Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", payload);
		byte[] ff = new byte[43];
		Arrays.fill(ff, (byte) 0xff);
		BAD_POINT_RECIPIENT = Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", ff);
	}

	private ScriptedNativeAdapter adapter;
	private ZcashFamilyNativeAdapter originalAdapter;
	private FakeController controller;
	private CrossChainPirateChainResource resource;

	private static String entropy(int value) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, (byte) value);
		return Base58.encode(bytes);
	}

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		PirateChainWalletController.resetForTesting();
		PirateChain.resetForTesting();
		FieldUtils.writeField(Settings.getInstance(), "pirateChainWalletUnified", true, true);

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		assertFalse("native lane must be healthy for this harness", coordinator.isDegraded());
		this.adapter = new ScriptedNativeAdapter();
		this.originalAdapter = (ZcashFamilyNativeAdapter) FieldUtils.readField(coordinator, "adapter", true);
		FieldUtils.writeField(coordinator, "adapter", this.adapter, true);

		this.controller = new FakeController(this.adapter);
		Field instance = PirateChainWalletController.class.getDeclaredField("instance");
		instance.setAccessible(true);
		instance.set(null, this.controller);

		ApiCommon.installTestApiKey();
		this.resource = (CrossChainPirateChainResource) ApiCommon.buildResource(
				CrossChainPirateChainResource.class, ApiCommon.TEST_API_KEY);

		// Explicit activation, exactly as Home does through /walletsession, makes this entropy the owner.
		String revision = PirateChainWalletController.walletSession(ENTROPY).revision;
		PirateChainWalletController.activateWallet(ENTROPY, revision);
		assertEquals("SELF", PirateChainWalletController.walletSession(ENTROPY).relation);
		this.adapter.calls.clear();
	}

	@After
	public void after() throws Exception {
		FieldUtils.writeField(ZcashFamilyNativeCoordinator.getInstance(), "adapter", this.originalAdapter, true);
		PirateChainWalletController.resetForTesting();
		PirateChain.resetForTesting();
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		FieldUtils.writeField(Settings.getInstance(), "pirateChainWalletUnified", false, true);
		ApiCommon.clearTestApiKey();
	}

	private static PirateChainSendRequest request(String recipient, String amount, String memo) {
		PirateChainSendRequest sendRequest = new PirateChainSendRequest();
		sendRequest.entropy58 = ENTROPY;
		sendRequest.receivingAddress = recipient;
		sendRequest.arrrAmount = amount;
		sendRequest.memo = memo;
		sendRequest.idempotencyKey = "123e4567-e89b-12d3-a456-426614174000";
		return sendRequest;
	}

	private ApiException sendFails(PirateChainSendRequest sendRequest) {
		return assertThrows(ApiException.class, () -> this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, sendRequest));
	}

	@Test
	public void testPassingRequestRunsGatesThenExactlyOneNativeSendAndReturnsTxid() throws Exception {
		String memo = "héllo \"quoted\" back\\slash\nsecond line\t😀";
		PirateChainSendResult result = this.resource.sendPirateChain(ApiCommon.TEST_API_KEY,
				request(RECIPIENT, "1.5", memo));

		assertEquals("f00dtxid", result.txid);
		assertEquals("10000", result.feeAtomic);
		assertEquals("FIXED", result.feePolicy);
		assertEquals(1, result.sendProtocolVersion);

		// Exactly one native send, with the exact atomic amount, the fixed fee, the wallet's own
		// export address as input and the memo round-tripped verbatim through the JSON payload.
		List<String> sends = this.adapter.arguments("execute:send");
		assertEquals(1, sends.size());
		JSONObject payload = new JSONObject(sends.get(0));
		assertEquals(INPUT_ADDRESS, payload.getString("input"));
		assertEquals(10_000L, payload.getLong("fee"));
		JSONArray outputs = payload.getJSONArray("output");
		assertEquals(1, outputs.length());
		assertEquals(RECIPIENT, outputs.getJSONObject(0).getString("address"));
		assertEquals(150_000_000L, outputs.getJSONObject(0).getLong("amount"));
		assertEquals(memo, outputs.getJSONObject(0).getString("memo"));

		// Ordering: synchronized gate (height/tip + native syncStatus) -> native recipient validation
		// -> verified balance -> unlock -> export -> send.
		int syncStatus = this.adapter.indexOf("execute:syncStatus");
		int validate = this.adapter.indexOf("invokeJson:validate_address");
		int balance = this.adapter.indexOf("invokeJson:get_balance");
		int unlock = this.adapter.indexOf("execute:encryptionstatus");
		int export = this.adapter.indexOf("execute:export");
		int send = this.adapter.indexOf("execute:send");
		assertTrue(this.adapter.describe(), syncStatus >= 0 && syncStatus < validate);
		assertTrue(this.adapter.describe(), validate < balance);
		assertTrue(this.adapter.describe(), balance < unlock);
		assertTrue(this.adapter.describe(), unlock < export);
		assertTrue(this.adapter.describe(), export < send);
		assertTrue(this.adapter.indexOf("execute:height") >= 0 && this.adapter.indexOf("execute:height") < send);
		assertEquals(RECIPIENT, new JSONObject(this.adapter.arguments("invokeJson:validate_address").get(0)).getString("address"));
	}

	@Test
	public void testNativeRecipientRejectionIsInvalidAddressAndNothingIsSent() {
		ApiException exception = sendFails(request(BAD_POINT_RECIPIENT, "1.5", null));
		assertEquals(400, exception.status);
		assertEquals(ApiError.INVALID_ADDRESS.getCode(), exception.error);
		assertEquals(PirateChain.RECIPIENT_INVALID_REASON, exception.message);
		assertEquals(0, this.adapter.arguments("execute:send").size());
		// The address only ever went to the non-spending validation call.
		assertEquals(BAD_POINT_RECIPIENT,
				new JSONObject(this.adapter.arguments("invokeJson:validate_address").get(0)).getString("address"));
	}

	@Test
	public void testUnknownUnifiedSpendableBalanceIsNotReadyNotNetworkIssue() {
		this.adapter.balanceReply = "{\"ok\":true,\"result\":{\"total\":\"500000000\"}}";
		ApiException exception = sendFails(request(RECIPIENT, "1.5", null));
		assertEquals(503, exception.status);
		assertEquals(1205, exception.error);
		assertEquals(PirateChain.VERIFIED_BALANCE_UNKNOWN_REASON, exception.message);
		assertEquals(0, this.adapter.arguments("execute:send").size());

		this.adapter.balanceReply = "{\"ok\":true,\"result\":{\"total\":\"500000000\",\"spendable\":null}}";
		exception = sendFails(request(RECIPIENT, "1.5", null));
		assertEquals(1205, exception.error);
		assertEquals(0, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testInsufficientVerifiedFundsIsRefusedBeforeAnySend() {
		// total covers it, verified/spendable does not
		this.adapter.balanceReply = "{\"ok\":true,\"result\":{\"total\":\"500000000\",\"spendable\":\"150009999\"}}";
		ApiException exception = sendFails(request(RECIPIENT, "1.5", null));
		assertEquals(402, exception.status);
		assertEquals(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE.getCode(), exception.error);
		assertEquals(PirateChain.INSUFFICIENT_VERIFIED_FUNDS_REASON, exception.message);
		assertEquals(0, this.adapter.arguments("execute:send").size());

		// exactly amount + fee is enough
		this.adapter.balanceReply = "{\"ok\":true,\"result\":{\"total\":\"500000000\",\"spendable\":\"150010000\"}}";
		assertEquals("f00dtxid", this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, request(RECIPIENT, "1.5", null)).txid);
		assertEquals(1, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testExplicitNativeErrorIsADefinitiveSanitizedFailure() {
		this.adapter.sendReply = "{\"error\":\"Failed to build transaction for " + RECIPIENT + "\"}";
		ApiException exception = sendFails(request(RECIPIENT, "1.5", "private"));
		assertEquals(500, exception.status);
		assertEquals(ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE.getCode(), exception.error);
		assertEquals(PirateChain.NATIVE_SEND_FAILED_REASON, exception.message);
		assertFalse(exception.message.contains(RECIPIENT));
		assertEquals(1, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testTxidLessNativeReplyIsReportedAsUnknownOutcomeWithNoRetryGuidance() {
		this.adapter.sendReply = "garbage";
		ApiException exception = sendFails(request(RECIPIENT, "1.5", null));
		assertEquals(500, exception.status);
		assertEquals(ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE.getCode(), exception.error);
		assertTrue(exception.message, exception.message.startsWith(PirateChain.SEND_OUTCOME_UNKNOWN_REASON));
		assertTrue(exception.message, exception.message.contains("may already have been broadcast"));
		assertTrue(exception.message, exception.message.contains("do NOT retry"));
		assertEquals(1, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testNativeSendThrowingIsAnUnknownOutcomeAtTheWalletLayer() throws Exception {
		this.adapter.sendThrows = true;
		PirateChain pirateChain = PirateChain.getInstance();
		assertNotNull(pirateChain);
		ForeignBlockchainException.SendOutcomeUnknownException unknown = assertThrows(
				ForeignBlockchainException.SendOutcomeUnknownException.class,
				() -> pirateChain.sendCoins(ENTROPY, RECIPIENT, 150_000_000L, null));
		assertEquals(PirateChain.SEND_OUTCOME_UNKNOWN_REASON, unknown.getMessage());
		assertEquals(1, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testNativeValidationOutageIsNotReadyNotValid() {
		this.adapter.validateReply = address -> "{\"ok\":false,\"error\":\"service unavailable\"}";
		ApiException exception = sendFails(request(RECIPIENT, "1.5", null));
		assertEquals(503, exception.status);
		assertEquals(1205, exception.error);
		assertEquals(PirateChain.RECIPIENT_VALIDATION_UNAVAILABLE_REASON, exception.message);
		assertEquals(0, this.adapter.arguments("execute:send").size());
	}

	@Test
	public void testAnotherAccountIsBusyRejectedWithoutAnySend() {
		PirateChainSendRequest other = request(RECIPIENT, "1.5", null);
		other.entropy58 = entropy(10);
		ApiException exception = sendFails(other);
		assertEquals(409, exception.status);
		assertEquals(ApiError.OPERATION_IN_PROGRESS.getCode(), exception.error);
		assertEquals(0, this.adapter.arguments("execute:send").size());
	}

	/** Controller double: real ownership/lane logic, wallet creation and startup stubbed. */
	private static class FakeController extends PirateChainWalletController {
		private final ZcashFamilyNativeAdapter adapter;
		private final Path root = Files.createTempDirectory("arrr-send-flow-test-");

		FakeController(ZcashFamilyNativeAdapter adapter) throws Exception {
			this.adapter = adapter;
			FieldUtils.writeField(this, "lifecycleState", LifecycleState.RUNNING, true);
		}

		@Override public synchronized boolean startController() { return true; }
		@Override public boolean shutdown() { return true; }

		@Override
		protected <T> T executeChecked(String name, ZcashFamilyNativeCoordinator.NativeOperation<T> operation)
				throws ForeignBlockchainException {
			try {
				return operation.execute(this.adapter);
			} catch (ForeignBlockchainException e) {
				throw e;
			} catch (Exception e) {
				throw new ForeignBlockchainException(e.getMessage());
			}
		}

		@Override
		protected PirateWallet createWallet(byte[] bytes, boolean nullSeed, boolean tip) throws IOException {
			return new PirateSendTestWallet(bytes, nullSeed, this.root);
		}
	}

	/** Records every native call in order and answers with scripted replies. */
	private static final class ScriptedNativeAdapter implements ZcashFamilyNativeAdapter {
		final List<String[]> calls = new ArrayList<>();
		String balanceReply = "{\"ok\":true,\"result\":{\"total\":\"500000000\",\"spendable\":\"400000000\",\"pending\":\"0\"}}";
		String sendReply = "{\"txid\":\"f00dtxid\"}";
		boolean sendThrows;
		Function<String, String> validateReply = address -> BAD_POINT_RECIPIENT.equals(address)
				? "{\"ok\":true,\"result\":{\"address_type\":null,\"is_valid\":false,\"reason\":\"Invalid shielded address.\"}}"
				: "{\"ok\":true,\"result\":{\"address_type\":\"Sapling\",\"is_valid\":true,\"reason\":null}}";

		private synchronized void record(String kind, String arguments) {
			this.calls.add(new String[] { kind, arguments });
		}

		synchronized List<String> arguments(String kind) {
			List<String> out = new ArrayList<>();
			for (String[] call : this.calls)
				if (call[0].equals(kind))
					out.add(call[1]);
			return out;
		}

		synchronized int indexOf(String kind) {
			for (int i = 0; i < this.calls.size(); i++)
				if (this.calls.get(i)[0].equals(kind))
					return i;
			return -1;
		}

		synchronized String describe() {
			StringBuilder out = new StringBuilder();
			for (String[] call : this.calls)
				out.append(call[0]).append(' ');
			return out.toString();
		}

		@Override public boolean isLoaded() { return true; }
		@Override public void loadLibrary(Path path) { }
		@Override public void initLogging() { }
		@Override public String getSeedPhraseFromEntropyB64(String entropy64) { return null; }
		@Override public String getSeedPhraseFromEntropy(String entropy) { return null; }
		@Override public String configureStorage(String baseDirectory, String passphrase) { return null; }
		@Override public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) { return null; }
		@Override public String initFromB64(String serverUri, String params, String wallet64,
				String saplingOutput64, String saplingSpend64) { return null; }
		@Override public String save() { return null; }

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			JSONObject request = new JSONObject(requestJson);
			String method = request.getString("method");
			record("invokeJson:" + method, requestJson);
			return switch (method) {
				case "get_active_wallet" -> "{\"ok\":true,\"result\":\"wallet-1\"}";
				case "get_balance" -> this.balanceReply;
				case "validate_address" -> this.validateReply.apply(request.getString("address"));
				case "sync_status" -> "{\"ok\":true,\"result\":{\"target_height\":100}}";
				default -> "{\"ok\":false,\"error\":\"unexpected method " + method + "\"}";
			};
		}

		@Override
		public String execute(String command, String arguments) {
			record("execute:" + command, arguments);
			switch (command) {
				case "height": return "{\"height\":100}";
				case "info": return "{\"latest_block_height\":100}";
				case "syncStatus": return "{\"in_progress\":false,\"syncing\":false}";
				case "encryptionstatus": return "{\"encrypted\":false}";
				case "export": return "[{\"address\":\"" + INPUT_ADDRESS + "\"}]";
				case "send":
					if (this.sendThrows)
						throw new IllegalStateException("simulated JNI failure mid-send");
					return this.sendReply;
				case "sync": return "{\"result\":\"success\"}";
				default: return "{}";
			}
		}
	}
}
