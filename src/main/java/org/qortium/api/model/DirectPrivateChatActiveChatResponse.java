package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.bouncycastle.util.encoders.Base64;
import org.qortium.chat.DirectPrivateChatService;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.chat.ChatMessage.Encoding;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class DirectPrivateChatActiveChatResponse {

	@Schema(
		description = "other direct chat participant address",
		example = "Qaddress"
	)
	public String address;

	@Schema(
		description = "other participant's primary name, if known",
		example = "alice"
	)
	public String name;

	@Schema(
		description = "latest direct message timestamp",
		example = "1672531200000"
	)
	public Long timestamp;

	@Schema(
		description = "latest direct message sender public key",
		example = "sender_public_key"
	)
	public byte[] senderPublicKey;

	@Schema(
		description = "latest direct message sender address",
		example = "Qaddress"
	)
	public String sender;

	@Schema(
		description = "latest direct message sender's primary name, if known",
		example = "alice"
	)
	public String senderName;

	@Schema(
		description = "latest direct message recipient address",
		example = "Qaddress"
	)
	public String recipient;

	@Schema(
		description = "latest direct message recipient's primary name, if known",
		example = "bob"
	)
	public String recipientName;

	@Schema(
		description = "optional prior chat transaction signature referenced by the latest direct message",
		example = "chat_reference"
	)
	public byte[] chatReference;

	@Schema(
		description = "stored CHAT transaction signature for the latest direct message",
		example = "message_signature"
	)
	public byte[] signature;

	@Schema(
		description = "encoding used for decrypted latest message data"
	)
	public Encoding encoding;

	@Schema(
		description = "decrypted or plaintext latest message data using the requested encoding, or null when unreadable",
		example = "message_data"
	)
	public String data;

	@Schema(
		description = "whether the decrypted or plaintext latest message data should be treated as text"
	)
	public Boolean isText;

	@Schema(
		description = "whether the stored latest chat payload is encrypted"
	)
	public Boolean isEncrypted;

	@Schema(
		description = "decryption status for the latest direct message"
	)
	public DirectPrivateChatService.DecryptionStatus decryptionStatus;

	protected DirectPrivateChatActiveChatResponse() {
		/* For JAXB */
	}

	public DirectPrivateChatActiveChatResponse(DirectPrivateChatService.ActiveChatResult result,
			Encoding encoding) {
		this.address = result.getAddress();
		this.name = result.getName();
		this.encoding = encoding != null ? encoding : Encoding.BASE58;

		DirectPrivateChatService.ListMessageResult messageResult = result.getMessageResult();
		ChatMessage message = messageResult.getMessage();
		this.timestamp = message.getTimestamp();
		this.senderPublicKey = message.getSenderPublicKey();
		this.sender = message.getSender();
		this.senderName = message.getSenderName();
		this.recipient = message.getRecipient();
		this.recipientName = message.getRecipientName();
		this.chatReference = message.getChatReference();
		this.signature = message.getSignature();
		this.data = encode(messageResult.getData(), this.encoding);
		this.isText = messageResult.isText();
		this.isEncrypted = message.isEncrypted();
		this.decryptionStatus = messageResult.getStatus();
	}

	private static String encode(byte[] data, Encoding encoding) {
		if (data == null)
			return null;

		switch (encoding) {
			case BASE64:
				return Base64.toBase64String(data);

			case BASE58:
			default:
				return Base58.encode(data);
		}
	}

}
