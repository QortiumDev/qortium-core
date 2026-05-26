package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatRotationRequestResponse {

	@Schema(
		description = "signature of the stored private group chat rotation request CHAT transaction",
		example = "request_signature"
	)
	public byte[] requestSignature;

	@Schema(
		description = "membership epoch id the rotation request applies to",
		example = "epoch_id"
	)
	public byte[] epochId;

}
