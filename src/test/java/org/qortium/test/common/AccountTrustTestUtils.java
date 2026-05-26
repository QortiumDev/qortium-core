package org.qortium.test.common;

import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustRatingCountsData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

	public static void setBlocksMinted(Repository repository, PrivateKeyAccount account, int blocksMinted)
			throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		accountData.setPublicKey(account.getPublicKey());
		accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.saveChanges();
	}

	public static void replaceSubjectTrustSnapshots(Repository repository,
			AccountTrustDerivationData... derivationData) throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Arrays.asList(derivationData),
				repository.getBlockRepository().getBlockchainHeight(),
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	public static AccountTrustDerivationData subjectTrustSnapshot(PrivateKeyAccount account,
			AccountTrustStatus trustStatus) {
		return subjectTrustSnapshot(account, trustStatus, false);
	}

	public static AccountTrustDerivationData subjectTrustSnapshot(PrivateKeyAccount account,
			AccountTrustStatus trustStatus, boolean seedMember) {
		AccountTrustCategoryData subjectTrust = new AccountTrustCategoryData(
				AccountRatingCategory.SUBJECT,
				scoreForStatus(trustStatus),
				levelForStatus(trustStatus),
				trustStatus,
				new AccountTrustRatingCountsData(),
				List.of());

		return new AccountTrustDerivationData(account.getPublicKey(), account.getAddress(), trustStatus, seedMember,
				List.of(subjectTrust));
	}

	public static long scoreForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 100_000_000L;
			case SILVER:
				return 50_000_000L;
			case BRONZE:
				return 10_000_000L;
			case SUSPICIOUS:
				return -1L;
			case UNVERIFIED:
			default:
				return 0L;
		}
	}

	public static int levelForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 3;
			case SILVER:
				return 2;
			case BRONZE:
				return 1;
			case SUSPICIOUS:
				return -1;
			case UNVERIFIED:
			default:
				return 0;
		}
	}

	public static void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	public static void useAccountRatingCooldown(int cooldownBlocks)
			throws IOException, URISyntaxException, DataException {
		String config = loadDefaultTestChainConfig();
		config = replaceRequired(config, "\"accountRatingChangeCooldownBlocks\": 1440",
				"\"accountRatingChangeCooldownBlocks\": " + cooldownBlocks);
		loadTemporaryConfig(config);
	}

	private static String loadDefaultTestChainConfig() throws IOException, URISyntaxException {
		URL testChainUrl = Common.class.getClassLoader().getResource("test-chain-v2.json");
		if (testChainUrl == null)
			throw new IllegalStateException("test-chain-v2.json not found");

		return Files.readString(Paths.get(testChainUrl.toURI()), StandardCharsets.UTF_8);
	}

	private static String replaceRequired(String config, String target, String replacement) {
		String updatedConfig = config.replace(target, replacement);
		if (updatedConfig.equals(config))
			throw new IllegalStateException("Config replacement target not found: " + target);

		return updatedConfig;
	}

	private static void loadTemporaryConfig(String config) throws IOException {
		Path tempDir = Files.createTempDirectory("account-trust-cooldown");
		Path configPath = tempDir.resolve("blockchain.json");

		Files.writeString(configPath, config, StandardCharsets.UTF_8);
		BlockChain.fileInstance(tempDir.toString() + File.separator, configPath.getFileName().toString());
		Files.deleteIfExists(configPath);
		Files.deleteIfExists(tempDir);
	}
}
