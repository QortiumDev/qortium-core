package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.AccountTrustCategoryPolicyCodec;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.AccountLevelShareBin;
import org.qortal.block.ChainParameter;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustCategoryPoliciesData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.ChainParameterUpdateTransaction;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Amounts;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ChainParameterUpdateTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testApprovedBlockRewardUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			long originalReward = BlockChain.getInstance().getRewardAtHeight(repository, activationHeight);
			long updatedReward = originalReward + Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildBlockRewardUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedReward);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight - 1));
			assertEquals(updatedReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight));
			assertEquals(updatedReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.BLOCK_REWARD.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.BLOCK_REWARD.id, activationHeight));
			assertEquals(originalReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedMinAccountsToActivateShareBinUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalValue = BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight);
			int updatedValue = originalValue + 1;

			ChainParameterUpdateTransactionData transactionData = buildMinAccountsToActivateShareBinUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedValue);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalValue, BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalValue, BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight - 1));
			assertEquals(updatedValue, BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight));
			assertEquals(updatedValue, BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, activationHeight));
			assertEquals(originalValue, BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountRatingCooldownUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository,
					buildRateAccountTransactionData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalValue = BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight);
			int updatedValue = 0;

			ChainParameterUpdateTransactionData transactionData = buildAccountRatingCooldownUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedValue);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalValue, BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight));
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							buildRateAccountTransactionData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalValue, BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight - 1));
			assertEquals(updatedValue, BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight));
			assertEquals(updatedValue, BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight + 100));
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							buildRateAccountTransactionData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository,
							buildRateAccountTransactionData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, activationHeight));
			assertEquals(originalValue, BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedTrustStatusVoteWeightsUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int[] originalWeights = BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight);
			int[] updatedWeights = new int[] { 5, 10, 50, 75, 95 };

			ChainParameterUpdateTransactionData transactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedWeights);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalWeights,
					BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalWeights,
					BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight - 1));
			assertArrayEquals(updatedWeights,
					BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight));
			assertArrayEquals(updatedWeights,
					BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, activationHeight));
			assertArrayEquals(originalWeights,
					BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustStartingEnergyUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			long originalStartingEnergy = BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight);
			long updatedStartingEnergy = originalStartingEnergy + 1_000L;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustStartingEnergyUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedStartingEnergy);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalStartingEnergy,
					BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalStartingEnergy,
					BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight - 1));
			assertEquals(updatedStartingEnergy,
					BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight));
			assertEquals(updatedStartingEnergy,
					BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, activationHeight));
			assertEquals(originalStartingEnergy,
					BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustManagerEnergyHopsUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalManagerEnergyHops = BlockChain.getInstance()
					.getAccountTrustManagerEnergyHops(repository, activationHeight);
			int updatedManagerEnergyHops = originalManagerEnergyHops + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustManagerEnergyHopsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedManagerEnergyHops);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalManagerEnergyHops,
					BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalManagerEnergyHops,
					BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, activationHeight - 1));
			assertEquals(updatedManagerEnergyHops,
					BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, activationHeight));
			assertEquals(updatedManagerEnergyHops,
					BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, activationHeight));
			assertEquals(originalManagerEnergyHops,
					BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustPositiveMinBranchCountUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalPositiveMinBranchCount = BlockChain.getInstance()
					.getAccountTrustPositiveMinBranchCount(repository, activationHeight);
			int updatedPositiveMinBranchCount = originalPositiveMinBranchCount + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustPositiveMinBranchCountUpdate(repository,
					alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					updatedPositiveMinBranchCount);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalPositiveMinBranchCount,
					BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalPositiveMinBranchCount,
					BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, activationHeight - 1));
			assertEquals(updatedPositiveMinBranchCount,
					BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, activationHeight));
			assertEquals(updatedPositiveMinBranchCount,
					BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, activationHeight));
			assertEquals(originalPositiveMinBranchCount,
					BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustSuspiciousMinRaterCountUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalSuspiciousMinRaterCount = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinRaterCount(repository, activationHeight);
			int updatedSuspiciousMinRaterCount = originalSuspiciousMinRaterCount + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustSuspiciousMinRaterCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					updatedSuspiciousMinRaterCount);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight - 1));
			assertEquals(updatedSuspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight));
			assertEquals(updatedSuspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, activationHeight));
			assertEquals(originalSuspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustSuspiciousMinBranchCountUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalSuspiciousMinBranchCount = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinBranchCount(repository, activationHeight);
			int updatedSuspiciousMinBranchCount = originalSuspiciousMinBranchCount + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustSuspiciousMinBranchCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					updatedSuspiciousMinBranchCount);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinBranchCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinBranchCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight - 1));
			assertEquals(updatedSuspiciousMinBranchCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight));
			assertEquals(updatedSuspiciousMinBranchCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight));
			assertEquals(originalSuspiciousMinBranchCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight));
		}
	}

	@Test
	public void testSuspiciousMinBranchCountZeroFollowsEffectiveRaterCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 20);
			int suspiciousMinRaterCount = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinRaterCount(repository, activationHeight) + 1;

			ChainParameterUpdateTransactionData raterCountTransactionData =
					buildAccountTrustSuspiciousMinRaterCountUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, suspiciousMinRaterCount);
			TransactionUtils.signAndMint(repository, raterCountTransactionData, alice);
			approveAndSettle(repository, raterCountTransactionData);

			ChainParameterUpdateTransactionData branchCountTransactionData =
					buildAccountTrustSuspiciousMinBranchCountUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			TransactionUtils.signAndMint(repository, branchCountTransactionData, alice);
			approveAndSettle(repository, branchCountTransactionData);

			assertEquals(suspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight));
			assertEquals(suspiciousMinRaterCount,
					BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, activationHeight));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight);
			assertEquals(0, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.decodeIntValue(
					overlayData.getValue()));
		}
	}

	@Test
	public void testApprovedAccountTrustSuspiciousMinRatingConfidenceUpdateAppliesAtActivationHeight()
			throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int originalSuspiciousMinRatingConfidence = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight);
			int updatedSuspiciousMinRatingConfidence = originalSuspiciousMinRatingConfidence == 4
					? 1
					: originalSuspiciousMinRatingConfidence + 1;

			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
							updatedSuspiciousMinRatingConfidence);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinRatingConfidence,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalSuspiciousMinRatingConfidence,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight - 1));
			assertEquals(updatedSuspiciousMinRatingConfidence,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight));
			assertEquals(updatedSuspiciousMinRatingConfidence,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id,
							activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id,
							activationHeight));
			assertEquals(originalSuspiciousMinRatingConfidence,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedAccountTrustCategoryPoliciesUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			byte[] originalValue = BlockChain.getInstance()
					.getAccountTrustCategoryPoliciesValue(repository, activationHeight);
			byte[] updatedValue = buildCategoryPoliciesValueWithLevelThreshold(AccountRatingCategory.SUBJECT, 1,
					BlockChain.getInstance().getAccountTrustSettings()
							.getLevelThreshold(AccountRatingCategory.SUBJECT, 1) + 1_000_000L);

			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustCategoryPoliciesUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedValue);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING,
					GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalValue,
					BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalValue,
					BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, activationHeight - 1));
			assertArrayEquals(updatedValue,
					BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, activationHeight));
			assertArrayEquals(updatedValue,
					BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, activationHeight + 100));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING,
					GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, activationHeight));
			assertArrayEquals(originalValue,
					BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, activationHeight));
		}
	}

	@Test
	public void testAccountTrustCategoryPoliciesChangeDerivedTrustAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			AccountTrustTestUtils.saveDerivedSilverSubjectRatings(repository, alice, bob, chloe, dilbert);
			repository.saveChanges();

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			byte[] updatedValue = buildCategoryPoliciesValueWithLevelThreshold(AccountRatingCategory.SUBJECT, 2,
					BlockChain.getInstance().getAccountTrustSettings()
							.getLevelThreshold(AccountRatingCategory.SUBJECT, 2) + 10_000_000L);
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustCategoryPoliciesUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedValue);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			AccountTrustDerivationData beforeActivationDerivation =
					findDerivation(AccountTrustDerivation.deriveAll(repository, activationHeight - 1), alice);
			AccountTrustDerivationData atActivationDerivation =
					findDerivation(AccountTrustDerivation.deriveAll(repository, activationHeight), alice);

			assertEquals(AccountTrustStatus.SILVER, beforeActivationDerivation.getDerivedTrustStatus());
			assertEquals(AccountTrustStatus.BRONZE, atActivationDerivation.getDerivedTrustStatus());
		}
	}

	@Test
	public void testAccountTrustCategoryPoliciesActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			byte[] updatedValue = buildCategoryPoliciesValueWithLevelThreshold(AccountRatingCategory.SUBJECT, 1,
					BlockChain.getInstance().getAccountTrustSettings()
							.getLevelThreshold(AccountRatingCategory.SUBJECT, 1) + 1_000_000L);
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustCategoryPoliciesUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedValue);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
		}
	}

	@Test
	public void testTrustStatusVoteWeightsActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int[] updatedWeights = new int[] { 0, 25, 40, 70, 100 };

			ChainParameterUpdateTransactionData transactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedWeights);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);
			assertEquals(AccountTrustStatus.UNVERIFIED, beforeActivationSnapshot.getMappedTrustStatus());
			assertEquals(0, beforeActivationSnapshot.getMappedTrustWeightPercent());

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());
			assertEquals(AccountTrustStatus.UNVERIFIED, activationSnapshot.getMappedTrustStatus());
			assertEquals(25, activationSnapshot.getMappedTrustWeightPercent());

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
			assertEquals(AccountTrustStatus.UNVERIFIED, orphanedSnapshot.getMappedTrustStatus());
			assertEquals(0, orphanedSnapshot.getMappedTrustWeightPercent());
		}
	}

	@Test
	public void testAccountTrustStartingEnergyActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			long updatedStartingEnergy = BlockChain.getInstance().getAccountTrustStartingEnergy(repository, activationHeight)
					+ 1_000L;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustStartingEnergyUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedStartingEnergy);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
		}
	}

	@Test
	public void testAccountTrustManagerEnergyHopsActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int updatedManagerEnergyHops = BlockChain.getInstance()
					.getAccountTrustManagerEnergyHops(repository, activationHeight) + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustManagerEnergyHopsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedManagerEnergyHops);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
		}
	}

	@Test
	public void testAccountTrustPositiveMinBranchCountActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int updatedPositiveMinBranchCount = BlockChain.getInstance()
					.getAccountTrustPositiveMinBranchCount(repository, activationHeight) + 1;

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustPositiveMinBranchCountUpdate(repository,
					alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					updatedPositiveMinBranchCount);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
		}
	}

	@Test
	public void testAccountTrustSuspiciousMinRaterCountActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int updatedSuspiciousMinRaterCount = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinRaterCount(repository, activationHeight) + 1;

			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinRaterCountUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
							updatedSuspiciousMinRaterCount);
			assertTrustParameterActivationRefreshesTrustSnapshots(repository, alice, activationHeight, transactionData);
		}
	}

	@Test
	public void testAccountTrustSuspiciousMinBranchCountActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int updatedSuspiciousMinBranchCount = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinBranchCount(repository, activationHeight) + 1;

			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinBranchCountUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
							updatedSuspiciousMinBranchCount);
			assertTrustParameterActivationRefreshesTrustSnapshots(repository, alice, activationHeight, transactionData);
		}
	}

	@Test
	public void testAccountTrustSuspiciousMinRatingConfidenceActivationRefreshesTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			int originalSuspiciousMinRatingConfidence = BlockChain.getInstance()
					.getAccountTrustSuspiciousMinRatingConfidence(repository, activationHeight);
			int updatedSuspiciousMinRatingConfidence = originalSuspiciousMinRatingConfidence == 4
					? 1
					: originalSuspiciousMinRatingConfidence + 1;

			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
							updatedSuspiciousMinRatingConfidence);
			assertTrustParameterActivationRefreshesTrustSnapshots(repository, alice, activationHeight, transactionData);
		}
	}

	@Test
	public void testNonTrustParameterActivationDoesNotRefreshTrustSnapshots() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 5);
			long updatedReward = BlockChain.getInstance().getRewardAtHeight(repository, activationHeight) + Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildBlockRewardUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedReward);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			approveAndSettle(repository, transactionData);

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertNotNull(beforeActivationSnapshot);
			int snapshotHeightBeforeActivation = beforeActivationSnapshot.getSnapshotHeight();

			BlockUtils.mintBlock(repository);
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
			assertEquals(snapshotHeightBeforeActivation, activationSnapshot.getSnapshotHeight());
		}
	}

	@Test
	public void testApprovedRewardShareWeightsUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			int[] originalWeights = BlockChain.getInstance().getRewardShareWeights(repository, activationHeight);
			int[] updatedWeights = new int[] { 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 };

			ChainParameterUpdateTransactionData transactionData = buildRewardShareWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedWeights);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalWeights, BlockChain.getInstance().getRewardShareWeights(repository, activationHeight));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertArrayEquals(originalWeights, BlockChain.getInstance().getRewardShareWeights(repository, activationHeight - 1));
			assertArrayEquals(updatedWeights, BlockChain.getInstance().getRewardShareWeights(repository, activationHeight));
			assertArrayEquals(updatedWeights, BlockChain.getInstance().getRewardShareWeights(repository, activationHeight + 100));
			assertRewardShareBinsMatchWeights(repository, activationHeight, updatedWeights);

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.REWARD_SHARE_WEIGHTS.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.REWARD_SHARE_WEIGHTS.id, activationHeight));
			assertArrayEquals(originalWeights, BlockChain.getInstance().getRewardShareWeights(repository, activationHeight));
		}
	}

	@Test
	public void testApprovedUnitFeeUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = System.currentTimeMillis();

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			long originalUnitFee = BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight, timestamp);
			long updatedUnitFee = originalUnitFee + Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildUnitFeeUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedUnitFee);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalUnitFee, BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight, timestamp));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalUnitFee, BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight - 1, timestamp));
			assertEquals(updatedUnitFee, BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight, timestamp));
			assertEquals(updatedUnitFee, BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight + 100, timestamp));

			PaymentTransaction beforeActivationTransaction = buildPaymentTransaction(repository, alice, originalUnitFee);
			assertEquals(Transaction.ValidationResult.OK, beforeActivationTransaction.isFeeValid());

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			PaymentTransaction oldFeeTransaction = buildPaymentTransaction(repository, alice, originalUnitFee);
			assertEquals(Transaction.ValidationResult.INSUFFICIENT_FEE, oldFeeTransaction.isFeeValid());

			PaymentTransaction updatedFeeTransaction = buildPaymentTransaction(repository, alice, 0L);
			updatedFeeTransaction.getTransactionData().setFee(updatedFeeTransaction.calcRecommendedFee());
			assertEquals(Transaction.ValidationResult.OK, updatedFeeTransaction.isFeeValid());

			assertEquals(BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(timestamp),
					new RegisterNameTransaction(repository, null).getUnitFee(timestamp));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.UNIT_FEE.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.UNIT_FEE.id, activationHeight));
			assertEquals(originalUnitFee, BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight, timestamp));
		}
	}

	@Test
	public void testApprovedNameRegistrationUnitFeeUpdateAppliesAtActivationHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = System.currentTimeMillis();

			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			long originalNameRegistrationUnitFee = BlockChain.getInstance()
					.getNameRegistrationUnitFeeAtHeight(repository, activationHeight, timestamp);
			long updatedNameRegistrationUnitFee = originalNameRegistrationUnitFee + Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildNameRegistrationUnitFeeUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedNameRegistrationUnitFee);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalNameRegistrationUnitFee,
					BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, activationHeight, timestamp));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.APPROVED, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertEquals(originalNameRegistrationUnitFee,
					BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, activationHeight - 1, timestamp));
			assertEquals(updatedNameRegistrationUnitFee,
					BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, activationHeight, timestamp));
			assertEquals(updatedNameRegistrationUnitFee,
					BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, activationHeight + 100, timestamp));

			RegisterNameTransaction beforeActivationTransaction = buildRegisterNameTransaction(repository, alice,
					originalNameRegistrationUnitFee, "name-fee-before");
			assertEquals(Transaction.ValidationResult.OK, beforeActivationTransaction.isFeeValid());

			BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

			RegisterNameTransaction oldFeeTransaction = buildRegisterNameTransaction(repository, alice,
					originalNameRegistrationUnitFee, "name-fee-old");
			assertEquals(Transaction.ValidationResult.INSUFFICIENT_FEE, oldFeeTransaction.isFeeValid());

			RegisterNameTransaction updatedFeeTransaction = buildRegisterNameTransaction(repository, alice,
					0L, "name-fee-updated");
			updatedFeeTransaction.getTransactionData().setFee(updatedFeeTransaction.calcRecommendedFee());
			assertEquals(Transaction.ValidationResult.OK, updatedFeeTransaction.isFeeValid());

			assertEquals(BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
					BlockChain.getInstance().getUnitFeeAtHeight(repository, activationHeight, timestamp));

			ChainParameterData overlayData = repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, activationHeight);
			assertEquals(activationHeight, overlayData.getActivationHeight());
			assertArrayEquals(transactionData.getValue(), overlayData.getValue());

			int approvalHeight = GroupUtils.getApprovalHeight(repository, transactionData.getSignature());
			BlockUtils.orphanToBlock(repository, approvalHeight - 1);

			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, activationHeight));
			assertEquals(originalNameRegistrationUnitFee,
					BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, activationHeight, timestamp));
		}
	}

	@Test
	public void testChainParameterUpdateRequiresActiveDevelopmentGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			long updatedReward = Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData noGroupTransactionData = buildBlockRewardUpdate(repository, alice,
					Group.NO_GROUP, activationHeight, updatedReward);
			assertEquals(Transaction.ValidationResult.INVALID_TX_GROUP_ID,
					new ChainParameterUpdateTransaction(repository, noGroupTransactionData).isValid());

			ChainParameterUpdateTransactionData mintingGroupTransactionData = buildBlockRewardUpdate(repository, alice,
					TestChainBootstrapUtils.MINTING_GROUP_ID, activationHeight, updatedReward);
			assertEquals(Transaction.ValidationResult.INVALID_TX_GROUP_ID,
					new ChainParameterUpdateTransaction(repository, mintingGroupTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRequiresActivationLeadTimeAtSubmission() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight()
					+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay();
			long updatedReward = Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildBlockRewardUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedReward);

			assertEquals(Transaction.ValidationResult.INVALID_LIFETIME,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testApprovalFailsIfActivationLeadTimeIsConsumed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + getApprovalSettlementBlockCount(repository) + 3;
			long originalReward = BlockChain.getInstance().getRewardAtHeight(repository, activationHeight);
			long updatedReward = originalReward + Amounts.MULTIPLIER;

			ChainParameterUpdateTransactionData transactionData = buildBlockRewardUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, updatedReward);
			TransactionUtils.signAndMint(repository, transactionData, alice);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));

			approveAndSettle(repository, transactionData);

			assertEquals(Transaction.ApprovalStatus.INVALID, GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));
			assertNull(repository.getChainParameterRepository()
					.getEffectiveParameter(ChainParameter.BLOCK_REWARD.id, activationHeight));
			assertEquals(originalReward, BlockChain.getInstance().getRewardAtHeight(repository, activationHeight));
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNegativeIntegerValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData transactionData = buildMinAccountsToActivateShareBinUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);

			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNegativeAccountRatingCooldownValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData transactionData = buildAccountRatingCooldownUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);

			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNegativeUnitFeeValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData transactionData = buildUnitFeeUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1L);

			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNegativeNameRegistrationUnitFeeValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData transactionData = buildNameRegistrationUnitFeeUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1L);

			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidRewardShareWeights() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData shortTransactionData = buildRewardShareWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, new int[] { 1, 2, 3 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, shortTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildRewardShareWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					new int[] { 1, 2, 3, 4, 5, -6, 7, 8, 9, 10 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());

			ChainParameterUpdateTransactionData zeroLevelOneTransactionData = buildRewardShareWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroLevelOneTransactionData).isValid());

			ChainParameterUpdateTransactionData zeroTransactionData = buildRewardShareWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidTrustStatusVoteWeights() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData shortTransactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, new int[] { 0, 40, 70 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, shortTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, new int[] { 0, -1, 40, 70, 100 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());

			ChainParameterUpdateTransactionData excessiveTransactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, new int[] { 0, 0, 40, 70, 101 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, excessiveTransactionData).isValid());

			ChainParameterUpdateTransactionData zeroTransactionData = buildTrustStatusVoteWeightsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, new int[] { 0, 0, 0, 0, 0 });
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNonPositiveAccountTrustStartingEnergy() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData = buildAccountTrustStartingEnergyUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0L);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildAccountTrustStartingEnergyUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1L);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNonPositiveAccountTrustManagerEnergyHops() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData = buildAccountTrustManagerEnergyHopsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildAccountTrustManagerEnergyHopsUpdate(repository, alice,
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsNonPositiveAccountTrustPositiveMinBranchCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData = buildAccountTrustPositiveMinBranchCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildAccountTrustPositiveMinBranchCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidAccountTrustSuspiciousMinRaterCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData = buildAccountTrustSuspiciousMinRaterCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildAccountTrustSuspiciousMinRaterCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidAccountTrustSuspiciousMinBranchCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData = buildAccountTrustSuspiciousMinBranchCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			assertEquals(Transaction.ValidationResult.OK,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData = buildAccountTrustSuspiciousMinBranchCountUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidAccountTrustSuspiciousMinRatingConfidence() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);

			ChainParameterUpdateTransactionData zeroTransactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 0);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, zeroTransactionData).isValid());

			ChainParameterUpdateTransactionData excessiveTransactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, 5);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, excessiveTransactionData).isValid());

			ChainParameterUpdateTransactionData negativeTransactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight, -1);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, negativeTransactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateAcceptsAccountTrustCategoryPolicies() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			byte[] categoryPoliciesValue = buildCategoryPoliciesValueWithLevelThreshold(
					AccountRatingCategory.SUBJECT, 1,
					BlockChain.getInstance().getAccountTrustSettings()
							.getLevelThreshold(AccountRatingCategory.SUBJECT, 1) + 1_000_000L);

			ChainParameterUpdateTransactionData transactionData = buildAccountTrustCategoryPoliciesUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					categoryPoliciesValue);

			assertEquals(Transaction.ValidationResult.OK,
					new ChainParameterUpdateTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testChainParameterUpdateRejectsInvalidAccountTrustCategoryPolicies() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = getActivationHeightSafelyAfterApproval(repository, 10);
			byte[] validCategoryPoliciesValue = BlockChain.getInstance().getAccountTrustCategoryPoliciesValue();

			ChainParameterUpdateTransactionData shortTransactionData = buildAccountTrustCategoryPoliciesUpdate(
					repository, alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
					new byte[validCategoryPoliciesValue.length - 1]);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, shortTransactionData).isValid());

			byte[] policyWithUnreachableSuspiciousThreshold = buildCategoryPoliciesValueWithSuspiciousCap(
					AccountRatingCategory.SUBJECT, 4_000_000L, 3);
			ChainParameterUpdateTransactionData unreachableThresholdTransactionData =
					buildAccountTrustCategoryPoliciesUpdate(repository, alice,
							TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, activationHeight,
							policyWithUnreachableSuspiciousThreshold);
			assertEquals(Transaction.ValidationResult.INVALID_VALUE_LENGTH,
					new ChainParameterUpdateTransaction(repository, unreachableThresholdTransactionData).isValid());
		}
	}

	private static ChainParameterUpdateTransactionData buildBlockRewardUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, long reward) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.BLOCK_REWARD.id, activationHeight, ChainParameter.BLOCK_REWARD.encodeLongValue(reward));
	}

	private static ChainParameterUpdateTransactionData buildMinAccountsToActivateShareBinUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int value) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, activationHeight,
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(value));
	}

	private static ChainParameterUpdateTransactionData buildRewardShareWeightsUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int[] weights) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.REWARD_SHARE_WEIGHTS.id, activationHeight,
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountRatingCooldownUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int cooldownBlocks) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, activationHeight,
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.encodeIntValue(cooldownBlocks));
	}

	private static ChainParameterUpdateTransactionData buildTrustStatusVoteWeightsUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int[] weights) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustStartingEnergyUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, long startingEnergy) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(startingEnergy));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustManagerEnergyHopsUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int managerEnergyHops) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(managerEnergyHops));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustPositiveMinBranchCountUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, int positiveMinBranchCount)
			throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(positiveMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRaterCountUpdate(
			Repository repository, PrivateKeyAccount updater, int txGroupId, int activationHeight,
			int suspiciousMinRaterCount) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(suspiciousMinRaterCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinBranchCountUpdate(
			Repository repository, PrivateKeyAccount updater, int txGroupId, int activationHeight,
			int suspiciousMinBranchCount) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(suspiciousMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRatingConfidenceUpdate(
			Repository repository, PrivateKeyAccount updater, int txGroupId, int activationHeight,
			int suspiciousMinRatingConfidence) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
						suspiciousMinRatingConfidence));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustCategoryPoliciesUpdate(
			Repository repository, PrivateKeyAccount updater, int txGroupId, int activationHeight,
			byte[] categoryPoliciesValue) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, activationHeight, categoryPoliciesValue);
	}

	private static ChainParameterUpdateTransactionData buildUnitFeeUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, long unitFee) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.UNIT_FEE.id, activationHeight, ChainParameter.UNIT_FEE.encodeLongValue(unitFee));
	}

	private static ChainParameterUpdateTransactionData buildNameRegistrationUnitFeeUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, long nameRegistrationUnitFee) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, activationHeight,
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(nameRegistrationUnitFee));
	}

	private static byte[] buildCategoryPoliciesValueWithLevelThreshold(AccountRatingCategory updatedCategory,
			int updatedLevel, long updatedThreshold) {
		AccountTrustCategoryPoliciesData categoryPolicies = replaceCategoryPolicyValues(updatedCategory, updatedLevel,
				updatedThreshold, null);
		return AccountTrustCategoryPolicyCodec.encode(categoryPolicies,
				BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount());
	}

	private static byte[] buildCategoryPoliciesValueWithSuspiciousCap(AccountRatingCategory updatedCategory,
			long updatedSuspiciousCap, int validationSuspiciousMinRaterCount) {
		AccountTrustCategoryPoliciesData categoryPolicies = replaceCategoryPolicyValues(updatedCategory, null, null,
				updatedSuspiciousCap);
		return AccountTrustCategoryPolicyCodec.encode(categoryPolicies, validationSuspiciousMinRaterCount);
	}

	private static AccountTrustCategoryPoliciesData replaceCategoryPolicyValues(AccountRatingCategory updatedCategory,
			Integer updatedLevel, Long updatedThreshold, Long updatedSuspiciousCap) {
		AccountTrustCategoryPoliciesData categoryPolicies = AccountTrustCategoryPolicyCodec.decode(
				BlockChain.getInstance().getAccountTrustCategoryPoliciesValue());

		ArrayList<AccountTrustCategoryPoliciesData.CategoryPolicy> updatedPolicies = new ArrayList<>();
		for (AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy : categoryPolicies.getCategoryPolicies()) {
			ArrayList<AccountTrustCategoryPoliciesData.LevelPolicy> updatedLevels = new ArrayList<>();
			for (AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy : categoryPolicy.getLevels()) {
				long threshold = categoryPolicy.getCategory() == updatedCategory
						&& updatedLevel != null && levelPolicy.getLevel() == updatedLevel
						? updatedThreshold
						: levelPolicy.getThreshold();
				updatedLevels.add(new AccountTrustCategoryPoliciesData.LevelPolicy(levelPolicy.getLevel(), threshold,
						levelPolicy.getLevelScoreCap()));
			}

			long suspiciousLevelScoreCap = categoryPolicy.getCategory() == updatedCategory
					&& updatedSuspiciousCap != null
					? updatedSuspiciousCap
					: categoryPolicy.getSuspiciousLevelScoreCap();
			updatedPolicies.add(new AccountTrustCategoryPoliciesData.CategoryPolicy(categoryPolicy.getCategory(),
					updatedLevels, categoryPolicy.getSuspiciousThreshold(), suspiciousLevelScoreCap));
		}

		return new AccountTrustCategoryPoliciesData(updatedPolicies);
	}

	private static PaymentTransaction buildPaymentTransaction(Repository repository, PrivateKeyAccount sender, long fee)
			throws DataException {
		PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
		BaseTransactionData baseTransactionData = new BaseTransactionData(System.currentTimeMillis(),
				Group.NO_GROUP, sender.getPublicKey(), fee, null);
		PaymentTransactionData paymentTransactionData = new PaymentTransactionData(baseTransactionData,
				recipient.getAddress(), 1L);

		return new PaymentTransaction(repository, paymentTransactionData);
	}

	private static RegisterNameTransaction buildRegisterNameTransaction(Repository repository, PrivateKeyAccount registrant,
			long fee, String name) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(System.currentTimeMillis(),
				Group.NO_GROUP, registrant.getPublicKey(), fee, null);
		RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(baseTransactionData,
				name, "test data");

		return new RegisterNameTransaction(repository, registerNameTransactionData);
	}

	private static RateAccountTransactionData buildRateAccountTransactionData(PrivateKeyAccount rater,
			PrivateKeyAccount target, AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private static AccountTrustSnapshotData getSubjectTrustSnapshot(Repository repository, PrivateKeyAccount account)
			throws DataException {
		return repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(account.getAddress(), AccountRatingCategory.SUBJECT);
	}

	private static AccountTrustDerivationData findDerivation(List<AccountTrustDerivationData> derivations,
			PrivateKeyAccount account) {
		for (AccountTrustDerivationData derivation : derivations)
			if (derivation.getAccountAddress().equals(account.getAddress()))
				return derivation;

		throw new AssertionError("Missing trust derivation for " + account.getAddress());
	}

	private static void assertTrustParameterActivationRefreshesTrustSnapshots(Repository repository,
			PrivateKeyAccount alice, int activationHeight, ChainParameterUpdateTransactionData transactionData)
			throws DataException {
		TransactionUtils.signAndMint(repository, transactionData, alice);
		approveAndSettle(repository, transactionData);

		BlockUtils.mintBlocks(repository, activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight());

		AccountTrustSnapshotData beforeActivationSnapshot = getSubjectTrustSnapshot(repository, alice);
		assertNotNull(beforeActivationSnapshot);

		BlockUtils.mintBlock(repository);
		assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

		AccountTrustSnapshotData activationSnapshot = getSubjectTrustSnapshot(repository, alice);
		assertEquals(activationHeight, activationSnapshot.getSnapshotHeight());

		BlockUtils.orphanLastBlock(repository);

		AccountTrustSnapshotData orphanedSnapshot = getSubjectTrustSnapshot(repository, alice);
		assertEquals(activationHeight - 1, orphanedSnapshot.getSnapshotHeight());
	}

	private static void approveAndSettle(Repository repository, ChainParameterUpdateTransactionData transactionData) throws DataException {
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
	}

	private static int getApprovalSettlementBlockCount(Repository repository) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private static int getActivationHeightSafelyAfterApproval(Repository repository, int extraBlocks) throws DataException {
		return repository.getBlockRepository().getBlockchainHeight()
				+ getApprovalSettlementBlockCount(repository)
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ extraBlocks;
	}

	private static void assertRewardShareBinsMatchWeights(Repository repository, int activationHeight, int[] weights)
			throws DataException {
		long totalWeight = 0;
		int lastPositiveWeightIndex = -1;
		for (int i = 0; i < weights.length; ++i) {
			if (weights[i] > 0)
				lastPositiveWeightIndex = i;

			totalWeight += weights[i];
		}

		long remainingShare = Amounts.MULTIPLIER;
		for (int i = 0; i < weights.length; ++i) {
			AccountLevelShareBin shareBin = BlockChain.getInstance().getAccountLevelShareBins(repository, activationHeight).get(i);
			assertEquals(i + 1, shareBin.id);
			assertEquals(1, shareBin.levels.size());
			assertEquals(Integer.valueOf(i + 1), shareBin.levels.get(0));

			long expectedShare;
			if (weights[i] == 0) {
				expectedShare = 0L;
			} else if (i == lastPositiveWeightIndex) {
				expectedShare = remainingShare;
			} else {
				expectedShare = Amounts.scaledDivide(weights[i], totalWeight);
				remainingShare -= expectedShare;
			}

			assertEquals(expectedShare, shareBin.share);
		}
	}
}
