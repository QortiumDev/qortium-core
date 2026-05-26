package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatDecryptRequest {

	@Schema(
		description = "recipient's private key",
		example = "private_key"
	)
	public byte[] recipientPrivateKey;

	@Schema(
		description = "signature of the stored encrypted message CHAT transaction",
		example = "message_signature"
	)
	public byte[] messageSignature;

}
