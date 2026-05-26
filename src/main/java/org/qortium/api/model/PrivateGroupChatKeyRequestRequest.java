package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyRequestRequest {

	@Schema(
		description = "requester's private key",
		example = "private_key"
	)
	public byte[] requesterPrivateKey;

	@Schema(
		description = "closed group id to request a private group chat key for",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "optional private group chat key id to request",
		example = "key_id"
	)
	public byte[] keyId;

	@Schema(
		description = "optional historical membership epoch id to request the key for; defaults to the current epoch when omitted",
		example = "epoch_id"
	)
	public byte[] epochId;

}
