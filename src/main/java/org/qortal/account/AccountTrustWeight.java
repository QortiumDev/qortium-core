package org.qortal.account;

import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;

public class AccountTrustWeight {

	public static final AccountRatingCategory ACTIVE_WEIGHT_CATEGORY = AccountRatingCategory.SUBJECT;

	private AccountTrustWeight() {
	}

	public static AccountTrustStatus statusFromSnapshot(AccountTrustSnapshotData snapshotData) {
		return snapshotData == null ? AccountTrustStatus.UNVERIFIED : snapshotData.getMappedTrustStatus();
	}

	public static int calculateEffectiveVoteWeight(int blocksMinted, AccountTrustSnapshotData snapshotData) {
		return statusFromSnapshot(snapshotData).calculateEffectiveVoteWeight(blocksMinted);
	}

}
