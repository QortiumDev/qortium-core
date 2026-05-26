package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustSnapshotData {

	private byte[] accountPublicKey;
	private String accountAddress;
	private AccountRatingCategory category;
	private long score;
	private long levelScore;
	private long levelScoreCap;
	private int level;
	private AccountTrustStatus mappedTrustStatus;
	private int mappedTrustStatusValue;
	private int mappedTrustWeightPercent;
	private boolean mintingSeedMember;
	private AccountTrustRatingCountsData inboundRatings;
	private int snapshotHeight;
	private long snapshotTimestamp;

	protected AccountTrustSnapshotData() {
	}

	public AccountTrustSnapshotData(byte[] accountPublicKey, String accountAddress, AccountRatingCategory category,
			long score, int level, AccountTrustStatus mappedTrustStatus, boolean mintingSeedMember,
			AccountTrustRatingCountsData inboundRatings, int snapshotHeight, long snapshotTimestamp) {
		this(accountPublicKey, accountAddress, category, score, score, 0L, level, mappedTrustStatus, mintingSeedMember,
				inboundRatings, snapshotHeight, snapshotTimestamp);
	}

	public AccountTrustSnapshotData(byte[] accountPublicKey, String accountAddress, AccountRatingCategory category,
			long score, long levelScore, long levelScoreCap, int level, AccountTrustStatus mappedTrustStatus,
			boolean mintingSeedMember, AccountTrustRatingCountsData inboundRatings, int snapshotHeight,
			long snapshotTimestamp) {
		this(accountPublicKey, accountAddress, category, score, levelScore, levelScoreCap, level, mappedTrustStatus,
				mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED.getVoteWeightPercent()
						: mappedTrustStatus.getVoteWeightPercent(),
				mintingSeedMember, inboundRatings, snapshotHeight, snapshotTimestamp);
	}

	public AccountTrustSnapshotData(byte[] accountPublicKey, String accountAddress, AccountRatingCategory category,
			long score, long levelScore, long levelScoreCap, int level, AccountTrustStatus mappedTrustStatus,
			int mappedTrustWeightPercent, boolean mintingSeedMember, AccountTrustRatingCountsData inboundRatings,
			int snapshotHeight, long snapshotTimestamp) {
		AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

		this.accountPublicKey = accountPublicKey;
		this.accountAddress = accountAddress;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.score = score;
		this.levelScore = levelScore;
		this.levelScoreCap = levelScoreCap;
		this.level = level;
		this.mappedTrustStatus = effectiveMappedStatus;
		this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
		this.mappedTrustWeightPercent = mappedTrustWeightPercent;
		this.mintingSeedMember = mintingSeedMember;
		this.inboundRatings = inboundRatings == null ? new AccountTrustRatingCountsData() : inboundRatings;
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

	public long getLevelScore() {
		return this.levelScore;
	}

	public long getLevelScoreCap() {
		return this.levelScoreCap;
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

	public AccountTrustRatingCountsData getInboundRatings() {
		return this.inboundRatings;
	}

	public int getSnapshotHeight() {
		return this.snapshotHeight;
	}

	public long getSnapshotTimestamp() {
		return this.snapshotTimestamp;
	}
}
