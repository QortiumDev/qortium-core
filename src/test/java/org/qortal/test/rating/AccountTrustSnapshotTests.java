package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.group.Group;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.Groups;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustSnapshotTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTrustSnapshotTableSchema() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "account"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "account_public_key"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "category"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "score"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "level_score"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "level_score_cap"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "level"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "mapped_trust_status"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "minting_seed_member"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "positive_low_count"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "negative_very_high_count"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "snapshot_height"));
			assertTrue(tableHasColumn(repository, "AccountTrustDerivationSnapshots", "snapshot_timestamp"));
			assertTrue(tableHasPrimaryKey(repository, "AccountTrustDerivationSnapshots", "account"));
			assertTrue(tableHasPrimaryKey(repository, "AccountTrustDerivationSnapshots", "category"));
			assertTrue(tableHasIndex(repository, "AccountTrustDerivationSnapshots", "mapped_trust_status"));
			assertTrue(tableHasIndex(repository, "AccountTrustDerivationSnapshots", "snapshot_height"));
			assertTrue(indexHasColumns(repository, "AccountTrustDerivationSnapshots",
					"AccountTrustDerivationSnapshotSeedIndex", "minting_seed_member", "account", "category"));
			assertTrue(indexHasColumns(repository, "AccountTrustDerivationSnapshots",
					"AccountTrustDerivationSnapshotCategoryLevelIndex", "category", "level", "score", "account"));
			assertTrue(indexHasColumns(repository, "AccountTrustDerivationSnapshots",
					"AccountTrustDerivationSnapshotSubjectStatusIndex", "category", "mapped_trust_status", "account"));
		}
	}

	@Test
	public void testAccountRatingQueryIndexes() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertTrue(indexHasColumns(repository, "AccountRatings", "AccountRatingsTargetCategoryRatingIndex",
					"target", "category", "rating"));
			assertTrue(indexHasColumns(repository, "AccountRatings", "AccountRatingsRaterCategoryTargetIndex",
					"rater", "category", "target_account"));
			assertTrue(indexHasColumns(repository, "AccountRatings", "AccountRatingsCategoryTargetRaterIndex",
					"category", "target_account", "rater_account"));
			assertTrue(indexHasColumns(repository, "AccountRatings", "AccountRatingsAccountOrderIndex",
					"target_account", "rater_account", "category"));
		}
	}

	@Test
	public void testBlockProcessingStoresDerivedCategorySnapshots() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
			ensureKnownAccount(repository, dilbert);
			repository.saveChanges();

			AccountTrustTestUtils.saveDerivedSilverSubjectRatings(repository, alice, bob, chloe, dilbert);
			repository.saveChanges();
			BlockUtils.mintBlock(repository);

			BlockData lastBlockData = repository.getBlockRepository().getLastBlock();
			List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null);
			assertEquals(60, snapshots.size());

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(), AccountRatingCategory.SUBJECT);
			assertArrayEquals(alice.getPublicKey(), aliceSubject.getAccountPublicKey());
			assertTrue(aliceSubject.isMintingSeedMember());
			assertEquals(96_000_000L, aliceSubject.getScore());
			assertEquals(50_000_000L, aliceSubject.getLevelScore());
			assertEquals(25_000_000L, aliceSubject.getLevelScoreCap());
			assertEquals(2, aliceSubject.getLevel());
			assertEquals(AccountTrustStatus.SILVER, aliceSubject.getMappedTrustStatus());
			assertEquals(50, aliceSubject.getMappedTrustWeightPercent());
			assertEquals(2, aliceSubject.getInboundRatings().getPositiveMediumCount());
			assertEquals(lastBlockData.getHeight().intValue(), aliceSubject.getSnapshotHeight());
			assertEquals(lastBlockData.getTimestamp(), aliceSubject.getSnapshotTimestamp());

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);
			assertEquals(1_000_000L, bobManager.getScore());
			assertEquals(200_000L, bobManager.getLevelScore());
			assertEquals(100_000L, bobManager.getLevelScoreCap());
			assertEquals(2, bobManager.getLevel());
			assertEquals(2, bobManager.getInboundRatings().getPositiveLowCount());

			AccountTrustSnapshotData chloeTrainer = findSnapshot(repository, chloe.getAddress(), AccountRatingCategory.TRAINER);
			assertEquals(8_000_000L, chloeTrainer.getScore());
			assertEquals(1_000_000L, chloeTrainer.getLevelScore());
			assertEquals(500_000L, chloeTrainer.getLevelScoreCap());
			assertEquals(2, chloeTrainer.getLevel());

			AccountTrustSnapshotData dilbertPlayer = findSnapshot(repository, dilbert.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(32_000_000L, dilbertPlayer.getScore());
			assertEquals(3_000_000L, dilbertPlayer.getLevelScore());
			assertEquals(1_500_000L, dilbertPlayer.getLevelScoreCap());
			assertEquals(3, dilbertPlayer.getLevel());
		}
	}

	@Test
	public void testFilteredTrustSnapshotRepositoryQueries() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
			ensureKnownAccount(repository, dilbert);
			repository.saveChanges();

			AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);

			List<AccountTrustSnapshotData> allSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null, null, null, null, null, null);
			assertEquals(60, allSnapshots.size());

			List<AccountTrustSnapshotData> aliceSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(alice.getAddress(), null, null, null, null, null, null, null);
			assertEquals(4, aliceSnapshots.size());

			List<AccountTrustSnapshotData> silverSubjectSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, AccountRatingCategory.SUBJECT, AccountTrustStatus.SILVER, null,
							null, null, null, null);
			assertEquals(1, silverSubjectSnapshots.size());
			assertEquals(alice.getAddress(), silverSubjectSnapshots.get(0).getAccountAddress());
			assertEquals(AccountRatingCategory.SUBJECT, silverSubjectSnapshots.get(0).getCategory());

			List<AccountTrustSnapshotData> managerSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, AccountRatingCategory.MANAGER, null, null, 2, null, null, null);
			assertEquals(2, managerSnapshots.size());
			for (AccountTrustSnapshotData snapshot : managerSnapshots) {
				assertEquals(AccountRatingCategory.MANAGER, snapshot.getCategory());
				assertTrue(snapshot.getLevel() >= 2);
			}

			List<AccountTrustSnapshotData> seedSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null, true, null, null, null, null);
			assertEquals(4, seedSnapshots.size());
			for (AccountTrustSnapshotData snapshot : seedSnapshots)
				assertEquals(alice.getAddress(), snapshot.getAccountAddress());

			List<AccountTrustSnapshotData> pagedSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null, null, null, 2, 1, null);
			assertEquals(2, pagedSnapshots.size());
			assertEquals(allSnapshots.get(1).getAccountAddress(), pagedSnapshots.get(0).getAccountAddress());
			assertEquals(allSnapshots.get(1).getCategory(), pagedSnapshots.get(0).getCategory());

			List<AccountTrustSnapshotData> reversedSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null, null, null, 1, null, true);
			assertEquals(1, reversedSnapshots.size());
			assertEquals(allSnapshots.get(allSnapshots.size() - 1).getAccountAddress(),
					reversedSnapshots.get(0).getAccountAddress());
			assertEquals(allSnapshots.get(allSnapshots.size() - 1).getCategory(), reversedSnapshots.get(0).getCategory());

			assertTrue(repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null, null, null, 0, null, null).isEmpty());
		}
	}

	@Test
	public void testTrustDerivationRepositoryQueryPagesAccountsBeforeLoadingSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
			ensureKnownAccount(repository, dilbert);
			repository.saveChanges();

			AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);

			List<AccountTrustSnapshotData> silverAccountSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(AccountTrustStatus.SILVER, AccountRatingCategory.SUBJECT,
							null, null, null, null, null);
			assertSingleAccountSnapshotPage(silverAccountSnapshots, alice.getAddress());

			List<AccountTrustSnapshotData> playerLevelThreeSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.PLAYER, null, 3, null, null,
							null);
			assertSingleAccountSnapshotPage(playerLevelThreeSnapshots, dilbert.getAddress());

			List<AccountTrustSnapshotData> firstAccountSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, 1, null,
							null);
			assertSingleAccountSnapshotPage(firstAccountSnapshots, alice.getAddress());

			List<AccountTrustSnapshotData> reversedFirstAccountSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, 1, null,
							true);
			assertEquals(4, reversedFirstAccountSnapshots.size());
			assertFalse(alice.getAddress().equals(reversedFirstAccountSnapshots.get(0).getAccountAddress()));

			assertTrue(repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, 0, null,
							null).isEmpty());
		}
	}

	@Test
	public void testRateAccountBlockSnapshotRestoresOnOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			int baselineHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			assertEquals(0L, findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT).getScore());

			BlockUtils.orphanLastBlock(repository);
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(baselineHeight, aliceSubject.getSnapshotHeight());
		}
	}

	@Test
	public void testRatingRemovalRefreshesSnapshot() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			repository.saveChanges();

			saveManagerTrust(repository, alice, bob, 1);
			repository.saveChanges();
			BlockUtils.mintBlock(repository);
			assertFalse(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			removeManagerTrust(repository);
			repository.saveChanges();
			BlockUtils.mintBlock(repository);

			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());
		}
	}

	@Test
	public void testMintingGroupSeedMembershipRefreshesSnapshot() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int mintingGroupId = Groups.getGroupIdsToMint(BlockChain.getInstance(),
					repository.getBlockRepository().getBlockchainHeight() + 1).get(0);
			assertEquals(TestChainBootstrapUtils.MINTING_GROUP_ID, mintingGroupId);

			BlockUtils.mintBlock(repository);
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			JoinGroupTransactionData joinGroupTransactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					mintingGroupId);
			TransactionUtils.signAndMint(repository, joinGroupTransactionData, bob);

			AccountTrustSnapshotData bobSubject = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT);
			assertTrue(bobSubject.isMintingSeedMember());
			assertEquals(0L, bobSubject.getScore());
			assertEquals(0, bobSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, bobSubject.getMappedTrustStatus());

			BlockUtils.orphanLastBlock(repository);
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void saveManagerTrust(Repository repository, PrivateKeyAccount seedAccount, PrivateKeyAccount managerTarget,
			int rating) throws DataException {
		PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);

		ensureKnownAccount(repository, evaluator);
		saveManagerEnergyPath(repository, seedAccount, evaluator);
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(managerTarget.getPublicKey(), evaluator.getPublicKey(),
						AccountRatingCategory.MANAGER, rating));
	}

	private void removeManagerTrust(Repository repository) throws DataException {
		List<AccountRatingData> managerRatings = repository.getAccountRatingRepository()
				.getRatings(null, null, AccountRatingCategory.MANAGER, null, null, null);

		for (AccountRatingData rating : managerRatings)
			repository.getAccountRatingRepository().delete(rating.getTargetPublicKey(), rating.getRaterPublicKey(),
					AccountRatingCategory.MANAGER);
	}

	private void saveManagerEnergyPath(Repository repository, PrivateKeyAccount seedAccount, PrivateKeyAccount evaluator)
			throws DataException {
		List<PrivateKeyAccount> pathAccounts = Arrays.asList(
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository));

		ensureKnownAccount(repository, seedAccount);
		ensureKnownAccount(repository, evaluator);
		ensureKnownAccount(repository, pathAccounts.get(0));
		ensureKnownAccount(repository, pathAccounts.get(1));
		ensureKnownAccount(repository, pathAccounts.get(2));

		repository.getAccountRatingRepository()
				.save(new AccountRatingData(pathAccounts.get(0).getPublicKey(),
						seedAccount.getPublicKey(), AccountRatingCategory.MANAGER, 4));
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(pathAccounts.get(1).getPublicKey(),
						pathAccounts.get(0).getPublicKey(), AccountRatingCategory.MANAGER, 4));
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(pathAccounts.get(2).getPublicKey(),
						pathAccounts.get(1).getPublicKey(), AccountRatingCategory.MANAGER, 4));
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(evaluator.getPublicKey(),
						pathAccounts.get(2).getPublicKey(), AccountRatingCategory.MANAGER, 4));
	}

	private void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress, AccountRatingCategory category)
			throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private void assertSingleAccountSnapshotPage(List<AccountTrustSnapshotData> snapshots, String accountAddress) {
		assertEquals(4, snapshots.size());
		for (AccountTrustSnapshotData snapshot : snapshots)
			assertEquals(accountAddress, snapshot.getAccountAddress());

		assertEquals(AccountRatingCategory.SUBJECT, snapshots.get(0).getCategory());
		assertEquals(AccountRatingCategory.PLAYER, snapshots.get(1).getCategory());
		assertEquals(AccountRatingCategory.TRAINER, snapshots.get(2).getCategory());
		assertEquals(AccountRatingCategory.MANAGER, snapshots.get(3).getCategory());
	}

	private boolean tableHasColumn(Repository repository, String tableName, String columnName) throws SQLException {
		try (ResultSet resultSet = repository.getConnection().getMetaData()
				.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
			return resultSet.next();
		}
	}

	private boolean tableHasPrimaryKey(Repository repository, String tableName, String columnName) throws SQLException {
		DatabaseMetaData metaData = repository.getConnection().getMetaData();
		try (ResultSet resultSet = metaData.getPrimaryKeys(null, null, tableName.toUpperCase())) {
			while (resultSet.next()) {
				if (columnName.equalsIgnoreCase(resultSet.getString("COLUMN_NAME")))
					return true;
			}
		}

		return false;
	}

	private boolean tableHasIndex(Repository repository, String tableName, String columnName) throws SQLException {
		DatabaseMetaData metaData = repository.getConnection().getMetaData();
		try (ResultSet resultSet = metaData.getIndexInfo(null, null, tableName.toUpperCase(), false, false)) {
			while (resultSet.next()) {
				if (columnName.equalsIgnoreCase(resultSet.getString("COLUMN_NAME")))
					return true;
			}
		}

		return false;
	}

	private boolean indexHasColumns(Repository repository, String tableName, String indexName, String... columnNames)
			throws SQLException {
		String[] actualColumns = new String[columnNames.length];
		int columnCount = 0;
		DatabaseMetaData metaData = repository.getConnection().getMetaData();

		try (ResultSet resultSet = metaData.getIndexInfo(null, null, tableName.toUpperCase(), false, false)) {
			while (resultSet.next()) {
				if (!indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME")))
					continue;

				String columnName = resultSet.getString("COLUMN_NAME");
				short position = resultSet.getShort("ORDINAL_POSITION");
				if (columnName == null || position <= 0)
					continue;

				++columnCount;
				if (position <= columnNames.length)
					actualColumns[position - 1] = columnName;
			}
		}

		if (columnCount != columnNames.length)
			return false;

		for (int i = 0; i < columnNames.length; ++i)
			if (!columnNames[i].equalsIgnoreCase(actualColumns[i]))
				return false;

		return true;
	}
}
