package org.qortium.data.account;

import org.qortium.account.AccountTrustPolicy;

public enum AccountTrustStatus {
	SUSPICIOUS(-1, false),
	UNVERIFIED(0, true),
	BRONZE(1, true),
	SILVER(2, true),
	GOLD(3, true);

	private final int value;
	private final boolean canMint;

	AccountTrustStatus(int value, boolean canMint) {
		this.value = value;
		this.canMint = canMint;
	}

	public int getValue() {
		return this.value;
	}

	public int getVoteWeightPercent() {
		return AccountTrustPolicy.getVoteWeightPercent(this);
	}

	public boolean canMint() {
		return this.canMint;
	}

	public int calculateEffectiveVoteWeight(int blocksMinted) {
		return AccountTrustPolicy.calculateEffectiveVoteWeight(blocksMinted, this);
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
