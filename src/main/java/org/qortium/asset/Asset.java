package org.qortium.asset;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortium.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.SellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateAssetTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;

public class Asset {

	/**
	 * Native chain asset, with fixed asset ID zero.
	 */
	public static final long NATIVE = 0L;

	// Other useful constants

	public static final int MIN_NAME_SIZE = 3;
	public static final int MAX_NAME_SIZE = 40;
	public static final int MAX_DESCRIPTION_SIZE = 4000;
	public static final int MAX_DATA_SIZE = 400000;

	public static final long MAX_QUANTITY = 10_000_000_000L * Amounts.MULTIPLIER; // but also to 8 decimal places

	// Properties
	private Repository repository;
	private AssetData assetData;

	// Constructors

	public Asset(Repository repository, AssetData assetData) {
		this.repository = repository;
		this.assetData = assetData;
	}

	public Asset(Repository repository, IssueAssetTransactionData issueAssetTransactionData) {
		this.repository = repository;

		String ownerAddress = Crypto.toAddress(issueAssetTransactionData.getCreatorPublicKey());

		// NOTE: transaction's reference is used to look up newly assigned assetID on creation!
		this.assetData = new AssetData(ownerAddress, issueAssetTransactionData.getAssetName(),
				issueAssetTransactionData.getDescription(), issueAssetTransactionData.getQuantity(),
				issueAssetTransactionData.isDivisible(), issueAssetTransactionData.getData(),
				issueAssetTransactionData.isUnspendable(), issueAssetTransactionData.getTxGroupId(),
				issueAssetTransactionData.getSignature(), issueAssetTransactionData.getReducedAssetName());
	}

	public Asset(Repository repository, long assetId) throws DataException {
		this.repository = repository;
		this.assetData = this.repository.getAssetRepository().fromAssetId(assetId);
	}

	// Getters/setters

	public AssetData getAssetData() {
		return this.assetData;
	}

	// Processing

	public void issue() throws DataException {
		this.repository.getAssetRepository().save(this.assetData);
	}

	public void deissue() throws DataException {
		this.repository.getAssetRepository().delete(this.assetData.getAssetId());
	}

	public void update(UpdateAssetTransactionData updateAssetTransactionData) throws DataException {
		// Update reference in transaction data
		updateAssetTransactionData.setOrphanReference(this.assetData.getReference());

		// New reference is this transaction's signature
		this.assetData.setReference(updateAssetTransactionData.getSignature());

		// Update asset's mutable metadata
		if (!updateAssetTransactionData.getNewName().isEmpty()) {
			this.assetData.setName(updateAssetTransactionData.getNewName());
			this.assetData.setReducedAssetName(updateAssetTransactionData.getReducedNewName());
		}

		if (!updateAssetTransactionData.getNewDescription().isEmpty())
			this.assetData.setDescription(updateAssetTransactionData.getNewDescription());

		if (!updateAssetTransactionData.getNewData().isEmpty())
			this.assetData.setData(updateAssetTransactionData.getNewData());

		// Save updated asset
		this.repository.getAssetRepository().save(this.assetData);
	}

	public void revert(UpdateAssetTransactionData updateAssetTransactionData) throws DataException {
		// Previous asset reference is taken from this transaction's cached copy
		this.assetData.setReference(updateAssetTransactionData.getOrphanReference());

		/*
		 * It's possible the previous transaction might be an UPDATE_ASSET that didn't change
		 * name/description/data fields and so we have to keep going back until we find an actual value,
		 * even to the original ISSUE_ASSET transaction if necessary.
		 *
		 * So we need to keep track of whether we still need
		 * a previous name, description and/or data so we can stop looking.
		 */
		boolean needName = !updateAssetTransactionData.getNewName().isEmpty();
		boolean needDescription = !updateAssetTransactionData.getNewDescription().isEmpty();
		boolean needData = !updateAssetTransactionData.getNewData().isEmpty();

		byte[] previousTransactionSignature = this.assetData.getReference();

		do {
			// Previous name, description and/or data taken from referenced transaction
			TransactionData previousTransactionData = this.repository.getTransactionRepository()
					.fromSignature(previousTransactionSignature);

			if (previousTransactionData == null)
				throw new IllegalStateException("Missing referenced transaction when orphaning UPDATE_ASSET");

			switch (previousTransactionData.getType()) {
				case ISSUE_ASSET: {
					IssueAssetTransactionData previousIssueAssetTransactionData = (IssueAssetTransactionData) previousTransactionData;

					if (needName) {
						this.assetData.setName(previousIssueAssetTransactionData.getAssetName());
						this.assetData.setReducedAssetName(previousIssueAssetTransactionData.getReducedAssetName());
						needName = false;
					}

					if (needDescription) {
						this.assetData.setDescription(previousIssueAssetTransactionData.getDescription());
						needDescription = false;
					}

					if (needData) {
						this.assetData.setData(previousIssueAssetTransactionData.getData());
						needData = false;
					}
					break;
				}

				case UPDATE_ASSET: {
					UpdateAssetTransactionData previousUpdateAssetTransactionData = (UpdateAssetTransactionData) previousTransactionData;

					if (needName && !previousUpdateAssetTransactionData.getNewName().isEmpty()) {
						this.assetData.setName(previousUpdateAssetTransactionData.getNewName());
						this.assetData.setReducedAssetName(previousUpdateAssetTransactionData.getReducedNewName());
						needName = false;
					}

					if (needDescription && !previousUpdateAssetTransactionData.getNewDescription().isEmpty()) {
						this.assetData.setDescription(previousUpdateAssetTransactionData.getNewDescription());
						needDescription = false;
					}

					if (needData && !previousUpdateAssetTransactionData.getNewData().isEmpty()) {
						this.assetData.setData(previousUpdateAssetTransactionData.getNewData());
						needData = false;
					}

					// Get signature for previous transaction in chain, just in case we need it
					if (needName || needDescription || needData)
						previousTransactionSignature = previousUpdateAssetTransactionData.getOrphanReference();

					break;
				}

				case BUY_ASSET_OWNERSHIP: {
					BuyAssetOwnershipTransactionData previousBuyAssetOwnershipTransactionData = (BuyAssetOwnershipTransactionData) previousTransactionData;
					previousTransactionSignature = previousBuyAssetOwnershipTransactionData.getAssetReference();
					break;
				}

				default:
					throw new IllegalStateException("Invalid referenced transaction when orphaning UPDATE_ASSET");
			}

		} while (needName || needDescription || needData);

		// Save reverted asset
		this.repository.getAssetRepository().save(this.assetData);

		// Remove reference to previous asset-changing transaction
		updateAssetTransactionData.setOrphanReference(null);
	}

	public void sellOwnership(SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData) throws DataException {
		this.assetData.setIsOwnerForSale(true);
		this.assetData.setOwnerSalePrice(sellAssetOwnershipTransactionData.getAmount());
		this.assetData.setOwnerSaleRecipient(sellAssetOwnershipTransactionData.getRecipient());

		this.repository.getAssetRepository().save(this.assetData);
	}

	public void unsellOwnership(SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData) throws DataException {
		this.assetData.setIsOwnerForSale(false);
		this.assetData.setOwnerSalePrice(null);
		this.assetData.setOwnerSaleRecipient(null);

		this.repository.getAssetRepository().save(this.assetData);
	}

	public void cancelSellOwnership(CancelSellAssetOwnershipTransactionData cancelSellAssetOwnershipTransactionData) throws DataException {
		cancelSellAssetOwnershipTransactionData.setSalePrice(this.assetData.getOwnerSalePrice());
		cancelSellAssetOwnershipTransactionData.setSaleRecipient(this.assetData.getOwnerSaleRecipient());

		this.assetData.setIsOwnerForSale(false);
		this.assetData.setOwnerSalePrice(null);
		this.assetData.setOwnerSaleRecipient(null);

		this.repository.getAssetRepository().save(this.assetData);
	}

	public void uncancelSellOwnership(CancelSellAssetOwnershipTransactionData cancelSellAssetOwnershipTransactionData) throws DataException {
		this.assetData.setIsOwnerForSale(true);
		this.assetData.setOwnerSalePrice(cancelSellAssetOwnershipTransactionData.getSalePrice());
		this.assetData.setOwnerSaleRecipient(cancelSellAssetOwnershipTransactionData.getSaleRecipient());

		this.repository.getAssetRepository().save(this.assetData);
	}

	public void buyOwnership(BuyAssetOwnershipTransactionData buyAssetOwnershipTransactionData, boolean modifyBalances) throws DataException {
		buyAssetOwnershipTransactionData.setAssetReference(this.assetData.getReference());
		buyAssetOwnershipTransactionData.setSaleRecipient(this.assetData.getOwnerSaleRecipient());

		this.assetData.setIsOwnerForSale(false);
		this.assetData.setOwnerSalePrice(null);
		this.assetData.setOwnerSaleRecipient(null);

		if (modifyBalances && buyAssetOwnershipTransactionData.getAmount() != 0) {
			Account seller = new Account(this.repository, this.assetData.getOwner());
			seller.modifyAssetBalance(Asset.NATIVE, buyAssetOwnershipTransactionData.getAmount());
		}

		Account buyer = new PublicKeyAccount(this.repository, buyAssetOwnershipTransactionData.getBuyerPublicKey());
		this.assetData.setOwner(buyer.getAddress());

		if (modifyBalances && buyAssetOwnershipTransactionData.getAmount() != 0)
			buyer.modifyAssetBalance(Asset.NATIVE, -buyAssetOwnershipTransactionData.getAmount());

		this.assetData.setReference(buyAssetOwnershipTransactionData.getSignature());

		this.repository.getAssetRepository().save(this.assetData);
	}

	public void unbuyOwnership(BuyAssetOwnershipTransactionData buyAssetOwnershipTransactionData) throws DataException {
		this.assetData.setIsOwnerForSale(true);
		this.assetData.setOwnerSalePrice(buyAssetOwnershipTransactionData.getAmount());
		this.assetData.setOwnerSaleRecipient(buyAssetOwnershipTransactionData.getSaleRecipient());

		this.assetData.setReference(buyAssetOwnershipTransactionData.getAssetReference());
		this.assetData.setOwner(buyAssetOwnershipTransactionData.getSeller());

		this.repository.getAssetRepository().save(this.assetData);

		if (buyAssetOwnershipTransactionData.getAmount() != 0) {
			Account buyer = new PublicKeyAccount(this.repository, buyAssetOwnershipTransactionData.getBuyerPublicKey());
			buyer.modifyAssetBalance(Asset.NATIVE, buyAssetOwnershipTransactionData.getAmount());

			Account seller = new Account(this.repository, buyAssetOwnershipTransactionData.getSeller());
			seller.modifyAssetBalance(Asset.NATIVE, -buyAssetOwnershipTransactionData.getAmount());
		}

		buyAssetOwnershipTransactionData.setAssetReference(null);
		buyAssetOwnershipTransactionData.setSaleRecipient(null);
	}

}
