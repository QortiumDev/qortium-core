package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Script helpers for standard Bitcoin-like output scripts.
 */
public final class BitcoinyScript {

	private static final int OP_0 = 0x00;
	private static final int OP_DUP = 0x76;
	private static final int OP_HASH160 = 0xa9;
	private static final int OP_EQUAL = 0x87;
	private static final int OP_EQUALVERIFY = 0x88;
	private static final int OP_CHECKSIG = 0xac;
	private static final int OP_PUSHDATA1 = 0x4c;

	private BitcoinyScript() {
	}

	public static byte[] scriptPubKey(NetworkParameters params, String address) {
		return scriptPubKey(BitcoinyAddress.fromString(params, address));
	}

	public static byte[] scriptPubKey(BitcoinyAddress address) {
		switch (address.getType()) {
			case P2PKH:
				return p2pkhScript(address.getPayload());

			case P2SH:
				return p2shScript(address.getPayload());

			case P2WPKH:
			case P2WSH:
				return witnessV0Script(address.getPayload());

			default:
				throw new IllegalArgumentException("Unsupported address type");
		}
	}

	public static byte[] p2pkhScript(byte[] publicKeyHash) {
		if (publicKeyHash == null || publicKeyHash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Expected 20-byte public key hash");

		byte[] script = new byte[25];
		script[0] = (byte) OP_DUP;
		script[1] = (byte) OP_HASH160;
		script[2] = (byte) publicKeyHash.length;
		System.arraycopy(publicKeyHash, 0, script, 3, publicKeyHash.length);
		script[23] = (byte) OP_EQUALVERIFY;
		script[24] = (byte) OP_CHECKSIG;

		return script;
	}

	public static byte[] p2shScript(byte[] scriptHash) {
		if (scriptHash == null || scriptHash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Expected 20-byte script hash");

		byte[] script = new byte[23];
		script[0] = (byte) OP_HASH160;
		script[1] = (byte) scriptHash.length;
		System.arraycopy(scriptHash, 0, script, 2, scriptHash.length);
		script[22] = (byte) OP_EQUAL;

		return script;
	}

	public static byte[] witnessV0Script(byte[] witnessProgram) {
		if (witnessProgram == null || (witnessProgram.length != Bitcoiny.HASH160_LENGTH && witnessProgram.length != 32))
			throw new IllegalArgumentException("Expected 20-byte or 32-byte witness program");

		byte[] script = new byte[2 + witnessProgram.length];
		script[0] = (byte) OP_0;
		script[1] = (byte) witnessProgram.length;
		System.arraycopy(witnessProgram, 0, script, 2, witnessProgram.length);

		return script;
	}

	public static List<byte[]> extractScriptSigChunks(byte[] scriptSigBytes) {
		List<byte[]> chunks = new ArrayList<>();

		int offset = 0;
		while (offset < scriptSigBytes.length) {
			int pushLength = scriptSigBytes[offset++] & 0xff;

			if (pushLength > OP_PUSHDATA1)
				return Collections.emptyList();

			if (pushLength == OP_PUSHDATA1) {
				if (offset >= scriptSigBytes.length)
					return Collections.emptyList();

				pushLength = scriptSigBytes[offset++] & 0xff;
			}

			if (pushLength > scriptSigBytes.length - offset)
				return Collections.emptyList();

			chunks.add(Arrays.copyOfRange(scriptSigBytes, offset, offset + pushLength));
			offset += pushLength;
		}

		return chunks;
	}
}
