package org.qortium.data.arbitrary;

import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;

/**
 * Stores response information for arbitrary file list requests.
 *
 * Note: Inherits from ArbitraryRelayInfo which stores PeerData (lightweight)
 * instead of Peer (heavy) to prevent memory leaks.
 */
public class ArbitraryFileListResponseInfo extends ArbitraryRelayInfo {

    // When the holder claims direct connectability, the sender of the file list response can
    // still relay the chunks if the direct dial fails. Stored as lightweight PeerData for the
    // same memory-leak reason as the parent's peer field. Null when no fallback exists.
    private PeerData relayFallbackPeerData;

    public ArbitraryFileListResponseInfo(String hash58, String signature58, Peer peer, String nodeId, Long timestamp, Long requestTime, Integer requestHops, Boolean isDirectConnectable) {
        super(hash58, signature58, peer, nodeId, timestamp, requestTime, requestHops, isDirectConnectable);
        //    Chunk , File       , peer
    }

    public void setRelayFallbackPeerData(PeerData relayFallbackPeerData) {
        this.relayFallbackPeerData = relayFallbackPeerData;
    }

    public PeerData getRelayFallbackPeerData() {
        return this.relayFallbackPeerData;
    }

}
