package org.qortium.test.naming;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.block.BlockChain;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.BuyNameTransactionData;
import org.qortium.data.transaction.CancelSellNameTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Amounts;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.Assert.*;

public class BuySellTests extends Common {

	protected static final Random random = new Random();

	private Repository repository;
	private PrivateKeyAccount alice;
	private PrivateKeyAccount bob;
	private PrivateKeyAccount chloe;

	private String name;
	private Long price;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		repository = RepositoryManager.getRepository();
		alice = Common.getTestAccount(repository, "alice");
		bob = Common.getTestAccount(repository, "bob");
		chloe = Common.getTestAccount(repository, "chloe");

		name = "test name" + " " + random.nextInt(1_000_000);
		price = (random.nextInt(1000) + 1) * Amounts.MULTIPLIER;
	}

	@After
	public void afterTest() throws DataException {
		name = null;
		price = null;

		alice = null;
		bob = null;
		chloe = null;

		repository = null;

		Common.orphanCheck();
	}

	@Test
	public void testRegisterName() throws DataException {
		// Register-name
		RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
		transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
		TransactionUtils.signAndMint(repository, transactionData, alice);

		String name = transactionData.getName();

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Orphan register-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));

		// Re-process register-name
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));
	}

	@Test
	public void testRegisterNameMultiple() throws DataException {
		// register name 1
		RegisterNameTransactionData transactionData1 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
		transactionData1.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData1.getTimestamp()));
		TransactionUtils.signAndMint(repository, transactionData1, alice);

		String name1 = transactionData1.getName();

		// check name does exist
		assertTrue(repository.getNameRepository().nameExists(name1));

		// register another name, second registered name should also be allowed
		final String name2 = "another name";
		RegisterNameTransactionData transactionData2 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
		transactionData2.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData2.getTimestamp()));
		Transaction.ValidationResult result = TransactionUtils.signAndImport(repository, transactionData2, alice);
		assertEquals(Transaction.ValidationResult.OK, result);

		// mint block, confirm transaction
		BlockUtils.mintBlock(repository);

		// check name does exist
		assertTrue(repository.getNameRepository().nameExists(name2));

		// check that there are 2 names for one account
		List<NameData> namesByOwner = repository.getNameRepository().getNamesByOwner(alice.getAddress(), 0, 0, false);

		assertEquals(2, namesByOwner.size() );

		// check that the order is correct
		assertEquals(name1, namesByOwner.get(0).getName());

		SellNameTransactionData sellPrimaryNameData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, sellPrimaryNameData, alice);

		NameData nameData = repository.getNameRepository().fromName(name);
		assertTrue("primary name should be sellable while owner has multiple names", nameData.isForSale());
	}

	@Test
	public void testSellName() throws DataException {
		// Register-name
		testRegisterName();

		// assert primary name for alice
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertTrue(alicePrimaryName1.get().equals(name));

		// Sell-name
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		// assert primary name for alice
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isPresent());
		assertTrue(alicePrimaryName2.get().equals(name));

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// assert alice cannot register another name while primary name is for sale
		final String name2 = "another name";
		RegisterNameTransactionData registerSecondNameData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
		registerSecondNameData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerSecondNameData.getTimestamp()));
		Transaction.ValidationResult registrationResult = TransactionUtils.signAndImport(repository, registerSecondNameData, alice);

		// check that registering is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(registrationResult));

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockUtils.mintBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name and register-name
		BlockUtils.orphanBlocks(repository, 2);

		// assert primary name for alice
		Optional<String> alicePrimaryName3 = alice.getPrimaryName();
		assertTrue(alicePrimaryName3.isEmpty());

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));
		nameData = repository.getNameRepository().fromName(name);
		assertNull(nameData);

		// Re-process register-name and sell-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name exists thanks to register-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
	}

	@Test
	public void testCancelSellName() throws DataException {
		// Register-name and sell-name
		testSellName();

		// Cancel Sell-name
		CancelSellNameTransactionData transactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData;

		// Check name is no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Orphan cancel sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
	}

	@Test
	public void testDirectSellNameOnlyRecipientCanBuy() throws DataException {
		testRegisterName();

		String seller = alice.getAddress();
		SellNameTransactionData sellNameTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price, bob.getAddress());
		TransactionUtils.signAndMint(repository, sellNameTransactionData, alice);

		NameData nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
		assertEquals("sale recipient incorrect", bob.getAddress(), nameData.getSaleRecipient());

		BuyNameTransactionData chloeBuyData = new BuyNameTransactionData(TestTransaction.generateBase(chloe), name, price, seller);
		Transaction.ValidationResult chloeBuyResult = TransactionUtils.signAndImport(repository, chloeBuyData, chloe);
		assertEquals(Transaction.ValidationResult.INVALID_BUYER, chloeBuyResult);

		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals(alice.getAddress(), nameData.getOwner());
		assertEquals(bob.getAddress(), nameData.getSaleRecipient());

		BuyNameTransactionData bobBuyData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, price, seller);
		TransactionUtils.signAndMint(repository, bobBuyData, bob);

		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertEquals(bob.getAddress(), nameData.getOwner());
		assertNull(nameData.getSaleRecipient());

		BlockUtils.orphanLastBlock(repository);

		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals(alice.getAddress(), nameData.getOwner());
		assertEquals("price incorrect", price, nameData.getSalePrice());
		assertEquals("sale recipient incorrect", bob.getAddress(), nameData.getSaleRecipient());
	}

	@Test
	public void testDirectSellNameAllowsZeroPriceGift() throws DataException {
		testRegisterName();

		String seller = alice.getAddress();
		SellNameTransactionData publicZeroPriceSale = new SellNameTransactionData(TestTransaction.generateBase(alice), name, 0L);
		Transaction.ValidationResult publicSaleResult = TransactionUtils.signAndImport(repository, publicZeroPriceSale, alice);
		assertEquals(Transaction.ValidationResult.INVALID_AMOUNT, publicSaleResult);

		SellNameTransactionData giftSale = new SellNameTransactionData(TestTransaction.generateBase(alice), name, 0L, bob.getAddress());
		TransactionUtils.signAndMint(repository, giftSale, alice);

		NameData nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", Long.valueOf(0L), nameData.getSalePrice());
		assertEquals("sale recipient incorrect", bob.getAddress(), nameData.getSaleRecipient());

		BuyNameTransactionData bobBuyData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, 0L, seller);
		TransactionUtils.signAndMint(repository, bobBuyData, bob);

		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertEquals(bob.getAddress(), nameData.getOwner());
		assertNull(nameData.getSaleRecipient());

		BlockUtils.orphanLastBlock(repository);

		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals(alice.getAddress(), nameData.getOwner());
		assertEquals("price incorrect", Long.valueOf(0L), nameData.getSalePrice());
		assertEquals("sale recipient incorrect", bob.getAddress(), nameData.getSaleRecipient());
	}

	@Test
	public void testCancelDirectSellNameRestoresRecipientWhenOrphaned() throws DataException {
		testRegisterName();

		SellNameTransactionData sellNameTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price, bob.getAddress());
		TransactionUtils.signAndMint(repository, sellNameTransactionData, alice);

		CancelSellNameTransactionData cancelSellNameTransactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, cancelSellNameTransactionData, alice);

		NameData nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());
		assertNull(nameData.getSaleRecipient());

		BlockUtils.orphanLastBlock(repository);

		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
		assertEquals("sale recipient incorrect", bob.getAddress(), nameData.getSaleRecipient());
	}

	@Test
	public void testDuplicateCancelSellNameInvalid() throws DataException {
		// Register-name and sell-name
		testSellName();

		// First cancel succeeds
		CancelSellNameTransactionData transactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());

		// Second cancel without relisting should fail
		CancelSellNameTransactionData duplicateCancelTransactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		Transaction.ValidationResult validationResult = TransactionUtils.signAndImport(repository, duplicateCancelTransactionData, alice);
		assertEquals("Duplicate cancel-sell should be rejected", Transaction.ValidationResult.NAME_NOT_FOR_SALE, validationResult);

		// Name should remain not for sale after the rejected duplicate cancel
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());
	}

	@Test
	public void testCancelSellNameAndRelist() throws DataException {
		// Register-name and sell-name
		testSellName();

		// Cancel Sell-name
		CancelSellNameTransactionData transactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData;

		// Check name is no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());

		// Re-sell-name
		Long newPrice = random.nextInt(1000) * Amounts.MULTIPLIER;
		SellNameTransactionData sellNameTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, newPrice);
		TransactionUtils.signAndMint(repository, sellNameTransactionData, alice);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());

		// Orphan cancel-sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name is for sale (at original price)
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name and register-name
		BlockUtils.orphanBlocks(repository, 2);
	}

	@Test
	public void testBuyName() throws DataException {
		// Register-name and sell-name
		testSellName();

		String seller = alice.getAddress();

		// assert alice has the name as primary
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertEquals(name, alicePrimaryName1.get());

		// assert bob does not have a primary name
		Optional<String> bobPrimaryName1 = bob.getPrimaryName();
		assertTrue(bobPrimaryName1.isEmpty());

		// Buy-name
		BuyNameTransactionData transactionData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, price, seller);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		// assert alice does not have a primary name anymore
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isEmpty());

		// assert bob does have the name as primary
		Optional<String> bobPrimaryName2 = bob.getPrimaryName();
		assertTrue(bobPrimaryName2.isPresent());
		assertEquals(name, bobPrimaryName2.get());

		NameData nameData;

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Orphan buy-name
		BlockUtils.orphanLastBlock(repository);

		// assert alice has the name as primary
		Optional<String> alicePrimaryNameOrphaned = alice.getPrimaryName();
		assertTrue(alicePrimaryNameOrphaned.isPresent());
		assertEquals(name, alicePrimaryNameOrphaned.get());

		// assert bob does not have a primary name
		Optional<String> bobPrimaryNameOrphaned = bob.getPrimaryName();
		assertTrue(bobPrimaryNameOrphaned.isEmpty());

		// Check name is for sale (not sold)
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Re-process buy-name
		BlockUtils.mintBlock(repository);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());

		// Orphan buy-name and sell-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process sell-name and buy-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed buy-name transaction not included in previous block
		// as it isn't valid until name is for sale thanks to sell-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());

		assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
		assertEquals(bob.getPrimaryName(), bob.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
	}

	@Test
	public void testSellBuySellName() throws DataException {
		// Register-name, sell-name, buy-name
		testBuyName();

		// Sell-name
		Long newPrice = random.nextInt(1000) * Amounts.MULTIPLIER;
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(bob), name, newPrice);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockUtils.mintBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name and buy-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		// Note: original sale price
		assertEquals("price incorrect", price, nameData.getSalePrice());
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process buy-name and sell-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name owned by bob thanks to buy-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());
		assertEquals(bob.getAddress(), nameData.getOwner());

		assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
		assertEquals(bob.getPrimaryName(), bob.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
	}

	@Test
	public void testBuyInvalidationDuringPrimaryNameSale() throws DataException {
		// Register-name
		testRegisterName();

		// assert primary name for alice
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertTrue(alicePrimaryName1.get().equals(name));

		// Sell-name
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		// assert primary name for alice
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isPresent());
		assertTrue(alicePrimaryName2.get().equals(name));

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// assert alice cannot register another name while primary name is for sale
		final String name2 = "another name";
		RegisterNameTransactionData registerSecondNameData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
		registerSecondNameData.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerSecondNameData.getTimestamp()));
		Transaction.ValidationResult registrationResult = TransactionUtils.signAndImport(repository, registerSecondNameData, alice);

		// check that registering is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(registrationResult));

		String bobName = "bob";
		RegisterNameTransactionData bobRegisterData = new RegisterNameTransactionData(TestTransaction.generateBase(bob), bobName, "{}");
		bobRegisterData.setFee(new RegisterNameTransaction(null, null).getUnitFee(bobRegisterData.getTimestamp()));
		TransactionUtils.signAndMint(repository, bobRegisterData, bob);

		Optional<String> bobPrimaryName = bob.getPrimaryName();

		assertTrue(bobPrimaryName.isPresent());
		assertEquals(bobName, bobPrimaryName.get());

		SellNameTransactionData bobSellData = new SellNameTransactionData(TestTransaction.generateBase(bob), bobName, price);
		TransactionUtils.signAndMint(repository, bobSellData, bob);

		BuyNameTransactionData aliceBuyData = new BuyNameTransactionData(TestTransaction.generateBase(alice), bobName, price, bob.getAddress());
		Transaction.ValidationResult aliceBuyResult = TransactionUtils.signAndImport(repository, aliceBuyData, alice);

		// check that buying is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(aliceBuyResult));
	}
}
