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
@XmlDiscriminatorValue("UPDATE_NAME")
public class UpdateNameTransactionData extends TransactionData {

	// Properties

	@Schema(description = "owner's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] ownerPublicKey;

	@Schema(description = "which name to update", example = "my-name")
	private String name;

	@Schema(description = "new name", example = "my-new-name")
	private String newName;

	@Schema(description = "replacement simple name-related info in JSON or text format", example = "Name metadata for this chain")
	private String newData;

	@Schema(description = "optional primary-name setting for this name after update; null leaves primary-name state unchanged")
	private Boolean primary;

	// For internal use
	@XmlTransient
	@Schema(hidden = true)
	private String reducedNewName;

	// For internal use when orphaning
	@XmlTransient
	@Schema(hidden = true)
	private byte[] nameReference;

	// For internal use when orphaning explicit primary-name changes
	@XmlTransient
	@Schema(hidden = true)
	private String previousPrimaryName;

	// Constructors

	// For JAXB
	protected UpdateNameTransactionData() {
		super(TransactionType.UPDATE_NAME);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
		this.reducedNewName = this.newName != null ? Unicode.sanitize(this.newName) : null;
	}

	/** From repository */
	public UpdateNameTransactionData(BaseTransactionData baseTransactionData, String name, String newName, String newData,
			Boolean primary, String reducedNewName, byte[] nameReference, String previousPrimaryName) {
		super(TransactionType.UPDATE_NAME, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.name = name;
		this.newName = newName;
		this.newData = newData;
		this.primary = primary;
		this.reducedNewName = reducedNewName;
		this.nameReference = nameReference;
		this.previousPrimaryName = previousPrimaryName;
	}

	/** From repository */
	public UpdateNameTransactionData(BaseTransactionData baseTransactionData, String name, String newName, String newData, String reducedNewName, byte[] nameReference) {
		this(baseTransactionData, name, newName, newData, null, reducedNewName, nameReference, null);
	}

	/** From network */
	public UpdateNameTransactionData(BaseTransactionData baseTransactionData, String name, String newName, String newData, Boolean primary) {
		this(baseTransactionData, name, newName, newData, primary, Unicode.sanitize(newName), null, null);
	}

	/** From network */
	public UpdateNameTransactionData(BaseTransactionData baseTransactionData, String name, String newName, String newData) {
		this(baseTransactionData, name, newName, newData, null);
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public String getName() {
		return this.name;
	}

	public String getNewName() {
		return this.newName;
	}

	public String getNewData() {
		return this.newData;
	}

	public Boolean getPrimary() {
		return this.primary;
	}

	public String getReducedNewName() {
		return this.reducedNewName;
	}

	public byte[] getNameReference() {
		return this.nameReference;
	}

	public void setNameReference(byte[] nameReference) {
		this.nameReference = nameReference;
	}

	public String getPreviousPrimaryName() {
		return this.previousPrimaryName;
	}

	public void setPreviousPrimaryName(String previousPrimaryName) {
		this.previousPrimaryName = previousPrimaryName;
	}

}
