package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustCategoryData {
	private AccountRatingCategory category;
	private long score;
	private long levelScore;
	private long levelScoreCap;
	private int level;
	private AccountTrustStatus mappedTrustStatus;
	private int mappedTrustStatusValue;
	private AccountTrustRatingCountsData inboundRatings;
	private List<AccountTrustCategoryImpactData> impacts;

	protected AccountTrustCategoryData() {
	}

	public AccountTrustCategoryData(AccountRatingCategory category, long score, int level,
			AccountTrustStatus mappedTrustStatus, AccountTrustRatingCountsData inboundRatings,
			List<AccountTrustCategoryImpactData> impacts) {
		this(category, score, score, 0L, level, mappedTrustStatus, inboundRatings, impacts);
	}

	public AccountTrustCategoryData(AccountRatingCategory category, long score, long levelScore, long levelScoreCap,
			int level, AccountTrustStatus mappedTrustStatus, AccountTrustRatingCountsData inboundRatings,
			List<AccountTrustCategoryImpactData> impacts) {
		AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null
				? AccountTrustStatus.UNVERIFIED
				: mappedTrustStatus;

		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.score = score;
		this.levelScore = levelScore;
		this.levelScoreCap = levelScoreCap;
		this.level = level;
		this.mappedTrustStatus = effectiveMappedStatus;
		this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
		this.inboundRatings = inboundRatings == null ? new AccountTrustRatingCountsData() : inboundRatings;
		this.impacts = impacts == null ? new ArrayList<>() : impacts;
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

	public AccountTrustRatingCountsData getInboundRatings() {
		return this.inboundRatings;
	}

	public List<AccountTrustCategoryImpactData> getImpacts() {
		return this.impacts;
	}
}
