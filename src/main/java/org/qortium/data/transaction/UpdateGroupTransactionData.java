package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Unicode;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@Schema( allOf = { TransactionData.class } )
// JAXB: use this subclass if XmlDiscriminatorNode matches XmlDiscriminatorValue below:
@XmlDiscriminatorValue("UPDATE_GROUP")
public class UpdateGroupTransactionData extends TransactionData {

	// Properties
	@Schema(
		description = "owner's public key",
		example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP"
	)
	private byte[] ownerPublicKey;

	@Schema(
		description = "which group to update",
		example = "my-group"
	)
	private int groupId;

	@Schema(
		description = "new group name, or empty for no change",
		example = "my-new-group"
	)
	private String newName;

	@Schema(
		description = "replacement group description",
		example = "my group for accounts I like"
	)
	private String newDescription;

	@Schema(
		description = "new group join policy",
		example = "true"
	)
	private boolean newIsOpen;

	@Schema(
		description = "new group member transaction approval threshold"
	)
	private ApprovalThreshold newApprovalThreshold;

	@Schema(description = "new minimum block delay before approval takes effect")
	private int newMinimumBlockDelay;

	@Schema(description = "new maximum block delay before which transaction approval must be reached")
	private int newMaximumBlockDelay;

	// For internal use
	@XmlTransient
	@Schema(hidden = true)
	private String reducedNewName;

	/** Reference to CREATE_GROUP or UPDATE_GROUP transaction, used to rebuild group during orphaning. */
	// For internal use when orphaning
	@XmlTransient
	@Schema(
		hidden = true
	)
	private byte[] groupReference;

	// Constructors

	// For JAXB
	protected UpdateGroupTransactionData() {
		super(TransactionType.UPDATE_GROUP);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
		this.reducedNewName = this.newName != null ? Unicode.sanitize(this.newName) : null;
	}

	/** From repository */
	public UpdateGroupTransactionData(BaseTransactionData baseTransactionData,
			int groupId, String newName, String newDescription, boolean newIsOpen, ApprovalThreshold newApprovalThreshold,
			int newMinimumBlockDelay, int newMaximumBlockDelay, String reducedNewName, byte[] groupReference) {
		super(TransactionType.UPDATE_GROUP, baseTransactionData);

		this.ownerPublicKey = baseTransactionData.creatorPublicKey;
		this.newName = newName;
		this.groupId = groupId;
		this.newDescription = newDescription;
		this.newIsOpen = newIsOpen;
		this.newApprovalThreshold = newApprovalThreshold;
		this.newMinimumBlockDelay = newMinimumBlockDelay;
		this.newMaximumBlockDelay = newMaximumBlockDelay;
		this.reducedNewName = reducedNewName;
		this.groupReference = groupReference;
	}

	/** From repository */
	public UpdateGroupTransactionData(BaseTransactionData baseTransactionData,
			int groupId, String newDescription, boolean newIsOpen, ApprovalThreshold newApprovalThreshold,
			int newMinimumBlockDelay, int newMaximumBlockDelay, byte[] groupReference) {
		this(baseTransactionData, groupId, "", newDescription, newIsOpen, newApprovalThreshold,
				newMinimumBlockDelay, newMaximumBlockDelay, "", groupReference);
	}

	/** From network/API */
	public UpdateGroupTransactionData(BaseTransactionData baseTransactionData,
			int groupId, String newName, String newDescription, boolean newIsOpen, ApprovalThreshold newApprovalThreshold,
			int newMinimumBlockDelay, int newMaximumBlockDelay) {
		this(baseTransactionData, groupId, newName, newDescription, newIsOpen, newApprovalThreshold,
				newMinimumBlockDelay, newMaximumBlockDelay, Unicode.sanitize(newName), null);
	}

	/** From network/API */
	public UpdateGroupTransactionData(BaseTransactionData baseTransactionData,
			int groupId, String newDescription, boolean newIsOpen, ApprovalThreshold newApprovalThreshold,
			int newMinimumBlockDelay, int newMaximumBlockDelay) {
		this(baseTransactionData, groupId, newDescription, newIsOpen, newApprovalThreshold, newMinimumBlockDelay, newMaximumBlockDelay, null);
	}

	// Getters / setters

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public String getNewName() {
		return this.newName;
	}

	public String getNewDescription() {
		return this.newDescription;
	}

	public boolean getNewIsOpen() {
		return this.newIsOpen;
	}

	public ApprovalThreshold getNewApprovalThreshold() {
		return this.newApprovalThreshold;
	}

	public int getNewMinimumBlockDelay() {
		return this.newMinimumBlockDelay;
	}

	public int getNewMaximumBlockDelay() {
		return this.newMaximumBlockDelay;
	}

	public String getReducedNewName() {
		return this.reducedNewName;
	}

	public byte[] getGroupReference() {
		return this.groupReference;
	}

	public void setGroupReference(byte[] groupReference) {
		this.groupReference = groupReference;
	}

}
