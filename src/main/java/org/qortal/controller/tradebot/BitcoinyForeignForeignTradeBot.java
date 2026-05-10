package org.qortal.controller.tradebot;

import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.crosschain.ACCT;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class BitcoinyForeignForeignTradeBot implements AcctTradeBot {

	private static BitcoinyForeignForeignTradeBot instance;

	private final List<String> endStates = Arrays.asList(TradeStates.State.MAKER_DONE, TradeStates.State.MAKER_REFUNDED,
			TradeStates.State.TAKER_DONE, TradeStates.State.TAKER_REFUNDED).stream()
			.map(TradeStates.State::name)
			.collect(Collectors.toUnmodifiableList());

	private BitcoinyForeignForeignTradeBot() {
	}

	public static synchronized BitcoinyForeignForeignTradeBot getInstance() {
		if (instance == null)
			instance = new BitcoinyForeignForeignTradeBot();

		return instance;
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	@Override
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) {
		return null;
	}

	@Override
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct,
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) {
		return ResponseResult.INVALID_CRITERIA;
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) {
		return true;
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) {
	}

}
