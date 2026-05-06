package org.qortal.data.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.crosschain.AcctMode;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

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

	@Schema(description = "AT's current native asset balance")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long nativeBalance;

	@Schema(description = "HASH160 of 32-byte secret-A")
	public byte[] hashOfSecretA;

	@Schema(description = "HASH160 of 32-byte secret-B")
	public byte[] hashOfSecretB;

	@Schema(description = "Final native asset payment that will be sent to local-chain trade partner")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long nativeAmount;

	@Schema(description = "Trade partner's local-chain address (trade begins when this is set)")
	public String partnerAddress;

	@Schema(description = "Timestamp when AT switched to trade mode")
	public Long tradeModeTimestamp;

	@Schema(description = "How long from AT creation until AT triggers automatic refund to AT creator (minutes)")
	public Integer refundTimeout;

	@Schema(description = "Actual local-chain block height when AT will automatically refund to AT creator (after trade begins)")
	public Integer tradeRefundHeight;

	@Schema(description = "Amount, in foreign blockchain currency, that AT creator expects trade partner to pay out (excluding miner fees)")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long expectedForeignAmount;

	@Schema(description = "Current AT execution mode")
	public AcctMode mode;

	@Schema(description = "Suggested P2SH-A nLockTime based on trade timeout")
	public Integer lockTimeA;

	@Schema(description = "Suggested P2SH-B nLockTime based on trade timeout")
	public Integer lockTimeB;

	@Schema(description = "Trade partner's foreign blockchain public-key-hash (PKH)")
	public byte[] partnerForeignPKH;

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

}
