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

	@Test
	public void testVersionRequestAsksForOneTwoToOneFour() {
		List<String> versions = ElectrumX.buildVersionParams();

		assertEquals(List.of("1.2", "1.4"), versions);
	}

	@Test
	public void testSupportedNegotiatedVersionsAreAccepted() {
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.2"));
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.4"));
		assertTrue(ElectrumX.isNegotiatedVersionSupported("1.4.2"));
	}

	@Test
	public void testNegotiatedVersionAboveMaximumIsRejected() {
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.7.0"));

		JSONArray versionResponse = new JSONArray();
		versionResponse.add("ElectrumX 2.0.0");
		versionResponse.add("1.7.0");

		Optional<String> note = ElectrumX.negotiatedVersionRejectionNote(versionResponse);

		assertTrue(note.isPresent());
		assertEquals("negotiated protocol 1.7.0 outside supported 1.2-1.4", note.get());
	}

	@Test
	public void testNegotiatedVersionBelowMinimumIsRejected() {
		assertFalse(ElectrumX.isNegotiatedVersionSupported("1.1"));

		JSONArray versionResponse = new JSONArray();
		versionResponse.add("ElectrumX 1.8.7");
		versionResponse.add("1.1");

		Optional<String> note = ElectrumX.negotiatedVersionRejectionNote(versionResponse);

		assertTrue(note.isPresent());
		assertTrue(note.get().contains("negotiated protocol 1.1"));
	}

	@Test
	public void testSupportedNegotiatedVersionIsNotRejected() {
		JSONArray versionResponse = new JSONArray();
		versionResponse.add("ElectrumX 1.16.0");
		versionResponse.add("1.4");

		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(versionResponse));
	}

	@Test
	public void testMissingOrUnparsableVersionResponseIsNotRejected() {
		// Servers that answer without a usable version string keep the pre-existing behaviour:
		// the wallet-capability probe decides whether they are usable.
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(null));
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote("not an array"));

		JSONArray shortResponse = new JSONArray();
		shortResponse.add("ElectrumX 1.16.0");
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(shortResponse));

		JSONArray blankVersion = new JSONArray();
		blankVersion.add("ElectrumX 1.16.0");
		blankVersion.add("   ");
		assertEquals(Optional.empty(), ElectrumX.negotiatedVersionRejectionNote(blankVersion));
	}

	@Test
	public void testGarbageNegotiatedVersionIsNotSupported() {
		assertFalse(ElectrumX.isNegotiatedVersionSupported("not-a-version"));
		assertFalse(ElectrumX.isNegotiatedVersionSupported(null));
	}

	@Test
	public void testProtocolMinAboveMaximumIsRejected() {
		JSONObject featuresJson = new JSONObject();
		featuresJson.put("protocol_min", "1.5");

		Optional<String> note = ElectrumX.protocolMinRejectionNote(featuresJson);

		assertTrue(note.isPresent());
		assertEquals("new version: protocol_min = 1.5 > MAX_PROTOCOL_VERSION = 1.4", note.get());
	}

	@Test
	public void testProtocolMinBelowMinimumIsRejected() {
		JSONObject featuresJson = new JSONObject();
		featuresJson.put("protocol_min", "1.1");

		Optional<String> note = ElectrumX.protocolMinRejectionNote(featuresJson);

		assertTrue(note.isPresent());
		assertTrue(note.get().startsWith("old version: protocol_min = 1.1"));
	}

	@Test
	public void testSupportedProtocolMinIsAccepted() {
		JSONObject featuresJson = new JSONObject();
		featuresJson.put("protocol_min", "1.4");

		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(featuresJson));

		featuresJson.put("protocol_min", "1.2");
		assertEquals(Optional.empty(), ElectrumX.protocolMinRejectionNote(featuresJson));
	}

	@Test
	public void testMissingProtocolMinIsRejected() {
		Optional<String> note = ElectrumX.protocolMinRejectionNote(new JSONObject());

		assertTrue(note.isPresent());
		assertEquals("server version not available: protocol_min", note.get());
	}

	@Test
	public void testMaximumProtocolVersionIsOneFour() {
		assertEquals(1.4d, ElectrumX.MAX_PROTOCOL_VERSION, 0.0001d);
	}
}
