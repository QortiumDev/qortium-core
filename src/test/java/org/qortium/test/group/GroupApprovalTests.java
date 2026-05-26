package org.qortium.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;

import static org.junit.Assert.*;

public class GroupApprovalTests extends Common {

	private static final long amount = 5000L * Amounts.MULTIPLIER;
	private static final long fee = 1L * Amounts.MULTIPLIER;
	private static final int minBlockDelay = 5;
	private static final int maxBlockDelay = 10;


	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	/** Check that a transaction type that doesn't need approval doesn't accept txGroupId apart from NO_GROUP */
	public void testNonApprovalTxGroupId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = buildPaymentTransaction(repository, "alice", "bob", amount, Group.NO_GROUP);
			assertEquals(ValidationResult.OK, transaction.isValidUnconfirmed());

			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.NONE, 0, 10);

			transaction = buildPaymentTransaction(repository, "alice", "bob", amount, groupId);
			assertEquals(ValidationResult.INVALID_TX_GROUP_ID, transaction.isValidUnconfirmed());
		}
	}

	@Test
	/** Check that a transaction type that does need approval, auto-approves if created by group admin */
	public void testAutoApprove() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			Transaction transaction = buildIssueAssetTransaction(repository, "alice", groupId);
			TransactionUtils.signAndMint(repository, transaction.getTransactionData(), aliceAccount);

			// Confirm transaction doesn't need approval
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, transaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.NOT_REQUIRED, approvalStatus);
		}
	}

	@Test
	/** Check that a transaction, that requires approval, updates fees without changing legacy references. */
	public void testApprovalPendingFees() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			GroupUtils.joinGroup(repository, "bob", groupId);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			long bobOriginalBalance = bobAccount.getConfirmedBalance(Asset.NATIVE);

			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Confirm transaction needs approval, and hasn't been approved
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Bob's balance should have the fee removed, even though the transaction itself hasn't been approved yet
			long bobPostAssetBalance = bobAccount.getConfirmedBalance(Asset.NATIVE);
			assertEquals("approval-pending transaction creator's balance incorrect", bobOriginalBalance - fee, bobPostAssetBalance);

			// Transaction fee should still be recorded in the block even while the transaction awaits approval
			long blockFees = repository.getBlockRepository().getLastBlock().getTotalFees();
			assertEquals("block total fees incorrect", fee, blockFees);

			// Have Bob do a non-approval transaction to confirm fee/orphan handling does not rely on references
			Transaction bobPaymentTransaction = buildPaymentTransaction(repository, "bob", "chloe", amount, Group.NO_GROUP);
			TransactionUtils.signAndMint(repository, bobPaymentTransaction.getTransactionData(), bobAccount);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), true);

			// Now mint a few blocks so transaction is approved
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm transaction now approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.APPROVED, approvalStatus);

			// Ok, now unwind/orphan all the above to double-check

			// Orphan blocks that decided transaction approval
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Orphan block containing Alice's group-approval transaction
			BlockUtils.orphanLastBlock(repository);

			// Orphan block containing Bob's non-approval payment transaction
			BlockUtils.orphanLastBlock(repository);

			// Orphan block containing Bob's issue-asset approval-needed transaction
			BlockUtils.orphanLastBlock(repository);

			// Also check Bob's balance is back to original value
			long bobBalance = bobAccount.getConfirmedBalance(Asset.NATIVE);
			assertEquals("reverted balance doesn't match original", bobOriginalBalance, bobBalance);
		}
	}

	@Test
	/** Test generic approval. */
	public void testApproval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			GroupUtils.joinGroup(repository, "bob", groupId);

			// Bob's issue-asset transaction needs group-approval
			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Confirm transaction needs approval, and hasn't been approved
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction has no group-approval decision height
			Integer approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), true);

			// Now mint a few blocks so transaction is approved
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm transaction now approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.APPROVED, approvalStatus);

			// Confirm transaction now has a group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNotNull("group-approval decision height should not be null", approvalHeight);

			// Orphan blocks that decided approval
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Orphan block containing Alice's group-approval transaction
			BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);
		}
	}

	@Test
	/** Test that rejection votes do not finalize transactions. */
	public void testRejectionVoteExpiresWithoutApproval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			GroupUtils.joinGroup(repository, "bob", groupId);

			// Bob's issue-asset transaction needs group-approval
			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Confirm transaction needs approval, and hasn't been approved
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction has no group-approval decision height
			Integer approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Have Alice reject Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), false);

			// Now mint a few blocks so the transaction reaches the minimum approval delay
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm rejection votes alone do not finalize the transaction
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction still has no group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Now mint the remaining blocks so the pending transaction expires
			for (int blockCount = 0; blockCount < maxBlockDelay - minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm transaction now expired
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.EXPIRED, approvalStatus);

			// Confirm transaction now has a group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNotNull("group-approval decision height should not be null", approvalHeight);

			// Orphan blocks that decided expiry
			for (int blockCount = 0; blockCount < maxBlockDelay - minBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer expired
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Orphan blocks that reached the minimum approval delay
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Orphan block containing Alice's rejection vote
			BlockUtils.orphanLastBlock(repository);

			// Confirm transaction is still pending
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);
		}
	}

	@Test
	/** Test that a rejection vote can be replaced by an approval vote before expiry. */
	public void testRejectionVoteCanBeChangedToApproval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			GroupUtils.joinGroup(repository, "bob", groupId);

			// Bob's issue-asset transaction needs group-approval
			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Have Alice reject Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), false);

			// Now mint a few blocks so the transaction reaches the minimum approval delay
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm rejection votes alone do not finalize the transaction
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			Integer approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Have Alice replace her rejection vote with an approval vote
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), true);

			// Mint the decision block after the replacement approval vote
			BlockUtils.mintBlock(repository);

			// Confirm transaction now approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.APPROVED, approvalStatus);

			// Confirm transaction now has a group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNotNull("group-approval decision height should not be null", approvalHeight);
		}
	}

	@Test
	/** Test generic expiry. */
	public void testExpiry() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			GroupUtils.joinGroup(repository, "bob", groupId);

			// Bob's issue-asset transaction needs group-approval
			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Confirm transaction needs approval, and hasn't been approved
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction has no group-approval decision height
			Integer approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Now mint a few blocks so group-approval for transaction expires
			for (int blockCount = 0; blockCount <= maxBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm transaction now expired
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.EXPIRED, approvalStatus);

			// Confirm transaction now has a group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNotNull("group-approval decision height should not be null", approvalHeight);

			// Orphan blocks that decided expiry
			for (int blockCount = 0; blockCount <= maxBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer expired
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);
		}
	}

	@Test
	/** Test generic invalid. */
	public void testInvalid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, minBlockDelay, maxBlockDelay);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			GroupUtils.joinGroup(repository, "bob", groupId);

			// Bob's issue-asset transaction needs group-approval
			Transaction bobAssetTransaction = buildIssueAssetTransaction(repository, "bob", groupId);
			TransactionUtils.signAndMint(repository, bobAssetTransaction.getTransactionData(), bobAccount);

			// Confirm transaction needs approval, and hasn't been approved
			ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction has no group-approval decision height
			Integer approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Have Alice approve Bob's approval-needed transaction
			GroupUtils.approveTransaction(repository, "alice", bobAssetTransaction.getTransactionData().getSignature(), true);

			// But wait! Alice issues an asset with the same name before Bob's asset is issued!
			// This transaction will be auto-approved as Alice is the group owner (and admin)
			Transaction aliceAssetTransaction = buildIssueAssetTransaction(repository, "alice", groupId);
			TransactionUtils.signAndMint(repository, aliceAssetTransaction.getTransactionData(), aliceAccount);

			// Confirm Alice's transaction auto-approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, aliceAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.NOT_REQUIRED, approvalStatus);

			// Now mint a few blocks so transaction is approved
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.mintBlock(repository);

			// Confirm Bob's transaction now invalid
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.INVALID, approvalStatus);

			// Confirm transaction now has a group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNotNull("group-approval decision height should not be null", approvalHeight);

			// Orphan blocks that decided group-approval
			for (int blockCount = 0; blockCount < minBlockDelay; ++blockCount)
				BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer invalid
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);

			// Orphan block containing Alice's issue-asset transaction
			BlockUtils.orphanLastBlock(repository);

			// Orphan block containing Alice's group-approval transaction
			BlockUtils.orphanLastBlock(repository);

			// Confirm transaction no longer approved
			approvalStatus = GroupUtils.getApprovalStatus(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertEquals("incorrect transaction approval status", ApprovalStatus.PENDING, approvalStatus);

			// Confirm transaction no longer has group-approval decision height
			approvalHeight = GroupUtils.getApprovalHeight(repository, bobAssetTransaction.getTransactionData().getSignature());
			assertNull("group-approval decision height should be null", approvalHeight);
		}
	}

	private Transaction buildPaymentTransaction(Repository repository, String sender, String recipient, long amount, int txGroupId) throws DataException {
		PrivateKeyAccount sendingAccount = Common.getTestAccount(repository, sender);
		PrivateKeyAccount recipientAccount = Common.getTestAccount(repository, recipient);

		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, sendingAccount.getPublicKey(), fee, null);
		PaymentTransactionData transactionData = new PaymentTransactionData(baseTransactionData, recipientAccount.getAddress(), amount);

		return Transaction.fromData(repository, transactionData);
	}

	private Transaction buildIssueAssetTransaction(Repository repository, String testAccountName, int txGroupId) throws DataException {
		PrivateKeyAccount account = Common.getTestAccount(repository, testAccountName);

		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, account.getPublicKey(), fee, null);
		TransactionData transactionData = new IssueAssetTransactionData(baseTransactionData, "test asset", "test asset desc", 1000L, true, "{}", false);

		return Transaction.fromData(repository, transactionData);
	}

}
