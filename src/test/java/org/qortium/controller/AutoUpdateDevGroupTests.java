package org.qortium.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transaction.Transaction.TransactionType;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AutoUpdateDevGroupTests extends Common {

	private static final int DEV_GROUP_ID = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
	private static final int MIN_BLOCK_DELAY = 1;
	private static final int MAX_BLOCK_DELAY = 10;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testLatestAutoUpdateTransactionUsesConfiguredDevGroups() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int devGroupId = createDevGroup(repository, "auto-dev-group");
			int otherGroupId = createDevGroup(repository, "other-dev-group");

			GroupUtils.joinGroup(repository, "bob", devGroupId);
			GroupUtils.joinGroup(repository, "bob", otherGroupId);

			byte[] otherGroupSignature = createApprovedAutoUpdateTransaction(repository, "bob", otherGroupId);

			assertNull(repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, Collections.emptyList(), Service.AUTO_UPDATE.value));
			assertNull(repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(devGroupId), Service.AUTO_UPDATE.value));

			waitUntilNextMillisecond();
			byte[] devGroupSignature = createApprovedAutoUpdateTransaction(repository, "bob", devGroupId);

			assertArrayEquals(devGroupSignature, repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(devGroupId), Service.AUTO_UPDATE.value));
			assertArrayEquals(devGroupSignature, repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(devGroupId, otherGroupId), Service.AUTO_UPDATE.value));
			assertArrayEquals(otherGroupSignature, repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(otherGroupId), Service.AUTO_UPDATE.value));
		}
	}

	@Test
	public void testNullOwnedDevGroupAdminSubmittedAutoUpdateRequiresApproval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			GroupData devGroupData = repository.getGroupRepository().fromGroupId(DEV_GROUP_ID);

			assertEquals(Group.NULL_OWNER_ADDRESS, devGroupData.getOwner());
			TransactionData transactionData = createAutoUpdateTransaction(repository, alice, DEV_GROUP_ID);
			assertEquals(ApprovalStatus.PENDING, transactionData.getApprovalStatus());

			assertNull(repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(DEV_GROUP_ID), Service.AUTO_UPDATE.value));

			approveAndSettle(repository, List.of(alice), transactionData);

			assertEquals(ApprovalStatus.APPROVED, repository.getTransactionRepository()
					.fromSignature(transactionData.getSignature()).getApprovalStatus());
			assertArrayEquals(transactionData.getSignature(), repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(DEV_GROUP_ID), Service.AUTO_UPDATE.value));
		}
	}

	@Test
	public void testNullOwnedDevGroupFallsBackToMemberApprovalWithoutUsableAdmins() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");

			ensureDevGroupMember(repository, bob);
			ensureDevGroupMember(repository, chloe);
			repository.getGroupRepository().deleteAdmin(DEV_GROUP_ID, alice.getAddress());
			repository.saveChanges();

			assertEquals(0, repository.getGroupRepository().countUsableGroupAdmins(DEV_GROUP_ID));

			TransactionData transactionData = createAutoUpdateTransaction(repository, bob, DEV_GROUP_ID);
			assertEquals(ApprovalStatus.PENDING, repository.getTransactionRepository()
					.fromSignature(transactionData.getSignature()).getApprovalStatus());

			approveAndSettle(repository, List.of(bob, chloe), transactionData);

			assertEquals(ApprovalStatus.APPROVED, repository.getTransactionRepository()
					.fromSignature(transactionData.getSignature()).getApprovalStatus());
			assertArrayEquals(transactionData.getSignature(), repository.getTransactionRepository().getLatestAutoUpdateTransaction(
					TransactionType.ARBITRARY, List.of(DEV_GROUP_ID), Service.AUTO_UPDATE.value));
		}
	}

	private static int createDevGroup(Repository repository, String groupName) throws DataException {
		return GroupUtils.createGroup(repository, "alice", groupName, true, ApprovalThreshold.ONE, MIN_BLOCK_DELAY, MAX_BLOCK_DELAY);
	}

	private static byte[] createApprovedAutoUpdateTransaction(Repository repository, String creatorName, int groupId) throws DataException {
		PrivateKeyAccount creator = Common.getTestAccount(repository, creatorName);
		TransactionData transactionData = createAutoUpdateTransaction(repository, creator, groupId);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, MIN_BLOCK_DELAY);

		assertEquals(ApprovalStatus.APPROVED, repository.getTransactionRepository()
				.fromSignature(transactionData.getSignature()).getApprovalStatus());

		return transactionData.getSignature();
	}

	private static TransactionData createAutoUpdateTransaction(Repository repository, PrivateKeyAccount creator, int groupId) throws DataException {
		BaseTransactionData baseTransactionData = TestTransaction.generateBase(creator, groupId);
		int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
		byte[] data = new byte[60];

		ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData, version,
				Service.AUTO_UPDATE.value, 0, data.length, null, null, ArbitraryTransactionData.Method.PUT,
				null, ArbitraryTransactionData.Compression.NONE, data, ArbitraryTransactionData.DataType.RAW_DATA,
				null, Collections.emptyList());

		TransactionUtils.signAndMint(repository, transactionData, creator);
		return repository.getTransactionRepository().fromSignature(transactionData.getSignature());
	}

	private static void approveAndSettle(Repository repository, List<PrivateKeyAccount> signers, TransactionData transactionData) throws DataException {
		for (PrivateKeyAccount signer : signers) {
			BaseTransactionData baseTransactionData = TestTransaction.generateBase(signer, Group.NO_GROUP);
			GroupApprovalTransactionData approvalTransactionData = new GroupApprovalTransactionData(baseTransactionData, transactionData.getSignature(), true);
			TransactionUtils.signAndMint(repository, approvalTransactionData, signer);
		}

		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository, transactionData));
	}

	private static int getApprovalSettlementBlockCount(Repository repository, TransactionData transactionData) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(transactionData.getTxGroupId());
		if (groupData == null)
			return 2;

		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private static void ensureDevGroupMember(Repository repository, PrivateKeyAccount account) throws DataException {
		if (repository.getGroupRepository().memberExists(DEV_GROUP_ID, account.getAddress()))
			return;

		GroupData groupData = repository.getGroupRepository().fromGroupId(DEV_GROUP_ID);
		repository.getGroupRepository().save(new GroupMemberData(DEV_GROUP_ID, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
	}

	private static void waitUntilNextMillisecond() {
		long now = System.currentTimeMillis();
		while (System.currentTimeMillis() == now) {
			try {
				Thread.sleep(1L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
