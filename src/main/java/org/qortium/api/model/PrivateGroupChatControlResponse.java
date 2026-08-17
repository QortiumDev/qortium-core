package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.chat.PrivateGroupChatPublicService;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatControlResponse {

	@Schema(description = "retained CHAT timestamp")
	public final long timestamp;

	@Schema(description = "closed group id")
	public final int txGroupId;

	@Schema(description = "QPGC control-envelope type")
	public final PrivateGroupChatEnvelope.Type type;

	@Schema(description = "QPGC membership epoch id encoded in Base58")
	public final String epochId;

	@Schema(description = "optional QPGC key id encoded in Base58")
	public final String keyId;

	@Schema(description = "outer CHAT sender address; relayed announcements can differ from their creator")
	public final String sender;

	@Schema(description = "optional referenced CHAT signature encoded in Base58")
	public final String chatReference;

	@Schema(description = "outer CHAT signature encoded in Base58")
	public final String signature;

	@Schema(description = "complete signed CHAT transaction bytes encoded in Base58")
	public final String signedTransaction;

	protected PrivateGroupChatControlResponse() {
		this.timestamp = 0L;
		this.txGroupId = 0;
		this.type = null;
		this.epochId = null;
		this.keyId = null;
		this.sender = null;
		this.chatReference = null;
		this.signature = null;
		this.signedTransaction = null;
	}

	public PrivateGroupChatControlResponse(PrivateGroupChatPublicService.ControlRecord record) {
		this.timestamp = record.getTimestamp();
		this.txGroupId = record.getGroupId();
		this.type = record.getType();
		this.epochId = Base58.encode(record.getEpochId());
		this.keyId = encodeNullable(record.getKeyId());
		this.sender = record.getSender();
		this.chatReference = encodeNullable(record.getChatReference());
		this.signature = Base58.encode(record.getSignature());
		this.signedTransaction = Base58.encode(record.getSignedTransactionBytes());
	}

	private static String encodeNullable(byte[] value) {
		return value == null ? null : Base58.encode(value);
	}
}
