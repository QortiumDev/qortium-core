package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.PresenceTransaction.PresenceType;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("PRESENCE")
public class PresenceTransactionData extends TransactionData {

	// Properties
	@Schema(description = "sender's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] senderPublicKey;

	private PresenceType presenceType;

	@Schema(description = "timestamp signature", example = "2yGEbwRFyhPZZckKA")
	private byte[] timestampSignature;

	// Constructors

	// For JAXB
	protected PresenceTransactionData() {
		super(TransactionType.PRESENCE);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.senderPublicKey;
	}

	public PresenceTransactionData(BaseTransactionData baseTransactionData,
			int nonce, PresenceType presenceType, byte[] timestampSignature) {
		super(TransactionType.PRESENCE, baseTransactionData);

		this.senderPublicKey = baseTransactionData.creatorPublicKey;
		this.nonce = Integer.valueOf(nonce);
		this.presenceType = presenceType;
		this.timestampSignature = timestampSignature;
	}

	// Getters/Setters

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public PresenceType getPresenceType() {
		return this.presenceType;
	}

	public byte[] getTimestampSignature() {
		return this.timestampSignature;
	}

}
