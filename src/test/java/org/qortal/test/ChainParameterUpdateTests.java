package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.block.ChainParameter;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

	private static ChainParameterUpdateTransactionData buildUnitFeeUpdate(Repository repository,
			PrivateKeyAccount updater, int txGroupId, int activationHeight, long unitFee) throws DataException {
		return new ChainParameterUpdateTransactionData(TestTransaction.generateBase(updater, txGroupId),
				ChainParameter.UNIT_FEE.id, activationHeight, ChainParameter.UNIT_FEE.encodeLongValue(unitFee));
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
}
