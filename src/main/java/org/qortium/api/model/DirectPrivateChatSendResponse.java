package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.chat.DirectPrivateChatService;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class DirectPrivateChatSendResponse {

	@Schema(
		description = "signature of the stored encrypted direct CHAT transaction",
		example = "message_signature"
	)
	public byte[] messageSignature;

	@Schema(
		description = "storage status"
	)
	public DirectPrivateChatService.SendStatus status;

}
