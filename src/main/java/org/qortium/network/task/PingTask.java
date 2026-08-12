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
        LOGGER.trace("[{}] Sending PING to peer {}", peer.getPeerConnectionId(), peer);
        
        PingMessage pingMessage = new PingMessage();
        // Use a short timeout by default - if peer doesn't respond to ping quickly, disconnect.
        // Configurable via peerPingTimeoutMillis (default 4s) so slow-link operators can widen this
        // window without weakening dead-peer detection for everyone else.
        Message message = peer.getResponseWithTimeout(pingMessage, Settings.getInstance().getPeerPingTimeoutMillis());

        if (message == null || message.getType() != MessageType.PING) {
            LOGGER.trace("[{}] Didn't receive reply from {} for PING ID {}",
                    peer.getPeerConnectionId(), peer, pingMessage.getId());
            peer.disconnect("no ping received");
            return;
        }

        long rtt = NTP.getTime() - now;
        LOGGER.trace("[{}] Received PONG from peer {} (RTT: {}ms)", peer.getPeerConnectionId(), peer, rtt);
        peer.setLastPing(rtt);
    }
}
