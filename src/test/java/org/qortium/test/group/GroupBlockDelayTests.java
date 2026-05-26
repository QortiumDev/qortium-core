package org.qortium.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.test.common.BlockUtils;
import org.qortium.transaction.CreateGroupTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transaction.UpdateGroupTransaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class GroupBlockDelayTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateGroupBlockDelayValues() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			// Check we're starting with something valid
			Transaction transaction = buildCreateGroupWithDelays(repository, alice, 10, 40);
			assertEquals(ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, -1, 40);
			assertNotSame("Negative minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 10, -1);
			assertNotSame("Negative maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 10, 0);
			assertNotSame("Zero maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 40, 10);
			assertNotSame("Maximum block delay smaller than minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildCreateGroupWithDelays(repository, alice, 40, 40);
			assertEquals("Maximum block delay same as minimum block delay should be OK", ValidationResult.OK, transaction.isValid());
		}
	}

	private CreateGroupTransaction buildCreateGroupWithDelays(Repository repository, PrivateKeyAccount account, int minimumBlockDelay, int maximumBlockDelay) throws DataException {
		String groupName = "test group";
		String description = "random test group";
		final boolean isOpen = false;
		ApprovalThreshold approvalThreshold = ApprovalThreshold.PCT40;

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(account), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
		return new CreateGroupTransaction(repository, transactionData);
	}

	@Test
	public void testUpdateGroupBlockDelayValues() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, 10, 40);

			// Check we're starting with something valid
			Transaction transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, 40);
			assertEquals(ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, -1, 40);
			assertNotSame("Negative minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, -1);
			assertNotSame("Negative maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 10, 0);
			assertNotSame("Zero maximum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 40, 10);
			assertNotSame("Maximum block delay smaller than minimum block delay should be invalid", ValidationResult.OK, transaction.isValid());

			transaction = buildUpdateGroupWithDelays(repository, alice, groupId, 40, 40);
			assertEquals("Maximum block delay same as minimum block delay should be OK", ValidationResult.OK, transaction.isValid());
		}
	}

	private UpdateGroupTransaction buildUpdateGroupWithDelays(Repository repository, PrivateKeyAccount account, int groupId, int newMinimumBlockDelay, int newMaximumBlockDelay) throws DataException {
		String newDescription = "random test group";
		final boolean newIsOpen = false;
		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.PCT40;

		UpdateGroupTransactionData transactionData = new UpdateGroupTransactionData(TestTransaction.generateBase(account), groupId, newDescription, newIsOpen, newApprovalThreshold, newMinimumBlockDelay, newMaximumBlockDelay);
		return new UpdateGroupTransaction(repository, transactionData);
	}

	@Test
	public void testUpdateGroupBlockDelaysApplyAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "test", true, ApprovalThreshold.ONE, 10, 40);

			UpdateGroupTransactionData transactionData = new UpdateGroupTransactionData(TestTransaction.generateBase(alice), groupId,
					"updated test group", false, ApprovalThreshold.PCT40, 20, 60);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals(20, groupData.getMinimumBlockDelay());
			assertEquals(60, groupData.getMaximumBlockDelay());

			BlockUtils.orphanLastBlock(repository);

			groupData = repository.getGroupRepository().fromGroupId(groupId);
			assertEquals(10, groupData.getMinimumBlockDelay());
			assertEquals(40, groupData.getMaximumBlockDelay());
		}
	}

}
