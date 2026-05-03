package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.UpdateGroupTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.*;

public class UpdateGroupTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testUpdateGroupRenameAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "alpha-group", true, ApprovalThreshold.ONE, 10, 40);

			UpdateGroupTransactionData updateData = new UpdateGroupTransactionData(TestTransaction.generateBase(alice), groupId, "beta-group",
					"updated test group", false, ApprovalThreshold.PCT40, 20, 60);
			TransactionUtils.signAndMint(repository, updateData, alice);

			assertFalse(repository.getGroupRepository().groupExists("alpha-group"));
			GroupData groupData = repository.getGroupRepository().fromGroupName("beta-group");
			assertNotNull(groupData);
			assertEquals(groupId, groupData.getGroupId().intValue());

			BlockUtils.orphanLastBlock(repository);

			groupData = repository.getGroupRepository().fromGroupName("alpha-group");
			assertNotNull(groupData);
			assertEquals(groupId, groupData.getGroupId().intValue());
			assertNull(repository.getGroupRepository().fromGroupName("beta-group"));
		}
	}

	@Test
	public void testUpdateGroupDuplicateReducedNameInvalid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "alpha-group", true, ApprovalThreshold.ONE, 10, 40);
			GroupUtils.createGroup(repository, "alice", "beta-group", true, ApprovalThreshold.ONE, 10, 40);

			UpdateGroupTransactionData updateData = new UpdateGroupTransactionData(TestTransaction.generateBase(alice), groupId, "beta-group",
					"updated test group", false, ApprovalThreshold.PCT40, 20, 60);
			ValidationResult result = TransactionUtils.signAndImport(repository, updateData, alice);
			assertEquals(ValidationResult.GROUP_ALREADY_EXISTS, result);
		}
	}

	@Test
	public void testUpdateGroupCaseOnlyRenameAllowed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int groupId = GroupUtils.createGroup(repository, "alice", "alpha-group", true, ApprovalThreshold.ONE, 10, 40);

			UpdateGroupTransactionData updateData = new UpdateGroupTransactionData(TestTransaction.generateBase(alice), groupId, "Alpha-Group",
					"updated test group", false, ApprovalThreshold.PCT40, 20, 60);
			TransactionUtils.signAndMint(repository, updateData, alice);

			GroupData groupData = repository.getGroupRepository().fromGroupName("Alpha-Group");
			assertNotNull(groupData);
			assertEquals(groupId, groupData.getGroupId().intValue());
		}
	}

}
