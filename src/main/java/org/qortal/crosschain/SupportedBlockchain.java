package org.qortal.crosschain;

import org.qortal.utils.ByteArray;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Supplier;

public enum SupportedBlockchain {

	BITCOIN,
	LITECOIN,
	DOGECOIN,
	DIGIBYTE,
	RAVENCOIN,
	PIRATECHAIN;

	private ForeignBlockchainRegistry.Entry entry() {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(this.name());
		if (entry == null)
			throw new IllegalStateException("No foreign blockchain registry entry for " + this.name());

		return entry;
	}

	public ForeignBlockchain getInstance() {
		return this.entry().getInstance();
	}

	public ACCT getLatestAcct() {
		return this.entry().getLatestAcct();
	}

	public int getForeignBlockchainId() {
		return this.entry().getForeignBlockchainId();
	}

	public String getCurrencyCode() {
		return this.entry().getCurrencyCode();
	}

	public boolean isBitcoiny() {
		return this.entry().isBitcoiny();
	}

	public BitcoinyChainSpec getBitcoinySpec() {
		return this.entry().getBitcoinySpec();
	}

	public Bitcoiny getBitcoinyInstance() {
		return this.entry().getBitcoinyInstance();
	}

	public void resetForTesting() {
		this.entry().resetForTesting();
	}

	public static EnumSet<SupportedBlockchain> bitcoinyBlockchains() {
		return ForeignBlockchainRegistry.bitcoinyFacades();
	}

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return ForeignBlockchainRegistry.getAcctMap();
	}

	public static SupportedBlockchain fromString(String name) {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(name);
		return entry == null ? null : entry.getFacade();
	}

	public static SupportedBlockchain fromRegisteredBitcoinyString(String name) {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromRegisteredBitcoinyString(name);
		return entry == null ? null : entry.getFacade();
	}

	public static Bitcoiny getRegisteredBitcoinyInstance(String name) {
		return ForeignBlockchainRegistry.getRegisteredBitcoinyInstance(name);
	}

	public static Bitcoiny getBitcoinyInstance(String name) {
		return ForeignBlockchainRegistry.getBitcoinyInstance(name);
	}

	public static SupportedBlockchain fromForeignBlockchainId(int foreignBlockchainId) {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromForeignBlockchainId(foreignBlockchainId);
		return entry == null ? null : entry.getFacade();
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(SupportedBlockchain blockchain) {
		return ForeignBlockchainRegistry.getFilteredAcctMap(blockchain);
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(String specificBlockchain) {
		return ForeignBlockchainRegistry.getFilteredAcctMap(specificBlockchain);
	}

	public static ACCT getAcctByCodeHash(byte[] codeHash) {
		return ForeignBlockchainRegistry.getAcctByCodeHash(codeHash);
	}

	public static ACCT getAcctByName(String acctName) {
		return ForeignBlockchainRegistry.getAcctByName(acctName);
	}

}
