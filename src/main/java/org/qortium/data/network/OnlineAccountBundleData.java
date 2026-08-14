package org.qortium.data.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, origin-independent proof that a reward node approved a canonical set of online
 * minting accounts for one online-account timestamp.
 */
public final class OnlineAccountBundleData {

	/** Canonical ordering for member public keys: lexicographic, treating every byte as unsigned. */
	public static final Comparator<Member> UNSIGNED_PUBLIC_KEY_COMPARATOR =
			(left, right) -> compareUnsigned(left.publicKey, right.publicKey);

	private final int protocolVersion;
	private final long timestamp;
	private final byte[] nodePublicKey;
	private final List<Member> members;
	private final byte[] nodeSignature;
	private final byte[] commitmentHash;

	public OnlineAccountBundleData(int protocolVersion, long timestamp, byte[] nodePublicKey,
			List<Member> members, byte[] nodeSignature, byte[] commitmentHash) {
		this.protocolVersion = protocolVersion;
		this.timestamp = timestamp;
		this.nodePublicKey = copy(nodePublicKey);
		this.members = immutableMemberCopies(members);
		this.nodeSignature = copy(nodeSignature);
		this.commitmentHash = copy(commitmentHash);
	}

	public int getProtocolVersion() {
		return this.protocolVersion;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getNodePublicKey() {
		return copy(this.nodePublicKey);
	}

	public List<Member> getMembers() {
		return immutableMemberCopies(this.members);
	}

	public byte[] getNodeSignature() {
		return copy(this.nodeSignature);
	}

	public byte[] getCommitmentHash() {
		return copy(this.commitmentHash);
	}

	/** Return defensive member copies sorted into canonical unsigned-public-key order. */
	public static List<Member> canonicalMemberCopies(List<Member> members) {
		List<Member> copies = mutableMemberCopies(members);
		copies.sort(UNSIGNED_PUBLIC_KEY_COMPARATOR);
		return Collections.unmodifiableList(copies);
	}

	/** Compare two byte arrays lexicographically, treating bytes as unsigned values. */
	public static int compareUnsigned(byte[] left, byte[] right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");

		int sharedLength = Math.min(left.length, right.length);
		for (int i = 0; i < sharedLength; ++i) {
			int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
			if (comparison != 0)
				return comparison;
		}

		return Integer.compare(left.length, right.length);
	}

	private static List<Member> immutableMemberCopies(List<Member> members) {
		return Collections.unmodifiableList(mutableMemberCopies(members));
	}

	private static List<Member> mutableMemberCopies(List<Member> members) {
		Objects.requireNonNull(members, "members");
		List<Member> copies = new ArrayList<>(members.size());
		for (Member member : members)
			copies.add(new Member(Objects.requireNonNull(member, "member")));
		return copies;
	}

	private static byte[] copy(byte[] bytes) {
		return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
	}

	public static final class Member {

		private final byte[] publicKey;
		private final int nonce;
		private final byte[] signature;

		/**
		 * Construct a member. {@code signature} can be null while preparing the commitment to sign;
		 * canonical serialization rejects unsigned members.
		 */
		public Member(byte[] publicKey, int nonce, byte[] signature) {
			this.publicKey = copy(publicKey);
			this.nonce = nonce;
			this.signature = copy(signature);
		}

		public Member(Member other) {
			this(Objects.requireNonNull(other, "other").publicKey, other.nonce, other.signature);
		}

		public byte[] getPublicKey() {
			return copy(this.publicKey);
		}

		public int getNonce() {
			return this.nonce;
		}

		public byte[] getSignature() {
			return copy(this.signature);
		}

		public boolean hasSignature() {
			return this.signature != null;
		}

		public Member withSignature(byte[] signature) {
			return new Member(this.publicKey, this.nonce, signature);
		}

		@Override
		public boolean equals(Object object) {
			if (this == object)
				return true;
			if (!(object instanceof Member))
				return false;

			Member other = (Member) object;
			return this.nonce == other.nonce
					&& Arrays.equals(this.publicKey, other.publicKey)
					&& Arrays.equals(this.signature, other.signature);
		}

		@Override
		public int hashCode() {
			int result = Integer.hashCode(this.nonce);
			result = 31 * result + Arrays.hashCode(this.publicKey);
			result = 31 * result + Arrays.hashCode(this.signature);
			return result;
		}
	}
}
