package org.qortium.network;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic candidate selection for voluntary peer rotation.
 *
 * <p>The controller owns the maintenance cadence. These selectors only decide
 * whether one peer is eligible on a given pass.</p>
 */
final class PeerMaintenancePolicy {

	private PeerMaintenancePolicy() {
	}

	static final class ChainCandidate<T> {
		final T peer;
		final boolean outbound;
		final boolean fixed;
		final boolean syncing;
		final long connectionAgeMillis;
		final long maximumAgeMillis;

		ChainCandidate(T peer, boolean outbound, boolean fixed, boolean syncing,
				long connectionAgeMillis, long maximumAgeMillis) {
			this.peer = peer;
			this.outbound = outbound;
			this.fixed = fixed;
			this.syncing = syncing;
			this.connectionAgeMillis = connectionAgeMillis;
			this.maximumAgeMillis = maximumAgeMillis;
		}

		long overdueMillis() {
			return this.connectionAgeMillis - this.maximumAgeMillis;
		}
	}

	static final class DataCandidate<T> {
		final T peer;
		final boolean outbound;
		final long idleMillis;
		final boolean activeWork;

		DataCandidate(T peer, boolean outbound, long idleMillis, boolean activeWork) {
			this.peer = peer;
			this.outbound = outbound;
			this.idleMillis = idleMillis;
			this.activeWork = activeWork;
		}
	}

	static <T> Optional<ChainCandidate<T>> selectChainRotation(List<ChainCandidate<T>> candidates,
			int handshakedCount, int minimumPeers, int outboundCount, int minimumOutboundPeers) {
		if (handshakedCount <= minimumPeers || outboundCount < minimumOutboundPeers)
			return Optional.empty();

		return candidates.stream()
				.filter(candidate -> candidate.outbound)
				.filter(candidate -> !candidate.fixed)
				.filter(candidate -> !candidate.syncing)
				.filter(candidate -> candidate.overdueMillis() > 0L)
				.max(Comparator.comparingLong(ChainCandidate::overdueMillis));
	}

	static <T> Optional<DataCandidate<T>> selectDataRotation(List<DataCandidate<T>> candidates,
			int handshakedCount, int minimumPeers, int outboundCount, int minimumOutboundPeers,
			long idleThresholdMillis) {
		if (handshakedCount <= minimumPeers || outboundCount < minimumOutboundPeers)
			return Optional.empty();

		return candidates.stream()
				.filter(candidate -> candidate.outbound)
				.filter(candidate -> !candidate.activeWork)
				.filter(candidate -> candidate.idleMillis > idleThresholdMillis)
				.max(Comparator.comparingLong(candidate -> candidate.idleMillis));
	}

	static <T> List<T> preferOutsideRotationCooldown(List<T> candidates, Set<T> coolingDown) {
		List<T> alternatives = candidates.stream()
				.filter(candidate -> !coolingDown.contains(candidate))
				.collect(Collectors.toList());
		return alternatives.isEmpty() ? candidates : alternatives;
	}
}
