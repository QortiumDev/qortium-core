package org.qortal.test.common;

import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AccountTrustTestUtils {

	private AccountTrustTestUtils() {
	}

	public static void createDerivedSilverSubjectSnapshot(Repository repository, PrivateKeyAccount subject,
			PrivateKeyAccount manager, PrivateKeyAccount trainer, PrivateKeyAccount player) throws DataException {
		saveDerivedSilverSubjectRatings(repository, subject, manager, trainer, player);
		refreshTrustSnapshots(repository);
	}

	public static void saveDerivedSilverSubjectRatings(Repository repository, PrivateKeyAccount seedAccount,
			PrivateKeyAccount manager, PrivateKeyAccount trainer, PrivateKeyAccount player) throws DataException {
		PrivateKeyAccount managerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount playerPeer = Common.generateRandomSeedAccount(repository);

		saveDerivedManagerLevelTwoRatings(repository, seedAccount, Arrays.asList(manager, managerPeer));

		saveAccountRating(repository, manager, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, manager, trainerPeer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainerPeer, AccountRatingCategory.TRAINER, 4);

		saveAccountRating(repository, trainer, player, AccountRatingCategory.PLAYER, 2);
		saveAccountRating(repository, trainerPeer, player, AccountRatingCategory.PLAYER, 2);
		saveAccountRating(repository, trainer, playerPeer, AccountRatingCategory.PLAYER, 1);
		saveAccountRating(repository, trainerPeer, playerPeer, AccountRatingCategory.PLAYER, 1);

		saveAccountRating(repository, player, seedAccount, AccountRatingCategory.SUBJECT, 2);
		saveAccountRating(repository, playerPeer, seedAccount, AccountRatingCategory.SUBJECT, 2);
	}

	public static void saveDerivedPlayerLevelThreeRatings(Repository repository, PrivateKeyAccount seedAccount,
			PrivateKeyAccount playerTarget) throws DataException {
		PrivateKeyAccount manager = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount managerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainerPeer = Common.generateRandomSeedAccount(repository);

		saveDerivedManagerLevelTwoRatings(repository, seedAccount, Arrays.asList(manager, managerPeer));

		saveAccountRating(repository, manager, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, manager, trainerPeer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainerPeer, AccountRatingCategory.TRAINER, 4);

		saveAccountRating(repository, trainer, playerTarget, AccountRatingCategory.PLAYER, 2);
		saveAccountRating(repository, trainerPeer, playerTarget, AccountRatingCategory.PLAYER, 2);
	}

	public static void saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(Repository repository,
			PrivateKeyAccount seedAccount, List<? extends PrivateKeyAccount> playerTargets) throws DataException {
		PrivateKeyAccount manager = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount managerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainerPeer = Common.generateRandomSeedAccount(repository);

		saveDerivedManagerLevelTwoRatingsFromSharedManagerBranch(repository, seedAccount, Arrays.asList(manager, managerPeer));

		saveAccountRating(repository, manager, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, manager, trainerPeer, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, managerPeer, trainerPeer, AccountRatingCategory.TRAINER, 4);

		for (PrivateKeyAccount playerTarget : playerTargets) {
			saveAccountRating(repository, trainer, playerTarget, AccountRatingCategory.PLAYER, 2);
			saveAccountRating(repository, trainerPeer, playerTarget, AccountRatingCategory.PLAYER, 2);
		}
	}

	public static void saveDerivedManagerLevelTwoRatings(Repository repository, PrivateKeyAccount seedAccount,
			List<? extends PrivateKeyAccount> managerTargets) throws DataException {
		List<PrivateKeyAccount> evaluators = saveManagerEnergyPaths(repository, seedAccount, 2);

		for (PrivateKeyAccount evaluator : evaluators) {
			for (PrivateKeyAccount managerTarget : managerTargets)
				saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, 1);
		}
	}

	public static void saveDerivedManagerLevelTwoRatingsFromSharedManagerBranch(Repository repository,
			PrivateKeyAccount seedAccount, List<? extends PrivateKeyAccount> managerTargets) throws DataException {
		List<PrivateKeyAccount> evaluators = saveManagerEnergyPathsFromSharedFirstHop(repository, seedAccount, 2);

		for (PrivateKeyAccount evaluator : evaluators) {
			for (PrivateKeyAccount managerTarget : managerTargets)
				saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, 1);
		}
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
		saveManagerEnergyPathRatings(repository, seedAccount, evaluator);
	}

	public static List<PrivateKeyAccount> saveManagerEnergyPaths(Repository repository, PrivateKeyAccount seedAccount,
			int pathCount) throws DataException {
		List<PrivateKeyAccount> evaluators = new ArrayList<>();
		for (int i = 0; i < pathCount; ++i) {
			PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);
			evaluators.add(evaluator);
			saveManagerEnergyPathRatings(repository, seedAccount, evaluator);
		}

		return evaluators;
	}

	public static List<PrivateKeyAccount> saveManagerEnergyPathsFromSharedFirstHop(Repository repository,
			PrivateKeyAccount seedAccount, int pathCount) throws DataException {
		List<PrivateKeyAccount> evaluators = new ArrayList<>();
		PrivateKeyAccount firstHop = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount secondHop = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount thirdHop = Common.generateRandomSeedAccount(repository);

		ensureKnownAccount(repository, seedAccount);
		ensureKnownAccount(repository, firstHop);
		ensureKnownAccount(repository, secondHop);
		ensureKnownAccount(repository, thirdHop);

		saveAccountRating(repository, seedAccount, firstHop, AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, firstHop, secondHop, AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, secondHop, thirdHop, AccountRatingCategory.MANAGER, 4);

		for (int i = 0; i < pathCount; ++i) {
			PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);
			evaluators.add(evaluator);
			ensureKnownAccount(repository, evaluator);
			saveAccountRating(repository, thirdHop, evaluator, AccountRatingCategory.MANAGER, 4);
		}

		return evaluators;
	}

	private static void saveManagerEnergyPathRatings(Repository repository, PrivateKeyAccount seedAccount,
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
