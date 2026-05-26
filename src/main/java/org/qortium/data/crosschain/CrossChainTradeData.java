package org.qortium.data.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.TradeDirection;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.ArrayList;
import java.util.List;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainTradeData {

	// Properties

	@Schema(description = "AT's local-chain address")
	public String atAddress;

	@Schema(description = "AT creator's local-chain address")
	public String creatorAddress;

	@Schema(description = "AT creator's ephemeral trading key-pair represented as local-chain address")
	public String creatorTradeAddress;

	@Schema(description = "AT creator's foreign blockchain trade public-key-hash (PKH)")
	public byte[] creatorForeignPKH;

	@Schema(description = "Timestamp when AT was created (milliseconds since epoch)")
	public long creationTimestamp;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	public int tradeTimeout;

	@Schema(description = "Trade direction from the maker's viewpoint")
	public TradeDirection tradeDirection;

	@Schema(description = "Local-chain asset id paid by this AT")
	public long localAssetId;

	@Schema(description = "AT's current local asset balance")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long localBalance;

	@Schema(description = "HASH160 of 32-byte secret-A")
	public byte[] hashOfSecretA;

	@Schema(description = "HASH160 of 32-byte secret-B")
	public byte[] hashOfSecretB;

	@Schema(description = "Final local asset payment that will be sent to local-chain trade partner")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long localAmount;

	@Schema(description = "Total local-chain asset amount originally offered")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long totalLocalAmount;

	@Schema(description = "Local-chain asset amount currently available for new fills")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long remainingLocalAmount;

	@Schema(description = "Local-chain asset amount currently locked in active fills")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long activeLocalAmount;

	@Schema(description = "Local-chain asset amount already completed by fills")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long completedLocalAmount;

	@Schema(description = "Minimum local-chain asset amount accepted for one fill")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long minFillLocalAmount;

	@Schema(description = "Maximum local-chain asset amount accepted for one fill")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long maxFillLocalAmount;

	@Schema(description = "Number of active split-fill slots")
	public int activeFillCount;

	@Schema(description = "Number of split-fill slots available for new fills")
	public int availableFillSlots;

	@Schema(description = "Active split-fill slot details")
	public List<Fill> fills = new ArrayList<>();

	@Schema(description = "Trade partner's local-chain address (trade begins when this is set)")
	public String partnerAddress;

	@Schema(description = "Timestamp when AT switched to trade mode")
	public Long tradeModeTimestamp;

	@Schema(description = "How long from AT creation until AT triggers automatic refund to AT creator (minutes)")
	public Integer refundTimeout;

	@Schema(description = "Actual local-chain block height when AT will automatically refund to AT creator (after trade begins)")
	public Integer tradeRefundHeight;

	@Schema(description = "Amount, in foreign blockchain currency, that AT creator expects trade partner to pay out (excluding miner fees)")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long expectedForeignAmount;

	@Schema(description = "Foreign blockchain being sold by maker for foreign/foreign offers")
	public String offeredForeignBlockchain;

	@Schema(description = "Foreign blockchain amount being sold by maker for foreign/foreign offers")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long offeredForeignAmount;

	@Schema(description = "Foreign blockchain requested by maker for foreign/foreign offers")
	public String requestedForeignBlockchain;

	@Schema(description = "Foreign blockchain amount requested by maker for foreign/foreign offers")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long requestedForeignAmount;

	@Schema(description = "Current AT execution mode")
	public AcctMode mode;

	@Schema(description = "Suggested P2SH-A nLockTime based on trade timeout")
	public Integer lockTimeA;

	@Schema(description = "Suggested P2SH-B nLockTime based on trade timeout")
	public Integer lockTimeB;

	@Schema(description = "Trade partner's foreign blockchain public-key-hash (PKH)")
	public byte[] partnerForeignPKH;

	@Schema(description = "Maker's offered-chain foreign blockchain public-key-hash (PKH)")
	public byte[] creatorOfferedForeignPKH;

	@Schema(description = "Maker's requested-chain foreign blockchain public-key-hash (PKH)")
	public byte[] creatorRequestedForeignPKH;

	@Schema(description = "Trade partner's offered-chain foreign blockchain public-key-hash (PKH)")
	public byte[] partnerOfferedForeignPKH;

	@Schema(description = "Trade partner's requested-chain foreign blockchain public-key-hash (PKH)")
	public byte[] partnerRequestedForeignPKH;

	@Schema(description = "Trade partner's local-chain receiving address")
	public String partnerReceivingAddress;

	public String foreignBlockchain;

	public String acctName;

	@Schema(description = "Timestamp when AT creator's trade-bot presence expires")
	public Long creatorPresenceExpiry;

	@Schema(description = "Timestamp when trade partner's trade-bot presence expires")
	public Long partnerPresenceExpiry;

	// Constructors

	// Necessary for JAXB
	public CrossChainTradeData() {
	}

	public boolean isFillableOffer() {
		if (this.mode != AcctMode.OFFERING)
			return false;

		if (!hasFillMetadata())
			return true;

		if (this.remainingLocalAmount <= 0 || this.availableFillSlots <= 0)
			return false;

		long maxFill = Math.min(this.maxFillLocalAmount, this.remainingLocalAmount);
		if (maxFill < this.minFillLocalAmount)
			return false;

		return this.remainingLocalAmount <= maxFill
				|| Math.min(maxFill, this.remainingLocalAmount - this.minFillLocalAmount) >= this.minFillLocalAmount;
	}

	public boolean isFillableAmount(long fillLocalAmount) {
		if (!isFillableOffer())
			return false;

		if (!hasFillMetadata())
			return fillLocalAmount > 0 && fillLocalAmount <= this.localAmount;

		long maxFill = Math.min(this.maxFillLocalAmount, this.remainingLocalAmount);
		if (fillLocalAmount < this.minFillLocalAmount || fillLocalAmount > maxFill)
			return false;

		long remainingAfterFill = this.remainingLocalAmount - fillLocalAmount;
		return remainingAfterFill == 0 || remainingAfterFill >= this.minFillLocalAmount;
	}

	private boolean hasFillMetadata() {
		return this.totalLocalAmount > 0
				|| this.remainingLocalAmount > 0
				|| this.minFillLocalAmount > 0
				|| this.maxFillLocalAmount > 0
				|| this.availableFillSlots > 0;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class Fill {
		@Schema(description = "Split-fill slot index")
		public int slotIndex;

		@Schema(description = "Trade partner's local-chain trade address")
		public String partnerAddress;

		@Schema(description = "Trade partner's foreign blockchain public-key-hash")
		public byte[] partnerForeignPKH;

		@Schema(description = "HASH160 of 32-byte secret-A for this fill")
		public byte[] hashOfSecretA;

		@Schema(description = "Local-chain asset amount locked in this fill")
		@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
		public long localAmount;

		@Schema(description = "Foreign blockchain amount expected for this fill")
		@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
		public long expectedForeignAmount;

		@Schema(description = "Suggested P2SH-A nLockTime for this fill")
		public int lockTimeA;

		@Schema(description = "Actual local-chain block height when this fill will automatically refund")
		public int tradeRefundHeight;

		public Fill() {
		}
	}

}
