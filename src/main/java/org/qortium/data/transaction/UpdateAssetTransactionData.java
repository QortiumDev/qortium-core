package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Unicode;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("UPDATE_ASSET")
public class UpdateAssetTransactionData extends TransactionData {

	// Properties
	private long assetId;
	@Schema(description = "asset owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;
	@Schema(description = "asset new name; empty means no change", example = "GOLD")
	private String newName;
	@Schema(description = "asset new description", example = "Gold asset - 1 unit represents one 1kg of gold")
	private String newDescription;
	@Schema(description = "new asset-related data, typically JSON", example = "{\"logo\": \"data:image/jpeg;base64,/9j/4AAQSkZJRgA==\"}")
	private String newData;
	// No need to expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String reducedNewName;
	// No need to expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private byte[] orphanReference;

	// Constructors

	// For JAXB
	protected UpdateAssetTransactionData() {
		super(TransactionType.UPDATE_ASSET);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
		if (this.newName == null)
			this.newName = "";

		this.reducedNewName = Unicode.sanitize(this.newName);
	}

	/** From repository */
	public UpdateAssetTransactionData(BaseTransactionData baseTransactionData,
			long assetId, String newName, String newDescription, String newData,
			String reducedNewName, byte[] orphanReference) {
		super(TransactionType.UPDATE_ASSET, baseTransactionData);

		this.assetId = assetId;
		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.newName = newName;
		this.newDescription = newDescription;
		this.newData = newData;
		this.reducedNewName = reducedNewName;
		this.orphanReference = orphanReference;
	}

	/** From repository */
	public UpdateAssetTransactionData(BaseTransactionData baseTransactionData,
			long assetId, String newDescription, String newData, byte[] orphanReference) {
		this(baseTransactionData, assetId, "", newDescription, newData, "", orphanReference);
	}

	/** From network/API */
	public UpdateAssetTransactionData(BaseTransactionData baseTransactionData, long assetId, String newName, String newDescription, String newData) {
		this(baseTransactionData, assetId, newName != null ? newName : "", newDescription, newData,
				Unicode.sanitize(newName != null ? newName : ""), null);
	}

	/** From network/API */
	public UpdateAssetTransactionData(BaseTransactionData baseTransactionData, long assetId, String newDescription, String newData) {
		this(baseTransactionData, assetId, "", newDescription, newData);
	}

	// Getters/Setters

	public long getAssetId() {
		return this.assetId;
	}

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public String getNewName() {
		return this.newName;
	}

	public String getNewDescription() {
		return this.newDescription;
	}

	public String getNewData() {
		return this.newData;
	}

	public String getReducedNewName() {
		return this.reducedNewName;
	}

	public byte[] getOrphanReference() {
		return this.orphanReference;
	}

	public void setOrphanReference(byte[] orphanReference) {
		this.orphanReference = orphanReference;
	}

}
