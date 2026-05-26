package org.qortium.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.repository.AccountRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class TransferPrivsTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(TransferPrivsTransaction.class);

	// Properties
	private TransferPrivsTransactionData transferPrivsTransactionData;

	// Constructors

	public TransferPrivsTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.transferPrivsTransactionData = (TransferPrivsTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.transferPrivsTransactionData.getRecipient());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	public Account getRecipient() {
		return new Account(this.repository, this.transferPrivsTransactionData.getRecipient());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check recipient address is valid
		if (!Crypto.isValidAddress(this.transferPrivsTransactionData.getRecipient()))
			return ValidationResult.INVALID_ADDRESS;

		// Check recipient is new account
		AccountData recipientAccountData = this.repository.getAccountRepository().getAccount(this.transferPrivsTransactionData.getRecipient());
		if (recipientAccountData != null)
			return ValidationResult.ACCOUNT_ALREADY_EXISTS;

		// Check sender has funds for fee
		if (getSender().getConfirmedBalance(Asset.NATIVE) < this.transferPrivsTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		AccountData senderAccountData = this.repository.getAccountRepository().getAccount(getSender().getAddress());
		if (senderAccountData == null)
			return ValidationResult.ACCOUNT_NOT_TRANSFERABLE;

		return ValidationResult.OK;
	}


	@Override
	public boolean isConfirmableAtHeight(int height) {
		// Once batch reward distribution is active, do not confirm privilege transfers
		// inside online-account capture blocks or distribution blocks.
		if (Block.isBatchRewardDistributionActive(height)
				&& (Block.isOnlineAccountsBlock(height) || Block.isBatchRewardDistributionBlock(height))) {
			return false;
		}
		return true;
	}

	@Override
	public void process() throws DataException {
		Account sender = this.getSender();
		Account recipient = this.getRecipient();

		// Combine blocks minted counts
		final AccountRepository accountRepository = this.repository.getAccountRepository();

		AccountData senderData = accountRepository.getAccount(sender.getAddress());
		int sendersBlocksMinted = senderData.getBlocksMinted();

		AccountData recipientData = accountRepository.getAccount(recipient.getAddress());
		if (recipientData != null)
			throw new DataException("TRANSFER_PRIVS recipient account already exists");

		recipient.ensureAccount();
		recipientData = accountRepository.getAccount(recipient.getAddress());

		// Save prior values
		this.transferPrivsTransactionData.setPreviousSenderBlocksMinted(sendersBlocksMinted);

		// Transfer blocks minted
		recipientData.setBlocksMinted(sendersBlocksMinted);
		accountRepository.setMintedBlockCount(recipientData);

		// Determine new recipient level based on blocks
		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;
		final int effectiveBlocksMinted = recipientData.getBlocksMinted();

		for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
			if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
				if (newLevel > recipientData.getLevel()) {
					// Account has increased in level!
					recipientData.setLevel(newLevel);
					accountRepository.setLevel(recipientData);
					AccountData updatedRecipientData = recipientData;
					LOGGER.trace(() -> String.format("TRANSFER_PRIVS recipient %s bumped to level %d", updatedRecipientData.getAddress(), updatedRecipientData.getLevel()));
				}

				break;
			}

		// Reset sender's level
		sender.setLevel(0);

		// Reset sender's blocks minted count
		senderData.setBlocksMinted(0);
		accountRepository.setMintedBlockCount(senderData);

		// Save this transaction
		this.repository.getTransactionRepository().save(this.transferPrivsTransactionData);
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		super.processReferencesAndFees();
	}

	@Override
	public void orphan() throws DataException {
		Account sender = this.getSender();
		Account recipient = this.getRecipient();

		final AccountRepository accountRepository = this.repository.getAccountRepository();

		AccountData senderData = accountRepository.getAccount(sender.getAddress());

		final List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
		final int maximumLevel = cumulativeBlocksByLevel.size() - 1;

		// Restore sender's block minted count
		senderData.setBlocksMinted(this.transferPrivsTransactionData.getPreviousSenderBlocksMinted());
		accountRepository.setMintedBlockCount(senderData);

		// Recalculate sender's level
		int effectiveBlocksMinted = senderData.getBlocksMinted();
		for (int newLevel = maximumLevel; newLevel > 0; --newLevel)
			if (effectiveBlocksMinted >= cumulativeBlocksByLevel.get(newLevel)) {
				// Account level
				senderData.setLevel(newLevel);
				accountRepository.setLevel(senderData);
				LOGGER.trace(() -> String.format("TRANSFER_PRIVS sender %s reset to level %d", senderData.getAddress(), senderData.getLevel()));

				break;
			}

		// Recipient did not exist before this transfer.
		accountRepository.delete(recipient.getAddress());

		// Clear values in transaction data
		this.transferPrivsTransactionData.setPreviousSenderBlocksMinted(null);

		// Save this transaction
		this.repository.getTransactionRepository().save(this.transferPrivsTransactionData);
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		super.orphanReferencesAndFees();
	}

}
