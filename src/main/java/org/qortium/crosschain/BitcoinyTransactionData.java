package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.qortium.crypto.Crypto;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class BitcoinyTransactionData {

	public static final long NO_LOCKTIME_SEQUENCE = 0xffffffffL;

	private final int version;
	private final Long timestamp;
	private final List<Input> inputs;
	private final List<Output> outputs;
	private final long lockTime;

	public BitcoinyTransactionData(int version, List<Input> inputs, List<Output> outputs, long lockTime) {
		this(version, null, inputs, outputs, lockTime);
	}

	public BitcoinyTransactionData(int version, Long timestamp, List<Input> inputs, List<Output> outputs, long lockTime) {
		if (inputs == null)
			throw new IllegalArgumentException("Missing transaction inputs");
		if (outputs == null)
			throw new IllegalArgumentException("Missing transaction outputs");
		if (inputs.isEmpty())
			throw new IllegalArgumentException("Transaction has no inputs");
		if (outputs.isEmpty())
			throw new IllegalArgumentException("Transaction has no outputs");

		if (timestamp != null)
			validateUnsignedInt(timestamp, "timestamp");
		validateUnsignedInt(lockTime, "lockTime");

		this.version = version;
		this.timestamp = timestamp;
		this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
		this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
		this.lockTime = lockTime;
	}

	public static BitcoinyTransactionData fromParsedLegacy(int version, BitcoinyTransaction transaction) {
		if (transaction == null)
			throw new IllegalArgumentException("Missing parsed transaction");

		List<Input> inputs = transaction.inputs.stream()
				.map(input -> new Input(input.outputTxHash, Integer.toUnsignedLong(input.outputVout),
						HashCode.fromString(input.scriptSig).asBytes(), Integer.toUnsignedLong(input.sequence)))
				.collect(Collectors.toList());

		List<Output> outputs = transaction.outputs.stream()
				.map(output -> new Output(output.value, HashCode.fromString(output.scriptPubKey).asBytes()))
				.collect(Collectors.toList());

		return new BitcoinyTransactionData(version, inputs, outputs, Integer.toUnsignedLong(transaction.locktime));
	}

	public int getVersion() {
		return this.version;
	}

	public Long getTimestamp() {
		return this.timestamp;
	}

	public List<Input> getInputs() {
		return this.inputs;
	}

	public List<Output> getOutputs() {
		return this.outputs;
	}

	public long getLockTime() {
		return this.lockTime;
	}

	public byte[] serialize() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		writeInt32(output, this.version);
		if (this.timestamp != null)
			writeUnsignedInt32(output, this.timestamp);

		writeVarInt(output, this.inputs.size());
		for (Input input : this.inputs)
			input.serialize(output);

		writeVarInt(output, this.outputs.size());
		for (Output transactionOutput : this.outputs)
			transactionOutput.serialize(output);

		writeUnsignedInt32(output, this.lockTime);

		return output.toByteArray();
	}

	public String txHash() {
		byte[] hash = Crypto.doubleDigest(serialize());
		reverse(hash);
		return HashCode.fromBytes(hash).toString();
	}

	public static final class Input {
		private final String previousTxHash;
		private final long outputIndex;
		private final byte[] scriptSig;
		private final long sequence;

		public Input(String previousTxHash, long outputIndex, byte[] scriptSig, long sequence) {
			if (previousTxHash == null || previousTxHash.length() != 64)
				throw new IllegalArgumentException("Expected 32-byte previous transaction hash");
			try {
				HashCode.fromString(previousTxHash);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Expected hexadecimal previous transaction hash", e);
			}
			if (scriptSig == null)
				throw new IllegalArgumentException("Missing scriptSig");

			validateUnsignedInt(outputIndex, "outputIndex");
			validateUnsignedInt(sequence, "sequence");

			this.previousTxHash = previousTxHash;
			this.outputIndex = outputIndex;
			this.scriptSig = Arrays.copyOf(scriptSig, scriptSig.length);
			this.sequence = sequence;
		}

		public String getPreviousTxHash() {
			return this.previousTxHash;
		}

		public long getOutputIndex() {
			return this.outputIndex;
		}

		public byte[] getScriptSig() {
			return Arrays.copyOf(this.scriptSig, this.scriptSig.length);
		}

		public long getSequence() {
			return this.sequence;
		}

		private void serialize(ByteArrayOutputStream output) {
			byte[] previousHash = HashCode.fromString(this.previousTxHash).asBytes();
			reverse(previousHash);
			output.write(previousHash, 0, previousHash.length);
			writeUnsignedInt32(output, this.outputIndex);
			writeVarBytes(output, this.scriptSig);
			writeUnsignedInt32(output, this.sequence);
		}
	}

	public static final class Output {
		private final long value;
		private final byte[] scriptPubKey;

		public Output(long value, byte[] scriptPubKey) {
			if (value < 0)
				throw new IllegalArgumentException("Negative output value");
			if (scriptPubKey == null)
				throw new IllegalArgumentException("Missing scriptPubKey");

			this.value = value;
			this.scriptPubKey = Arrays.copyOf(scriptPubKey, scriptPubKey.length);
		}

		public long getValue() {
			return this.value;
		}

		public byte[] getScriptPubKey() {
			return Arrays.copyOf(this.scriptPubKey, this.scriptPubKey.length);
		}

		private void serialize(ByteArrayOutputStream output) {
			writeInt64(output, this.value);
			writeVarBytes(output, this.scriptPubKey);
		}
	}

	private static void writeVarBytes(ByteArrayOutputStream output, byte[] bytes) {
		writeVarInt(output, bytes.length);
		output.write(bytes, 0, bytes.length);
	}

	private static void writeVarInt(ByteArrayOutputStream output, long value) {
		if (value < 0)
			throw new IllegalArgumentException("Negative variable integer");

		if (value < 0xfd) {
			output.write((int) value);
			return;
		}

		if (value <= 0xffffL) {
			output.write(0xfd);
			writeUnsignedInt16(output, value);
			return;
		}

		if (value <= 0xffffffffL) {
			output.write(0xfe);
			writeUnsignedInt32(output, value);
			return;
		}

		output.write(0xff);
		writeInt64(output, value);
	}

	private static void writeUnsignedInt16(ByteArrayOutputStream output, long value) {
		output.write((int) value & 0xff);
		output.write((int) (value >>> 8) & 0xff);
	}

	private static void writeInt32(ByteArrayOutputStream output, int value) {
		output.write(value & 0xff);
		output.write((value >>> 8) & 0xff);
		output.write((value >>> 16) & 0xff);
		output.write((value >>> 24) & 0xff);
	}

	private static void writeUnsignedInt32(ByteArrayOutputStream output, long value) {
		validateUnsignedInt(value, "uint32");
		writeInt32(output, (int) value);
	}

	private static void writeInt64(ByteArrayOutputStream output, long value) {
		for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE)
			output.write((int) (value >>> shift) & 0xff);
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
}
