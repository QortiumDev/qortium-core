package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.asset.Asset;
import org.qortium.crypto.MemoryPoW;
import org.qortium.data.transaction.PublicizeTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;

import java.util.Collections;
import java.util.List;

public class PublicizeTransaction extends Transaction {

	// Properties
	private PublicizeTransactionData publicizeTransactionData;

	// Other useful constants

	// Constructors

	public PublicizeTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.publicizeTransactionData = (PublicizeTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	public void computeNonce() {
		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}

		// Clear nonce from transactionBytes
		TransactionTransformer.clearMempowFeeNonce(transactionBytes);

		// Calculate nonce
		this.publicizeTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, this.getMempowFeeAlternativeBufferSize(),
				this.getMempowFeeAlternativeDifficulty()));
	}


	@Override
	public ValidationResult isValid() throws DataException {
		// There can be only one
		List<byte[]> signatures = this.repository.getTransactionRepository().getSignaturesMatchingCriteria(
				TransactionType.PUBLICIZE,
				this.transactionData.getCreatorPublicKey(),
				ConfirmationStatus.CONFIRMED,
				1, null, null);

		if (!signatures.isEmpty())
			return ValidationResult.TRANSACTION_ALREADY_EXISTS;

		// Validate fee if one has been included
		PublicKeyAccount creator = this.getCreator();
		if (this.transactionData.getFee() > 0)
			if (creator.getConfirmedBalance(Asset.NATIVE) < this.transactionData.getFee())
				return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Save this transaction
		this.repository.getTransactionRepository().save(this.transactionData);

		// Ensure public key & address are saved
		this.getSender().ensureAccount();
	}

	@Override
	public void orphan() throws DataException {
		/* Don't actually need to do anything */
	}

}
