package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.crosschain.AcctMode;
import org.qortal.data.crosschain.CrossChainTradeData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainOfferSummary {

	// Properties

	@Schema(description = "AT's local-chain address")
	private String atAddress;

	@Schema(description = "AT creator's local-chain address")
	private String creatorAddress;

	@Schema(description = "AT creator's ephemeral trading key-pair represented as local-chain address")
	private String creatorTradeAddress;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long nativeAmount;

	@Schema(description = "Bitcoin amount - DEPRECATED: use foreignAmount")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	@Deprecated
	private long btcAmount;

	@Schema(description = "Foreign blockchain amount")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long foreignAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	private int tradeTimeout;

	@Schema(description = "Current AT execution mode")
	private AcctMode mode;

	private long timestamp;

	@Schema(description = "Trade partner's local-chain receiving address")
	private String partnerReceivingAddress;

	private String foreignBlockchain;

	private String acctName;

	protected CrossChainOfferSummary() {
		/* For JAXB */
	}

	public CrossChainOfferSummary(CrossChainTradeData crossChainTradeData, long timestamp) {
		this.atAddress = crossChainTradeData.atAddress;
		this.creatorAddress = crossChainTradeData.creatorAddress;
		this.creatorTradeAddress = crossChainTradeData.creatorTradeAddress;
		this.nativeAmount = crossChainTradeData.nativeAmount;
		this.foreignAmount = crossChainTradeData.expectedForeignAmount;
		this.btcAmount = this.foreignAmount; // Duplicate for deprecated field
		this.tradeTimeout = crossChainTradeData.tradeTimeout;
		this.mode = crossChainTradeData.mode;
		this.timestamp = timestamp;
		this.partnerReceivingAddress = crossChainTradeData.partnerReceivingAddress;
		this.foreignBlockchain = crossChainTradeData.foreignBlockchain;
		this.acctName = crossChainTradeData.acctName;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public String getCreatorAddress() {
		return this.creatorAddress;
	}

	public String getCreatorTradeAddress() {
		return this.creatorTradeAddress;
	}

	public long getNativeAmount() {
		return this.nativeAmount;
	}

	public long getBtcAmount() {
		return this.btcAmount;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public int getTradeTimeout() {
		return this.tradeTimeout;
	}

	public AcctMode getMode() {
		return this.mode;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public String getPartnerReceivingAddress() {
		return this.partnerReceivingAddress;
	}

	public String getForeignBlockchain() {
		return this.foreignBlockchain;
	}

	public String getAcctName() {
		return this.acctName;
	}

	// For debugging mostly

	public String toString() {
		return String.format("%s: %s", this.atAddress, this.mode);
	}

}
