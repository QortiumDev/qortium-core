package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyAnnouncementRelayRequest {

	@Schema(
		description = "relayer's private key",
		example = "private_key"
	)
	public byte[] relayerPrivateKey;

	@Schema(
		description = "closed group id to relay a private group chat key announcement for",
		example = "100"
	)
	public int groupId;

	@Schema(
		description = "current membership epoch id to relay a key announcement for",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "optional private group chat key id to relay",
		example = "key_id"
	)
	public byte[] keyId;

}
