package org.qortium.asset;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.data.asset.OrderData;
import org.qortium.data.asset.TradeData;
import org.qortium.repository.AssetRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

public class Trade {

	// Properties
	private Repository repository;
	private TradeData tradeData;

	private AssetRepository assetRepository;

	private OrderData initiatingOrder;
	private OrderData targetOrder;
	private long fulfilled;

	// Constructors

	public Trade(Repository repository, TradeData tradeData) {
		this.repository = repository;
		this.tradeData = tradeData;

		this.assetRepository = this.repository.getAssetRepository();
	}

	// Processing

	private void commonPrep() throws DataException {
		this.initiatingOrder = assetRepository.fromOrderId(this.tradeData.getInitiator());
		this.targetOrder = assetRepository.fromOrderId(this.tradeData.getTarget());

		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		// "amount" and "fulfilled" are the same asset for both orders
		// which is the matchedAmount in asset with highest assetID
		this.fulfilled = initiatingOrder.getHaveAssetId() < initiatingOrder.getWantAssetId() ? this.tradeData.getTargetAmount() : this.tradeData.getInitiatorAmount();
	}

	public void process() throws DataException {
		// Save trade into repository
		assetRepository.save(tradeData);

		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		commonPrep();

		// Update corresponding Orders on both sides of trade
		initiatingOrder.setFulfilled(initiatingOrder.getFulfilled() + fulfilled);
		initiatingOrder.setIsFulfilled(Order.isFulfilled(initiatingOrder));
		// Set isClosed to true if isFulfilled now true
		initiatingOrder.setIsClosed(initiatingOrder.getIsFulfilled());
		assetRepository.save(initiatingOrder);

		targetOrder.setFulfilled(targetOrder.getFulfilled() + fulfilled);
		targetOrder.setIsFulfilled(Order.isFulfilled(targetOrder));
		// Set isClosed to true if isFulfilled now true
		targetOrder.setIsClosed(targetOrder.getIsFulfilled());
		assetRepository.save(targetOrder);

		// Actually transfer asset balances
		Account initiatingCreator = new PublicKeyAccount(this.repository, initiatingOrder.getCreatorPublicKey());
		initiatingCreator.modifyAssetBalance(initiatingOrder.getWantAssetId(), tradeData.getTargetAmount());

		Account targetCreator = new PublicKeyAccount(this.repository, targetOrder.getCreatorPublicKey());
		targetCreator.modifyAssetBalance(targetOrder.getWantAssetId(), tradeData.getInitiatorAmount());

		// Possible partial saving to refund to initiator
		long initiatorSaving = this.tradeData.getInitiatorSaving();
		if (initiatorSaving > 0)
			initiatingCreator.modifyAssetBalance(initiatingOrder.getHaveAssetId(), initiatorSaving);
	}

	public void orphan() throws DataException {
		// Note: targetAmount is amount traded FROM target order
		// Note: initiatorAmount is amount traded FROM initiating order

		commonPrep();

		// Revert corresponding Orders on both sides of trade
		initiatingOrder.setFulfilled(initiatingOrder.getFulfilled() - fulfilled);
		initiatingOrder.setIsFulfilled(Order.isFulfilled(initiatingOrder));
		// Set isClosed to false if isFulfilled now false
		initiatingOrder.setIsClosed(initiatingOrder.getIsFulfilled());
		assetRepository.save(initiatingOrder);

		targetOrder.setFulfilled(targetOrder.getFulfilled() - fulfilled);
		targetOrder.setIsFulfilled(Order.isFulfilled(targetOrder));
		// Set isClosed to false if isFulfilled now false
		targetOrder.setIsClosed(targetOrder.getIsFulfilled());
		assetRepository.save(targetOrder);

		// Reverse asset transfers
		Account initiatingCreator = new PublicKeyAccount(this.repository, initiatingOrder.getCreatorPublicKey());
		initiatingCreator.modifyAssetBalance(initiatingOrder.getWantAssetId(), - tradeData.getTargetAmount());

		Account targetCreator = new PublicKeyAccount(this.repository, targetOrder.getCreatorPublicKey());
		targetCreator.modifyAssetBalance(targetOrder.getWantAssetId(), - tradeData.getInitiatorAmount());

		// Possible partial saving to claw back from  initiator
		long initiatorSaving = this.tradeData.getInitiatorSaving();
		if (initiatorSaving > 0)
			initiatingCreator.modifyAssetBalance(initiatingOrder.getHaveAssetId(), - initiatorSaving);

		// Remove trade from repository
		assetRepository.delete(tradeData);
	}

}
