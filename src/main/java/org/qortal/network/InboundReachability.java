package org.qortal.network;

final class InboundReachability {

	private static final long RECENT_INBOUND_HANDSHAKE_WINDOW = 60 * 60 * 1000L;

	private volatile boolean listenSocketAvailable;
	private volatile boolean portMapped;
	private volatile long lastInboundHandshakeTimestamp = -1L;

	void setListenSocketAvailable(boolean listenSocketAvailable) {
		this.listenSocketAvailable = listenSocketAvailable;
	}

	void setPortMapped(boolean portMapped) {
		this.portMapped = portMapped;
	}

	void recordInboundHandshake() {
		this.lastInboundHandshakeTimestamp = System.currentTimeMillis();
	}

	boolean canAcceptInbound() {
		if (!this.listenSocketAvailable)
			return false;

		return this.portMapped || this.hasRecentInboundHandshake(System.currentTimeMillis());
	}

	boolean hasRecentInboundHandshake(long now) {
		long lastInboundHandshakeTimestamp = this.lastInboundHandshakeTimestamp;
		return lastInboundHandshakeTimestamp > 0
				&& now - lastInboundHandshakeTimestamp <= RECENT_INBOUND_HANDSHAKE_WINDOW;
	}

	boolean isListenSocketAvailable() {
		return this.listenSocketAvailable;
	}

	boolean isPortMapped() {
		return this.portMapped;
	}

	long getLastInboundHandshakeTimestamp() {
		return this.lastInboundHandshakeTimestamp;
	}
}
