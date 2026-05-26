package org.qortium.controller.tradebot;

import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.List;

public interface AcctTradeBot {

	public enum ResponseResult { OK, BALANCE_ISSUE, NETWORK_ISSUE, TRADE_ALREADY_EXISTS, INVALID_CRITERIA }

	/** Returns list of state names for trade-bot entries that have ended, e.g. redeemed, refunded or cancelled. */
	public List<String> getEndStates();

	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException;

	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct,
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) throws DataException;

	public boolean canDelete(Repository repository, TradeBotData tradeBotData) throws DataException;

	public void progress(Repository repository, TradeBotData tradeBotData) throws DataException, ForeignBlockchainException;

}
