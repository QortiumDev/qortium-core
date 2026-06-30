package org.qortium.network;

import org.junit.Test;
import org.qortium.network.PeerDirectionPolicy.DuplicateConnectionDecision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerDirectionPolicyTests {

	@Test
	public void testLowerNodeIdPrefersOutbound() {
		assertTrue(PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb"));
		assertFalse(PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa"));
	}

	@Test
	public void testReachablePairKeepsNodeIdTieBreak() {
		assertTrue(PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", true, false));
		assertFalse(PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa", true, false));
	}

	@Test
	public void testUnreachableLocalNodePrefersOutboundToKnownDialablePeer() {
		assertTrue(PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa", false, true, false));
	}

	@Test
	public void testReachableNodeAcceptsLikelyUnreachablePeerInbound() {
		assertFalse(PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", true, true));
	}

	@Test
	public void testUnknownReachabilityKeepsNodeIdTieBreak() {
		assertTrue(PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", false, false));
		assertFalse(PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa", false, false));
	}

	@Test
	public void testMutualUnreachableEvidenceKeepsNodeIdTieBreak() {
		assertTrue(PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", false, true));
		assertFalse(PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa", false, true));
	}

	@Test
	public void testReachabilityAsymmetryDedupesToSameConnection() {
		boolean unreachableSideShouldBeOutbound = PeerDirectionPolicy.shouldBeOutbound("bbb", "aaa", false, false);
		boolean reachableSideShouldBeOutbound = PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", true, false);

		assertEquals(DuplicateConnectionDecision.KEEP_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, false, true, unreachableSideShouldBeOutbound));
		assertEquals(DuplicateConnectionDecision.KEEP_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, true, false, reachableSideShouldBeOutbound));
	}

	@Test
	public void testLikelyUnreachablePeerInboundWinsDuplicateDecision() {
		boolean weShouldBeOutbound = PeerDirectionPolicy.shouldBeOutbound("aaa", "bbb", true, true);
		assertEquals(DuplicateConnectionDecision.REPLACE_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, true, false, weShouldBeOutbound));
	}

	@Test
	public void testSingleWrongDirectionPeerIsKeptAsFallback() {
		assertTrue(PeerDirectionPolicy.shouldKeepSinglePeerAsFallback(false, true));
		assertTrue(PeerDirectionPolicy.shouldKeepSinglePeerAsFallback(true, false));
		assertFalse(PeerDirectionPolicy.shouldKeepSinglePeerAsFallback(true, true));
	}

	@Test
	public void testPreferredDuplicateReplacesExistingFallback() {
		assertEquals(DuplicateConnectionDecision.REPLACE_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, false, true, true));
	}

	@Test
	public void testCorrectExistingRejectsWrongDirectionDuplicate() {
		assertEquals(DuplicateConnectionDecision.KEEP_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, true, false, true));
	}

	@Test
	public void testWrongDirectionDuplicateIsRejectedWhenExistingIsUsable() {
		assertEquals(DuplicateConnectionDecision.KEEP_EXISTING,
				PeerDirectionPolicy.decideDuplicate(true, true, true, false));
	}

	@Test
	public void testStaleExistingIsAlwaysReplaced() {
		assertEquals(DuplicateConnectionDecision.REPLACE_EXISTING,
				PeerDirectionPolicy.decideDuplicate(false, true, true, false));
	}

	@Test
	public void testPreferredOutboundAttemptOnlyForInboundFallback() {
		assertTrue(PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(false, true, false));
		assertFalse(PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(true, true, false));
		assertFalse(PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(false, false, false));
		assertFalse(PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(false, true, true));
	}
}
