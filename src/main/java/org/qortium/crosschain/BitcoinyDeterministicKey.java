package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.NetworkParameters;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BitcoinyDeterministicKey {

	private static final int EXTENDED_KEY_PAYLOAD_LENGTH = 78;
	private static final int EXTENDED_KEY_LENGTH = EXTENDED_KEY_PAYLOAD_LENGTH + 4;
	private static final int HARDENED_BIT = 0x80000000;
	private static final X9ECParameters SECP256K1 = CustomNamedCurves.getByName("secp256k1");
	private static final BigInteger CURVE_ORDER = SECP256K1.getN();

	private final int depth;
	private final int parentFingerprint;
	private final int childNumber;
	private final byte[] chainCode;
	private final byte[] publicKey;
	private final byte[] privateKey;
	private final List<Integer> path;

	private BitcoinyDeterministicKey(int depth, int parentFingerprint, int childNumber, byte[] chainCode,
			byte[] publicKey, byte[] privateKey, List<Integer> path) {
		this.depth = depth;
		this.parentFingerprint = parentFingerprint;
		this.childNumber = childNumber;
		this.chainCode = Arrays.copyOf(chainCode, chainCode.length);
		this.publicKey = Arrays.copyOf(publicKey, publicKey.length);
		this.privateKey = privateKey == null ? null : Arrays.copyOf(privateKey, privateKey.length);
		this.path = Collections.unmodifiableList(new ArrayList<>(path));
	}

	public static BitcoinyDeterministicKey fromBase58(NetworkParameters params, String key58) {
		if (key58 == null)
			throw new IllegalArgumentException("Missing extended key");

		byte[] decoded = Base58.decode(key58);
		if (decoded == null || decoded.length != EXTENDED_KEY_LENGTH)
			throw new IllegalArgumentException("Invalid extended key length");

		byte[] payload = Arrays.copyOf(decoded, EXTENDED_KEY_PAYLOAD_LENGTH);
		byte[] checksum = Crypto.doubleDigest(payload);
		for (int index = 0; index < 4; ++index)
			if (decoded[EXTENDED_KEY_PAYLOAD_LENGTH + index] != checksum[index])
				throw new IllegalArgumentException("Invalid extended key checksum");

		ByteBuffer buffer = ByteBuffer.wrap(payload);
		int version = buffer.getInt();
		boolean hasPrivateKey = isPrivateKeyVersion(params, version);
		if (!hasPrivateKey && !isPublicKeyVersion(params, version))
			throw new IllegalArgumentException("Extended key version is not valid for this network");

		int depth = buffer.get() & 0xff;
		int parentFingerprint = buffer.getInt();
		int childNumber = buffer.getInt();
		byte[] chainCode = new byte[32];
		buffer.get(chainCode);
		byte[] keyData = new byte[33];
		buffer.get(keyData);

		byte[] privateKey = null;
		byte[] publicKey;
		if (hasPrivateKey) {
			if (keyData[0] != 0)
				throw new IllegalArgumentException("Invalid private extended key data");

			privateKey = Arrays.copyOfRange(keyData, 1, keyData.length);
			validatePrivateKey(privateKey);
			publicKey = publicKeyFromPrivate(privateKey);
		} else {
			publicKey = Arrays.copyOf(keyData, keyData.length);
			decodePublicKey(publicKey);
		}

		return new BitcoinyDeterministicKey(depth, parentFingerprint, childNumber, chainCode,
				publicKey, privateKey, rootPath(depth, childNumber));
	}

	private static boolean isPrivateKeyVersion(NetworkParameters params, int version) {
		return version == params.getBip32HeaderP2PKHpriv() || version == params.getBip32HeaderP2WPKHpriv();
	}

	private static boolean isPublicKeyVersion(NetworkParameters params, int version) {
		return version == params.getBip32HeaderP2PKHpub() || version == params.getBip32HeaderP2WPKHpub();
	}

	private static List<Integer> rootPath(int depth, int childNumber) {
		if (depth == 0)
			return Collections.emptyList();

		return Collections.singletonList(childNumber);
	}

	public BitcoinyDeterministicKey derive(int childIndex) {
		if ((childIndex & HARDENED_BIT) != 0)
			throw new IllegalArgumentException("Hardened child derivation requires private parent keys");

		byte[] childData = ByteBuffer.allocate(37)
				.put(this.publicKey)
				.putInt(childIndex)
				.array();
		byte[] derived = hmacSha512(this.chainCode, childData);

		byte[] tweakBytes = Arrays.copyOfRange(derived, 0, 32);
		BigInteger tweak = new BigInteger(1, tweakBytes);
		if (tweak.signum() <= 0 || tweak.compareTo(CURVE_ORDER) >= 0)
			throw new IllegalArgumentException("Invalid child key derivation");

		ECPoint parentPoint = decodePublicKey(this.publicKey);
		ECPoint childPoint = SECP256K1.getG().multiply(tweak).add(parentPoint).normalize();
		if (childPoint.isInfinity())
			throw new IllegalArgumentException("Invalid child public key");

		byte[] childPrivateKey = null;
		if (this.privateKey != null) {
			BigInteger parentPrivateKey = new BigInteger(1, this.privateKey);
			BigInteger privateKeyInteger = tweak.add(parentPrivateKey).mod(CURVE_ORDER);
			if (privateKeyInteger.signum() == 0)
				throw new IllegalArgumentException("Invalid child private key");

			childPrivateKey = to32Bytes(privateKeyInteger);
		}

		List<Integer> childPath = new ArrayList<>(this.path);
		childPath.add(childIndex);

		return new BitcoinyDeterministicKey(this.depth + 1, fingerprint(), childIndex,
				Arrays.copyOfRange(derived, 32, 64), childPoint.getEncoded(true), childPrivateKey, childPath);
	}

	public byte[] getPublicKey() {
		return Arrays.copyOf(this.publicKey, this.publicKey.length);
	}

	public byte[] getPrivateKey() {
		if (this.privateKey == null)
			return null;

		return Arrays.copyOf(this.privateKey, this.privateKey.length);
	}

	public boolean hasPrivateKey() {
		return this.privateKey != null;
	}

	public byte[] getPublicKeyHash() {
		return Crypto.hash160(this.publicKey);
	}

	public String getCacheKey() {
		return HashCode.fromBytes(this.publicKey).toString();
	}

	public List<Integer> getPath() {
		return this.path;
	}

	public String getPathAsString() {
		if (this.path.isEmpty())
			return "M";

		StringBuilder builder = new StringBuilder("M");
		for (int child : this.path) {
			builder.append('/');
			builder.append(child & ~HARDENED_BIT);
			if ((child & HARDENED_BIT) != 0)
				builder.append('H');
		}

		return builder.toString();
	}

	public int getDepth() {
		return this.depth;
	}

	public int getParentFingerprint() {
		return this.parentFingerprint;
	}

	public int getChildNumber() {
		return this.childNumber;
	}

	private int fingerprint() {
		byte[] identifier = Crypto.hash160(this.publicKey);
		return ByteBuffer.wrap(identifier, 0, 4).getInt();
	}

	private static byte[] hmacSha512(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(key, "HmacSHA512"));
			return mac.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA512 is unavailable", e);
		}
	}

	private static void validatePrivateKey(byte[] privateKey) {
		if (privateKey.length != 32)
			throw new IllegalArgumentException("Invalid private key length");

		BigInteger privateKeyInteger = new BigInteger(1, privateKey);
		if (privateKeyInteger.signum() <= 0 || privateKeyInteger.compareTo(CURVE_ORDER) >= 0)
			throw new IllegalArgumentException("Private key is out of range");
	}

	private static byte[] publicKeyFromPrivate(byte[] privateKey) {
		BigInteger privateKeyInteger = new BigInteger(1, privateKey);
		return SECP256K1.getG().multiply(privateKeyInteger).normalize().getEncoded(true);
	}

	private static ECPoint decodePublicKey(byte[] publicKey) {
		if (publicKey.length != 33 || (publicKey[0] != 0x02 && publicKey[0] != 0x03))
			throw new IllegalArgumentException("Invalid compressed public key");

		return SECP256K1.getCurve().decodePoint(publicKey).normalize();
	}

	private static byte[] to32Bytes(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes.length == 32)
			return bytes;

		byte[] result = new byte[32];
		if (bytes.length > 32)
			System.arraycopy(bytes, bytes.length - 32, result, 0, 32);
		else
			System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);

		return result;
	}
}
