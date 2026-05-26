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
public class CancelSellAssetOwnershipTransactionData extends TransactionData {

	// Properties

	@Schema(description = "asset owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;

	@Schema(description = "which asset ownership sale to cancel", example = "1")
	private long assetId;

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
	protected CancelSellAssetOwnershipTransactionData() {
		super(TransactionType.CANCEL_SELL_ASSET_OWNERSHIP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public CancelSellAssetOwnershipTransactionData(BaseTransactionData baseTransactionData, long assetId, Long salePrice, String saleRecipient) {
		super(TransactionType.CANCEL_SELL_ASSET_OWNERSHIP, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.assetId = assetId;
		this.salePrice = salePrice;
		this.saleRecipient = saleRecipient;
	}

	/** From network/API */
	public CancelSellAssetOwnershipTransactionData(BaseTransactionData baseTransactionData, long assetId) {
		this(baseTransactionData, assetId, null, null);
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public long getAssetId() {
		return this.assetId;
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
