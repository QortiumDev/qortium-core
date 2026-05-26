package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatMessageCountRequest {

	@Schema(
		description = "recipient's private key",
		example = "private_key"
	)
	public byte[] recipientPrivateKey;

	@Schema(
		description = "closed group id to count",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "only count messages before this timestamp",
		example = "1672531200000"
	)
	public Long before;

	@Schema(
		description = "only count messages after this timestamp",
		example = "1672531200000"
	)
	public Long after;

	@Schema(
		description = "optional prior chat transaction signature referenced by counted messages",
		example = "chat_reference"
	)
	public byte[] chatReference;

	@Schema(
		description = "whether counted messages must have a chat reference"
	)
	public Boolean hasChatReference;

	@Schema(
		description = "optional sender address filter",
		example = "Qaddress"
	)
	public String sender;

}
