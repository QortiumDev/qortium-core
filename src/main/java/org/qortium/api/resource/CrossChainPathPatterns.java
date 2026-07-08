package org.qortium.api.resource;

final class CrossChainPathPatterns {

	static final String BITCOINY_BLOCKCHAIN_SEGMENT = "(?i:(?!(?:blockchains|tradeoffers|tradeoffer|trades|trade|signedfees|unsignedfees|ledger|price|p2sh|txactivity|htlc|tradebot|arrr)$)[A-Za-z0-9]+)";

	private CrossChainPathPatterns() {
	}
}
