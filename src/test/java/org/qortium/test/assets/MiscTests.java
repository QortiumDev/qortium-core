package org.qortium.test.assets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.asset.Asset;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortium.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.SellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateAssetTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;
import org.qortium.utils.Unicode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateAssetWithExistingName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			String assetName = "test-asset";
			String description = "description";
			long quantity = 12345678L;
			boolean isDivisible = true;
			String data = "{}";
			boolean isUnspendable = false;

			TransactionData transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice), assetName, description, quantity, isDivisible, data, isUnspendable);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String duplicateAssetName = "TEST-Ásset";
			transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice), duplicateAssetName, description, quantity, isDivisible, data, isUnspendable);

			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	@Test
	public void testCreateAssetRejectsIConfusableReducedName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			String description = "description";
			long quantity = 12345678L;
			boolean isDivisible = true;
			String data = "{}";
			boolean isUnspendable = false;

			AssetUtils.issueAsset(repository, "alice", "sample-label", quantity, isDivisible);

			TransactionData transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice),
					"sample-Iabel", description, quantity, isDivisible, data, isUnspendable);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.ASSET_ALREADY_EXISTS, result);
		}
	}

	@Test
	public void testCreateAssetRejectsTrailingVisualBlankName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			String assetName = "sample-space";
			String spoofedAssetName = assetName + Unicode.BRAILLE_PATTERN_BLANK;
			String description = "description";
			long quantity = 12345678L;
			boolean isDivisible = true;
			String data = "{}";
			boolean isUnspendable = false;

			TransactionData transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice),
					spoofedAssetName, description, quantity, isDivisible, data, isUnspendable);
			assertEquals(Unicode.sanitize(assetName), ((IssueAssetTransactionData) transactionData).getReducedAssetName());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.NAME_NOT_NORMALIZED, result);
		}
	}

	@Test
	public void testCreateAssetRejectsBidiControlName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			String assetName = "sample-name";
			String spoofedAssetName = "sam\u202eple-name";
			String description = "description";
			long quantity = 12345678L;
			boolean isDivisible = true;
			String data = "{}";
			boolean isUnspendable = false;

			TransactionData transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice),
					spoofedAssetName, description, quantity, isDivisible, data, isUnspendable);
			assertEquals(Unicode.sanitize(assetName), ((IssueAssetTransactionData) transactionData).getReducedAssetName());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.NAME_NOT_NORMALIZED, result);
		}
	}

	@Test
	public void testNonNativeAssetCannotHaveZeroQuantity() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			TransactionData transactionData = new IssueAssetTransactionData(TestTransaction.generateBase(alice),
					"zero-quantity-asset", "description", 0L, true, "{}", false);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.INVALID_QUANTITY, result);
		}
	}

	@Test
	public void testUpdateAssetName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			String assetName = "rename-asset";
			long assetId = AssetUtils.issueAsset(repository, "alice", assetName, 1000L, true);

			String newName = "renamed-asset";
			TransactionData transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, newName, "", "");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(newName, assetData.getName());
			assertEquals(newName, assetData.getReducedAssetName());
			assertEquals(assetId, repository.getAssetRepository().fromAssetName(newName).getAssetId().longValue());
			assertEquals(assetId, repository.getAssetRepository().fromReducedAssetName(newName).getAssetId().longValue());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(assetName, assetData.getName());
			assertEquals(assetName, assetData.getReducedAssetName());
			assertEquals(assetId, repository.getAssetRepository().fromAssetName(assetName).getAssetId().longValue());
		}
	}

	@Test
	public void testUpdateAssetNameDuplicateReducedName() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			AssetUtils.issueAsset(repository, "alice", "test-asset", 1000L, true);
			long assetId = AssetUtils.issueAsset(repository, "alice", "other-asset", 1000L, true);

			TransactionData transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, "TEST-Ásset", "", "");
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);

			assertEquals(ValidationResult.ASSET_ALREADY_EXISTS, result);
		}
	}

	@Test
	public void testUpdateAssetNameCaseOnly() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			long assetId = AssetUtils.issueAsset(repository, "alice", "Case-Asset", 1000L, true);

			String newName = "case-asset";
			TransactionData transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, newName, "", "");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(newName, assetData.getName());
			assertEquals("case-asset", assetData.getReducedAssetName());
		}
	}

	@Test
	public void testUpdateAssetNameRevertsThroughNonRenameUpdate() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			String originalName = "chain-asset";
			long assetId = AssetUtils.issueAsset(repository, "alice", originalName, 1000L, true);

			String middleName = "middle-asset";
			TransactionData transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, middleName, "", "");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, "", "updated description", "");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			String newestName = "newest-asset";
			transactionData = new UpdateAssetTransactionData(TestTransaction.generateBase(alice),
					assetId, newestName, "", "");
			TransactionUtils.signAndMint(repository, transactionData, alice);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(newestName, assetData.getName());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(middleName, assetData.getName());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(middleName, assetData.getName());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(originalName, assetData.getName());
		}
	}

	@Test
	public void testSellAndBuyAssetOwnership() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			long assetId = AssetUtils.issueAsset(repository, "alice", "owned-asset", 1000L, true);
			long price = 5L * Amounts.MULTIPLIER;

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, price);
			TransactionUtils.signAndMint(repository, sellData, alice);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(true, assetData.isOwnerForSale());
			assertEquals(Long.valueOf(price), assetData.getOwnerSalePrice());
			assertEquals(null, assetData.getOwnerSaleRecipient());

			TransactionData buyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), assetId, price, alice.getAddress());
			TransactionUtils.signAndMint(repository, buyData, bob);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(bob.getAddress(), assetData.getOwner());
			assertEquals(false, assetData.isOwnerForSale());
			assertEquals(null, assetData.getOwnerSalePrice());
			assertEquals(null, assetData.getOwnerSaleRecipient());
		}
	}

	@Test
	public void testDirectAssetOwnershipSaleOnlyRecipientCanBuy() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			long assetId = AssetUtils.issueAsset(repository, "alice", "direct-asset", 1000L, true);
			long price = 5L * Amounts.MULTIPLIER;

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, price, bob.getAddress());
			TransactionUtils.signAndMint(repository, sellData, alice);

			TransactionData chloeBuyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(chloe), assetId, price, alice.getAddress());
			ValidationResult result = TransactionUtils.signAndImport(repository, chloeBuyData, chloe);
			assertEquals(ValidationResult.INVALID_BUYER, result);

			TransactionData bobBuyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), assetId, price, alice.getAddress());
			TransactionUtils.signAndMint(repository, bobBuyData, bob);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(bob.getAddress(), assetData.getOwner());
		}
	}

	@Test
	public void testDirectAssetOwnershipSaleAllowsZeroPriceGift() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			long assetId = AssetUtils.issueAsset(repository, "alice", "gift-asset", 1000L, true);

			TransactionData publicZeroPriceSale = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, 0L);
			ValidationResult result = TransactionUtils.signAndImport(repository, publicZeroPriceSale, alice);
			assertEquals(ValidationResult.INVALID_AMOUNT, result);

			TransactionData giftSale = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, 0L, bob.getAddress());
			TransactionUtils.signAndMint(repository, giftSale, alice);

			TransactionData bobBuyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), assetId, 0L, alice.getAddress());
			TransactionUtils.signAndMint(repository, bobBuyData, bob);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(bob.getAddress(), assetData.getOwner());
			assertEquals(false, assetData.isOwnerForSale());
		}
	}

	@Test
	public void testCancelDirectAssetOwnershipSaleRestoresRecipientWhenOrphaned() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			long assetId = AssetUtils.issueAsset(repository, "alice", "cancel-direct-asset", 1000L, true);
			long price = 5L * Amounts.MULTIPLIER;

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, price, bob.getAddress());
			TransactionUtils.signAndMint(repository, sellData, alice);

			TransactionData cancelData = new CancelSellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId);
			TransactionUtils.signAndMint(repository, cancelData, alice);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(false, assetData.isOwnerForSale());
			assertEquals(null, assetData.getOwnerSalePrice());
			assertEquals(null, assetData.getOwnerSaleRecipient());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(true, assetData.isOwnerForSale());
			assertEquals(Long.valueOf(price), assetData.getOwnerSalePrice());
			assertEquals(bob.getAddress(), assetData.getOwnerSaleRecipient());
		}
	}

	@Test
	public void testAssetOwnershipBuyOrphanRestoresSaleState() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			long assetId = AssetUtils.issueAsset(repository, "alice", "orphan-buy-asset", 1000L, true);
			long price = 5L * Amounts.MULTIPLIER;

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, price, bob.getAddress());
			TransactionUtils.signAndMint(repository, sellData, alice);

			TransactionData buyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), assetId, price, alice.getAddress());
			TransactionUtils.signAndMint(repository, buyData, bob);

			BlockUtils.orphanLastBlock(repository);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(alice.getAddress(), assetData.getOwner());
			assertEquals(true, assetData.isOwnerForSale());
			assertEquals(Long.valueOf(price), assetData.getOwnerSalePrice());
			assertEquals(bob.getAddress(), assetData.getOwnerSaleRecipient());
		}
	}

	@Test
	public void testNativeAssetOwnershipSaleUnsupported() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), Asset.NATIVE, 1L);
			assertEquals(ValidationResult.NOT_SUPPORTED, TransactionUtils.signAndImport(repository, sellData, alice));

			TransactionData cancelData = new CancelSellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), Asset.NATIVE);
			assertEquals(ValidationResult.NOT_SUPPORTED, TransactionUtils.signAndImport(repository, cancelData, alice));

			TransactionData buyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), Asset.NATIVE, 1L, alice.getAddress());
			assertEquals(ValidationResult.NOT_SUPPORTED, TransactionUtils.signAndImport(repository, buyData, bob));
		}
	}

	@Test
	public void testUpdateAssetNameRevertsThroughOwnershipBuy() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			String originalName = "owned-chain-asset";
			long assetId = AssetUtils.issueAsset(repository, "alice", originalName, 1000L, true);
			long price = 5L * Amounts.MULTIPLIER;

			TransactionData sellData = new SellAssetOwnershipTransactionData(TestTransaction.generateBase(alice), assetId, price, bob.getAddress());
			TransactionUtils.signAndMint(repository, sellData, alice);

			TransactionData buyData = new BuyAssetOwnershipTransactionData(TestTransaction.generateBase(bob), assetId, price, alice.getAddress());
			TransactionUtils.signAndMint(repository, buyData, bob);

			String newName = "owned-chain-renamed";
			TransactionData updateData = new UpdateAssetTransactionData(TestTransaction.generateBase(bob), assetId, newName, "", "");
			TransactionUtils.signAndMint(repository, updateData, bob);

			AssetData assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(bob.getAddress(), assetData.getOwner());
			assertEquals(newName, assetData.getName());

			BlockUtils.orphanLastBlock(repository);

			assetData = repository.getAssetRepository().fromAssetId(assetId);
			assertEquals(bob.getAddress(), assetData.getOwner());
			assertEquals(originalName, assetData.getName());
		}
	}

	@Test
	public void testCalcCommitmentWithRoundUp() throws DataException {
		long amount = 1234_87654321L;
		long price = 1_35615263L;

		// 1234.87654321 * 1.35615263 = 1674.6810717995501423
		// rounded up to 8dp gives: 1674.68107180
		long expectedCommitment = 1674_68107180L;

		long actualCommitment = Amounts.roundUpScaledMultiply(amount, price);
		assertEquals(expectedCommitment, actualCommitment);
	}

	@Test
	public void testCalcCommitmentWithoutRoundUp() throws DataException {
		long amount = 1234_87650000L;
		long price = 1_35610000L;

		// 1234.87650000 * 1.35610000 = 1674.6160216500000000
		// rounded up to 8dp gives: 1674.61602165
		long expectedCommitment = 1674_61602165L;

		long actualCommitment = Amounts.roundUpScaledMultiply(amount, price);
		assertEquals(expectedCommitment, actualCommitment);
	}

}
