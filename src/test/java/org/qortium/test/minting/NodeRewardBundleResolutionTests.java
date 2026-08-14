package org.qortium.test.minting;

import org.junit.Test;
import org.qortium.block.NodeRewardBundleResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NodeRewardBundleResolutionTests {

	@Test
	public void testSmallestOriginalBundleWinsOverlapWithoutReranking() {
		byte[] shared = key(10);
		NodeRewardBundleResolver.DeclaredBundle smaller = bundle(2, shared, key(11));
		NodeRewardBundleResolver.DeclaredBundle larger = bundle(1, shared, key(31), key(32));

		List<NodeRewardBundleResolver.ResolvedBundle> resolved = resolveShuffled(smaller, larger);

		assertEquals(2, resolved.size());
		assertMembers(find(resolved, 1), key(31), key(32));
		assertMembers(find(resolved, 2), shared, key(11));
		assertEquals(3, find(resolved, 1).getOriginalMemberCount());
		assertEquals(2, find(resolved, 2).getOriginalMemberCount());
	}

	@Test
	public void testUnsignedNodeKeyBreaksEqualSizeTie() {
		byte[] shared = key(9);
		NodeRewardBundleResolver.DeclaredBundle lowerUnsigned = bundle(0x7f, shared, key(1));
		NodeRewardBundleResolver.DeclaredBundle higherUnsigned = bundle(0x80, shared, key(2));

		List<NodeRewardBundleResolver.ResolvedBundle> resolved = resolveShuffled(higherUnsigned, lowerUnsigned);

		assertMembers(find(resolved, 0x7f), key(1), shared);
		assertMembers(find(resolved, 0x80), key(2));
	}

	@Test
	public void testLosingSingletonIsDroppedAndAccountAppearsOnce() {
		byte[] shared = key(9);
		NodeRewardBundleResolver.DeclaredBundle winningSingleton = bundle(1, shared);
		NodeRewardBundleResolver.DeclaredBundle losingSingleton = bundle(2, shared);
		NodeRewardBundleResolver.DeclaredBundle unrelated = bundle(3, key(7));

		List<NodeRewardBundleResolver.ResolvedBundle> resolved = resolveShuffled(unrelated, losingSingleton, winningSingleton);

		assertEquals(2, resolved.size());
		assertMembers(find(resolved, 1), shared);
		assertMembers(find(resolved, 3), key(7));
		assertEquals(1L, resolved.stream().flatMap(bundle -> bundle.getMemberPublicKeys().stream())
				.filter(member -> Arrays.equals(member, shared)).count());
	}

	@Test
	public void testInputOrderDoesNotChangeResolution() {
		byte[] shared = key(50);
		List<NodeRewardBundleResolver.DeclaredBundle> input = List.of(
				bundle(3, shared, key(31)),
				bundle(1, shared, key(11)),
				bundle(2, key(21)));

		List<NodeRewardBundleResolver.ResolvedBundle> expected = NodeRewardBundleResolver.resolveOverlaps(input);
		for (int seed = 0; seed < 20; ++seed) {
			List<NodeRewardBundleResolver.DeclaredBundle> shuffled = new ArrayList<>(input);
			Collections.shuffle(shuffled, new Random(seed));
			assertResolutionEquals(expected, NodeRewardBundleResolver.resolveOverlaps(shuffled));
		}
	}

	@Test
	public void testRejectsDuplicateMemberWithinBundleAndDuplicateNode() {
		byte[] shared = key(4);
		assertThrows(IllegalArgumentException.class,
				() -> bundle(1, shared, shared));

		NodeRewardBundleResolver.DeclaredBundle first = bundle(1, key(2));
		NodeRewardBundleResolver.DeclaredBundle second = bundle(1, key(3));
		assertThrows(IllegalArgumentException.class,
				() -> NodeRewardBundleResolver.resolveOverlaps(List.of(first, second)));
	}

	private static List<NodeRewardBundleResolver.ResolvedBundle> resolveShuffled(
			NodeRewardBundleResolver.DeclaredBundle... bundles) {
		List<NodeRewardBundleResolver.DeclaredBundle> shuffled = new ArrayList<>(Arrays.asList(bundles));
		Collections.shuffle(shuffled, new Random(73));
		return NodeRewardBundleResolver.resolveOverlaps(shuffled);
	}

	private static NodeRewardBundleResolver.DeclaredBundle bundle(int nodeLeadingByte, byte[]... members) {
		return new NodeRewardBundleResolver.DeclaredBundle(key(nodeLeadingByte), Arrays.asList(members));
	}

	private static NodeRewardBundleResolver.ResolvedBundle find(
			List<NodeRewardBundleResolver.ResolvedBundle> bundles, int nodeLeadingByte) {
		return bundles.stream()
				.filter(bundle -> Byte.toUnsignedInt(bundle.getNodePublicKey()[0]) == nodeLeadingByte)
				.findFirst()
				.orElseThrow();
	}

	private static void assertMembers(NodeRewardBundleResolver.ResolvedBundle bundle, byte[]... expected) {
		List<byte[]> actual = bundle.getMemberPublicKeys();
		List<byte[]> sortedExpected = new ArrayList<>(Arrays.asList(expected));
		sortedExpected.sort(NodeRewardBundleResolutionTests::compareUnsigned);
		assertEquals(sortedExpected.size(), actual.size());
		for (int i = 0; i < sortedExpected.size(); ++i)
			assertArrayEquals(sortedExpected.get(i), actual.get(i));
	}

	private static void assertResolutionEquals(List<NodeRewardBundleResolver.ResolvedBundle> expected,
			List<NodeRewardBundleResolver.ResolvedBundle> actual) {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); ++i) {
			assertArrayEquals(expected.get(i).getNodePublicKey(), actual.get(i).getNodePublicKey());
			assertEquals(expected.get(i).getOriginalMemberCount(), actual.get(i).getOriginalMemberCount());
			assertMembers(actual.get(i), expected.get(i).getMemberPublicKeys().toArray(new byte[0][]));
		}
	}

	private static byte[] key(int leadingByte) {
		byte[] key = new byte[32];
		key[0] = (byte) leadingByte;
		return key;
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		for (int i = 0; i < Math.min(left.length, right.length); ++i) {
			int comparison = Byte.compareUnsigned(left[i], right[i]);
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	}
}
