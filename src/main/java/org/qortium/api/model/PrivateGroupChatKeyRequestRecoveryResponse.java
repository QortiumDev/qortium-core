package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.chat.PrivateGroupChatService;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyRequestRecoveryResponse {

	@Schema(
		description = "signature of the stored key request CHAT transaction",
		example = "request_signature"
	)
	public byte[] requestSignature;

	@Schema(
		description = "public key of the account that requested the key",
		example = "requester_public_key"
	)
	public byte[] requesterPublicKey;

	@Schema(
		description = "membership epoch id from the key request",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "specific private group chat key id requested, if supplied",
		example = "requested_key_id"
	)
	public byte[] requestedKeyId;

	@Schema(
		description = "private group chat key id relayed or treated as already relayed",
		example = "relayed_key_id"
	)
	public byte[] relayedKeyId;

	@Schema(
		description = "signature of the stored relayed key announcement CHAT transaction, if relayed",
		example = "announcement_signature"
	)
	public byte[] announcementSignature;

	@Schema(
		description = "result of processing this key request"
	)
	public PrivateGroupChatService.KeyRequestRecoveryStatus status;

	protected PrivateGroupChatKeyRequestRecoveryResponse() {
		/* For JAXB */
	}

	public PrivateGroupChatKeyRequestRecoveryResponse(PrivateGroupChatService.KeyRequestRecoveryResult result) {
		this.requestSignature = result.getRequestSignature();
		this.requesterPublicKey = result.getRequesterPublicKey();
		this.epochId = result.getEpochId();
		this.requestedKeyId = result.getRequestedKeyId();
		this.relayedKeyId = result.getRelayedKeyId();
		this.announcementSignature = result.getAnnouncementSignature();
		this.status = result.getStatus();
	}

}
