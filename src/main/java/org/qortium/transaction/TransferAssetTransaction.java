package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.payment.Payment;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class TransferAssetTransaction extends Transaction {

	// Properties

	private TransferAssetTransactionData transferAssetTransactionData;
	private PaymentData paymentData = null;

	// Constructors

	public TransferAssetTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.transferAssetTransactionData = (TransferAssetTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.transferAssetTransactionData.getRecipient());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	private PaymentData getPaymentData() {
		if (this.paymentData == null)
			this.paymentData = new PaymentData(this.transferAssetTransactionData.getRecipient(), this.transferAssetTransactionData.getAssetId(),
					this.transferAssetTransactionData.getAmount());

		return this.paymentData;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Wrap asset transfer as a payment and delegate final payment checks to Payment class
		return new Payment(this.repository).isValid(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData(), this.transferAssetTransactionData.getFee());
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Wrap asset transfer as a payment and delegate final processable checks to Payment class
		return new Payment(this.repository).isProcessable(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData(), this.transferAssetTransactionData.getFee());
	}


	@Override
	public void process() throws DataException {
		// Wrap asset transfer as a payment and delegate processing to Payment class.
		new Payment(this.repository).process(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap asset transfer as a payment and delegate fee processing to Payment class.
		new Payment(this.repository).processReferencesAndFees(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData(), this.transferAssetTransactionData.getFee(),
				this.transferAssetTransactionData.getSignature(), false);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap asset transfer as a payment and delegate processing to Payment class.
		new Payment(this.repository).orphan(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap asset transfer as a payment and delegate fee restoration to Payment class.
		new Payment(this.repository).orphanReferencesAndFees(this.transferAssetTransactionData.getSenderPublicKey(), getPaymentData(), this.transferAssetTransactionData.getFee(),
				this.transferAssetTransactionData.getSignature(), false);
	}

}
