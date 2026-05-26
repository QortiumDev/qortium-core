package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.qortium.crypto.Crypto;
import org.qortium.utils.BitTwiddling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BitcoinyRawTransactionParser {

	private static final int SEGWIT_MARKER = 0x00;

	private BitcoinyRawTransactionParser() {
	}

	public static BitcoinyTransaction parse(byte[] rawTransactionBytes) {
		return parse(BitcoinyTransactionFormat.LEGACY, null, rawTransactionBytes);
	}

	public static BitcoinyTransaction parse(String txHash, byte[] rawTransactionBytes) {
		return parse(BitcoinyTransactionFormat.LEGACY, txHash, rawTransactionBytes);
	}

	public static BitcoinyTransaction parse(BitcoinyTransactionFormat transactionFormat, byte[] rawTransactionBytes) {
		return parse(transactionFormat, null, rawTransactionBytes);
	}

	public static BitcoinyTransaction parse(BitcoinyTransactionFormat transactionFormat, String txHash, byte[] rawTransactionBytes) {
		if (rawTransactionBytes == null)
			throw new IllegalArgumentException("Missing raw transaction bytes");

		if (transactionFormat == null)
			throw new IllegalArgumentException("Missing transaction format");

		Parser parser = new Parser(rawTransactionBytes);
		return parser.parse(transactionFormat, txHash);
	}

	private static String transactionHash(byte[] transactionBytes) {
		byte[] txHashBytes = Crypto.doubleDigest(transactionBytes);
		reverse(txHashBytes);
		return HashCode.fromBytes(txHashBytes).toString();
	}

	private static void reverse(byte[] bytes) {
		for (int left = 0, right = bytes.length - 1; left < right; ++left, --right) {
			byte tmp = bytes[left];
			bytes[left] = bytes[right];
			bytes[right] = tmp;
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
			builder.append(String.format("%02x", value & 0xff));

		return builder.toString();
	}

	private static final class Parser {
		private final byte[] bytes;
		private int offset = 0;

		private Parser(byte[] bytes) {
			this.bytes = bytes;
		}

		private BitcoinyTransaction parse(BitcoinyTransactionFormat transactionFormat, String txHash) {
			int transactionStart = this.offset;
			int version = readInt32();
			int prefixEnd = this.offset;

			if (hasTransactionTimestamp(transactionFormat, version)) {
				readInt32();
				prefixEnd = this.offset;
			}

			int inputSectionStart = this.offset;
			int markerOrInputCount = readUnsignedByte();
			boolean hasWitness = false;
			long inputCount;

			if (markerOrInputCount == SEGWIT_MARKER) {
				int flag = readUnsignedByte();
				if (flag == 0)
					throw new IllegalArgumentException("Invalid witness transaction flag");

				hasWitness = true;
				inputSectionStart = this.offset;
				inputCount = readVarInt();
			} else {
				inputCount = readVarInt(markerOrInputCount);
			}

			List<BitcoinyTransaction.Input> inputs = readInputs(inputCount);
			List<BitcoinyTransaction.Output> outputs = readOutputs();
			int baseTransactionEnd = this.offset;

			if (hasWitness)
				skipWitnesses(inputCount);

			int lockTimeStart = this.offset;
			int lockTime = readInt32();
			if (this.offset != this.bytes.length)
				throw new IllegalArgumentException("Raw transaction has trailing data");

			String resolvedTxHash = txHash == null
					? transactionHash(transactionBytesForTxHash(transactionStart, prefixEnd, inputSectionStart, baseTransactionEnd, lockTimeStart))
					: txHash;

			return new BitcoinyTransaction(resolvedTxHash, this.bytes.length, lockTime, null, inputs, outputs);
		}

		private byte[] transactionBytesForTxHash(int transactionStart, int prefixEnd, int inputSectionStart, int baseTransactionEnd, int lockTimeStart) {
			int prefixLength = prefixEnd - transactionStart;
			int baseSectionLength = baseTransactionEnd - inputSectionStart;
			byte[] txHashBytes = new byte[prefixLength + baseSectionLength + 4];

			System.arraycopy(this.bytes, transactionStart, txHashBytes, 0, prefixLength);
			System.arraycopy(this.bytes, inputSectionStart, txHashBytes, prefixLength, baseSectionLength);
			System.arraycopy(this.bytes, lockTimeStart, txHashBytes, prefixLength + baseSectionLength, 4);

			return txHashBytes;
		}

		private boolean hasTransactionTimestamp(BitcoinyTransactionFormat transactionFormat, int version) {
			return transactionFormat == BitcoinyTransactionFormat.TIMESTAMPED_LEGACY
					|| (transactionFormat == BitcoinyTransactionFormat.PEERCOIN && version < 3);
		}

		private List<BitcoinyTransaction.Input> readInputs(long inputCount) {
			if (inputCount > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Too many transaction inputs");

			List<BitcoinyTransaction.Input> inputs = new ArrayList<>((int) inputCount);
			for (long index = 0; index < inputCount; ++index) {
				byte[] previousTxHash = readBytes(32);
				reverse(previousTxHash);

				int outputVout = readInt32();
				byte[] scriptSig = readVarBytes();
				int sequence = readInt32();

				inputs.add(new BitcoinyTransaction.Input(toHex(scriptSig), sequence, toHex(previousTxHash), outputVout));
			}

			return inputs;
		}

		private List<BitcoinyTransaction.Output> readOutputs() {
			long outputCount = readVarInt();
			if (outputCount > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Too many transaction outputs");

			List<BitcoinyTransaction.Output> outputs = new ArrayList<>((int) outputCount);
			for (long index = 0; index < outputCount; ++index) {
				long value = readInt64();
				byte[] scriptPubKey = readVarBytes();

				outputs.add(new BitcoinyTransaction.Output(toHex(scriptPubKey), value));
			}

			return outputs;
		}

		private void skipWitnesses(long inputCount) {
			for (long inputIndex = 0; inputIndex < inputCount; ++inputIndex) {
				long witnessItemCount = readVarInt();
				for (long itemIndex = 0; itemIndex < witnessItemCount; ++itemIndex)
					readVarBytes();
			}
		}

		private int readUnsignedByte() {
			ensureAvailable(1);
			return this.bytes[this.offset++] & 0xff;
		}

		private int readInt32() {
			ensureAvailable(4);
			int value = BitTwiddling.intFromLEBytes(this.bytes, this.offset);
			this.offset += 4;
			return value;
		}

		private long readInt64() {
			ensureAvailable(8);
			long value = BitTwiddling.longFromLEBytes(this.bytes, this.offset);
			this.offset += 8;
			return value;
		}

		private byte[] readBytes(int length) {
			if (length < 0)
				throw new IllegalArgumentException("Negative length");

			ensureAvailable(length);
			byte[] result = Arrays.copyOfRange(this.bytes, this.offset, this.offset + length);
			this.offset += length;
			return result;
		}

		private byte[] readVarBytes() {
			long length = readVarInt();
			if (length > Integer.MAX_VALUE)
				throw new IllegalArgumentException("Variable byte field is too large");

			return readBytes((int) length);
		}

		private long readVarInt() {
			return readVarInt(readUnsignedByte());
		}

		private long readVarInt(int firstByte) {
			if (firstByte < 0xfd)
				return firstByte;

			if (firstByte == 0xfd)
				return readUnsignedByte() | ((long) readUnsignedByte() << 8);

			if (firstByte == 0xfe)
				return readUnsignedByte()
						| ((long) readUnsignedByte() << 8)
						| ((long) readUnsignedByte() << 16)
						| ((long) readUnsignedByte() << 24);

			long value = 0L;
			for (int i = 0; i < 8; ++i)
				value |= (long) readUnsignedByte() << (8 * i);

			if (value < 0)
				throw new IllegalArgumentException("Variable integer is too large");

			return value;
		}

		private void ensureAvailable(int length) {
			if (length > this.bytes.length - this.offset)
				throw new IllegalArgumentException("Raw transaction is truncated");
		}
	}
}
