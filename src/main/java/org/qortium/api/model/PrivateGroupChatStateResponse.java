package org.qortium.api.model;

import org.qortium.chat.PrivateGroupChatPublicService;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.chat.crypto.PrivateGroupChatMembership;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatStateResponse {

	public final int txGroupId;
	public final boolean exists;
	public final Boolean isOpen;
	public final String epochId;
	public final int memberCount;
	public final List<String> memberPublicKeys;
	public final boolean allPublicKeysKnown;
	public final List<String> missingPublicKeyAddresses;
	public final int qpgcVersion;
	public final int maxV1Members;
	public final boolean available;
	public final PrivateGroupChatPublicService.UnavailableReason unavailableReason;

	protected PrivateGroupChatStateResponse() {
		this.txGroupId = 0;
		this.exists = false;
		this.isOpen = null;
		this.epochId = null;
		this.memberCount = 0;
		this.memberPublicKeys = List.of();
		this.allPublicKeysKnown = false;
		this.missingPublicKeyAddresses = List.of();
		this.qpgcVersion = PrivateGroupChatEnvelope.VERSION;
		this.maxV1Members = PrivateGroupChatMembership.MAX_V1_MEMBERS;
		this.available = false;
		this.unavailableReason = null;
	}

	public PrivateGroupChatStateResponse(PrivateGroupChatPublicService.GroupState state) {
		this.txGroupId = state.getGroupId();
		this.exists = state.exists();
		this.isOpen = state.isOpen();
		this.epochId = state.getEpochId() == null ? null : Base58.encode(state.getEpochId());
		this.memberCount = state.getMemberCount();
		List<String> encodedKeys = new ArrayList<>(state.getMemberPublicKeys().size());
		for (byte[] publicKey : state.getMemberPublicKeys())
			encodedKeys.add(Base58.encode(publicKey));
		this.memberPublicKeys = List.copyOf(encodedKeys);
		this.allPublicKeysKnown = state.areAllPublicKeysKnown();
		this.missingPublicKeyAddresses = List.copyOf(state.getMissingPublicKeyAddresses());
		this.qpgcVersion = PrivateGroupChatEnvelope.VERSION;
		this.maxV1Members = PrivateGroupChatMembership.MAX_V1_MEMBERS;
		this.available = state.isAvailable();
		this.unavailableReason = state.getUnavailableReason();
	}
}
