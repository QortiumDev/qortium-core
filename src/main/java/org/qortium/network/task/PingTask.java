package org.qortium.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.network.Peer;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageType;
import org.qortium.network.message.PingMessage;
import org.qortium.settings.Settings;
import org.qortium.utils.ExecuteProduceConsume.Task;
import org.qortium.utils.NTP;

public class PingTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PingTask.class);

    private final Peer peer;
    private final Long now;
    private final String name;

    public PingTask(Peer peer, Long now) {
        this.peer = peer;
        this.now = now;
        this.name = "PingTask::" + peer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
		try {
			LOGGER.trace("[{}] Sending PING to peer {}", peer.getPeerConnectionId(), peer);

			PingMessage pingMessage = new PingMessage();
			// Use a short timeout by default - if peer doesn't respond to ping quickly, disconnect.
			// Configurable via peerPingTimeoutMillis (default 4s) so slow-link operators can widen this
			// window without weakening dead-peer detection for everyone else.
			Message message = peer.getResponseWithTimeout(pingMessage, Settings.getInstance().getPeerPingTimeoutMillis());

			if (message == null || message.getType() != MessageType.PING) {
				// Missed pings only disconnect after N CONSECUTIVE misses ("three strikes", default 3):
				// one late PONG on a saturated link means slow, not dead. Genuinely dead TCP connections
				// are already torn down independently by socket errors, and a truly unresponsive (zombie)
				// peer is removed after N sequential attempts. Set peerPingFailureThreshold=1 to restore
				// the previous instant-disconnect-on-first-miss behavior.
				int threshold = Settings.getInstance().getPeerPingFailureThreshold();
				int misses = peer.recordMissedPing();

				if (shouldDisconnectAfterMiss(misses, threshold)) {
					LOGGER.trace("[{}] Didn't receive reply from {} for PING ID {} ({}/{} consecutive misses)",
							peer.getPeerConnectionId(), peer, pingMessage.getId(), misses, threshold);
					peer.disconnect("no ping received");
				} else {
					LOGGER.debug("[{}] Ping missed for peer {} ({}/{}), will retry on next ping cycle",
							peer.getPeerConnectionId(), peer, misses, threshold);
				}
				return;
			}

			peer.resetMissedPings();

			long rtt = NTP.getTime() - now;
			LOGGER.trace("[{}] Received PONG from peer {} (RTT: {}ms)", peer.getPeerConnectionId(), peer, rtt);
			peer.setLastPing(rtt);
		} finally {
			peer.completePingTask();
		}
    }

    /**
     * Pure decision helper (unit-testable): should this peer be disconnected given its current
     * consecutive-miss count and the configured failure threshold?
     */
    static boolean shouldDisconnectAfterMiss(int consecutiveMisses, int threshold) {
        return consecutiveMisses >= threshold;
    }
}
