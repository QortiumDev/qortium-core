package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
public class DeployAtTransactionData extends TransactionData {

	// Properties
	private String name;
	private String description;
	private String aTType;
	private String tags;
	private byte[] creationBytes;
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long amount;
	private long assetId;
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long nativeFeeReserve;
	private String aTAddress;

	// Constructors

	// For JAX-RS
	protected DeployAtTransactionData() {
		super(TransactionType.DEPLOY_AT);
	}

	/** From repository */
	public DeployAtTransactionData(BaseTransactionData baseTransactionData,
			String aTAddress, String name, String description, String aTType, String tags, byte[] creationBytes, long amount, long assetId, long nativeFeeReserve) {
		super(TransactionType.DEPLOY_AT, baseTransactionData);

		this.aTAddress = aTAddress;
		this.name = name;
		this.description = description;
		this.aTType = aTType;
		this.tags = tags;
		this.creationBytes = creationBytes;
		this.amount = amount;
		this.assetId = assetId;
		this.nativeFeeReserve = nativeFeeReserve;
	}

	/** From repository */
	public DeployAtTransactionData(BaseTransactionData baseTransactionData,
			String aTAddress, String name, String description, String aTType, String tags, byte[] creationBytes, long amount, long assetId) {
		this(baseTransactionData, aTAddress, name, description, aTType, tags, creationBytes, amount, assetId, 0L);
	}

	/** From network/API */
	public DeployAtTransactionData(BaseTransactionData baseTransactionData,
			String name, String description, String aTType, String tags, byte[] creationBytes, long amount, long assetId, long nativeFeeReserve) {
		this(baseTransactionData, null, name, description, aTType, tags, creationBytes, amount, assetId, nativeFeeReserve);
	}

	/** From network/API */
	public DeployAtTransactionData(BaseTransactionData baseTransactionData,
			String name, String description, String aTType, String tags, byte[] creationBytes, long amount, long assetId) {
		this(baseTransactionData, null, name, description, aTType, tags, creationBytes, amount, assetId, 0L);
	}

	// Getters/Setters

	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	public String getAtType() {
		return this.aTType;
	}

	public String getTags() {
		return this.tags;
	}

	public byte[] getCreationBytes() {
		return this.creationBytes;
	}

	public long getAmount() {
		return this.amount;
	}

	public long getAssetId() {
		return this.assetId;
	}

	public long getNativeFeeReserve() {
		return this.nativeFeeReserve;
	}

	public String getAtAddress() {
		return this.aTAddress;
	}

	public void setAtAddress(String AtAddress) {
		this.aTAddress = AtAddress;
	}

	// Re-expose creatorPublicKey for this transaction type for JAXB
	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "AT creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public byte[] getAtCreatorPublicKey() {
		return this.creatorPublicKey;
	}

	@XmlElement(name = "creatorPublicKey")
	@Schema(name = "creatorPublicKey", description = "AT creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	public void setAtCreatorPublicKey(byte[] creatorPublicKey) {
		this.creatorPublicKey = creatorPublicKey;
	}

}
