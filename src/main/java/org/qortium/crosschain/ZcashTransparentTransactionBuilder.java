package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.base.Sha256Hash;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.qortium.crypto.Crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ZcashTransparentTransactionBuilder {

	private static final int HEADER = 0x80000005;
	private static final int VERSION_GROUP_ID = 0x26A7270A;
	private static final int SIGHASH_ALL = 0x01;
	private static final int EXPIRY_DELTA = 20;
	private static final long NO_LOCKTIME_SEQUENCE = BitcoinyHTLC.NO_LOCKTIME_NO_RBF_SEQUENCE;
	private static final long LOCKTIME_SEQUENCE = BitcoinyHTLC.LOCKTIME_NO_RBF_SEQUENCE;
	private static final byte[] EMPTY = new byte[0];

	private static final int SAPLING_ACTIVATION_HEIGHT = 419_200;
	private static final int BLOSSOM_ACTIVATION_HEIGHT = 653_600;
	private static final int HEARTWOOD_ACTIVATION_HEIGHT = 903_000;
	private static final int CANOPY_ACTIVATION_HEIGHT = 1_046_400;
	private static final int NU5_ACTIVATION_HEIGHT = 1_687_104;
	private static final int NU6_ACTIVATION_HEIGHT = 2_726_400;
	private static final int NU6_1_ACTIVATION_HEIGHT = 3_146_400;

	private static final int SAPLING_BRANCH_ID = 0x76B809BB;
	private static final int BLOSSOM_BRANCH_ID = 0x2BB40E60;
	private static final int HEARTWOOD_BRANCH_ID = 0xF5B9230B;
	private static final int CANOPY_BRANCH_ID = 0xE9FF75A6;
	private static final int NU5_BRANCH_ID = 0xC2D6D0B4;
	private static final int NU6_BRANCH_ID = 0xC8E71055;
	private static final int NU6_1_BRANCH_ID = 0x4DEC4DF0;

	private ZcashTransparentTransactionBuilder() {
	}

	static BitcoinySignedTransaction buildSpend(Bitcoiny bitcoiny, String xprv58, String recipient, long amount, Long feePerByte) {
		return buildSpend(bitcoiny, xprv58, Map.of(recipient, amount), feePerByte);
	}

	static BitcoinySignedTransaction buildSpend(Bitcoiny bitcoiny, String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		try {
			if (amountByRecipient.isEmpty())
				return null;

			List<Output> paymentOutputs = new ArrayList<>(amountByRecipient.size());
			long paymentTotal = 0L;
			for (Map.Entry<String, Long> entry : amountByRecipient.entrySet()) {
				long amount = entry.getValue();
				if (amount <= 0)
					return null;

				paymentOutputs.add(new Output(amount, BitcoinyScript.scriptPubKey(bitcoiny.getNetworkParameters(), entry.getKey())));
				paymentTotal = Math.addExact(paymentTotal, amount);
			}

			List<SpendCandidate> spendCandidates = spendCandidates(bitcoiny, xprv58);
			long selectedTotal = 0L;
			List<Input> selectedInputs = new ArrayList<>();
			long feeRate = bitcoiny.getSpendFeePerByte(feePerByte);
			long minChange = bitcoiny.getMinNonDustOutput().value;

			for (SpendCandidate candidate : spendCandidates) {
				selectedInputs.add(Input.p2pkh(candidate.output, candidate.key, NO_LOCKTIME_SEQUENCE));
				selectedTotal = Math.addExact(selectedTotal, candidate.output.value);

				long estimatedFee = estimateFee(feeRate, selectedInputs, paymentOutputs, minChange, selectedTotal, paymentTotal);
				if (selectedTotal >= paymentTotal + estimatedFee)
					break;
			}

			if (selectedTotal < paymentTotal)
				return null;

			List<Output> outputs = new ArrayList<>(paymentOutputs);
			long fee = estimateFee(feeRate, selectedInputs, paymentOutputs, minChange, selectedTotal, paymentTotal);
			long change = selectedTotal - paymentTotal - fee;
			if (change < 0)
				return null;

			if (change >= minChange)
				outputs.add(new Output(change, BitcoinyScript.p2pkhScript(selectedInputs.get(0).publicKeyHash)));

			return buildSignedTransaction(bitcoiny, selectedInputs, outputs, 0L);
		} catch (ForeignBlockchainException | RuntimeException e) {
			Bitcoiny.LOGGER.warn("Unable to build Zcash transparent spend transaction: {}", e.getMessage());
			return null;
		}
	}

	static BitcoinySignedTransaction buildSpendMax(Bitcoiny bitcoiny, String xprv58, String recipient, Long feePerByte) {
		try {
			List<SpendCandidate> spendCandidates = spendCandidates(bitcoiny, xprv58);
			if (spendCandidates.isEmpty())
				return null;

			List<Input> selectedInputs = new ArrayList<>(spendCandidates.size());
			long selectedTotal = 0L;
			for (SpendCandidate candidate : spendCandidates) {
				selectedInputs.add(Input.p2pkh(candidate.output, candidate.key, NO_LOCKTIME_SEQUENCE));
				selectedTotal = Math.addExact(selectedTotal, candidate.output.value);
			}

			long feeRate = bitcoiny.getSpendFeePerByte(feePerByte);
			long fee = Math.multiplyExact(feeRate, estimateSize(selectedInputs.size(), 1));
			long amount = selectedTotal - fee;
			if (amount < bitcoiny.getMinNonDustOutput().value)
				return null;

			List<Output> outputs = List.of(new Output(amount, BitcoinyScript.scriptPubKey(bitcoiny.getNetworkParameters(), recipient)));
			return buildSignedTransaction(bitcoiny, selectedInputs, outputs, 0L);
		} catch (ForeignBlockchainException | RuntimeException e) {
			Bitcoiny.LOGGER.warn("Unable to build Zcash transparent send-max transaction: {}", e.getMessage());
			return null;
		}
	}

	static BitcoinySignedTransaction buildRedeem(Bitcoiny bitcoiny, Coin amount, ECKey spendKey, List<UnspentOutput> fundingOutputs,
			byte[] redeemScriptBytes, byte[] secret, byte[] outputPublicKeyHash) throws ForeignBlockchainException {
		byte[] redeemPubKey = spendKey.getPubKey();
		return buildP2sh(bitcoiny, amount.value, spendKey, fundingOutputs, redeemScriptBytes, 0L,
				NO_LOCKTIME_SEQUENCE, outputPublicKeyHash,
				txSigBytes -> Bytes.concat(
						BitcoinyScript.pushData(secret),
						BitcoinyScript.pushData(txSigBytes),
						BitcoinyScript.pushData(redeemPubKey),
						BitcoinyScript.pushData(redeemScriptBytes)));
	}

	static BitcoinySignedTransaction buildRefund(Bitcoiny bitcoiny, Coin amount, ECKey spendKey, List<UnspentOutput> fundingOutputs,
			byte[] redeemScriptBytes, long lockTime, byte[] outputPublicKeyHash) throws ForeignBlockchainException {
		byte[] refundPubKey = spendKey.getPubKey();
		return buildP2sh(bitcoiny, amount.value, spendKey, fundingOutputs, redeemScriptBytes, lockTime,
				LOCKTIME_SEQUENCE, outputPublicKeyHash,
				txSigBytes -> Bytes.concat(
						BitcoinyScript.pushData(txSigBytes),
						BitcoinyScript.pushData(refundPubKey),
						BitcoinyScript.pushData(redeemScriptBytes)));
	}

	private static BitcoinySignedTransaction buildP2sh(Bitcoiny bitcoiny, long amount, ECKey spendKey, List<UnspentOutput> fundingOutputs,
			byte[] redeemScriptBytes, long lockTime, long sequence, byte[] outputPublicKeyHash, ScriptSigFactory scriptSigFactory)
			throws ForeignBlockchainException {
		List<Input> inputs = new ArrayList<>(fundingOutputs.size());
		for (UnspentOutput fundingOutput : fundingOutputs)
			inputs.add(Input.p2sh(fundingOutput, spendKey, redeemScriptBytes, sequence, scriptSigFactory));

		List<Output> outputs = List.of(new Output(amount, BitcoinyScript.p2pkhScript(outputPublicKeyHash)));
		return buildSignedTransaction(bitcoiny, inputs, outputs, lockTime);
	}

	private static BitcoinySignedTransaction buildSignedTransaction(Bitcoiny bitcoiny, List<Input> inputs, List<Output> outputs, long lockTime)
			throws ForeignBlockchainException {
		int nextHeight = bitcoiny.getBlockchainProvider().getCurrentHeight() + 1;
		int branchId = consensusBranchId(nextHeight);
		if (nextHeight < NU5_ACTIVATION_HEIGHT)
			throw new ForeignBlockchainException("Zcash transparent v5 transactions require NU5 activation");

		int expiryHeight = nextHeight + EXPIRY_DELTA;
		for (int inputIndex = 0; inputIndex < inputs.size(); ++inputIndex) {
			Input input = inputs.get(inputIndex);
			byte[] signature = sign(input.key, signatureHash(branchId, inputs, outputs, lockTime, expiryHeight, inputIndex));
			input.scriptSig = input.scriptSigFactory.build(signature);
		}

		byte[] rawTransaction = serialize(branchId, inputs, outputs, lockTime, expiryHeight);
		String txHash = txHash(branchId, inputs, outputs, lockTime, expiryHeight);
		return BitcoinySignedTransaction.fromRawWithTxHash(rawTransaction, txHash);
	}

	static int consensusBranchId(int height) {
		if (height >= NU6_1_ACTIVATION_HEIGHT)
			return NU6_1_BRANCH_ID;
		if (height >= NU6_ACTIVATION_HEIGHT)
			return NU6_BRANCH_ID;
		if (height >= NU5_ACTIVATION_HEIGHT)
			return NU5_BRANCH_ID;
		if (height >= CANOPY_ACTIVATION_HEIGHT)
			return CANOPY_BRANCH_ID;
		if (height >= HEARTWOOD_ACTIVATION_HEIGHT)
			return HEARTWOOD_BRANCH_ID;
		if (height >= BLOSSOM_ACTIVATION_HEIGHT)
			return BLOSSOM_BRANCH_ID;
		if (height >= SAPLING_ACTIVATION_HEIGHT)
			return SAPLING_BRANCH_ID;

		return 0;
	}

	private static byte[] signatureHash(int branchId, List<Input> inputs, List<Output> outputs, long lockTime, int expiryHeight, int inputIndex) {
		byte[] headerDigest = headerDigest(branchId, lockTime, expiryHeight);
		byte[] transparentDigest = transparentSigDigest(inputs, outputs, inputIndex);
		return transactionDigest(branchId, headerDigest, transparentDigest);
	}

	private static String txHash(int branchId, List<Input> inputs, List<Output> outputs, long lockTime, int expiryHeight) {
		byte[] digest = transactionDigest(branchId, headerDigest(branchId, lockTime, expiryHeight), transparentDigest(inputs, outputs));
		reverse(digest);
		return HashCode.fromBytes(digest).toString();
	}

	private static byte[] transactionDigest(int branchId, byte[] headerDigest, byte[] transparentDigest) {
		return blake2b256(txHashPersonalization(branchId), Bytes.concat(
				headerDigest,
				transparentDigest,
				blake2b256("ZTxIdSaplingHash", EMPTY),
				blake2b256("ZTxIdOrchardHash", EMPTY)));
	}

	private static byte[] headerDigest(int branchId, long lockTime, int expiryHeight) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeInt32(stream, HEADER);
		writeInt32(stream, VERSION_GROUP_ID);
		writeInt32(stream, branchId);
		writeUint32(stream, lockTime);
		writeInt32(stream, expiryHeight);
		return blake2b256("ZTxIdHeadersHash", stream.toByteArray());
	}

	private static byte[] transparentDigest(List<Input> inputs, List<Output> outputs) {
		return blake2b256("ZTxIdTranspaHash", Bytes.concat(
				blake2b256("ZTxIdPrevoutHash", serializePrevouts(inputs)),
				blake2b256("ZTxIdSequencHash", serializeSequences(inputs)),
				blake2b256("ZTxIdOutputsHash", serializeOutputFields(outputs))));
	}

	private static byte[] transparentSigDigest(List<Input> inputs, List<Output> outputs, int inputIndex) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		stream.write(SIGHASH_ALL);
		writeBytes(stream, blake2b256("ZTxIdPrevoutHash", serializePrevouts(inputs)));
		writeBytes(stream, blake2b256("ZTxTrAmountsHash", serializeInputAmounts(inputs)));
		writeBytes(stream, blake2b256("ZTxTrScriptsHash", serializeInputScriptPubKeys(inputs)));
		writeBytes(stream, blake2b256("ZTxIdSequencHash", serializeSequences(inputs)));
		writeBytes(stream, blake2b256("ZTxIdOutputsHash", serializeOutputFields(outputs)));
		writeBytes(stream, txinSigDigest(inputs.get(inputIndex)));
		return blake2b256("ZTxIdTranspaHash", stream.toByteArray());
	}

	private static byte[] txinSigDigest(Input input) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeOutpoint(stream, input.output);
		writeInt64(stream, input.output.value);
		writeVarBytes(stream, input.scriptPubKey);
		writeUint32(stream, input.sequence);
		return blake2b256("Zcash___TxInHash", stream.toByteArray());
	}

	private static byte[] serialize(int branchId, List<Input> inputs, List<Output> outputs, long lockTime, int expiryHeight) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeInt32(stream, HEADER);
		writeInt32(stream, VERSION_GROUP_ID);
		writeInt32(stream, branchId);
		writeUint32(stream, lockTime);
		writeInt32(stream, expiryHeight);
		writeCompactSize(stream, inputs.size());
		for (Input input : inputs) {
			writeOutpoint(stream, input.output);
			writeVarBytes(stream, input.scriptSig);
			writeUint32(stream, input.sequence);
		}
		writeCompactSize(stream, outputs.size());
		writeBytes(stream, serializeOutputFields(outputs));
		writeCompactSize(stream, 0);
		writeCompactSize(stream, 0);
		writeCompactSize(stream, 0);
		return stream.toByteArray();
	}

	private static byte[] serializePrevouts(List<Input> inputs) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		for (Input input : inputs)
			writeOutpoint(stream, input.output);
		return stream.toByteArray();
	}

	private static byte[] serializeInputAmounts(List<Input> inputs) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		for (Input input : inputs)
			writeInt64(stream, input.output.value);
		return stream.toByteArray();
	}

	private static byte[] serializeInputScriptPubKeys(List<Input> inputs) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		for (Input input : inputs)
			writeVarBytes(stream, input.scriptPubKey);
		return stream.toByteArray();
	}

	private static byte[] serializeSequences(List<Input> inputs) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		for (Input input : inputs)
			writeUint32(stream, input.sequence);
		return stream.toByteArray();
	}

	private static byte[] serializeOutputFields(List<Output> outputs) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		for (Output output : outputs) {
			writeInt64(stream, output.amount);
			writeVarBytes(stream, output.scriptPubKey);
		}
		return stream.toByteArray();
	}

	private static byte[] sign(ECKey key, byte[] hash) {
		byte[] derSignature = key.sign(Sha256Hash.wrap(hash)).encodeToDER();
		return Bytes.concat(derSignature, new byte[] { (byte) SIGHASH_ALL });
	}

	private static List<SpendCandidate> spendCandidates(Bitcoiny bitcoiny, String xprv58) throws ForeignBlockchainException {
		Set<BitcoinyDeterministicKey> walletKeys = bitcoiny.getWalletKeys(xprv58);
		List<BitcoinyDeterministicKey> sortedKeys = new ArrayList<>(walletKeys);
		sortedKeys.sort(Comparator.comparing(BitcoinyDeterministicKey::getPathAsString));

		List<SpendCandidate> candidates = new ArrayList<>();
		for (BitcoinyDeterministicKey key : sortedKeys) {
			byte[] privateKey = key.getPrivateKey();
			if (privateKey == null)
				continue;

			String address = bitcoiny.pkhToAddress(key.getPublicKeyHash());
			for (UnspentOutput unspentOutput : bitcoiny.getUnspentOutputs(address, true))
				candidates.add(new SpendCandidate(key, unspentOutput));
		}

		candidates.sort(Comparator
				.comparingInt((SpendCandidate candidate) -> candidate.output.height)
				.thenComparing(candidate -> new BigInteger(1, candidate.output.hash))
				.thenComparingInt(candidate -> candidate.output.index));
		return candidates;
	}

	private static long estimateFee(long feePerByte, List<Input> inputs, List<Output> paymentOutputs, long minChange, long selectedTotal, long paymentTotal) {
		int outputCount = paymentOutputs.size();
		long noChangeFee = feePerByte * estimateSize(inputs.size(), outputCount);
		long change = selectedTotal - paymentTotal - noChangeFee;
		if (change >= minChange)
			return feePerByte * estimateSize(inputs.size(), outputCount + 1);

		return noChangeFee;
	}

	private static long estimateSize(int inputCount, int outputCount) {
		return 4L + 4L + 4L + 4L + 4L
				+ compactSizeLength(inputCount)
				+ inputCount * 149L
				+ compactSizeLength(outputCount)
				+ outputCount * 34L
				+ 1L + 1L + 1L;
	}

	private static byte[] blake2b256(String personalization, byte[] data) {
		return blake2b256(personalization.getBytes(StandardCharsets.US_ASCII), data);
	}

	private static byte[] blake2b256(byte[] personalization, byte[] data) {
		Blake2bDigest digest = new Blake2bDigest(null, 32, null, personalization);
		digest.update(data, 0, data.length);
		byte[] output = new byte[32];
		digest.doFinal(output, 0);
		return output;
	}

	private static byte[] txHashPersonalization(int branchId) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeBytes(stream, "ZcashTxHash_".getBytes(StandardCharsets.US_ASCII));
		writeInt32(stream, branchId);
		return stream.toByteArray();
	}

	private static void writeOutpoint(ByteArrayOutputStream stream, UnspentOutput output) {
		byte[] txHash = java.util.Arrays.copyOf(output.hash, output.hash.length);
		reverse(txHash);
		writeBytes(stream, txHash);
		writeInt32(stream, output.index);
	}

	private static void writeVarBytes(ByteArrayOutputStream stream, byte[] bytes) {
		writeCompactSize(stream, bytes.length);
		writeBytes(stream, bytes);
	}

	private static void writeCompactSize(ByteArrayOutputStream stream, long value) {
		if (value < 0xfd) {
			stream.write((int) value);
			return;
		}

		if (value <= 0xffff) {
			stream.write(0xfd);
			writeInt16(stream, (int) value);
			return;
		}

		if (value <= 0xffffffffL) {
			stream.write(0xfe);
			writeUint32(stream, value);
			return;
		}

		stream.write(0xff);
		writeInt64(stream, value);
	}

	private static int compactSizeLength(long value) {
		if (value < 0xfd)
			return 1;
		if (value <= 0xffff)
			return 3;
		if (value <= 0xffffffffL)
			return 5;
		return 9;
	}

	private static void writeInt16(ByteArrayOutputStream stream, int value) {
		stream.write(value & 0xff);
		stream.write((value >>> 8) & 0xff);
	}

	private static void writeInt32(ByteArrayOutputStream stream, int value) {
		stream.write(value & 0xff);
		stream.write((value >>> 8) & 0xff);
		stream.write((value >>> 16) & 0xff);
		stream.write((value >>> 24) & 0xff);
	}

	private static void writeUint32(ByteArrayOutputStream stream, long value) {
		writeInt32(stream, (int) (value & 0xffffffffL));
	}

	private static void writeInt64(ByteArrayOutputStream stream, long value) {
		for (int i = 0; i < 8; ++i)
			stream.write((int) (value >>> (8 * i)) & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream stream, byte[] bytes) {
		stream.write(bytes, 0, bytes.length);
	}

	private static void reverse(byte[] bytes) {
		for (int left = 0, right = bytes.length - 1; left < right; ++left, --right) {
			byte tmp = bytes[left];
			bytes[left] = bytes[right];
			bytes[right] = tmp;
		}
	}

	@FunctionalInterface
	private interface ScriptSigFactory {
		byte[] build(byte[] txSigBytes);
	}

	private static final class SpendCandidate {
		private final BitcoinyDeterministicKey key;
		private final UnspentOutput output;

		private SpendCandidate(BitcoinyDeterministicKey key, UnspentOutput output) {
			this.key = key;
			this.output = output;
		}
	}

	private static final class Input {
		private final UnspentOutput output;
		private final ECKey key;
		private final byte[] publicKeyHash;
		private final byte[] scriptPubKey;
		private final long sequence;
		private final ScriptSigFactory scriptSigFactory;
		private byte[] scriptSig = new byte[0];

		private Input(UnspentOutput output, ECKey key, byte[] publicKeyHash, byte[] scriptPubKey, long sequence, ScriptSigFactory scriptSigFactory) {
			this.output = output;
			this.key = key;
			this.publicKeyHash = publicKeyHash;
			this.scriptPubKey = scriptPubKey;
			this.sequence = sequence;
			this.scriptSigFactory = scriptSigFactory;
		}

		private static Input p2pkh(UnspentOutput output, BitcoinyDeterministicKey key, long sequence) {
			byte[] publicKey = key.getPublicKey();
			byte[] publicKeyHash = key.getPublicKeyHash();
			ECKey ecKey = ECKey.fromPrivate(key.getPrivateKey(), true);
			byte[] scriptPubKey = output.script != null ? output.script : BitcoinyScript.p2pkhScript(publicKeyHash);
			return new Input(output, ecKey, publicKeyHash, scriptPubKey, sequence,
					txSigBytes -> Bytes.concat(BitcoinyScript.pushData(txSigBytes), BitcoinyScript.pushData(publicKey)));
		}

		private static Input p2sh(UnspentOutput output, ECKey key, byte[] redeemScript, long sequence, ScriptSigFactory scriptSigFactory) {
			byte[] scriptPubKey = output.script != null ? output.script : BitcoinyScript.p2shScript(Crypto.hash160(redeemScript));
			return new Input(output, key, Crypto.hash160(key.getPubKey()), scriptPubKey, sequence, scriptSigFactory);
		}
	}

	private static final class Output {
		private final long amount;
		private final byte[] scriptPubKey;

		private Output(long amount, byte[] scriptPubKey) {
			this.amount = amount;
			this.scriptPubKey = scriptPubKey;
		}
	}
}
