package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatRotateRequest {

	@Schema(
		description = "rotator's private key",
		example = "private_key"
	)
	public byte[] rotatorPrivateKey;

	@Schema(
		description = "closed group id to rotate the local private group chat key for",
		example = "100"
	)
	public int groupId;

}
