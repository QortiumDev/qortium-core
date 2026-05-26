package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyAnnouncementRelayResponse {

	@Schema(
		description = "signature of the stored relayed key announcement CHAT transaction",
		example = "announcement_signature"
	)
	public byte[] announcementSignature;

	@Schema(
		description = "membership epoch id for the relayed key announcement",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "private group chat key id for the relayed key announcement",
		example = "key_id"
	)
	public byte[] keyId;

}
