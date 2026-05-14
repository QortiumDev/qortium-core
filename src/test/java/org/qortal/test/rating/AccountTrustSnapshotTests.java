package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.MANAGER, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, chloe, AccountRatingCategory.TRAINER, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(chloe, dilbert, AccountRatingCategory.PLAYER, 4), chloe);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, 4), dilbert);

			BlockData lastBlockData = repository.getBlockRepository().getLastBlock();
			List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null);
			assertEquals(16, snapshots.size());

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(), AccountRatingCategory.SUBJECT);
			assertArrayEquals(alice.getPublicKey(), aliceSubject.getAccountPublicKey());
			assertTrue(aliceSubject.isMintingSeedMember());
			assertEquals(64_000_000L, aliceSubject.getScore());
			assertEquals(2, aliceSubject.getLevel());
			assertEquals(AccountTrustStatus.SILVER, aliceSubject.getMappedTrustStatus());
			assertEquals(50, aliceSubject.getMappedTrustWeightPercent());
			assertEquals(1, aliceSubject.getInboundRatings().getPositiveVeryHighCount());
			assertEquals(lastBlockData.getHeight().intValue(), aliceSubject.getSnapshotHeight());
			assertEquals(lastBlockData.getTimestamp(), aliceSubject.getSnapshotTimestamp());

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);
			assertEquals(1_000_000L, bobManager.getScore());
			assertEquals(2, bobManager.getLevel());
			assertEquals(1, bobManager.getInboundRatings().getPositiveVeryHighCount());

			AccountTrustSnapshotData chloeTrainer = findSnapshot(repository, chloe.getAddress(), AccountRatingCategory.TRAINER);
			assertEquals(4_000_000L, chloeTrainer.getScore());
			assertEquals(2, chloeTrainer.getLevel());

			AccountTrustSnapshotData dilbertPlayer = findSnapshot(repository, dilbert.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(16_000_000L, dilbertPlayer.getScore());
			assertEquals(3, dilbertPlayer.getLevel());
		}
	}

	@Test
	public void testRateAccountBlockSnapshotRestoresOnOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			BlockUtils.mintBlock(repository);
			int baselineHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.MANAGER, 4), alice);
			assertEquals(1_000_000L, findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER).getScore());

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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.MANAGER, 4), alice);
			assertFalse(repository.getAccountRatingRepository().getTrustDerivationSnapshots(bob.getAddress()).isEmpty());

			TransactionUtils.signAndMint(repository,
					ratingData(alice, bob, AccountRatingCategory.MANAGER, AccountRating.NO_RATING), alice);

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

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress, AccountRatingCategory category)
			throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
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
}
