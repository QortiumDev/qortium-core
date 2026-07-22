package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.transaction.Transaction.TransactionType;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("SET_ACCOUNT_AVATAR")
public class SetAccountAvatarTransactionData extends TransactionData {

	private byte[] ownerPublicKey;
	private byte[] avatarSignature;

	@XmlTransient
	@Schema(hidden = true)
	private byte[] previousAvatarSignature;

	protected SetAccountAvatarTransactionData() {
		super(TransactionType.SET_ACCOUNT_AVATAR);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public SetAccountAvatarTransactionData(BaseTransactionData base, byte[] avatarSignature, byte[] previousAvatarSignature) {
		super(TransactionType.SET_ACCOUNT_AVATAR, base);
		this.ownerPublicKey = base.creatorPublicKey;
		this.avatarSignature = avatarSignature;
		this.previousAvatarSignature = previousAvatarSignature;
	}

	public SetAccountAvatarTransactionData(BaseTransactionData base, byte[] avatarSignature) {
		this(base, avatarSignature, null);
	}

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public byte[] getAvatarSignature() {
		return this.avatarSignature;
	}

	public byte[] getPreviousAvatarSignature() {
		return this.previousAvatarSignature;
	}

	public void setPreviousAvatarSignature(byte[] value) {
		this.previousAvatarSignature = value;
	}
}
