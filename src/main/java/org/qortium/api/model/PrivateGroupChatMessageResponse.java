package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.bouncycastle.util.encoders.Base64;
import org.qortium.chat.PrivateGroupChatService;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.chat.ChatMessage.Encoding;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatMessageResponse {

	public enum Status {
		DECRYPTED,
		MISSING_KEY
	}

	@Schema(
		description = "message timestamp",
		example = "1672531200000"
	)
	public long timestamp;

	@Schema(
		description = "closed group id",
		example = "100"
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
		description = "recipient address, if any",
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
		description = "decrypted message data using the requested encoding, or null if the key is missing",
		example = "message_data"
	)
	public String data;

	@Schema(
		description = "whether the decrypted message data should be treated as text"
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
		description = "decryption status for this message"
	)
	public Status status;

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

	protected PrivateGroupChatMessageResponse() {
		/* For JAXB */
	}

	public PrivateGroupChatMessageResponse(PrivateGroupChatService.ListMessageResult result, Encoding encoding) {
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
		this.status = result.isDecrypted() ? Status.DECRYPTED : Status.MISSING_KEY;
		this.epochId = result.getEpochId();
		this.keyId = result.getKeyId();
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
