package org.qortium.payment;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.repository.AssetRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;

import java.util.*;
import java.util.Map.Entry;

public class Payment {

	// Properties
	private Repository repository;

	// Constructors

	public Payment(Repository repository) {
		this.repository = repository;
	}

	// Processing


	// isValid

	/** Are payments valid? */
	public ValidationResult isValid(byte[] senderPublicKey, List<PaymentData> payments, long fee, boolean isZeroAmountValid) throws DataException {
		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Check fee is positive or zero
		// We have already checked that the fee is correct in the Transaction superclass
		if (fee < 0)
			return ValidationResult.NEGATIVE_FEE;

		// Total up payment amounts by assetId
		Map<Long, Long> amountsByAssetId = new HashMap<>();
		// Add transaction fee to start with
		amountsByAssetId.put(Asset.NATIVE, fee);

		// From atCheckedArithmeticHeight, per-asset totals are accumulated with checked arithmetic so a
		// crafted payment list can no longer wrap the required total negative and slip past the balance
		// check below. Below the trigger, the historic silently-wrapping accumulation is preserved
		// byte-for-byte so every node keeps agreeing on already-reachable states until the flag day.
		boolean checkedArithmetic = this.repository.getBlockRepository().getBlockchainHeight() + 1
				>= BlockChain.getInstance().getAtCheckedArithmeticHeight();

		// Grab sender info
		Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

		// Check payments, and calculate amount total by assetId
		for (PaymentData paymentData : payments) {
			// Check amount is zero or positive
			if (paymentData.getAmount() < 0)
				return ValidationResult.NEGATIVE_AMOUNT;

			// Optional zero-amount check
			if (!isZeroAmountValid && paymentData.getAmount() <= 0)
				return ValidationResult.NEGATIVE_AMOUNT;

			// Check recipient address is valid
			if (!Crypto.isValidAddress(paymentData.getRecipient()))
				return ValidationResult.INVALID_ADDRESS;

			boolean recipientIsAT = Crypto.isValidAtAddress(paymentData.getRecipient());
			ATData atData = null;

			// Do not allow payments to finished/dead/nonexistent ATs
			if (recipientIsAT) {
				atData = this.repository.getATRepository().fromATAddress(paymentData.getRecipient());

				if (atData == null)
					return ValidationResult.AT_UNKNOWN;

				if (atData != null && atData.getIsFinished())
					return ValidationResult.AT_IS_FINISHED;
			}

			AssetData assetData = assetRepository.fromAssetId(paymentData.getAssetId());
			// Check asset even exists
			if (assetData == null)
				return ValidationResult.ASSET_DOES_NOT_EXIST;

			// Do not allow unspendable assets to be trapped inside AT accounts
			if (recipientIsAT && assetData.isUnspendable())
				return ValidationResult.ASSET_NOT_SPENDABLE;

			// Do not allow non-owner asset holders to use asset
			if (assetData.isUnspendable() && !assetData.getOwner().equals(sender.getAddress()))
				return ValidationResult.ASSET_NOT_SPENDABLE;

			// Check asset amount is integer if asset is not divisible
			if (!assetData.isDivisible() && paymentData.getAmount() % Amounts.MULTIPLIER != 0)
				return ValidationResult.INVALID_AMOUNT;

			// Set or add amount into amounts-by-asset map
			if (checkedArithmetic) {
				try {
					amountsByAssetId.merge(paymentData.getAssetId(), paymentData.getAmount(), Math::addExact);
				} catch (ArithmeticException e) {
					// The required per-asset total is not representable, so no sender balance could ever
					// cover it: reject deterministically within the normal validation-result contract.
					return ValidationResult.NO_BALANCE;
				}
			} else {
				amountsByAssetId.compute(paymentData.getAssetId(), (assetId, amount) -> amount == null ? paymentData.getAmount() : amount + paymentData.getAmount());
			}
		}

		// Check sender has enough of each asset
		for (Entry<Long, Long> pair : amountsByAssetId.entrySet())
			if (sender.getConfirmedBalance(pair.getKey()) < pair.getValue())
				return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	/** Are payments valid? */
	public ValidationResult isValid(byte[] senderPublicKey, List<PaymentData> payments, long fee) throws DataException {
		return isValid(senderPublicKey, payments, fee, false);
	}

	/** Is single payment valid? */
	public ValidationResult isValid(byte[] senderPublicKey, PaymentData paymentData, long fee, boolean isZeroAmountValid) throws DataException {
		return isValid(senderPublicKey, Collections.singletonList(paymentData), fee, isZeroAmountValid);
	}

	/** Is single payment valid? */
	public ValidationResult isValid(byte[] senderPublicKey, PaymentData paymentData, long fee) throws DataException {
		return isValid(senderPublicKey, paymentData, fee, false);
	}

	// isProcessable

	/** Are multiple payments processable? */
	public ValidationResult isProcessable(byte[] senderPublicKey, List<PaymentData> payments, long fee, boolean isZeroAmountValid) throws DataException {
		// Essentially the same as isValid...
		return isValid(senderPublicKey, payments, fee, isZeroAmountValid);
	}

	/** Are multiple payments processable? */
	public ValidationResult isProcessable(byte[] senderPublicKey, List<PaymentData> payments, long fee) throws DataException {
		return isProcessable(senderPublicKey, payments, fee, false);
	}

	/** Is single payment processable? */
	public ValidationResult isProcessable(byte[] senderPublicKey, PaymentData paymentData, long fee, boolean isZeroAmountValid) throws DataException {
		return isProcessable(senderPublicKey, Collections.singletonList(paymentData), fee, isZeroAmountValid);
	}

	/** Is single payment processable? */
	public ValidationResult isProcessable(byte[] senderPublicKey, PaymentData paymentData, long fee) throws DataException {
		return isProcessable(senderPublicKey, paymentData, fee, false);
	}

	// process

	/** Multiple payment processing */
	public void process(byte[] senderPublicKey, List<PaymentData> payments) throws DataException {
		Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

		// Process all payments
		for (PaymentData paymentData : payments) {
			Account recipient = new Account(this.repository, paymentData.getRecipient());

			long assetId = paymentData.getAssetId();
			long amount = paymentData.getAmount();

			// Update sender's balance due to amount
			sender.modifyAssetBalance(assetId, - amount);

			// Update recipient's balance
			recipient.modifyAssetBalance(assetId, amount);
		}
	}

	/** Single payment processing */
	public void process(byte[] senderPublicKey, PaymentData paymentData) throws DataException {
		process(senderPublicKey, Collections.singletonList(paymentData));
	}

	// processReferenceAndFees

	/** Multiple payment fee processing. Legacy references are no longer mutated. */
	public void processReferencesAndFees(byte[] senderPublicKey, List<PaymentData> payments, long fee, byte[] signature, boolean alwaysInitializeRecipientReference)
			throws DataException {
		Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

		// Keep sender public-key metadata even when fee is zero.
		sender.ensureAccount();

		// Update sender's balance due to fee
		if (fee > 0)
			sender.modifyAssetBalance(Asset.NATIVE, - fee);
	}

	/** Single payment fee processing. Legacy references are no longer mutated. */
	public void processReferencesAndFees(byte[] senderPublicKey, PaymentData payment, long fee, byte[] signature, boolean alwaysInitializeRecipientReference)
			throws DataException {
		processReferencesAndFees(senderPublicKey, Collections.singletonList(payment), fee, signature, alwaysInitializeRecipientReference);
	}

	// orphan

	public void orphan(byte[] senderPublicKey, List<PaymentData> payments) throws DataException {
		Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

		// Orphan all payments
		for (PaymentData paymentData : payments) {
			Account recipient = new Account(this.repository, paymentData.getRecipient());
			long assetId = paymentData.getAssetId();
			long amount = paymentData.getAmount();

			// Update sender's balance due to amount
			sender.modifyAssetBalance(assetId, amount);

			// Update recipient's balance
			recipient.modifyAssetBalance(assetId, - amount);
		}
	}

	public void orphan(byte[] senderPublicKey, PaymentData paymentData) throws DataException {
		orphan(senderPublicKey, Collections.singletonList(paymentData));
	}

	// orphanReferencesAndFees

	public void orphanReferencesAndFees(byte[] senderPublicKey, List<PaymentData> payments, long fee, byte[] signature,
			boolean alwaysUninitializeRecipientReference) throws DataException {
		Account sender = new PublicKeyAccount(this.repository, senderPublicKey);

		// Update sender's balance due to fee
		if (fee > 0)
			sender.modifyAssetBalance(Asset.NATIVE, fee);
	}

	public void orphanReferencesAndFees(byte[] senderPublicKey, PaymentData paymentData, long fee, byte[] signature,
			boolean alwaysUninitializeRecipientReference) throws DataException {
		orphanReferencesAndFees(senderPublicKey, Collections.singletonList(paymentData), fee, signature, alwaysUninitializeRecipientReference);
	}

}
