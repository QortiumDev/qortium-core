package org.qortal.crosschain;

import org.qortal.utils.ByteArray;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public enum SupportedBlockchain {

	BITCOIN(1, Bitcoin.DEFINITION),
	LITECOIN(2, Litecoin.DEFINITION),
	DOGECOIN(3, Dogecoin.DEFINITION),
	DIGIBYTE(4, Digibyte.DEFINITION),
	RAVENCOIN(5, Ravencoin.DEFINITION),
	PIRATECHAIN(6, PirateChain.CURRENCY_CODE, PirateChain::getInstance, false);

	private static final Supplier<ACCT> BITCOINY_ACCT_SUPPLIER = BitcoinyACCTv3::getInstance;
	private static final Supplier<ACCT> PIRATECHAIN_ACCT_SUPPLIER = PirateChainACCTv3::getInstance;

	private static final Map<ByteArray, Supplier<ACCT>> bitcoinyAcctMap = Collections.singletonMap(
			ByteArray.wrap(BitcoinyACCTv3.CODE_BYTES_HASH), BITCOINY_ACCT_SUPPLIER);

	private static final Map<ByteArray, Supplier<ACCT>> pirateChainAcctMap = Collections.singletonMap(
			ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH), PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<ByteArray, Supplier<ACCT>> supportedAcctsByCodeHash = Map.of(
			ByteArray.wrap(BitcoinyACCTv3.CODE_BYTES_HASH), BITCOINY_ACCT_SUPPLIER,
			ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH), PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<String, Supplier<ACCT>> supportedAcctsByName = Map.of(
			BitcoinyACCTv3.NAME, BITCOINY_ACCT_SUPPLIER,
			PirateChainACCTv3.NAME, PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<String, SupportedBlockchain> blockchainsByName = Arrays.stream(SupportedBlockchain.values())
			.collect(Collectors.toUnmodifiableMap(Enum::name, blockchain -> blockchain));

	private static final Map<String, SupportedBlockchain> blockchainsByCurrencyCode = Arrays.stream(SupportedBlockchain.values())
			.collect(Collectors.toUnmodifiableMap(SupportedBlockchain::getCurrencyCode, blockchain -> blockchain));

	private static final Map<Integer, SupportedBlockchain> bitcoinyBlockchainsById = Arrays.stream(SupportedBlockchain.values())
			.filter(SupportedBlockchain::isBitcoiny)
			.collect(Collectors.toUnmodifiableMap(SupportedBlockchain::getForeignBlockchainId, blockchain -> blockchain));

	private final int foreignBlockchainId;
	private final String currencyCode;
	private final Supplier<ForeignBlockchain> instanceSupplier;
	private final boolean bitcoiny;

	SupportedBlockchain(int foreignBlockchainId, String currencyCode, Supplier<ForeignBlockchain> instanceSupplier, boolean bitcoiny) {
		this.foreignBlockchainId = foreignBlockchainId;
		this.currencyCode = currencyCode;
		this.instanceSupplier = instanceSupplier;
		this.bitcoiny = bitcoiny;
	}

	SupportedBlockchain(int foreignBlockchainId, BitcoinyChainDefinition<? extends Bitcoiny> definition) {
		this(foreignBlockchainId, definition.getCurrencyCode(), definition::getInstance, true);
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

	public Bitcoiny getBitcoinyInstance() {
		ForeignBlockchain foreignBlockchain = this.getInstance();
		return foreignBlockchain instanceof Bitcoiny ? (Bitcoiny) foreignBlockchain : null;
	}

	public static EnumSet<SupportedBlockchain> bitcoinyBlockchains() {
		return Arrays.stream(values())
				.filter(SupportedBlockchain::isBitcoiny)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(SupportedBlockchain.class)));
	}

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return supportedAcctsByCodeHash;
	}

	public static SupportedBlockchain fromString(String name) {
		if (name == null)
			return null;

		String normalizedName = name.toUpperCase(Locale.ROOT);

		SupportedBlockchain blockchain = blockchainsByName.get(normalizedName);
		return blockchain != null ? blockchain : blockchainsByCurrencyCode.get(normalizedName);
	}

	public static SupportedBlockchain fromForeignBlockchainId(int foreignBlockchainId) {
		return bitcoinyBlockchainsById.get(foreignBlockchainId);
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(SupportedBlockchain blockchain) {
		if (blockchain == null)
			return getAcctMap();

		return blockchain.isBitcoiny() ? bitcoinyAcctMap : pirateChainAcctMap;
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(String specificBlockchain) {
		if (specificBlockchain == null)
			return getAcctMap();

		SupportedBlockchain blockchain = fromString(specificBlockchain);
		if (blockchain == null)
			return Collections.emptyMap();

		return getFilteredAcctMap(blockchain);
	}

	public static ACCT getAcctByCodeHash(byte[] codeHash) {
		ByteArray wrappedCodeHash = ByteArray.wrap(codeHash);

		Supplier<ACCT> acctInstanceSupplier = supportedAcctsByCodeHash.get(wrappedCodeHash);
		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

	public static ACCT getAcctByName(String acctName) {
		Supplier<ACCT> acctInstanceSupplier = supportedAcctsByName.get(acctName);
		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

}
