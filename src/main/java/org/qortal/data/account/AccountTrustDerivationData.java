package org.qortal.data.account;

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
	private boolean mintingSeedMember;
	private Integer snapshotHeight;
	private Long snapshotTimestamp;
	private boolean live;
	private List<AccountTrustPreviewData.CategoryTrust> categories;

	protected AccountTrustDerivationData() {
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			boolean mintingSeedMember, List<AccountTrustPreviewData.CategoryTrust> categories) {
		this(accountPublicKey, accountAddress, derivedTrustStatus, mintingSeedMember, null, null, true, categories);
	}

	public AccountTrustDerivationData(byte[] accountPublicKey, String accountAddress, AccountTrustStatus derivedTrustStatus,
			boolean mintingSeedMember, Integer snapshotHeight, Long snapshotTimestamp, boolean live,
			List<AccountTrustPreviewData.CategoryTrust> categories) {
		AccountTrustStatus effectiveDerivedTrustStatus = derivedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : derivedTrustStatus;

		this.accountPublicKey = accountPublicKey;
		this.accountAddress = accountAddress;
		this.derivedTrustStatus = effectiveDerivedTrustStatus;
		this.derivedTrustStatusValue = effectiveDerivedTrustStatus.getValue();
		this.derivedTrustWeightPercent = effectiveDerivedTrustStatus.getVoteWeightPercent();
		this.mintingSeedMember = mintingSeedMember;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
		this.live = live;
		this.categories = categories == null ? new ArrayList<>() : categories;
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

	public List<AccountTrustPreviewData.CategoryTrust> getCategories() {
		return this.categories;
	}
}
