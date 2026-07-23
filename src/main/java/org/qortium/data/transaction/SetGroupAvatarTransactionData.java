package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.data.avatar.AvatarData;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

/** Points a group's avatar at a QDN resource (service, name, identifier), served as its latest revision. */
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("SET_GROUP_AVATAR")
public class SetGroupAvatarTransactionData extends TransactionData {
	private byte[] ownerPublicKey;
	private int groupId;
	@Schema(description = "Pointer to the QDN resource to show as the group avatar; omit or null to clear")
	private AvatarData avatar;
	@XmlTransient
	@Schema(hidden = true)
	private byte[] groupReference;

	protected SetGroupAvatarTransactionData() { super(TransactionType.SET_GROUP_AVATAR); }

	public void afterUnmarshal(Unmarshaller u, Object parent) { this.creatorPublicKey = this.ownerPublicKey; }

	public SetGroupAvatarTransactionData(BaseTransactionData base, int groupId, AvatarData avatar, byte[] groupReference) {
		super(TransactionType.SET_GROUP_AVATAR, base);
		this.ownerPublicKey = base.creatorPublicKey;
		this.groupId = groupId;
		this.avatar = avatar;
		this.groupReference = groupReference;
	}

	public SetGroupAvatarTransactionData(BaseTransactionData base, int groupId, AvatarData avatar) {
		this(base, groupId, avatar, null);
	}

	public byte[] getOwnerPublicKey() { return this.ownerPublicKey; }
	public int getGroupId() { return this.groupId; }
	public AvatarData getAvatar() { return this.avatar; }
	public byte[] getGroupReference() { return this.groupReference; }
	public void setGroupReference(byte[] groupReference) { this.groupReference = groupReference; }
}
