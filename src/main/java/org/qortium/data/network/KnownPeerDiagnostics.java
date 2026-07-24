package org.qortium.data.network;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@XmlAccessorType(XmlAccessType.FIELD)
public class KnownPeerDiagnostics {

	public enum Layer {
		CHAIN,
		DATA
	}

	public Layer layer;
	public int knownCount;
	public int connectedCount;
	public int handshakedCount;
	public int outboundHandshakedCount;
	/** Effective startup capacity for the DATA layer; null for the CHAIN layer. */
	public Integer dataPeerCapacity;
	/** DATA-layer admissions reserved before a socket has joined connectedPeers. */
	public Integer pendingDataPeerAdmissions;
	public int connectableCount;
	public int backoffCount;
	public List<String> allowedTransports = new ArrayList<>();
	public boolean i2pSessionUp;
	public Integer qdnFallbackCandidateCount;
	public Map<KnownPeerDiagnostic.Reason, Integer> reasonCounts = new EnumMap<>(KnownPeerDiagnostic.Reason.class);
	public List<KnownPeerDiagnostic> peers = new ArrayList<>();

	public KnownPeerDiagnostics() {
	}

	public KnownPeerDiagnostics(Layer layer) {
		this.layer = layer;
	}

	public void addPeer(KnownPeerDiagnostic peer) {
		this.peers.add(peer);
		if (peer.connectable)
			this.connectableCount++;
		if (peer.inBackoff)
			this.backoffCount++;
		for (KnownPeerDiagnostic.Reason reason : peer.reasons)
			this.reasonCounts.merge(reason, 1, Integer::sum);
	}

}
