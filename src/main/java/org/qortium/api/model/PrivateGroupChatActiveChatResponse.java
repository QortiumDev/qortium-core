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
public class PrivateGroupChatActiveChatResponse {

	public enum Status {
		DECRYPTED,
		MISSING_KEY,
		NO_MESSAGES
	}

	@Schema(
		description = "closed group id",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "closed group name",
		example = "private group"
	)
	public String groupName;

	@Schema(
		description = "latest private message status for this group"
	)
	public Status status;

	@Schema(
		description = "latest private message timestamp, or null if the group has no private messages",
		example = "1672531200000"
	)
	public Long timestamp;

	@Schema(
		description = "latest private message sender public key",
		example = "sender_public_key"
	)
	public byte[] senderPublicKey;

	@Schema(
		description = "latest private message sender address",
		example = "Qaddress"
	)
	public String sender;

	@Schema(
		description = "latest private message sender's primary name, if known",
		example = "alice"
	)
	public String senderName;

	@Schema(
		description = "optional prior chat transaction signature referenced by the latest private message",
		example = "chat_reference"
	)
	public byte[] chatReference;

	@Schema(
		description = "stored CHAT transaction signature for the latest private message",
		example = "message_signature"
	)
	public byte[] signature;

	@Schema(
		description = "encoding used for decrypted latest message data"
	)
	public Encoding encoding;

	@Schema(
		description = "decrypted latest message data using the requested encoding, or null if the key is missing",
		example = "message_data"
	)
	public String data;

	@Schema(
		description = "whether the decrypted latest message data should be treated as text"
	)
	public Boolean isText;

	@Schema(
		description = "whether the stored latest chat payload is encrypted"
	)
	public Boolean isEncrypted;

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

	protected PrivateGroupChatActiveChatResponse() {
		/* For JAXB */
	}

	public PrivateGroupChatActiveChatResponse(PrivateGroupChatService.ActiveChatResult result,
			Encoding encoding) {
		this.groupId = result.getGroupId();
		this.groupName = result.getGroupName();
		this.status = Status.valueOf(result.getStatus().name());
		this.encoding = encoding != null ? encoding : Encoding.BASE58;
		this.data = encode(result.getData(), this.encoding);
		this.isText = result.isText();
		this.epochId = result.getEpochId();
		this.keyId = result.getKeyId();

		ChatMessage message = result.getMessage();
		if (message == null)
			return;

		this.timestamp = message.getTimestamp();
		this.senderPublicKey = message.getSenderPublicKey();
		this.sender = message.getSender();
		this.senderName = message.getSenderName();
		this.chatReference = message.getChatReference();
		this.signature = message.getSignature();
		this.isEncrypted = message.isEncrypted();
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
