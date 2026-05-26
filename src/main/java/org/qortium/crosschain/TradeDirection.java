package org.qortium.crosschain;

public enum TradeDirection {
	/** Maker escrows local-chain asset in the AT; taker pays foreign-chain funds. */
	SELL_LOCAL,

	/** Maker escrows foreign-chain funds; taker pays local-chain asset into the AT. */
	SELL_FOREIGN,

	/** Maker escrows one foreign-chain currency; taker pays another foreign-chain currency. */
	SELL_FOREIGN_FOR_FOREIGN
}
