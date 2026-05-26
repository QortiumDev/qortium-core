package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.asset.Asset;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = {TransactionData.class})
//JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("GENESIS")
public class GenesisTransactionData extends TransactionData {

	// Properties

	private String recipient;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long amount;

	private long assetId;

	// Constructors

	// For JAXB
	protected GenesisTransactionData() {
		super(TransactionType.GENESIS);
	}

	/** From repository */
	public GenesisTransactionData(BaseTransactionData baseTransactionData, String recipient, long amount, long assetId) {
		super(TransactionType.GENESIS, baseTransactionData);

		this.recipient = recipient;
		this.amount = amount;
		this.assetId = assetId;
	}

	/** From repository, defaulting to the native asset. */
	public GenesisTransactionData(BaseTransactionData baseTransactionData, String recipient, long amount) {
		this(baseTransactionData, recipient, amount, Asset.NATIVE);
	}

	// Getters/Setters

	public String getRecipient() {
		return this.recipient;
	}

	public long getAmount() {
		return this.amount;
	}

	public long getAssetId() {
		return this.assetId;
	}

}
