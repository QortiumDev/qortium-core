package org.qortal.test.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.AddressInfo;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.UnspentOutput;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public abstract class BitcoinyTests extends Common {

	private static final String RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY = "qortium.runLiveCrosschainTests";
	private static final long MOCK_UTXO_VALUE = 100_000_000L;

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

	protected String getLiveHtlcSecretAddress() {
		return null;
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
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		assumeLiveCrosschainTestsEnabled();
		assumeTrue(getLiveHtlcSecretAddress() != null);

		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = getLiveHtlcSecretAddress();

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BitcoinyHTLC.findHtlcSecret(bitcoiny, p2shAddress);

		assertNotNull(secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
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

		// Check spent key caching doesn't affect outcome

		transaction = mockBitcoiny.buildSpend(getDeterministicKey58(), recipient, amount);
		assertNotNull(transaction);
	}

	@Test
	public void testRepair() throws ForeignBlockchainException {
		assumeLiveCrosschainTestsEnabled();
		assumeTrue(supportsDeterministicWalletTests());

		String xprv58 = getDeterministicKey58();

		String transaction = bitcoiny.repairOldWallet(xprv58);

		assertNotNull(transaction);
	}

	@Test
	public void testGetWalletBalance() throws ForeignBlockchainException {
		TestBitcoiny mockBitcoiny = createMockBitcoinyWithWalletUtxo();
		Long balance = mockBitcoiny.getWalletBalance(getDeterministicKey58());

		assertNotNull(balance);
		assertEquals(Long.valueOf(MOCK_UTXO_VALUE), balance);

		// Check spent key caching doesn't affect outcome

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

	private void addMockUtxo(MockBitcoinyBlockchainProvider blockchainProvider, TestBitcoiny mockBitcoiny, String walletAddress, Set<String> fundedAddresses) {
		if (!fundedAddresses.add(walletAddress))
			return;

		byte[] scriptPubKey = ScriptBuilder.createOutputScript(Address.fromString(mockBitcoiny.getNetworkParameters(), walletAddress)).getProgram();
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
}
