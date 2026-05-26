package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class BuyAssetOwnershipTransaction extends Transaction {

	// Properties
	private BuyAssetOwnershipTransactionData buyAssetOwnershipTransactionData;

	// Constructors

	public BuyAssetOwnershipTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.buyAssetOwnershipTransactionData = (BuyAssetOwnershipTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.buyAssetOwnershipTransactionData.getSeller());
	}

	// Navigation

	public Account getBuyer() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		long assetId = this.buyAssetOwnershipTransactionData.getAssetId();

		if (assetId == Asset.NATIVE)
			return ValidationResult.NOT_SUPPORTED;

		if (!Crypto.isValidAddress(this.buyAssetOwnershipTransactionData.getSeller()))
			return ValidationResult.INVALID_ADDRESS;

		AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		if (!assetData.isOwnerForSale())
			return ValidationResult.ASSET_NOT_FOR_SALE;

		Account buyer = getBuyer();
		if (buyer.getAddress().equals(assetData.getOwner()))
			return ValidationResult.BUYER_ALREADY_OWNER;

		String saleRecipient = assetData.getOwnerSaleRecipient();
		if (saleRecipient != null && !saleRecipient.equals(buyer.getAddress()))
			return ValidationResult.INVALID_BUYER;

		if (!this.buyAssetOwnershipTransactionData.getSeller().equals(assetData.getOwner()))
			return ValidationResult.INVALID_SELLER;

		if (assetData.getOwnerSalePrice() == null || this.buyAssetOwnershipTransactionData.getAmount() != assetData.getOwnerSalePrice())
			return ValidationResult.INVALID_AMOUNT;

		if (buyer.getConfirmedBalance(Asset.NATIVE) < this.buyAssetOwnershipTransactionData.getFee() + this.buyAssetOwnershipTransactionData.getAmount())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		Asset asset = new Asset(this.repository, this.buyAssetOwnershipTransactionData.getAssetId());
		asset.buyOwnership(this.buyAssetOwnershipTransactionData, true);

		this.repository.getTransactionRepository().save(this.buyAssetOwnershipTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		Asset asset = new Asset(this.repository, this.buyAssetOwnershipTransactionData.getAssetId());
		asset.unbuyOwnership(this.buyAssetOwnershipTransactionData);

		this.repository.getTransactionRepository().save(this.buyAssetOwnershipTransactionData);
	}

}
