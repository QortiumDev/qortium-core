package org.qortium.network;

import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InboundReachabilityTests {

	/**
	 * canAcceptInbound() consults Settings.isIPAllowed(), so without pinning Settings these tests
	 * inherit whatever global instance an earlier test in the same JVM happened to install. That
	 * is why they passed locally and failed in CI - surefire simply ordered the suite differently.
	 */
	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCannotAcceptInboundWithoutListenSocket() {
		InboundReachability reachability = new InboundReachability();

		reachability.setPortMapped(true);
		reachability.recordInboundHandshake();

		assertFalse(reachability.canAcceptInbound());
	}

	@Test
	public void testPortMappingMakesInboundReachableWhenListening() {
		InboundReachability reachability = new InboundReachability();

		reachability.setListenSocketAvailable(true);
		reachability.setPortMapped(true);

		assertTrue(reachability.canAcceptInbound());
	}

	@Test
	public void testInboundHandshakeMakesInboundReachableWhenListening() {
		InboundReachability reachability = new InboundReachability();

		reachability.setListenSocketAvailable(true);
		reachability.recordInboundHandshake();

		assertTrue(reachability.canAcceptInbound());
		assertTrue(reachability.hasRecentInboundHandshake(System.currentTimeMillis()));
	}
}
