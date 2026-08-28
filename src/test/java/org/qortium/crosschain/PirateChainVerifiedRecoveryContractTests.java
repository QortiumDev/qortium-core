package org.qortium.crosschain;

import org.json.JSONObject;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PirateChainVerifiedRecoveryContractTests {

	private static final String SPENDING_KEY = "secret-extended-key-main1testvector";

	private static PirateChainVerifiedRecoveryRequest buildRequest() {
		PirateChainVerifiedRecoveryRequest request = new PirateChainVerifiedRecoveryRequest();
		request.entropy58 = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV";
		request.pool = "sapling";
		request.spendingKey = SPENDING_KEY;
		request.expectedAddress = "zs1expectedaddress";
		request.addressIndex = 7;
		request.birthdayHeight = 2_000_000;
		request.label = "Recovered wallet";
		return request;
	}

	@Test
	public void testImportPayloadMatchesUpstreamRequestContract() {
		JSONObject payload = PirateWallet.buildVerifiedImportPayload("wallet-1", buildRequest());

		assertEquals("import_spending_key_verified", payload.getString("method"));
		assertEquals("wallet-1", payload.getString("wallet_id"));
		assertEquals("sapling", payload.getString("pool"));
		assertEquals(SPENDING_KEY, payload.getString("spending_key"));
		assertEquals("zs1expectedaddress", payload.getString("expected_address"));
		assertEquals(7, payload.getInt("address_index"));
		assertEquals(2_000_000, payload.getInt("birthday_height"));
		assertEquals("Recovered wallet", payload.getString("label"));
		assertEquals(8, payload.keySet().size());
	}

	@Test
	public void testImportPayloadOmitsAbsentLabel() {
		PirateChainVerifiedRecoveryRequest request = buildRequest();
		request.label = null;

		JSONObject payload = PirateWallet.buildVerifiedImportPayload("wallet-1", request);
		assertFalse(payload.has("label"));
		assertEquals(7, payload.keySet().size());
	}

	@Test
	public void testSuccessResultParsesAllEightFieldsIncludingNullFloor() throws Exception {
		PirateChainVerifiedRecoveryResult pending = PirateWallet.parseVerifiedImportResult(
				"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1canonical\","
						+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":false,"
						+ "\"rescan_required\":true,\"required_rescan_from_height\":1999000}}",
				SPENDING_KEY);
		assertEquals(42L, pending.keyId);
		assertEquals("sapling", pending.pool);
		assertEquals("zs1canonical", pending.address);
		assertEquals(7, pending.addressIndex);
		assertEquals(1_999_000, pending.birthdayHeight);
		assertFalse(pending.alreadyImported);
		assertTrue(pending.rescanRequired);
		assertEquals(Long.valueOf(1_999_000L), pending.requiredRescanFromHeight);

		PirateChainVerifiedRecoveryResult completed = PirateWallet.parseVerifiedImportResult(
				"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"ironwood\",\"address\":\"zs1canonical\","
						+ "\"address_index\":0,\"birthday_height\":1999000,\"already_imported\":true,"
						+ "\"rescan_required\":false,\"required_rescan_from_height\":null}}",
				SPENDING_KEY);
		assertTrue(completed.alreadyImported);
		assertFalse(completed.rescanRequired);
		assertNull(completed.requiredRescanFromHeight);
	}

	@Test
	public void testNativeErrorStringsPassThroughSanitized() {
		ForeignBlockchainException exception = assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":false,\"error\":\"Expected address is not controlled by the spending key\"}",
						SPENDING_KEY));
		assertEquals("Expected address is not controlled by the spending key", exception.getMessage());
	}

	@Test
	public void testErrorContainingKeyMaterialIsRedacted() {
		ForeignBlockchainException exception = assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":false,\"error\":\"bad key " + SPENDING_KEY + "\"}", SPENDING_KEY));
		assertEquals("Verified key import failed", exception.getMessage());
		assertFalse(exception.getMessage().contains(SPENDING_KEY));

		assertEquals("Verified key import failed",
				PirateWallet.sanitizeVerifiedImportError("upper " + SPENDING_KEY.toUpperCase(), SPENDING_KEY));
		assertEquals("Verified key import failed", PirateWallet.sanitizeVerifiedImportError(null, SPENDING_KEY));
		assertEquals("Verified key import failed", PirateWallet.sanitizeVerifiedImportError("  ", SPENDING_KEY));
		assertEquals("plain failure", PirateWallet.sanitizeVerifiedImportError("plain failure", SPENDING_KEY));
	}

	@Test
	public void testIncompleteOrMalformedResultsFailClosed() {
		// Missing ok
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult("{\"result\":{}}", SPENDING_KEY));
		// ok without result
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult("{\"ok\":true}", SPENDING_KEY));
		// Missing required result field
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":false}}",
						SPENDING_KEY));
		// Null required result field
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":null,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true}}",
						SPENDING_KEY));
		// Not JSON at all (e.g. a bare JNI error string)
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult("Error: string input", SPENDING_KEY));
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(null, SPENDING_KEY));
	}

	@Test
	public void testCoercibleScalarTypesAreRejected() {
		// A result missing only required_rescan_from_height is incomplete: JSONObject.isNull
		// cannot distinguish an absent property from an explicit null, so presence is required.
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true}}",
						SPENDING_KEY));

		// A string "true" envelope flag must not be coerced into success
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":\"true\",\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));

		// String booleans and decimal indexes must not be coerced either
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":1999000,\"already_imported\":\"false\","
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7.9,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));

		// Out-of-range values fail closed even with correct types
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":4097,\"birthday_height\":1999000,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":0,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));

		// A u32-range birthday above Integer.MAX_VALUE must fail closed, not wrap negative
		assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseVerifiedImportResult(
						"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
								+ "\"address_index\":7,\"birthday_height\":4294967295,\"already_imported\":false,"
								+ "\"rescan_required\":true,\"required_rescan_from_height\":null}}",
						SPENDING_KEY));
	}

	@Test
	public void testBirthdayBoundaryAtIntegerMaxIsAccepted() throws Exception {
		PirateChainVerifiedRecoveryResult result = PirateWallet.parseVerifiedImportResult(
				"{\"ok\":true,\"result\":{\"key_id\":42,\"pool\":\"sapling\",\"address\":\"zs1\","
						+ "\"address_index\":7,\"birthday_height\":2147483647,\"already_imported\":false,"
						+ "\"rescan_required\":true,\"required_rescan_from_height\":2147483647}}",
				SPENDING_KEY);
		assertEquals(Integer.MAX_VALUE, result.birthdayHeight);
		assertEquals(Long.valueOf(Integer.MAX_VALUE), result.requiredRescanFromHeight);
	}

	@Test
	public void testTypedBalancePayloadMatchesUpstreamRequestContract() {
		JSONObject payload = PirateWallet.buildBalancePayload("wallet-1");

		assertEquals("get_balance", payload.getString("method"));
		assertEquals("wallet-1", payload.getString("wallet_id"));
		assertEquals(2, payload.keySet().size());
	}

	@Test
	public void testTypedBalanceParsesUpstreamStringAmounts() throws Exception {
		// Unified serializes amounts as decimal strings.
		PirateChainBalance balance = PirateWallet.parseTypedBalance(
				"{\"ok\":true,\"result\":{\"total\":\"123456789\",\"spendable\":\"120000000\","
						+ "\"pending\":\"3456789\"}}");
		assertEquals(123456789L, balance.zbalance);
		assertEquals(120000000L, balance.verified_zbalance);

		// Plain integers are accepted too, as the upstream decoder accepts either form.
		PirateChainBalance numeric = PirateWallet.parseTypedBalance(
				"{\"ok\":true,\"result\":{\"total\":10,\"spendable\":4,\"pending\":6}}");
		assertEquals(10L, numeric.zbalance);
		assertEquals(4L, numeric.verified_zbalance);
	}

	@Test
	public void testTypedBalanceFailsClosedOnMalformedResponses() {
		for (String malformed : new String[] {
				// not an envelope success
				"{\"ok\":false,\"error\":\"nope\"}",
				"{\"ok\":\"true\",\"result\":{\"total\":\"1\",\"spendable\":\"1\"}}",
				// missing result or fields
				"{\"ok\":true}",
				"{\"ok\":true,\"result\":{\"total\":\"1\"}}",
				// wrong scalar shapes and impossible values
				"{\"ok\":true,\"result\":{\"total\":{},\"spendable\":\"1\"}}",
				"{\"ok\":true,\"result\":{\"total\":\"not-a-number\",\"spendable\":\"1\"}}",
				"{\"ok\":true,\"result\":{\"total\":1.5,\"spendable\":1}}",
				"{\"ok\":true,\"result\":{\"total\":\"-1\",\"spendable\":\"1\"}}",
				// not JSON at all
				"Error: string input" }) {
			assertThrows("should fail closed: " + malformed, ForeignBlockchainException.class,
					() -> PirateWallet.parseTypedBalance(malformed));
		}
		assertThrows(ForeignBlockchainException.class, () -> PirateWallet.parseTypedBalance(null));
	}
}
