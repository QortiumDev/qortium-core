package org.qortium.network;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerMaintenancePolicyTests {

	@Test
	public void testChainThresholdProtectsAllPeers() {
		List<PeerMaintenancePolicy.ChainCandidate<String>> candidates = Arrays.asList(
				chain("old", true, false, false, 20_000L, 10_000L));

		assertFalse(PeerMaintenancePolicy.selectChainRotation(candidates, 2, 2, 2, 1).isPresent());
	}

	@Test
	public void testChainSelectsMostOverdueEligibleOutboundPeer() {
		List<PeerMaintenancePolicy.ChainCandidate<String>> candidates = Arrays.asList(
				chain("oldest-not-most-overdue", true, false, false, 30_000L, 25_000L),
				chain("most-overdue", true, false, false, 25_000L, 10_000L),
				chain("inbound", false, false, false, 50_000L, 1_000L),
				chain("fixed", true, true, false, 50_000L, 1_000L),
				chain("syncing", true, false, true, 50_000L, 1_000L));

		Optional<PeerMaintenancePolicy.ChainCandidate<String>> selected =
				PeerMaintenancePolicy.selectChainRotation(candidates, 6, 2, 4, 2);

		assertTrue(selected.isPresent());
		assertEquals("most-overdue", selected.get().peer);
	}

	@Test
	public void testDataThresholdProtectsAllPeers() {
		List<PeerMaintenancePolicy.DataCandidate<String>> candidates = Arrays.asList(
				data("idle", true, 20_000L, false));

		assertFalse(PeerMaintenancePolicy.selectDataRotation(candidates, 2, 2, 2, 1, 10_000L).isPresent());
	}

	@Test
	public void testDataSelectsLeastRecentlyUsefulEligibleOutboundPeer() {
		List<PeerMaintenancePolicy.DataCandidate<String>> candidates = Arrays.asList(
				data("idle", true, 20_000L, false),
				data("most-idle", true, 40_000L, false),
				data("recent", true, 5_000L, false),
				data("inbound", false, 80_000L, false));

		Optional<PeerMaintenancePolicy.DataCandidate<String>> selected =
				PeerMaintenancePolicy.selectDataRotation(candidates, 5, 2, 4, 2, 10_000L);

		assertTrue(selected.isPresent());
		assertEquals("most-idle", selected.get().peer);
	}

	@Test
	public void testEveryActiveQdnWorkFormProtectsIdlePeer() {
		for (String workForm : Arrays.asList("in-flight chunk", "pending relay", "queued send",
				"output in progress", "active prefetch")) {
			List<PeerMaintenancePolicy.DataCandidate<String>> candidates = Arrays.asList(
					data(workForm, true, 60_000L, true));

			assertFalse(workForm, PeerMaintenancePolicy
					.selectDataRotation(candidates, 3, 1, 2, 1, 10_000L).isPresent());
		}
	}

	@Test
	public void testRoutineControlTrafficDoesNotEnterIdleSelectorAsActiveWork() {
		List<PeerMaintenancePolicy.DataCandidate<String>> candidates = Arrays.asList(
				data("routine-file-list-gossip", true, 60_000L, false));

		assertEquals("routine-file-list-gossip", PeerMaintenancePolicy
				.selectDataRotation(candidates, 3, 1, 2, 1, 10_000L).get().peer);
	}

	@Test
	public void testOutboundThresholdProtectsRotation() {
		assertFalse(PeerMaintenancePolicy.selectChainRotation(Arrays.asList(
				chain("old", true, false, false, 20_000L, 10_000L)),
				5, 1, 1, 2).isPresent());
		assertFalse(PeerMaintenancePolicy.selectDataRotation(Arrays.asList(
				data("idle", true, 20_000L, false)),
				5, 1, 1, 2, 10_000L).isPresent());
	}

	@Test
	public void testRotationCanReplacePeerAtOutboundTarget() {
		assertTrue(PeerMaintenancePolicy.selectChainRotation(Arrays.asList(
				chain("old", true, false, false, 20_000L, 10_000L)),
				5, 1, 2, 2).isPresent());
		assertTrue(PeerMaintenancePolicy.selectDataRotation(Arrays.asList(
				data("idle", true, 20_000L, false)),
				5, 1, 2, 2, 10_000L).isPresent());
	}

	@Test
	public void testRotationCooldownPrefersAlternativeButAllowsFallback() {
		List<String> peers = Arrays.asList("rotated", "alternative");
		assertEquals(Arrays.asList("alternative"), PeerMaintenancePolicy.preferOutsideRotationCooldown(
				peers, new HashSet<>(Arrays.asList("rotated"))));

		List<String> thinNetwork = Arrays.asList("rotated");
		assertTrue(thinNetwork == PeerMaintenancePolicy.preferOutsideRotationCooldown(
				thinNetwork, new HashSet<>(Arrays.asList("rotated"))));
	}

	private static PeerMaintenancePolicy.ChainCandidate<String> chain(String name, boolean outbound,
			boolean fixed, boolean syncing, long age, long maximumAge) {
		return new PeerMaintenancePolicy.ChainCandidate<>(name, outbound, fixed, syncing, age, maximumAge);
	}

	private static PeerMaintenancePolicy.DataCandidate<String> data(String name, boolean outbound,
			long idle, boolean activeWork) {
		return new PeerMaintenancePolicy.DataCandidate<>(name, outbound, idle, activeWork);
	}
}
