package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatDecryptResponse {

	@Schema(
		description = "decrypted message data",
		example = "message_data"
	)
	public byte[] data;

	@Schema(
		description = "whether the decrypted message data should be treated as text"
	)
	public boolean isText;

	@Schema(
		description = "closed group id for the decrypted message",
		example = "100"
	)
	public int groupId;

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

}
