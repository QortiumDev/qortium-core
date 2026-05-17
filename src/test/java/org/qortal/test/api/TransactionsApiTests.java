package org.qortal.test.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.data.transaction.RewardShareTransactionData;
import org.qortal.data.transaction.TransactionConfirmationTimingData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransactionsApiTests extends ApiCommon {

	private TransactionsResource transactionsResource;

	@Before
	public void buildResource() {
		this.transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.transactionsResource);
	}

	@Test
	public void testGetPendingTransactions() {
		for (Integer txGroupId : Arrays.asList(null, 0, 1)) {
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, null, null, null));
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, 1, 1, true));
		}
	}

	@Test
	public void testGetUnconfirmedTransactions() {
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, null, null, null));
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, 1, 1, true));
	}

	@Test
	public void testSearchTransactions() {
		List<TransactionType> txTypes = Arrays.asList(TransactionType.PAYMENT, TransactionType.ISSUE_ASSET);

		for (Integer startBlock : Arrays.asList(null, 1))
			for (Integer blockLimit : Arrays.asList(null, 1))
				for (Integer txGroupId : Arrays.asList(null, 1))
					for (String address : Arrays.asList(null, aliceAddress))
						for (ConfirmationStatus confirmationStatus : ConfirmationStatus.values()) {
							if (confirmationStatus != ConfirmationStatus.CONFIRMED) {
								startBlock = null;
								blockLimit = null;
							}

							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, null, null, null));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, 1, 1, true));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, null, address, confirmationStatus, 1, 1, true));
						}
	}

	@Test
	public void testConfirmationTimingForOrdinaryTransaction()
			throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PaymentTransactionData paymentTransactionData = new PaymentTransactionData(
					TestTransaction.generateBase(alice), bob.getAddress(), 1L);

			TransactionConfirmationTimingData timing = this.transactionsResource
					.getTransactionConfirmationTiming(rawTransaction(paymentTransactionData));

			assertEquals(TransactionType.PAYMENT, timing.getTransactionType());
			assertEquals(TransactionType.PAYMENT.value, timing.getTransactionTypeValue());
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), timing.getCurrentHeight());
			assertEquals(timing.getCurrentHeight() + 1, timing.getCandidateHeight());
			assertTrue(timing.isTransactionConfirmable());
			assertTrue(timing.isConfirmableAtCandidateHeight());
			assertNull(timing.getFirstConfirmableHeight());
			assertNull(timing.getConfirmationDelayBlocks());
			assertNull(timing.getDelayReason());
		}
	}

	@Test
	public void testConfirmationTimingForRateAccountWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			RateAccountTransactionData transactionData = new RateAccountTransactionData(
					TestTransaction.generateBase(alice), bob.getPublicKey(), 4);

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.RATE_ACCOUNT);
		}
	}

	@Test
	public void testConfirmationTimingForRewardShareWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			TransactionData transactionData = AccountUtils.createRewardShare(repository, chloe, chloe,
					-100, 1L * Amounts.MULTIPLIER);

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.REWARD_SHARE);
		}
	}

	@Test
	public void testConfirmationTimingForTransferPrivsWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.generateRandomSeedAccount(repository);
			BaseTransactionData baseTransactionData = new BaseTransactionData(TransactionUtils.nextTimestamp(repository),
					Group.NO_GROUP, alice.getPublicKey(), 1L * Amounts.MULTIPLIER, null);
			TransferPrivsTransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData,
					recipient.getAddress());

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.TRANSFER_PRIVS);
		}
	}

	private void assertProtectedWindowDelay(Repository repository, String rawTransaction, TransactionType transactionType)
			throws DataException {
		TransactionConfirmationTimingData timing = this.transactionsResource
				.getTransactionConfirmationTiming(rawTransaction);

		assertEquals(transactionType, timing.getTransactionType());
		assertEquals(transactionType.value, timing.getTransactionTypeValue());
		assertEquals(repository.getBlockRepository().getBlockchainHeight(), timing.getCurrentHeight());
		assertEquals(90, timing.getCandidateHeight());
		assertTrue(timing.isTransactionConfirmable());
		assertFalse(timing.isConfirmableAtCandidateHeight());
		assertEquals(Integer.valueOf(101), timing.getFirstConfirmableHeight());
		assertEquals(Integer.valueOf(11), timing.getConfirmationDelayBlocks());
		assertEquals("PROTECTED_ONLINE_ACCOUNT_WINDOW", timing.getDelayReason());
	}

	private static void useShortProtectedWindow() throws IllegalAccessException {
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);
	}

	private static void mintToHeight(Repository repository, int targetHeight) throws DataException {
		int blocksToMint = targetHeight - repository.getBlockRepository().getBlockchainHeight();
		if (blocksToMint > 0)
			BlockUtils.mintBlocks(repository, blocksToMint);
		assertEquals(targetHeight, repository.getBlockRepository().getBlockchainHeight());
	}

	private static String rawTransaction(TransactionData transactionData) throws TransformationException {
		return Base58.encode(TransactionTransformer.toBytes(transactionData));
	}

}
