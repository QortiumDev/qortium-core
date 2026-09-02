package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Protocol negotiation must stay within the range Core actually implements: ElectrumX 2.0 servers
 * offer protocol 1.5+, which no longer serves the blockchain.scripthash.* methods Core relies on.
 */
public class ElectrumProtocolVersionTests {

	private static ElectrumProtocolVersion version(String value) {
		return ElectrumProtocolVersion.parse(value).orElseThrow(() -> new AssertionError("could not parse " + value));
	}

	// --- version parsing and ordering ---

	@Test
	public void testComponentsAreComparedAsIntegersNotDecimals() {
		// 1.10 parsed as a decimal collapses to 1.1 and sorts below 1.4; as components it is above it.
		assertTrue(version("1.10").compareTo(version("1.4")) > 0);
		assertTrue(version("1.20").compareTo(version("1.4")) > 0);
		assertTrue(version("1.10").compareTo(version("1.9")) > 0);
		assertTrue(version("1.4").compareTo(version("1.10")) < 0);
		assertTrue(version("1.4.2").compareTo(version("1.4")) > 0);
		assertEquals(0, version("1.4").compareTo(version("1.4.0")));
	}

	@Test
	public void testInvalidVersionsDoNotParse() {
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse(null));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse(""));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("1"));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("1."));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("1.4.2.1"));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("1.x"));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("-1.4"));
		assertEquals(Optional.empty(), ElectrumProtocolVersion.parse("not-a-version"));
	}

	@Test
	public void testTwoComponentCeilingCoversItsWholeFamily() {
		ElectrumProtocolVersion ceiling = ElectrumProtocolVersion.of(1, 4);

		assertTrue(version("1.4").isAtOrBelow(ceiling));
		assertTrue(version("1.4.0").isAtOrBelow(ceiling));
		assertTrue(version("1.4.2").isAtOrBelow(ceiling));
		assertTrue(version("1.4.99").isAtOrBelow(ceiling));
		assertFalse(version("1.5").isAtOrBelow(ceiling));
		assertFalse(version("1.10").isAtOrBelow(ceiling));
		assertFalse(version("1.20").isAtOrBelow(ceiling));
		assertFalse(version("2.0").isAtOrBelow(ceiling));
	}

	@Test
	public void testThreeComponentCeilingIsExact() {
		ElectrumProtocolVersion ceiling = version("1.4.2");

		assertTrue(version("1.4.2").isAtOrBelow(ceiling));
		assertTrue(version("1.4.1").isAtOrBelow(ceiling));
		assertFalse(version("1.4.3").isAtOrBelow(ceiling));
	}

	@Test
	public void testIsWithinChecksBothEnds() {
		ElectrumProtocolVersion minimum = ElectrumProtocolVersion.of(1, 2);
		ElectrumProtocolVersion maximum = ElectrumProtocolVersion.of(1, 4);

		assertTrue(version("1.2").isWithin(minimum, maximum));
		assertTrue(version("1.3").isWithin(minimum, maximum));
		assertTrue(version("1.4.2").isWithin(minimum, maximum));
		assertFalse(version("1.1").isWithin(minimum, maximum));
		assertFalse(version("1.1.9").isWithin(minimum, maximum));
		assertFalse(version("1.10").isWithin(minimum, maximum));
		assertFalse(version("2.0").isWithin(minimum, maximum));
	}

	@Test
	public void testToStringKeepsTheComponentsItWasGiven() {
		assertEquals("1.4", version("1.4").toString());
		assertEquals("1.4.2", version("1.4.2").toString());
		assertEquals("1.10", version("1.10").toString());
		assertEquals("1.4", ElectrumProtocolVersion.of(1, 4).toString());
	}

	// --- ElectrumX negotiation ---

	@Test
	public void testSupportedRangeIsOneTwoToTheOneFourFamily() {
		assertEquals(ElectrumProtocolVersion.of(1, 2), ElectrumX.MIN_PROTOCOL_VERSION);
		assertEquals(ElectrumProtocolVersion.of(1, 4), ElectrumX.MAX_PROTOCOL_VERSION);
	}

	@Test
	public void testVersionRequestAsksForOneTwoToOneFour() {
		assertEquals(List.of("1.2", "1.4"), ElectrumX.buildVersionParams());
	}

	@Test
	public void testSupportedNegotiatedVersionsAreAccepted() {
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.2"));
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.3"));
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.4"));
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.4.2"));
	}

	@Test
	public void testNegotiatedVersionsOutsideTheRangeAreNotSupported() {
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.1"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.5"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.7.0"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.10"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.20"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("2.0"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported("not-a-version"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported(null));
	}

	@Test
	public void testNegotiatedVersionAboveMaximumIsRejected() {
		Optional<String> note = ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 2.0.0", "1.7.0"));

		assertTrue(note.isPresent());
		assertEquals("negotiated protocol 1.7.0 outside supported 1.2-1.4", note.get());
	}

	@Test
	public void testDecimalLookalikeNegotiatedVersionsAreRejected() {
		assertEquals(Optional.of("negotiated protocol 1.10 outside supported 1.2-1.4"),
				ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 2.1.0", "1.10")));
		assertEquals(Optional.of("negotiated protocol 1.20 outside supported 1.2-1.4"),
				ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 2.1.0", "1.20")));
		assertEquals(Optional.of("negotiated protocol 2.0 outside supported 1.2-1.4"),
				ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 2.1.0", "2.0")));
	}

	@Test
	public void testNegotiatedVersionBelowMinimumIsRejected() {
		Optional<String> note = ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 1.8.7", "1.1"));

		assertTrue(note.isPresent());
		assertEquals("negotiated protocol 1.1 outside supported 1.2-1.4", note.get());
	}

	@Test
	public void testSupportedNegotiatedVersionIsNotRejected() {
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 1.16.0", "1.4")));
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 1.16.0", "1.4.2")));
	}

	@Test
	public void testMalformedVersionResponseIsRejected() {
		// A negotiation we cannot read is not evidence that the connection is usable, so it must not fail open.
		assertEquals(Optional.of(ElectrumX.MALFORMED_VERSION_RESPONSE_NOTE),
				ElectrumX.negotiatedVersionRejectionNote(null));
		assertEquals(Optional.of(ElectrumX.MALFORMED_VERSION_RESPONSE_NOTE),
				ElectrumX.negotiatedVersionRejectionNote("not an array"));

		JSONArray shortResponse = new JSONArray();
		shortResponse.add("ElectrumX 1.16.0");
		assertEquals(Optional.of(ElectrumX.MALFORMED_VERSION_RESPONSE_NOTE),
				ElectrumX.negotiatedVersionRejectionNote(shortResponse));

		assertEquals(Optional.of(ElectrumX.MALFORMED_VERSION_RESPONSE_NOTE),
				ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 1.16.0", "   ")));

		JSONArray nonStringVersion = new JSONArray();
		nonStringVersion.add("ElectrumX 1.16.0");
		nonStringVersion.add(1.4d);
		assertEquals(Optional.of(ElectrumX.MALFORMED_VERSION_RESPONSE_NOTE),
				ElectrumX.negotiatedVersionRejectionNote(nonStringVersion));
	}

	@Test
	public void testUnparsableNegotiatedVersionIsRejected() {
		assertEquals(Optional.of("negotiated protocol 1.x is not a valid version"),
				ElectrumX.negotiatedVersionRejectionNote(versionResponse("ElectrumX 1.16.0", "1.x")));
	}

	// --- server.features protocol_min ---

	@Test
	public void testProtocolMinAboveMaximumIsRejected() {
		assertEquals(Optional.of("new version: protocol_min = 1.5 > MAX_PROTOCOL_VERSION = 1.4"),
				ElectrumX.protocolMinRejectionNote(features("1.5")));
		assertEquals(Optional.of("new version: protocol_min = 1.10 > MAX_PROTOCOL_VERSION = 1.4"),
				ElectrumX.protocolMinRejectionNote(features("1.10")));
		assertEquals(Optional.of("new version: protocol_min = 2.0 > MAX_PROTOCOL_VERSION = 1.4"),
				ElectrumX.protocolMinRejectionNote(features("2.0")));
	}

	@Test
	public void testProtocolMinBelowMinimumIsAccepted() {
		// protocol_min only says how low a server can go; what matters is whether it can also speak our range,
		// and the negotiated version proves that separately.
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(features("1.1")));
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(features("0.54")));
	}

	@Test
	public void testSupportedProtocolMinIsAccepted() {
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(features("1.2")));
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(features("1.4")));
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(features("1.4.2")));
	}

	@Test
	public void testMissingOrInvalidProtocolMinIsRejected() {
		assertEquals(Optional.of("server version not available: protocol_min"),
				ElectrumX.protocolMinRejectionNote(new JSONObject()));
		assertEquals(Optional.of("banana is not a valid version"),
				ElectrumX.protocolMinRejectionNote(features("banana")));
	}

	private static JSONArray versionResponse(String software, Object protocolVersion) {
		JSONArray response = new JSONArray();
		response.add(software);
		response.add(protocolVersion);
		return response;
	}

	@SuppressWarnings("unchecked")
	private static JSONObject features(String protocolMin) {
		JSONObject featuresJson = new JSONObject();
		featuresJson.put("protocol_min", protocolMin);
		return featuresJson;
	}
}
