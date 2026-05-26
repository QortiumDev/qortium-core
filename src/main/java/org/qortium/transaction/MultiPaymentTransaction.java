package org.qortium.transaction;

import org.qortium.data.PaymentData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.payment.Payment;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.List;
import java.util.stream.Collectors;

public class MultiPaymentTransaction extends Transaction {

	// Properties
	private MultiPaymentTransactionData multiPaymentTransactionData;

	// Useful constants
	private static final int MAX_PAYMENTS_COUNT = 400;

	// Constructors

	public MultiPaymentTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.multiPaymentTransactionData = (MultiPaymentTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.multiPaymentTransactionData.getPayments().stream().map(PaymentData::getRecipient).collect(Collectors.toList());
	}

	@Override
	public ValidationResult isValid() throws DataException {
		List<PaymentData> payments = this.multiPaymentTransactionData.getPayments();

		// Check number of payments
		if (payments.isEmpty() || payments.size() > MAX_PAYMENTS_COUNT)
			return ValidationResult.INVALID_PAYMENTS_COUNT;

		return new Payment(this.repository).isValid(this.multiPaymentTransactionData.getSenderPublicKey(), payments, this.multiPaymentTransactionData.getFee());
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		List<PaymentData> payments = this.multiPaymentTransactionData.getPayments();

		return new Payment(this.repository).isProcessable(this.multiPaymentTransactionData.getSenderPublicKey(), payments, this.multiPaymentTransactionData.getFee());
	}


	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments());
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate fee processing to Payment class.
		new Payment(this.repository).processReferencesAndFees(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments(),
				this.multiPaymentTransactionData.getFee(), this.multiPaymentTransactionData.getSignature(), true);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate fee restoration to Payment class.
		new Payment(this.repository).orphanReferencesAndFees(this.multiPaymentTransactionData.getSenderPublicKey(), this.multiPaymentTransactionData.getPayments(),
				this.multiPaymentTransactionData.getFee(), this.multiPaymentTransactionData.getSignature(), true);
	}

}
