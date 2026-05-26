package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.SellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class SellAssetOwnershipTransaction extends Transaction {

	private static final long MAX_AMOUNT = Asset.MAX_QUANTITY;

	// Properties
	private SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData;

	// Constructors

	public SellAssetOwnershipTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.sellAssetOwnershipTransactionData = (SellAssetOwnershipTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return this.sellAssetOwnershipTransactionData.getRecipient() != null
				? Collections.singletonList(this.sellAssetOwnershipTransactionData.getRecipient())
				: Collections.emptyList();
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		long assetId = this.sellAssetOwnershipTransactionData.getAssetId();

		if (assetId == Asset.NATIVE)
			return ValidationResult.NOT_SUPPORTED;

		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		if (assetData.isOwnerForSale())
			return ValidationResult.ASSET_ALREADY_FOR_SALE;

		Account owner = getOwner();
		if (!owner.getAddress().equals(assetData.getOwner()))
			return ValidationResult.INVALID_ASSET_OWNER;

		String recipient = this.sellAssetOwnershipTransactionData.getRecipient();
		if (recipient != null && !Crypto.isValidAddress(recipient))
			return ValidationResult.INVALID_ADDRESS;

		long amount = this.sellAssetOwnershipTransactionData.getAmount();
		if (amount < 0)
			return ValidationResult.NEGATIVE_AMOUNT;
		if (amount == 0 && recipient == null)
			return ValidationResult.INVALID_AMOUNT;
		if (amount >= MAX_AMOUNT)
			return ValidationResult.INVALID_AMOUNT;

		if (owner.getConfirmedBalance(Asset.NATIVE) < this.sellAssetOwnershipTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		Asset asset = new Asset(this.repository, this.sellAssetOwnershipTransactionData.getAssetId());
		asset.sellOwnership(this.sellAssetOwnershipTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		Asset asset = new Asset(this.repository, this.sellAssetOwnershipTransactionData.getAssetId());
		asset.unsellOwnership(this.sellAssetOwnershipTransactionData);
	}

}
