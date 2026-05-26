package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustSummaryData {

	private AccountRatingCategory activeWeightCategory;
	private Integer snapshotHeight;
	private Long snapshotTimestamp;
	private long snapshotAccountCount;
	private long snapshotRowCount;
	private long expectedSnapshotRowCount;
	private boolean snapshotsComplete;
	private long activeRatingCount;
	private long trustStatusChangeCount;
	private Integer latestTrustChangeHeight;
	private Long latestTrustChangeTimestamp;
	private long activeSnapshotAccountCount;
	private long activeSeedMemberCount;
	private long activeMintingAllowedCount;
	private long suspiciousCount;
	private long rawVoteWeight;
	private long effectiveVoteWeight;
	private List<StatusSummary> statusSummaries;
	private List<CategorySummary> categorySummaries;
	private List<RatingCategorySummary> ratingCategorySummaries;

	protected AccountTrustSummaryData() {
	}

	public AccountTrustSummaryData(AccountRatingCategory activeWeightCategory, Integer snapshotHeight, Long snapshotTimestamp,
			long snapshotAccountCount, long snapshotRowCount, long expectedSnapshotRowCount, boolean snapshotsComplete,
			long activeRatingCount, long trustStatusChangeCount, Integer latestTrustChangeHeight,
			Long latestTrustChangeTimestamp, List<StatusSummary> statusSummaries,
			List<CategorySummary> categorySummaries, List<RatingCategorySummary> ratingCategorySummaries) {
		this.activeWeightCategory = activeWeightCategory == null ? AccountRatingCategory.SUBJECT : activeWeightCategory;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
		this.snapshotAccountCount = snapshotAccountCount;
		this.snapshotRowCount = snapshotRowCount;
		this.expectedSnapshotRowCount = expectedSnapshotRowCount;
		this.snapshotsComplete = snapshotsComplete;
		this.activeRatingCount = activeRatingCount;
		this.trustStatusChangeCount = trustStatusChangeCount;
		this.latestTrustChangeHeight = latestTrustChangeHeight;
		this.latestTrustChangeTimestamp = latestTrustChangeTimestamp;
		this.statusSummaries = statusSummaries == null ? new ArrayList<>() : statusSummaries;
		this.categorySummaries = categorySummaries == null ? new ArrayList<>() : categorySummaries;
		this.ratingCategorySummaries = ratingCategorySummaries == null ? new ArrayList<>() : ratingCategorySummaries;
		this.updateTotals();
	}

	public AccountRatingCategory getActiveWeightCategory() {
		return this.activeWeightCategory;
	}

	public Integer getSnapshotHeight() {
		return this.snapshotHeight;
	}

	public Long getSnapshotTimestamp() {
		return this.snapshotTimestamp;
	}

	public long getSnapshotAccountCount() {
		return this.snapshotAccountCount;
	}

	public long getSnapshotRowCount() {
		return this.snapshotRowCount;
	}

	public long getExpectedSnapshotRowCount() {
		return this.expectedSnapshotRowCount;
	}

	public boolean isSnapshotsComplete() {
		return this.snapshotsComplete;
	}

	public long getActiveRatingCount() {
		return this.activeRatingCount;
	}

	public long getTrustStatusChangeCount() {
		return this.trustStatusChangeCount;
	}

	public Integer getLatestTrustChangeHeight() {
		return this.latestTrustChangeHeight;
	}

	public Long getLatestTrustChangeTimestamp() {
		return this.latestTrustChangeTimestamp;
	}

	public long getActiveSnapshotAccountCount() {
		return this.activeSnapshotAccountCount;
	}

	public long getActiveSeedMemberCount() {
		return this.activeSeedMemberCount;
	}

	public long getActiveMintingAllowedCount() {
		return this.activeMintingAllowedCount;
	}

	public long getSuspiciousCount() {
		return this.suspiciousCount;
	}

	public long getRawVoteWeight() {
		return this.rawVoteWeight;
	}

	public long getEffectiveVoteWeight() {
		return this.effectiveVoteWeight;
	}

	public List<StatusSummary> getStatusSummaries() {
		return this.statusSummaries;
	}

	public List<CategorySummary> getCategorySummaries() {
		return this.categorySummaries;
	}

	public List<RatingCategorySummary> getRatingCategorySummaries() {
		return this.ratingCategorySummaries;
	}

	private void updateTotals() {
		this.activeSnapshotAccountCount = 0L;
		this.activeSeedMemberCount = 0L;
		this.activeMintingAllowedCount = 0L;
		this.suspiciousCount = 0L;
		this.rawVoteWeight = 0L;
		this.effectiveVoteWeight = 0L;

		for (StatusSummary statusSummary : this.statusSummaries) {
			this.activeSnapshotAccountCount += statusSummary.getAccountCount();
			this.activeSeedMemberCount += statusSummary.getSeedMemberCount();
			this.rawVoteWeight += statusSummary.getRawVoteWeight();
			this.effectiveVoteWeight += statusSummary.getEffectiveVoteWeight();

			if (statusSummary.isTrustAllowsMinting())
				this.activeMintingAllowedCount += statusSummary.getSeedMemberCount();

			if (statusSummary.getStatus() == AccountTrustStatus.SUSPICIOUS)
				this.suspiciousCount += statusSummary.getAccountCount();
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class StatusSummary {
		private AccountTrustStatus status;
		private int statusValue;
		private int voteWeightPercent;
		private boolean trustAllowsMinting;
		private long accountCount;
		private long seedMemberCount;
		private long rawVoteWeight;
		private long effectiveVoteWeight;

		protected StatusSummary() {
		}

		public StatusSummary(AccountTrustStatus status, long accountCount, long seedMemberCount, long rawVoteWeight,
				long effectiveVoteWeight) {
			this(status, (status == null ? AccountTrustStatus.UNVERIFIED : status).getVoteWeightPercent(),
					accountCount, seedMemberCount, rawVoteWeight, effectiveVoteWeight);
		}

		public StatusSummary(AccountTrustStatus status, int voteWeightPercent, long accountCount, long seedMemberCount,
				long rawVoteWeight, long effectiveVoteWeight) {
			AccountTrustStatus effectiveStatus = status == null ? AccountTrustStatus.UNVERIFIED : status;

			this.status = effectiveStatus;
			this.statusValue = effectiveStatus.getValue();
			this.voteWeightPercent = voteWeightPercent;
			this.trustAllowsMinting = effectiveStatus.canMint();
			this.accountCount = accountCount;
			this.seedMemberCount = seedMemberCount;
			this.rawVoteWeight = rawVoteWeight;
			this.effectiveVoteWeight = effectiveVoteWeight;
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

		public long getAccountCount() {
			return this.accountCount;
		}

		public long getSeedMemberCount() {
			return this.seedMemberCount;
		}

		public long getRawVoteWeight() {
			return this.rawVoteWeight;
		}

		public long getEffectiveVoteWeight() {
			return this.effectiveVoteWeight;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategorySummary {
		private AccountRatingCategory category;
		private List<StatusCount> statusCounts;

		protected CategorySummary() {
		}

		public CategorySummary(AccountRatingCategory category, List<StatusCount> statusCounts) {
			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.statusCounts = statusCounts == null ? new ArrayList<>() : statusCounts;
		}

		public AccountRatingCategory getCategory() {
			return this.category;
		}

		public List<StatusCount> getStatusCounts() {
			return this.statusCounts;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class StatusCount {
		private AccountTrustStatus status;
		private int statusValue;
		private long accountCount;

		protected StatusCount() {
		}

		public StatusCount(AccountTrustStatus status, long accountCount) {
			AccountTrustStatus effectiveStatus = status == null ? AccountTrustStatus.UNVERIFIED : status;

			this.status = effectiveStatus;
			this.statusValue = effectiveStatus.getValue();
			this.accountCount = accountCount;
		}

		public AccountTrustStatus getStatus() {
			return this.status;
		}

		public int getStatusValue() {
			return this.statusValue;
		}

		public long getAccountCount() {
			return this.accountCount;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class RatingCategorySummary {
		private AccountRatingCategory category;
		private long ratingCount;
		private long positiveRatingCount;
		private long negativeRatingCount;

		protected RatingCategorySummary() {
		}

		public RatingCategorySummary(AccountRatingCategory category, long ratingCount, long positiveRatingCount,
				long negativeRatingCount) {
			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.ratingCount = ratingCount;
			this.positiveRatingCount = positiveRatingCount;
			this.negativeRatingCount = negativeRatingCount;
		}

		public AccountRatingCategory getCategory() {
			return this.category;
		}

		public long getRatingCount() {
			return this.ratingCount;
		}

		public long getPositiveRatingCount() {
			return this.positiveRatingCount;
		}

		public long getNegativeRatingCount() {
			return this.negativeRatingCount;
		}
	}
}
