package org.qortium.test.assets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.asset.Order;
import org.qortium.data.asset.OrderData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CreateAssetOrderTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.Transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CreateAssetOrderValidationTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testExploitOrderValidatesBeforeActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = buildCreateOrderTransaction(repository, "alice", Asset.NATIVE, AssetUtils.testAssetId,
					100000001L, Long.MAX_VALUE);

			assertEquals("Pre-activation validation should preserve historical behavior",
					Transaction.ValidationResult.OK, transaction.isValid());
		}
	}

	@Test
	public void testExploitOrderRejectedAfterActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			mintToActivation(repository);

			long initialNativeBalance = AccountUtils.getBalance(repository, "alice", Asset.NATIVE);
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			TransactionData transactionData = buildCreateOrderTransactionData(repository, alice, Asset.NATIVE, AssetUtils.testAssetId,
					100000001L, Long.MAX_VALUE);

			Transaction transaction = Transaction.fromData(repository, transactionData);
			assertEquals("Overflowing asset order should be invalid after activation",
					Transaction.ValidationResult.INVALID_AMOUNT, transaction.isValid());

			Transaction.ValidationResult importResult = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertTrue("Overflowing asset order should not import as unconfirmed",
					importResult != Transaction.ValidationResult.OK);
			assertEquals("Rejected order should not change native balance",
					initialNativeBalance, AccountUtils.getBalance(repository, "alice", Asset.NATIVE));
		}
	}

	@Test
	public void testAmountAndPriceBoundsAfterActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			mintToActivation(repository);

			assertCreateOrderValidation(repository, Asset.MAX_QUANTITY + 1, 1L, Transaction.ValidationResult.INVALID_AMOUNT);
			assertCreateOrderValidation(repository, 1L, Asset.MAX_QUANTITY + 1, Transaction.ValidationResult.INVALID_AMOUNT);
			assertCreateOrderValidation(repository, Asset.MAX_QUANTITY, 1L, Transaction.ValidationResult.OK);
		}
	}

	@Test
	public void testRoundedCommitmentOverflowRejectedAfterActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			mintToActivation(repository);

			assertCreateOrderValidation(repository, 922337202763140378L, 1000000001L,
					Transaction.ValidationResult.INVALID_AMOUNT);
		}
	}

	@Test
	public void testRoundedCommitmentDivisibilityRejectedAfterActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			mintToActivation(repository);

			long indivisibleAssetId = AssetUtils.issueAsset(repository, "alice", "INDIV-ORDER", 1_00000000L, false);
			long divisibleAssetId = AssetUtils.issueAsset(repository, "alice", "DIV-ORDER", 1_00000000L, true);

			assertCreateOrderValidation(repository, indivisibleAssetId, divisibleAssetId, 1L, 1L,
					Transaction.ValidationResult.INVALID_AMOUNT);
		}
	}

	@Test
	public void testProcessOverflowFailsClosedAfterActivation() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			mintToActivation(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long initialNativeBalance = AccountUtils.getBalance(repository, "alice", Asset.NATIVE);

			// Craft an order whose rounded have-asset commitment (amount * price / 1e8) overflows a signed long.
			// isValid() already rejects this after activation, so we drive Order.process() directly to prove the
			// processing path fails closed (throws) rather than wrapping a debit into a credit (the C-02 mint).
			OrderData orderData = new OrderData(new byte[64], alice.getPublicKey(), Asset.NATIVE, AssetUtils.testAssetId,
					100000001L, Long.MAX_VALUE, System.currentTimeMillis());

			try {
				new Order(repository, orderData).process();
				fail("Order.process() should throw when the have-asset commitment overflows a signed long");
			} catch (DataException expected) {
				// Expected: fail closed instead of minting.
			}

			assertEquals("Failed order processing must not change native balance",
					initialNativeBalance, AccountUtils.getBalance(repository, "alice", Asset.NATIVE));
		}
	}

	private static void mintToActivation(Repository repository) throws DataException {
		while (repository.getBlockRepository().getBlockchainHeight() + 1 < 10)
			BlockUtils.mintBlock(repository);
	}

	private static void assertCreateOrderValidation(Repository repository, long amount, long price,
			Transaction.ValidationResult expectedResult) throws DataException {
		Transaction transaction = buildCreateOrderTransaction(repository, "alice", Asset.NATIVE, AssetUtils.testAssetId, amount, price);

		assertEquals("Unexpected CREATE_ASSET_ORDER validation result", expectedResult, transaction.isValid());
	}

	private static void assertCreateOrderValidation(Repository repository, long haveAssetId, long wantAssetId, long amount,
			long price, Transaction.ValidationResult expectedResult) throws DataException {
		Transaction transaction = buildCreateOrderTransaction(repository, "alice", haveAssetId, wantAssetId, amount, price);

		assertEquals("Unexpected CREATE_ASSET_ORDER validation result", expectedResult, transaction.isValid());
	}

	private static Transaction buildCreateOrderTransaction(Repository repository, String accountName, long haveAssetId,
			long wantAssetId, long amount, long price) throws DataException {
		PrivateKeyAccount account = Common.getTestAccount(repository, accountName);
		return Transaction.fromData(repository, buildCreateOrderTransactionData(repository, account, haveAssetId, wantAssetId, amount, price));
	}

	private static TransactionData buildCreateOrderTransactionData(Repository repository, PrivateKeyAccount account,
			long haveAssetId, long wantAssetId, long amount, long price) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, AssetUtils.txGroupId, account.getPublicKey(),
				AssetUtils.fee, null);

		return new CreateAssetOrderTransactionData(baseTransactionData, haveAssetId, wantAssetId, amount, price);
	}

}
