package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.data.avatar.AvatarData;
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

	@Schema(description = "Pointer to the QDN resource to show as the avatar; omit or null to clear")
	private AvatarData avatar;

	@XmlTransient
	@Schema(hidden = true)
	private AvatarData previousAvatar;

	protected SetAccountAvatarTransactionData() {
		super(TransactionType.SET_ACCOUNT_AVATAR);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.ownerPublicKey;
	}

	public SetAccountAvatarTransactionData(BaseTransactionData base, AvatarData avatar, AvatarData previousAvatar) {
		super(TransactionType.SET_ACCOUNT_AVATAR, base);
		this.ownerPublicKey = base.creatorPublicKey;
		this.avatar = avatar;
		this.previousAvatar = previousAvatar;
	}

	public SetAccountAvatarTransactionData(BaseTransactionData base, AvatarData avatar) {
		this(base, avatar, null);
	}

	public byte[] getOwnerPublicKey() {
		return this.ownerPublicKey;
	}

	public AvatarData getAvatar() {
		return this.avatar;
	}

	public AvatarData getPreviousAvatar() {
		return this.previousAvatar;
	}

	public void setPreviousAvatar(AvatarData value) {
		this.previousAvatar = value;
	}
}
