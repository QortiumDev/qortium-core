package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

public class BitcoinyForeignForeignACCTv1 implements ACCT {

	public static final String NAME = BitcoinyForeignForeignACCTv1.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff00112233445566778899aabbccddeeff").asBytes();

	public static final int SECRET_LENGTH = 32;
	public static final int PUBLIC_KEY_HASH_LENGTH = 20;
	public static final int PADDED_PUBLIC_KEY_HASH_LENGTH = 32;
	public static final int CHAIN_ID_REFERENCE_LENGTH = Bip122ChainId.REFERENCE_BYTE_LENGTH;

	public static final int RESERVE_MESSAGE_LENGTH = 2 * PADDED_PUBLIC_KEY_HASH_LENGTH;
	public static final int MAKER_HTLC_MESSAGE_LENGTH = 8;
	public static final int TAKER_HTLC_MESSAGE_LENGTH = 8;
	public static final int SECRET_REVEAL_MESSAGE_LENGTH = SECRET_LENGTH;
	public static final int CANCEL_MESSAGE_LENGTH = 32;

	public static final int REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES = 30;

	private static BitcoinyForeignForeignACCTv1 instance;

	private BitcoinyForeignForeignACCTv1() {
	}

	public static synchronized BitcoinyForeignForeignACCTv1 getInstance() {
		if (instance == null)
			instance = new BitcoinyForeignForeignACCTv1();

		return instance;
	}

	@Override
	public byte[] getCodeBytesHash() {
		return CODE_BYTES_HASH;
	}

	@Override
	public int getModeByteOffset() {
		return 0;
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) {
		return null;
	}

	@Override
	public List<CrossChainTradeData> populateTradeDataList(Repository repository, List<ATData> atDataList) {
		return Collections.emptyList();
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) {
		return null;
	}

	@Override
	public CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, long creationTimestamp,
			ATStateData atStateData, OptionalLong optionalBalance) {
		return null;
	}

	public static CrossChainTradeData buildSkeletonTradeData(ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain, byte[] makerOfferedForeignPublicKeyHash,
			byte[] makerRequestedForeignPublicKeyHash, byte[] hashOfSecret, long offeredForeignAmount,
			long requestedForeignAmount, int tradeTimeout) {
		if (!isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain))
			throw new IllegalArgumentException("Foreign/foreign trades require two supported Bitcoiny blockchains");

		requireHash160(makerOfferedForeignPublicKeyHash, "maker offered-chain public key hash");
		requireHash160(makerRequestedForeignPublicKeyHash, "maker requested-chain public key hash");
		requireHash160(hashOfSecret, "hash of secret");

		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.acctName = NAME;
		tradeData.tradeDirection = TradeDirection.SELL_FOREIGN_FOR_FOREIGN;
		tradeData.offeredForeignBlockchain = offeredForeignBlockchain.name();
		tradeData.offeredForeignAmount = offeredForeignAmount;
		tradeData.requestedForeignBlockchain = requestedForeignBlockchain.name();
		tradeData.requestedForeignAmount = requestedForeignAmount;
		tradeData.creatorOfferedForeignPKH = makerOfferedForeignPublicKeyHash;
		tradeData.creatorRequestedForeignPKH = makerRequestedForeignPublicKeyHash;
		tradeData.hashOfSecretA = hashOfSecret;
		tradeData.tradeTimeout = tradeTimeout;
		tradeData.mode = AcctMode.OFFERING;
		return tradeData;
	}

	public static boolean isSupportedBitcoinyPair(ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		return offeredForeignBlockchain != null && offeredForeignBlockchain.isBitcoiny()
				&& requestedForeignBlockchain != null && requestedForeignBlockchain.isBitcoiny();
	}

	public static ForeignBlockchainRegistry.Entry requireBitcoinyEntry(String blockchainName, String fieldName) {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(blockchainName);
		if (entry == null || !entry.isBitcoiny())
			throw new IllegalArgumentException("Unsupported Bitcoiny blockchain for " + fieldName + ": " + blockchainName);

		return entry;
	}

	public static byte[] buildReserveMessage(byte[] takerOfferedForeignPublicKeyHash, byte[] takerRequestedForeignPublicKeyHash) {
		byte[] data = new byte[RESERVE_MESSAGE_LENGTH];
		putPaddedHash160(data, 0, takerOfferedForeignPublicKeyHash, "taker offered-chain public key hash");
		putPaddedHash160(data, PADDED_PUBLIC_KEY_HASH_LENGTH, takerRequestedForeignPublicKeyHash, "taker requested-chain public key hash");
		return data;
	}

	public static byte[] buildMakerHtlcMessage(int makerLockTime) {
		return BitTwiddling.toBEByteArray((long) makerLockTime);
	}

	public static byte[] buildTakerHtlcMessage(int takerLockTime) {
		return BitTwiddling.toBEByteArray((long) takerLockTime);
	}

	public static byte[] buildSecretRevealMessage(byte[] secret) {
		if (secret == null || secret.length != SECRET_LENGTH)
			throw new IllegalArgumentException("Secret should be 32 bytes");

		byte[] data = new byte[SECRET_REVEAL_MESSAGE_LENGTH];
		System.arraycopy(secret, 0, data, 0, secret.length);
		return data;
	}

	@Override
	public byte[] buildCancelMessage(String creatorAddress) {
		byte[] data = new byte[CANCEL_MESSAGE_LENGTH];
		System.arraycopy(Base58.decode(creatorAddress), 0, data, 0, 25);
		return data;
	}

	@Override
	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		return null;
	}

	private static void putPaddedHash160(byte[] data, int offset, byte[] hash, String label) {
		requireHash160(hash, label);
		System.arraycopy(hash, 0, data, offset, hash.length);
	}

	private static void requireHash160(byte[] hash, String label) {
		if (hash == null || hash.length != PUBLIC_KEY_HASH_LENGTH)
			throw new IllegalArgumentException(label + " should be 20 bytes");
	}

}
