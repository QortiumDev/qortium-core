package org.qortal.api.model;

import org.qortal.crosschain.TradeDirection;
import org.qortal.data.crosschain.CrossChainTradeData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeSummary {

	private long tradeTimestamp;

	private TradeDirection tradeDirection;

	private long localAssetId;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long localAmount;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long foreignAmount;

	private String atAddress;

	private String sellerAddress;

	private String buyerReceivingAddress;

	protected CrossChainTradeSummary() {
		/* For JAXB */
	}

	public CrossChainTradeSummary(CrossChainTradeData crossChainTradeData, long timestamp) {
		this.tradeTimestamp = timestamp;
		this.tradeDirection = crossChainTradeData.tradeDirection;
		this.localAssetId = crossChainTradeData.localAssetId;
		this.localAmount = crossChainTradeData.localAmount;
		this.foreignAmount = crossChainTradeData.expectedForeignAmount;
		this.sellerAddress = crossChainTradeData.creatorAddress;
		this.buyerReceivingAddress = crossChainTradeData.partnerReceivingAddress;
		this.atAddress = crossChainTradeData.atAddress;
	}

	public long getTradeTimestamp() {
		return this.tradeTimestamp;
	}

	public TradeDirection getTradeDirection() {
		return this.tradeDirection;
	}

	public long getLocalAssetId() {
		return this.localAssetId;
	}

	public long getLocalAmount() {
		return this.localAmount;
	}

	public long getForeignAmount() { return this.foreignAmount; }

	public String getAtAddress() { return this.atAddress; }

	public String getSellerAddress() { return this.sellerAddress; }

	public String getBuyerReceivingAddressAddress() { return this.buyerReceivingAddress; }
}
