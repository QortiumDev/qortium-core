package org.qortium.crosschain;

import com.google.common.primitives.Bytes;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.qortium.crypto.Crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LegacyTransactionBuilder {

	private static final int DEFAULT_VERSION = 1;
	private static final int SIGHASH_ALL = 0x01;
	private static final byte[] EMPTY_SCRIPT = new byte[0];
	private static final long NO_LOCKTIME_SEQUENCE = BitcoinyHTLC.NO_LOCKTIME_NO_RBF_SEQUENCE;

	private LegacyTransactionBuilder() {
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
			long feeRate = feePerByte != null ? feePerByte : Math.max(1L, bitcoiny.getFeePerKb().value / 1000L);
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
			Bitcoiny.LOGGER.warn("Unable to build legacy spend transaction: {}", e.getMessage());
			return null;
		}
	}

	private static BitcoinySignedTransaction buildSignedTransaction(Bitcoiny bitcoiny, List<Input> inputs, List<Output> outputs, long lockTime) {
		validateUnsignedInt(lockTime, "lockTime");

		Integer configuredVersion = bitcoiny.getSpendTransactionVersion();
		int version = configuredVersion != null ? configuredVersion : DEFAULT_VERSION;
		for (int inputIndex = 0; inputIndex < inputs.size(); ++inputIndex) {
			Input input = inputs.get(inputIndex);
			byte[] signature = sign(input.key, signatureHash(version, inputs, outputs, lockTime, inputIndex));
			input.scriptSig = input.scriptSigFactory.build(signature);
		}

		byte[] rawTransaction = serialize(version, inputs, outputs, lockTime, -1);
		return BitcoinySignedTransaction.fromRaw(rawTransaction);
	}

	private static byte[] signatureHash(int version, List<Input> inputs, List<Output> outputs, long lockTime, int inputIndex) {
		byte[] signaturePreimage = serialize(version, inputs, outputs, lockTime, inputIndex);
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeBytes(stream, signaturePreimage);
		writeInt32(stream, SIGHASH_ALL);
		return Crypto.doubleDigest(stream.toByteArray());
	}

	private static byte[] serialize(int version, List<Input> inputs, List<Output> outputs, long lockTime, int signatureInputIndex) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		writeInt32(stream, version);
		writeCompactSize(stream, inputs.size());
		for (int index = 0; index < inputs.size(); ++index) {
			Input input = inputs.get(index);
			writeOutpoint(stream, input.output);
			byte[] script = signatureInputIndex < 0 ? input.scriptSig : (index == signatureInputIndex ? input.scriptCode : EMPTY_SCRIPT);
			writeVarBytes(stream, script);
			writeUint32(stream, input.sequence);
		}

		writeCompactSize(stream, outputs.size());
		for (Output output : outputs) {
			writeInt64(stream, output.amount);
			writeVarBytes(stream, output.scriptPubKey);
		}

		writeUint32(stream, lockTime);
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
		return 4L
				+ compactSizeLength(inputCount)
				+ inputCount * 149L
				+ compactSizeLength(outputCount)
				+ outputCount * 34L
				+ 4L;
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
		validateUnsignedInt(value, "uint32");
		writeInt32(stream, (int) (value & 0xffffffffL));
	}

	private static void writeInt64(ByteArrayOutputStream stream, long value) {
		for (int i = 0; i < Long.BYTES; ++i)
			stream.write((int) (value >>> (8 * i)) & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream stream, byte[] bytes) {
		stream.write(bytes, 0, bytes.length);
	}

	private static void validateUnsignedInt(long value, String fieldName) {
		if (value < 0 || value > 0xffffffffL)
			throw new IllegalArgumentException(fieldName + " is out of uint32 range");
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
		private final byte[] scriptCode;
		private final long sequence;
		private final ScriptSigFactory scriptSigFactory;
		private byte[] scriptSig = EMPTY_SCRIPT;

		private Input(UnspentOutput output, ECKey key, byte[] publicKeyHash, byte[] scriptCode, long sequence, ScriptSigFactory scriptSigFactory) {
			this.output = output;
			this.key = key;
			this.publicKeyHash = publicKeyHash;
			this.scriptCode = scriptCode;
			this.sequence = sequence;
			this.scriptSigFactory = scriptSigFactory;
		}

		private static Input p2pkh(UnspentOutput output, BitcoinyDeterministicKey key, long sequence) {
			byte[] publicKey = key.getPublicKey();
			byte[] publicKeyHash = key.getPublicKeyHash();
			ECKey ecKey = ECKey.fromPrivate(key.getPrivateKey(), true);
			byte[] scriptCode = BitcoinyScript.p2pkhScript(publicKeyHash);
			return new Input(output, ecKey, publicKeyHash, scriptCode, sequence,
					txSigBytes -> Bytes.concat(BitcoinyScript.pushData(txSigBytes), BitcoinyScript.pushData(publicKey)));
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
