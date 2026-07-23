package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("SELL_ASSET_OWNERSHIP")
public class SellAssetOwnershipTransactionData extends TransactionData {

	// Properties

	@Schema(description = "asset owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;

	@Schema(description = "which asset ownership to sell", example = "1")
	private long assetId;

	@Schema(description = "selling price", example = "123.456")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long amount;

	@Schema(description = "optional direct-sale recipient address", example = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v")
	private String recipient;

	// Constructors

	// For JAXB
	protected SellAssetOwnershipTransactionData() {
		super(TransactionType.SELL_ASSET_OWNERSHIP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public SellAssetOwnershipTransactionData(BaseTransactionData baseTransactionData, long assetId, long amount) {
		this(baseTransactionData, assetId, amount, null);
	}

	public SellAssetOwnershipTransactionData(BaseTransactionData baseTransactionData, long assetId, long amount, String recipient) {
		super(TransactionType.SELL_ASSET_OWNERSHIP, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.assetId = assetId;
		this.amount = amount;
		this.recipient = recipient;
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public long getAmount() {
		return this.amount;
	}

	public String getRecipient() {
		return this.recipient;
	}

}
