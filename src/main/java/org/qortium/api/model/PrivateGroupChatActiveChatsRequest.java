package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.data.chat.ChatMessage.Encoding;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatActiveChatsRequest {

	@Schema(
		description = "recipient's private key",
		example = "private_key"
	)
	public byte[] recipientPrivateKey;

	@Schema(
		description = "encoding to use for decrypted latest message data"
	)
	public Encoding encoding;

}
