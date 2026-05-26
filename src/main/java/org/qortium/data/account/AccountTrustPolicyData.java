package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustPolicyData {

	private AccountRatingCategory activeWeightCategory;
	private long startingEnergy;
	private int managerEnergyHops;
	private int positiveMinBranchCount;
	private int suspiciousMinRaterCount;
	private int suspiciousMinBranchCount;
	private int suspiciousMinRatingConfidence;
	private int accountRatingChangeCooldownBlocks;
	private List<StatusVoteWeight> statusVoteWeights;
	private List<CategoryPolicy> categoryPolicies;

	protected AccountTrustPolicyData() {
	}

	public AccountTrustPolicyData(AccountRatingCategory activeWeightCategory, long startingEnergy, int managerEnergyHops,
			int positiveMinBranchCount, int suspiciousMinRaterCount, int suspiciousMinBranchCount,
			int suspiciousMinRatingConfidence, int accountRatingChangeCooldownBlocks,
			List<StatusVoteWeight> statusVoteWeights, List<CategoryPolicy> categoryPolicies) {
		this.activeWeightCategory = activeWeightCategory == null ? AccountRatingCategory.SUBJECT : activeWeightCategory;
		this.startingEnergy = startingEnergy;
		this.managerEnergyHops = managerEnergyHops;
		this.positiveMinBranchCount = positiveMinBranchCount;
		this.suspiciousMinRaterCount = suspiciousMinRaterCount;
		this.suspiciousMinBranchCount = suspiciousMinBranchCount;
		this.suspiciousMinRatingConfidence = suspiciousMinRatingConfidence;
		this.accountRatingChangeCooldownBlocks = accountRatingChangeCooldownBlocks;
		this.statusVoteWeights = statusVoteWeights == null ? new ArrayList<>() : statusVoteWeights;
		this.categoryPolicies = categoryPolicies == null ? new ArrayList<>() : categoryPolicies;
	}

	public AccountRatingCategory getActiveWeightCategory() {
		return this.activeWeightCategory;
	}

	public long getStartingEnergy() {
		return this.startingEnergy;
	}

	public int getManagerEnergyHops() {
		return this.managerEnergyHops;
	}

	public int getPositiveMinBranchCount() {
		return this.positiveMinBranchCount;
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

	public int getAccountRatingChangeCooldownBlocks() {
		return this.accountRatingChangeCooldownBlocks;
	}

	public List<StatusVoteWeight> getStatusVoteWeights() {
		return this.statusVoteWeights;
	}

	public List<CategoryPolicy> getCategoryPolicies() {
		return this.categoryPolicies;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class StatusVoteWeight {
		private AccountTrustStatus status;
		private int statusValue;
		private int voteWeightPercent;
		private boolean trustAllowsMinting;

		protected StatusVoteWeight() {
		}

		public StatusVoteWeight(AccountTrustStatus status, int voteWeightPercent) {
			AccountTrustStatus effectiveStatus = status == null ? AccountTrustStatus.UNVERIFIED : status;

			this.status = effectiveStatus;
			this.statusValue = effectiveStatus.getValue();
			this.voteWeightPercent = voteWeightPercent;
			this.trustAllowsMinting = effectiveStatus.canMint();
		}

		public AccountTrustStatus getStatus() {
			return this.status;
		}

		public int getStatusValue() {
			return this.statusValue;
		}

		public int getVoteWeightPercent() {
			return this.voteWeightPercent;
		}

		public boolean isTrustAllowsMinting() {
			return this.trustAllowsMinting;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryPolicy {
		private AccountRatingCategory category;
		private List<LevelPolicy> levels;
		private long suspiciousThreshold;
		private long suspiciousLevelScoreCap;

		protected CategoryPolicy() {
		}

		public CategoryPolicy(AccountRatingCategory category, List<LevelPolicy> levels, long suspiciousThreshold,
				long suspiciousLevelScoreCap) {
			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.levels = levels == null ? new ArrayList<>() : levels;
			this.suspiciousThreshold = suspiciousThreshold;
			this.suspiciousLevelScoreCap = suspiciousLevelScoreCap;
		}

		public AccountRatingCategory getCategory() {
			return this.category;
		}

		public List<LevelPolicy> getLevels() {
			return this.levels;
		}

		public long getSuspiciousThreshold() {
			return this.suspiciousThreshold;
		}

		public long getSuspiciousLevelScoreCap() {
			return this.suspiciousLevelScoreCap;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class LevelPolicy {
		private int level;
		private AccountTrustStatus mappedTrustStatus;
		private int mappedTrustStatusValue;
		private int mappedTrustWeightPercent;
		private long threshold;
		private long levelScoreCap;

		protected LevelPolicy() {
		}

		public LevelPolicy(int level, AccountTrustStatus mappedTrustStatus, long threshold, long levelScoreCap) {
			this(level, mappedTrustStatus,
					(mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus).getVoteWeightPercent(),
					threshold, levelScoreCap);
		}

		public LevelPolicy(int level, AccountTrustStatus mappedTrustStatus, int mappedTrustWeightPercent,
				long threshold, long levelScoreCap) {
			AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

			this.level = level;
			this.mappedTrustStatus = effectiveMappedStatus;
			this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
			this.mappedTrustWeightPercent = mappedTrustWeightPercent;
			this.threshold = threshold;
			this.levelScoreCap = levelScoreCap;
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

		public long getThreshold() {
			return this.threshold;
		}

		public long getLevelScoreCap() {
			return this.levelScoreCap;
		}
	}
}
