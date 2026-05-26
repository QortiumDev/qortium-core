package org.qortium.crosschain;

import com.google.common.hash.HashCode;

import java.util.Locale;

public final class Bip122ChainId {

	public static final String NAMESPACE = "bip122";
	public static final String PREFIX = NAMESPACE + ":";
	public static final int REFERENCE_HEX_LENGTH = 32;
	public static final int REFERENCE_BYTE_LENGTH = REFERENCE_HEX_LENGTH / 2;

	private Bip122ChainId() {
	}

	public static String fromBlockHash(String blockHash) {
		if (blockHash == null)
			throw new IllegalArgumentException("BIP122 block hash cannot be null");

		String normalizedBlockHash = blockHash.toLowerCase(Locale.ROOT);
		if (normalizedBlockHash.length() < REFERENCE_HEX_LENGTH)
			throw new IllegalArgumentException("BIP122 block hash is too short: " + blockHash);

		String reference = normalizedBlockHash.substring(0, REFERENCE_HEX_LENGTH);
		HashCode.fromString(reference);

		return PREFIX + reference;
	}

	public static String normalize(String chainId) {
		if (chainId == null)
			throw new IllegalArgumentException("BIP122 chain id cannot be null");

		String normalizedChainId = chainId.toLowerCase(Locale.ROOT);
		String reference = getReference(normalizedChainId);
		HashCode.fromString(reference);

		return PREFIX + reference;
	}

	public static byte[] toReferenceBytes(String chainId) {
		return HashCode.fromString(getReference(normalize(chainId))).asBytes();
	}

	public static String fromReferenceBytes(byte[] referenceBytes) {
		if (referenceBytes.length != REFERENCE_BYTE_LENGTH)
			throw new IllegalArgumentException("BIP122 reference should be " + REFERENCE_BYTE_LENGTH + " bytes");

		return PREFIX + HashCode.fromBytes(referenceBytes);
	}

	private static String getReference(String chainId) {
		if (!chainId.startsWith(PREFIX))
			throw new IllegalArgumentException("Unsupported chain id namespace: " + chainId);

		String reference = chainId.substring(PREFIX.length());
		if (reference.length() != REFERENCE_HEX_LENGTH)
			throw new IllegalArgumentException("BIP122 reference should be " + REFERENCE_HEX_LENGTH + " hex characters");

		return reference;
	}

}
