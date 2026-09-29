package org.qortium.crosschain;

import org.bitcoinj.base.Bech32;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Address, memo, funds and native-response vectors for the ARRR send contract (protocol version 1).
 * The Sapling vectors are built in-test from a fixed 43-byte payload so no real address is needed.
 */
public class PirateChainSendValidationTests {

	private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

	private static byte[] payload(int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++)
			bytes[i] = (byte) (i * 7 + 3);
		return bytes;
	}

	private static final String VALID = Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", payload(43));

	@BeforeClass
	public static void beforeClass() throws DataException {
		// parseSendResponse consults Settings (debug logging flag).
		Common.useDefaultSettings();
	}

	// --- minimal Bech32 encoder over raw 5-bit groups, to craft a nonzero-padding vector ---

	private static int polymod(int[] values) {
		int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
		int chk = 1;
		for (int v : values) {
			int b = chk >>> 25;
			chk = ((chk & 0x1ffffff) << 5) ^ v;
			for (int i = 0; i < 5; i++)
				if (((b >>> i) & 1) == 1)
					chk ^= gen[i];
		}
		return chk;
	}

	private static String encodeRaw(String hrp, int[] data5, int constant) {
		int[] values = new int[hrp.length() * 2 + 1 + data5.length + 6];
		int k = 0;
		for (char c : hrp.toCharArray())
			values[k++] = c >>> 5;
		values[k++] = 0;
		for (char c : hrp.toCharArray())
			values[k++] = c & 31;
		for (int d : data5)
			values[k++] = d;
		int mod = polymod(values) ^ constant;
		StringBuilder out = new StringBuilder(hrp).append('1');
		for (int d : data5)
			out.append(CHARSET.charAt(d));
		for (int i = 0; i < 6; i++)
			out.append(CHARSET.charAt((mod >>> (5 * (5 - i))) & 31));
		return out.toString();
	}

	private static int[] toGroups5(byte[] bytes) {
		int acc = 0, bits = 0, n = 0;
		int[] out = new int[(bytes.length * 8 + 4) / 5];
		for (byte b : bytes) {
			acc = (acc << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				bits -= 5;
				out[n++] = (acc >>> bits) & 31;
			}
		}
		if (bits > 0)
			out[n++] = (acc << (5 - bits)) & 31;
		return out;
	}

	@Test
	public void testVectorConstructionMatchesBitcoinj() {
		assertEquals(78, VALID.length());
		assertEquals(VALID, encodeRaw("zs", toGroups5(payload(43)), 1));
		assertTrue(PirateChain.isCanonicalSaplingAddress(VALID));
	}

	@Test
	public void testCanonicalSaplingAddressRejectsEveryNonCanonicalForm() {
		assertFalse("null", PirateChain.isCanonicalSaplingAddress(null));
		assertFalse("empty", PirateChain.isCanonicalSaplingAddress(""));
		assertFalse("uppercase", PirateChain.isCanonicalSaplingAddress(VALID.toUpperCase()));
		assertFalse("mixed case", PirateChain.isCanonicalSaplingAddress(
				"Zs" + VALID.substring(2)));
		assertFalse("mixed case in data", PirateChain.isCanonicalSaplingAddress(
				VALID.substring(0, 40) + Character.toUpperCase(VALID.charAt(40)) + VALID.substring(41)));
		assertFalse("bech32m", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "zs", payload(43))));

		// Corrupted checksum: replace the last character with a different valid Bech32 character.
		char last = VALID.charAt(VALID.length() - 1);
		char other = CHARSET.charAt((CHARSET.indexOf(last) + 1) % CHARSET.length());
		assertFalse("checksum", PirateChain.isCanonicalSaplingAddress(VALID.substring(0, 77) + other));
		// Corrupted data with a matching-length string.
		char d = VALID.charAt(10);
		char d2 = CHARSET.charAt((CHARSET.indexOf(d) + 1) % CHARSET.length());
		assertFalse("data bit flip", PirateChain.isCanonicalSaplingAddress(
				VALID.substring(0, 10) + d2 + VALID.substring(11)));

		assertFalse("hrp ztestsapling", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "ztestsapling", payload(43))));
		assertFalse("hrp zz (same length)", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "zz", payload(43))));
		assertFalse("hrp pirate (ironwood)", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "pirate", payload(43))));
		assertFalse("hrp u (unified)", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "u", payload(43))));
		assertFalse("transparent t1", PirateChain.isCanonicalSaplingAddress("t1KhEjLJv3nMxUQcPqHi3xYjXwbNdq3UzC3"));

		assertFalse("42-byte payload", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", payload(42))));
		assertFalse("44-byte payload", PirateChain.isCanonicalSaplingAddress(
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", payload(44))));

		// 43 bytes = 344 bits = 69 groups with exactly one zero padding bit. Setting that bit yields
		// a 78-character string with a valid checksum whose padding is non-zero: must be rejected.
		int[] groups = toGroups5(payload(43));
		assertEquals(69, groups.length);
		int[] badPadding = groups.clone();
		badPadding[68] |= 1;
		String nonZeroPadding = encodeRaw("zs", badPadding, 1);
		assertEquals(78, nonZeroPadding.length());
		assertFalse("non-zero padding", PirateChain.isCanonicalSaplingAddress(nonZeroPadding));

		assertFalse("invalid character 'b'", PirateChain.isCanonicalSaplingAddress(VALID.substring(0, 20) + "b" + VALID.substring(21)));
		assertFalse("separator only", PirateChain.isCanonicalSaplingAddress("zs1"));
		assertFalse("leading whitespace", PirateChain.isCanonicalSaplingAddress(" " + VALID.substring(1)));
		assertFalse("trailing whitespace", PirateChain.isCanonicalSaplingAddress(VALID + " "));
		assertFalse("too long", PirateChain.isCanonicalSaplingAddress(VALID + "q"));
	}

	@Test
	public void testMemoValidationVectors() {
		assertNull(PirateChain.validateMemo(null));
		assertNull(PirateChain.validateMemo(""));
		assertNull(PirateChain.validateMemo("hello"));
		assertNull(PirateChain.validateMemo("line1\tcol\r\nline2\n"));
		assertNull(PirateChain.validateMemo("  leading and trailing spaces  "));
		assertNull(PirateChain.validateMemo("😀")); // well-formed surrogate pair (4 bytes)
		assertNull(PirateChain.validateMemo("x".repeat(512)));
		assertNull(PirateChain.validateMemo("€".repeat(170))); // 510 bytes
		assertNull(PirateChain.validateMemo("😀".repeat(128))); // exactly 512 bytes

		assertEquals("MEMO_TOO_LONG", PirateChain.validateMemo("x".repeat(513)));
		assertEquals("MEMO_TOO_LONG", PirateChain.validateMemo("€".repeat(171))); // 513 bytes
		assertEquals("MEMO_TOO_LONG", PirateChain.validateMemo("😀".repeat(129)));

		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("a\u0000b"));
		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("\u001b[31mred"));
		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("del\u007f"));
		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("c1\u0085next"));
		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("\u000b")); // vertical tab
		assertEquals("MEMO_CONTROL_CHARACTER", PirateChain.validateMemo("\u000c")); // form feed

		assertEquals("MEMO_LONE_SURROGATE", PirateChain.validateMemo("\uD83D"));
		assertEquals("MEMO_LONE_SURROGATE", PirateChain.validateMemo("\uDE00"));
		assertEquals("MEMO_LONE_SURROGATE", PirateChain.validateMemo("a\uD83Db"));
		assertEquals("MEMO_LONE_SURROGATE", PirateChain.validateMemo("\uDE00\uD83D")); // reversed pair
		// A lone surrogate hidden inside otherwise-valid content is still rejected, never replaced.
		assertEquals("MEMO_LONE_SURROGATE", PirateChain.validateMemo("😀\uD83D"));
	}

	@Test
	public void testVerifiedFundsCheckCoversAmountPlusFeeWithoutOverflow() throws Exception {
		long fee = PirateChain.getSendFeeAtomic();
		assertEquals(10_000L, fee);

		// Exactly amount + fee is enough; one atomic unit less is not.
		PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 500_000L), 500_000L - fee);
		ForeignBlockchainException.InsufficientFundsException insufficient = assertThrows(
				ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 500_000L),
						500_000L - fee + 1));
		assertEquals(PirateChain.INSUFFICIENT_VERIFIED_FUNDS_REASON, insufficient.getMessage());

		// Only the VERIFIED figure counts: funds within the total but above the verified balance fail.
		assertThrows(ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 500_000L), 600_000L));

		// amount + fee overflow must be a typed insufficient-funds failure, never an ArithmeticException.
		assertThrows(ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(Long.MAX_VALUE, Long.MAX_VALUE),
						Long.MAX_VALUE));
		assertThrows(ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(Long.MAX_VALUE, Long.MAX_VALUE),
						Long.MAX_VALUE - fee + 1));
		// ...while the largest amount that does not overflow is accepted against a matching balance.
		PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE - fee);

		// Non-positive amounts never pass the funds gate even with a huge balance.
		assertThrows(ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 1_000_000L), 0L));
		assertThrows(ForeignBlockchainException.InsufficientFundsException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 1_000_000L), -1L));
	}

	@Test
	public void testUnknownVerifiedBalanceIsNotReadyRatherThanInsufficientOrTrusted() {
		// A legacy-style reading with an unknown verified figure carries the total as a placeholder;
		// a send must not treat that placeholder as spendable, nor call it "insufficient".
		ForeignBlockchainException.WalletNotReadyException notReady = assertThrows(
				ForeignBlockchainException.WalletNotReadyException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(new PirateChainBalance(1_000_000L, 1_000_000L, false), 1L));
		assertEquals(PirateChain.VERIFIED_BALANCE_UNKNOWN_REASON, notReady.getMessage());
		assertThrows(ForeignBlockchainException.WalletNotReadyException.class,
				() -> PirateChain.assertSufficientVerifiedFunds(null, 1L));
	}

	@Test
	public void testNativeSendResponseIsReducedToStableReasons() throws Exception {
		String txid = "ab".repeat(32);
		assertEquals(txid, PirateChain.parseSendResponse("{\"txid\":\"" + txid.toUpperCase(java.util.Locale.ROOT) + "\"}"));
		for (String invalid : new String[] { "a".repeat(63), "a".repeat(65), "g".repeat(64) })
			assertThrows(ForeignBlockchainException.SendOutcomeUnknownException.class,
					() -> PirateChain.parseSendResponse("{\"txid\":\"" + invalid + "\"}"));
		assertEquals(txid, PirateChain.parseSendResponse("{\"txid\":\"" + txid + "\"}"));
		for (String reply : new String[] { "{\"error\":\"Insufficient funds\"}",
				"{\"error\":\"Invalid recipient\"}", "{\"error\":\"transport failed\"}",
				"{\"txid\":\"abc123\"}", "{\"txid\":\"" + txid + "\",\"error\":\"ambiguous\"}" }) {
			ForeignBlockchainException.SendOutcomeUnknownException error = assertThrows(
					ForeignBlockchainException.SendOutcomeUnknownException.class, () -> PirateChain.parseSendResponse(reply));
			assertEquals(PirateChain.SEND_OUTCOME_UNKNOWN_REASON, error.getMessage());
		}
	}

	@Test
	public void testNativeSendReplyWithoutTxidOrErrorIsAnUnknownOutcomeNotACleanFailure() {
		// The native wallet broadcasts before it answers: a reply that is neither a txid nor an
		// explicit error proves nothing about whether the payment went out, so it must never be
		// reported as a definitive failure a client could safely retry.
		for (String reply : new String[] { null, "", "not json", "{}", "{\"txid\":\"\"}", "{\"txid\":null}",
				"{\"result\":\"success\"}", "{\"error\":null}", "[]", "\"txid\"" }) {
			ForeignBlockchainException.SendOutcomeUnknownException unknown = assertThrows(
					"reply: " + reply, ForeignBlockchainException.SendOutcomeUnknownException.class,
					() -> PirateChain.parseSendResponse(reply));
			assertEquals(PirateChain.SEND_OUTCOME_UNKNOWN_REASON, unknown.getMessage());
		}
	}

	@Test
	public void testNativeValidateAddressReplyInterpretation() throws Exception {
		// Observed v1.2.4 replies (probe against the bundled library, no wallet):
		assertTrue(PirateChain.parseValidateAddressResponse(
				"{\"ok\":true,\"result\":{\"address_type\":\"Sapling\",\"is_valid\":true,\"reason\":null}}"));
		assertFalse(PirateChain.parseValidateAddressResponse(
				"{\"ok\":true,\"result\":{\"address_type\":null,\"is_valid\":false,\"reason\":"
						+ "\"Invalid shielded address. Supported formats start with \\\"zs1\\\" or \\\"pirate1\\\".\"}}"));
		// A valid address of another pool is still not an acceptable Sapling recipient.
		assertFalse(PirateChain.parseValidateAddressResponse(
				"{\"ok\":true,\"result\":{\"address_type\":\"Ironwood\",\"is_valid\":true,\"reason\":null}}"));
		assertFalse(PirateChain.parseValidateAddressResponse(
				"{\"ok\":true,\"result\":{\"is_valid\":true}}"));
		assertFalse(PirateChain.parseValidateAddressResponse(
				"{\"ok\":true,\"result\":{\"address_type\":\"Sapling\",\"is_valid\":\"true\"}}"));

		// Not an answer at all: fail closed as "cannot validate right now", never as "valid".
		for (String reply : new String[] { null, "", "nope", "{\"ok\":false,\"error\":\"Invalid request JSON\"}",
				"{\"ok\":true}", "{\"ok\":\"true\",\"result\":{\"is_valid\":true,\"address_type\":\"Sapling\"}}" }) {
			ForeignBlockchainException.WalletNotReadyException notReady = assertThrows("reply: " + reply,
					ForeignBlockchainException.WalletNotReadyException.class,
					() -> PirateChain.parseValidateAddressResponse(reply));
			assertEquals(PirateChain.RECIPIENT_VALIDATION_UNAVAILABLE_REASON, notReady.getMessage());
		}
	}

	@Test
	public void testUnifiedBalanceWithoutSpendableIsTypedUnavailableNotGenericFailure() throws Exception {
		for (String reply : new String[] { "{\"ok\":true,\"result\":{\"total\":\"500000000\"}}",
				"{\"ok\":true,\"result\":{\"total\":\"500000000\",\"spendable\":null}}",
				"{\"ok\":true,\"result\":{\"total\":500,\"pending\":\"1\"}}" }) {
			PirateChainBalance balance = PirateWallet.parseTypedBalance(reply);
			assertFalse(balance.verifiedBalanceKnown);
			assertTrue(balance.zbalance > 0);
			assertThrows(ForeignBlockchainException.WalletNotReadyException.class,
					() -> PirateChain.assertSufficientVerifiedFunds(balance, 1));
		}
		// A malformed spendable value is still a generic fail-closed failure, not "unavailable".
		ForeignBlockchainException malformed = assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseTypedBalance("{\"ok\":true,\"result\":{\"total\":\"1\",\"spendable\":\"x\"}}"));
		assertFalse(malformed instanceof ForeignBlockchainException.BalanceUnavailableException);
		// And a missing total is still malformed.
		ForeignBlockchainException noTotal = assertThrows(ForeignBlockchainException.class,
				() -> PirateWallet.parseTypedBalance("{\"ok\":true,\"result\":{\"spendable\":\"1\"}}"));
		assertFalse(noTotal instanceof ForeignBlockchainException.BalanceUnavailableException);
	}

	@Test
	public void testSendPayloadCarriesFixedFeeAndAtomicAmount() {
		org.json.JSONObject payload = PirateChain.buildSendPayload("zs-input", VALID, 150_000_000L, "note");
		assertEquals("zs-input", payload.getString("input"));
		assertEquals(10_000L, payload.getLong("fee"));
		org.json.JSONObject output = payload.getJSONArray("output").getJSONObject(0);
		assertEquals(VALID, output.getString("address"));
		assertEquals(150_000_000L, output.getLong("amount"));
		assertEquals("note", output.getString("memo"));
		assertEquals(4, "note".getBytes(StandardCharsets.UTF_8).length);
		assertTrue(Arrays.equals(payload(43), Bech32.decodeBytes(VALID, "zs", Bech32.Encoding.BECH32)));
	}
}
