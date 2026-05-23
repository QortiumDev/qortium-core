package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustCategoryPoliciesData {

	private List<CategoryPolicy> categoryPolicies;

	protected AccountTrustCategoryPoliciesData() {
	}

	public AccountTrustCategoryPoliciesData(List<CategoryPolicy> categoryPolicies) {
		this.categoryPolicies = categoryPolicies == null ? new ArrayList<>() : categoryPolicies;
	}

	public List<CategoryPolicy> getCategoryPolicies() {
		return this.categoryPolicies;
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
			this.category = category;
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
		private long threshold;
		private long levelScoreCap;

		protected LevelPolicy() {
		}

		public LevelPolicy(int level, long threshold, long levelScoreCap) {
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
}
