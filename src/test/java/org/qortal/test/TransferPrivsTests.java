package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Amounts;

import java.util.List;

import static org.junit.Assert.*;

public class TransferPrivsTests extends Common {

	private static final long FEE = 1L * Amounts.MULTIPLIER;

	private List<Integer> cumulativeBlocksByLevel;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		this.cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testTransferPrivsToCleanAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount sender = Common.getTestAccount(repository, "alice");
			Account recipient = Common.generateRandomSeedAccount(repository);

			assertNull(repository.getAccountRepository().getAccount(recipient.getAddress()));

			AccountData preTransferSenderData = repository.getAccountRepository().getAccount(sender.getAddress());
			int preTransferSenderBlocksMinted = preTransferSenderData.getBlocksMinted();
			int preTransferSenderLevel = preTransferSenderData.getLevel();

			TransactionData transactionData = buildTransferPrivs(repository, sender, recipient);
			TransactionUtils.signAndImportValid(repository, transactionData, sender);

			BlockUtils.mintBlock(repository);

			TransferPrivsTransactionData processedTransactionData = (TransferPrivsTransactionData) repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			int transferredBlocksMinted = processedTransactionData.getPreviousSenderBlocksMinted();

			assertEquals("Alice reward-share should mint the transfer block before Alice transfers privs", preTransferSenderBlocksMinted + 1, transferredBlocksMinted);

			AccountData postTransferSenderData = repository.getAccountRepository().getAccount(sender.getAddress());
			checkSenderPostTransfer(postTransferSenderData);

			AccountData postTransferRecipientData = repository.getAccountRepository().getAccount(recipient.getAddress());
			assertNotNull(postTransferRecipientData);
			assertEquals("recipient minted block count incorrect", transferredBlocksMinted, postTransferRecipientData.getBlocksMinted());
			assertEquals("recipient level incorrect", levelForBlocksMinted(transferredBlocksMinted), postTransferRecipientData.getLevel());

			BlockUtils.orphanLastBlock(repository);

			AccountData orphanedSenderData = repository.getAccountRepository().getAccount(sender.getAddress());
			assertEquals("sender's minted block count wasn't restored", preTransferSenderBlocksMinted, orphanedSenderData.getBlocksMinted());
			assertEquals("sender's level wasn't restored", preTransferSenderLevel, orphanedSenderData.getLevel());
			assertNull("clean recipient account should be removed when transfer is orphaned", repository.getAccountRepository().getAccount(recipient.getAddress()));
		}
	}

	@Test
	public void testFundedAccountCanTransferPrivsToCleanAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount sender = Common.generateRandomSeedAccount(repository);
			Account recipient = Common.generateRandomSeedAccount(repository);

			AccountUtils.pay(repository, Common.getTestAccount(repository, "alice"), sender.getAddress(), FEE + 1L);
			assertNotNull(repository.getAccountRepository().getAccount(sender.getAddress()));
			assertNull(repository.getAccountRepository().getAccount(recipient.getAddress()));

			TransactionData transactionData = buildTransferPrivs(repository, sender, recipient);
			TransactionUtils.signAndImportValid(repository, transactionData, sender);

			BlockUtils.mintBlock(repository);

			AccountData postTransferSenderData = repository.getAccountRepository().getAccount(sender.getAddress());
			checkSenderPostTransfer(postTransferSenderData);

			AccountData postTransferRecipientData = repository.getAccountRepository().getAccount(recipient.getAddress());
			assertNotNull(postTransferRecipientData);
			assertEquals("recipient minted block count incorrect", 0, postTransferRecipientData.getBlocksMinted());
			assertEquals("recipient level incorrect", 0, postTransferRecipientData.getLevel());
		}
	}

	@Test
	public void testTransferPrivsRejectsGenesisRecipient() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount sender = Common.getTestAccount(repository, "alice");
			TestAccount recipient = Common.getTestAccount(repository, "dilbert");

			assertEquals(ValidationResult.ACCOUNT_ALREADY_EXISTS, validateTransferPrivs(repository, sender, recipient));
		}
	}

	@Test
	public void testTransferPrivsRejectsPreviouslyFundedRecipient() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount sender = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.generateRandomSeedAccount(repository);

			AccountUtils.pay(repository, sender, recipient.getAddress(), 1L);
			assertNotNull(repository.getAccountRepository().getAccount(recipient.getAddress()));

			assertEquals(ValidationResult.ACCOUNT_ALREADY_EXISTS, validateTransferPrivs(repository, sender, recipient));
		}
	}

	private ValidationResult validateTransferPrivs(Repository repository, PrivateKeyAccount senderAccount, Account recipientAccount) throws DataException {
		TransactionData transactionData = buildTransferPrivs(repository, senderAccount, recipientAccount);
		Transaction transaction = Transaction.fromData(repository, transactionData);

		return transaction.isValid();
	}

	private TransactionData buildTransferPrivs(Repository repository, PrivateKeyAccount senderAccount, Account recipientAccount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, senderAccount.getPublicKey(), FEE, null);
		return new TransferPrivsTransactionData(baseTransactionData, recipientAccount.getAddress());
	}

	private int levelForBlocksMinted(int blocksMinted) {
		for (int level = this.cumulativeBlocksByLevel.size() - 1; level > 0; --level)
			if (blocksMinted >= this.cumulativeBlocksByLevel.get(level))
				return level;

		return 0;
	}

	private void checkSenderPostTransfer(AccountData senderAccountData) {
		assertEquals("sender's level should be zeroed", 0, senderAccountData.getLevel());
		assertEquals("sender's minted block count should be zeroed", 0, senderAccountData.getBlocksMinted());
	}

}
