package org.qortium.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PeerDirectionState {

	private static final int OUTBOUND_FAILURE_THRESHOLD = 3;
	private static final long OUTBOUND_FAILURE_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes

	/*
	 * Chain and data peers intentionally share direction-mismatch backoff. The data layer used to
	 * allow longer backoff because QDN could tolerate asymmetry, but direction is now a shared
	 * safety rule: both layers need the same recovery cadence and bootstrap/fixed-peer exemptions.
	 */
	private static final long DIRECTION_MISMATCH_BASE_BACKOFF = 2 * 60 * 1000L; // 2 minutes
	private static final long DIRECTION_MISMATCH_MAX_BACKOFF = 30 * 60 * 1000L; // 30 minutes

	private final Map<String, OutboundFailureInfo> outboundFailures = new ConcurrentHashMap<>();
	private final Map<String, OutboundFailureInfo> outboundFailuresByNodeId = new ConcurrentHashMap<>();
	private final Map<String, DirectionMismatchInfo> directionMismatchByNodeId = new ConcurrentHashMap<>();

	int recordOutboundFailure(String peerAddress, String nodeId) {
		OutboundFailureInfo info;
		if (nodeId != null) {
			info = this.outboundFailuresByNodeId.computeIfAbsent(nodeId, k -> new OutboundFailureInfo());
		} else {
			String peerIP = PeerAddress.fromString(peerAddress).getHost();
			info = this.outboundFailures.computeIfAbsent(peerIP, k -> new OutboundFailureInfo());
		}

		synchronized (info) {
			if (info.firstFailureTimestamp == 0)
				info.firstFailureTimestamp = System.currentTimeMillis();

			info.failureCount++;
			info.lastFailureTimestamp = System.currentTimeMillis();
			return info.failureCount;
		}
	}

	boolean hasRecentOutboundFailures(String nodeId, String peerIP) {
		return hasRecentOutboundFailure(nodeId, peerIP, OUTBOUND_FAILURE_THRESHOLD);
	}

	boolean hasRecentOutboundFailureEvidence(String nodeId, String peerIP) {
		return hasRecentOutboundFailure(nodeId, peerIP, 1);
	}

	private boolean hasRecentOutboundFailure(String nodeId, String peerIP, int minimumFailureCount) {
		long now = System.currentTimeMillis();

		if (nodeId != null) {
			OutboundFailureInfo info = this.outboundFailuresByNodeId.get(nodeId);
			if (info != null) {
				synchronized (info) {
					if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
						this.outboundFailuresByNodeId.remove(nodeId);
						return false;
					}

					return info.failureCount >= minimumFailureCount;
				}
			}
		}

		if (peerIP != null) {
			OutboundFailureInfo info = this.outboundFailures.get(peerIP);
			if (info != null) {
				synchronized (info) {
					if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
						this.outboundFailures.remove(peerIP);
						return false;
					}

					return info.failureCount >= minimumFailureCount;
				}
			}
		}

		return false;
	}

	int clearOutboundFailures(String peerIP, String nodeId) {
		int removedCount = 0;

		if (peerIP != null) {
			OutboundFailureInfo removed = this.outboundFailures.remove(peerIP);
			if (removed != null)
				removedCount = Math.max(removedCount, removed.failureCount);
		}

		if (nodeId != null) {
			OutboundFailureInfo removedById = this.outboundFailuresByNodeId.remove(nodeId);
			if (removedById != null)
				removedCount = Math.max(removedCount, removedById.failureCount);
		}

		return removedCount;
	}

	int cleanupStaleOutboundFailures() {
		if (this.outboundFailures.isEmpty() && this.outboundFailuresByNodeId.isEmpty())
			return 0;

		long now = System.currentTimeMillis();
		int removed = 0;

		var iterator = this.outboundFailures.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			OutboundFailureInfo info = entry.getValue();
			synchronized (info) {
				if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
					iterator.remove();
					removed++;
				}
			}
		}

		var nodeIdIterator = this.outboundFailuresByNodeId.entrySet().iterator();
		while (nodeIdIterator.hasNext()) {
			var entry = nodeIdIterator.next();
			OutboundFailureInfo info = entry.getValue();
			synchronized (info) {
				if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
					nodeIdIterator.remove();
					removed++;
				}
			}
		}

		return removed;
	}

	DirectionMismatchRecord recordDirectionMismatch(String nodeId) {
		DirectionMismatchInfo info = this.directionMismatchByNodeId.computeIfAbsent(nodeId, k -> new DirectionMismatchInfo());

		synchronized (info) {
			if (info.firstMismatch == 0)
				info.firstMismatch = System.currentTimeMillis();

			info.count++;
			info.lastMismatch = System.currentTimeMillis();
			return new DirectionMismatchRecord(info.count, info.getBackoffDuration());
		}
	}

	boolean hasRecentDirectionMismatch(String nodeId) {
		DirectionMismatchInfo info = this.directionMismatchByNodeId.get(nodeId);
		if (info == null)
			return false;

		long now = System.currentTimeMillis();

		synchronized (info) {
			if (now - info.lastMismatch > info.getBackoffDuration()) {
				this.directionMismatchByNodeId.remove(nodeId);
				return false;
			}

			return true;
		}
	}

	int cleanupStaleDirectionMismatches() {
		long now = System.currentTimeMillis();
		int removed = 0;

		var mismatchIterator = this.directionMismatchByNodeId.entrySet().iterator();
		while (mismatchIterator.hasNext()) {
			var entry = mismatchIterator.next();
			DirectionMismatchInfo info = entry.getValue();
			synchronized (info) {
				if (now - info.lastMismatch > info.getBackoffDuration()) {
					mismatchIterator.remove();
					removed++;
				}
			}
		}

		return removed;
	}

	int clearDirectionMismatch(String nodeId) {
		DirectionMismatchInfo removed = this.directionMismatchByNodeId.remove(nodeId);
		return removed == null ? 0 : removed.count;
	}

	static final class DirectionMismatchRecord {
		final int count;
		final long backoffDuration;

		private DirectionMismatchRecord(int count, long backoffDuration) {
			this.count = count;
			this.backoffDuration = backoffDuration;
		}
	}

	private static class OutboundFailureInfo {
		int failureCount = 0;
		long firstFailureTimestamp = 0;
		long lastFailureTimestamp = 0;
	}

	private static class DirectionMismatchInfo {
		int count = 0;
		long firstMismatch = 0;
		long lastMismatch = 0;

		long getBackoffDuration() {
			return Math.min(DIRECTION_MISMATCH_BASE_BACKOFF * (1L << (count - 1)),
					DIRECTION_MISMATCH_MAX_BACKOFF);
		}
	}
}
