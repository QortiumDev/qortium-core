package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.data.chat.ChatMessage.Encoding;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class DirectPrivateChatActiveChatsRequest {

	@Schema(
		description = "local account private key",
		example = "private_key"
	)
	public byte[] accountPrivateKey;

	@Schema(
		description = "encoding to use for decrypted latest message data"
	)
	public Encoding encoding;

	@Schema(
		description = "whether returned active chats must have a chat reference"
	)
	public Boolean hasChatReference;

}
