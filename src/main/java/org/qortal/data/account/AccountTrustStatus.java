package org.qortal.data.account;

public enum AccountTrustStatus {
	SUSPICIOUS(-1, 0, false),
	UNVERIFIED(0, 0, true),
	BRONZE(1, 25, true),
	SILVER(2, 50, true),
	GOLD(3, 100, true);

	private final int value;
	private final int voteWeightPercent;
	private final boolean canMint;

	AccountTrustStatus(int value, int voteWeightPercent, boolean canMint) {
		this.value = value;
		this.voteWeightPercent = voteWeightPercent;
		this.canMint = canMint;
	}

	public int getValue() {
		return this.value;
	}

	public int getVoteWeightPercent() {
		return this.voteWeightPercent;
	}

	public boolean canMint() {
		return this.canMint;
	}

	public int calculateEffectiveVoteWeight(int blocksMinted) {
		if (blocksMinted <= 0 || this.voteWeightPercent <= 0)
			return 0;

		long effectiveWeight = (long) blocksMinted * this.voteWeightPercent / 100;
		return effectiveWeight > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) effectiveWeight;
	}

	public static int calculateEffectiveVoteWeight(AccountData accountData) {
		if (accountData == null)
			return 0;

		return accountData.getTrustStatus().calculateEffectiveVoteWeight(accountData.getBlocksMinted());
	}

	public static AccountTrustStatus valueOf(int value) {
		for (AccountTrustStatus status : values())
			if (status.value == value)
				return status;

		return UNVERIFIED;
	}
}
