package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("BUY_ASSET_OWNERSHIP")
public class BuyAssetOwnershipTransactionData extends TransactionData {

	// Properties

	@Schema(description = "buyer's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] buyerPublicKey;

	@Schema(description = "which asset ownership to buy", example = "1")
	private long assetId;

	@Schema(description = "selling price", example = "123.456")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long amount;

	@Schema(description = "seller's address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	private String seller;

	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private byte[] assetReference;

	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private String saleRecipient;

	// Constructors

	// For JAXB
	protected BuyAssetOwnershipTransactionData() {
		super(TransactionType.BUY_ASSET_OWNERSHIP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.buyerPublicKey;
	}

	/** From repository */
	public BuyAssetOwnershipTransactionData(BaseTransactionData baseTransactionData,
			long assetId, long amount, String seller, byte[] assetReference, String saleRecipient) {
		super(TransactionType.BUY_ASSET_OWNERSHIP, baseTransactionData);

		this.buyerPublicKey = baseTransactionData.creatorPublicKey;
		this.assetId = assetId;
		this.amount = amount;
		this.seller = seller;
		this.assetReference = assetReference;
		this.saleRecipient = saleRecipient;
	}

	/** From network/API */
	public BuyAssetOwnershipTransactionData(BaseTransactionData baseTransactionData, long assetId, long amount, String seller) {
		this(baseTransactionData, assetId, amount, seller, null, null);
	}

	// Getters / setters

	public byte[] getBuyerPublicKey() {
		return this.buyerPublicKey;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public long getAmount() {
		return this.amount;
	}

	public String getSeller() {
		return this.seller;
	}

	public byte[] getAssetReference() {
		return this.assetReference;
	}

	public void setAssetReference(byte[] assetReference) {
		this.assetReference = assetReference;
	}

	public String getSaleRecipient() {
		return this.saleRecipient;
	}

	public void setSaleRecipient(String saleRecipient) {
		this.saleRecipient = saleRecipient;
	}

}
