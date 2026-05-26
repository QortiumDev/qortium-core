package org.qortium.transaction;

import com.google.common.primitives.Bytes;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.ATTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.AtTransactionTransformer;
import org.qortium.utils.Amounts;

import java.util.Arrays;
import java.util.List;

public class AtTransaction extends Transaction {

	// Properties

	private ATTransactionData atTransactionData;
	private Account atAccount = null;
	private Account recipientAccount = null;

	// Other useful constants
	public static final int MAX_DATA_SIZE = 256;

	// Constructors

	public AtTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.atTransactionData = (ATTransactionData) this.transactionData;

		// Check whether we need to generate the ATTransaction's pseudo-signature
		if (this.atTransactionData.getSignature() == null) {
			// Signature is SHA2-256 of serialized transaction data, duplicated to make standard signature size of 64 bytes.
			try {
				byte[] digest = Crypto.digest(AtTransactionTransformer.toBytes(transactionData));
				byte[] signature = Bytes.concat(digest, digest);
				this.atTransactionData.setSignature(signature);
			} catch (TransformationException e) {
				throw new RuntimeException("Couldn't transform AT Transaction into bytes", e);
			}
		}
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Arrays.asList(this.atTransactionData.getATAddress(), this.atTransactionData.getRecipient());
	}

	// Navigation

	public Account getATAccount() {
		if (this.atAccount == null)
			this.atAccount = new Account(this.repository, this.atTransactionData.getATAddress());

		return this.atAccount;
	}

	public Account getRecipient() {
		if (this.recipientAccount == null)
			this.recipientAccount = new Account(this.repository, this.atTransactionData.getRecipient());

		return this.recipientAccount;
	}

	// Processing


	@Override
	public ValidationResult isValid() throws DataException {
		// Check recipient address is valid
		if (!Crypto.isValidAddress(this.atTransactionData.getRecipient()))
			return ValidationResult.INVALID_ADDRESS;

		Long amount = this.atTransactionData.getAmount();
		Long assetId = this.atTransactionData.getAssetId();
		byte[] message = this.atTransactionData.getMessage();

		boolean hasPayment = amount != null && assetId != null;
		boolean hasMessage = message != null; // empty message OK

		// We can only have either message or payment, not both, nor neither
		if ((hasMessage && hasPayment) || (!hasMessage && !hasPayment))
			return ValidationResult.INVALID_AT_TRANSACTION;

		if (hasMessage && message.length > MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// If we have no payment then we're done
		if (!hasPayment)
			return ValidationResult.OK;

		// Check amount is zero or positive
		if (amount < 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		// Check asset even exists
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Check asset amount is integer if asset is not divisible
		if (!assetData.isDivisible() && amount % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_AMOUNT;

		Account sender = getATAccount();
		// Check sender has enough of asset
		if (sender.getConfirmedBalance(assetId) < amount)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		Long amount = this.atTransactionData.getAmount();

		if (amount != null) {
			Account sender = getATAccount();
			Account recipient = getRecipient();

			long assetId = this.atTransactionData.getAssetId();

			// Update sender's balance due to amount
			sender.modifyAssetBalance(assetId, - amount);

			// Update recipient's balance
			recipient.modifyAssetBalance(assetId, amount);
		}
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		getATAccount().ensureAccount();
	}

	@Override
	public void processCreatorAccount() {
		// AT transactions are emitted by AT accounts and use explicit AT account handling.
	}

	@Override
	public void orphan() throws DataException {
		Long amount = this.atTransactionData.getAmount();

		if (amount != null) {
			Account sender = getATAccount();
			Account recipient = getRecipient();

			long assetId = this.atTransactionData.getAssetId();

			// Update sender's balance due to amount
			sender.modifyAssetBalance(assetId, amount);

			// Update recipient's balance
			recipient.modifyAssetBalance(assetId, - amount);
		}

		// As AT_TRANSACTIONs are really part of a block, the caller (Block) will probably delete this transaction after orphaning
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		// Legacy references are no longer mutated.
	}

}
