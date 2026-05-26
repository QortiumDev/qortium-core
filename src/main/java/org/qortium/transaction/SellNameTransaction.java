package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class SellNameTransaction extends Transaction {

	/** Maximum amount/price for selling a name. Chosen so value, including 8 decimal places, encodes into 8 bytes or fewer. */
	private static final long MAX_AMOUNT = Asset.MAX_QUANTITY;

	// Properties
	private SellNameTransactionData sellNameTransactionData;

	// Constructors
	public SellNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);
		this.sellNameTransactionData = (SellNameTransactionData) this.transactionData;
	}

	// More information
	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.sellNameTransactionData.getRecipient() != null
				? Collections.singletonList(this.sellNameTransactionData.getRecipient())
				: Collections.emptyList();
	}

	// Navigation
	public Account getOwner() {
		return this.getCreator(); // Owner is the creator of the transaction
	}

	// Processing
	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.sellNameTransactionData.getName();

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Retrieve name data from repository
		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check if name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name is not already for sale
		if (nameData.isForSale())
			return ValidationResult.NAME_ALREADY_FOR_SALE;

		// Validate transaction's public key matches name's current owner
		Account owner = getOwner();
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		String recipient = this.sellNameTransactionData.getRecipient();
		if (recipient != null && !Crypto.isValidAddress(recipient))
			return ValidationResult.INVALID_ADDRESS;

		// Check amount is non-negative for direct sales, positive for public sales, and within valid range
		long amount = this.sellNameTransactionData.getAmount();
		if (amount < 0)
			return ValidationResult.NEGATIVE_AMOUNT;
		if (amount == 0 && recipient == null)
			return ValidationResult.INVALID_AMOUNT;
		if (amount >= MAX_AMOUNT)
			return ValidationResult.INVALID_AMOUNT;

		// Check if owner has enough balance for the transaction fee
		if (owner.getConfirmedBalance(Asset.NATIVE) < this.sellNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK; // All validation checks passed
	}

	@Override
	public void process() throws DataException {
		// Sell the name
		Name name = new Name(this.repository, this.sellNameTransactionData.getName());
		name.sell(this.sellNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert the name sale in case of orphaning
		Name name = new Name(this.repository, this.sellNameTransactionData.getName());
		name.unsell(this.sellNameTransactionData);
	}
}
