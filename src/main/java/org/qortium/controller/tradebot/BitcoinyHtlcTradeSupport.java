package org.qortium.controller.tradebot;

import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.utils.NTP;

import java.util.List;

final class BitcoinyHtlcTradeSupport {

	private static final int SECONDS_PER_MINUTE = 60;

	private HtlcStatusResolver htlcStatusResolver = BitcoinyHTLC::determineHtlcStatus;
	private HtlcSecretResolver htlcSecretResolver = BitcoinyHTLC::findHtlcSecret;

	@FunctionalInterface
	interface HtlcStatusResolver {
		BitcoinyHTLC.Status determineHtlcStatus(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount)
				throws ForeignBlockchainException;
	}

	@FunctionalInterface
	interface HtlcSecretResolver {
		byte[] findHtlcSecret(Bitcoiny bitcoiny, String p2shAddress) throws ForeignBlockchainException;
	}

	void setHtlcStatusResolverForTesting(HtlcStatusResolver htlcStatusResolver) {
		this.htlcStatusResolver = htlcStatusResolver != null ? htlcStatusResolver : BitcoinyHTLC::determineHtlcStatus;
	}

	void setHtlcSecretResolverForTesting(HtlcSecretResolver htlcSecretResolver) {
		this.htlcSecretResolver = htlcSecretResolver != null ? htlcSecretResolver : BitcoinyHTLC::findHtlcSecret;
	}

	void resetTestHooks() {
		this.htlcStatusResolver = BitcoinyHTLC::determineHtlcStatus;
		this.htlcSecretResolver = BitcoinyHTLC::findHtlcSecret;
	}

	BitcoinyHTLC.Status determineHtlcStatus(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount)
			throws ForeignBlockchainException {
		return this.htlcStatusResolver.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
	}

	byte[] findHtlcSecret(Bitcoiny bitcoiny, String p2shAddress) throws ForeignBlockchainException {
		return this.htlcSecretResolver.findHtlcSecret(bitcoiny, p2shAddress);
	}

	boolean fundIfUnfunded(Bitcoiny bitcoiny, String fundingKey, String p2shAddress, long minimumAmount)
			throws ForeignBlockchainException {
		BitcoinySignedTransaction fundingTransaction = bitcoiny.buildSpendTransaction(fundingKey, p2shAddress, minimumAmount);
		if (fundingTransaction == null)
			return false;

		bitcoiny.broadcastTransaction(fundingTransaction);
		return true;
	}

	boolean redeemIfFunded(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount, long redeemAmount,
			byte[] tradePrivateKey, byte[] redeemScript, byte[] secret, byte[] receivingAccountInfo)
			throws ForeignBlockchainException {
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);

		switch (htlcStatus) {
			case REDEEM_IN_PROGRESS:
			case REDEEMED:
				return true;

			case FUNDED:
				List<UnspentOutput> fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddress, false);
				BitcoinySignedTransaction redeemTransaction = bitcoiny.buildHtlcRedeemTransaction(Coin.valueOf(redeemAmount),
						ECKey.fromPrivate(tradePrivateKey), fundingOutputs, redeemScript, secret, receivingAccountInfo);
				bitcoiny.broadcastTransaction(redeemTransaction);
				return true;

			default:
				return false;
		}
	}

	boolean refundIfExpired(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount, long refundAmount,
			byte[] tradePrivateKey, byte[] redeemScript, int lockTime, byte[] receivingAccountInfo)
			throws ForeignBlockchainException {
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);

		switch (htlcStatus) {
			case UNFUNDED:
			case REFUND_IN_PROGRESS:
			case REFUNDED:
				return true;

			case FUNDED:
				if (NTP.getTime() <= lockTime * 1000L || bitcoiny.getMedianBlockTime() <= lockTime)
					return false;

				List<UnspentOutput> fundingOutputs = bitcoiny.getUnspentOutputs(p2shAddress, false);
				BitcoinySignedTransaction refundTransaction = bitcoiny.buildHtlcRefundTransaction(Coin.valueOf(refundAmount),
						ECKey.fromPrivate(tradePrivateKey), fundingOutputs, redeemScript, lockTime, receivingAccountInfo);
				bitcoiny.broadcastTransaction(refundTransaction);
				return true;

			default:
				return false;
		}
	}

	static byte[] buildRedeemScript(CrossChainTradeData tradeData) {
		return buildRedeemScript(tradeData.creatorForeignPKH, tradeData.lockTimeA,
				tradeData.partnerForeignPKH, tradeData.hashOfSecretA);
	}

	static byte[] buildRedeemScript(byte[] refundPublicKeyHash, int lockTime,
			byte[] redeemPublicKeyHash, byte[] hashOfSecret) {
		return BitcoinyHTLC.buildScript(refundPublicKeyHash, lockTime, redeemPublicKeyHash, hashOfSecret);
	}

	static String deriveP2shAddress(Bitcoiny bitcoiny, CrossChainTradeData tradeData) {
		return bitcoiny.deriveP2shAddress(buildRedeemScript(tradeData));
	}

	static String deriveP2shAddress(Bitcoiny bitcoiny, byte[] refundPublicKeyHash, int lockTime,
			byte[] redeemPublicKeyHash, byte[] hashOfSecret) {
		return bitcoiny.deriveP2shAddress(buildRedeemScript(refundPublicKeyHash, lockTime,
				redeemPublicKeyHash, hashOfSecret));
	}

	static long minimumHtlcAmount(Bitcoiny bitcoiny, long foreignAmount) throws ForeignBlockchainException {
		return foreignAmount + bitcoiny.getP2shFee(NTP.getTime());
	}

	static boolean hasSufficientTimeBeforeLock(long now, long protectedWindowSeconds, int safetyMarginMinutes,
			Integer lockTime) {
		if (lockTime == null)
			return false;

		long safetyMarginSeconds = (long) safetyMarginMinutes * SECONDS_PER_MINUTE;
		return now / 1000L + protectedWindowSeconds + safetyMarginSeconds < lockTime;
	}

	static boolean hasLaterRefundSafetyMargin(Integer earlierLockTime, Integer laterLockTime, int safetyMarginMinutes) {
		if (earlierLockTime == null || laterLockTime == null)
			return false;

		long safetyMarginSeconds = (long) safetyMarginMinutes * SECONDS_PER_MINUTE;
		return earlierLockTime + safetyMarginSeconds < laterLockTime;
	}

}
