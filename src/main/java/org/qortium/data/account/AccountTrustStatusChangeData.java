package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustStatusChangeData {

	private byte[] accountPublicKey;
	private String accountAddress;
	private AccountRatingCategory category;
	private int previousLevel;
	private int newLevel;
	private AccountTrustStatus previousTrustStatus;
	private int previousTrustStatusValue;
	private AccountTrustStatus newTrustStatus;
	private int newTrustStatusValue;
	private long previousScore;
	private long newScore;
	private long previousLevelScore;
	private long newLevelScore;
	private boolean previousMintingSeedMember;
	private boolean newMintingSeedMember;
	private int previousSnapshotHeight;
	private long previousSnapshotTimestamp;
	private int snapshotHeight;
	private long snapshotTimestamp;

	protected AccountTrustStatusChangeData() {
	}

	public AccountTrustStatusChangeData(byte[] accountPublicKey, String accountAddress, AccountRatingCategory category,
			int previousLevel, int newLevel, AccountTrustStatus previousTrustStatus, AccountTrustStatus newTrustStatus,
			long previousScore, long newScore, long previousLevelScore, long newLevelScore,
			boolean previousMintingSeedMember, boolean newMintingSeedMember, int previousSnapshotHeight,
			long previousSnapshotTimestamp, int snapshotHeight, long snapshotTimestamp) {
		AccountTrustStatus effectivePreviousStatus = previousTrustStatus == null
				? AccountTrustStatus.UNVERIFIED
				: previousTrustStatus;
		AccountTrustStatus effectiveNewStatus = newTrustStatus == null
				? AccountTrustStatus.UNVERIFIED
				: newTrustStatus;

		this.accountPublicKey = accountPublicKey;
		this.accountAddress = accountAddress;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.previousLevel = previousLevel;
		this.newLevel = newLevel;
		this.previousTrustStatus = effectivePreviousStatus;
		this.previousTrustStatusValue = effectivePreviousStatus.getValue();
		this.newTrustStatus = effectiveNewStatus;
		this.newTrustStatusValue = effectiveNewStatus.getValue();
		this.previousScore = previousScore;
		this.newScore = newScore;
		this.previousLevelScore = previousLevelScore;
		this.newLevelScore = newLevelScore;
		this.previousMintingSeedMember = previousMintingSeedMember;
		this.newMintingSeedMember = newMintingSeedMember;
		this.previousSnapshotHeight = previousSnapshotHeight;
		this.previousSnapshotTimestamp = previousSnapshotTimestamp;
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

	public int getPreviousLevel() {
		return this.previousLevel;
	}

	public int getNewLevel() {
		return this.newLevel;
	}

	public AccountTrustStatus getPreviousTrustStatus() {
		return this.previousTrustStatus;
	}

	public int getPreviousTrustStatusValue() {
		return this.previousTrustStatusValue;
	}

	public AccountTrustStatus getNewTrustStatus() {
		return this.newTrustStatus;
	}

	public int getNewTrustStatusValue() {
		return this.newTrustStatusValue;
	}

	public long getPreviousScore() {
		return this.previousScore;
	}

	public long getNewScore() {
		return this.newScore;
	}

	public long getPreviousLevelScore() {
		return this.previousLevelScore;
	}

	public long getNewLevelScore() {
		return this.newLevelScore;
	}

	public boolean isPreviousMintingSeedMember() {
		return this.previousMintingSeedMember;
	}

	public boolean isNewMintingSeedMember() {
		return this.newMintingSeedMember;
	}

	public int getPreviousSnapshotHeight() {
		return this.previousSnapshotHeight;
	}

	public long getPreviousSnapshotTimestamp() {
		return this.previousSnapshotTimestamp;
	}

	public int getSnapshotHeight() {
		return this.snapshotHeight;
	}

	public long getSnapshotTimestamp() {
		return this.snapshotTimestamp;
	}
}
