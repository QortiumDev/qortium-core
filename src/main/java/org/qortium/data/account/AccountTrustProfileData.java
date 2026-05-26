package org.qortium.data.account;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustProfileData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private AccountTrustStatus trustStatus;
	private int trustStatusValue;
	private int trustWeightPercent;
	private boolean trustAllowsMinting;
	@Schema(description = "Raw minted-block history. This remains account history and is not the effective governance weight.")
	private int blocksMinted;
	@Schema(description = "Effective governance and rating weight after applying the active trust multiplier to blocksMinted")
	private int effectiveVoteWeight;
	private AccountRatingCategory activeWeightCategory;
	private boolean mintingSeedMember;
	private Integer snapshotHeight;
	private Long snapshotTimestamp;
	private List<CategoryProfile> categories;

	protected AccountTrustProfileData() {
	}

	public AccountTrustProfileData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			int blocksMinted, int effectiveVoteWeight, AccountRatingCategory activeWeightCategory,
			boolean mintingSeedMember, Integer snapshotHeight, Long snapshotTimestamp, List<CategoryProfile> categories) {
		this(targetPublicKey, targetAddress, trustStatus,
				(trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus).getVoteWeightPercent(),
				blocksMinted, effectiveVoteWeight, activeWeightCategory, mintingSeedMember, snapshotHeight,
				snapshotTimestamp, categories);
	}

	public AccountTrustProfileData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			int trustWeightPercent, int blocksMinted, int effectiveVoteWeight, AccountRatingCategory activeWeightCategory,
			boolean mintingSeedMember, Integer snapshotHeight, Long snapshotTimestamp, List<CategoryProfile> categories) {
		AccountTrustStatus effectiveTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustStatus = effectiveTrustStatus;
		this.trustStatusValue = effectiveTrustStatus.getValue();
		this.trustWeightPercent = trustWeightPercent;
		this.trustAllowsMinting = effectiveTrustStatus.canMint();
		this.blocksMinted = blocksMinted;
		this.effectiveVoteWeight = effectiveVoteWeight;
		this.activeWeightCategory = activeWeightCategory == null ? AccountRatingCategory.SUBJECT : activeWeightCategory;
		this.mintingSeedMember = mintingSeedMember;
		this.snapshotHeight = snapshotHeight;
		this.snapshotTimestamp = snapshotTimestamp;
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

	public boolean isTrustAllowsMinting() {
		return this.trustAllowsMinting;
	}

	public int getBlocksMinted() {
		return this.blocksMinted;
	}

	public int getEffectiveVoteWeight() {
		return this.effectiveVoteWeight;
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

	public List<CategoryProfile> getCategories() {
		return this.categories;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryProfile {
		private AccountRatingCategory category;
		private long score;
		private long levelScore;
		private long levelScoreCap;
		private int level;
		private AccountTrustStatus mappedTrustStatus;
		private int mappedTrustStatusValue;
		private int mappedTrustWeightPercent;
		private AccountTrustRatingCountsData inboundRatings;
		private AccountTrustRatingCountsData outboundRatings;
		private Integer snapshotHeight;
		private Long snapshotTimestamp;

		protected CategoryProfile() {
		}

		public CategoryProfile(AccountRatingCategory category, long score, long levelScore, long levelScoreCap,
				int level, AccountTrustStatus mappedTrustStatus, AccountTrustRatingCountsData inboundRatings,
				AccountTrustRatingCountsData outboundRatings, Integer snapshotHeight, Long snapshotTimestamp) {
			this(category, score, levelScore, levelScoreCap, level, mappedTrustStatus,
					(mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus).getVoteWeightPercent(),
					inboundRatings, outboundRatings, snapshotHeight, snapshotTimestamp);
		}

		public CategoryProfile(AccountRatingCategory category, long score, long levelScore, long levelScoreCap,
				int level, AccountTrustStatus mappedTrustStatus, int mappedTrustWeightPercent,
				AccountTrustRatingCountsData inboundRatings, AccountTrustRatingCountsData outboundRatings,
				Integer snapshotHeight, Long snapshotTimestamp) {
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
			this.outboundRatings = outboundRatings == null ? new AccountTrustRatingCountsData() : outboundRatings;
			this.snapshotHeight = snapshotHeight;
			this.snapshotTimestamp = snapshotTimestamp;
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

		public AccountTrustRatingCountsData getOutboundRatings() {
			return this.outboundRatings;
		}

		public Integer getSnapshotHeight() {
			return this.snapshotHeight;
		}

		public Long getSnapshotTimestamp() {
			return this.snapshotTimestamp;
		}
	}
}
