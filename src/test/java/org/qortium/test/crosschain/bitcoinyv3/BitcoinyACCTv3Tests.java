package org.qortium.test.crosschain.bitcoinyv3;

import com.google.common.hash.HashCode;
import org.ciyam.at.MachineState;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.Bip122ChainId;
import org.qortium.crosschain.BitcoinyACCTv3;
import org.qortium.crosschain.BitcoinyNetwork;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.crosschain.ACCTTests;
import org.qortium.transaction.DeployAtTransaction;

import java.util.Arrays;

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
		return BitcoinyACCTv3.buildTradeAT(ForeignBlockchainRegistry.fromString("BITCOIN"), address, publicKey, redeemAmount, foreignAmount, tradeTimeout);
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
	public void testForeignBlockchainChainIdRoundTrip() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = createTradeAccount(repository);

			for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.bitcoinyEntries()) {
				DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), blockchain);
				Account at = deployAtTransaction.getATAccount();

				ATData atData = repository.getATRepository().fromATAddress(at.getAddress());
				CrossChainTradeData tradeData = BitcoinyACCTv3.getInstance().populateTradeData(repository, atData);

				assertEquals(blockchain.name(), tradeData.foreignBlockchain);
				assertNotNull(blockchain.getActiveChainId());
				assertArrayEquals(BitcoinyACCTv3.CODE_BYTES_HASH, atData.getCodeHash());
			}
		}
	}

	@Test
	public void testRejectsInactiveBitcoinyChainId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = createTradeAccount(repository);

			for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.bitcoinyEntries()) {
				BitcoinyNetwork inactiveNetwork = blockchain.getBitcoinySpec().getNetworks().stream()
						.filter(network -> !network.getChainId().equals(blockchain.getActiveChainId()))
						.findFirst()
						.orElse(null);

				if (inactiveNetwork == null)
					continue;

				DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), blockchain);
				Account at = deployAtTransaction.getATAccount();
				ATStateData atStateData = repository.getATRepository().getLatestATState(at.getAddress());

				byte[] stateData = Arrays.copyOf(atStateData.getStateData(), atStateData.getStateData().length);
				byte[] inactiveChainIdReference = Bip122ChainId.toReferenceBytes(inactiveNetwork.getChainId());
				System.arraycopy(inactiveChainIdReference, 0, stateData, BitcoinyACCTv3.MODE_BYTE_OFFSET + MachineState.VALUE_SIZE,
						inactiveChainIdReference.length);

				ATStateData mismatchedState = new ATStateData(atStateData.getATAddress(), atStateData.getHeight(), stateData,
						atStateData.getStateHash(), atStateData.getFees(), atStateData.isInitial(),
						atStateData.getSleepUntilMessageTimestamp());

				assertNull(BitcoinyACCTv3.getInstance().populateTradeData(repository, mismatchedState));
				return;
			}
		}

		fail("No registered Bitcoiny chain has an inactive network for mismatch coverage");
	}

	@Test
	public void testForeignBlockchainRegistryLookupByNameAndCurrencyCode() {
		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
			assertSame(blockchain, ForeignBlockchainRegistry.fromString(blockchain.name()));
			assertSame(blockchain, ForeignBlockchainRegistry.fromString(blockchain.name().toLowerCase()));
			assertSame(blockchain, ForeignBlockchainRegistry.fromString(blockchain.getCurrencyCode()));
			assertSame(blockchain, ForeignBlockchainRegistry.fromString(blockchain.getCurrencyCode().toLowerCase()));
		}

		assertNull(ForeignBlockchainRegistry.fromString("unknown"));
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, ForeignBlockchainRegistry.Entry blockchain) throws DataException {
		byte[] creationBytes = BitcoinyACCTv3.buildTradeAT(blockchain, tradeAddress, foreignPublicKeyHash, redeemAmount, foreignAmount, tradeTimeout);

		long txTimestamp = System.currentTimeMillis();
		Long fee = null;
		String name = "NATIVE-" + blockchain.getCurrencyCode() + " cross-chain trade";
		String description = "Local-chain-" + blockchain.name() + " cross-chain trade";
		String atType = "ACCT";
		String tags = "NATIVE-" + blockchain.getCurrencyCode() + " ACCT";

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, name, description, atType, tags, creationBytes, fundingAmount, Asset.NATIVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

		fee = deployAtTransaction.calcRecommendedFee();
		deployAtTransactionData.setFee(fee);

		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}
}
