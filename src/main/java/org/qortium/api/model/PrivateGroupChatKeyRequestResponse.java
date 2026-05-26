package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatKeyRequestResponse {

	@Schema(
		description = "signature of the stored private group chat key request CHAT transaction",
		example = "request_signature"
	)
	public byte[] requestSignature;

	@Schema(
		description = "membership epoch id the key request applies to",
		example = "epoch_id"
	)
	public byte[] epochId;

	@Schema(
		description = "private group chat key id requested, if supplied",
		example = "key_id"
	)
	public byte[] keyId;

}
