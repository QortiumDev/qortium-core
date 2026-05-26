package org.qortium.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

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
