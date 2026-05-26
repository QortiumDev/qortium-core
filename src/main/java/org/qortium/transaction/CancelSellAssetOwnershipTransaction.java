package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class CancelSellAssetOwnershipTransaction extends Transaction {

	// Properties
	private CancelSellAssetOwnershipTransactionData cancelSellAssetOwnershipTransactionData;

	// Constructors

	public CancelSellAssetOwnershipTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.cancelSellAssetOwnershipTransactionData = (CancelSellAssetOwnershipTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		long assetId = this.cancelSellAssetOwnershipTransactionData.getAssetId();

		if (assetId == Asset.NATIVE)
			return ValidationResult.NOT_SUPPORTED;

		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		if (!assetData.isOwnerForSale())
			return ValidationResult.ASSET_NOT_FOR_SALE;

		Account owner = getOwner();
		if (!owner.getAddress().equals(assetData.getOwner()))
			return ValidationResult.INVALID_ASSET_OWNER;

		if (owner.getConfirmedBalance(Asset.NATIVE) < this.cancelSellAssetOwnershipTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		Asset asset = new Asset(this.repository, this.cancelSellAssetOwnershipTransactionData.getAssetId());
		asset.cancelSellOwnership(this.cancelSellAssetOwnershipTransactionData);

		this.repository.getTransactionRepository().save(this.cancelSellAssetOwnershipTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		Asset asset = new Asset(this.repository, this.cancelSellAssetOwnershipTransactionData.getAssetId());
		asset.uncancelSellOwnership(this.cancelSellAssetOwnershipTransactionData);

		this.repository.getTransactionRepository().save(this.cancelSellAssetOwnershipTransactionData);
	}

}
