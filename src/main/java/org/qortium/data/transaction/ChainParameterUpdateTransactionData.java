package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.block.ChainParameter;
import org.qortium.crypto.Crypto;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("CHAIN_PARAMETER_UPDATE")
public class ChainParameterUpdateTransactionData extends TransactionData {

	@Schema(description = "update creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] updaterPublicKey;

	@Schema(description = "numeric chain parameter ID. Supported IDs are listed by the chain-parameters API", example = "1")
	private int parameterId;

	@Schema(description = "block height from which the approved parameter value becomes active", example = "10000")
	private int activationHeight;

	@Schema(description = "canonical binary value for the parameter, encoded in Base58")
	private byte[] value;

	protected ChainParameterUpdateTransactionData() {
		super(TransactionType.CHAIN_PARAMETER_UPDATE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.updaterPublicKey;
	}

	public ChainParameterUpdateTransactionData(BaseTransactionData baseTransactionData, int parameterId, int activationHeight, byte[] value) {
		super(TransactionType.CHAIN_PARAMETER_UPDATE, baseTransactionData);

		this.updaterPublicKey = baseTransactionData.creatorPublicKey;
		this.parameterId = parameterId;
		this.activationHeight = activationHeight;
		this.value = value;
	}

	public byte[] getUpdaterPublicKey() {
		return this.updaterPublicKey;
	}

	@XmlElement(name = "updaterAddress")
	@Schema(description = "update creator's address")
	protected String getUpdaterAddress() {
		return this.updaterPublicKey == null ? null : Crypto.toAddress(this.updaterPublicKey);
	}

	public int getParameterId() {
		return this.parameterId;
	}

	@XmlElement(name = "parameterName")
	@Schema(description = "known name for this parameter ID")
	protected String getParameterName() {
		ChainParameter parameter = ChainParameter.valueOf(this.parameterId);
		return parameter == null ? null : parameter.name();
	}

	public int getActivationHeight() {
		return this.activationHeight;
	}

	public byte[] getValue() {
		return this.value;
	}
}
