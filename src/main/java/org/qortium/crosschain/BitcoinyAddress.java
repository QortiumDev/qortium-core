package org.qortium.crosschain;

import org.bitcoinj.core.NetworkParameters;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;

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
	private static final long[] CASHADDR_GENERATORS = { 0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L, 0xae2eabe2a8L, 0x1e4f43e470L };
	private static final int CASHADDR_CHECKSUM_LENGTH = 8;
	private static final int CASHADDR_P2PKH_VERSION = 0;
	private static final int CASHADDR_P2SH_VERSION = 8;
	private static final int ZCASH_LEGACY_P2SH_HEADER = 0x1cbd;

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

		if (cashAddressPrefix(params) != null) {
			try {
				return fromCashAddress(params, address);
			} catch (IllegalArgumentException e) {
				// Fall through to legacy forms for backwards compatibility.
			}
		}

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
		if (decoded == null || decoded.length < 1 + Bitcoiny.HASH160_LENGTH + 4)
			throw new IllegalArgumentException("Invalid Base58Check address length");

		byte[] payload = Arrays.copyOf(decoded, decoded.length - 4);
		byte[] checksum = Crypto.doubleDigest(payload);
		for (int index = 0; index < 4; ++index)
			if (decoded[payload.length + index] != checksum[index])
				throw new IllegalArgumentException("Invalid Base58Check address checksum");

		byte[] addressHeader = headerBytes(params.getAddressHeader());
		if (startsWith(payload, addressHeader)) {
			byte[] hash = Arrays.copyOfRange(payload, addressHeader.length, payload.length);
			if (hash.length == Bitcoiny.HASH160_LENGTH)
				return new BitcoinyAddress(Type.P2PKH, hash, address);
		}

		byte[] p2shHeader = headerBytes(params.getP2SHHeader());
		if (startsWith(payload, p2shHeader)) {
			byte[] hash = Arrays.copyOfRange(payload, p2shHeader.length, payload.length);
			if (hash.length == Bitcoiny.HASH160_LENGTH)
				return new BitcoinyAddress(Type.P2SH, hash, address);
		}

		if (supportsLegacyZcashP2sh(params)) {
			byte[] zcashP2shHeader = headerBytes(ZCASH_LEGACY_P2SH_HEADER);
			if (startsWith(payload, zcashP2shHeader)) {
				byte[] hash = Arrays.copyOfRange(payload, zcashP2shHeader.length, payload.length);
				if (hash.length == Bitcoiny.HASH160_LENGTH)
					return new BitcoinyAddress(Type.P2SH, hash, address);
			}
		}

		throw new IllegalArgumentException("Address version is not valid for this network");
	}

	public static BitcoinyAddress fromPubKeyHash(NetworkParameters params, byte[] publicKeyHash) {
		String cashAddressPrefix = cashAddressPrefix(params);
		if (cashAddressPrefix != null)
			return fromCashAddressHash(cashAddressPrefix, CASHADDR_P2PKH_VERSION, Type.P2PKH, publicKeyHash);

		return fromHash(params.getAddressHeader(), Type.P2PKH, publicKeyHash);
	}

	public static BitcoinyAddress fromScriptHash(NetworkParameters params, byte[] scriptHash) {
		String cashAddressPrefix = cashAddressPrefix(params);
		if (cashAddressPrefix != null)
			return fromCashAddressHash(cashAddressPrefix, CASHADDR_P2SH_VERSION, Type.P2SH, scriptHash);

		return fromHash(params.getP2SHHeader(), Type.P2SH, scriptHash);
	}

	private static BitcoinyAddress fromHash(int header, Type type, byte[] hash) {
		return fromHash(headerBytes(header), type, hash);
	}

	private static BitcoinyAddress fromHash(byte[] header, Type type, byte[] hash) {
		if (hash == null || hash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Expected 20-byte address hash");

		byte[] payload = new byte[header.length + hash.length];
		System.arraycopy(header, 0, payload, 0, header.length);
		System.arraycopy(hash, 0, payload, header.length, hash.length);

		byte[] checksum = Crypto.doubleDigest(payload);
		byte[] encoded = new byte[payload.length + 4];
		System.arraycopy(payload, 0, encoded, 0, payload.length);
		System.arraycopy(checksum, 0, encoded, payload.length, 4);

		return new BitcoinyAddress(type, hash, Base58.encode(encoded));
	}

	private static byte[] headerBytes(int header) {
		if (header < 0)
			throw new IllegalArgumentException("Address header cannot be negative");

		if (header <= 0xff)
			return new byte[] { (byte) header };

		int byteCount = 0;
		for (int value = header; value != 0; value >>>= 8)
			++byteCount;

		byte[] bytes = new byte[byteCount];
		for (int index = byteCount - 1, value = header; index >= 0; --index, value >>>= 8)
			bytes[index] = (byte) value;

		return bytes;
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		if (bytes.length < prefix.length)
			return false;

		for (int index = 0; index < prefix.length; ++index)
			if (bytes[index] != prefix[index])
				return false;

		return true;
	}

	private static boolean supportsLegacyZcashP2sh(NetworkParameters params) {
		return params.getAddressHeader() == 60 && params.getP2SHHeader() == 85
				&& "zs".equals(params.getSegwitAddressHrp());
	}

	private static BitcoinyAddress fromCashAddressHash(String prefix, int version, Type type, byte[] hash) {
		if (hash == null || hash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Expected 20-byte address hash");

		byte[] payload = new byte[1 + hash.length];
		payload[0] = (byte) version;
		System.arraycopy(hash, 0, payload, 1, hash.length);

		byte[] convertedPayload = convertBitsToByteValues(payload, 8, 5, true);
		String encoded = encodeCashAddress(prefix, convertedPayload);
		return new BitcoinyAddress(type, hash, encoded);
	}

	private static BitcoinyAddress fromCashAddress(NetworkParameters params, String address) {
		String expectedPrefix = cashAddressPrefix(params);
		if (expectedPrefix == null)
			throw new IllegalArgumentException("Network does not support CashAddr addresses");

		CashAddressData cashAddress = decodeCashAddress(address, expectedPrefix);
		byte[] payload = convertBitsToByteValues(cashAddress.values, 5, 8, false);
		if (payload.length < 1)
			throw new IllegalArgumentException("Missing CashAddr version");

		int version = payload[0] & 0xff;
		if ((version & 0x80) != 0)
			throw new IllegalArgumentException("Unsupported CashAddr version");

		int type = (version >> 3) & 0x0f;
		int size = version & 0x07;
		if (size != 0)
			throw new IllegalArgumentException("Unsupported CashAddr hash size");

		byte[] hash = Arrays.copyOfRange(payload, 1, payload.length);
		if (hash.length != Bitcoiny.HASH160_LENGTH)
			throw new IllegalArgumentException("Invalid CashAddr payload length");

		if (type == 0)
			return new BitcoinyAddress(Type.P2PKH, hash, encodeCashAddress(cashAddress.prefix, cashAddress.values));

		if (type == 1)
			return new BitcoinyAddress(Type.P2SH, hash, encodeCashAddress(cashAddress.prefix, cashAddress.values));

		throw new IllegalArgumentException("Unsupported CashAddr type");
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
		return convertBitsToByteValues(values, fromBits, toBits, pad);
	}

	private static byte[] convertBitsToByteValues(byte[] values, int fromBits, int toBits, boolean pad) {
		int[] intValues = new int[values.length];
		for (int index = 0; index < values.length; ++index)
			intValues[index] = values[index] & 0xff;

		return convertBitsToByteValues(intValues, fromBits, toBits, pad);
	}

	private static byte[] convertBitsToByteValues(int[] values, int fromBits, int toBits, boolean pad) {
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

	private static String encodeCashAddress(String prefix, byte[] payloadValues) {
		String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
		int[] checksumValues = cashAddressChecksum(normalizedPrefix, payloadValues);
		StringBuilder builder = new StringBuilder(normalizedPrefix.length() + 1 + payloadValues.length + checksumValues.length);
		builder.append(normalizedPrefix).append(':');
		for (byte value : payloadValues)
			builder.append(BECH32_CHARSET.charAt(value & 0xff));
		for (int value : checksumValues)
			builder.append(BECH32_CHARSET.charAt(value));

		return builder.toString();
	}

	private static CashAddressData decodeCashAddress(String address, String expectedPrefix) {
		if (address == null)
			throw new IllegalArgumentException("Missing CashAddr address");

		boolean hasLower = false;
		boolean hasUpper = false;
		for (int index = 0; index < address.length(); ++index) {
			char c = address.charAt(index);
			if (c < 33 || c > 126)
				throw new IllegalArgumentException("Invalid CashAddr character");

			hasLower |= c >= 'a' && c <= 'z';
			hasUpper |= c >= 'A' && c <= 'Z';
		}

		if (hasLower && hasUpper)
			throw new IllegalArgumentException("Mixed-case CashAddr address");

		String normalized = address.toLowerCase(Locale.ROOT);
		int separatorIndex = normalized.lastIndexOf(':');
		String prefix;
		String payloadPart;
		if (separatorIndex >= 0) {
			if (separatorIndex < 1 || separatorIndex != normalized.indexOf(':'))
				throw new IllegalArgumentException("Invalid CashAddr separator position");

			prefix = normalized.substring(0, separatorIndex);
			payloadPart = normalized.substring(separatorIndex + 1);
		} else {
			prefix = expectedPrefix;
			payloadPart = normalized;
		}

		if (!expectedPrefix.equals(prefix))
			throw new IllegalArgumentException("CashAddr prefix is not valid for this network");

		if (payloadPart.length() <= CASHADDR_CHECKSUM_LENGTH)
			throw new IllegalArgumentException("Missing CashAddr payload");

		int[] valuesWithChecksum = new int[payloadPart.length()];
		for (int index = 0; index < payloadPart.length(); ++index) {
			char c = payloadPart.charAt(index);
			if (c >= BECH32_CHARSET_REV.length || BECH32_CHARSET_REV[c] < 0)
				throw new IllegalArgumentException("Invalid CashAddr data character");

			valuesWithChecksum[index] = BECH32_CHARSET_REV[c];
		}

		if (cashAddressPolymod(prefix, valuesWithChecksum) != 0)
			throw new IllegalArgumentException("Invalid CashAddr checksum");

		byte[] values = new byte[valuesWithChecksum.length - CASHADDR_CHECKSUM_LENGTH];
		for (int index = 0; index < values.length; ++index)
			values[index] = (byte) valuesWithChecksum[index];

		return new CashAddressData(prefix, values);
	}

	private static int[] cashAddressChecksum(String prefix, byte[] payloadValues) {
		int[] values = new int[prefix.length() + 1 + payloadValues.length + CASHADDR_CHECKSUM_LENGTH];
		int offset = 0;
		for (int index = 0; index < prefix.length(); ++index)
			values[offset++] = prefix.charAt(index) & 31;
		values[offset++] = 0;
		for (byte value : payloadValues)
			values[offset++] = value & 31;

		long polymod = cashAddressPolymod(values);
		int[] checksum = new int[CASHADDR_CHECKSUM_LENGTH];
		for (int index = 0; index < CASHADDR_CHECKSUM_LENGTH; ++index)
			checksum[index] = (int) ((polymod >> (5 * (CASHADDR_CHECKSUM_LENGTH - 1 - index))) & 31);

		return checksum;
	}

	private static long cashAddressPolymod(String prefix, int[] payloadValuesWithChecksum) {
		int[] values = new int[prefix.length() + 1 + payloadValuesWithChecksum.length];
		int offset = 0;
		for (int index = 0; index < prefix.length(); ++index)
			values[offset++] = prefix.charAt(index) & 31;
		values[offset++] = 0;
		for (int value : payloadValuesWithChecksum)
			values[offset++] = value;

		return cashAddressPolymod(values);
	}

	private static long cashAddressPolymod(int[] values) {
		long checksum = 1;
		for (int value : values) {
			long top = checksum >>> 35;
			checksum = ((checksum & 0x07ffffffffL) << 5) ^ value;
			for (int index = 0; index < CASHADDR_GENERATORS.length; ++index)
				if (((top >>> index) & 1L) != 0)
					checksum ^= CASHADDR_GENERATORS[index];
		}

		return checksum ^ 1L;
	}

	private static String cashAddressPrefix(NetworkParameters params) {
		if (params instanceof StaticBitcoinyParams)
			return ((StaticBitcoinyParams) params).getCashAddressPrefix();

		return null;
	}

	public Type getType() {
		return this.type;
	}

	public byte[] getPayload() {
		return Arrays.copyOf(this.payload, this.payload.length);
	}

	public static String encodeBech32Values(String hrp, byte[] payloadValues) {
		if (hrp == null || hrp.isEmpty())
			throw new IllegalArgumentException("Missing Bech32 HRP");

		String normalizedHrp = hrp.toLowerCase(Locale.ROOT);
		int[] values = new int[payloadValues.length + 6];
		for (int index = 0; index < payloadValues.length; ++index) {
			int value = payloadValues[index] & 0xff;
			if (value > 31)
				throw new IllegalArgumentException("Invalid Bech32 value");

			values[index] = value;
		}

		int polymod = bech32Polymod(hrpExpand(normalizedHrp), values) ^ 1;
		for (int index = 0; index < 6; ++index)
			values[payloadValues.length + index] = (polymod >> (5 * (5 - index))) & 31;

		StringBuilder builder = new StringBuilder(normalizedHrp.length() + 1 + values.length);
		builder.append(normalizedHrp).append('1');
		for (int value : values)
			builder.append(BECH32_CHARSET.charAt(value));

		return builder.toString();
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

	private static final class CashAddressData {
		private final String prefix;
		private final byte[] values;

		private CashAddressData(String prefix, byte[] values) {
			this.prefix = prefix;
			this.values = values;
		}
	}
}
