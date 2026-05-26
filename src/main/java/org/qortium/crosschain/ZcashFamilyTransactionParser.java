package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.qortium.transform.TransformationException;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class ZcashFamilyTransactionParser {

	private static final int OVERWINTER_VERSION_GROUP_ID = 0x03C48270;
	private static final int SAPLING_VERSION_GROUP_ID = 0x892F2085;
	private static final int ZIP225_VERSION_GROUP_ID = 0x26A7270A;

	private ZcashFamilyTransactionParser() {
	}

	public static BitcoinyTransaction deserializeRawTransaction(String rawTransactionHex) throws TransformationException {
		byte[] rawTransactionData = HashCode.fromString(rawTransactionHex).asBytes();
		return deserializeRawTransaction(rawTransactionData);
	}

	public static BitcoinyTransaction deserializeRawTransaction(byte[] rawTransactionData) throws TransformationException {
		return deserializeRawTransaction(null, rawTransactionData);
	}

	public static BitcoinyTransaction deserializeRawTransaction(String txHash, byte[] rawTransactionData) throws TransformationException {
		ByteBuffer byteBuffer = ByteBuffer.wrap(rawTransactionData);

		int header = BitTwiddling.readU32(byteBuffer);
		boolean overwintered = (header & 0x80000000) != 0;
		int version = header & 0x7FFFFFFF;

		int versionGroupId = 0;
		if (overwintered)
			versionGroupId = BitTwiddling.readU32(byteBuffer);

		boolean isOverwinterV3 = overwintered && versionGroupId == OVERWINTER_VERSION_GROUP_ID && version == 3;
		boolean isSaplingV4 = overwintered && versionGroupId == SAPLING_VERSION_GROUP_ID && version == 4;
		boolean isZip225V5 = overwintered && versionGroupId == ZIP225_VERSION_GROUP_ID && version == 5;
		if (overwintered && !(isOverwinterV3 || isSaplingV4 || isZip225V5))
			throw new TransformationException("Unknown transaction format");

		int locktime = 0;
		if (isZip225V5) {
			BitTwiddling.readU32(byteBuffer); // consensus branch id
			locktime = BitTwiddling.readU32(byteBuffer);
			BitTwiddling.readU32(byteBuffer); // expiry height
		}

		List<BitcoinyTransaction.Input> inputs = new ArrayList<>();
		int vinCount = readCompactSize(byteBuffer);
		for (int i = 0; i < vinCount; i++) {
			byte[] outpointHashBytes = new byte[32];
			byteBuffer.get(outpointHashBytes);
			String outpointHash = HashCode.fromBytes(outpointHashBytes).toString();

			int vout = BitTwiddling.readU32(byteBuffer);

			int scriptSigLength = readCompactSize(byteBuffer);
			byte[] scriptSigBytes = new byte[scriptSigLength];
			byteBuffer.get(scriptSigBytes);
			String scriptSig = HashCode.fromBytes(scriptSigBytes).toString();

			int sequence = BitTwiddling.readU32(byteBuffer);

			inputs.add(new BitcoinyTransaction.Input(scriptSig, sequence, outpointHash, vout));
		}

		List<BitcoinyTransaction.Output> outputs = new ArrayList<>();
		int voutCount = readCompactSize(byteBuffer);
		for (int i = 0; i < voutCount; i++) {
			byte[] amountBytes = new byte[8];
			byteBuffer.get(amountBytes);
			long amount = BitTwiddling.longFromLEBytes(amountBytes, 0);

			int scriptPubkeySize = readCompactSize(byteBuffer);
			byte[] scriptPubkeyBytes = new byte[scriptPubkeySize];
			byteBuffer.get(scriptPubkeyBytes);
			String scriptPubKey = HashCode.fromBytes(scriptPubkeyBytes).toString();

			outputs.add(new BitcoinyTransaction.Output(scriptPubKey, amount, null));
		}

		if (!isZip225V5) {
			byte[] locktimeBytes = new byte[4];
			byteBuffer.get(locktimeBytes);
			locktime = BitTwiddling.intFromLEBytes(locktimeBytes, 0);
		}

		if (isOverwinterV3 || isSaplingV4) {
			byte[] expiryHeightBytes = new byte[4];
			byteBuffer.get(expiryHeightBytes);
		}

		// This parser intentionally stops after transparent fields. Shielded spend/output
		// data can be added when a chain integration needs full shielded transaction
		// introspection rather than HTLC-transparent metadata.
		return new BitcoinyTransaction(txHash, rawTransactionData.length, locktime, null, inputs, outputs);
	}

	private static int readCompactSize(ByteBuffer byteBuffer) throws TransformationException {
		int first = BitTwiddling.readU8(byteBuffer);
		if (first < 0xfd)
			return first;

		long value;
		if (first == 0xfd) {
			value = BitTwiddling.readU8(byteBuffer) | ((long) BitTwiddling.readU8(byteBuffer) << 8);
		} else if (first == 0xfe) {
			value = BitTwiddling.readU32(byteBuffer) & 0xffffffffL;
		} else {
			byte[] bytes = new byte[8];
			byteBuffer.get(bytes);
			value = BitTwiddling.longFromLEBytes(bytes, 0);
		}

		if (value < 0 || value > Integer.MAX_VALUE)
			throw new TransformationException("Compact size is too large");

		return (int) value;
	}
}
