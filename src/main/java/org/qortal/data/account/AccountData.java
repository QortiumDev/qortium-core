package org.qortal.data.account;

import org.qortal.group.Group;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class AccountData {

	// Properties
	protected String address;
	protected byte[] publicKey;
	protected int defaultGroupId;
	protected int level;
	protected int blocksMinted;
	protected AccountTrustStatus trustStatus;

	// Constructors

	// For JAXB
	protected AccountData() {
	}

	public AccountData(String address, byte[] publicKey, int defaultGroupId, int level, int blocksMinted) {
		this(address, publicKey, defaultGroupId, level, blocksMinted, AccountTrustStatus.UNVERIFIED);
	}

	public AccountData(String address, byte[] publicKey, int defaultGroupId, int level, int blocksMinted, AccountTrustStatus trustStatus) {
		this.address = address;
		this.publicKey = publicKey;
		this.defaultGroupId = defaultGroupId;
		this.level = level;
		this.blocksMinted = blocksMinted;
		this.trustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;
	}

	public AccountData(String address) {
		this(address, null, Group.NO_GROUP, 0, 0);
	}

	// Getters/Setters

	public String getAddress() {
		return this.address;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	public void setPublicKey(byte[] publicKey) {
		this.publicKey = publicKey;
	}

	public int getDefaultGroupId() {
		return this.defaultGroupId;
	}

	public void setDefaultGroupId(int defaultGroupId) {
		this.defaultGroupId = defaultGroupId;
	}

	public int getLevel() {
		return this.level;
	}

	public void setLevel(int level) {
		this.level = level;
	}

	public int getBlocksMinted() {
		return this.blocksMinted;
	}

	public void setBlocksMinted(int blocksMinted) {
		this.blocksMinted = blocksMinted;
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus == null ? AccountTrustStatus.UNVERIFIED : this.trustStatus;
	}

	public void setTrustStatus(AccountTrustStatus trustStatus) {
		this.trustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;
	}

	// Comparison

	@Override
	public boolean equals(Object b) {
		if (!(b instanceof AccountData))
			return false;

		return this.getAddress().equals(((AccountData) b).getAddress());
	}

	@Override
	public int hashCode() {
		return this.getAddress().hashCode();
	}

}
