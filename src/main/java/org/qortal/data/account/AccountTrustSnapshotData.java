package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustSnapshotData {

	private byte[] accountPublicKey;
	private String accountAddress;
	private AccountRatingCategory category;
	private long score;
	private int level;
	private AccountTrustStatus mappedTrustStatus;
	private int mappedTrustStatusValue;
	private int mappedTrustWeightPercent;
	private boolean mintingSeedMember;
	private AccountTrustPreviewData.RatingCounts inboundRatings;
	private int snapshotHeight;
	private long snapshotTimestamp;

	protected AccountTrustSnapshotData() {
	}

	public AccountTrustSnapshotData(byte[] accountPublicKey, String accountAddress, AccountRatingCategory category,
			long score, int level, AccountTrustStatus mappedTrustStatus, boolean mintingSeedMember,
			AccountTrustPreviewData.RatingCounts inboundRatings, int snapshotHeight, long snapshotTimestamp) {
		AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

		this.accountPublicKey = accountPublicKey;
		this.accountAddress = accountAddress;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.score = score;
		this.level = level;
		this.mappedTrustStatus = effectiveMappedStatus;
		this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
		this.mappedTrustWeightPercent = effectiveMappedStatus.getVoteWeightPercent();
		this.mintingSeedMember = mintingSeedMember;
		this.inboundRatings = inboundRatings == null ? new AccountTrustPreviewData.RatingCounts() : inboundRatings;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
	}

	public byte[] getAccountPublicKey() {
		return this.accountPublicKey;
	}

	public String getAccountAddress() {
		return this.accountAddress;
	}

	public AccountRatingCategory getCategory() {
		return this.category;
	}

	public long getScore() {
		return this.score;
	}

	public int getLevel() {
		return this.level;
	}

	public AccountTrustStatus getMappedTrustStatus() {
		return this.mappedTrustStatus;
	}

	public int getMappedTrustStatusValue() {
		return this.mappedTrustStatusValue;
	}

	public int getMappedTrustWeightPercent() {
		return this.mappedTrustWeightPercent;
	}

	public boolean isMintingSeedMember() {
		return this.mintingSeedMember;
	}

	public AccountTrustPreviewData.RatingCounts getInboundRatings() {
		return this.inboundRatings;
	}

	public int getSnapshotHeight() {
		return this.snapshotHeight;
	}

	public long getSnapshotTimestamp() {
		return this.snapshotTimestamp;
	}
}
