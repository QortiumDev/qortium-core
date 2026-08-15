package org.qortium.test.minting;

import org.junit.Test;
import org.qortium.block.NodeRewardBundlePayoutPlan;
import org.qortium.block.NodeRewardBundlePayoutPlan.PayoutBundle;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.transform.OnlineAccountBundleTransformer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NodeRewardBundlePayoutPlanTests {

	@Test
	public void testRawCreditRoundsDownAcrossBoundaryCases() {
		for (int memberCount : List.of(1, 2, 3, 100, 101, 1024)) {
			OnlineAccountBundleData bundle = bundle(1, keys(1, memberCount));
			List<PayoutBundle> plan = NodeRewardBundlePayoutPlan.resolve(
					List.of(bundle), keys(1, memberCount), 100);

			assertEquals(1, plan.size());
			assertEquals(memberCount, plan.get(0).getMemberPublicKeys().size());
			assertEquals(100 / memberCount, plan.get(0).getBlocksMintedCreditPerMember());
		}
	}

	@Test
	public void testOverlapResolvesBeforePayoutEligibilityFiltering() {
		byte[] shared = key(1);
		byte[] singletonNode = key(10);
		byte[] largerNode = key(20);
		List<byte[]> largerMembers = new ArrayList<>();
		largerMembers.add(shared);
		largerMembers.addAll(keys(2, 9));

		OnlineAccountBundleData singleton = bundle(singletonNode, List.of(shared));
		OnlineAccountBundleData larger = bundle(largerNode, largerMembers);
		List<byte[]> eligible = new ArrayList<>(largerMembers);
		eligible.removeIf(candidate -> Arrays.equals(candidate, shared));

		List<PayoutBundle> plan = NodeRewardBundlePayoutPlan.resolve(
				List.of(larger, singleton), eligible, 100);

		assertEquals("The singleton wins overlap first, then disappears as payout-ineligible", 1,
				plan.size());
		assertArrayEquals(largerNode, plan.get(0).getNodePublicKey());
		assertEquals("The losing larger bundle must not regain the shared key", 9,
				plan.get(0).getMemberPublicKeys().size());
		assertEquals(11, plan.get(0).getBlocksMintedCreditPerMember());
	}

	@Test
	public void testEligibilityUsesSurvivingMemberCountAndDropsEmptyBundles() {
		OnlineAccountBundleData first = bundle(1, keys(1, 3));
		OnlineAccountBundleData second = bundle(2, keys(10, 2));
		List<byte[]> eligible = List.of(key(1), key(3));

		List<PayoutBundle> plan = NodeRewardBundlePayoutPlan.resolve(
				List.of(second, first), eligible, 100);

		assertEquals(1, plan.size());
		assertEquals(3, plan.get(0).getOriginalMemberCount());
		assertEquals(2, plan.get(0).getMemberPublicKeys().size());
		assertEquals(50, plan.get(0).getBlocksMintedCreditPerMember());
	}

	private static OnlineAccountBundleData bundle(int nodeId, Collection<byte[]> members) {
		return bundle(key(nodeId), members);
	}

	private static OnlineAccountBundleData bundle(byte[] nodePublicKey, Collection<byte[]> members) {
		List<Member> memberData = members.stream()
				.map(publicKey -> new Member(publicKey, 0, new byte[64]))
				.toList();
		return new OnlineAccountBundleData(OnlineAccountBundleTransformer.PROTOCOL_VERSION, 1000L,
				nodePublicKey, memberData, new byte[64], new byte[32]);
	}

	private static List<byte[]> keys(int first, int count) {
		List<byte[]> keys = new ArrayList<>(count);
		for (int i = 0; i < count; ++i)
			keys.add(key(first + i));
		return keys;
	}

	private static byte[] key(int value) {
		return ByteBuffer.allocate(32).putInt(value).array();
	}
}
