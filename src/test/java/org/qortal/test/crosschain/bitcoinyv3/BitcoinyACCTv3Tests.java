package org.qortal.test.crosschain.bitcoinyv3;

import com.google.common.hash.HashCode;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.BitcoinyACCTv3;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.crosschain.ACCTTests;
import org.qortal.transaction.DeployAtTransaction;

import static org.junit.Assert.*;

public class BitcoinyACCTv3Tests extends ACCTTests {

	public static final byte[] foreignPublicKeyHash = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final String SYMBOL = "BTC";
	private static final String NAME = "Bitcoin";

	@Override
	protected byte[] getPublicKey() {
		return foreignPublicKeyHash;
	}

	@Override
	protected byte[] buildTradeAT(String address, byte[] publicKey, long redeemAmount, long foreignAmount, int tradeTimeout) {
		return BitcoinyACCTv3.buildTradeAT(SupportedBlockchain.BITCOIN, address, publicKey, redeemAmount, foreignAmount, tradeTimeout);
	}

	@Override
	protected ACCT getInstance() {
		return BitcoinyACCTv3.getInstance();
	}

	@Override
	protected int calcRefundTimeout(long partnersOfferMessageTransactionTimestamp, int lockTimeA) {
		return BitcoinyACCTv3.calcRefundTimeout(partnersOfferMessageTransactionTimestamp, lockTimeA);
	}

	@Override
	protected byte[] buildTradeMessage(String address, byte[] publicKey, byte[] hashOfSecretA, int lockTimeA, int refundTimeout) {
		return BitcoinyACCTv3.buildTradeMessage(address, publicKey, hashOfSecretA, lockTimeA, refundTimeout);
	}

	@Override
	protected byte[] buildRedeemMessage(byte[] secretA, String address) {
		return BitcoinyACCTv3.buildRedeemMessage(secretA, address);
	}

	@Override
	protected byte[] getCodeBytesHash() {
		return BitcoinyACCTv3.CODE_BYTES_HASH;
	}

	@Override
	protected String getSymbol() {
		return SYMBOL;
	}

	@Override
	protected String getName() {
		return NAME;
	}

	@Test
	public void testForeignBlockchainIdRoundTrip() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = createTradeAccount(repository);

			for (SupportedBlockchain blockchain : SupportedBlockchain.bitcoinyBlockchains()) {
				DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), blockchain);
				Account at = deployAtTransaction.getATAccount();

				ATData atData = repository.getATRepository().fromATAddress(at.getAddress());
				CrossChainTradeData tradeData = BitcoinyACCTv3.getInstance().populateTradeData(repository, atData);

				assertEquals(blockchain.name(), tradeData.foreignBlockchain);
				assertArrayEquals(BitcoinyACCTv3.CODE_BYTES_HASH, atData.getCodeHash());
			}
		}
	}

	@Test
	public void testSupportedBlockchainLookupByNameAndCurrencyCode() {
		for (SupportedBlockchain blockchain : SupportedBlockchain.values()) {
			assertEquals(blockchain, SupportedBlockchain.fromString(blockchain.name()));
			assertEquals(blockchain, SupportedBlockchain.fromString(blockchain.name().toLowerCase()));
			assertEquals(blockchain, SupportedBlockchain.fromString(blockchain.getCurrencyCode()));
			assertEquals(blockchain, SupportedBlockchain.fromString(blockchain.getCurrencyCode().toLowerCase()));
		}

		assertNull(SupportedBlockchain.fromString("unknown"));
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, SupportedBlockchain blockchain) throws DataException {
		byte[] creationBytes = BitcoinyACCTv3.buildTradeAT(blockchain, tradeAddress, foreignPublicKeyHash, redeemAmount, foreignAmount, tradeTimeout);

		long txTimestamp = System.currentTimeMillis();
		Long fee = null;
		String name = "NATIVE-" + blockchain.getInstance().getCurrencyCode() + " cross-chain trade";
		String description = "Local-chain-" + blockchain.name() + " cross-chain trade";
		String atType = "ACCT";
		String tags = "NATIVE-" + blockchain.getInstance().getCurrencyCode() + " ACCT";

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, atType, tags, creationBytes, fundingAmount, Asset.NATIVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}
}
