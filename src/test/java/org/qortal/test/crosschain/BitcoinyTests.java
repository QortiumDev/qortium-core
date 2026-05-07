package org.qortal.test.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.AddressInfo;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.BitcoinyScript;
import org.qortal.crosschain.BitcoinyTransaction;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.TransactionHash;
import org.qortal.crosschain.UnspentOutput;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public abstract class BitcoinyTests extends Common {

	private static final String RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY = "qortium.runLiveCrosschainTests";
	private static final long MOCK_UTXO_VALUE = 100_000_000L;
	private static final byte[] EXPECTED_HTLC_SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int HTLC_LOCK_TIME = 1_700_000_000;

	protected Bitcoiny bitcoiny;

	protected abstract String getCoinName();

	protected abstract String getCoinSymbol();

	protected abstract Bitcoiny getCoin();

	protected abstract void resetCoinForTesting();

	protected abstract String getDeterministicKey58();

	protected abstract String getDeterministicPublicKey58();

	protected abstract String getRecipient();

	protected boolean supportsDeterministicWalletTests() {
		return getDeterministicKey58() != null && getDeterministicPublicKey58() != null;
	}

	protected boolean supportsDeterministicHtlcTests() {
		return true;
	}

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoiny = getCoin();
	}

	@After
	public void afterTest() {
		resetCoinForTesting();
		bitcoiny = null;
	}

	@Test
	public void testGetMedianBlockTimeFromMockProvider() throws ForeignBlockchainException {
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider(getCoinName() + "-mock");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(this.bitcoiny.getNetworkParameters(), blockchainProvider, getCoinSymbol());

		assertEquals(1_700_000_005, mockBitcoiny.getMedianBlockTime());
	}

	@Test
	public void testConfiguredFeesAndMinimumOrder() {
		assertTrue(bitcoiny.getMinimumOrderAmount() >= 0);

		long originalFeeRequired = bitcoiny.getFeeRequired();
		long updatedFeeRequired = originalFeeRequired + 1;

		try {
			bitcoiny.setFeeRequired(updatedFeeRequired);
			assertEquals(updatedFeeRequired, bitcoiny.getFeeRequired());
		} finally {
			bitcoiny.setFeeRequired(originalFeeRequired);
		}
	}

	@Test
	public void testGetMedianBlockTimeFromLiveProvider() throws ForeignBlockchainException {
		assumeLiveCrosschainTestsEnabled();

		System.out.println(String.format("Starting " + getCoinSymbol() + " instance..."));
		System.out.println(String.format(getCoinSymbol() + " instance started"));

		long before = System.currentTimeMillis();
		System.out.println(String.format(getCoinName() + " median blocktime: %d", bitcoiny.getMedianBlockTime()));
		long afterFirst = System.currentTimeMillis();

		System.out.println(String.format(getCoinName() + " median blocktime: %d", bitcoiny.getMedianBlockTime()));
		long afterSecond = System.currentTimeMillis();

		long firstPeriod = afterFirst - before;
		long secondPeriod = afterSecond - afterFirst;

		System.out.println(String.format("1st call: %d ms, 2nd call: %d ms", firstPeriod, secondPeriod));

		makeGetMedianBlockTimeAssertions(firstPeriod, secondPeriod);
	}

	public void makeGetMedianBlockTimeAssertions(long firstPeriod, long secondPeriod) {
		assertTrue("2nd call should be quicker than 1st", secondPeriod < firstPeriod);
		assertTrue("2nd call should take less than 5 seconds", secondPeriod < 5000L);
	}

	@Test
	public void testFindHtlcSecretFromMockProvider() throws ForeignBlockchainException {
		assumeTrue(supportsDeterministicHtlcTests());
		HtlcFixture fixture = createHtlcFixture(true);

		byte[] secret = BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress);

		assertNotNull(secret);
		assertArrayEquals("secret incorrect", EXPECTED_HTLC_SECRET, secret);
	}

	@Test
	public void testDetermineHtlcStatusFromMockProvider() throws ForeignBlockchainException {
		assumeTrue(supportsDeterministicHtlcTests());
		HtlcFixture fixture = createHtlcFixture(false);

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(fixture.bitcoiny.getBlockchainProvider(), fixture.p2shAddress, 1L);

		assertEquals(BitcoinyHTLC.Status.FUNDED, htlcStatus);
	}

	@Test
	public void testBuildSpend() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();
		String recipient = getSpendRecipient(mockBitcoiny);

		long amount = 1000L;

		Transaction transaction = mockBitcoiny.buildSpend(getDeterministicKey58(), recipient, amount);
		assertNotNull(transaction);
		assertFalse(transaction.getInputs().isEmpty());
		assertTrue(transaction.getOutputs().stream().anyMatch(output -> output.getValue().value == amount));

		// Check repeated spend building doesn't affect outcome

		transaction = mockBitcoiny.buildSpend(getDeterministicKey58(), recipient, amount);
		assertNotNull(transaction);
	}

	@Test
	public void testBuildSpendMultiple() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();

		Map<String, Long> amountByRecipient = new LinkedHashMap<>();
		amountByRecipient.put(getSpendRecipient(mockBitcoiny), 1000L);
		amountByRecipient.put(mockBitcoiny.pkhToAddress(HashCode.fromString("03".repeat(20)).asBytes()), 2000L);

		Transaction transaction = mockBitcoiny.buildSpendMultiple(getDeterministicKey58(), amountByRecipient, null);

		assertNotNull(transaction);
		assertFalse(transaction.getInputs().isEmpty());
		assertTrue(transaction.getOutputs().stream().anyMatch(output -> output.getValue().value == 1000L));
		assertTrue(transaction.getOutputs().stream().anyMatch(output -> output.getValue().value == 2000L));
	}

	@Test
	public void testGetWalletBalance() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();
		Long balance = mockBitcoiny.getWalletBalance(getDeterministicKey58());

		assertNotNull(balance);
		assertEquals(Long.valueOf(MOCK_UTXO_VALUE), balance);

		// Check repeated balance lookups don't affect outcome

		Long repeatBalance = mockBitcoiny.getWalletBalance(getDeterministicKey58());

		assertNotNull(repeatBalance);
		assertEquals(balance, repeatBalance);
	}

	@Test
	public void testGetUnusedReceiveAddress() throws ForeignBlockchainException {
		assumeTrue(supportsDeterministicWalletTests());

		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider(getCoinName() + "-mock");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(this.bitcoiny.getNetworkParameters(), blockchainProvider, getCoinSymbol());
		String address = mockBitcoiny.getUnusedReceiveAddress(getDeterministicKey58());

		assertNotNull(address);
		assertTrue(address.length() > 20);
	}

	@Test
	public void testGenerateRootKeyForTesting() {
		String rootKey = BitcoinyTestsUtils.generateBip32RootKey(this.bitcoiny.getNetworkParameters());
		assertTrue(this.bitcoiny.isValidDeterministicKey(rootKey));
	}

	@Test
	public void testGetWalletAddresses() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();

		Set<String> addresses = mockBitcoiny.getWalletAddresses(getDeterministicKey58());

		assertFalse(addresses.isEmpty());
	}

	@Test
	public void testWalletAddressInfos() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();

		List<AddressInfo> addressInfos = mockBitcoiny.getWalletAddressInfos(getDeterministicPublicKey58());

		assertFalse(addressInfos.isEmpty());
		assertTrue(addressInfos.stream().anyMatch(addressInfo -> addressInfo.getValue() == MOCK_UTXO_VALUE));
	}

	@Test
	public void testWalletSpendingCandidateAddresses() throws ForeignBlockchainException {
		assumeTrue(supportsDeterministicWalletTests());

		List<String> candidateAddresses = this.bitcoiny.getSpendingCandidateAddresses(getDeterministicPublicKey58());

		assertFalse(candidateAddresses.isEmpty());
	}

	private TestBitcoiny createMockBitcoinyWithWalletUtxo() throws ForeignBlockchainException {
		assumeTrue(supportsDeterministicWalletTests());

		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider(getCoinName() + "-mock");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(this.bitcoiny.getNetworkParameters(), blockchainProvider, getCoinSymbol());
		Set<String> fundedAddresses = new HashSet<>();

		String privateWalletAddress = mockBitcoiny.getWalletAddresses(getDeterministicKey58()).iterator().next();
		addMockUtxo(blockchainProvider, mockBitcoiny, privateWalletAddress, fundedAddresses);

		String publicWalletAddress = mockBitcoiny.getWalletAddresses(getDeterministicPublicKey58()).iterator().next();
		addMockUtxo(blockchainProvider, mockBitcoiny, publicWalletAddress, fundedAddresses);

		return mockBitcoiny;
	}

	private HtlcFixture createHtlcFixture(boolean includeRedeemTransaction) {
		NetworkParameters params = this.bitcoiny.getNetworkParameters();
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider(getCoinName() + "-mock-htlc");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(params, blockchainProvider, getCoinSymbol());
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), HTLC_LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(EXPECTED_HTLC_SECRET));
		String p2shAddress = mockBitcoiny.deriveP2shAddress(redeemScriptBytes);
		byte[] p2shScriptPubKey = BitcoinyScript.scriptPubKey(params, p2shAddress);
		Transaction fundingTransaction = new Transaction(params);
		fundingTransaction.addOutput(Coin.valueOf(20_000L), Address.fromString(params, p2shAddress));
		String fundingTxHash = fundingTransaction.getTxId().toString();

		if (includeRedeemTransaction) {
			UnspentOutput fundingOutput = new UnspentOutput(HashCode.fromString(fundingTxHash).asBytes(), 0, 10, 20_000L,
					fundingTransaction.getOutput(0).getScriptPubKey().getProgram(), p2shAddress);
			Transaction redeemTransaction = BitcoinyHTLC.buildRedeemTransaction(params, Coin.valueOf(19_000L), redeemKey,
					Collections.singletonList(fundingOutput), redeemScriptBytes, EXPECTED_HTLC_SECRET, redeemKey.getPubKeyHash());
			String redeemTxHash = redeemTransaction.getTxId().toString();
			blockchainProvider.addAddressTransaction(p2shScriptPubKey, new TransactionHash(11, redeemTxHash));
			blockchainProvider.addRawTransaction(redeemTxHash, redeemTransaction.bitcoinSerialize());
		} else {
			blockchainProvider.addAddressTransaction(p2shScriptPubKey, new TransactionHash(10, fundingTxHash));
			blockchainProvider.addTransaction(new BitcoinyTransaction(fundingTxHash, fundingTransaction.bitcoinSerialize().length, (int) fundingTransaction.getLockTime(),
					1_700_000_000, Collections.emptyList(), Collections.singletonList(
							new BitcoinyTransaction.Output(HashCode.fromBytes(p2shScriptPubKey).toString(), 20_000L))));
		}

		return new HtlcFixture(mockBitcoiny, p2shAddress);
	}

	private void addMockUtxo(MockBitcoinyBlockchainProvider blockchainProvider, TestBitcoiny mockBitcoiny, String walletAddress, Set<String> fundedAddresses) {
		if (!fundedAddresses.add(walletAddress))
			return;

		byte[] scriptPubKey = BitcoinyScript.scriptPubKey(mockBitcoiny.getNetworkParameters(), walletAddress);
		byte[] txHash = HashCode.fromString("01".repeat(32)).asBytes();

		UnspentOutput unspentOutput = new UnspentOutput(txHash, 0, 1, MOCK_UTXO_VALUE, scriptPubKey, walletAddress);
		blockchainProvider.addUnspentOutput(walletAddress, unspentOutput);
		blockchainProvider.addUnspentOutput(scriptPubKey, unspentOutput);
	}

	private String getSpendRecipient(Bitcoiny coin) {
		if (getRecipient() != null)
			return getRecipient();

		return coin.pkhToAddress(HashCode.fromString("02".repeat(20)).asBytes());
	}

	private void assumeLiveCrosschainTestsEnabled() {
		assumeTrue(Boolean.getBoolean(RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY));
	}

	private static class HtlcFixture {
		private final Bitcoiny bitcoiny;
		private final String p2shAddress;

		private HtlcFixture(Bitcoiny bitcoiny, String p2shAddress) {
			this.bitcoiny = bitcoiny;
			this.p2shAddress = p2shAddress;
		}
	}
}
