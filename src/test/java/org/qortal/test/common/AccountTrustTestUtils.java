package org.qortal.test.common;

import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Arrays;
import java.util.List;

public final class AccountTrustTestUtils {

	private AccountTrustTestUtils() {
	}

	public static void createDerivedSilverSubjectSnapshot(Repository repository, PrivateKeyAccount subject,
			PrivateKeyAccount manager, PrivateKeyAccount trainer, PrivateKeyAccount player) throws DataException {
		saveManagerTrust(repository, subject, manager, 1);
		saveAccountRating(repository, manager, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, trainer, player, AccountRatingCategory.PLAYER, 4);
		saveAccountRating(repository, player, subject, AccountRatingCategory.SUBJECT, 4);
		refreshTrustSnapshots(repository);
	}

	public static void saveManagerTrust(Repository repository, PrivateKeyAccount seedAccount,
			PrivateKeyAccount managerTarget, int rating) throws DataException {
		PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);

		ensureKnownAccount(repository, evaluator);
		saveManagerEnergyPath(repository, seedAccount, evaluator);
		saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, rating);
	}

	public static void saveManagerEnergyPath(Repository repository, PrivateKeyAccount seedAccount,
			PrivateKeyAccount evaluator) throws DataException {
		List<PrivateKeyAccount> pathAccounts = Arrays.asList(
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository));

		ensureKnownAccount(repository, seedAccount);
		ensureKnownAccount(repository, evaluator);
		for (PrivateKeyAccount account : pathAccounts)
			ensureKnownAccount(repository, account);

		saveAccountRating(repository, seedAccount, pathAccounts.get(0), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(0), pathAccounts.get(1), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(1), pathAccounts.get(2), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(2), evaluator, AccountRatingCategory.MANAGER, 4);
	}

	public static void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		ensureKnownAccount(repository, rater);
		ensureKnownAccount(repository, target);
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(), category, rating));
	}

	public static void refreshTrustSnapshots(Repository repository) throws DataException {
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	public static void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}
}
