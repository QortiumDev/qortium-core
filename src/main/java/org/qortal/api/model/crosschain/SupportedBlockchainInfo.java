package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Supported cross-chain blockchain metadata")
public class SupportedBlockchainInfo {

	@Schema(description = "Canonical blockchain registry name", example = "BITCOIN")
	public String name;

	@Schema(description = "Currency code used by this blockchain", example = "BTC")
	public String currencyCode;

	@Schema(description = "Human-readable blockchain name", example = "Bitcoin")
	public String displayName;

	@Schema(description = "Cross-chain implementation family", example = "BITCOINY")
	public String type;

	@Schema(description = "Canonical API path for this blockchain", example = "/crosschain/BITCOIN")
	public String apiPath;

	@Schema(description = "Whether this node has wallet support enabled for this blockchain")
	public boolean walletEnabled;

	@Schema(description = "Configured active foreign-chain network", example = "MAIN")
	public String activeNetwork;

	@Schema(description = "Active Bitcoiny chain id when available", example = "bip122:000000000019d6689c085ae165831e93")
	public String chainId;

	@Schema(description = "SLIP-44 coin type when available", example = "0")
	public Integer slip44CoinType;

	@Schema(description = "Number of decimal places used by this blockchain's atomic units", example = "8")
	public int decimalPlaces;

	@Schema(description = "Whether Qortium has wallet APIs for this blockchain")
	public boolean supportsWallet;

	@Schema(description = "Whether Qortium supports HTLC trade flow for this blockchain")
	public boolean supportsHtlc;

	@Schema(description = "Whether this blockchain can be traded against the local chain")
	public boolean supportsLocalChainTrades;

	@Schema(description = "Whether this blockchain can be used in foreign-to-foreign trade flow")
	public boolean supportsForeignForeignTrades;

	public SupportedBlockchainInfo() {
		// For JAXB
	}

	public SupportedBlockchainInfo(String name, String currencyCode, String displayName, String type, String apiPath,
			boolean walletEnabled, String activeNetwork, String chainId, Integer slip44CoinType, int decimalPlaces,
			boolean supportsWallet, boolean supportsHtlc, boolean supportsLocalChainTrades, boolean supportsForeignForeignTrades) {
		this.name = name;
		this.currencyCode = currencyCode;
		this.displayName = displayName;
		this.type = type;
		this.apiPath = apiPath;
		this.walletEnabled = walletEnabled;
		this.activeNetwork = activeNetwork;
		this.chainId = chainId;
		this.slip44CoinType = slip44CoinType;
		this.decimalPlaces = decimalPlaces;
		this.supportsWallet = supportsWallet;
		this.supportsHtlc = supportsHtlc;
		this.supportsLocalChainTrades = supportsLocalChainTrades;
		this.supportsForeignForeignTrades = supportsForeignForeignTrades;
	}

}
