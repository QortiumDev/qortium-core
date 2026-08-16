package org.qortium.controller;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControllerQdnCatchUpTests {

	@Test
	public void testNoQuorumRequirementWhenChainIsNotBehind() {
		assertFalse(Controller.isQdnChainCatchUpActive(false, false));
	}

	@Test
	public void testActiveSynchronizationYieldsQdn() {
		assertTrue(Controller.isQdnChainCatchUpActive(true, false));
	}

	@Test
	public void testOneFreshHigherPeerYieldsQdnWithoutQuorum() {
		assertTrue(Controller.isQdnChainCatchUpActive(false, true));
	}
}
