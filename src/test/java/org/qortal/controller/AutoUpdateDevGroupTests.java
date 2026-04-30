package org.qortal.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.transaction.Transaction.TransactionType;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AutoUpdateDevGroupTests extends Common {

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

	private static int createDevGroup(Repository repository, String groupName) throws DataException {
		return GroupUtils.createGroup(repository, "alice", groupName, true, ApprovalThreshold.ONE, MIN_BLOCK_DELAY, MAX_BLOCK_DELAY);
	}

	private static byte[] createApprovedAutoUpdateTransaction(Repository repository, String creatorName, int groupId) throws DataException {
		PrivateKeyAccount creator = Common.getTestAccount(repository, creatorName);
		BaseTransactionData baseTransactionData = TestTransaction.generateBase(creator, groupId);
		int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
		byte[] data = new byte[60];

		ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData, version,
				Service.AUTO_UPDATE.value, 0, data.length, null, null, ArbitraryTransactionData.Method.PUT,
				null, ArbitraryTransactionData.Compression.NONE, data, ArbitraryTransactionData.DataType.RAW_DATA,
				null, Collections.emptyList());

		TransactionUtils.signAndMint(repository, transactionData, creator);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, MIN_BLOCK_DELAY);

		assertEquals(ApprovalStatus.APPROVED, repository.getTransactionRepository()
				.fromSignature(transactionData.getSignature()).getApprovalStatus());

		return transactionData.getSignature();
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
