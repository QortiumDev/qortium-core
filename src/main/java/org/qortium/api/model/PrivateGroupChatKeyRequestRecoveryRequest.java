package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyRequestRecoveryRequest {

	@Schema(
		description = "relayer's private key",
		example = "private_key"
	)
	public byte[] relayerPrivateKey;

	@Schema(
		description = "closed group id to resolve private group chat key requests for",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "maximum number of stored key requests to process",
		example = "20"
	)
	public Integer limit;

}
