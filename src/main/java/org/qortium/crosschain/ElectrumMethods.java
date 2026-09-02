package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.qortium.crypto.Crypto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the ElectrumX method family to use on one connection, and normalises its results.
 * <p>
 * ElectrumX protocol 1.7 replaced the <code>blockchain.scripthash.*</code> calls with
 * <code>blockchain.scriptpubkey.*</code>, which take the raw output script instead of its reversed SHA-256
 * and wrap their results in an object. Protocol 1.6 had already replaced <code>blockchain.relayfee</code>
 * with <code>mempool.get_info</code> and changed <code>blockchain.block.headers</code> from one concatenated
 * <code>hex</code> string to a <code>headers</code> array. Servers that speak the older protocols are still
 * the majority, so Core speaks all of them and picks per connection from the negotiated version.
 * <p>
 * Result normalisation is deliberately shape-driven rather than version-driven: the caller gets the same
 * internal types out of either family, and a server that answers in the other family's shape (or a proxy
 * that rewrites it) is still understood rather than rejected.
 *
 * @see <a href="https://electrumx.readthedocs.io/en/latest/protocol-changes.html">ElectrumX protocol changes</a>
 */
public final class ElectrumMethods {

	/** From 1.7, blockchain.scripthash.* is gone and blockchain.scriptpubkey.* takes its place. */
	static final ElectrumProtocolVersion SCRIPTPUBKEY_FROM = ElectrumProtocolVersion.of(1, 7);
	/** From 1.6, blockchain.relayfee is gone and mempool.get_info carries the relay fee. */
	static final ElectrumProtocolVersion MEMPOOL_INFO_FROM = ElectrumProtocolVersion.of(1, 6);
	/** From 1.6, blockchain.block.headers returns a headers array instead of one concatenated hex string. */
	static final ElectrumProtocolVersion HEADERS_ARRAY_FROM = ElectrumProtocolVersion.of(1, 6);
	/** Unsubscribing was only added in 1.4.2. */
	static final ElectrumProtocolVersion UNSUBSCRIBE_FROM = ElectrumProtocolVersion.of(1, 4, 2);

	/**
	 * Sanity ceiling on the number of entries in a history or unspent-output reply. Real addresses can be
	 * busy — one probe script has 16,426 unspent outputs on Namecoin — so this only has to be far above any
	 * legitimate answer; its job is to stop an unbounded or hostile reply from being walked at all.
	 */
	public static final int MAX_SCRIPT_RESULT_ENTRIES = 250_000;
	/** ElectrumX serves at most 2016 headers per request; allow a margin for chains that raise it. */
	public static final int MAX_BLOCK_HEADERS = 2016 * 4;
	/** Chunks in a 1.6 headers array. Headers can be far longer than 80 bytes, so allow generous chunking. */
	public static final int MAX_BLOCK_HEADER_CHUNKS = 200_000;
	private static final int MAX_BLOCK_HEADER_HEX_LENGTH = 16 * 1024;
	private static final int TX_HASH_HEX_LENGTH = 64;
	/**
	 * Lowest height a server may report. get_history uses 0 for an unconfirmed transaction and get_mempool
	 * uses -1 for one whose parents are also unconfirmed, so -1 is legitimate and must survive validation.
	 */
	private static final long MIN_REPORTED_HEIGHT = -1L;
	private static final long MAX_REPORTED_HEIGHT = Integer.MAX_VALUE;

	private static final String SCRIPTHASH_PREFIX = "blockchain.scripthash.";
	private static final String SCRIPTPUBKEY_PREFIX = "blockchain.scriptpubkey.";

	public static final String SCRIPTHASH_SUBSCRIBE = SCRIPTHASH_PREFIX + "subscribe";
	public static final String SCRIPTPUBKEY_SUBSCRIBE = SCRIPTPUBKEY_PREFIX + "subscribe";

	private final ElectrumProtocolVersion version;
	private final boolean scriptPubKeyFamily;

	private ElectrumMethods(ElectrumProtocolVersion version) {
		this.version = version;
		this.scriptPubKeyFamily = version != null && version.isAtOrAbove(SCRIPTPUBKEY_FROM);
	}

	/**
	 * @param version the version negotiated on this connection, or null when it is not known — an unknown
	 * version falls back to the long-standing scripthash family, which every server below 1.7 speaks.
	 */
	public static ElectrumMethods forVersion(ElectrumProtocolVersion version) {
		return new ElectrumMethods(version);
	}

	public ElectrumProtocolVersion getVersion() {
		return this.version;
	}

	/** @return true when this connection uses the 1.7 blockchain.scriptpubkey.* family */
	public boolean usesScriptPubKey() {
		return this.scriptPubKeyFamily;
	}

	/** @return true when blockchain.block.headers answers with a headers array rather than one hex string */
	public boolean usesBlockHeadersArray() {
		return this.version != null && this.version.isAtOrAbove(HEADERS_ARRAY_FROM);
	}

	/** @return true when this connection can unsubscribe; 1.4 and 1.4.1 cannot */
	public boolean supportsUnsubscribe() {
		return this.version == null || this.version.isAtOrAbove(UNSUBSCRIBE_FROM);
	}

	// --- requests ---

	public ElectrumRequest getBalance(byte[] script) {
		return scriptRequest("get_balance", script);
	}

	public ElectrumRequest getHistory(byte[] script) {
		return scriptRequest("get_history", script);
	}

	public ElectrumRequest getMempool(byte[] script) {
		return scriptRequest("get_mempool", script);
	}

	public ElectrumRequest listUnspent(byte[] script) {
		return scriptRequest("listunspent", script);
	}

	public ElectrumRequest subscribe(byte[] script) {
		return scriptRequest("subscribe", script);
	}

	public ElectrumRequest unsubscribe(byte[] script) {
		return scriptRequest("unsubscribe", script);
	}

	/**
	 * The relay fee, in whole coins per kB.
	 * <p>
	 * 1.6 removed blockchain.relayfee; mempool.get_info carries the same number as
	 * <code>minrelaytxfee</code>.
	 */
	public ElectrumRequest relayFee() {
		return this.version != null && this.version.isAtOrAbove(MEMPOOL_INFO_FROM)
				? new ElectrumRequest("mempool.get_info")
				: new ElectrumRequest("blockchain.relayfee");
	}

	private ElectrumRequest scriptRequest(String call, byte[] script) {
		return this.scriptPubKeyFamily
				? new ElectrumRequest(SCRIPTPUBKEY_PREFIX + call, scriptPubKeyHex(script))
				: new ElectrumRequest(SCRIPTHASH_PREFIX + call, scriptHash(script));
	}

	/**
	 * The key a subscription notification is delivered under.
	 * <p>
	 * ElectrumX renamed the notification method to blockchain.scriptpubkey.subscribe at 1.7 but did not
	 * change its payload: the first parameter is still the scripthash, because the server only ever stores
	 * the scripthash it derived from the script it was given. Subscriptions are therefore tracked by
	 * scripthash at every protocol version.
	 */
	public static String subscriptionKey(byte[] script) {
		return scriptHash(script);
	}

	/** @return true when this method name is a scripthash or scriptpubkey subscription notification */
	public static boolean isSubscriptionNotification(String method) {
		return SCRIPTHASH_SUBSCRIBE.equals(method) || SCRIPTPUBKEY_SUBSCRIBE.equals(method);
	}

	/** The 1.4-family parameter: SHA-256 of the output script, byte-reversed, as hex. */
	public static String scriptHash(byte[] script) {
		byte[] scriptHash = Crypto.digest(script);
		Bytes.reverse(scriptHash);
		return HashCode.fromBytes(scriptHash).toString();
	}

	/** The 1.7-family parameter: the raw output script as hex. */
	public static String scriptPubKeyHex(byte[] script) {
		return HashCode.fromBytes(script).toString();
	}

	// --- result normalisation ---

	/**
	 * Normalise a history or mempool result to the array both families ultimately carry, rejecting any
	 * reply whose entries are not well formed.
	 * <p>
	 * Validation is not cosmetic: callers walk these entries with unchecked casts, and an empty result here
	 * is turned into a server-attributed network failure, which evicts the server and retries elsewhere.
	 * A reply that says the right thing in the wrong shape is a broken server, not usable data.
	 *
	 * @return the history entries, or empty when the response is not a well-formed history
	 */
	public static Optional<JSONArray> normalizeHistory(Object response) {
		return unwrapArray(response, "history").filter(ElectrumMethods::isValidHistory);
	}

	/**
	 * Normalise an unspent-output result to the array both families ultimately carry, rejecting any reply
	 * whose entries are not well formed.
	 *
	 * @return the unspent outputs, or empty when the response is not a well-formed unspent-output list
	 */
	public static Optional<JSONArray> normalizeUnspentOutputs(Object response) {
		return unwrapArray(response, "utxos").filter(ElectrumMethods::isValidUnspentOutputs);
	}

	/** get_balance answers with the same object in both families; its amounts still have to make sense. */
	public static Optional<JSONObject> normalizeBalance(Object response) {
		if (!(response instanceof JSONObject))
			return Optional.empty();

		JSONObject balance = (JSONObject) response;

		Long confirmed = integerValue(balance.get("confirmed"));
		if (confirmed == null || confirmed < 0L)
			return Optional.empty();

		// The mempool delta is only validated when the server sends it: Core reads the confirmed balance
		// and nothing else, so refusing a server for omitting a field we never look at would be gratuitous.
		// When it is present it must still be an integer, though it is legitimately negative when
		// unconfirmed coins are being spent.
		Object unconfirmed = balance.get("unconfirmed");
		if (unconfirmed != null && integerValue(unconfirmed) == null)
			return Optional.empty();

		return Optional.of(balance);
	}

	private static boolean isValidHistory(JSONArray history) {
		if (history.size() > MAX_SCRIPT_RESULT_ENTRIES)
			return false;

		for (Object entry : history) {
			if (!(entry instanceof JSONObject))
				return false;

			JSONObject historyEntry = (JSONObject) entry;
			if (!isTransactionHash(historyEntry.get("tx_hash")) || !isReportedHeight(historyEntry.get("height")))
				return false;
		}

		return true;
	}

	private static boolean isValidUnspentOutputs(JSONArray unspentOutputs) {
		if (unspentOutputs.size() > MAX_SCRIPT_RESULT_ENTRIES)
			return false;

		for (Object entry : unspentOutputs) {
			if (!(entry instanceof JSONObject))
				return false;

			JSONObject unspentOutput = (JSONObject) entry;
			if (!isTransactionHash(unspentOutput.get("tx_hash")) || !isReportedHeight(unspentOutput.get("height")))
				return false;

			Long outputIndex = integerValue(unspentOutput.get("tx_pos"));
			if (outputIndex == null || outputIndex < 0L || outputIndex > Integer.MAX_VALUE)
				return false;

			Long value = integerValue(unspentOutput.get("value"));
			if (value == null || value < 0L)
				return false;
		}

		return true;
	}

	private static boolean isTransactionHash(Object value) {
		return value instanceof String && isHex((String) value, TX_HASH_HEX_LENGTH);
	}

	private static boolean isReportedHeight(Object value) {
		Long height = integerValue(value);
		return height != null && height >= MIN_REPORTED_HEIGHT && height <= MAX_REPORTED_HEIGHT;
	}

	/**
	 * @return the value as a long when it is a whole number that fits in one.
	 * <p>
	 * Any integral {@link Number} is accepted, because a JSON integer does not always arrive as a Long:
	 * json-simple parses them as Long but other producers use Integer or BigInteger. What is rejected is a
	 * value that is not a whole number, or one too large to hold — json-simple falls back to Double once an
	 * integer no longer fits, so an overflowing amount or height is caught by the range check.
	 */
	private static Long integerValue(Object value) {
		if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
			return ((Number) value).longValue();

		if (value instanceof BigInteger) {
			BigInteger bigInteger = (BigInteger) value;
			return bigInteger.bitLength() < Long.SIZE ? bigInteger.longValue() : null;
		}

		if (value instanceof Number) {
			double numeric = ((Number) value).doubleValue();
			if (Double.isFinite(numeric) && numeric == Math.rint(numeric)
					&& numeric >= -9.223372036854775E18d && numeric <= 9.223372036854775E18d)
				return (long) numeric;
		}

		return null;
	}

	/**
	 * The header count, which some servers send as a numeric string rather than a JSON number. Core has
	 * always tolerated that, and a server sending "2" instead of 2 is not a server worth refusing.
	 */
	private static Long headerCountValue(Object value) {
		if (value instanceof String)
			try {
				return Long.parseLong(((String) value).trim());
			} catch (NumberFormatException e) {
				return null;
			}

		return integerValue(value);
	}

	private static boolean isHex(String value, int expectedLength) {
		if (value.length() != expectedLength)
			return false;

		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			boolean isHexDigit = (character >= '0' && character <= '9')
					|| (character >= 'a' && character <= 'f')
					|| (character >= 'A' && character <= 'F');
			if (!isHexDigit)
				return false;
		}

		return true;
	}

	/**
	 * Normalise a blockchain.block.headers result.
	 * <p>
	 * Below 1.6 the headers arrive as one concatenated <code>hex</code> string; from 1.6 the same bytes
	 * arrive as a <code>headers</code> array of fixed-size chunks. Neither shape can be split into block
	 * headers here, because header length varies by chain — Namecoin's AuxPoW headers and Firo's 120-byte
	 * headers are not the canonical 80 bytes — so both are handed to the caller for the chain-specific
	 * splitter to divide. <code>count</code> is the number of block headers, not the number of chunks.
	 */
	public static Optional<BlockHeadersResult> normalizeBlockHeaders(Object response) {
		if (!(response instanceof JSONObject))
			return Optional.empty();

		JSONObject headersJson = (JSONObject) response;

		Long reportedCount = headerCountValue(headersJson.get("count"));
		if (reportedCount == null || reportedCount < 0L || reportedCount > MAX_BLOCK_HEADERS)
			return Optional.empty();

		int count = reportedCount.intValue();

		Object headersObj = headersJson.get("headers");
		if (headersObj instanceof JSONArray) {
			// Verified live against Namecoin (AuxPoW) and Firo: the 1.6 'headers' array is the same bytes
			// the older 'hex' string carried, chunked at a fixed size that has nothing to do with 'count'.
			// A Namecoin reply for 2 headers arrives as 18 chunks. Treat the array as chunks, never as a
			// list of block headers, and never require its length to match 'count'.
			JSONArray headersArray = (JSONArray) headersObj;
			if (headersArray.size() > MAX_BLOCK_HEADER_CHUNKS)
				return Optional.empty();

			List<String> headerHexes = new ArrayList<>(headersArray.size());
			for (Object headerHex : headersArray) {
				if (!isBlockHeaderHex(headerHex))
					return Optional.empty();

				headerHexes.add((String) headerHex);
			}

			return Optional.of(BlockHeadersResult.ofHeaders(count, headerHexes));
		}

		Object hexObj = headersJson.get("hex");
		if (isBlockHeaderHex(hexObj) || (count == 0 && "".equals(hexObj)))
			return Optional.of(BlockHeadersResult.ofConcatenatedHex(count, (String) hexObj));

		return Optional.empty();
	}

	/**
	 * The relay fee in whole coins per kB, from either blockchain.relayfee (a bare number) or
	 * mempool.get_info (an object whose <code>minrelaytxfee</code> carries it).
	 */
	public static Optional<Double> normalizeRelayFee(Object response) {
		if (response instanceof Number)
			return validRelayFee(((Number) response).doubleValue());

		if (response instanceof JSONObject) {
			Object minRelayFee = ((JSONObject) response).get("minrelaytxfee");
			if (minRelayFee instanceof Number)
				return validRelayFee(((Number) minRelayFee).doubleValue());
		}

		return Optional.empty();
	}

	private static Optional<Double> validRelayFee(double relayFee) {
		return Double.isFinite(relayFee) && relayFee >= 0.0d ? Optional.of(relayFee) : Optional.empty();
	}

	private static boolean isBlockHeaderHex(Object value) {
		if (!(value instanceof String))
			return false;

		String headerHex = (String) value;
		return !headerHex.isEmpty() && headerHex.length() <= MAX_BLOCK_HEADER_HEX_LENGTH
				&& (headerHex.length() & 1) == 0 && isHex(headerHex, headerHex.length());
	}

	private static Optional<JSONArray> unwrapArray(Object response, String key) {
		if (response instanceof JSONArray)
			return Optional.of((JSONArray) response);

		if (response instanceof JSONObject) {
			Object wrapped = ((JSONObject) response).get(key);
			if (wrapped instanceof JSONArray)
				return Optional.of((JSONArray) wrapped);
		}

		return Optional.empty();
	}

	/** A blockchain.block.headers result in whichever shape the server sent. */
	public static final class BlockHeadersResult {
		private final int count;
		private final String concatenatedHex;
		private final List<String> headerHexes;

		private BlockHeadersResult(int count, String concatenatedHex, List<String> headerHexes) {
			this.count = count;
			this.concatenatedHex = concatenatedHex;
			this.headerHexes = headerHexes;
		}

		static BlockHeadersResult ofConcatenatedHex(int count, String concatenatedHex) {
			return new BlockHeadersResult(count, concatenatedHex, null);
		}

		static BlockHeadersResult ofHeaders(int count, List<String> headerHexes) {
			return new BlockHeadersResult(count, null, List.copyOf(headerHexes));
		}

		/** The number of headers the server says it returned. */
		public int getCount() {
			return this.count;
		}

		/** @return true when the server returned individually split headers (protocol 1.6 and above) */
		public boolean isSplit() {
			return this.headerHexes != null;
		}

		/** The already-split header hex strings, or null when the server concatenated them. */
		public List<String> getHeaderHexes() {
			return this.headerHexes;
		}

		/** The concatenated header hex, or null when the server already split them. */
		public String getConcatenatedHex() {
			return this.concatenatedHex;
		}
	}
}
