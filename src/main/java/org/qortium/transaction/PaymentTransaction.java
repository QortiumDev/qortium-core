package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.notification.NotificationEvent;
import org.qortium.notification.NotificationManager;
import org.qortium.payment.Payment;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import java.util.Collections;
import java.util.List;

public class PaymentTransaction extends Transaction {

	// Properties

	private PaymentTransactionData paymentTransactionData;
	private PaymentData paymentData = null;

	// Constructors

	public PaymentTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.paymentTransactionData = (PaymentTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.paymentTransactionData.getRecipient());
	}

	// Navigation

	public Account getSender() {
		return this.getCreator();
	}

	// Processing

	private PaymentData getPaymentData() {
		if (this.paymentData == null)
			this.paymentData = new PaymentData(this.paymentTransactionData.getRecipient(), Asset.NATIVE, this.paymentTransactionData.getAmount());

		return this.paymentData;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Wrap and delegate final payment checks to Payment class
		return new Payment(this.repository).isValid(this.paymentTransactionData.getSenderPublicKey(), getPaymentData(), this.paymentTransactionData.getFee());
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Wrap and delegate final processable checks to Payment class
		return new Payment(this.repository).isProcessable(this.paymentTransactionData.getSenderPublicKey(), getPaymentData(), this.paymentTransactionData.getFee());
	}


	@Override
	public void process() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).process(this.paymentTransactionData.getSenderPublicKey(), getPaymentData());

		if (!NotificationManager.isRecentEnoughToNotify(this.paymentTransactionData.getTimestamp()))
			return;

		if (!NotificationManager.getInstance().hasSubscriptions("PAYMENT_RECEIVED"))
			return;

		// Capture PAYMENT_RECEIVED data before handing it to NotificationManager's
		// dedicated bounded dispatcher. This must never add latency to block commit.
		final String sender    = Crypto.toAddress(this.paymentTransactionData.getCreatorPublicKey());
		final String recipient = this.paymentTransactionData.getRecipient();
		final String amount    = Amounts.prettyAmount(this.paymentTransactionData.getAmount());
		final String timestamp = String.valueOf(this.paymentTransactionData.getTimestamp());
		final String signature = this.paymentTransactionData.getSignature() != null
				? Base58.encode(this.paymentTransactionData.getSignature()) : null;
		final java.util.Map<String, String> data = new java.util.HashMap<>();
		data.put("sender", sender);
		data.put("recipient", recipient);
		data.put("amount", amount);
		data.put("created", timestamp);
		if (signature != null) data.put("signature", signature);
		NotificationManager.getInstance().dispatchEventAsync(
				new NotificationEvent("PAYMENT_RECEIVED", data, signature));
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		// Wrap and delegate fee processing to Payment class.
		new Payment(this.repository).processReferencesAndFees(this.paymentTransactionData.getSenderPublicKey(), getPaymentData(), this.paymentTransactionData.getFee(),
				this.paymentTransactionData.getSignature(), false);
	}

	@Override
	public void orphan() throws DataException {
		// Wrap and delegate payment processing to Payment class.
		new Payment(this.repository).orphan(this.paymentTransactionData.getSenderPublicKey(), getPaymentData());
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Wrap and delegate fee restoration to Payment class.
		new Payment(this.repository).orphanReferencesAndFees(this.paymentTransactionData.getSenderPublicKey(), getPaymentData(), this.paymentTransactionData.getFee(),
				this.paymentTransactionData.getSignature(), false);
	}

}
