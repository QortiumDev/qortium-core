package org.qortium.controller.arbitrary;

import org.qortium.network.Peer;
import org.qortium.network.message.Message;

public class PeerMessage {
    Peer peer;
    Message message;

    public PeerMessage(Peer peer, Message message) {
        this.peer = peer;
        this.message = message;
    }

    public Peer getPeer() {
        return peer;
    }

    public Message getMessage() {
        return message;
    }
}
