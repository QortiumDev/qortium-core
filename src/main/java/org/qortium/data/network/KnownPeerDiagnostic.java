package org.qortium.data.network;

import org.qortium.network.PeerAddress;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class KnownPeerDiagnostic {

	public enum Reason {
		RECENT_CONNECT_FAILURE,
		SELF,
		LOCAL_I2P_ADDRESS,
		ALREADY_CONNECTED,
		ALREADY_CONNECTED_BY_NODE_ID,
		ALREADY_CONNECTING_I2P,
		I2P_SESSION_DOWN,
		I2P_ALTERNATIVE_ALREADY_CONNECTED,
		DIRECTION_MISMATCH,
		TRANSPORT_NOT_ALLOWED
	}

	public enum Tag {
		FIXED_PEER,
		INITIAL_PEER,
		INITIAL_DATA_PEER,
		NETWORK_FALLBACK_CANDIDATE
	}

	public String address;
	public String transport;
	public Long addedWhen;
	public String addedBy;
	public Long lastAttempted;
	public Long lastConnected;
	public Long lastMisbehaved;
	public int failedSyncCount;
	public boolean connected;
	public boolean handshaked;
	public Boolean outbound;
	public String nodeId;
	public boolean connectable;
	public boolean inBackoff;
	public boolean isolationRetryCandidate;
	public Long backoffUntil;
	public List<Reason> reasons = new ArrayList<>();
	public List<Tag> tags = new ArrayList<>();

	public KnownPeerDiagnostic() {
	}

	public KnownPeerDiagnostic(PeerData peerData) {
		PeerAddress address = peerData.getAddress();
		this.address = address.toString();
		this.transport = address.isI2P() ? "I2P" : "IP";
		this.addedWhen = peerData.getAddedWhen();
		this.addedBy = peerData.getAddedBy();
		this.lastAttempted = peerData.getLastAttempted();
		this.lastConnected = peerData.getLastConnected();
		this.lastMisbehaved = peerData.getLastMisbehaved();
		this.failedSyncCount = peerData.getFailedSyncCount();
	}

	public boolean hasOnlyBackoffReason() {
		return this.reasons.size() == 1 && this.reasons.contains(Reason.RECENT_CONNECT_FAILURE);
	}

}
