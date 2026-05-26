package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatSendResponse {

	@Schema(
		description = "signature of the stored encrypted message CHAT transaction",
		example = "message_signature"
	)
	public byte[] messageSignature;

	@Schema(
		description = "signature of the stored key announcement CHAT transaction, if a new group key was announced",
		example = "key_announcement_signature"
	)
	public byte[] keyAnnouncementSignature;

	@Schema(
		description = "membership epoch id used for encryption",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "private group chat key id used for encryption",
		example = "key_id"
	)
	public byte[] keyId;

}
