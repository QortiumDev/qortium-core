package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatRotateResponse {

	@Schema(
		description = "signature of the stored fresh key announcement CHAT transaction",
		example = "key_announcement_signature"
	)
	public byte[] keyAnnouncementSignature;

	@Schema(
		description = "membership epoch id for the fresh key announcement",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "private group chat key id for the fresh key announcement",
		example = "key_id"
	)
	public byte[] keyId;

}
