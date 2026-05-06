package org.qortal.api.model;

import org.qortal.data.crosschain.CrossChainTradeData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeSummary {

	private long tradeTimestamp;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long nativeAmount;

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
		this.nativeAmount = crossChainTradeData.nativeAmount;
		this.foreignAmount = crossChainTradeData.expectedForeignAmount;
		this.sellerAddress = crossChainTradeData.creatorAddress;
		this.buyerReceivingAddress = crossChainTradeData.partnerReceivingAddress;
		this.atAddress = crossChainTradeData.atAddress;
	}

	public long getTradeTimestamp() {
		return this.tradeTimestamp;
	}

	public long getNativeAmount() {
		return this.nativeAmount;
	}

	public long getForeignAmount() { return this.foreignAmount; }

	public String getAtAddress() { return this.atAddress; }

	public String getSellerAddress() { return this.sellerAddress; }

	public String getBuyerReceivingAddressAddress() { return this.buyerReceivingAddress; }
}
