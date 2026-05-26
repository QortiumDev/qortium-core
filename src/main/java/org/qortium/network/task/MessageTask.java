package org.qortium.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.network.Handshake;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
import org.qortium.network.message.Message;
import org.qortium.utils.ExecuteProduceConsume.Task;

public class MessageTask implements Task {
    private static final int MAX_CHAINED_MESSAGES = 16;

    private final Peer peer;
    private final Message nextMessage;
    private final int network;
    private final String name;
    private static final Logger LOGGER = LogManager.getLogger(MessageTask.class);

    public MessageTask(Peer peer, Message nextMessage, int network) {
        this.peer = peer;
        this.peer.setPeerType(network);
        this.nextMessage = nextMessage;
        this.network = network;
        this.name = "MessageTask::" + peer + "::" + nextMessage.getType();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        Message message = this.nextMessage;
        int processed = 0;

        while (message != null) {
            boolean wasHandshaking = peer.getHandshakeStatus() != Handshake.COMPLETED;
            routeMessage(message);
            processed++;

            if (!wasHandshaking) {
                return;
            }

            if (processed >= MAX_CHAINED_MESSAGES) {
                return;
            }

            Task nextTask = peer.getMessageTask(this.network);
            if (!(nextTask instanceof MessageTask)) {
                return;
            }

            message = ((MessageTask) nextTask).nextMessage;
        }
    }

    private void routeMessage(Message message) {
        try {
            LOGGER.trace("Routing Message to network {}", peer.getPeerType());

            if (peer.getPeerType() == Peer.NETWORKDATA)
                NetworkData.getInstance().onMessage(peer, message);
            else
                Network.getInstance().onMessage(peer, message);
        } finally {
            // Clears the handshake gate so already-queued follow-up messages can be scheduled.
            peer.resetHandshakeMessagePending();
        }
    }
}
