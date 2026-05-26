package org.qortium.data.account;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.group.Group;

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
	@Schema(description = "Raw minted-block history. This remains account history and is not the effective governance weight.")
	protected int blocksMinted;
	@Schema(description = "Active Subject-derived trust status used for trust-weighted voting and rating influence")
	protected AccountTrustStatus trustStatus;
	@Schema(description = "Numeric storage value for the active trust status")
	protected int trustStatusValue;
	@Schema(description = "Percent of raw blocksMinted that counts as effective voting and rating weight")
	protected int trustWeightPercent;
	@Schema(description = "Whether the active trust status allows minting when the account also has base minting permission")
	protected boolean trustAllowsMinting;
	@Schema(description = "Effective governance and rating weight after applying the active trust multiplier to blocksMinted")
	protected int effectiveVoteWeight;
	@Schema(description = "Block height of the active trust snapshot used for trust fields")
	protected Integer trustSnapshotHeight;
	@Schema(description = "Block timestamp of the active trust snapshot used for trust fields")
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

	public void setTrustSnapshot(AccountTrustSnapshotData snapshotData, int trustWeightPercent) {
		this.trustStatus = snapshotData == null ? AccountTrustStatus.UNVERIFIED : snapshotData.getMappedTrustStatus();
		this.trustSnapshotHeight = snapshotData == null ? null : snapshotData.getSnapshotHeight();
		this.trustSnapshotTimestamp = snapshotData == null ? null : snapshotData.getSnapshotTimestamp();
		this.updateTrustFields(trustWeightPercent);
	}

	private void updateTrustFields() {
		AccountTrustStatus trustStatus = this.getTrustStatus();
		this.updateTrustFields(trustStatus.getVoteWeightPercent());
	}

	private void updateTrustFields(int trustWeightPercent) {
		AccountTrustStatus trustStatus = this.getTrustStatus();
		this.trustStatusValue = trustStatus.getValue();
		this.trustWeightPercent = trustWeightPercent;
		this.trustAllowsMinting = trustStatus.canMint();
		this.effectiveVoteWeight = AccountTrustPolicy.calculateEffectiveVoteWeight(this.blocksMinted, trustWeightPercent);
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
