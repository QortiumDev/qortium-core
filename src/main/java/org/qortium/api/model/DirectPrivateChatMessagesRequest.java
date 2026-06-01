package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.data.chat.ChatMessage.Encoding;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class DirectPrivateChatMessagesRequest {

	@Schema(
		description = "local account private key",
		example = "private_key"
	)
	public byte[] accountPrivateKey;

	@Schema(
		description = "other direct chat participant address",
		example = "Qaddress"
	)
	public String otherAddress;

	@Schema(
		description = "only return messages before this timestamp",
		example = "1672531200000"
	)
	public Long before;

	@Schema(
		description = "only return messages after this timestamp",
		example = "1672531200000"
	)
	public Long after;

	@Schema(
		description = "optional prior chat transaction signature referenced by returned messages",
		example = "chat_reference"
	)
	public byte[] chatReference;

	@Schema(
		description = "whether returned messages must have a chat reference"
	)
	public Boolean hasChatReference;

	@Schema(
		description = "optional sender address filter",
		example = "Qaddress"
	)
	public String sender;

	@Schema(
		description = "encoding to use for decrypted message data"
	)
	public Encoding encoding;

	@Schema(
		description = "maximum number of direct messages to return",
		example = "20"
	)
	public Integer limit;

	@Schema(
		description = "number of matching direct messages to skip",
		example = "0"
	)
	public Integer offset;

	@Schema(
		description = "return newest messages first"
	)
	public Boolean reverse;

}
