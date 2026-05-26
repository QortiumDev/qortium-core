package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatSendRequest {

	@Schema(
		description = "sender's private key",
		example = "private_key"
	)
	public byte[] senderPrivateKey;

	@Schema(
		description = "closed group id to send to",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "plain message data before private group encryption",
		example = "message_data"
	)
	public byte[] data;

	@Schema(
		description = "whether the decrypted message data should be treated as text"
	)
	public boolean isText;

	@Schema(
		description = "optional prior chat transaction signature this message references",
		example = "chat_reference"
	)
	public byte[] chatReference;

}
