package org.qortium.controller;

import org.qortium.arbitrary.misc.Service;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class AutoUpdateManifest {

	static final byte[] QDN_V1_MAGIC = new byte[] {'Q', 'A', 'U', '1'};
	static final int GIT_COMMIT_HASH_LENGTH = 20;
	static final int SIGNATURE_LENGTH = 64;
	static final int QDN_V1_BASE_LENGTH = QDN_V1_MAGIC.length + Transformer.TIMESTAMP_LENGTH
			+ GIT_COMMIT_HASH_LENGTH + Transformer.SHA256_LENGTH + 1;
	static final int QDN_V1_WITH_SIGNATURE_LENGTH = QDN_V1_BASE_LENGTH + SIGNATURE_LENGTH;

	public static final String QDN_UPDATE_NAME = "qortium";
	public static final Service QDN_UPDATE_SERVICE = Service.AUTO_UPDATE_BINARY;
	public static final String QDN_UPDATE_PATH = "qortium.update";

	private final long timestamp;
	private final byte[] commitHash;
	private final byte[] updateHash;
	private final byte[] binarySignature;

	private AutoUpdateManifest(long timestamp, byte[] commitHash, byte[] updateHash, byte[] binarySignature) {
		this.timestamp = timestamp;
		this.commitHash = commitHash;
		this.updateHash = updateHash;
		this.binarySignature = binarySignature;
	}

	public static AutoUpdateManifest parse(byte[] data) {
		if (data == null)
			throw new IllegalArgumentException("Missing auto-update manifest data");

		if (data.length == QDN_V1_BASE_LENGTH || data.length == QDN_V1_WITH_SIGNATURE_LENGTH)
			return parseQdnV1(data);

		throw new IllegalArgumentException(String.format("Unsupported auto-update manifest length %d", data.length));
	}

	public static AutoUpdateManifest qdnV1(long timestamp, byte[] commitHash, byte[] updateHash, byte[] binarySignature) {
		validateCommitHash(commitHash);
		validateUpdateHash(updateHash);
		validateBinarySignature(binarySignature);

		return new AutoUpdateManifest(timestamp, commitHash.clone(), updateHash.clone(),
				binarySignature != null ? binarySignature.clone() : null);
	}

	private static AutoUpdateManifest parseQdnV1(byte[] data) {
		ByteBuffer byteBuffer = ByteBuffer.wrap(data);

		byte[] magic = new byte[QDN_V1_MAGIC.length];
		byteBuffer.get(magic);
		if (!Arrays.equals(QDN_V1_MAGIC, magic))
			throw new IllegalArgumentException("Unsupported auto-update manifest version");

		long timestamp = byteBuffer.getLong();
		byte[] commitHash = new byte[GIT_COMMIT_HASH_LENGTH];
		byteBuffer.get(commitHash);
		byte[] updateHash = new byte[Transformer.SHA256_LENGTH];
		byteBuffer.get(updateHash);

		int signatureLength = Byte.toUnsignedInt(byteBuffer.get());
		if (signatureLength != 0 && signatureLength != SIGNATURE_LENGTH)
			throw new IllegalArgumentException("Invalid auto-update binary signature length");

		if (signatureLength == 0 && byteBuffer.hasRemaining())
			throw new IllegalArgumentException("Unexpected trailing auto-update manifest data");

		if (signatureLength == SIGNATURE_LENGTH && byteBuffer.remaining() != SIGNATURE_LENGTH)
			throw new IllegalArgumentException("Auto-update binary signature length mismatch");

		byte[] binarySignature = null;
		if (signatureLength == SIGNATURE_LENGTH) {
			binarySignature = new byte[SIGNATURE_LENGTH];
			byteBuffer.get(binarySignature);
		}

		return qdnV1(timestamp, commitHash, updateHash, binarySignature);
	}

	public byte[] toBytes() {
		ByteBuffer byteBuffer = ByteBuffer.allocate(this.binarySignature == null ? QDN_V1_BASE_LENGTH : QDN_V1_WITH_SIGNATURE_LENGTH);
		byteBuffer.put(QDN_V1_MAGIC);
		byteBuffer.putLong(this.timestamp);
		byteBuffer.put(this.commitHash);
		byteBuffer.put(this.updateHash);
		byteBuffer.put((byte) (this.binarySignature == null ? 0 : SIGNATURE_LENGTH));
		if (this.binarySignature != null)
			byteBuffer.put(this.binarySignature);

		return byteBuffer.array();
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getCommitHash() {
		return this.commitHash.clone();
	}

	public String getCommitHashHex() {
		StringBuilder stringBuilder = new StringBuilder(GIT_COMMIT_HASH_LENGTH * 2);
		for (byte b : this.commitHash)
			stringBuilder.append(String.format("%02x", b));

		return stringBuilder.toString();
	}

	public byte[] getUpdateHash() {
		return this.updateHash.clone();
	}

	public byte[] getBinarySignature() {
		return this.binarySignature != null ? this.binarySignature.clone() : null;
	}

	public String getBinarySignature58() {
		return this.binarySignature != null ? Base58.encode(this.binarySignature) : null;
	}

	public String getQdnName() {
		return QDN_UPDATE_NAME;
	}

	public Service getQdnService() {
		return QDN_UPDATE_SERVICE;
	}

	public String getQdnIdentifier() {
		return getCommitHashHex();
	}

	public String getQdnPath() {
		return QDN_UPDATE_PATH;
	}

	private static void validateCommitHash(byte[] commitHash) {
		if (commitHash == null || commitHash.length != GIT_COMMIT_HASH_LENGTH)
			throw new IllegalArgumentException("Auto-update commit hash must be 20 bytes");
	}

	private static void validateUpdateHash(byte[] updateHash) {
		if (updateHash == null || updateHash.length != Transformer.SHA256_LENGTH)
			throw new IllegalArgumentException("Auto-update SHA-256 hash must be 32 bytes");
	}

	private static void validateBinarySignature(byte[] binarySignature) {
		if (binarySignature != null && binarySignature.length != SIGNATURE_LENGTH)
			throw new IllegalArgumentException("Auto-update binary signature must be 64 bytes");
	}
}
