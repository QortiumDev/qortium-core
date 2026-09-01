package org.qortium.api.model.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;
import java.util.Map;

@XmlAccessorType(XmlAccessType.FIELD)
public class ForeignWalletSpendContext {

	private int version;
	private String blockchain;
	private String currencyCode;
	private String activeNetwork;
	private String chainId;
	private int tipHeight;
	private boolean confirmedOnly;
	private String transactionFormat;
	private int transactionVersion;
	private int sighashType;
	private long sequence;
	private long lockTime;
	private String minimumNonDustOutput;
	private String recommendedFeePerByte;
	private Map<String, String> previousTransactions;
	private List<ForeignWalletSpendContextUtxo> utxos;

	public ForeignWalletSpendContext() {
	}

	public ForeignWalletSpendContext(int version, String blockchain, String currencyCode, String activeNetwork,
			String chainId, int tipHeight, boolean confirmedOnly, String transactionFormat, int transactionVersion,
			int sighashType, long sequence, long lockTime, String minimumNonDustOutput,
			String recommendedFeePerByte, Map<String, String> previousTransactions,
			List<ForeignWalletSpendContextUtxo> utxos) {
		this.version = version;
		this.blockchain = blockchain;
		this.currencyCode = currencyCode;
		this.activeNetwork = activeNetwork;
		this.chainId = chainId;
		this.tipHeight = tipHeight;
		this.confirmedOnly = confirmedOnly;
		this.transactionFormat = transactionFormat;
		this.transactionVersion = transactionVersion;
		this.sighashType = sighashType;
		this.sequence = sequence;
		this.lockTime = lockTime;
		this.minimumNonDustOutput = minimumNonDustOutput;
		this.recommendedFeePerByte = recommendedFeePerByte;
		this.previousTransactions = previousTransactions;
		this.utxos = utxos;
	}

	public int getVersion() { return this.version; }
	public String getBlockchain() { return this.blockchain; }
	public String getCurrencyCode() { return this.currencyCode; }
	public String getActiveNetwork() { return this.activeNetwork; }
	public String getChainId() { return this.chainId; }
	public int getTipHeight() { return this.tipHeight; }
	public boolean isConfirmedOnly() { return this.confirmedOnly; }
	public String getTransactionFormat() { return this.transactionFormat; }
	public int getTransactionVersion() { return this.transactionVersion; }
	public int getSighashType() { return this.sighashType; }
	public long getSequence() { return this.sequence; }
	public long getLockTime() { return this.lockTime; }
	public String getMinimumNonDustOutput() { return this.minimumNonDustOutput; }
	public String getRecommendedFeePerByte() { return this.recommendedFeePerByte; }
	public Map<String, String> getPreviousTransactions() { return this.previousTransactions; }
	public List<ForeignWalletSpendContextUtxo> getUtxos() { return this.utxos; }
}
