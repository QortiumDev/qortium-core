package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustDerivationData {

	private byte[] accountPublicKey;
	private String accountAddress;
	private AccountTrustStatus derivedTrustStatus;
	private int derivedTrustStatusValue;
	private int derivedTrustWeightPercent;
	private int blocksMinted;
	private int mintingLevel;
	private int effectiveVoteWeight;
	private boolean mintingSeedMember;
	private Integer snapshotHeight;
	private Long snapshotTimestamp;
	private boolean live;
	private List<AccountTrustCategoryData> categories;

	protected AccountTrustDerivationData() {
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			boolean mintingSeedMember, List<AccountTrustCategoryData> categories) {
		this(accountPublicKey, accountAddress, derivedTrustStatus, mintingSeedMember, 0, 0, 0, null, null, true,
				categories);
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			boolean mintingSeedMember, int blocksMinted, int mintingLevel, int effectiveVoteWeight,
			Integer snapshotHeight, Long snapshotTimestamp, boolean live,
			List<AccountTrustCategoryData> categories) {
		this(accountPublicKey, accountAddress, derivedTrustStatus,
				(derivedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : derivedTrustStatus).getVoteWeightPercent(),
				blocksMinted, mintingLevel, effectiveVoteWeight, mintingSeedMember, snapshotHeight, snapshotTimestamp, live,
				categories);
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			int derivedTrustWeightPercent, int blocksMinted, int mintingLevel, int effectiveVoteWeight,
			boolean mintingSeedMember, Integer snapshotHeight, Long snapshotTimestamp, boolean live,
			List<AccountTrustCategoryData> categories) {
		AccountTrustStatus effectiveDerivedTrustStatus = derivedTrustStatus == null ? AccountTrustStatus.UNVERIFIED
				: derivedTrustStatus;

		this.accountPublicKey = accountPublicKey;
		this.accountAddress = accountAddress;
		this.derivedTrustStatus = effectiveDerivedTrustStatus;
		this.derivedTrustStatusValue = effectiveDerivedTrustStatus.getValue();
		this.derivedTrustWeightPercent = derivedTrustWeightPercent;
		this.blocksMinted = blocksMinted;
		this.mintingLevel = mintingLevel;
		this.effectiveVoteWeight = effectiveVoteWeight;
		this.mintingSeedMember = mintingSeedMember;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
		this.live = live;
		this.categories = categories == null ? new ArrayList<>() : categories;
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			int derivedTrustWeightPercent, boolean mintingSeedMember, Integer snapshotHeight, Long snapshotTimestamp,
			boolean live, List<AccountTrustCategoryData> categories) {
		this(accountPublicKey, accountAddress, derivedTrustStatus, derivedTrustWeightPercent, 0, 0, 0, mintingSeedMember,
				snapshotHeight, snapshotTimestamp, live, categories);
	}

	public byte[] getAccountPublicKey() {
		return this.accountPublicKey;
	}

	public String getAccountAddress() {
		return this.accountAddress;
	}

	public AccountTrustStatus getDerivedTrustStatus() {
		return this.derivedTrustStatus;
	}

	public int getDerivedTrustStatusValue() {
		return this.derivedTrustStatusValue;
	}

	public int getDerivedTrustWeightPercent() {
		return this.derivedTrustWeightPercent;
	}

	public int getBlocksMinted() {
		return this.blocksMinted;
	}

	public int getMintingLevel() {
		return this.mintingLevel;
	}

	public int getEffectiveVoteWeight() {
		return this.effectiveVoteWeight;
	}

	public boolean isMintingSeedMember() {
		return this.mintingSeedMember;
	}

	public Integer getSnapshotHeight() {
		return this.snapshotHeight;
	}

	public Long getSnapshotTimestamp() {
		return this.snapshotTimestamp;
	}

	public boolean isLive() {
		return this.live;
	}

	public List<AccountTrustCategoryData> getCategories() {
		return this.categories;
	}
}
