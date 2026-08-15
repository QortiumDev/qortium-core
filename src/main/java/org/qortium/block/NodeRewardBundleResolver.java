package org.qortium.block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure deterministic overlap resolution for node reward bundles.
 * <p>
 * Capture validation owns signatures, eligibility and canonical member order.
 * This resolver owns the separate payout rule that one self-share public key
 * can survive in at most one bundle.
 */
public final class NodeRewardBundleResolver {

	private static final Comparator<byte[]> UNSIGNED_BYTES = NodeRewardBundleResolver::compareUnsigned;

	private NodeRewardBundleResolver() {
	}

	public static final class DeclaredBundle {
		private final byte[] nodePublicKey;
		private final List<byte[]> memberPublicKeys;

		public DeclaredBundle(byte[] nodePublicKey, Collection<byte[]> memberPublicKeys) {
			if (nodePublicKey == null)
				throw new IllegalArgumentException("Missing reward-node public key");
			if (memberPublicKeys == null || memberPublicKeys.isEmpty())
				throw new IllegalArgumentException("Reward-node bundle has no members");

			this.nodePublicKey = Arrays.copyOf(nodePublicKey, nodePublicKey.length);
			this.memberPublicKeys = copyAndRejectDuplicates(memberPublicKeys);
		}

		public byte[] getNodePublicKey() {
			return Arrays.copyOf(this.nodePublicKey, this.nodePublicKey.length);
		}

		public List<byte[]> getMemberPublicKeys() {
			return copyKeys(this.memberPublicKeys);
		}

		public int getOriginalMemberCount() {
			return this.memberPublicKeys.size();
		}
	}

	public static final class ResolvedBundle {
		private final byte[] nodePublicKey;
		private final int originalMemberCount;
		private final List<byte[]> memberPublicKeys;

		private ResolvedBundle(byte[] nodePublicKey, int originalMemberCount, List<byte[]> memberPublicKeys) {
			this.nodePublicKey = Arrays.copyOf(nodePublicKey, nodePublicKey.length);
			this.originalMemberCount = originalMemberCount;
			this.memberPublicKeys = copyKeys(memberPublicKeys);
		}

		public byte[] getNodePublicKey() {
			return Arrays.copyOf(this.nodePublicKey, this.nodePublicKey.length);
		}

		public int getOriginalMemberCount() {
			return this.originalMemberCount;
		}

		public List<byte[]> getMemberPublicKeys() {
			return copyKeys(this.memberPublicKeys);
		}
	}

	/**
	 * Resolve account overlap using original declared sizes, then unsigned node-key ordering.
	 * Returned bundles and members are canonically sorted and empty losing bundles are omitted.
	 */
	public static List<ResolvedBundle> resolveOverlaps(Collection<DeclaredBundle> declaredBundles) {
		if (declaredBundles == null || declaredBundles.isEmpty())
			return List.of();

		List<DeclaredBundle> bundles = new ArrayList<>(declaredBundles);
		bundles.sort(Comparator.comparing(DeclaredBundle::getNodePublicKey, UNSIGNED_BYTES));
		rejectDuplicateNodePublicKeys(bundles);

		Map<Key, List<DeclaredBundle>> ownersByMember = new HashMap<>();
		for (DeclaredBundle bundle : bundles)
			for (byte[] memberPublicKey : bundle.memberPublicKeys)
				ownersByMember.computeIfAbsent(new Key(memberPublicKey), ignored -> new ArrayList<>()).add(bundle);

		Comparator<DeclaredBundle> winnerOrder = Comparator
				.comparingInt(DeclaredBundle::getOriginalMemberCount)
				.thenComparing(DeclaredBundle::getNodePublicKey, UNSIGNED_BYTES);

		Map<DeclaredBundle, List<byte[]>> survivingMembers = new HashMap<>();
		for (Map.Entry<Key, List<DeclaredBundle>> entry : ownersByMember.entrySet()) {
			DeclaredBundle winner = entry.getValue().stream().min(winnerOrder).orElseThrow();
			survivingMembers.computeIfAbsent(winner, ignored -> new ArrayList<>()).add(entry.getKey().bytes);
		}

		List<ResolvedBundle> resolved = new ArrayList<>();
		for (DeclaredBundle bundle : bundles) {
			List<byte[]> members = survivingMembers.get(bundle);
			if (members == null || members.isEmpty())
				continue;

			members.sort(UNSIGNED_BYTES);
			resolved.add(new ResolvedBundle(bundle.nodePublicKey, bundle.getOriginalMemberCount(), members));
		}

		return List.copyOf(resolved);
	}

	private static void rejectDuplicateNodePublicKeys(List<DeclaredBundle> bundles) {
		for (int i = 1; i < bundles.size(); ++i)
			if (Arrays.equals(bundles.get(i - 1).nodePublicKey, bundles.get(i).nodePublicKey))
				throw new IllegalArgumentException("Repeated reward-node public key");
	}

	private static List<byte[]> copyAndRejectDuplicates(Collection<byte[]> keys) {
		List<byte[]> copies = new ArrayList<>(keys.size());
		Set<Key> seen = new HashSet<>();
		for (byte[] key : keys) {
			if (key == null)
				throw new IllegalArgumentException("Missing bundle member public key");

			byte[] copy = Arrays.copyOf(key, key.length);
			if (!seen.add(new Key(copy)))
				throw new IllegalArgumentException("Repeated bundle member public key");
			copies.add(copy);
		}

		copies.sort(UNSIGNED_BYTES);
		return List.copyOf(copies);
	}

	private static List<byte[]> copyKeys(Collection<byte[]> keys) {
		List<byte[]> copies = new ArrayList<>(keys.size());
		for (byte[] key : keys)
			copies.add(Arrays.copyOf(key, key.length));
		return List.copyOf(copies);
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		int sharedLength = Math.min(left.length, right.length);
		for (int i = 0; i < sharedLength; ++i) {
			int comparison = Byte.compareUnsigned(left[i], right[i]);
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}

	private static final class Key {
		private final byte[] bytes;
		private final int hashCode;

		private Key(byte[] bytes) {
			this.bytes = Arrays.copyOf(bytes, bytes.length);
			this.hashCode = Arrays.hashCode(this.bytes);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key && Arrays.equals(this.bytes, ((Key) other).bytes);
		}

		@Override
		public int hashCode() {
			return this.hashCode;
		}
	}
}
