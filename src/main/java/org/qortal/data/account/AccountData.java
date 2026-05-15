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
	protected int trustStatusValue;
	protected int trustWeightPercent;
	protected boolean trustAllowsMinting;
	protected int effectiveVoteWeight;
	protected Integer trustSnapshotHeight;
	protected Long trustSnapshotTimestamp;

	// Constructors

	// For JAXB
	protected AccountData() {
	}

	public AccountData(String address, byte[] publicKey, int defaultGroupId, int level, int blocksMinted) {
		this.address = address;
		this.publicKey = publicKey;
		this.defaultGroupId = defaultGroupId;
		this.level = level;
		this.blocksMinted = blocksMinted;
		this.setTrustSnapshot(null);
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
		this.updateTrustFields();
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus == null ? AccountTrustStatus.UNVERIFIED : this.trustStatus;
	}

	public int getTrustStatusValue() {
		return this.trustStatusValue;
	}

	public int getTrustWeightPercent() {
		return this.trustWeightPercent;
	}

	public boolean isTrustAllowsMinting() {
		return this.trustAllowsMinting;
	}

	public int getEffectiveVoteWeight() {
		return this.effectiveVoteWeight;
	}

	public Integer getTrustSnapshotHeight() {
		return this.trustSnapshotHeight;
	}

	public Long getTrustSnapshotTimestamp() {
		return this.trustSnapshotTimestamp;
	}

	public void setTrustSnapshot(AccountTrustSnapshotData snapshotData) {
		this.trustStatus = snapshotData == null ? AccountTrustStatus.UNVERIFIED : snapshotData.getMappedTrustStatus();
		this.trustSnapshotHeight = snapshotData == null ? null : snapshotData.getSnapshotHeight();
		this.trustSnapshotTimestamp = snapshotData == null ? null : snapshotData.getSnapshotTimestamp();
		this.updateTrustFields();
	}

	private void updateTrustFields() {
		AccountTrustStatus trustStatus = this.getTrustStatus();

		this.trustStatusValue = trustStatus.getValue();
		this.trustWeightPercent = trustStatus.getVoteWeightPercent();
		this.trustAllowsMinting = trustStatus.canMint();
		this.effectiveVoteWeight = trustStatus.calculateEffectiveVoteWeight(this.blocksMinted);
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
