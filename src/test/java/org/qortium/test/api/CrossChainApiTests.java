package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.model.CrossChainTradeLedgerEntry;
import org.qortium.api.model.crosschain.SupportedBlockchainInfo;
import org.qortium.api.resource.CrossChainResource;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.crosschain.BitcoinyChainConfig;
import org.qortium.crosschain.BitcoinyChainSpec;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyNetwork;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.PirateChain;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CrossChainApiTests extends ApiCommon {

	private static final String SPECIFIC_BLOCKCHAIN = null;
	private static final String SPECIFIC_BLOCKCHAIN_ALIAS = "LTC";
	private static final String UNKNOWN_BLOCKCHAIN = "UNKNOWN";

	private CrossChainResource crossChainResource;

	@Before
	public void buildResource() {
		this.crossChainResource = (CrossChainResource) ApiCommon.buildResource(CrossChainResource.class);
	}

	@Test
	public void testGetSupportedBlockchains() {
		List<SupportedBlockchainInfo> blockchains = this.crossChainResource.getSupportedBlockchains();

		assertEquals(ForeignBlockchainRegistry.entries().size(), blockchains.size());
		assertSupportedBitcoinInfo(blockchains);
		assertSupportedPirateChainInfo(blockchains);
	}

	@Test
	public void testGetSupportedBlockchainsDoesNotStartPirateChain() throws ReflectiveOperationException {
		PirateChain.resetForTesting();

		this.crossChainResource.getSupportedBlockchains();

		Field instanceField = PirateChain.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		assertNull(instanceField.get(null));
	}

	@Test
	public void testGetTradeOffers() {
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getTradeOffers(SPECIFIC_BLOCKCHAIN, null, null, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getTradeOffers(SPECIFIC_BLOCKCHAIN_ALIAS, null, null, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getTradeOffers(null, "BTC", "LTC", null, limit, offset, reverse));
	}

	@Test
	public void testGetCompletedTrades() {
		long minimumTimestamp = System.currentTimeMillis();
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, null, null, minimumTimestamp, null, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN_ALIAS, null, null, null, minimumTimestamp, null, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getCompletedTrades(null, "BTC", "LTC", null, minimumTimestamp, null, null, limit, offset, reverse));
	}

	@Test
	public void testInvalidGetCompletedTrades() {
		Integer limit = null;
		Integer offset = null;
		Boolean reverse = null;

		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, null, null, -1L /*minimumTimestamp*/, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, null, null, 0L /*minimumTimestamp*/, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getTradeOffers(UNKNOWN_BLOCKCHAIN, null, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getTradeOffers(null, UNKNOWN_BLOCKCHAIN, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getTradeOffers(null, null, UNKNOWN_BLOCKCHAIN, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(UNKNOWN_BLOCKCHAIN, null, null, null, null, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(null, UNKNOWN_BLOCKCHAIN, null, null, null, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(null, null, UNKNOWN_BLOCKCHAIN, null, null, null, null, limit, offset, reverse));
	}

	@Test
	public void testLedgerCsvEscapesStringFields() throws IOException {
		CrossChainTradeLedgerEntry entry = new CrossChainTradeLedgerEntry(
				"BTC,NATIVE",
				"=BTC",
				100000000L,
				5000000L,
				"F\"EE",
				250000000L,
				0L);
		StringWriter writer = new StringWriter();

		CrossChainUtils.writeToLedger(writer, List.of(entry));

		String expected = "Market,Currency,Quantity,Commission Paid,Commission Currency,Total Price,Date Time,Exchange" + System.lineSeparator()
				+ "\"BTC,NATIVE\",'=BTC,1.00000000,0.05000000,\"F\"\"EE\",2.50000000,19700101 00:00,Local Chain" + System.lineSeparator();
		assertEquals(expected, writer.toString());
	}

	private static void assertSupportedBitcoinInfo(List<SupportedBlockchainInfo> blockchains) {
		SupportedBlockchainInfo bitcoin = findBlockchain(blockchains, "BITCOIN");
		ForeignBlockchainRegistry.Entry bitcoinEntry = ForeignBlockchainRegistry.fromString("BTC");
		BitcoinyChainSpec bitcoinSpec = BitcoinyChainSpecs.BITCOIN;
		BitcoinyChainConfig bitcoinConfig = bitcoinSpec.getConfig();
		BitcoinyNetwork activeNetwork = Settings.getInstance().getBitcoinyNetwork(bitcoinConfig.getCurrencyCode());

		assertNotNull(bitcoinEntry);
		assertEquals(bitcoinEntry.name(), bitcoin.name);
		assertEquals(bitcoinConfig.getCurrencyCode(), bitcoin.currencyCode);
		assertEquals(bitcoinConfig.getDisplayName(), bitcoin.displayName);
		assertEquals("BITCOINY", bitcoin.type);
		assertEquals("/crosschain/BITCOIN", bitcoin.apiPath);
		assertTrue(bitcoin.walletEnabled);
		assertEquals(activeNetwork.name(), bitcoin.activeNetwork);
		assertEquals(activeNetwork.getChainId(), bitcoin.chainId);
		assertEquals(bitcoinEntry.getSlip44CoinType(), bitcoin.slip44CoinType);
		assertEquals(bitcoinConfig.getDecimalPlaces(), bitcoin.decimalPlaces);
		assertTrue(bitcoin.supportsWallet);
		assertTrue(bitcoin.supportsHtlc);
		assertTrue(bitcoin.supportsLocalChainTrades);
		assertEquals(bitcoinSpec.supportsForeignForeignTrades(), bitcoin.supportsForeignForeignTrades);
	}

	private static void assertSupportedPirateChainInfo(List<SupportedBlockchainInfo> blockchains) {
		SupportedBlockchainInfo pirateChain = findBlockchain(blockchains, ForeignBlockchainRegistry.PIRATECHAIN_NAME);

		assertEquals(ForeignBlockchainRegistry.PIRATECHAIN_NAME, pirateChain.name);
		assertEquals(PirateChain.CURRENCY_CODE, pirateChain.currencyCode);
		assertEquals(PirateChain.WALLET_CONFIG.getDisplayName(), pirateChain.displayName);
		assertEquals("PIRATECHAIN", pirateChain.type);
		assertEquals("/crosschain/arrr", pirateChain.apiPath);
		assertTrue(pirateChain.walletEnabled);
		assertEquals(Settings.getInstance().getPirateChainNet().name(), pirateChain.activeNetwork);
		assertNull(pirateChain.chainId);
		assertNull(pirateChain.slip44CoinType);
		assertEquals(8, pirateChain.decimalPlaces);
		assertTrue(pirateChain.supportsWallet);
		assertTrue(pirateChain.supportsHtlc);
		assertTrue(pirateChain.supportsLocalChainTrades);
		assertFalse(pirateChain.supportsForeignForeignTrades);
	}

	private static SupportedBlockchainInfo findBlockchain(List<SupportedBlockchainInfo> blockchains, String name) {
		return blockchains.stream()
				.filter(blockchain -> name.equals(blockchain.name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing supported blockchain: " + name));
	}

}
