package org.qortal.account;

import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;

public class AccountTrustWeight {

	private AccountTrustWeight() {
	}

	public static AccountRatingCategory getActiveWeightCategory() {
		return AccountTrustPolicy.getActiveWeightCategory();
	}

	public static AccountTrustStatus statusFromSnapshot(AccountTrustSnapshotData snapshotData) {
		return snapshotData == null ? AccountTrustStatus.UNVERIFIED : snapshotData.getMappedTrustStatus();
	}

	public static int calculateEffectiveVoteWeight(int blocksMinted, AccountTrustSnapshotData snapshotData) {
		return AccountTrustPolicy.calculateEffectiveVoteWeight(blocksMinted, statusFromSnapshot(snapshotData));
	}

	public static boolean canMint(AccountTrustSnapshotData snapshotData) {
		return statusFromSnapshot(snapshotData).canMint();
	}

}
