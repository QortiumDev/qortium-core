package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatRotationRequestRequest {

	@Schema(
		description = "owner or admin requester's private key",
		example = "private_key"
	)
	public byte[] requesterPrivateKey;

	@Schema(
		description = "closed group id to request private group chat key rotation for",
		example = "100"
	)
	public int groupId;

}
