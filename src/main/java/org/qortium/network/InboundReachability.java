package org.qortium.network;

import org.qortium.settings.Settings;

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
		if (!Settings.getInstance().isIPAllowed())
			return false; // I2P-only: never advertise direct IP reachability

		if (!this.listenSocketAvailable)
			return false;

		return this.portMapped
				|| hasConfiguredExternalAddress()
				|| this.hasRecentInboundHandshake(System.currentTimeMillis());
	}

	/**
	 * Transport-aware direct-connectability for QDN data fetching.
	 * <p>
	 * {@link #canAcceptInbound()} only describes clearnet (IP) reachability and must stay that
	 * way so that node-status reporting and IP peer logic are not misled. However, a NAT'd node
	 * that has no usable external IP can still be dialled directly over I2P when it has a usable
	 * I2P data destination (a published b32). In that case it should advertise itself as directly
	 * connectable for QDN so requesters dial its data destination instead of dead-ending on an
	 * unreachable I2P relay.
	 *
	 * @param hasUsableI2PDestination true if this node currently has a usable (session-up) I2P destination
	 */
	boolean canAcceptInboundWithI2P(boolean hasUsableI2PDestination) {
		return this.canAcceptInbound() || hasUsableI2PDestination;
	}

	boolean canAcceptInboundData(boolean hasUsableI2PDataDestination) {
		return this.canAcceptInboundWithI2P(hasUsableI2PDataDestination);
	}

	/**
	 * A node with a configured external IP address (e.g. a public-IP seed/VPS with an
	 * open or forwarded port) is reachable from outside even when UPnP is disabled and
	 * no inbound handshake has arrived within the recent window, so it should still
	 * advertise itself as directly connectable.
	 */
	private static boolean hasConfiguredExternalAddress() {
		String externalIpAddress = Settings.getInstance().getOurExternalIpAddress();
		return externalIpAddress != null && !externalIpAddress.isBlank();
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
