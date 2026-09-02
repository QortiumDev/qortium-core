package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.qortium.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * blockchain.block.headers must produce byte-identical headers whichever protocol served it.
 * <p>
 * Fixtures are real replies captured on 2026-09-02 from nmc2.bitcoins.sk:57002 (Namecoin, AuxPoW headers)
 * and electrumx01.firo.org:50002 (Firo, 120-byte headers), each fetched twice from the same server: once
 * negotiated at 1.4, which answers with one concatenated "hex" string, and once at the server's maximum,
 * which answers with a "headers" array.
 * <p>
 * The array is <em>not</em> a list of block headers. It is the same bytes chunked at a fixed size, and the
 * chunk count is unrelated to "count": Namecoin returns 9 chunks for 1 header(s). Returning those chunks as headers
 * would hand callers 80-byte slices taken at arbitrary offsets, so both shapes are re-joined and divided by
 * the chain's own splitter instead.
 */
public class ElectrumBlockHeaderParityTests {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	// --- Namecoin, AuxPoW headers, start 300000, count 1 ---
	private static final int NMC_COUNT = 1;
	private static final String NMC_CONCATENATED =
			"04010100b18b42bc64a7ab219c39578f74dd3bfb9bd43127b6fe1631b8aa459b077a12f821616423e1e064bc661861e00854"
			+ "8b94149edad0f92fe5d9a6269d13051d1bd38d1faf57a3e60b18000000000100000001000000000000000000000000000000"
			+ "0000000000000000000000000000000000ffffffff64033d7c06df9808f809aab07ab617b747e1906e2100cdb21eabb9a519"
			+ "e6765e62befe555ef080b2e3cc0100000000000000ad7feee00e080b44eef9ed08002f425443432f00000000000000000000"
			+ "000000000000000000000000000000000000000000000000000001a620234c000000001976a9142c30a6aaac6d9668729147"
			+ "5d7d52f4b469f665a688ac0000000000000000000000000000000000000000000000000000000000000000000000000aef9c"
			+ "f11705dcc85dd9dba8c9e19c5c18d1b192d47e1bcf6f332fe9565096d9184a55a70ffbe5e25c00493b692a33d85587e6ee41"
			+ "a9647da9e30c25e1304d42a9a2d26b6d04878f7ce8d89fedb054b026e8bf22e28e29d7df5bafd6b41fee6bb77063074e7fd0"
			+ "dc438fb1f58cefbb6b8ac2f7542ccf42734ef1a74b1ea6d3bb8a61f0537ba3bd2b2dc0c6bca8cfc57f7147bc28a76036b073"
			+ "eb23691e0b22c12d03ad13335b6b2e936b3f1fd7a28ab3f5db36f74955164c736f77455d51dfe3d2c6a36053334f3b5aa6ba"
			+ "2f6f532215c682e1428982e6053b0412314b046c604dbbd4b4c4622364fc6caa12703237ffc2fcf8b66be70a2cc9030cbf6a"
			+ "4a9597ed3ce76d1275e2bea27f697c7726dd892aac11c1ac81c4184fccef2fbf497a9affdad6f3cc202fc17c06bd07eef4e1"
			+ "99d9d337013a710d1565bae4508c4469151d000000000000000000000000204ca92a6b4afecd52c27d91ce876f102915d986"
			+ "e24fdd1f000000000000000000ba38e457ca9b37c98e8adfecd44e30af809c61711a2b03e12051d7066a99cf5a941faf5728"
			+ "7205186a6691a2";
	private static final List<String> NMC_CHUNKS = List.of(
			"04010100b18b42bc64a7ab219c39578f74dd3bfb9bd43127b6fe1631b8aa459b077a12f821616423e1e064bc661861e008548b94149edad0f92fe5d9a6269d13051d1bd38d1faf57a3e60b1800000000",
			"01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff64033d7c06df9808f809aab07ab617b747e1906e2100cdb21eabb9a519e6765e62befe555ef080",
			"b2e3cc0100000000000000ad7feee00e080b44eef9ed08002f425443432f00000000000000000000000000000000000000000000000000000000000000000000000001a620234c000000001976a9142c",
			"30a6aaac6d96687291475d7d52f4b469f665a688ac0000000000000000000000000000000000000000000000000000000000000000000000000aef9cf11705dcc85dd9dba8c9e19c5c18d1b192d47e1b",
			"cf6f332fe9565096d9184a55a70ffbe5e25c00493b692a33d85587e6ee41a9647da9e30c25e1304d42a9a2d26b6d04878f7ce8d89fedb054b026e8bf22e28e29d7df5bafd6b41fee6bb77063074e7fd0",
			"dc438fb1f58cefbb6b8ac2f7542ccf42734ef1a74b1ea6d3bb8a61f0537ba3bd2b2dc0c6bca8cfc57f7147bc28a76036b073eb23691e0b22c12d03ad13335b6b2e936b3f1fd7a28ab3f5db36f7495516",
			"4c736f77455d51dfe3d2c6a36053334f3b5aa6ba2f6f532215c682e1428982e6053b0412314b046c604dbbd4b4c4622364fc6caa12703237ffc2fcf8b66be70a2cc9030cbf6a4a9597ed3ce76d1275e2",
			"bea27f697c7726dd892aac11c1ac81c4184fccef2fbf497a9affdad6f3cc202fc17c06bd07eef4e199d9d337013a710d1565bae4508c4469151d000000000000000000000000204ca92a6b4afecd52c2",
			"7d91ce876f102915d986e24fdd1f000000000000000000ba38e457ca9b37c98e8adfecd44e30af809c61711a2b03e12051d7066a99cf5a941faf57287205186a6691a2");

	// --- Firo, 120-byte headers, start 300000, count 2 ---
	private static final int FIRO_COUNT = 2;
	private static final String FIRO_CONCATENATED =
			"00100020ffd0bbd8afc9a885fc452e7791a7b341aa043f9fc0bb04b140959ea87daa70546b9fe6cc2402f570aed284e81ef1"
			+ "8e6e89f7b820a240afb9796310dbe37a36fb0318555fc9591b1bb9d407140000100007df80d1681152392127b47a219c574e"
			+ "67de9647f92eb54b6f9b09000000000000000000000000000000000000000000000000000000000000000000000000000000"
			+ "0000000000000000000000000000000000000000000000000000000000000010002060fa3dd09cca57c5dd428b4bc80ad287"
			+ "9884ee9f7ec4eacafe09c6e0a9320732689131d963bee412d4e8a3b3d5a079ca07c7f408ca5297b9758b03ebcb1281328d18"
			+ "555fc9591b1bce1f2d0900001000aa5facde9062e180862a0bcbd927e47f290d492669ea21ccd80810000000000000000000"
			+ "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
			+ "00000000000000000000";
	private static final List<String> FIRO_CHUNKS = List.of(
			"00100020ffd0bbd8afc9a885fc452e7791a7b341aa043f9fc0bb04b140959ea87daa70546b9fe6cc2402f570aed284e81ef18e6e89f7b820a240afb9796310dbe37a36fb0318555fc9591b1bb9d40714",
			"0000100007df80d1681152392127b47a219c574e67de9647f92eb54b6f9b0900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
			"00000000000000000000000000000000000000000010002060fa3dd09cca57c5dd428b4bc80ad2879884ee9f7ec4eacafe09c6e0a9320732689131d963bee412d4e8a3b3d5a079ca07c7f408ca5297b9",
			"758b03ebcb1281328d18555fc9591b1bce1f2d0900001000aa5facde9062e180862a0bcbd927e47f290d492669ea21ccd808100000000000000000000000000000000000000000000000000000000000",
			"00000000000000000000000000000000000000000000000000000000000000000000000000000000");

	@Test
	public void testNamecoinAuxPowHeadersMatchAcrossProtocols() throws Exception {
		assertParity("NAMECOIN", NMC_COUNT, NMC_CONCATENATED, NMC_CHUNKS);
	}

	@Test
	public void testFiroHeadersMatchAcrossProtocols() throws Exception {
		assertParity("FIRO", FIRO_COUNT, FIRO_CONCATENATED, FIRO_CHUNKS);
	}

	@Test
	public void testChunkCountIsUnrelatedToHeaderCount() {
		// The regression this guards: requiring headers.length == count rejects every real reply from a
		// chain whose headers are not 80 bytes.
		assertTrue("Namecoin returns more chunks than headers", NMC_CHUNKS.size() > NMC_COUNT);
		assertTrue("Firo returns more chunks than headers", FIRO_CHUNKS.size() > FIRO_COUNT);

		assertTrue(ElectrumMethods.normalizeBlockHeaders(headersArrayResponse(NMC_COUNT, NMC_CHUNKS)).isPresent());
		assertTrue(ElectrumMethods.normalizeBlockHeaders(headersArrayResponse(FIRO_COUNT, FIRO_CHUNKS)).isPresent());
	}

	/** Both shapes must reduce to the same bytes, and then to the same canonical headers. */
	private static void assertParity(String coin, int count, String concatenated, List<String> chunks) throws Exception {
		ElectrumMethods.BlockHeadersResult fromConcatenated =
				ElectrumMethods.normalizeBlockHeaders(concatenatedResponse(count, concatenated)).orElseThrow();
		ElectrumMethods.BlockHeadersResult fromChunks =
				ElectrumMethods.normalizeBlockHeaders(headersArrayResponse(count, chunks)).orElseThrow();

		assertEquals(count, fromConcatenated.getCount());
		assertEquals(count, fromChunks.getCount());
		assertTrue(fromChunks.isSplit());

		byte[] concatenatedBytes = hex(fromConcatenated.getConcatenatedHex());
		byte[] rejoinedBytes = ElectrumX.concatenateBlockHeaders(fromChunks.getHeaderHexes());
		assertArrayEquals(coin + ": the two protocol shapes must carry the same bytes", concatenatedBytes, rejoinedBytes);

		Bitcoiny bitcoiny = (Bitcoiny) ForeignBlockchainRegistry.fromStringRequired(coin).getInstance();
		List<byte[]> fromOldProtocol = bitcoiny.splitRawBlockHeaders(concatenatedBytes, count);
		List<byte[]> fromNewProtocol = bitcoiny.splitRawBlockHeaders(rejoinedBytes, count);

		assertEquals(coin + ": header count", count, fromOldProtocol.size());
		assertEquals(coin + ": header count", count, fromNewProtocol.size());
		for (int index = 0; index < count; index++)
			assertArrayEquals(coin + ": header " + index + " must be identical across protocols",
					fromOldProtocol.get(index), fromNewProtocol.get(index));
	}

	@SuppressWarnings("unchecked")
	private static JSONObject concatenatedResponse(int count, String concatenated) {
		JSONObject response = new JSONObject();
		response.put("hex", concatenated);
		response.put("count", (long) count);
		response.put("max", 2016L);
		return response;
	}

	@SuppressWarnings("unchecked")
	private static JSONObject headersArrayResponse(int count, List<String> chunks) {
		JSONObject response = new JSONObject();
		response.put("count", (long) count);
		response.put("max", 2016L);
		JSONArray headers = new JSONArray();
		headers.addAll(chunks);
		response.put("headers", headers);
		return response;
	}

	private static byte[] hex(String value) {
		byte[] bytes = new byte[value.length() / 2];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
		return bytes;
	}

	@Test
	public void testFixturesAreSelfConsistent() {
		// Guard the fixtures themselves: the chunks must join back to exactly the concatenated reply.
		assertEquals(NMC_CONCATENATED, String.join("", NMC_CHUNKS));
		assertEquals(FIRO_CONCATENATED, String.join("", FIRO_CHUNKS));
	}
}
