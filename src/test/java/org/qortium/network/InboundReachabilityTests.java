package org.qortium.network;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InboundReachabilityTests {

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
