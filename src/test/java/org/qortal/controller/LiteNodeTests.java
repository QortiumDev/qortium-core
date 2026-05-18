package org.qortal.controller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiteNodeTests {

	@Test
	public void testLiteDataCapabilityRequiresSupportedNumericVersion() {
		assertFalse(LiteNode.isSupportedLiteDataCapability(null));
		assertFalse(LiteNode.isSupportedLiteDataCapability("1"));
		assertFalse(LiteNode.isSupportedLiteDataCapability(0));

		assertTrue(LiteNode.isSupportedLiteDataCapability(1));
		assertTrue(LiteNode.isSupportedLiteDataCapability(2L));
	}

	@Test
	public void testTransactionLimitNormalization() {
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST, LiteNode.normalizeTransactionLimit(0));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST, LiteNode.normalizeTransactionLimit(-1));
		assertEquals(25, LiteNode.normalizeTransactionLimit(25));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST,
				LiteNode.normalizeTransactionLimit(LiteNode.MAX_TRANSACTIONS_PER_REQUEST));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST,
				LiteNode.normalizeTransactionLimit(LiteNode.MAX_TRANSACTIONS_PER_REQUEST + 1));
	}

	@Test
	public void testStatsStartAtZero() {
		LiteNode liteNode = new LiteNode();

		assertEquals(0, liteNode.stats.requests.get());
		assertEquals(0, liteNode.stats.noCapablePeers.get());
		assertEquals(0, liteNode.stats.peerAttempts.get());
		assertEquals(0, liteNode.stats.emptyResponses.get());
		assertEquals(0, liteNode.stats.unexpectedResponses.get());
		assertEquals(0, liteNode.stats.successfulResponses.get());
		assertEquals(0, liteNode.stats.interruptedRequests.get());
	}

}
