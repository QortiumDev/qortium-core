package org.qortium.account;

import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

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

	public static int calculateEffectiveVoteWeight(Repository repository, int height, int blocksMinted,
			AccountTrustSnapshotData snapshotData) throws DataException {
		return AccountTrustPolicy.calculateEffectiveVoteWeight(repository, height, blocksMinted, statusFromSnapshot(snapshotData));
	}

	public static int calculateEffectiveVoteWeight(int[] voteWeightPercents, int blocksMinted,
			AccountTrustSnapshotData snapshotData) {
		return AccountTrustPolicy.calculateEffectiveVoteWeight(voteWeightPercents, blocksMinted, statusFromSnapshot(snapshotData));
	}

	public static int getVoteWeightPercent(Repository repository, int height, AccountTrustSnapshotData snapshotData)
			throws DataException {
		return AccountTrustPolicy.getVoteWeightPercent(repository, height, statusFromSnapshot(snapshotData));
	}

	public static int getVoteWeightPercent(int[] voteWeightPercents, AccountTrustSnapshotData snapshotData) {
		return AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, statusFromSnapshot(snapshotData));
	}

	public static boolean canMint(AccountTrustSnapshotData snapshotData) {
		return statusFromSnapshot(snapshotData).canMint();
	}

}
