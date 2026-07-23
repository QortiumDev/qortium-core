package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("TRANSFER_PRIVS")
public class TransferPrivsTransactionData extends TransactionData {

	// Properties
	@Schema(example = "sender_public_key")
	private byte[] senderPublicKey;

	private String recipient;

	// No need to ever expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private Integer previousSenderBlocksMinted;

	// Constructors

	// For JAXB
	protected TransferPrivsTransactionData() {
		super(TransactionType.TRANSFER_PRIVS);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.senderPublicKey;
	}

	/** Constructs using data from repository. */
	public TransferPrivsTransactionData(BaseTransactionData baseTransactionData, String recipient,
			Integer previousSenderBlocksMinted) {
		super(TransactionType.TRANSFER_PRIVS, baseTransactionData);

		this.senderPublicKey = baseTransactionData.creatorPublicKey;
		this.recipient = recipient;

		this.previousSenderBlocksMinted = previousSenderBlocksMinted;
	}

	/** Constructs using data from network/API. */
	public TransferPrivsTransactionData(BaseTransactionData baseTransactionData, String recipient) {
		this(baseTransactionData, recipient, null);
	}

	// Getters/setters

	public byte[] getSenderPublicKey() {
		return this.senderPublicKey;
	}

	public String getRecipient() {
		return this.recipient;
	}

	public Integer getPreviousSenderBlocksMinted() {
		return this.previousSenderBlocksMinted;
	}

	public void setPreviousSenderBlocksMinted(Integer previousSenderBlocksMinted) {
		this.previousSenderBlocksMinted = previousSenderBlocksMinted;
	}

}
