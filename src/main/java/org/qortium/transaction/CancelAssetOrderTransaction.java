package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.asset.Order;
import org.qortium.data.asset.OrderData;
import org.qortium.data.transaction.CancelAssetOrderTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.AssetRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CancelAssetOrderTransaction extends Transaction {

	// Properties
	private CancelAssetOrderTransactionData cancelOrderTransactionData;

	// Constructors

	public CancelAssetOrderTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.cancelOrderTransactionData = (CancelAssetOrderTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() {
		return Collections.emptyList();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		AssetRepository assetRepository = this.repository.getAssetRepository();

		// Check order even exists
		OrderData orderData = assetRepository.fromOrderId(this.cancelOrderTransactionData.getOrderId());

		if (orderData == null)
			return ValidationResult.ORDER_DOES_NOT_EXIST;

		if (orderData.getIsClosed())
			return ValidationResult.ORDER_ALREADY_CLOSED;

		// Check transaction creator matches order creator
		if (!Arrays.equals(this.transactionData.getCreatorPublicKey(), orderData.getCreatorPublicKey()))
			return ValidationResult.INVALID_ORDER_CREATOR;

		Account creator = getCreator();

		// Check creator has enough native asset for fee
		if (creator.getConfirmedBalance(Asset.NATIVE) < this.cancelOrderTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Mark Order as completed so no more trades can happen
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(this.cancelOrderTransactionData.getOrderId());
		Order order = new Order(this.repository, orderData);
		order.cancel();
	}

	@Override
	public void orphan() throws DataException {
		// Unmark Order as completed so trades can happen again
		OrderData orderData = this.repository.getAssetRepository().fromOrderId(this.cancelOrderTransactionData.getOrderId());
		Order order = new Order(this.repository, orderData);
		order.reopen();
	}

}
