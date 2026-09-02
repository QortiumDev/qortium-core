package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.qortium.crypto.Crypto;

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
	 * Normalise a history or mempool result to the array both families ultimately carry.
	 *
	 * @return the history entries, or empty when the response is neither shape
	 */
	public static Optional<JSONArray> normalizeHistory(Object response) {
		return unwrapArray(response, "history");
	}

	/**
	 * Normalise an unspent-output result to the array both families ultimately carry.
	 *
	 * @return the unspent outputs, or empty when the response is neither shape
	 */
	public static Optional<JSONArray> normalizeUnspentOutputs(Object response) {
		return unwrapArray(response, "utxos");
	}

	/** get_balance answers with the same object in both families, so this only checks the shape. */
	public static Optional<JSONObject> normalizeBalance(Object response) {
		return response instanceof JSONObject ? Optional.of((JSONObject) response) : Optional.empty();
	}

	/**
	 * Normalise a blockchain.block.headers result to the list of raw header hex strings.
	 * <p>
	 * Below 1.6 the headers arrive as one concatenated <code>hex</code> string, which cannot be split here
	 * because header length varies by chain; that shape is returned unsplit as a single element and the
	 * caller splits it. From 1.6 the server has already split them into a <code>headers</code> array.
	 */
	public static Optional<BlockHeadersResult> normalizeBlockHeaders(Object response) {
		if (!(response instanceof JSONObject))
			return Optional.empty();

		JSONObject headersJson = (JSONObject) response;
		Object countObj = headersJson.get("count");
		if (!(countObj instanceof Number))
			return Optional.empty();

		int count = ((Number) countObj).intValue();

		Object headersObj = headersJson.get("headers");
		if (headersObj instanceof JSONArray) {
			List<String> headerHexes = new ArrayList<>();
			for (Object headerHex : (JSONArray) headersObj) {
				if (!(headerHex instanceof String))
					return Optional.empty();

				headerHexes.add((String) headerHex);
			}

			return Optional.of(BlockHeadersResult.ofHeaders(count, headerHexes));
		}

		Object hexObj = headersJson.get("hex");
		if (hexObj instanceof String)
			return Optional.of(BlockHeadersResult.ofConcatenatedHex(count, (String) hexObj));

		return Optional.empty();
	}

	/**
	 * The relay fee in whole coins per kB, from either blockchain.relayfee (a bare number) or
	 * mempool.get_info (an object whose <code>minrelaytxfee</code> carries it).
	 */
	public static Optional<Double> normalizeRelayFee(Object response) {
		if (response instanceof Number)
			return Optional.of(((Number) response).doubleValue());

		if (response instanceof JSONObject) {
			Object minRelayFee = ((JSONObject) response).get("minrelaytxfee");
			if (minRelayFee instanceof Number)
				return Optional.of(((Number) minRelayFee).doubleValue());
		}

		return Optional.empty();
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
