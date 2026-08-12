package org.qortium.test.settings;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.Test;
import org.qortium.settings.Settings;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the low-bandwidth networking knobs added for slow-link operators: peerPingTimeoutMillis,
 * qdnRequestTimeoutMillis, qdnInitialChunkBatchSize, qdnMaxChunkBatchSize, singleBlockResponseTimeout,
 * and blocksBatchResponseTimeout. Every default must match the previous hard-coded constant so ordinary
 * nodes are unaffected.
 *
 * Also covers the adaptive-behavior knobs added on top of that: peerPingFailureThreshold (three-strikes
 * ping policy) and qdnYieldDuringSync / qdnSyncYieldBatchSize (chain-first QDN yielding). Unlike the
 * knobs above, these two DEFAULTS deliberately change behavior (3 strikes instead of 1, yielding on) -
 * that is the point of this change, so their "defaults" assertions pin the new values, not old constants.
 *
 * Also covers the second adaptive-networking tranche's opt-out booleans: qdnAdaptiveBatching (feedback/AIMD
 * chunk batching, replacing the time-based ramp) and blocksBatchAutoDegrade (GET_BLOCKS auto-halving on
 * timeout). Both default to true (a deliberate behavior change, same as the tranche above), and both can be
 * set false to restore the exact previous fixed behavior.
 */
public class LowBandwidthNetworkSettingsTests {

	@Test
	public void testDefaultsMatchPreviousHardCodedConstants() throws Exception {
		Settings settings = load("{}");

		assertEquals(4000, settings.getPeerPingTimeoutMillis());
		assertEquals(12000, settings.getQdnRequestTimeoutMillis());
		assertEquals(10, settings.getQdnInitialChunkBatchSize());
		assertEquals(40, settings.getQdnMaxChunkBatchSize());
		assertEquals(4000, settings.getSingleBlockResponseTimeout());
		assertEquals(10000, settings.getBlocksBatchResponseTimeout());
	}

	@Test
	public void testAdaptiveBehaviorDefaults() throws Exception {
		Settings settings = load("{}");

		// Deliberate default change: three strikes instead of instant disconnect on first missed ping.
		assertEquals(3, settings.getPeerPingFailureThreshold());
		// Deliberate default change: QDN yields to chain sync by default.
		assertTrue(settings.isQdnYieldDuringSync());
		assertEquals(1, settings.getQdnSyncYieldBatchSize());
	}

	@Test
	public void testSecondTrancheDefaults() throws Exception {
		Settings settings = load("{}");

		// Deliberate default change: feedback-based (AIMD) batching replaces the time-based ramp.
		assertTrue(settings.isQdnAdaptiveBatching());
		// Deliberate default change: GET_BLOCKS auto-degrades on timeout instead of wasting the round.
		assertTrue(settings.isBlocksBatchAutoDegrade());
	}

	@Test
	public void testQdnAdaptiveBatchingCanBeDisabled() throws Exception {
		Settings settings = load("{\"qdnAdaptiveBatching\":false}");
		assertEquals(false, settings.isQdnAdaptiveBatching());
	}

	@Test
	public void testBlocksBatchAutoDegradeCanBeDisabled() throws Exception {
		Settings settings = load("{\"blocksBatchAutoDegrade\":false}");
		assertEquals(false, settings.isBlocksBatchAutoDegrade());
	}

	@Test
	public void testPeerPingFailureThresholdOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"peerPingFailureThreshold\":0}", "peerPingFailureThreshold must be between 1 and 10");
		assertInvalid("{\"peerPingFailureThreshold\":11}", "peerPingFailureThreshold must be between 1 and 10");
	}

	@Test
	public void testPeerPingFailureThresholdBoundaryValuesAreAccepted() throws Exception {
		// 1 restores the previous instant-disconnect-on-first-miss behavior.
		assertEquals(1, load("{\"peerPingFailureThreshold\":1}").getPeerPingFailureThreshold());
		assertEquals(10, load("{\"peerPingFailureThreshold\":10}").getPeerPingFailureThreshold());
	}

	@Test
	public void testQdnYieldDuringSyncCanBeDisabled() throws Exception {
		Settings settings = load("{\"qdnYieldDuringSync\":false}");
		assertEquals(false, settings.isQdnYieldDuringSync());
	}

	@Test
	public void testQdnSyncYieldBatchSizeOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"qdnSyncYieldBatchSize\":0}", "qdnSyncYieldBatchSize must be between 1 and 100");
		assertInvalid("{\"qdnSyncYieldBatchSize\":101}", "qdnSyncYieldBatchSize must be between 1 and 100");
	}

	@Test
	public void testQdnSyncYieldBatchSizeBoundaryValuesAreAccepted() throws Exception {
		assertEquals(1, load("{\"qdnSyncYieldBatchSize\":1}").getQdnSyncYieldBatchSize());
		assertEquals(100, load("{\"qdnSyncYieldBatchSize\":100}").getQdnSyncYieldBatchSize());
	}

	@Test
	public void testLowBandwidthProfileValuesAreAccepted() throws Exception {
		Settings settings = load("{"
				+ "\"peerPingTimeoutMillis\":25000,"
				+ "\"qdnRequestTimeoutMillis\":180000,"
				+ "\"qdnInitialChunkBatchSize\":1,"
				+ "\"qdnMaxChunkBatchSize\":1,"
				+ "\"singleBlockResponseTimeout\":20000,"
				+ "\"blocksBatchResponseTimeout\":60000"
				+ "}");

		assertEquals(25000, settings.getPeerPingTimeoutMillis());
		assertEquals(180000, settings.getQdnRequestTimeoutMillis());
		assertEquals(1, settings.getQdnInitialChunkBatchSize());
		assertEquals(1, settings.getQdnMaxChunkBatchSize());
		assertEquals(20000, settings.getSingleBlockResponseTimeout());
		assertEquals(60000, settings.getBlocksBatchResponseTimeout());
	}

	@Test
	public void testPeerPingTimeoutOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"peerPingTimeoutMillis\":499}", "peerPingTimeoutMillis must be between 500 and 60000");
		assertInvalid("{\"peerPingTimeoutMillis\":60001}", "peerPingTimeoutMillis must be between 500 and 60000");
	}

	@Test
	public void testPeerPingTimeoutBoundaryValuesAreAccepted() throws Exception {
		assertEquals(500, load("{\"peerPingTimeoutMillis\":500}").getPeerPingTimeoutMillis());
		assertEquals(60000, load("{\"peerPingTimeoutMillis\":60000}").getPeerPingTimeoutMillis());
	}

	@Test
	public void testQdnRequestTimeoutOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"qdnRequestTimeoutMillis\":2999}", "qdnRequestTimeoutMillis must be between 3000 and 600000");
		assertInvalid("{\"qdnRequestTimeoutMillis\":600001}", "qdnRequestTimeoutMillis must be between 3000 and 600000");
	}

	@Test
	public void testSingleBlockResponseTimeoutOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"singleBlockResponseTimeout\":999}", "singleBlockResponseTimeout must be between 1000 and 600000");
		assertInvalid("{\"singleBlockResponseTimeout\":600001}", "singleBlockResponseTimeout must be between 1000 and 600000");
	}

	@Test
	public void testBlocksBatchResponseTimeoutOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"blocksBatchResponseTimeout\":999}", "blocksBatchResponseTimeout must be between 1000 and 600000");
		assertInvalid("{\"blocksBatchResponseTimeout\":600001}", "blocksBatchResponseTimeout must be between 1000 and 600000");
	}

	@Test
	public void testQdnChunkBatchSizeOutOfRangeIsRejected() throws Exception {
		assertInvalid("{\"qdnInitialChunkBatchSize\":0}", "qdnInitialChunkBatchSize must be between 1 and 100");
		assertInvalid("{\"qdnInitialChunkBatchSize\":101,\"qdnMaxChunkBatchSize\":101}", "qdnInitialChunkBatchSize must be between 1 and 100");
		assertInvalid("{\"qdnMaxChunkBatchSize\":0}", "qdnMaxChunkBatchSize must be between 1 and 100");
		assertInvalid("{\"qdnMaxChunkBatchSize\":101}", "qdnMaxChunkBatchSize must be between 1 and 100");
	}

	@Test
	public void testQdnMaxChunkBatchSizeMustNotBeLessThanInitial() throws Exception {
		assertInvalid("{\"qdnInitialChunkBatchSize\":10,\"qdnMaxChunkBatchSize\":5}",
				"qdnMaxChunkBatchSize must not be less than qdnInitialChunkBatchSize");

		// Equal is allowed (a fixed, non-ramping batch size such as the low-bandwidth profile's 1/1).
		Settings settings = load("{\"qdnInitialChunkBatchSize\":10,\"qdnMaxChunkBatchSize\":10}");
		assertEquals(10, settings.getQdnInitialChunkBatchSize());
		assertEquals(10, settings.getQdnMaxChunkBatchSize());
	}

	private static void assertInvalid(String json, String expectedMessage) throws Exception {
		try {
			load(json);
			fail("Expected settings validation failure");
		} catch (RuntimeException e) {
			assertTrue("Expected message containing: " + expectedMessage + ", got: " + e.getMessage(),
					e.getMessage().contains(expectedMessage));
		}
	}

	private static Settings load(String json) throws Exception {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {Settings.class}, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		Settings settings = unmarshaller.unmarshal(new StreamSource(new StringReader(json)), Settings.class).getValue();

		Method validate = Settings.class.getDeclaredMethod("validate");
		validate.setAccessible(true);
		try {
			validate.invoke(settings);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException)
				throw (RuntimeException) e.getCause();
			throw e;
		}
		return settings;
	}
}
