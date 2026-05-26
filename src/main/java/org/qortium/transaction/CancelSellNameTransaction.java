package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.CancelSellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class CancelSellNameTransaction extends Transaction {

	// Properties
	private CancelSellNameTransactionData cancelSellNameTransactionData;

	// Constructors
	public CancelSellNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);
		this.cancelSellNameTransactionData = (CancelSellNameTransactionData) this.transactionData;
	}

	// More information
	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList(); // No recipient address for this transaction
	}

	// Navigation
	public Account getOwner() {
		return this.getCreator(); // The creator of the transaction is the owner
	}

	// Processing
	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.cancelSellNameTransactionData.getName();

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

		// Check name is currently for sale
		if (!nameData.isForSale())
			return ValidationResult.NAME_NOT_FOR_SALE;

		// Check if transaction creator matches the name's current owner
		Account owner = getOwner();
		if (!owner.getAddress().equals(nameData.getOwner()))
			return ValidationResult.INVALID_NAME_OWNER;

		// Check if issuer has enough balance for the transaction fee
		if (owner.getConfirmedBalance(Asset.NATIVE) < cancelSellNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK; // All validations passed
	}

	@Override
	public void process() throws DataException {
		// Update the Name to reflect the cancellation of the sale
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.cancelSell(cancelSellNameTransactionData);

		// Save this transaction with updated "name reference"
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert the cancellation of the name sale
		Name name = new Name(this.repository, cancelSellNameTransactionData.getName());
		name.uncancelSell(cancelSellNameTransactionData);

		// Save the transaction with the reverted "name reference"
		this.repository.getTransactionRepository().save(cancelSellNameTransactionData);
	}
}
