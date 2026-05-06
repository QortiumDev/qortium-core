package org.qortal.crosschain;

import org.qortal.settings.Settings;
import org.qortal.utils.ByteArray;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Supplier;

public enum SupportedBlockchain {

	BITCOIN(BitcoinyChainSpecs.BITCOIN),
	LITECOIN(BitcoinyChainSpecs.LITECOIN),
	DOGECOIN(BitcoinyChainSpecs.DOGECOIN),
	DIGIBYTE(BitcoinyChainSpecs.DIGIBYTE),
	RAVENCOIN(BitcoinyChainSpecs.RAVENCOIN),
	PIRATECHAIN(6, PirateChain.CURRENCY_CODE, PirateChain::getInstance, false);

	private final int foreignBlockchainId;
	private final String currencyCode;
	private final Supplier<ForeignBlockchain> instanceSupplier;
	private final boolean bitcoiny;
	private final BitcoinyChainSpec bitcoinySpec;
	private final BitcoinyChainDefinition<? extends Bitcoiny> bitcoinyDefinition;

	SupportedBlockchain(int foreignBlockchainId, String currencyCode, Supplier<ForeignBlockchain> instanceSupplier, boolean bitcoiny) {
		this.foreignBlockchainId = foreignBlockchainId;
		this.currencyCode = currencyCode;
		this.instanceSupplier = instanceSupplier;
		this.bitcoiny = bitcoiny;
		this.bitcoinySpec = null;
		this.bitcoinyDefinition = null;
	}

	SupportedBlockchain(BitcoinyChainSpec spec) {
		this.foreignBlockchainId = spec.getForeignBlockchainId();
		this.currencyCode = spec.getCurrencyCode();
		this.bitcoiny = true;
		this.bitcoinySpec = spec;
		this.bitcoinyDefinition = new BitcoinyChainDefinition<>(
				spec.getConfig(),
				() -> Settings.getInstance().getBitcoinyNetwork(spec.getCurrencyCode()),
				(config, network) -> new RegisteredBitcoiny(spec, network));
		this.instanceSupplier = this.bitcoinyDefinition::getInstance;
	}

	public ForeignBlockchain getInstance() {
		return this.instanceSupplier.get();
	}

	public ACCT getLatestAcct() {
		return this.bitcoiny ? BitcoinyACCTv3.getInstance() : PirateChainACCTv3.getInstance();
	}

	public int getForeignBlockchainId() {
		return this.foreignBlockchainId;
	}

	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public boolean isBitcoiny() {
		return this.bitcoiny;
	}

	public BitcoinyChainSpec getBitcoinySpec() {
		return this.bitcoinySpec;
	}

	public Bitcoiny getBitcoinyInstance() {
		ForeignBlockchain foreignBlockchain = this.getInstance();
		return foreignBlockchain instanceof Bitcoiny ? (Bitcoiny) foreignBlockchain : null;
	}

	public void resetForTesting() {
		if (this.bitcoinyDefinition != null)
			this.bitcoinyDefinition.resetForTesting();
		else if (this == PIRATECHAIN)
			PirateChain.resetForTesting();
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
