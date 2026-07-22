package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

/** Authorizes an exact, already-confirmed public QDN THUMBNAIL transaction as a group avatar. */
@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("SET_GROUP_AVATAR")
public class SetGroupAvatarTransactionData extends TransactionData {
	private byte[] ownerPublicKey;
	private int groupId;
	@Schema(description = "Base58 signature of the confirmed public THUMBNAIL ARBITRARY transaction; omit or null to clear")
	private byte[] avatarSignature;
	@XmlTransient
	@Schema(hidden = true)
	private byte[] groupReference;

	protected SetGroupAvatarTransactionData() { super(TransactionType.SET_GROUP_AVATAR); }

	public void afterUnmarshal(Unmarshaller u, Object parent) { this.creatorPublicKey = this.ownerPublicKey; }

	public SetGroupAvatarTransactionData(BaseTransactionData base, int groupId, byte[] avatarSignature, byte[] groupReference) {
		super(TransactionType.SET_GROUP_AVATAR, base);
		this.ownerPublicKey = base.creatorPublicKey;
		this.groupId = groupId;
		this.avatarSignature = avatarSignature;
		this.groupReference = groupReference;
	}

	public SetGroupAvatarTransactionData(BaseTransactionData base, int groupId, byte[] avatarSignature) {
		this(base, groupId, avatarSignature, null);
	}

	public byte[] getOwnerPublicKey() { return this.ownerPublicKey; }
	public int getGroupId() { return this.groupId; }
	public byte[] getAvatarSignature() { return this.avatarSignature; }
	public byte[] getGroupReference() { return this.groupReference; }
	public void setGroupReference(byte[] groupReference) { this.groupReference = groupReference; }
}
