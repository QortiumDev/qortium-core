package org.qortium.crosschain;

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
	private static final int OP_PUSHDATA2 = 0x4d;
	private static final int OP_PUSHDATA4 = 0x4e;
	private static final int OP_2DROP = 0x6d;
	private static final int OP_DROP = 0x75;
	private static final int OP_NAME_NEW = 0x51;
	private static final int OP_NAME_FIRSTUPDATE = 0x52;
	private static final int OP_NAME_UPDATE = 0x53;
	private static final int OP_CLAIM_NAME = 0xb5;
	private static final int OP_SUPPORT_CLAIM = 0xb6;
	private static final int OP_UPDATE_CLAIM = 0xb7;
	private static final int BCH_TOKEN_PREFIX = 0xef;

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

	public static byte[] pushData(byte[] data) {
		if (data == null)
			throw new IllegalArgumentException("Missing push data");

		if (data.length <= 75) {
			byte[] script = new byte[1 + data.length];
			script[0] = (byte) data.length;
			System.arraycopy(data, 0, script, 1, data.length);
			return script;
		}

		if (data.length <= 0xff) {
			byte[] script = new byte[2 + data.length];
			script[0] = (byte) OP_PUSHDATA1;
			script[1] = (byte) data.length;
			System.arraycopy(data, 0, script, 2, data.length);
			return script;
		}

		throw new IllegalArgumentException("Push data is too large");
	}

	public static boolean isNamecoinNameOutputScript(byte[] script) {
		if (script == null || script.length == 0)
			return false;

		int offset = 1;
		switch (script[0] & 0xff) {
			case OP_NAME_NEW:
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset >= script.length || (script[offset++] & 0xff) != OP_2DROP)
					return false;
				return offset < script.length;

			case OP_NAME_FIRSTUPDATE:
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset + 2 >= script.length)
					return false;
				if ((script[offset++] & 0xff) != OP_2DROP || (script[offset++] & 0xff) != OP_2DROP)
					return false;
				return offset < script.length;

			case OP_NAME_UPDATE:
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset + 2 >= script.length)
					return false;
				if ((script[offset++] & 0xff) != OP_2DROP || (script[offset++] & 0xff) != OP_DROP)
					return false;
				return offset < script.length;

			default:
				return false;
		}
	}

	public static boolean isLbryClaimOutputScript(byte[] script) {
		if (script == null || script.length == 0)
			return false;

		int offset = 1;
		switch (script[0] & 0xff) {
			case OP_CLAIM_NAME:
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset + 2 >= script.length)
					return false;
				if ((script[offset++] & 0xff) != OP_2DROP || (script[offset++] & 0xff) != OP_DROP)
					return false;
				return offset < script.length;

			case OP_SUPPORT_CLAIM:
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset + 2 >= script.length)
					return false;
				if ((script[offset++] & 0xff) != OP_2DROP || (script[offset++] & 0xff) != OP_DROP)
					return false;
				return offset < script.length;

			case OP_UPDATE_CLAIM:
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				offset = readPushDataEnd(script, offset);
				if (offset < 0 || offset + 2 >= script.length)
					return false;
				if ((script[offset++] & 0xff) != OP_2DROP || (script[offset++] & 0xff) != OP_2DROP)
					return false;
				return offset < script.length;

			default:
				return false;
		}
	}

	public static boolean isBitcoinCashTokenOutput(byte[] script) {
		return script != null && script.length > 0 && (script[0] & 0xff) == BCH_TOKEN_PREFIX;
	}

	private static int readPushDataEnd(byte[] script, int offset) {
		if (offset < 0 || offset >= script.length)
			return -1;

		int opcode = script[offset++] & 0xff;
		long length;

		if (opcode < OP_PUSHDATA1) {
			length = opcode;
		} else if (opcode == OP_PUSHDATA1) {
			if (offset >= script.length)
				return -1;

			length = script[offset++] & 0xff;
		} else if (opcode == OP_PUSHDATA2) {
			if (offset + 1 >= script.length)
				return -1;

			length = (script[offset] & 0xffL) | ((script[offset + 1] & 0xffL) << 8);
			offset += 2;
		} else if (opcode == OP_PUSHDATA4) {
			if (offset + 3 >= script.length)
				return -1;

			length = (script[offset] & 0xffL)
					| ((script[offset + 1] & 0xffL) << 8)
					| ((script[offset + 2] & 0xffL) << 16)
					| ((script[offset + 3] & 0xffL) << 24);
			offset += 4;
		} else {
			return -1;
		}

		if (length > script.length - offset)
			return -1;

		return offset + (int) length;
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
