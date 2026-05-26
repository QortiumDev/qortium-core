package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateAssetTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class UpdateAssetTransaction extends Transaction {

	// Properties
	private UpdateAssetTransactionData updateAssetTransactionData;

	// Constructors

	public UpdateAssetTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.updateAssetTransactionData = (UpdateAssetTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public PublicKeyAccount getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check asset actually exists
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(this.updateAssetTransactionData.getAssetId());
		if (assetData == null)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		// Check new name (0 length means DO NOT CHANGE name)
		String newName = this.updateAssetTransactionData.getNewName();
		int newNameLength = Utf8.encodedLength(newName);
		if (newNameLength != 0) {
			if (newNameLength < Asset.MIN_NAME_SIZE || newNameLength > Asset.MAX_NAME_SIZE)
				return ValidationResult.INVALID_NAME_LENGTH;

			// Check name is in normalized form (no leading/trailing whitespace, etc.)
			if (!newName.equals(Unicode.normalize(newName)))
				return ValidationResult.NAME_NOT_NORMALIZED;
		}

		// Check new description size bounds. Note: zero length means DO NOT CHANGE description
		int newDescriptionLength = Utf8.encodedLength(this.updateAssetTransactionData.getNewDescription());
		if (newDescriptionLength > Asset.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// Check new data size bounds. Note: zero length means DO NOT CHANGE data
		int newDataLength = Utf8.encodedLength(this.updateAssetTransactionData.getNewData());
		if (newDataLength > Asset.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		// As this transaction type could require approval, check txGroupId
		// matches groupID at creation
		if (assetData.getCreationGroupId() != this.updateAssetTransactionData.getTxGroupId())
			return ValidationResult.TX_GROUP_ID_MISMATCH;

		Account currentOwner = getOwner();

		// Check current owner has enough funds
		if (currentOwner.getConfirmedBalance(Asset.NATIVE) < this.updateAssetTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Check transaction's public key matches asset's current owner
		Account currentOwner = getOwner();
		AssetData assetData = this.repository.getAssetRepository().fromAssetId(this.updateAssetTransactionData.getAssetId());

		if (!assetData.getOwner().equals(currentOwner.getAddress()))
			return ValidationResult.INVALID_ASSET_OWNER;

		if (!this.updateAssetTransactionData.getNewName().isEmpty()) {
			AssetData existingAssetData = this.repository.getAssetRepository().fromReducedAssetName(this.updateAssetTransactionData.getReducedNewName());
			if (existingAssetData != null && existingAssetData.getAssetId() != this.updateAssetTransactionData.getAssetId())
				return ValidationResult.ASSET_ALREADY_EXISTS;
		}

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Asset
		Asset asset = new Asset(this.repository, this.updateAssetTransactionData.getAssetId());
		asset.update(this.updateAssetTransactionData);

		// Save this transaction, with updated "name reference" to previous transaction that updated name
		this.repository.getTransactionRepository().save(this.updateAssetTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert asset
		Asset asset = new Asset(this.repository, this.updateAssetTransactionData.getAssetId());
		asset.revert(this.updateAssetTransactionData);

		// Save this transaction, with removed "name reference" to previous transaction that updated name
		this.repository.getTransactionRepository().save(this.updateAssetTransactionData);
	}

}
