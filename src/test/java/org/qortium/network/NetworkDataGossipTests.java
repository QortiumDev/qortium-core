package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.message.Message;
import org.qortium.network.message.PeersMessage;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the DATA-LAYER peer-exchange (gossip) added to {@link NetworkData}.
 *
 * The QDN/data overlay must discover data peers independently of the chain layer while upholding
 * three privacy invariants:
 *   (1) the exchange is TRANSPORT-SCOPED (an I2P requester only ever learns .b32.i2p data peers;
 *       a clearnet requester only ever learns clearnet data peers; never cross),
 *   (2) chain addresses are NEVER emitted (allKnownPeers holds only DATA destinations),
 *   (3) the consumer transport-gates incoming addresses by LOCAL reachability before merging.
 */
public class NetworkDataGossipTests extends Common {

	private static final String B32_A = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String B32_B = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
	private static final String CLEARNET_A = "198.51.100.10:24894";
	private static final String CLEARNET_B = "203.0.113.20:24894";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();
	}

	@After
	public void after() throws Exception {
		getMutableKnownPeers().clear();
	}

	// ---------------------------------------------------------------------------------------------
	// (i) RESPONDER: buildDataPeersMessage / scopeDataPeerAddresses are transport-scoped
	// ---------------------------------------------------------------------------------------------

	@Test
	public void testI2PRequesterReceivesOnlyI2PDataPeers() throws Exception {
		List<PeerData> known = knownPeers(B32_A, B32_B, CLEARNET_A, CLEARNET_B);

		// requesterIsI2P = true
		List<PeerAddress> advertised = roundTrip(NetworkData.scopeDataPeerAddresses(known, true, false));

		assertTrue("I2P requester must receive the I2P data peer", containsAddress(advertised, B32_A + ":0"));
		assertTrue("I2P requester must receive the I2P data peer", containsAddress(advertised, B32_B + ":0"));
		// Never cross transports: no clearnet data peer to an I2P requester.
		assertFalse("I2P requester must NEVER receive a clearnet peer", containsAddress(advertised, CLEARNET_A));
		assertFalse("I2P requester must NEVER receive a clearnet peer", containsAddress(advertised, CLEARNET_B));
		for (PeerAddress address : advertised) {
			assertTrue("Only I2P addresses may be advertised to an I2P requester", address.isI2P());
		}
	}

	@Test
	public void testClearnetRequesterReceivesOnlyClearnetDataPeers() throws Exception {
		List<PeerData> known = knownPeers(B32_A, B32_B, CLEARNET_A, CLEARNET_B);

		// requesterIsI2P = false, requesterIsLocal = true so non-routable-test addresses aren't dropped
		List<PeerAddress> advertised = roundTrip(NetworkData.scopeDataPeerAddresses(known, false, true));

		assertTrue("clearnet requester must receive the clearnet data peer", containsAddress(advertised, CLEARNET_A));
		assertTrue("clearnet requester must receive the clearnet data peer", containsAddress(advertised, CLEARNET_B));
		// Never cross transports: no I2P data peer to a clearnet requester.
		assertFalse("clearnet requester must NEVER receive an I2P peer", containsAddress(advertised, B32_A + ":0"));
		assertFalse("clearnet requester must NEVER receive an I2P peer", containsAddress(advertised, B32_B + ":0"));
		for (PeerAddress address : advertised) {
			assertFalse("Only clearnet addresses may be advertised to a clearnet requester", address.isI2P());
		}
	}

	@Test
	public void testEmptyKnownPeersAdvertisesNothingButSentinel() throws Exception {
		List<PeerData> known = new ArrayList<>();

		PeersMessage message = new PeersMessage(NetworkData.scopeDataPeerAddresses(known, true, false));
		List<PeerAddress> decoded = decode(message);

		// Only the sentinel first entry is present; no real advertised addresses.
		assertEquals(1, decoded.size());
	}

	// ---------------------------------------------------------------------------------------------
	// (iii) chain addresses are never emitted: allKnownPeers only ever holds DATA destinations, so
	// the responder can only ever advertise what is in it. Verified structurally: build a PEERS
	// message from getInstance() with a known DATA set and confirm nothing outside it leaks.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void testResponderOnlyEmitsAddressesFromAllKnownPeers() throws Exception {
		getMutableKnownPeers().clear();
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32_A), 1L, "test"));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(CLEARNET_A), 1L, "test"));

		List<PeerData> known = NetworkData.getInstance().getAllKnownPeers();

		// I2P requester: only the single I2P data destination is emitted, the clearnet one is withheld.
		List<PeerAddress> advertised = roundTrip(NetworkData.scopeDataPeerAddresses(known, true, false));
		assertEquals(1, advertised.size());
		assertTrue(containsAddress(advertised, B32_A + ":0"));
		assertFalse(containsAddress(advertised, CLEARNET_A));
	}

	// ---------------------------------------------------------------------------------------------
	// (ii) CONSUMER: onPeersMessage merges advertised addresses into allKnownPeers, transport-gated
	// by LOCAL reachability, after dropping the sentinel first entry.
	// ---------------------------------------------------------------------------------------------

	@Test
	public void testConsumerMergesDialableDataPeers() throws Exception {
		// Both transports enabled locally.
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();

		PeersMessage incoming = incomingPeersMessage(B32_A, CLEARNET_A);
		invokeOnPeersMessage(dataPeer(), incoming);

		List<PeerData> known = NetworkData.getInstance().getAllKnownPeers();
		assertTrue("I2P data peer should be merged", containsAddress(known, B32_A + ":0"));
		assertTrue("clearnet data peer should be merged", containsAddress(known, CLEARNET_A));
		// Sentinel must never be merged.
		assertFalse("sentinel 0.0.0.0 must never be stored", containsAddressPrefix(known, "0.0.0.0:"));
	}

	@Test
	public void testConsumerDropsClearnetWhenIPDisabled() throws Exception {
		// I2P-only node: must never store a clearnet data peer it cannot dial.
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("I2P"), true);
		getMutableKnownPeers().clear();

		PeersMessage incoming = incomingPeersMessage(B32_A, CLEARNET_A);
		invokeOnPeersMessage(dataPeer(), incoming);

		List<PeerData> known = NetworkData.getInstance().getAllKnownPeers();
		assertTrue("I2P data peer should be merged on an I2P-only node", containsAddress(known, B32_A + ":0"));
		assertFalse("clearnet data peer must NOT be stored on an I2P-only node", containsAddress(known, CLEARNET_A));
	}

	@Test
	public void testConsumerDropsI2PWhenI2PDisabled() throws Exception {
		// Clearnet-only node: must never store an I2P data peer it cannot dial.
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP"), true);
		getMutableKnownPeers().clear();

		PeersMessage incoming = incomingPeersMessage(B32_A, CLEARNET_A);
		invokeOnPeersMessage(dataPeer(), incoming);

		List<PeerData> known = NetworkData.getInstance().getAllKnownPeers();
		assertFalse("I2P data peer must NOT be stored on a clearnet-only node", containsAddress(known, B32_A + ":0"));
		assertTrue("clearnet data peer should be merged on a clearnet-only node", containsAddress(known, CLEARNET_A));
	}

	@Test
	public void testConsumerDedupsExistingPeers() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32_A), 1L, "existing"));

		int sizeBefore = NetworkData.getInstance().getAllKnownPeers().size();

		// Advertise the already-known I2P peer plus a new one.
		PeersMessage incoming = incomingPeersMessage(B32_A, B32_B);
		invokeOnPeersMessage(dataPeer(), incoming);

		List<PeerData> known = NetworkData.getInstance().getAllKnownPeers();
		// Only the new one is added.
		assertEquals(sizeBefore + 1, known.size());
		assertTrue(containsAddress(known, B32_B + ":0"));
	}

	// ---------------------------------------------------------------------------------------------
	// (iv) BOUNDS: gossip must not grow allKnownPeers without limit. The data layer has no age-prune,
	// so onPeersMessage must (a) cap addresses processed per message and (b) cap total list size,
	// evicting only the oldest GOSSIPED entries (never trusted/connected ones).
	// ---------------------------------------------------------------------------------------------

	@Test
	public void testConsumerCapsAddressesPerMessage() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();

		int cap = readIntConstant("MAX_GOSSIPED_PEERS_PER_MESSAGE");

		// Advertise more dialable I2P addresses than the per-message cap allows.
		int advertisedCount = cap + 50;
		String[] addresses = new String[advertisedCount];
		for (int i = 0; i < advertisedCount; i++) {
			addresses[i] = uniqueB32(i);
		}

		PeersMessage incoming = incomingPeersMessage(addresses);
		invokeOnPeersMessage(dataPeer(), incoming);

		int stored = NetworkData.getInstance().getAllKnownPeers().size();
		assertEquals("must store at most the per-message cap, regardless of how many were advertised",
				cap, stored);
	}

	@Test
	public void testTotalSizeCapEvictsOldestGossipedPeer() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();

		int maxKnown = readIntConstant("MAX_KNOWN_PEERS");
		String gossipMarker = readStringConstant("GOSSIP_ADDED_BY");

		// Fill the list right up to the cap with gossiped entries of increasing age (older = smaller
		// addedWhen). The very first one is the oldest and should be the eviction victim.
		List<PeerData> known = getMutableKnownPeers();
		String oldestAddress = uniqueB32(0);
		for (int i = 0; i < maxKnown; i++) {
			long addedWhen = 1000L + i; // strictly increasing => index 0 is oldest
			known.add(new PeerData(PeerAddress.fromString(uniqueB32(i)), addedWhen, gossipMarker));
		}
		assertEquals(maxKnown, NetworkData.getInstance().getAllKnownPeers().size());

		// One more gossiped address arrives: it must be stored, and the list must stay at the cap by
		// evicting the OLDEST existing gossiped entry.
		String newAddress = uniqueB32(maxKnown);
		invokeOnPeersMessage(dataPeer(), incomingPeersMessage(newAddress));

		List<PeerData> after = NetworkData.getInstance().getAllKnownPeers();
		assertEquals("list size must remain capped", maxKnown, after.size());
		assertTrue("the newly gossiped address must be stored", containsAddress(after, newAddress + ":0"));
		assertFalse("the oldest gossiped address must have been evicted",
				containsAddress(after, oldestAddress + ":0"));
	}

	@Test
	public void testTotalSizeCapNeverEvictsTrustedPeers() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", List.of("IP", "I2P"), true);
		getMutableKnownPeers().clear();

		int maxKnown = readIntConstant("MAX_KNOWN_PEERS");

		// Fill the entire list to the cap with TRUSTED (non-gossip addedBy) entries, e.g. seeds.
		List<PeerData> known = getMutableKnownPeers();
		for (int i = 0; i < maxKnown; i++) {
			known.add(new PeerData(PeerAddress.fromString(uniqueB32(i)), 1000L + i, "INIT"));
		}
		assertEquals(maxKnown, NetworkData.getInstance().getAllKnownPeers().size());

		// A new gossiped address arrives. There are no evictable gossiped entries, so it must be
		// dropped and every trusted entry must remain intact.
		String newAddress = uniqueB32(maxKnown);
		invokeOnPeersMessage(dataPeer(), incomingPeersMessage(newAddress));

		List<PeerData> after = NetworkData.getInstance().getAllKnownPeers();
		assertEquals("list size must remain capped (trusted entries never evicted)", maxKnown, after.size());
		assertFalse("a gossiped peer must NOT displace a trusted (seed) peer",
				containsAddress(after, newAddress + ":0"));
		// Spot-check that a couple of the original trusted entries survived.
		assertTrue(containsAddress(after, uniqueB32(0) + ":0"));
		assertTrue(containsAddress(after, uniqueB32(maxKnown - 1) + ":0"));
	}

	// ---------------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------------

	private static int readIntConstant(String name) throws Exception {
		java.lang.reflect.Field field = NetworkData.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.getInt(null);
	}

	private static String readStringConstant(String name) throws Exception {
		java.lang.reflect.Field field = NetworkData.class.getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(null);
	}

	/** Deterministic, distinct, valid 52-char base32 .b32.i2p host for index i (0..&lt;32^? ). */
	private static String uniqueB32(int i) {
		final String alphabet = "abcdefghijklmnopqrstuvwxyz234567"; // base32
		// Encode i across the last several characters of a fixed 52-char base32 host.
		char[] host = new char[52];
		for (int p = 0; p < 52; p++) {
			host[p] = 'a';
		}
		int value = i;
		int pos = 51;
		while (value > 0 && pos >= 0) {
			host[pos--] = alphabet.charAt(value & 31);
			value >>= 5;
		}
		return new String(host) + ".b32.i2p";
	}

	private static List<PeerData> knownPeers(String... addresses) {
		List<PeerData> peers = new ArrayList<>();
		for (String address : addresses) {
			peers.add(new PeerData(PeerAddress.fromString(address), 1L, "test"));
		}
		return peers;
	}

	/** Serialize a scoped address list through PeersMessage and decode it (dropping the sentinel). */
	private static List<PeerAddress> roundTrip(List<PeerAddress> scoped) throws Exception {
		PeersMessage message = new PeersMessage(scoped);
		List<PeerAddress> decoded = decode(message);
		// Drop the sentinel first entry exactly as the consumer does.
		assertFalse("expected at least the sentinel entry", decoded.isEmpty());
		decoded.remove(0);
		return decoded;
	}

	/** Decode a built (outbound) PeersMessage via its wire dataBytes; result includes the sentinel. */
	private static List<PeerAddress> decode(PeersMessage message) throws Exception {
		byte[] dataBytes = (byte[]) FieldUtils.readField(message, "dataBytes", true);
		PeersMessage parsed = (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(dataBytes));
		return new ArrayList<>(parsed.getPeerAddresses());
	}

	/** Build a PEERS message as it would arrive on the wire (sentinel-prefixed), then decode it. */
	private static PeersMessage incomingPeersMessage(String... advertised) throws Exception {
		List<PeerAddress> addresses = new ArrayList<>();
		for (String address : advertised) {
			addresses.add(PeerAddress.fromString(address));
		}
		PeersMessage outbound = new PeersMessage(addresses);
		byte[] dataBytes = (byte[]) FieldUtils.readField(outbound, "dataBytes", true);
		return (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(dataBytes));
	}

	private static Peer dataPeer() {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString(CLEARNET_B)), Peer.NETWORKDATA);
		peer.setPeersNodeId("gossip-test-node-id");
		peer.setIsDataPeer(true);
		return peer;
	}

	private static void invokeOnPeersMessage(Peer peer, Message message) throws Exception {
		Method method = NetworkData.class.getDeclaredMethod("onPeersMessage", Peer.class, Message.class);
		method.setAccessible(true);
		method.invoke(NetworkData.getInstance(), peer, message);
	}

	@SuppressWarnings("unchecked")
	private static List<PeerData> getMutableKnownPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(NetworkData.getInstance(), "allKnownPeers", true);
	}

	private static boolean containsAddress(List<? extends Object> peers, String address) {
		for (Object o : peers) {
			String s = (o instanceof PeerData) ? ((PeerData) o).getAddress().toString() : o.toString();
			if (s.equals(address)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsAddressPrefix(List<PeerData> peers, String prefix) {
		for (PeerData peerData : peers) {
			if (peerData.getAddress().toString().startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
