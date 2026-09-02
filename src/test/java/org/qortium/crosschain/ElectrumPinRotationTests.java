package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Test;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.crosschain.RefreshElectrumServers.PinDecision;
import org.qortium.crosschain.RefreshElectrumServers.PinOutcome;
import org.qortium.crosschain.RefreshElectrumServers.PinRotation;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Adopting a pin for an unpinned server is trust-on-first-use; adopting a <em>changed</em> pin is a fresh
 * trust decision, so it needs an explicit opt-in and an audit record.
 */
public class ElectrumPinRotationTests {

	private static final String PINNED_FINGERPRINT = "acd25d5ff8e227b264e07381790c79730f834a140e88b906c896187a670b7fde";
	private static final String LIVE_FINGERPRINT = "5b290af39c2eebfb999259f31b8214023f7fe4f33783eef3a264da8ed9b9778a";

	private static Server sslServer(String fingerprint) {
		return new Server("electrumx01.firo.org", ConnectionType.SSL, 50002, fingerprint);
	}

	@Test
	public void testUnchangedFingerprintKeepsTheServerAsIs() {
		Server server = sslServer(PINNED_FINGERPRINT);

		PinDecision decision = RefreshElectrumServers.decidePin(server, PINNED_FINGERPRINT, false);

		assertEquals(PinOutcome.UNCHANGED, decision.getOutcome());
		assertEquals(server, decision.getServer());
		assertEquals(PINNED_FINGERPRINT, decision.getServer().getCertificateSha256Fingerprint());
	}

	@Test
	public void testFingerprintComparisonIgnoresCase() {
		Server server = sslServer(PINNED_FINGERPRINT);

		PinDecision decision = RefreshElectrumServers.decidePin(server, PINNED_FINGERPRINT.toUpperCase(), false);

		assertEquals(PinOutcome.UNCHANGED, decision.getOutcome());
	}

	@Test
	public void testUnpinnedServerAdoptsTheLiveFingerprint() {
		PinDecision decision = RefreshElectrumServers.decidePin(sslServer(null), LIVE_FINGERPRINT, false);

		assertEquals(PinOutcome.PINNED, decision.getOutcome());
		assertNotNull(decision.getServer());
		assertEquals(LIVE_FINGERPRINT, decision.getServer().getCertificateSha256Fingerprint());
		assertNull(decision.getOldFingerprint());
	}

	@Test
	public void testRotatedFingerprintIsDroppedWithoutTheFlag() {
		PinDecision decision = RefreshElectrumServers.decidePin(sslServer(PINNED_FINGERPRINT), LIVE_FINGERPRINT, false);

		assertEquals(PinOutcome.ROTATION_REJECTED, decision.getOutcome());
		assertNull("a rejected rotation must not yield a server to verify", decision.getServer());
		assertEquals(PINNED_FINGERPRINT, decision.getOldFingerprint());
		assertEquals(LIVE_FINGERPRINT, decision.getNewFingerprint());
	}

	@Test
	public void testRotatedFingerprintIsRePinnedWithTheFlag() {
		PinDecision decision = RefreshElectrumServers.decidePin(sslServer(PINNED_FINGERPRINT), LIVE_FINGERPRINT, true);

		assertEquals(PinOutcome.ROTATION_ACCEPTED, decision.getOutcome());
		assertNotNull(decision.getServer());
		assertEquals(LIVE_FINGERPRINT, decision.getServer().getCertificateSha256Fingerprint());
		assertEquals(PINNED_FINGERPRINT, decision.getOldFingerprint());
		assertEquals(LIVE_FINGERPRINT, decision.getNewFingerprint());
	}

	@Test
	public void testProbeFailureKeepsTheExistingPin() {
		Server server = sslServer(PINNED_FINGERPRINT);

		PinDecision decision = RefreshElectrumServers.decidePin(server, null, true);

		assertEquals(PinOutcome.UNCHANGED, decision.getOutcome());
		assertEquals(server, decision.getServer());
		assertEquals(PINNED_FINGERPRINT, decision.getServer().getCertificateSha256Fingerprint());
	}

	@Test
	public void testPlaintextServersAreNeverPinned() {
		Server server = new Server("lbc.electrum1.cipig.net", ConnectionType.TCP, 10067, null);

		PinDecision decision = RefreshElectrumServers.decidePin(server, LIVE_FINGERPRINT, true);

		assertEquals(PinOutcome.UNCHANGED, decision.getOutcome());
		assertEquals(server, decision.getServer());
		assertNull(decision.getServer().getCertificateSha256Fingerprint());
	}

	@Test
	public void testAcceptedRotationIsRecordedInTheAuditManifest() throws Exception {
		Instant probedAt = Instant.parse("2026-09-01T12:00:00Z");
		String json = RefreshElectrumServers.rotationsManifestJson(List.of(
				new PinRotation("electrumx01.firo.org", 50002, PINNED_FINGERPRINT, LIVE_FINGERPRINT, probedAt)));

		JSONObject manifest = (JSONObject) new JSONParser().parse(json);
		JSONArray rotations = (JSONArray) manifest.get("acceptedRotations");

		assertNotNull(manifest.get("generatedAt"));
		assertEquals(1, rotations.size());

		JSONObject rotation = (JSONObject) rotations.get(0);
		assertEquals("electrumx01.firo.org", rotation.get("host"));
		assertEquals(50002L, rotation.get("port"));
		assertEquals(PINNED_FINGERPRINT, rotation.get("oldFingerprint"));
		assertEquals(LIVE_FINGERPRINT, rotation.get("newFingerprint"));
		assertEquals("2026-09-01T12:00:00Z", rotation.get("probedAt"));
	}

	@Test
	public void testEmptyManifestIsStillValidJson() throws Exception {
		JSONObject manifest = (JSONObject) new JSONParser().parse(RefreshElectrumServers.rotationsManifestJson(List.of()));

		assertEquals(0, ((JSONArray) manifest.get("acceptedRotations")).size());
	}
}
