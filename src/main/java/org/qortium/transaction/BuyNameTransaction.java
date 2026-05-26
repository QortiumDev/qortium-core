package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.BuyNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class BuyNameTransaction extends Transaction {

	// Properties

	private BuyNameTransactionData buyNameTransactionData;

	// Constructors

	public BuyNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.buyNameTransactionData = (BuyNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.buyNameTransactionData.getSeller());
	}

	// Navigation

	public Account getBuyer() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		Optional<String> buyerPrimaryName = this.getBuyer().getPrimaryName();
		if( buyerPrimaryName.isPresent()  ) {

			NameData nameData = repository.getNameRepository().fromName(buyerPrimaryName.get());
			if (nameData.isForSale()) {
				return ValidationResult.NOT_SUPPORTED;
			}
		}

		String name = this.buyNameTransactionData.getName();

		// Check seller address is valid
		if (!Crypto.isValidAddress(this.buyNameTransactionData.getSeller()))
			return ValidationResult.INVALID_ADDRESS;

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < Name.MIN_NAME_SIZE || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name is currently for sale
		if (!nameData.isForSale())
			return ValidationResult.NAME_NOT_FOR_SALE;

		// Check buyer isn't trying to buy own name
		Account buyer = getBuyer();
		if (buyer.getAddress().equals(nameData.getOwner()))
			return ValidationResult.BUYER_ALREADY_OWNER;

		// Check direct sale recipient, if one was set by seller
		String saleRecipient = nameData.getSaleRecipient();
		if (saleRecipient != null && !saleRecipient.equals(buyer.getAddress()))
			return ValidationResult.INVALID_BUYER;

		// Check expected seller currently owns name
		if (!this.buyNameTransactionData.getSeller().equals(nameData.getOwner()))
			return ValidationResult.INVALID_SELLER;

		// Check amounts agree
		if (this.buyNameTransactionData.getAmount() != nameData.getSalePrice())
			return ValidationResult.INVALID_AMOUNT;

		// Check buyer has enough funds
		if (buyer.getConfirmedBalance(Asset.NATIVE) < this.buyNameTransactionData.getFee() + this.buyNameTransactionData.getAmount())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Buy Name
		Name name = new Name(this.repository, this.buyNameTransactionData.getName());
		name.buy(this.buyNameTransactionData, true);

		// Save transaction with updated "name reference" pointing to previous transaction that changed name
		this.repository.getTransactionRepository().save(this.buyNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Un-buy name
		Name name = new Name(this.repository, this.buyNameTransactionData.getName());
		name.unbuy(this.buyNameTransactionData);

		// Save this transaction, with previous "name reference"
		this.repository.getTransactionRepository().save(this.buyNameTransactionData);
	}
}
