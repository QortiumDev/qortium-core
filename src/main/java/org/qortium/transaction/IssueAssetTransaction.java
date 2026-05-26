package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;
import org.qortium.utils.Groups;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class IssueAssetTransaction extends Transaction {

	// Properties

	private IssueAssetTransactionData issueAssetTransactionData;

	// Constructors

	public IssueAssetTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.issueAssetTransactionData = (IssueAssetTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getIssuer() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check name size bounds
		String assetName = this.issueAssetTransactionData.getAssetName();
		int assetNameLength = Utf8.encodedLength(assetName);
		if (assetNameLength < Asset.MIN_NAME_SIZE || assetNameLength > Asset.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is in normalized form (no leading/trailing whitespace, etc.)
		if (!assetName.equals(Unicode.normalize(assetName)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		// Check description size bounds
		int assetDescriptionlength = Utf8.encodedLength(this.issueAssetTransactionData.getDescription());
		if (assetDescriptionlength < 1 || assetDescriptionlength > Asset.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check data field
		String data = this.issueAssetTransactionData.getData();
		int dataLength = Utf8.encodedLength(data);
		if (data == null || dataLength < 1 || dataLength > Asset.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		long quantity = this.issueAssetTransactionData.getQuantity();
		boolean nativeAssetExists = this.repository.getAssetRepository().assetExists(Asset.NATIVE);
		boolean isNativeBootstrap = this.isNativeBootstrap();

		ValidationResult requestedAssetIdResult = this.validateRequestedAssetId(nativeAssetExists);
		if (requestedAssetIdResult != ValidationResult.OK)
			return requestedAssetIdResult;

		// Check quantity. The native asset may bootstrap with zero initial supply.
		if (quantity < 0 || quantity > Asset.MAX_QUANTITY)
			return ValidationResult.INVALID_QUANTITY;

		if (quantity == 0 && !isNativeBootstrap)
			return ValidationResult.INVALID_QUANTITY;

		// Check quantity versus indivisibility
		if (!this.issueAssetTransactionData.isDivisible() && quantity % Amounts.MULTIPLIER != 0)
			return ValidationResult.INVALID_QUANTITY;

		Account issuer = getIssuer();

		// Check issuer has enough funds
		if (issuer.getConfirmedBalance(Asset.NATIVE) < this.issueAssetTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		// Re-check native bootstrap at approval time, in case asset 0 was issued while this transaction was pending.
		if (this.isNativeBootstrap() && this.repository.getAssetRepository().assetExists(Asset.NATIVE))
			return ValidationResult.ASSET_ALREADY_EXISTS;

		// Check the name isn't already taken
		if (this.repository.getAssetRepository().reducedAssetNameExists(this.issueAssetTransactionData.getReducedAssetName()))
			return ValidationResult.ASSET_ALREADY_EXISTS;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Issue asset
		Asset asset = new Asset(this.repository, this.issueAssetTransactionData);
		if (this.isNativeBootstrap())
			asset.getAssetData().setAssetId(Asset.NATIVE);

		asset.issue();

		// Add asset to issuer
		Account issuer = this.getIssuer();
		issuer.setConfirmedBalance(asset.getAssetData().getAssetId(), this.issueAssetTransactionData.getQuantity());

		// Note newly assigned asset ID in our transaction record
		this.issueAssetTransactionData.setAssetId(asset.getAssetData().getAssetId());

		// Save this transaction with newly assigned assetId
		this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Remove asset from issuer
		Account issuer = this.getIssuer();
		issuer.deleteBalance(this.issueAssetTransactionData.getAssetId());

		// Deissue asset
		Asset asset = new Asset(this.repository, this.issueAssetTransactionData.getAssetId());
		asset.deissue();

		// Remove assigned asset ID from transaction info
		this.issueAssetTransactionData.setAssetId(null);

		// Save this transaction, with removed assetId
		this.repository.getTransactionRepository().save(this.issueAssetTransactionData);
	}

	private boolean isNativeBootstrap() {
		Long requestedAssetId = this.issueAssetTransactionData.getRequestedAssetId();
		return requestedAssetId != null && requestedAssetId == Asset.NATIVE;
	}

	private ValidationResult validateRequestedAssetId(boolean nativeAssetExists) throws DataException {
		Long requestedAssetId = this.issueAssetTransactionData.getRequestedAssetId();
		if (requestedAssetId == null)
			return ValidationResult.OK;

		if (requestedAssetId != Asset.NATIVE)
			return ValidationResult.ASSET_DOES_NOT_EXIST;

		if (nativeAssetExists)
			return ValidationResult.ASSET_ALREADY_EXISTS;

		// Genesis configs can seed assets explicitly; runtime no-native bootstrap is development-group gated.
		int blockchainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		if (blockchainHeight == 0)
			return ValidationResult.OK;

		int txGroupId = this.issueAssetTransactionData.getTxGroupId();
		if (txGroupId == Group.NO_GROUP)
			return ValidationResult.INVALID_TX_GROUP_ID;

		int targetBlockHeight = blockchainHeight + 1;
		List<Integer> devGroupIds = Groups.getGroupIdsAtHeight(BlockChain.getInstance().getDevGroupIds(), targetBlockHeight);
		return devGroupIds.contains(txGroupId) ? ValidationResult.OK : ValidationResult.INVALID_TX_GROUP_ID;
	}

}
