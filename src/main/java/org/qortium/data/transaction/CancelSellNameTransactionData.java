package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class CancelSellNameTransactionData extends TransactionData {

	// Properties
	@Schema(description = "owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;
	@Schema(description = "which name to cancel selling", example = "my-name")
	private String name;

	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private Long salePrice;

	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private String saleRecipient;

	// Constructors

	// For JAXB
	protected CancelSellNameTransactionData() {
		super(TransactionType.CANCEL_SELL_NAME);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public CancelSellNameTransactionData(BaseTransactionData baseTransactionData, String name, Long salePrice, String saleRecipient) {
		super(TransactionType.CANCEL_SELL_NAME, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.name = name;
		this.salePrice = salePrice;
		this.saleRecipient = saleRecipient;
	}

	public CancelSellNameTransactionData(BaseTransactionData baseTransactionData, String name, Long salePrice) {
		this(baseTransactionData, name, salePrice, null);
	}

	/** From network/API */
	public CancelSellNameTransactionData(BaseTransactionData baseTransactionData, String name) {
		this(baseTransactionData, name, null, null);
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public String getName() {
		return this.name;
	}

	public Long getSalePrice() {
		return this.salePrice;
	}

	public void setSalePrice(Long salePrice) {
		this.salePrice = salePrice;
	}

	public String getSaleRecipient() {
		return this.saleRecipient;
	}

	public void setSaleRecipient(String saleRecipient) {
		this.saleRecipient = saleRecipient;
	}

}
