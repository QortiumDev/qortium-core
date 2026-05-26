package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class RegisterNameTransaction extends Transaction {

	// Properties
	private RegisterNameTransactionData registerNameTransactionData;

	// Constructors

	public RegisterNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.registerNameTransactionData = (RegisterNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public long getUnitFee(Long timestamp) {
		return BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(timestamp);
	}

	@Override
	protected long getEffectiveUnitFee(Long timestamp) throws DataException {
		if (this.repository == null)
			return this.getUnitFee(timestamp);

		int nextBlockHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		return BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(this.repository, nextBlockHeight, timestamp);
	}

	// Navigation

	public Account getRegistrant() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		Account registrant = getRegistrant();
		String name = this.registerNameTransactionData.getName();

		Optional<String> registrantPrimaryName = registrant.getPrimaryName();
		if( registrantPrimaryName.isPresent()  ) {

			NameData nameData = repository.getNameRepository().fromName(registrantPrimaryName.get());
			if (nameData.isForSale()) {
				return ValidationResult.NOT_SUPPORTED;
			}
		}

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < Name.MIN_NAME_SIZE || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check data size bounds
		int dataLength = Utf8.encodedLength(this.registerNameTransactionData.getData());
		if (dataLength > Name.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!name.equals(Unicode.normalize(name)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Check name doesn't look like an address
		if (Crypto.isValidAddress(name))
			return ValidationResult.INVALID_ADDRESS;

		// Check registrant has enough funds
		if (registrant.getConfirmedBalance(Asset.NATIVE) < this.registerNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check the name isn't already taken
		if (this.repository.getNameRepository().reducedNameExists(this.registerNameTransactionData.getReducedName()))
			return ValidationResult.NAME_ALREADY_REGISTERED;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Register Name
		Name name = new Name(this.repository, this.registerNameTransactionData);
		name.register();
	}

	@Override
	public void orphan() throws DataException {
		// Unregister name
		Name name = new Name(this.repository, this.registerNameTransactionData.getName());
		name.unregister();
	}

}
