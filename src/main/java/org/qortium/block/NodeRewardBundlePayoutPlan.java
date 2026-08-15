package org.qortium.block;

import org.qortium.data.network.OnlineAccountBundleData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure deterministic payout planning for a committed reward-node bundle cohort.
 * <p>
 * Consensus validation owns bundle signatures and capture-time eligibility. This planner first
 * resolves cross-bundle overlap using the original declarations, then applies the separate
 * payout-height eligibility filter and calculates each surviving member's raw block credit.
 */
public final class NodeRewardBundlePayoutPlan {

	private NodeRewardBundlePayoutPlan() {
	}

	public static List<PayoutBundle> resolve(Collection<OnlineAccountBundleData> declaredBundles,
			Collection<byte[]> eligibleMemberPublicKeys, int batchSize) {
		if (batchSize <= 0)
			throw new IllegalArgumentException("Batch size must be positive");
		if (declaredBundles == null || declaredBundles.isEmpty())
			return List.of();
		if (eligibleMemberPublicKeys == null)
			throw new IllegalArgumentException("Eligible member set is missing");

		Set<Key> eligibleMembers = new HashSet<>();
		for (byte[] publicKey : eligibleMemberPublicKeys) {
			if (publicKey == null)
				throw new IllegalArgumentException("Eligible member public key is missing");
			eligibleMembers.add(new Key(publicKey));
		}

		List<NodeRewardBundleResolver.DeclaredBundle> declarations = new ArrayList<>();
		for (OnlineAccountBundleData bundle : declaredBundles) {
			if (bundle == null)
				throw new IllegalArgumentException("Declared bundle is missing");
			List<byte[]> memberPublicKeys = bundle.getMembers().stream()
					.map(OnlineAccountBundleData.Member::getPublicKey)
					.toList();
			declarations.add(new NodeRewardBundleResolver.DeclaredBundle(
					bundle.getNodePublicKey(), memberPublicKeys));
		}

		List<PayoutBundle> payoutBundles = new ArrayList<>();
		for (NodeRewardBundleResolver.ResolvedBundle resolvedBundle
				: NodeRewardBundleResolver.resolveOverlaps(declarations)) {
			List<byte[]> survivingMembers = resolvedBundle.getMemberPublicKeys().stream()
					.filter(publicKey -> eligibleMembers.contains(new Key(publicKey)))
					.toList();
			if (survivingMembers.isEmpty())
				continue;

			payoutBundles.add(new PayoutBundle(resolvedBundle.getNodePublicKey(),
					resolvedBundle.getOriginalMemberCount(), survivingMembers,
					batchSize / survivingMembers.size()));
		}

		return List.copyOf(payoutBundles);
	}

	public static final class PayoutBundle {
		private final byte[] nodePublicKey;
		private final int originalMemberCount;
		private final List<byte[]> memberPublicKeys;
		private final int blocksMintedCreditPerMember;

		private PayoutBundle(byte[] nodePublicKey, int originalMemberCount,
				Collection<byte[]> memberPublicKeys, int blocksMintedCreditPerMember) {
			this.nodePublicKey = Arrays.copyOf(nodePublicKey, nodePublicKey.length);
			this.originalMemberCount = originalMemberCount;
			this.memberPublicKeys = copyKeys(memberPublicKeys);
			this.blocksMintedCreditPerMember = blocksMintedCreditPerMember;
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

		public int getBlocksMintedCreditPerMember() {
			return this.blocksMintedCreditPerMember;
		}
	}

	private static List<byte[]> copyKeys(Collection<byte[]> keys) {
		List<byte[]> copies = new ArrayList<>(keys.size());
		for (byte[] key : keys)
			copies.add(Arrays.copyOf(key, key.length));
		return List.copyOf(copies);
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
