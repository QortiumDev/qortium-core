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
