package org.qortium.network.message;

import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Requests bundles not identified by the sender's bounded inventory. */
public class GetOnlineAccountBundlesMessage extends Message {

	public static final int MAX_IDENTIFIERS = OnlineAccountBundleTransformer.MAX_BUNDLES_PER_COHORT;
	private static final int IDENTIFIER_LENGTH = Transformer.TIMESTAMP_LENGTH
			+ Transformer.PUBLIC_KEY_LENGTH + Transformer.SHA256_LENGTH;

	private final List<BundleIdentifier> knownBundles;

	public GetOnlineAccountBundlesMessage(List<BundleIdentifier> knownBundles) throws MessageException {
		super(MessageType.GET_ONLINE_ACCOUNT_BUNDLES);
		validateCount(knownBundles);

		try {
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream(
					Transformer.INT_LENGTH + knownBundles.size() * IDENTIFIER_LENGTH);
			DataOutputStream bytes = new DataOutputStream(byteStream);
			bytes.writeInt(knownBundles.size());
			for (BundleIdentifier identifier : knownBundles) {
				validateIdentifier(identifier);
				bytes.writeLong(identifier.timestamp);
				bytes.write(identifier.nodePublicKey);
				bytes.write(identifier.commitmentHash);
			}
			this.dataBytes = byteStream.toByteArray();
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with byte-array streams", e);
		}
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
		this.knownBundles = immutableIdentifierCopies(knownBundles);
	}

	private GetOnlineAccountBundlesMessage(int id, List<BundleIdentifier> knownBundles) {
		super(id, MessageType.GET_ONLINE_ACCOUNT_BUNDLES);
		this.knownBundles = immutableIdentifierCopies(knownBundles);
	}

	public List<BundleIdentifier> getKnownBundles() {
		return immutableIdentifierCopies(this.knownBundles);
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		if (bytes.remaining() < Transformer.INT_LENGTH)
			throw new MessageException("Online-account bundle request is truncated");

		int count = bytes.getInt();
		if (count < 0 || count > MAX_IDENTIFIERS)
			throw new MessageException("Invalid online-account bundle identifier count: " + count);
		long expectedLength = (long) count * IDENTIFIER_LENGTH;
		if (expectedLength != bytes.remaining())
			throw new MessageException(expectedLength > bytes.remaining()
					? "Online-account bundle request is truncated"
					: "Online-account bundle request has trailing bytes");

		List<BundleIdentifier> knownBundles = new ArrayList<>(count);
		for (int i = 0; i < count; ++i) {
			long timestamp = bytes.getLong();
			byte[] nodePublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			bytes.get(nodePublicKey);
			byte[] commitmentHash = new byte[Transformer.SHA256_LENGTH];
			bytes.get(commitmentHash);
			knownBundles.add(new BundleIdentifier(timestamp, nodePublicKey, commitmentHash));
		}

		return new GetOnlineAccountBundlesMessage(id, knownBundles);
	}

	private static void validateCount(List<BundleIdentifier> identifiers) throws MessageException {
		if (identifiers == null)
			throw new MessageException("Online-account bundle identifiers are missing");
		if (identifiers.size() > MAX_IDENTIFIERS)
			throw new MessageException("Too many online-account bundle identifiers: " + identifiers.size());
	}

	private static void validateIdentifier(BundleIdentifier identifier) throws MessageException {
		if (identifier == null)
			throw new MessageException("Online-account bundle identifier is missing");
		if (identifier.nodePublicKey == null
				|| identifier.nodePublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw new MessageException("Invalid reward-node public key length in bundle identifier");
		if (identifier.commitmentHash == null
				|| identifier.commitmentHash.length != Transformer.SHA256_LENGTH)
			throw new MessageException("Invalid commitment length in bundle identifier");
	}

	private static List<BundleIdentifier> immutableIdentifierCopies(List<BundleIdentifier> identifiers) {
		if (identifiers == null)
			return Collections.emptyList();
		List<BundleIdentifier> copies = new ArrayList<>(identifiers.size());
		for (BundleIdentifier identifier : identifiers)
			copies.add(identifier == null ? null : new BundleIdentifier(identifier));
		return Collections.unmodifiableList(copies);
	}

	public static final class BundleIdentifier {

		private final long timestamp;
		private final byte[] nodePublicKey;
		private final byte[] commitmentHash;

		public BundleIdentifier(long timestamp, byte[] nodePublicKey, byte[] commitmentHash) {
			this.timestamp = timestamp;
			this.nodePublicKey = copy(nodePublicKey);
			this.commitmentHash = copy(commitmentHash);
		}

		public BundleIdentifier(BundleIdentifier other) {
			this(Objects.requireNonNull(other, "other").timestamp, other.nodePublicKey,
					other.commitmentHash);
		}

		public long getTimestamp() {
			return this.timestamp;
		}

		public byte[] getNodePublicKey() {
			return copy(this.nodePublicKey);
		}

		public byte[] getCommitmentHash() {
			return copy(this.commitmentHash);
		}

		@Override
		public boolean equals(Object object) {
			if (this == object)
				return true;
			if (!(object instanceof BundleIdentifier))
				return false;
			BundleIdentifier other = (BundleIdentifier) object;
			return this.timestamp == other.timestamp
					&& Arrays.equals(this.nodePublicKey, other.nodePublicKey)
					&& Arrays.equals(this.commitmentHash, other.commitmentHash);
		}

		@Override
		public int hashCode() {
			int result = Long.hashCode(this.timestamp);
			result = 31 * result + Arrays.hashCode(this.nodePublicKey);
			result = 31 * result + Arrays.hashCode(this.commitmentHash);
			return result;
		}

		private static byte[] copy(byte[] bytes) {
			return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
		}
	}
}
