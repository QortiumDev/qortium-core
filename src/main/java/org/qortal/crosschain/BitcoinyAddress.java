package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;
import org.qortal.crypto.Crypto;
import org.qortal.utils.Base58;

import java.util.Arrays;
import java.util.Locale;

public final class BitcoinyAddress {

	public enum Type {
		P2PKH,
		P2SH,
		P2WPKH,
		P2WSH
	}

	private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
	private static final int[] BECH32_CHARSET_REV = new int[128];
	private static final int[] BECH32_GENERATORS = { 0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3 };

	static {
		Arrays.fill(BECH32_CHARSET_REV, -1);
		for (int index = 0; index < BECH32_CHARSET.length(); ++index)
			BECH32_CHARSET_REV[BECH32_CHARSET.charAt(index)] = index;
	}

	private final Type type;
	private final byte[] payload;
	private final String encoded;

	private BitcoinyAddress(Type type, byte[] payload, String encoded) {
		this.type = type;
		this.payload = Arrays.copyOf(payload, payload.length);
		this.encoded = encoded;
	}

	public static BitcoinyAddress fromString(NetworkParameters params, String address) {
		if (address == null)
			throw new IllegalArgumentException("Missing address");

		try {
			return fromBase58(params, address);
		} catch (IllegalArgumentException e) {
			return fromBech32(params, address);
		}
	}

	public static BitcoinyAddress fromBase58(NetworkParameters params, String address) {
		if (address == null)
			throw new IllegalArgumentException("Missing Base58 address");

		byte[] decoded = Base58.decode(address);
		if (decoded == null || decoded.length != 25)
			throw new IllegalArgumentException("Invalid Base58Check address length");

		byte[] payload = Arrays.copyOf(decoded, decoded.length - 4);
		byte[] checksum = Crypto.doubleDigest(payload);
		for (int index = 0; index < 4; ++index)
			if (decoded[payload.length + index] != checksum[index])
				throw new IllegalArgumentException("Invalid Base58Check address checksum");

		int header = payload[0] & 0xff;
		byte[] hash = Arrays.copyOfRange(payload, 1, payload.length);
		if (hash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Invalid Base58Check address payload length");

		if (header == params.getAddressHeader())
			return new BitcoinyAddress(Type.P2PKH, hash, address);

		if (header == params.getP2SHHeader())
			return new BitcoinyAddress(Type.P2SH, hash, address);

		throw new IllegalArgumentException("Address version is not valid for this network");
	}

	public static BitcoinyAddress fromPubKeyHash(NetworkParameters params, byte[] publicKeyHash) {
		return fromHash(params.getAddressHeader(), Type.P2PKH, publicKeyHash);
	}

	public static BitcoinyAddress fromScriptHash(NetworkParameters params, byte[] scriptHash) {
		return fromHash(params.getP2SHHeader(), Type.P2SH, scriptHash);
	}

	private static BitcoinyAddress fromHash(int header, Type type, byte[] hash) {
		if (hash == null || hash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Expected 20-byte address hash");

		byte[] payload = new byte[1 + hash.length];
		payload[0] = (byte) header;
		System.arraycopy(hash, 0, payload, 1, hash.length);

		byte[] checksum = Crypto.doubleDigest(payload);
		byte[] encoded = new byte[payload.length + 4];
		System.arraycopy(payload, 0, encoded, 0, payload.length);
		System.arraycopy(checksum, 0, encoded, payload.length, 4);

		return new BitcoinyAddress(type, hash, Base58.encode(encoded));
	}

	private static BitcoinyAddress fromBech32(NetworkParameters params, String address) {
		String expectedHrp = params.getSegwitAddressHrp();
		if (expectedHrp == null)
			throw new IllegalArgumentException("Network does not support Bech32 addresses");

		Bech32Data bech32 = decodeBech32(address);
		if (!expectedHrp.equals(bech32.hrp))
			throw new IllegalArgumentException("Bech32 HRP is not valid for this network");

		if (bech32.values.length == 0)
			throw new IllegalArgumentException("Missing Bech32 witness version");

		int witnessVersion = bech32.values[0];
		if (witnessVersion != 0)
			throw new IllegalArgumentException("Only witness version 0 addresses are supported");

		byte[] witnessProgram = convertBits(Arrays.copyOfRange(bech32.values, 1, bech32.values.length), 5, 8, false);
		if (witnessProgram.length == 20)
			return new BitcoinyAddress(Type.P2WPKH, witnessProgram, address);

		if (witnessProgram.length == 32)
			return new BitcoinyAddress(Type.P2WSH, witnessProgram, address);

		throw new IllegalArgumentException("Invalid witness program length");
	}

	private static Bech32Data decodeBech32(String address) {
		if (address == null)
			throw new IllegalArgumentException("Missing Bech32 address");

		boolean hasLower = false;
		boolean hasUpper = false;
		for (int index = 0; index < address.length(); ++index) {
			char c = address.charAt(index);
			if (c < 33 || c > 126)
				throw new IllegalArgumentException("Invalid Bech32 character");

			hasLower |= c >= 'a' && c <= 'z';
			hasUpper |= c >= 'A' && c <= 'Z';
		}

		if (hasLower && hasUpper)
			throw new IllegalArgumentException("Mixed-case Bech32 address");

		String normalized = address.toLowerCase(Locale.ROOT);
		int separatorIndex = normalized.lastIndexOf('1');
		if (separatorIndex < 1 || separatorIndex + 7 > normalized.length())
			throw new IllegalArgumentException("Invalid Bech32 separator position");

		String hrp = normalized.substring(0, separatorIndex);
		String dataPart = normalized.substring(separatorIndex + 1);
		int[] valuesWithChecksum = new int[dataPart.length()];
		for (int index = 0; index < dataPart.length(); ++index) {
			char c = dataPart.charAt(index);
			if (c >= BECH32_CHARSET_REV.length || BECH32_CHARSET_REV[c] < 0)
				throw new IllegalArgumentException("Invalid Bech32 data character");

			valuesWithChecksum[index] = BECH32_CHARSET_REV[c];
		}

		if (bech32Polymod(hrpExpand(hrp), valuesWithChecksum) != 1)
			throw new IllegalArgumentException("Invalid Bech32 checksum");

		return new Bech32Data(hrp, Arrays.copyOf(valuesWithChecksum, valuesWithChecksum.length - 6));
	}

	private static int[] hrpExpand(String hrp) {
		int[] expanded = new int[hrp.length() * 2 + 1];
		for (int index = 0; index < hrp.length(); ++index)
			expanded[index] = hrp.charAt(index) >> 5;

		expanded[hrp.length()] = 0;
		for (int index = 0; index < hrp.length(); ++index)
			expanded[hrp.length() + 1 + index] = hrp.charAt(index) & 31;

		return expanded;
	}

	private static int bech32Polymod(int[] hrpExpanded, int[] values) {
		int checksum = 1;
		for (int value : hrpExpanded)
			checksum = bech32PolymodStep(checksum, value);
		for (int value : values)
			checksum = bech32PolymodStep(checksum, value);

		return checksum;
	}

	private static int bech32PolymodStep(int checksum, int value) {
		int top = checksum >>> 25;
		checksum = (checksum & 0x1ffffff) << 5 ^ value;
		for (int index = 0; index < BECH32_GENERATORS.length; ++index)
			if (((top >>> index) & 1) != 0)
				checksum ^= BECH32_GENERATORS[index];

		return checksum;
	}

	private static byte[] convertBits(int[] values, int fromBits, int toBits, boolean pad) {
		int accumulator = 0;
		int bits = 0;
		int maxValue = (1 << toBits) - 1;
		int maxAccumulator = (1 << (fromBits + toBits - 1)) - 1;
		byte[] converted = new byte[(values.length * fromBits + toBits - 1) / toBits];
		int convertedLength = 0;

		for (int value : values) {
			if (value < 0 || (value >>> fromBits) != 0)
				throw new IllegalArgumentException("Invalid Bech32 value");

			accumulator = ((accumulator << fromBits) | value) & maxAccumulator;
			bits += fromBits;

			while (bits >= toBits) {
				bits -= toBits;
				converted[convertedLength++] = (byte) ((accumulator >>> bits) & maxValue);
			}
		}

		if (pad) {
			if (bits > 0)
				converted[convertedLength++] = (byte) ((accumulator << (toBits - bits)) & maxValue);
		} else if (bits >= fromBits || ((accumulator << (toBits - bits)) & maxValue) != 0) {
			throw new IllegalArgumentException("Invalid Bech32 padding");
		}

		return Arrays.copyOf(converted, convertedLength);
	}

	public Type getType() {
		return this.type;
	}

	public byte[] getPayload() {
		return Arrays.copyOf(this.payload, this.payload.length);
	}

	public boolean isP2PKH() {
		return this.type == Type.P2PKH;
	}

	@Override
	public String toString() {
		return this.encoded;
	}

	private static final class Bech32Data {
		private final String hrp;
		private final int[] values;

		private Bech32Data(String hrp, int[] values) {
			this.hrp = hrp;
			this.values = values;
		}
	}
}
