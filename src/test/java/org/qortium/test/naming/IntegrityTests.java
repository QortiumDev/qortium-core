package org.qortium.test.naming;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.*;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryFactory;
import org.qortium.repository.RepositoryManager;
import org.qortium.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Unicode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public class IntegrityTests extends Common {

    private static final String RUN_LIVE_REPOSITORY_INTEGRITY_CHECKS_PROPERTY = "qortium.runLiveRepositoryIntegrityChecks";

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @Test
    public void testValidName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Run the database integrity check for this name
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));

            // Ensure the name still exists and the data is still correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testStoredReducedNameMatchesUnicodeSanitize() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "TEST-nÁme";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            NameData nameData = repository.getNameRepository().fromName(name);
            assertNotNull(nameData);
            assertEquals(Unicode.sanitize(name), nameData.getReducedName());

            repository.discardChanges();
        }
    }

    // Test integrity check after renaming to something else and then back again
    // This was originally confusing the rebuildName() code and creating a loop
    @Test
    public void testUpdateNameLoop() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "initial-name";
            String initialData = "{\"age\":30}";
            String initialReducedName = "1n1t1a1-name";

            TransactionData initialTransactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, initialData);
            initialTransactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(initialTransactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, initialTransactionData, alice);

            // Check initial name exists
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertNotNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Update the name to something new
            String newName = "new-name";
            String newData = "";
            String newReducedName = "new-name";
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Check old name no longer exists
            assertFalse(repository.getNameRepository().nameExists(initialName));
            assertNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Check new name exists
            assertTrue(repository.getNameRepository().nameExists(newName));
            assertNotNull(repository.getNameRepository().fromReducedName(newReducedName));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData.getTimestamp(), repository.getNameRepository().fromName(newName).getUpdated());

            // Update the name to another new name
            String newName2 = "new-name-2";
            String newData2 = "";
            String newReducedName2 = "new-name-2";
            TransactionData updateTransactionData2 = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName, newName2, newData2);
            TransactionUtils.signAndMint(repository, updateTransactionData2, alice);

            // Check old name no longer exists
            assertFalse(repository.getNameRepository().nameExists(newName));
            assertNull(repository.getNameRepository().fromReducedName(newReducedName));

            // Check new name exists
            assertTrue(repository.getNameRepository().nameExists(newName2));
            assertNotNull(repository.getNameRepository().fromReducedName(newReducedName2));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData2.getTimestamp(), repository.getNameRepository().fromName(newName2).getUpdated());

            // Update the name back to the initial name
            TransactionData updateTransactionData3 = new UpdateNameTransactionData(TestTransaction.generateBase(alice), newName2, initialName, initialData);
            TransactionUtils.signAndMint(repository, updateTransactionData3, alice);

            // Check previous name no longer exists
            assertFalse(repository.getNameRepository().nameExists(newName2));
            assertNull(repository.getNameRepository().fromReducedName(newReducedName2));

            // Check initial name exists again
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertNotNull(repository.getNameRepository().fromReducedName(initialReducedName));

            // Check updated timestamp is correct
            assertEquals((Long) updateTransactionData3.getTimestamp(), repository.getNameRepository().fromName(initialName).getUpdated());

            // Run the database integrity check for the initial name, to ensure it doesn't get into a loop
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(4, integrityCheck.rebuildName(initialName, repository)); // 4 transactions total

            // Ensure the new name still exists and the data is still correct
            assertTrue(repository.getNameRepository().nameExists(initialName));
            assertEquals(initialData, repository.getNameRepository().fromName(initialName).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateWithBlankNewName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name to Alice
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "initial_name";
            String data = "initial_data";
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Update the name, but keep the new name blank
            String newName = "";
            String newData = "updated_data";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the original name exists and the data is correct
            assertEquals(name, repository.getNameRepository().fromName(name).getName());
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            // Run the database integrity check for this name
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));

            // Ensure the name still exists and the data is still correct
            assertEquals(name, repository.getNameRepository().fromName(name).getName());
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateWithBlankNewNameAndBlankEmojiName() throws DataException {
        // Attempt to simulate a real world problem where an emoji with blank reducedName
        // confused the integrity check by associating it with previous UPDATE_NAME transactions
        // due to them also having a blank "newReducedName"

        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name to Alice
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "initial_name";
            String data = "initial_data";
            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Update the name, but keep the new name blank
            String newName = "";
            String newData = "updated_data";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Register emoji name
            String emojiName = "\uD83E\uDD73"; // Translates to a reducedName of ""

            // Ensure that the initial_name isn't associated with the emoji name
            NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
            List<TransactionData> transactions = namesDatabaseIntegrityCheck.fetchAllTransactionsInvolvingName(emojiName, repository);
            assertEquals(0, transactions.size());
        }
    }

    @Test
    public void testMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Run the database integrity check for this name and check that a row was modified
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));

            // Ensure the name exists again and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testMissingNameAfterUpdate() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Update the name
            String newData = "{\"age\":31}";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, name, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the name still exists and the data has been updated
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Run the database integrity check for this name
            // We expect 2 modifications to be made - the original register name followed by the update
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));

            // Ensure the name exists and the data is correct
            assertEquals(newData, repository.getNameRepository().fromName(name).getData());

            repository.discardChanges();
        }
    }

    @Test
    public void testMissingNameAfterRename() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Rename the name
            String newName = "new-name";
            String newData = "{\"age\":31}";
            UpdateNameTransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
            TransactionUtils.signAndMint(repository, updateTransactionData, alice);

            // Ensure the new name exists and the data has been updated
            assertEquals(newData, repository.getNameRepository().fromName(newName).getData());

            // Ensure the old name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Now delete the new name, to simulate a database inconsistency
            repository.getNameRepository().delete(newName);

            // Ensure the new name doesn't exist
            assertNull(repository.getNameRepository().fromName(newName));

            // Attempt to register the new name
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), newName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(alice);

            // Register-name validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should validate against current repository state", Transaction.ValidationResult.OK, result);
            assertNull(repository.getNameRepository().fromName(newName));

            // Explicit integrity repair should restore the missing name
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(newName, repository));
            assertEquals(newData, repository.getNameRepository().fromName(newName).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Name should already be registered after explicit repair", Transaction.ValidationResult.NAME_ALREADY_REGISTERED, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testRegisterMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Attempt to register the name again
            String duplicateName = "TEST-nÁme";
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), duplicateName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(alice);

            // Register-name validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should validate against current repository state", Transaction.ValidationResult.OK, result);
            assertNull(repository.getNameRepository().fromName(name));

            // Explicit integrity repair should restore the missing name and reduced-name collision
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Name should already be registered after explicit repair", Transaction.ValidationResult.NAME_ALREADY_REGISTERED, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(initialName).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(initialName);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(initialName));

            // Attempt to update the name
            String newName = "new-name";
            String newData = "";
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, newName, newData);
            Transaction transaction = Transaction.fromData(repository, updateTransactionData);
            transaction.sign(alice);

            // Update-name validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Missing current name should remain invalid until explicit repair", Transaction.ValidationResult.NAME_DOES_NOT_EXIST, result);
            assertNull(repository.getNameRepository().fromName(initialName));

            // Explicit integrity repair should restore the missing name and make the update valid
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(initialName, repository));
            assertEquals(data, repository.getNameRepository().fromName(initialName).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should be valid after explicit repair", Transaction.ValidationResult.OK, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testUpdateToMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String initialName = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), initialName, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(initialName).getData());

            // Register the second name that we will ultimately try and rename the first name to
            String secondName = "new-missing-name";
            String secondNameData = "{\"data2\":true}";
            transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), secondName, secondNameData);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the second name exists and the data is correct
            assertEquals(secondNameData, repository.getNameRepository().fromName(secondName).getData());

            // Now delete the second name, to simulate a database inconsistency
            repository.getNameRepository().delete(secondName);

            // Ensure the second name doesn't exist
            assertNull(repository.getNameRepository().fromName(secondName));

            // Attempt to rename the first name to the second name
            TransactionData updateTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), initialName, secondName, secondNameData);
            Transaction transaction = Transaction.fromData(repository, updateTransactionData);
            transaction.sign(alice);

            // Update-name validation should not repair destination name state as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should validate against current repository state", Transaction.ValidationResult.OK, result);
            assertNull(repository.getNameRepository().fromName(secondName));

            // Explicit integrity repair should restore the destination name and collision
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(secondName, repository));
            assertEquals(secondNameData, repository.getNameRepository().fromName(secondName).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Destination name should already exist after explicit repair", Transaction.ValidationResult.NAME_ALREADY_REGISTERED, result);

            assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));

            repository.discardChanges();
        }
    }

    @Test
    public void testSellMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Attempt to sell the name
            TransactionData sellTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, 123456);
            Transaction transaction = Transaction.fromData(repository, sellTransactionData);
            transaction.sign(alice);

            // Sell-name validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Missing name should remain invalid until explicit repair", Transaction.ValidationResult.NAME_DOES_NOT_EXIST, result);
            assertNull(repository.getNameRepository().fromName(name));

            // Explicit integrity repair should restore the missing name and make the sale valid
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should be valid after explicit repair", Transaction.ValidationResult.OK, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testBuyMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Register-name
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            // Ensure the name exists and the data is correct
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Sell the name
            long amount = 123456;
            TransactionData sellTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, amount);
            TransactionUtils.signAndMint(repository, sellTransactionData, alice);

            // Ensure the name now exists and is for sale
            assertTrue(repository.getNameRepository().fromName(name).isForSale());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);

            // Ensure the name doesn't exist
            assertNull(repository.getNameRepository().fromName(name));

            // Bob now attempts to buy the name
            String seller = alice.getAddress();
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
            TransactionData buyTransactionData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, amount, seller);
            Transaction transaction = Transaction.fromData(repository, buyTransactionData);
            transaction.sign(bob);

            // Buy-name validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Missing name should remain invalid until explicit repair", Transaction.ValidationResult.NAME_DOES_NOT_EXIST, result);
            assertNull(repository.getNameRepository().fromName(name));

            // Explicit integrity repair should restore the missing name sale and make the buy valid
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));
            assertTrue(repository.getNameRepository().fromName(name).isForSale());

            result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should be valid after explicit repair", Transaction.ValidationResult.OK, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testCancelSellMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            long amount = 123456;
            TransactionData sellTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, amount);
            TransactionUtils.signAndMint(repository, sellTransactionData, alice);

            assertTrue(repository.getNameRepository().fromName(name).isForSale());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);
            assertNull(repository.getNameRepository().fromName(name));

            TransactionData cancelSellTransactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
            Transaction transaction = Transaction.fromData(repository, cancelSellTransactionData);
            transaction.sign(alice);

            // Cancel-sell validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Missing name should remain invalid until explicit repair", Transaction.ValidationResult.NAME_DOES_NOT_EXIST, result);
            assertNull(repository.getNameRepository().fromName(name));

            // Explicit integrity repair should restore the missing name sale and make cancel-sell valid
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(2, integrityCheck.rebuildName(name, repository));
            assertTrue(repository.getNameRepository().fromName(name).isForSale());

            result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should be valid after explicit repair", Transaction.ValidationResult.OK, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testArbitraryMissingName() throws DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "test-name";
            String data = "{\"age\":30}";

            RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
            transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
            TransactionUtils.signAndMint(repository, transactionData, alice);

            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            // Now delete the name, to simulate a database inconsistency
            repository.getNameRepository().delete(name);
            assertNull(repository.getNameRepository().fromName(name));

            BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice);
            int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
            TransactionData arbitraryTransactionData = new ArbitraryTransactionData(
                    baseTransactionData,
                    version,
                    Service.ARBITRARY_DATA.value,
                    0,
                    1,
                    name,
                    null,
                    ArbitraryTransactionData.Method.PUT,
                    null,
                    ArbitraryTransactionData.Compression.NONE,
                    new byte[]{1},
                    ArbitraryTransactionData.DataType.RAW_DATA,
                    null,
                    Collections.emptyList());

            Transaction transaction = Transaction.fromData(repository, arbitraryTransactionData);
            transaction.sign(alice);

            // Arbitrary validation should not repair the Names table as a side effect
            transaction.preProcess();
            Transaction.ValidationResult result = transaction.isValidUnconfirmed();
            assertEquals("Missing name should remain invalid until explicit repair", Transaction.ValidationResult.NAME_DOES_NOT_EXIST, result);
            assertNull(repository.getNameRepository().fromName(name));

            // Explicit integrity repair should restore the missing name and make the publish valid
            NamesDatabaseIntegrityCheck integrityCheck = new NamesDatabaseIntegrityCheck();
            assertEquals(1, integrityCheck.rebuildName(name, repository));
            assertEquals(data, repository.getNameRepository().fromName(name).getData());

            result = transaction.isValidUnconfirmed();
            assertEquals("Transaction should be valid after explicit repair", Transaction.ValidationResult.OK, result);

            repository.discardChanges();
        }
    }

    @Test
    public void testLiveRepositoryReducedNameIntegrity() throws DataException {
        assumeTrue(Boolean.getBoolean(RUN_LIVE_REPOSITORY_INTEGRITY_CHECKS_PROPERTY));

        Common.setShouldRetainRepositoryAfterTest(true);
        Settings.fileInstance("settings.json"); // use 'live' settings

        String repositoryUrlTemplate = "jdbc:hsqldb:file:%s" + File.separator + "blockchain;create=false;hsqldb.full_log_replay=true";
        String connectionUrl = String.format(repositoryUrlTemplate, Settings.getInstance().getRepositoryPath());
        RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(connectionUrl);
        RepositoryManager.setRepositoryFactory(repositoryFactory);

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<NameData> names = repository.getNameRepository().getAllNames();
            List<String> integrityFailures = new ArrayList<>();

            for (NameData nameData : names) {
                String reReduced = Unicode.sanitize(nameData.getName());

                if (reReduced.isBlank()) {
                    integrityFailures.add(String.format("Name '%s' reduced to blank", nameData.getName()));
                }

                if (!nameData.getReducedName().equals(reReduced)) {
                    integrityFailures.add(String.format("Name '%s' reduced form was '%s' but is now '%s'",
                            nameData.getName(),
                            nameData.getReducedName(),
                            reReduced
                    ));

                    // ...but does another name already have this reduced form?
                    names.stream()
                            .filter(tmpNameData -> tmpNameData.getReducedName().equals(reReduced))
                            .forEach(tmpNameData ->
                                    integrityFailures.add(String.format("Name '%s' new reduced form also matches name '%s'",
                                            nameData.getName(),
                                            tmpNameData.getName()
                                    ))
                            );
                }
            }

            assertTrue(String.join(System.lineSeparator(), integrityFailures), integrityFailures.isEmpty());
        }
    }
}
