package org.qortium.crosschain;

import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.Transaction;
import org.qortium.transform.TransformationException;

import java.util.List;
import java.util.Map;

final class RegisteredBitcoiny extends ConfiguredBitcoiny {

	private final BitcoinyChainSpec spec;

	RegisteredBitcoiny(BitcoinyChainSpec spec, BitcoinyNetwork network) {
		super(spec.getConfig(), network);
		this.spec = spec;
	}

	@Override
	public String normalizeAddress(String address) {
		return this.spec.normalizeAddress(address, this.getNetworkParameters());
	}

	@Override
	protected Integer getSpendTransactionVersion() {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.PEERCOIN)
			return 3;

		return super.getSpendTransactionVersion();
	}

	@Override
	protected int getHtlcTransactionVersion() {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.PEERCOIN)
			return 3;

		return super.getHtlcTransactionVersion();
	}

	@Override
	protected boolean hasSpendableOutputScriptFilter() {
		return this.spec.hasSpendableOutputScriptFilter();
	}

	@Override
	protected boolean isSpendableOutputScript(byte[] scriptPubKey) {
		return this.spec.isSpendableOutputScript(scriptPubKey);
	}

	@Override
	public long getSpendFeePerByte(Long feePerByte) {
		if (feePerByte != null)
			return feePerByte;

		Long defaultSpendFeePerByte = this.spec.getDefaultSpendFeePerByte();
		if (defaultSpendFeePerByte != null)
			return defaultSpendFeePerByte;

		return super.getSpendFeePerByte(null);
	}

	@Override
	public Transaction buildSpend(String xprv58, String recipient, long amount, Long feePerByte) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			throw unsupportedBitcoinjTransactionFormat();

		return super.buildSpend(xprv58, recipient, amount, feePerByte);
	}

	@Override
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			throw unsupportedBitcoinjTransactionFormat();

		return buildSpend(xprv58, recipient, amount, null);
	}

	@Override
	public Transaction buildSpendMultiple(String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			throw unsupportedBitcoinjTransactionFormat();

		return super.buildSpendMultiple(xprv58, amountByRecipient, feePerByte);
	}

	@Override
	public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return ZcashTransparentTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			return TimestampedLegacyTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			return BitcoinCashTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);

		return super.buildSpendTransaction(xprv58, recipient, amount, feePerByte);
	}

	@Override
	public BitcoinySignedTransaction buildSpendMaxTransaction(String xprv58, String recipient, Long feePerByte) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.buildSpendMax(this, xprv58, recipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return ZcashTransparentTransactionBuilder.buildSpendMax(this, xprv58, recipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			return TimestampedLegacyTransactionBuilder.buildSpendMax(this, xprv58, recipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			return BitcoinCashTransactionBuilder.buildSpendMax(this, xprv58, recipient, feePerByte);

		return super.buildSpendMaxTransaction(xprv58, recipient, feePerByte);
	}

	@Override
	public BitcoinySignedTransaction buildSpendMultipleTransaction(String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.buildSpend(this, xprv58, amountByRecipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return ZcashTransparentTransactionBuilder.buildSpend(this, xprv58, amountByRecipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			return TimestampedLegacyTransactionBuilder.buildSpend(this, xprv58, amountByRecipient, feePerByte);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			return BitcoinCashTransactionBuilder.buildSpend(this, xprv58, amountByRecipient, feePerByte);

		return super.buildSpendMultipleTransaction(xprv58, amountByRecipient, feePerByte);
	}

	@Override
	public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey, List<UnspentOutput> fundingOutputs,
			byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) throws ForeignBlockchainException {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return ZcashTransparentTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			return TimestampedLegacyTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			return BitcoinCashTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);

		return super.buildHtlcRedeemTransaction(redeemAmount, redeemKey, fundingOutputs, redeemScriptBytes, secret, receivingAccountInfo);
	}

	@Override
	public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey, List<UnspentOutput> fundingOutputs,
			byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) throws ForeignBlockchainException {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return ZcashTransparentTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			return TimestampedLegacyTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.BITCOIN_CASH)
			return BitcoinCashTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);

		return super.buildHtlcRefundTransaction(refundAmount, refundKey, fundingOutputs, redeemScriptBytes, lockTime, receivingAccountInfo);
	}

	@Override
	public int getBlockHeaderTimestampOffset() {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return 4 + 32 + 32 + 32;

		return super.getBlockHeaderTimestampOffset();
	}

	@Override
	public List<byte[]> splitRawBlockHeaders(byte[] rawBlockHeaders, int count) throws ForeignBlockchainException {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			return SaplingTransparentTransactionBuilder.splitBlockHeaders(rawBlockHeaders, count);

		return super.splitRawBlockHeaders(rawBlockHeaders, count);
	}

	@Override
	public BitcoinyTransaction deserializeRawTransaction(String txHash, byte[] rawTransaction) throws ForeignBlockchainException {
		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.SAPLING_TRANSPARENT
				|| this.spec.getTransactionFormat() == BitcoinyTransactionFormat.ZCASH_TRANSPARENT) {
			try {
				return ZcashFamilyTransactionParser.deserializeRawTransaction(txHash, rawTransaction);
			} catch (TransformationException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}
		}

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.PEERCOIN) {
			try {
				return BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.PEERCOIN, txHash, rawTransaction);
			} catch (RuntimeException e) {
				throw new ForeignBlockchainException(String.format("Unable to deserialize raw transaction: %s", e.getMessage()));
			}
		}

		if (this.spec.getTransactionFormat() == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY) {
			try {
				return BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY, txHash, rawTransaction);
			} catch (RuntimeException e) {
				throw new ForeignBlockchainException(String.format("Unable to deserialize raw transaction: %s", e.getMessage()));
			}
		}

		return super.deserializeRawTransaction(txHash, rawTransaction);
	}

	private UnsupportedOperationException unsupportedBitcoinjTransactionFormat() {
		return new UnsupportedOperationException(String.format("%s transactions must be built with BitcoinySignedTransaction APIs",
				this.spec.getCurrencyCode()));
	}
}
