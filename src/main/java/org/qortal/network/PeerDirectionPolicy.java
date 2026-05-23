package org.qortal.network;

final class PeerDirectionPolicy {

	enum DuplicateConnectionDecision {
		KEEP_EXISTING,
		REPLACE_EXISTING
	}

	private PeerDirectionPolicy() {
	}

	static boolean shouldBeOutbound(String ourNodeId, String theirNodeId) {
		return ourNodeId.compareTo(theirNodeId) < 0;
	}

	static boolean shouldKeepSinglePeerAsFallback(boolean peerOutbound, boolean weShouldBeOutbound) {
		return peerOutbound != weShouldBeOutbound;
	}

	static boolean shouldAttemptPreferredOutboundReplacement(boolean existingPeerOutbound, boolean weShouldBeOutbound,
			boolean outboundRecentlyFailed) {
		return weShouldBeOutbound && !existingPeerOutbound && !outboundRecentlyFailed;
	}

	static DuplicateConnectionDecision decideDuplicate(boolean existingPeerUsable, boolean existingPeerOutbound,
			boolean newPeerOutbound, boolean weShouldBeOutbound) {
		if (!existingPeerUsable)
			return DuplicateConnectionDecision.REPLACE_EXISTING;

		boolean existingDirectionCorrect = existingPeerOutbound == weShouldBeOutbound;
		if (existingDirectionCorrect)
			return DuplicateConnectionDecision.KEEP_EXISTING;

		boolean newDirectionCorrect = newPeerOutbound == weShouldBeOutbound;
		if (newDirectionCorrect)
			return DuplicateConnectionDecision.REPLACE_EXISTING;

		return DuplicateConnectionDecision.KEEP_EXISTING;
	}
}
