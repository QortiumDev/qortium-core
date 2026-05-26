package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.NetworkParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;
import org.qortium.crosschain.*;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.utils.BitTwiddling;

import java.security.Security;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

@SuppressWarnings("unchecked")
public class ElectrumXTests {

	private static final String RUN_LIVE_ELECTRUMX_TESTS_PROPERTY = "qortium.runLiveElectrumXTests";

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	private static final Map<ElectrumX.Server.ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ElectrumX.Server.ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	private ElectrumX getLiveBitcoinTest4Instance() {
		assumeTrue(Boolean.getBoolean(RUN_LIVE_ELECTRUMX_TESTS_PROPERTY));
		BitcoinyNetwork bitcoinTest4 = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST4);
		Collection<ElectrumX.Server> servers = bitcoinTest4.getServers();
		assertFalse("No Bitcoin TEST4 ElectrumX servers are configured for explicit live ElectrumX checks", servers.isEmpty());
		return new ElectrumX("Bitcoin-" + bitcoinTest4.name(), bitcoinTest4.getGenesisHash(), servers, DEFAULT_ELECTRUMX_PORTS);
	}

	private ElectrumX getLiveBitcoinTest3FixtureInstance() {
		assumeTrue(Boolean.getBoolean(RUN_LIVE_ELECTRUMX_TESTS_PROPERTY));
		BitcoinyNetwork bitcoinTest3 = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3);
		Collection<ElectrumX.Server> servers = bitcoinTest3.getServers();
		assertFalse("No Bitcoin TEST3 ElectrumX servers are configured for legacy fixture checks", servers.isEmpty());
		return new ElectrumX("Bitcoin-" + bitcoinTest3.name(), bitcoinTest3.getGenesisHash(), servers, DEFAULT_ELECTRUMX_PORTS);
	}

	@Test
	public void testInstance() {
		ElectrumX electrumX = new MockElectrumX(Collections.emptyMap());
		assertNotNull(electrumX);
	}

	@Test
	public void testGetCurrentHeightFromMockRpc() throws ForeignBlockchainException {
		JSONObject response = new JSONObject();
		response.put("height", 12345L);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.headers.subscribe", response));

		assertEquals(12345, electrumX.getCurrentHeight());
	}

	@Test
	public void testRawBlockHeadersAcceptsIntegerCount() throws ForeignBlockchainException {
		JSONObject response = new JSONObject();
		response.put("count", Integer.valueOf(2));
		response.put("hex", "00".repeat(160));

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.block.headers", response));
		List<byte[]> rawBlockHeaders = electrumX.getRawBlockHeaders(1, 2);

		assertEquals(2, rawBlockHeaders.size());
		assertEquals(80, rawBlockHeaders.get(0).length);
		assertEquals(80, rawBlockHeaders.get(1).length);
	}

	@Test
	public void testRawBlockHeadersAcceptsStringCount() throws ForeignBlockchainException {
		JSONObject response = new JSONObject();
		response.put("count", "2");
		response.put("hex", "00".repeat(160));

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.block.headers", response));
		List<byte[]> rawBlockHeaders = electrumX.getRawBlockHeaders(1, 2);

		assertEquals(2, rawBlockHeaders.size());
		assertEquals(80, rawBlockHeaders.get(0).length);
		assertEquals(80, rawBlockHeaders.get(1).length);
	}

	@Test
	public void testRawBlockHeadersRejectsMissingHex() {
		JSONObject response = new JSONObject();
		response.put("count", Integer.valueOf(1));

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.block.headers", response));

		try {
			electrumX.getRawBlockHeaders(1, 1);
			fail("Missing raw header hex should cause network exception");
		} catch (ForeignBlockchainException e) {
			assertTrue(e instanceof ForeignBlockchainException.NetworkException);
		}
	}

	@Test
	public void testGetConfirmedBalanceFromMockRpc() throws ForeignBlockchainException {
		JSONObject response = new JSONObject();
		response.put("confirmed", 123456789L);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.scripthash.get_balance", response));

		assertEquals(123456789L, electrumX.getConfirmedBalance(new byte[] { 0x01, 0x02 }));
	}

	@Test
	public void testGetRawTransactionFromMockRpc() throws ForeignBlockchainException {
		String txHex = "00".repeat(32);
		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.transaction.get", txHex));

		assertArrayEquals(HashCode.fromString(txHex).asBytes(), electrumX.getRawTransaction("ab".repeat(32)));
	}

	@Test
	public void testGetUnknownRawTransactionFromMockRpc() {
		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.transaction.get",
				new ForeignBlockchainException.NetworkException(-5, "missing transaction")));

		try {
			electrumX.getRawTransaction("ab".repeat(32));
			fail("Missing transaction should cause NotFoundException");
		} catch (ForeignBlockchainException e) {
			assertTrue(e instanceof ForeignBlockchainException.NotFoundException);
		}
	}

	@Test
	public void testVerboseTransactionOutputUsesConfiguredDecimalPlaces() throws ForeignBlockchainException {
		JSONObject outputScript = new JSONObject();
		outputScript.put("hex", "76a914" + "11".repeat(20) + "88ac");
		outputScript.put("addresses", new JSONArray());
		((JSONArray) outputScript.get("addresses")).add("mock-address");

		JSONObject output = new JSONObject();
		output.put("value", 1.234567D);
		output.put("scriptPubKey", outputScript);

		JSONObject transaction = new JSONObject();
		transaction.put("size", 100L);
		transaction.put("locktime", 0L);
		transaction.put("vin", new JSONArray());
		transaction.put("vout", new JSONArray());
		((JSONArray) transaction.get("vout")).add(output);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.transaction.get", transaction), 6);

		assertEquals(1_234_567L, electrumX.getTransaction("mock-tx").outputs.get(0).value);
	}

	@Test
	public void testVerboseTransactionOutputDefaultsToEightDecimalPlaces() throws ForeignBlockchainException {
		JSONObject outputScript = new JSONObject();
		outputScript.put("hex", "76a914" + "11".repeat(20) + "88ac");
		outputScript.put("addresses", new JSONArray());
		((JSONArray) outputScript.get("addresses")).add("mock-address");

		JSONObject output = new JSONObject();
		output.put("value", 1.23456789D);
		output.put("scriptPubKey", outputScript);

		JSONObject transaction = new JSONObject();
		transaction.put("size", 100L);
		transaction.put("locktime", 0L);
		transaction.put("vin", new JSONArray());
		transaction.put("vout", new JSONArray());
		((JSONArray) transaction.get("vout")).add(output);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.transaction.get", transaction));

		assertEquals(123_456_789L, electrumX.getTransaction("mock-tx").outputs.get(0).value);
	}

	@Test
	public void testGetAddressTransactionsFromMockRpc() throws ForeignBlockchainException {
		JSONObject confirmedTransaction = new JSONObject();
		confirmedTransaction.put("height", 100L);
		confirmedTransaction.put("tx_hash", "aa".repeat(32));

		JSONObject unconfirmedTransaction = new JSONObject();
		unconfirmedTransaction.put("height", 0L);
		unconfirmedTransaction.put("tx_hash", "bb".repeat(32));

		JSONArray response = new JSONArray();
		response.add(confirmedTransaction);
		response.add(unconfirmedTransaction);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.scripthash.get_history", response));
		List<TransactionHash> transactionHashes = electrumX.getAddressTransactions(new byte[] { 0x01, 0x02 }, false);

		assertEquals(1, transactionHashes.size());
		assertEquals(100, transactionHashes.get(0).height);
		assertEquals("aa".repeat(32), transactionHashes.get(0).txHash);
	}

	@Test
	public void testGetUnspentOutputsFromMockRpc() throws ForeignBlockchainException {
		JSONObject confirmedOutput = new JSONObject();
		confirmedOutput.put("height", 100L);
		confirmedOutput.put("tx_hash", "cc".repeat(32));
		confirmedOutput.put("tx_pos", 1L);
		confirmedOutput.put("value", 5000L);

		JSONObject unconfirmedOutput = new JSONObject();
		unconfirmedOutput.put("height", 0L);
		unconfirmedOutput.put("tx_hash", "dd".repeat(32));
		unconfirmedOutput.put("tx_pos", 2L);
		unconfirmedOutput.put("value", 6000L);

		JSONArray response = new JSONArray();
		response.add(confirmedOutput);
		response.add(unconfirmedOutput);

		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.scripthash.listunspent", response));
		List<UnspentOutput> confirmedOutputs = electrumX.getUnspentOutputs(new byte[] { 0x01, 0x02 }, false);

		assertEquals(1, confirmedOutputs.size());
		assertArrayEquals(HashCode.fromString("cc".repeat(32)).asBytes(), confirmedOutputs.get(0).hash);
		assertEquals(1, confirmedOutputs.get(0).index);
		assertEquals(100, confirmedOutputs.get(0).height);
		assertEquals(5000L, confirmedOutputs.get(0).value);

		List<UnspentOutput> allOutputs = electrumX.getUnspentOutputs(new byte[] { 0x01, 0x02 }, true);
		assertEquals(2, allOutputs.size());
		assertArrayEquals(HashCode.fromString("dd".repeat(32)).asBytes(), allOutputs.get(1).hash);
		assertEquals(2, allOutputs.get(1).index);
		assertEquals(0, allOutputs.get(1).height);
		assertEquals(6000L, allOutputs.get(1).value);
	}

	@Test
	public void testGetUnspentOutputsRejectsMalformedResponse() {
		ElectrumX electrumX = new MockElectrumX(Collections.singletonMap("blockchain.scripthash.listunspent", "not-an-array"));

		try {
			electrumX.getUnspentOutputs(new byte[] { 0x01, 0x02 }, false);
			fail("Malformed unspent output response should fail");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("Expected array output"));
		}
	}

	@Test
	public void testGetCurrentHeight() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest4Instance();

		int height = electrumX.getCurrentHeight();

		assertTrue(height > 10000);
		System.out.println("Current TEST4 height: " + height);
	}

	@Test
	public void testInvalidRequest() {
		ElectrumX electrumX = getLiveBitcoinTest4Instance();
		try {
			electrumX.getRawBlockHeaders(-1, -1);
		} catch (ForeignBlockchainException e) {
			// Should throw due to negative start block height
			return;
		}

		fail("Negative start block height should cause error");
	}

	@Test
	public void testGetRecentBlocks() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest4Instance();

		int height = electrumX.getCurrentHeight();
		assertTrue(height > 10000);

		List<byte[]> recentBlockHeaders = electrumX.getRawBlockHeaders(height - 11, 11);

		System.out.println(String.format("Returned %d recent blocks", recentBlockHeaders.size()));
		for (int i = 0; i < recentBlockHeaders.size(); ++i) {
			byte[] blockHeader = recentBlockHeaders.get(i);

			// Timestamp(int) is at 4 + 32 + 32 = 68 bytes offset
			int offset = 4 + 32 + 32;
			int timestamp = BitTwiddling.intFromLEBytes(blockHeader, offset);
			System.out.println(String.format("Block %d timestamp: %d", height + i, timestamp));
		}
	}

	@Test
	public void testGetP2PKHBalance() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		String address = "n3GNqMveyvaPvUbH469vDRadqpJMPc84JA";
		byte[] script = BitcoinyScript.scriptPubKey(bitcoinTest3Params(), address);
		long balance = electrumX.getConfirmedBalance(script);

		assertTrue(balance > 0L);

		System.out.println(String.format("TestNet address %s has balance: %d sats / %d.%08d BTC", address, balance, (balance / 100000000L), (balance % 100000000L)));
	}

	@Test
	public void testGetP2SHBalance() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		String address = "2N4szZUfigj7fSBCEX4PaC8TVbC5EvidaVF";
		byte[] script = BitcoinyScript.scriptPubKey(bitcoinTest3Params(), address);
		long balance = electrumX.getConfirmedBalance(script);

		assertTrue(balance > 0L);

		System.out.println(String.format("TestNet address %s has balance: %d sats / %d.%08d BTC", address, balance, (balance / 100000000L), (balance % 100000000L)));
	}

	@Test
	public void testGetUnspentOutputs() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		String address = "2N4szZUfigj7fSBCEX4PaC8TVbC5EvidaVF";
		byte[] script = BitcoinyScript.scriptPubKey(bitcoinTest3Params(), address);
		List<UnspentOutput> unspentOutputs = electrumX.getUnspentOutputs(script, false);

		assertFalse(unspentOutputs.isEmpty());

		for (UnspentOutput unspentOutput : unspentOutputs)
			System.out.println(String.format("TestNet address %s has unspent output at tx %s, output index %d", address, HashCode.fromBytes(unspentOutput.hash), unspentOutput.index));
	}

	@Test
	public void testGetRawTransaction() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		byte[] txHash = HashCode.fromString("7653fea9ffcd829d45ed2672938419a94951b08175982021e77d619b553f29af").asBytes();

		byte[] rawTransactionBytes = electrumX.getRawTransaction(txHash);

		assertFalse(rawTransactionBytes.length == 0);
	}

	@Test
	public void testGetUnknownRawTransaction() {
		ElectrumX electrumX = getLiveBitcoinTest4Instance();

		byte[] txHash = HashCode.fromString("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0").asBytes();

		try {
			electrumX.getRawTransaction(txHash);
			fail("Bitcoin transaction should be unknown and hence throw exception");
		} catch (ForeignBlockchainException e) {
			if (!(e instanceof ForeignBlockchainException.NotFoundException))
				fail("Bitcoin transaction should be unknown and hence throw NotFoundException");
		}
	}

	@Test
	public void testGetTransaction() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		String txHash = "7653fea9ffcd829d45ed2672938419a94951b08175982021e77d619b553f29af";

		BitcoinyTransaction transaction = electrumX.getTransaction(txHash);

		assertNotNull(transaction);
		assertTrue(transaction.txHash.equals(txHash));
	}

	@Test
	public void testGetUnknownTransaction() {
		ElectrumX electrumX = getLiveBitcoinTest4Instance();

		String txHash = "f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0";

		try {
			electrumX.getTransaction(txHash);
			fail("Bitcoin transaction should be unknown and hence throw exception");
		} catch (ForeignBlockchainException e) {
			if (!(e instanceof ForeignBlockchainException.NotFoundException))
				fail("Bitcoin transaction should be unknown and hence throw NotFoundException");
		}
	}

	@Test
	public void testGetAddressTransactions() throws ForeignBlockchainException {
		ElectrumX electrumX = getLiveBitcoinTest3FixtureInstance();

		byte[] script = BitcoinyScript.scriptPubKey(bitcoinTest3Params(), "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE");

		List<TransactionHash> transactionHashes = electrumX.getAddressTransactions(script, false);

		assertFalse(transactionHashes.isEmpty());
	}

	private static NetworkParameters bitcoinTest3Params() {
		return BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3).getParams();
	}

	private static class MockElectrumX extends ElectrumX {

		private final Map<String, Object> responsesByMethod;

		private MockElectrumX(Map<String, Object> responsesByMethod) {
			this(responsesByMethod, 8);
		}

		private MockElectrumX(Map<String, Object> responsesByMethod, int coinDecimalPlaces) {
			super("mock", null, Collections.emptyList(), DEFAULT_ELECTRUMX_PORTS, coinDecimalPlaces);
			this.responsesByMethod = new HashMap<>(responsesByMethod);
		}

		@Override
		protected ElectrumServerResponse rpc(String method, Object... params) throws ForeignBlockchainException {
			Object response = this.responsesByMethod.get(method);
			if (response instanceof ForeignBlockchainException)
				throw (ForeignBlockchainException) response;

			return new ElectrumServerResponse(null, response);
		}
	}

}
