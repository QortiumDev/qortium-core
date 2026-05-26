package org.qortium.data.blockchain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(description = "Approved on-chain chain parameter update")
public class ChainParameterData {

	private byte[] signature;
	private int parameterId;
	private int activationHeight;
	private byte[] value;

	protected ChainParameterData() {
	}

	public ChainParameterData(byte[] signature, int parameterId, int activationHeight, byte[] value) {
		this.signature = signature;
		this.parameterId = parameterId;
		this.activationHeight = activationHeight;
		this.value = value;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public int getParameterId() {
		return this.parameterId;
	}

	public int getActivationHeight() {
		return this.activationHeight;
	}

	public byte[] getValue() {
		return this.value;
	}
}
