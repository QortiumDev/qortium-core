package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingCooldownData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private byte[] raterPublicKey;
	private String raterAddress;
	private AccountRatingCategory category;
	private Integer activeRating;
	private int cooldownBlocks;
	private Integer latestRatingChangeHeight;
	private int currentHeight;
	private int candidateChangeHeight;
	private int earliestAllowedHeight;
	private int blocksRemaining;
	private boolean canChangeNow;

	protected AccountRatingCooldownData() {
	}

	public AccountRatingCooldownData(byte[] targetPublicKey, String targetAddress, byte[] raterPublicKey,
			String raterAddress, AccountRatingCategory category, Integer activeRating, int cooldownBlocks,
			Integer latestRatingChangeHeight, int currentHeight, int candidateChangeHeight, int earliestAllowedHeight,
			int blocksRemaining, boolean canChangeNow) {
		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterAddress;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.activeRating = activeRating;
		this.cooldownBlocks = cooldownBlocks;
		this.latestRatingChangeHeight = latestRatingChangeHeight;
		this.currentHeight = currentHeight;
		this.candidateChangeHeight = candidateChangeHeight;
		this.earliestAllowedHeight = earliestAllowedHeight;
		this.blocksRemaining = blocksRemaining;
		this.canChangeNow = canChangeNow;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	public String getRaterAddress() {
		return this.raterAddress;
	}

	public AccountRatingCategory getCategory() {
		return this.category;
	}

	public Integer getActiveRating() {
		return this.activeRating;
	}

	public int getCooldownBlocks() {
		return this.cooldownBlocks;
	}

	public Integer getLatestRatingChangeHeight() {
		return this.latestRatingChangeHeight;
	}

	public int getCurrentHeight() {
		return this.currentHeight;
	}

	public int getCandidateChangeHeight() {
		return this.candidateChangeHeight;
	}

	public int getEarliestAllowedHeight() {
		return this.earliestAllowedHeight;
	}

	public int getBlocksRemaining() {
		return this.blocksRemaining;
	}

	public boolean isCanChangeNow() {
		return this.canChangeNow;
	}
}
