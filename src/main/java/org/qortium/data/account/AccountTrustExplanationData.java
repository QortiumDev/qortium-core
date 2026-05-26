package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustExplanationData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private AccountTrustStatus trustStatus;
	private int trustStatusValue;
	private int trustWeightPercent;
	private AccountRatingCategory activeWeightCategory;
	private boolean mintingSeedMember;
	private Integer snapshotHeight;
	private Long snapshotTimestamp;
	private boolean live;
	private List<CategoryExplanation> categories;

	protected AccountTrustExplanationData() {
	}

	public AccountTrustExplanationData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			AccountRatingCategory activeWeightCategory, boolean mintingSeedMember, Integer snapshotHeight,
			Long snapshotTimestamp, boolean live, List<CategoryExplanation> categories) {
		this(targetPublicKey, targetAddress, trustStatus,
				(trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus).getVoteWeightPercent(),
				activeWeightCategory, mintingSeedMember, snapshotHeight, snapshotTimestamp, live, categories);
	}

	public AccountTrustExplanationData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			int trustWeightPercent, AccountRatingCategory activeWeightCategory, boolean mintingSeedMember,
			Integer snapshotHeight, Long snapshotTimestamp, boolean live, List<CategoryExplanation> categories) {
		AccountTrustStatus effectiveTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustStatus = effectiveTrustStatus;
		this.trustStatusValue = effectiveTrustStatus.getValue();
		this.trustWeightPercent = trustWeightPercent;
		this.activeWeightCategory = activeWeightCategory == null ? AccountRatingCategory.SUBJECT : activeWeightCategory;
		this.mintingSeedMember = mintingSeedMember;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
		this.live = live;
		this.categories = categories == null ? new ArrayList<>() : categories;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus;
	}

	public int getTrustStatusValue() {
		return this.trustStatusValue;
	}

	public int getTrustWeightPercent() {
		return this.trustWeightPercent;
	}

	public AccountRatingCategory getActiveWeightCategory() {
		return this.activeWeightCategory;
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

	public List<CategoryExplanation> getCategories() {
		return this.categories;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryExplanation {
		private AccountRatingCategory category;
		private long score;
		private long levelScore;
		private long levelScoreCap;
		private int level;
		private AccountTrustStatus mappedTrustStatus;
		private int mappedTrustStatusValue;
		private int mappedTrustWeightPercent;
		private AccountTrustRatingCountsData inboundRatings;
		private List<ConfiguredLevel> configuredLevels;
		private int positiveMinBranchCount;
		private long suspiciousThreshold;
		private long suspiciousLevelScoreCap;
		private int suspiciousMinRaterCount;
		private int suspiciousMinBranchCount;
		private int suspiciousMinRatingConfidence;
		private List<Requirement> requirements;
		private List<AccountTrustCategoryImpactData> topPositiveImpacts;
		private List<AccountTrustCategoryImpactData> topNegativeImpacts;

		protected CategoryExplanation() {
		}

		public CategoryExplanation(AccountRatingCategory category, long score, long levelScore, long levelScoreCap,
				int level, AccountTrustStatus mappedTrustStatus, AccountTrustRatingCountsData inboundRatings,
				List<ConfiguredLevel> configuredLevels, long suspiciousThreshold, long suspiciousLevelScoreCap,
				int positiveMinBranchCount, int suspiciousMinRaterCount, int suspiciousMinBranchCount,
				int suspiciousMinRatingConfidence, List<Requirement> requirements,
				List<AccountTrustCategoryImpactData> topPositiveImpacts, List<AccountTrustCategoryImpactData> topNegativeImpacts) {
			this(category, score, levelScore, levelScoreCap, level, mappedTrustStatus,
					(mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus).getVoteWeightPercent(),
					inboundRatings, configuredLevels, suspiciousThreshold, suspiciousLevelScoreCap, positiveMinBranchCount,
					suspiciousMinRaterCount, suspiciousMinBranchCount, suspiciousMinRatingConfidence, requirements,
					topPositiveImpacts, topNegativeImpacts);
		}

		public CategoryExplanation(AccountRatingCategory category, long score, long levelScore, long levelScoreCap,
				int level, AccountTrustStatus mappedTrustStatus, int mappedTrustWeightPercent,
				AccountTrustRatingCountsData inboundRatings, List<ConfiguredLevel> configuredLevels,
				long suspiciousThreshold, long suspiciousLevelScoreCap, int positiveMinBranchCount,
				int suspiciousMinRaterCount, int suspiciousMinBranchCount, int suspiciousMinRatingConfidence,
				List<Requirement> requirements, List<AccountTrustCategoryImpactData> topPositiveImpacts,
				List<AccountTrustCategoryImpactData> topNegativeImpacts) {
			AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.score = score;
			this.levelScore = levelScore;
			this.levelScoreCap = levelScoreCap;
			this.level = level;
			this.mappedTrustStatus = effectiveMappedStatus;
			this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
			this.mappedTrustWeightPercent = mappedTrustWeightPercent;
			this.inboundRatings = inboundRatings == null ? new AccountTrustRatingCountsData() : inboundRatings;
			this.configuredLevels = configuredLevels == null ? new ArrayList<>() : configuredLevels;
			this.positiveMinBranchCount = positiveMinBranchCount;
			this.suspiciousThreshold = suspiciousThreshold;
			this.suspiciousLevelScoreCap = suspiciousLevelScoreCap;
			this.suspiciousMinRaterCount = suspiciousMinRaterCount;
			this.suspiciousMinBranchCount = suspiciousMinBranchCount;
			this.suspiciousMinRatingConfidence = suspiciousMinRatingConfidence;
			this.requirements = requirements == null ? new ArrayList<>() : requirements;
			this.topPositiveImpacts = topPositiveImpacts == null ? new ArrayList<>() : topPositiveImpacts;
			this.topNegativeImpacts = topNegativeImpacts == null ? new ArrayList<>() : topNegativeImpacts;
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

		public AccountTrustRatingCountsData getInboundRatings() {
			return this.inboundRatings;
		}

		public List<ConfiguredLevel> getConfiguredLevels() {
			return this.configuredLevels;
		}

		public int getPositiveMinBranchCount() {
			return this.positiveMinBranchCount;
		}

		public long getSuspiciousThreshold() {
			return this.suspiciousThreshold;
		}

		public long getSuspiciousLevelScoreCap() {
			return this.suspiciousLevelScoreCap;
		}

		public int getSuspiciousMinRaterCount() {
			return this.suspiciousMinRaterCount;
		}

		public int getSuspiciousMinBranchCount() {
			return this.suspiciousMinBranchCount;
		}

		public int getSuspiciousMinRatingConfidence() {
			return this.suspiciousMinRatingConfidence;
		}

		public List<Requirement> getRequirements() {
			return this.requirements;
		}

		public List<AccountTrustCategoryImpactData> getTopPositiveImpacts() {
			return this.topPositiveImpacts;
		}

		public List<AccountTrustCategoryImpactData> getTopNegativeImpacts() {
			return this.topNegativeImpacts;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class ConfiguredLevel {
		private int level;
		private long threshold;
		private long levelScoreCap;

		protected ConfiguredLevel() {
		}

		public ConfiguredLevel(int level, long threshold, long levelScoreCap) {
			this.level = level;
			this.threshold = threshold;
			this.levelScoreCap = levelScoreCap;
		}

		public int getLevel() {
			return this.level;
		}

		public long getThreshold() {
			return this.threshold;
		}

		public long getLevelScoreCap() {
			return this.levelScoreCap;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class Requirement {
		private String name;
		private boolean passed;
		private String actual;
		private String required;
		private String description;

		protected Requirement() {
		}

		public Requirement(String name, boolean passed, String actual, String required, String description) {
			this.name = name;
			this.passed = passed;
			this.actual = actual;
			this.required = required;
			this.description = description;
		}

		public String getName() {
			return this.name;
		}

		public boolean isPassed() {
			return this.passed;
		}

		public String getActual() {
			return this.actual;
		}

		public String getRequired() {
			return this.required;
		}

		public String getDescription() {
			return this.description;
		}
	}
}
