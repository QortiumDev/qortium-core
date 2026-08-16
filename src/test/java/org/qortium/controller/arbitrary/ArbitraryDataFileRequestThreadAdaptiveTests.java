package org.qortium.controller.arbitrary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ArbitraryDataFileRequestThreadAdaptiveTests extends Common {

	private static final String PEER_A = "198.51.100.10:24894";
	private static final String PEER_B = "198.51.100.11:24894";

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		ArbitraryDataFileRequestThread.resetAdaptiveWindowsForTesting();
	}

	@After
	public void afterTest() {
		ArbitraryDataFileRequestThread.resetAdaptiveWindowsForTesting();
	}

	@Test
	public void testLossIsIsolatedToOnePeer() {
		assertEquals(10, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_A));
		assertEquals(10, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_B));

		ArbitraryDataFileRequestThread.getInstance().onChunkRequestsExpired(List.of(PEER_A));

		assertEquals(5, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_A));
		assertEquals(10, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_B));
	}

	@Test
	public void testManyExpiredChunksCoalesceToOneLossPerInterval() {
		ArbitraryDataFileRequestThread.getInstance().onChunkRequestsExpired(
				List.of(PEER_A, PEER_A, PEER_A, PEER_A));
		ArbitraryDataFileRequestThread.getInstance().onChunkRequestsExpired(List.of(PEER_A));

		assertEquals("Repeated expiry reports in one interval must halve only once", 5,
				ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_A));

		ArbitraryDataFileRequestThread.finishAdaptiveIntervalForTesting();
		ArbitraryDataFileRequestThread.getInstance().onChunkRequestsExpired(List.of(PEER_A));
		assertEquals("A later interval is a new loss event", 2,
				ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_A));
	}

	@Test
	public void testCleanArrivalGrowsOnlyItsPeerAndLossSuppressesSameIntervalGrowth() {
		ArbitraryDataFileRequestThread.getInstance().onChunkReceived("sig", "hash-a", PEER_A);
		ArbitraryDataFileRequestThread.getInstance().onChunkReceived("sig", "hash-b", PEER_B);
		ArbitraryDataFileRequestThread.getInstance().onChunkRequestsExpired(List.of(PEER_A));

		ArbitraryDataFileRequestThread.finishAdaptiveIntervalForTesting();

		assertEquals(5, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_A));
		assertEquals(11, ArbitraryDataFileRequestThread.adaptiveWindowForTesting(PEER_B));
	}
}
