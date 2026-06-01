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
public class DirectPrivateChatMessageResponse {

	@Schema(
		description = "message timestamp",
		example = "1672531200000"
	)
	public long timestamp;

	@Schema(
		description = "chat transaction group id"
	)
	public int txGroupId;

	@Schema(
		description = "sender public key",
		example = "sender_public_key"
	)
	public byte[] senderPublicKey;

	@Schema(
		description = "sender address",
		example = "Qaddress"
	)
	public String sender;

	@Schema(
		description = "sender's primary name, if known",
		example = "alice"
	)
	public String senderName;

	@Schema(
		description = "recipient address",
		example = "Qaddress"
	)
	public String recipient;

	@Schema(
		description = "recipient's primary name, if known",
		example = "bob"
	)
	public String recipientName;

	@Schema(
		description = "optional prior chat transaction signature this message references",
		example = "chat_reference"
	)
	public byte[] chatReference;

	@Schema(
		description = "encoding used for decrypted message data"
	)
	public Encoding encoding;

	@Schema(
		description = "decrypted or plaintext message data using the requested encoding, or null when unreadable",
		example = "message_data"
	)
	public String data;

	@Schema(
		description = "whether the decrypted or plaintext message data should be treated as text"
	)
	public boolean isText;

	@Schema(
		description = "whether the stored chat payload is encrypted"
	)
	public boolean isEncrypted;

	@Schema(
		description = "stored CHAT transaction signature",
		example = "message_signature"
	)
	public byte[] signature;

	@Schema(
		description = "decryption status for this direct message"
	)
	public DirectPrivateChatService.DecryptionStatus decryptionStatus;

	protected DirectPrivateChatMessageResponse() {
		/* For JAXB */
	}

	public DirectPrivateChatMessageResponse(DirectPrivateChatService.ListMessageResult result, Encoding encoding) {
		ChatMessage message = result.getMessage();
		this.timestamp = message.getTimestamp();
		this.txGroupId = message.getTxGroupId();
		this.senderPublicKey = message.getSenderPublicKey();
		this.sender = message.getSender();
		this.senderName = message.getSenderName();
		this.recipient = message.getRecipient();
		this.recipientName = message.getRecipientName();
		this.chatReference = message.getChatReference();
		this.encoding = encoding != null ? encoding : Encoding.BASE58;
		this.data = encode(result.getData(), this.encoding);
		this.isText = result.isText();
		this.isEncrypted = message.isEncrypted();
		this.signature = message.getSignature();
		this.decryptionStatus = result.getStatus();
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
