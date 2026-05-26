package org.qortium.data.voting;

import org.qortium.data.account.AccountTrustStatus;

public class PollVoteWeightData {

	private final String pollName;
	private final byte[] voterPublicKey;
	private final int optionIndex;
	private final int rawVoteWeight;
	private final AccountTrustStatus trustStatus;
	private final int trustWeightPercent;
	private final int effectiveVoteWeight;
	private final Integer freezeHeight;
	private final Long freezeTimestamp;

	public PollVoteWeightData(String pollName, byte[] voterPublicKey, int optionIndex, int rawVoteWeight,
			AccountTrustStatus trustStatus, int trustWeightPercent, int effectiveVoteWeight) {
		this(pollName, voterPublicKey, optionIndex, rawVoteWeight, trustStatus, trustWeightPercent, effectiveVoteWeight, null, null);
	}

	public PollVoteWeightData(String pollName, byte[] voterPublicKey, int optionIndex, int rawVoteWeight,
			AccountTrustStatus trustStatus, int trustWeightPercent, int effectiveVoteWeight, Integer freezeHeight, Long freezeTimestamp) {
		this.pollName = pollName;
		this.voterPublicKey = voterPublicKey;
		this.optionIndex = optionIndex;
		this.rawVoteWeight = rawVoteWeight;
		this.trustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;
		this.trustWeightPercent = trustWeightPercent;
		this.effectiveVoteWeight = effectiveVoteWeight;
		this.freezeHeight = freezeHeight;
		this.freezeTimestamp = freezeTimestamp;
	}

	public String getPollName() {
		return this.pollName;
	}

	public byte[] getVoterPublicKey() {
		return this.voterPublicKey;
	}

	public int getOptionIndex() {
		return this.optionIndex;
	}

	public int getRawVoteWeight() {
		return this.rawVoteWeight;
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus;
	}

	public int getTrustWeightPercent() {
		return this.trustWeightPercent;
	}

	public int getEffectiveVoteWeight() {
		return this.effectiveVoteWeight;
	}

	public Integer getFreezeHeight() {
		return this.freezeHeight;
	}

	public Long getFreezeTimestamp() {
		return this.freezeTimestamp;
	}

}
