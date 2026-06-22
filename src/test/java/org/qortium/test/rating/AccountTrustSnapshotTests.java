package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustRatingCountsData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.GroupKickTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.group.Group;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Groups;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustSnapshotTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
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
	public void testAccountRatingBaselineSchema() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertTrue(tableHasColumn(repository, "AccountRatings", "category"));
			assertTrue(tableHasPrimaryKey(repository, "AccountRatings", "target"));
			assertTrue(tableHasPrimaryKey(repository, "AccountRatings", "rater"));
			assertTrue(tableHasPrimaryKey(repository, "AccountRatings", "category"));
			assertTrue(tableHasColumn(repository, "RateAccountTransactions", "category"));
			assertTrue(tableHasColumn(repository, "RateAccountTransactions", "rating_change_height"));
			assertTrue(indexHasColumns(repository, "RateAccountTransactions",
					"RateAccountTransactionEdgeChangeHeightIndex", "target", "rater", "category", "rating_change_height"));
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
			assertEquals(70, aliceSubject.getMappedTrustWeightPercent());
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
							null, null, null, null, null, null);
			assertSingleAccountSnapshotPage(silverAccountSnapshots, alice.getAddress());

			List<AccountTrustSnapshotData> playerLevelThreeSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.PLAYER, null, null, 3, null, null,
							null);
			assertSingleAccountSnapshotPage(playerLevelThreeSnapshots, dilbert.getAddress());

			List<AccountTrustSnapshotData> firstAccountSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, null, 1, null,
							null);
			assertSingleAccountSnapshotPage(firstAccountSnapshots, alice.getAddress());

			List<AccountTrustSnapshotData> reversedFirstAccountSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, null, 1, null,
							true);
			assertEquals(4, reversedFirstAccountSnapshots.size());
			assertFalse(alice.getAddress().equals(reversedFirstAccountSnapshots.get(0).getAccountAddress()));

			assertTrue(repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(null, AccountRatingCategory.SUBJECT, null, null, null, 0, null,
							null).isEmpty());
		}
	}

	@Test
	public void testFirstTrustSnapshotPopulationUsesMintingSeedState() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Set<String> seedAddresses = getMintingSeedAddresses(repository,
					repository.getBlockRepository().getBlockchainHeight() + 1);
			assertFalse(seedAddresses.isEmpty());
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(null, null, null).isEmpty());

			BlockUtils.mintBlock(repository);
			BlockData lastBlockData = repository.getBlockRepository().getLastBlock();

			List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null);
			assertEquals(seedAddresses.size() * AccountRatingCategory.values().length, snapshots.size());
			assertCompleteSnapshotCategories(snapshots, seedAddresses);

			for (String seedAddress : seedAddresses) {
				AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, seedAddress,
						AccountRatingCategory.SUBJECT);
				assertTrue(subjectSnapshot.isMintingSeedMember());
				assertEquals(AccountTrustStatus.UNVERIFIED, subjectSnapshot.getMappedTrustStatus());
				assertEquals(lastBlockData.getHeight().intValue(), subjectSnapshot.getSnapshotHeight());
				assertEquals(lastBlockData.getTimestamp(), subjectSnapshot.getSnapshotTimestamp());
			}

			assertTrue(repository.getAccountRatingRepository()
					.getTrustStatusChanges(null, null, null, null, null, null, null)
					.isEmpty());
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
			AccountTrustSnapshotData bobSubject = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(0L, bobSubject.getScore());
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), bobSubject.getSnapshotHeight());

			BlockUtils.orphanLastBlock(repository);
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(baselineHeight, aliceSubject.getSnapshotHeight());
		}
	}

	@Test
	public void testSameHeightTrustSnapshotReplacementReplacesRowsAndChangeHistory() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ensureKnownAccount(repository, alice);

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true, AccountTrustStatus.UNVERIFIED));
			replaceTrustSnapshots(repository, 11, 2000L,
					trustDerivation(alice, true, AccountTrustStatus.GOLD));

			assertEquals(4, repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(alice.getAddress()).size());
			List<AccountTrustStatusChangeData> initialChanges = repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.GOLD, null, null, null);
			assertEquals(1, initialChanges.size());

			replaceTrustSnapshots(repository, 11, 3000L,
					trustDerivation(alice, true, AccountTrustStatus.SILVER));

			List<AccountTrustSnapshotData> replacedSnapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(alice.getAddress());
			assertEquals(4, replacedSnapshots.size());
			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.SILVER, aliceSubject.getMappedTrustStatus());
			assertEquals(11, aliceSubject.getSnapshotHeight());
			assertEquals(3000L, aliceSubject.getSnapshotTimestamp());

			List<AccountTrustStatusChangeData> replacedChanges = repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), AccountRatingCategory.SUBJECT,
							null, null, null, null, null);
			assertEquals(1, replacedChanges.size());
			assertEquals(AccountTrustStatus.GOLD, replacedChanges.get(0).getPreviousTrustStatus());
			assertEquals(AccountTrustStatus.SILVER, replacedChanges.get(0).getNewTrustStatus());
			assertEquals(11, replacedChanges.get(0).getSnapshotHeight());
			assertEquals(3000L, replacedChanges.get(0).getSnapshotTimestamp());
		}
	}

	@Test
	public void testRollbackTrustSnapshotReplacementRemovesOrphanedChangeHistory() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ensureKnownAccount(repository, alice);

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true, AccountTrustStatus.UNVERIFIED));
			replaceTrustSnapshots(repository, 11, 2000L,
					trustDerivation(alice, true, AccountTrustStatus.GOLD));
			assertEquals(1, repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.GOLD, null, null, null)
					.size());

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true, AccountTrustStatus.UNVERIFIED));

			assertEquals(4, repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(alice.getAddress()).size());
			assertEquals(AccountTrustStatus.UNVERIFIED, findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT).getMappedTrustStatus());
			assertTrue(repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), null, null, null, null, null, null)
					.isEmpty());
		}
	}

	@Test
	public void testTrustSnapshotsAndChangeHistorySurviveRepositoryReopen() throws DataException {
		Common.useSettingsAndDb(Common.testSettingsFilename, false);

		try {
			String aliceAddress;

			try (final Repository repository = RepositoryManager.getRepository()) {
				TestAccount alice = Common.getTestAccount(repository, "alice");
				aliceAddress = alice.getAddress();
				ensureKnownAccount(repository, alice);

				replaceTrustSnapshots(repository, 10, 1000L,
						trustDerivation(alice, true, AccountTrustStatus.UNVERIFIED));
				replaceTrustSnapshots(repository, 11, 2000L,
						trustDerivation(alice, true, AccountTrustStatus.GOLD));
			}

			RepositoryManager.closeRepositoryFactory();
			Common.setRepository(false);

			try (final Repository repository = RepositoryManager.getRepository()) {
				List<AccountTrustSnapshotData> reopenedSnapshots = repository.getAccountRatingRepository()
						.getTrustDerivationSnapshots(aliceAddress);
				assertEquals(4, reopenedSnapshots.size());
				assertEquals(AccountTrustStatus.GOLD, findSnapshot(repository, aliceAddress,
						AccountRatingCategory.SUBJECT).getMappedTrustStatus());

				List<AccountTrustStatusChangeData> reopenedChanges = repository.getAccountRatingRepository()
						.getTrustStatusChanges(aliceAddress, AccountRatingCategory.SUBJECT,
								AccountTrustStatus.UNVERIFIED, AccountTrustStatus.GOLD, null, null, null);
				assertEquals(1, reopenedChanges.size());
				assertEquals(11, reopenedChanges.get(0).getSnapshotHeight());
			}
		} finally {
			Common.useDefaultSettings();
		}
	}

	@Test
	public void testOrdinaryBlockDoesNotRefreshTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			ensureKnownAccount(repository, alice);
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			AccountTrustSnapshotData initialAliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			int snapshotHeight = initialAliceSubject.getSnapshotHeight();
			long snapshotTimestamp = initialAliceSubject.getSnapshotTimestamp();

			BlockUtils.mintBlock(repository);

			AccountTrustSnapshotData laterAliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(snapshotHeight, laterAliceSubject.getSnapshotHeight());
			assertEquals(snapshotTimestamp, laterAliceSubject.getSnapshotTimestamp());
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			assertFalse(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 0), alice);
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			BlockUtils.orphanLastBlock(repository);
			assertFalse(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());
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

	@Test
	public void testNonMintingGroupMembershipDoesNotRefreshSnapshot() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			AccountTrustSnapshotData initialAliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			int snapshotHeight = initialAliceSubject.getSnapshotHeight();

			CreateGroupTransactionData createGroupTransactionData = new CreateGroupTransactionData(
					TestTransaction.generateBase(alice), "non-minting-trust-snapshot-test", "non minting group", true,
					Group.ApprovalThreshold.ONE, 10, 1440);
			TransactionUtils.signAndMint(repository, createGroupTransactionData, alice);
			int nonMintingGroupId = repository.getGroupRepository()
					.fromGroupName("non-minting-trust-snapshot-test").getGroupId();
			assertFalse(Groups.getGroupIdsToMint(BlockChain.getInstance(),
					repository.getBlockRepository().getBlockchainHeight()).contains(nonMintingGroupId));

			JoinGroupTransactionData joinGroupTransactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					nonMintingGroupId);
			TransactionUtils.signAndMint(repository, joinGroupTransactionData, bob);

			AccountTrustSnapshotData laterAliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(snapshotHeight, laterAliceSubject.getSnapshotHeight());
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());
		}
	}

	@Test
	public void testApprovedMintingGroupMembershipChangeRefreshesSnapshot() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int mintingGroupId = Groups.getGroupIdsToMint(BlockChain.getInstance(),
					repository.getBlockRepository().getBlockchainHeight() + 1).get(0);
			assertEquals(TestChainBootstrapUtils.MINTING_GROUP_ID, mintingGroupId);

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			repository.saveChanges();
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
			AccountTrustSnapshotData initialBobSubject = findSnapshot(repository, bob.getAddress(),
					AccountRatingCategory.SUBJECT);
			int snapshotHeight = initialBobSubject.getSnapshotHeight();

			GroupKickTransactionData kickBobTransactionData = new GroupKickTransactionData(
					TestTransaction.generateBase(alice, mintingGroupId), mintingGroupId, bob.getAddress(),
					"trust snapshot test");
			TransactionUtils.signAndMint(repository, kickBobTransactionData, alice);
			assertEquals(snapshotHeight, findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT)
					.getSnapshotHeight());

			TransactionData approvalTransactionData = new GroupApprovalTransactionData(TestTransaction.generateBase(alice),
					kickBobTransactionData.getSignature(), true);
			assertEquals(Transaction.ValidationResult.OK,
					TransactionUtils.signAndImport(repository, approvalTransactionData, alice));
			BlockUtils.mintBlocks(repository, 11);

			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			BlockUtils.orphanLastBlock(repository);
			AccountTrustSnapshotData restoredBobSubject = findSnapshot(repository, bob.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue(restoredBobSubject.isMintingSeedMember());
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), restoredBobSubject.getSnapshotHeight());
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private Set<String> getMintingSeedAddresses(Repository repository, int snapshotHeight) throws DataException {
		Set<String> seedAddresses = new TreeSet<>(Groups.getAllMembers(repository.getGroupRepository(),
				Groups.getGroupIdsToMint(BlockChain.getInstance(), snapshotHeight)));
		seedAddresses.remove(Group.NULL_OWNER_ADDRESS);
		return seedAddresses;
	}

	private void replaceTrustSnapshots(Repository repository, int snapshotHeight, long snapshotTimestamp,
			AccountTrustDerivationData... derivationData) throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Arrays.asList(derivationData),
				snapshotHeight, snapshotTimestamp);
		repository.saveChanges();
	}

	private AccountTrustDerivationData trustDerivation(PrivateKeyAccount account, boolean seedMember,
			AccountTrustStatus trustStatus) {
		return new AccountTrustDerivationData(account.getPublicKey(), account.getAddress(), trustStatus, seedMember,
				Arrays.asList(
						categoryTrust(AccountRatingCategory.SUBJECT, trustStatus),
						categoryTrust(AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED),
						categoryTrust(AccountRatingCategory.TRAINER, AccountTrustStatus.UNVERIFIED),
						categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.UNVERIFIED)));
	}

	private AccountTrustCategoryData categoryTrust(AccountRatingCategory category, AccountTrustStatus trustStatus) {
		return new AccountTrustCategoryData(category, scoreForStatus(trustStatus), levelForStatus(trustStatus),
				trustStatus, new AccountTrustRatingCountsData(), Collections.emptyList());
	}

	private long scoreForStatus(AccountTrustStatus trustStatus) {
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

	private int levelForStatus(AccountTrustStatus trustStatus) {
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

	private void assertCompleteSnapshotCategories(List<AccountTrustSnapshotData> snapshots, Set<String> expectedAccounts) {
		Set<String> foundAccountCategories = new TreeSet<>();
		for (AccountTrustSnapshotData snapshot : snapshots)
			foundAccountCategories.add(snapshot.getAccountAddress() + ":" + snapshot.getCategory().name());

		for (String accountAddress : expectedAccounts) {
			for (AccountRatingCategory category : AccountRatingCategory.values())
				assertTrue(foundAccountCategories.contains(accountAddress + ":" + category.name()));
		}
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
