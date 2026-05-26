package org.qortium.repository;

import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.crosschain.TradeBotFillData;

import java.util.List;

public interface CrossChainRepository {

	public TradeBotData getTradeBotData(byte[] tradePrivateKey) throws DataException;

	/** Returns true if there is an existing trade-bot entry relating to given AT address, excluding trade-bot entries with given states. */
	public boolean existsTradeWithAtExcludingStates(String atAddress, List<String> excludeStates) throws DataException;

	public List<TradeBotData> getAllTradeBotData() throws DataException;

	public List<TradeBotFillData> getTradeBotFillData(String atAddress) throws DataException;

	public List<TradeBotFillData> getAllTradeBotFillData() throws DataException;

	public TradeBotFillData getTradeBotFillData(String atAddress, byte[] hashOfSecret) throws DataException;

	public void save(TradeBotData tradeBotData) throws DataException;

	public void save(TradeBotFillData tradeBotFillData) throws DataException;

	/** Delete trade-bot states using passed private key. */
	public int delete(byte[] tradePrivateKey) throws DataException;

}
