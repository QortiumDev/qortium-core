package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.TradeDirection;
import org.qortium.data.crosschain.CrossChainTradeData;

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

	@Schema(description = "Trade direction from the maker's viewpoint")
	private TradeDirection tradeDirection;

	@Schema(description = "Local-chain asset id paid by this offer")
	private long localAssetId;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long localAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long totalLocalAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long remainingLocalAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long activeLocalAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long completedLocalAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long minFillLocalAmount;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long maxFillLocalAmount;

	private int activeFillCount;
	private int availableFillSlots;

	@Schema(description = "Foreign blockchain amount")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long foreignAmount;

	@Schema(description = "Foreign blockchain being sold by maker for foreign/foreign offers")
	private String offeredForeignBlockchain;

	@Schema(description = "Foreign blockchain amount being sold by maker for foreign/foreign offers")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long offeredForeignAmount;

	@Schema(description = "Foreign blockchain requested by maker for foreign/foreign offers")
	private String requestedForeignBlockchain;

	@Schema(description = "Foreign blockchain amount requested by maker for foreign/foreign offers")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long requestedForeignAmount;

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
		this.tradeDirection = crossChainTradeData.tradeDirection;
		this.localAssetId = crossChainTradeData.localAssetId;
		this.localAmount = crossChainTradeData.localAmount;
		this.totalLocalAmount = crossChainTradeData.totalLocalAmount;
		this.remainingLocalAmount = crossChainTradeData.remainingLocalAmount;
		this.activeLocalAmount = crossChainTradeData.activeLocalAmount;
		this.completedLocalAmount = crossChainTradeData.completedLocalAmount;
		this.minFillLocalAmount = crossChainTradeData.minFillLocalAmount;
		this.maxFillLocalAmount = crossChainTradeData.maxFillLocalAmount;
		this.activeFillCount = crossChainTradeData.activeFillCount;
		this.availableFillSlots = crossChainTradeData.availableFillSlots;
		this.foreignAmount = crossChainTradeData.expectedForeignAmount;
		this.offeredForeignBlockchain = crossChainTradeData.offeredForeignBlockchain;
		this.offeredForeignAmount = crossChainTradeData.offeredForeignAmount;
		this.requestedForeignBlockchain = crossChainTradeData.requestedForeignBlockchain;
		this.requestedForeignAmount = crossChainTradeData.requestedForeignAmount;
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

	public TradeDirection getTradeDirection() {
		return this.tradeDirection;
	}

	public long getLocalAssetId() {
		return this.localAssetId;
	}

	public long getLocalAmount() {
		return this.localAmount;
	}

	public long getTotalLocalAmount() {
		return this.totalLocalAmount;
	}

	public long getRemainingLocalAmount() {
		return this.remainingLocalAmount;
	}

	public long getActiveLocalAmount() {
		return this.activeLocalAmount;
	}

	public long getCompletedLocalAmount() {
		return this.completedLocalAmount;
	}

	public long getMinFillLocalAmount() {
		return this.minFillLocalAmount;
	}

	public long getMaxFillLocalAmount() {
		return this.maxFillLocalAmount;
	}

	public int getActiveFillCount() {
		return this.activeFillCount;
	}

	public int getAvailableFillSlots() {
		return this.availableFillSlots;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public String getOfferedForeignBlockchain() {
		return this.offeredForeignBlockchain;
	}

	public long getOfferedForeignAmount() {
		return this.offeredForeignAmount;
	}

	public String getRequestedForeignBlockchain() {
		return this.requestedForeignBlockchain;
	}

	public long getRequestedForeignAmount() {
		return this.requestedForeignAmount;
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
