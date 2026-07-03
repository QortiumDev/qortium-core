package org.qortium.network;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.test.common.Common;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PeerExchangeRecorderTests extends Common {

	private static final String B32_A = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String B32_B = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildRecordIncludesSenderVersion() {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString(B32_A)), Peer.NETWORK);
		peer.setPeersNodeId("NexampleNodeId");
		peer.setPeersVersion("qortium-1.2.2-9e677e4", 0x010202L);

		JSONObject record = PeerExchangeRecorder.buildRecord(
				"chain",
				peer,
				List.of(PeerAddress.fromString("0.0.0.0:0"), PeerAddress.fromString(B32_B)),
				Instant.parse("2026-07-03T00:00:00Z"));

		assertEquals("2026-07-03T00:00:00Z", record.getString("at"));
		assertEquals("chain", record.getString("layer"));
		assertEquals(B32_A + ":0", record.getString("fromPeer"));
		assertEquals("NexampleNodeId", record.getString("fromNodeId"));
		assertEquals("I2P", record.getString("transport"));
		assertEquals("qortium-1.2.2-9e677e4", record.getString("version"));
		assertEquals(1, record.getJSONArray("peers").length());
		assertEquals(B32_B + ":0", record.getJSONArray("peers").getString(0));
	}

	@Test
	public void testBuildRecordOmitsBlankSenderVersion() {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("203.0.113.10:24892")), Peer.NETWORK);

		JSONObject record = PeerExchangeRecorder.buildRecord(
				"chain",
				peer,
				List.of(PeerAddress.fromString("0.0.0.0:0")),
				Instant.parse("2026-07-03T00:00:00Z"));

		assertFalse(record.has("version"));
	}
}
