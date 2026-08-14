package org.qortium.block;

import io.druid.extendedset.intset.ConciseSet;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.group.Group;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.block.BlockTransformer;
import org.qortium.utils.NTP;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class NodeRewardBundleBoundaryRewardTests extends Common {

	private static final int PAYOUT_HEIGHT = 100;
	private static final int BATCH_SIZE = 100;
	private static final int CAPTURE_HEIGHT = PAYOUT_HEIGHT - 3;
	private static final int MINTING_GROUP_ID = 1;
	private static final long EPOCH = 600_000L;

	@Before
	public void beforeTest() throws Exception {
		Common.useSettings("test-settings-v2-reward-levels.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@After
	public void afterTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testExactHundredAndHundredOneMemberCreditBoundariesAndReloadOrphan()
			throws Exception {
		configureActivationWindow(1);

		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount minter = Common.getTestAccount(repository, "alice-reward-share");
			List<FixtureMember> members = createSyntheticMembers(repository, 101, 10, 1,
					(byte) 0x41);
			seedSyntheticChain(repository, PAYOUT_HEIGHT - 1, EPOCH, minter, -1, 0L);

			Map<String, AccountState> initialStates = captureStates(members);
			long batchReward = BlockChain.getInstance().getRewardAtHeight(repository,
					PAYOUT_HEIGHT) * BATCH_SIZE;
			assertEquals(10_000_000_000L, batchReward);

			List<FixtureMember> hundredMembers = members.subList(0, 100);
			saveCapture(repository, List.of(bundle(EPOCH, 1, publicKeys(hundredMembers))),
					hundredMembers, minter);
			processMintedPayout(repository, minter);

			for (int index = 0; index < members.size(); ++index) {
				FixtureMember member = members.get(index);
				AccountState initial = initialStates.get(member.owner.getAddress());
				int expectedCredit = index < 100 ? 1 : 0;
				long expectedReward = index < 100 ? batchReward / 100 : 0L;
				assertState(member, initial.balance + expectedReward,
						initial.blocksMinted + expectedCredit, initial.level);
			}

			BlockUtils.orphanLastBlock(repository);
			assertStates(initialStates, members);

			saveCapture(repository, List.of(bundle(EPOCH, 2, publicKeys(members))), members,
					minter);
			processMintedPayout(repository, minter);

			long perMemberReward = batchReward / 101;
			assertEquals(99_009_900L, perMemberReward);
			assertEquals(100L, batchReward - perMemberReward * 101);
			for (FixtureMember member : members) {
				AccountState initial = initialStates.get(member.owner.getAddress());
				assertState(member, initial.balance + perMemberReward,
						initial.blocksMinted, initial.level);
			}

			BlockUtils.orphanLastBlock(repository);
			assertStates(initialStates, members);
			repository.discardChanges();
		}
	}

	@Test
	public void testHighestPostCreditBinDustAndExternalNonMemberReloadOrphan()
			throws Exception {
		configureActivationWindow(1);

		try (Repository repository = RepositoryManager.getRepository()) {
			FixtureMember alice = namedMember(repository, "alice", "alice-reward-share", 50, 2);
			FixtureMember bob = namedMember(repository, "bob", "bob-reward-share", 0, 0);
			FixtureMember chloe = namedMember(repository, "chloe", "chloe-reward-share", 0, 0);
			FixtureMember dilbert = namedMember(repository, "dilbert", "dilbert-reward-share", 0, 0);
			List<FixtureMember> extraMembers = createSyntheticMembers(repository, 2, 0, 0,
					(byte) 0x52);
			FixtureMember erin = extraMembers.get(0);
			FixtureMember frank = extraMembers.get(1);
			List<FixtureMember> cohort = List.of(alice, bob, chloe, dilbert, erin, frank);

			PrivateKeyAccount externalRecipient = deterministicAccount(repository, (byte) 0x63, 1);
			repository.getAccountRepository().ensureAccount(new AccountData(
					externalRecipient.getAddress(), externalRecipient.getPublicKey(), Group.NO_GROUP,
					0, 0));
			byte[] externalSharePrivateKey = alice.owner.getRewardSharePrivateKey(
					externalRecipient.getPublicKey());
			PrivateKeyAccount externalShare = new PrivateKeyAccount(repository,
					externalSharePrivateKey);
			repository.getAccountRepository().save(new RewardShareData(alice.owner.getPublicKey(),
					alice.owner.getAddress(), externalRecipient.getAddress(),
					externalShare.getPublicKey(), 20_00));

			List<OnlineAccountBundleData> bundles = new ArrayList<>(List.of(
					bundle(EPOCH, 1, publicKeys(List.of(alice, bob))),
					bundle(EPOCH, 2, publicKeys(List.of(chloe, dilbert))),
					bundle(EPOCH, 3, publicKeys(List.of(erin, frank)))));
			bundles.sort((left, right) -> OnlineAccountBundleData.compareUnsigned(
					left.getNodePublicKey(), right.getNodePublicKey()));

			seedSyntheticChain(repository, PAYOUT_HEIGHT - 1, EPOCH, alice.selfShare,
					PAYOUT_HEIGHT - 1, 7L);
			saveCapture(repository, bundles, cohort, alice.selfShare);

			Map<String, AccountState> initialStates = captureStates(cohort);
			AccountState initialExternal = captureState(externalRecipient);
			processMintedPayout(repository, alice.selfShare);

			long batchRewardWithFee = BlockChain.getInstance().getRewardAtHeight(repository,
					PAYOUT_HEIGHT) * BATCH_SIZE + 7L;
			assertEquals(10_000_000_007L, batchRewardWithFee);
			long highBinPerMember = 3_333_333_202L;
			long lowBinPerMember = 833_333_400L;
			long externalAmount = 666_666_640L;

			assertState(alice, initialStates.get(alice.owner.getAddress()).balance
					+ highBinPerMember - externalAmount, 100, 4);
			assertState(bob, initialStates.get(bob.owner.getAddress()).balance
					+ highBinPerMember, 50, 2);
			for (FixtureMember member : List.of(chloe, dilbert, erin, frank))
				assertState(member, initialStates.get(member.owner.getAddress()).balance
						+ lowBinPerMember, 50, 2);

			assertEquals(initialExternal.balance + externalAmount,
					externalRecipient.getConfirmedBalance(Asset.NATIVE));
			assertEquals(initialExternal.blocksMinted,
					externalRecipient.getBlocksMinted().intValue());
			assertEquals(initialExternal.level, externalRecipient.getLevel().intValue());

			long totalPaid = highBinPerMember * 2 + lowBinPerMember * 4;
			assertEquals(3L, batchRewardWithFee - totalPaid);

			BlockUtils.orphanLastBlock(repository);
			assertStates(initialStates, cohort);
			assertEquals(initialExternal.balance,
					externalRecipient.getConfirmedBalance(Asset.NATIVE));
			assertEquals(initialExternal.blocksMinted,
					externalRecipient.getBlocksMinted().intValue());
			assertEquals(initialExternal.level, externalRecipient.getLevel().intValue());
			repository.discardChanges();
		}
	}

	private static List<FixtureMember> createSyntheticMembers(Repository repository, int count,
			int blocksMinted, int level, byte domain) throws Exception {
		List<FixtureMember> members = new ArrayList<>(count);
		for (int index = 0; index < count; ++index) {
			PrivateKeyAccount owner = deterministicAccount(repository, domain, index);
			byte[] selfSharePrivateKey = owner.getRewardSharePrivateKey(owner.getPublicKey());
			PrivateKeyAccount selfShare = new PrivateKeyAccount(repository, selfSharePrivateKey);
			members.add(saveMember(repository, owner, selfShare, blocksMinted, level));
		}
		return members;
	}

	private static FixtureMember namedMember(Repository repository, String ownerName,
			String selfShareName, int blocksMinted, int level) throws Exception {
		return saveMember(repository, Common.getTestAccount(repository, ownerName),
				Common.getTestAccount(repository, selfShareName), blocksMinted, level);
	}

	private static FixtureMember saveMember(Repository repository, PrivateKeyAccount owner,
			PrivateKeyAccount selfShare, int blocksMinted, int level) throws Exception {
		repository.getAccountRepository().ensureAccount(new AccountData(owner.getAddress(),
				owner.getPublicKey(), Group.NO_GROUP, level, blocksMinted));
		AccountData accountData = repository.getAccountRepository().getAccount(owner.getAddress());
		accountData.setBlocksMinted(blocksMinted);
		repository.getAccountRepository().setMintedBlockCount(accountData);
		accountData.setLevel(level);
		repository.getAccountRepository().setLevel(accountData);

		GroupData groupData = repository.getGroupRepository().fromGroupId(MINTING_GROUP_ID);
		assertNotNull(groupData);
		if (!repository.getGroupRepository().memberExists(MINTING_GROUP_ID, owner.getAddress()))
			repository.getGroupRepository().save(new GroupMemberData(MINTING_GROUP_ID,
					owner.getAddress(), groupData.getCreated(), groupData.getReference()));

		repository.getAccountRepository().save(new RewardShareData(owner.getPublicKey(),
				owner.getAddress(), owner.getAddress(), selfShare.getPublicKey(), 100_00));
		return new FixtureMember(owner, selfShare);
	}

	private static PrivateKeyAccount deterministicAccount(Repository repository, byte domain,
			int index) {
		byte[] seed = new byte[32];
		seed[0] = domain;
		ByteBuffer.wrap(seed, seed.length - Integer.BYTES, Integer.BYTES).putInt(index + 1);
		return new PrivateKeyAccount(repository, seed);
	}

	private static void processMintedPayout(Repository repository, PrivateKeyAccount minter)
			throws Exception {
		Block payout = Block.mint(repository,
				repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT - 1), minter);
		assertNotNull(payout);
		assertEquals(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
				payout.getBlockData().getVersion());
		payout.sign();
		payout.process();
		repository.saveChanges();
	}

	private static void saveCapture(Repository repository,
			List<OnlineAccountBundleData> bundles, List<FixtureMember> members,
			PrivateKeyAccount minter) throws Exception {
		byte[] payload = OnlineAccountBundleTransformer.toBlockCohortBytes(bundles,
				OnlineAccountBundleTransformer.ChainIdentity.current());
		BlockData captureData = syntheticBlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
				CAPTURE_HEIGHT, EPOCH, minter, CAPTURE_HEIGHT, 0L);
		captureData.setOnlineAccountBundles(payload);
		captureData.setOnlineAccountsTimestamp(EPOCH);
		captureData.setOnlineAccountsCount(members.size());
		captureData.setEncodedOnlineAccounts(encodeSelfShares(repository, publicKeys(members)));
		repository.getBlockRepository().save(captureData);
		repository.saveChanges();
	}

	private static OnlineAccountBundleData bundle(long epoch, int nodeId,
			List<byte[]> memberPublicKeys) throws Exception {
		List<Member> members = memberPublicKeys.stream()
				.map(publicKey -> new Member(publicKey, 0, new byte[64]))
				.sorted(OnlineAccountBundleData.UNSIGNED_PUBLIC_KEY_COMPARATOR)
				.toList();
		byte[] nodePublicKey = ByteBuffer.allocate(32).putInt(nodeId).array();
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(
				OnlineAccountBundleTransformer.ChainIdentity.current(),
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, epoch, nodePublicKey, members);
		return new OnlineAccountBundleData(OnlineAccountBundleTransformer.PROTOCOL_VERSION,
				epoch, nodePublicKey, members, new byte[64], commitment);
	}

	private static List<byte[]> publicKeys(List<FixtureMember> members) {
		return members.stream().map(member -> member.selfShare.getPublicKey()).toList();
	}

	private static byte[] encodeSelfShares(Repository repository, List<byte[]> publicKeys)
			throws Exception {
		List<byte[]> allSelfShares = repository.getAccountRepository().getSelfSharePublicKeys();
		List<Integer> indexes = new ArrayList<>();
		for (byte[] publicKey : publicKeys) {
			int index = -1;
			for (int candidate = 0; candidate < allSelfShares.size(); ++candidate) {
				if (Arrays.equals(publicKey, allSelfShares.get(candidate))) {
					index = candidate;
					break;
				}
			}
			if (index < 0)
				throw new AssertionError("Missing self-share in boundary reward test");
			indexes.add(index);
		}
		indexes.sort(null);
		return BlockTransformer.encodeOnlineAccounts(new ConciseSet().convert(indexes));
	}

	private static void seedSyntheticChain(Repository repository, int targetHeight, long timestamp,
			PrivateKeyAccount minter, int feeHeight, long fee) throws Exception {
		byte[] reference = repository.getBlockRepository().fromHeight(1).getSignature();
		for (int height = 2; height <= targetHeight; ++height) {
			long totalFees = height == feeHeight ? fee : 0L;
			BlockData blockData = syntheticBlockData(Block.CURRENT_VERSION, height, timestamp,
					minter, height, totalFees);
			blockData.setReference(reference);
			repository.getBlockRepository().save(blockData);
			reference = blockData.getSignature();
		}
		repository.saveChanges();
	}

	private static BlockData syntheticBlockData(int version, int height, long timestamp,
			PrivateKeyAccount minter, int marker, long totalFees) {
		byte[] minterSignature = new byte[64];
		byte[] transactionsSignature = new byte[64];
		Arrays.fill(minterSignature, (byte) marker);
		Arrays.fill(transactionsSignature, (byte) (marker + 1));
		return new BlockData(version, new byte[128], 0, totalFees, transactionsSignature,
				height, timestamp, minter.getPublicKey(), minterSignature, 0, 0L,
				new byte[0], 0, null, null);
	}

	private static Map<String, AccountState> captureStates(List<FixtureMember> members)
			throws Exception {
		Map<String, AccountState> states = new LinkedHashMap<>();
		for (FixtureMember member : members)
			states.put(member.owner.getAddress(), captureState(member.owner));
		return states;
	}

	private static AccountState captureState(PrivateKeyAccount account) throws Exception {
		return new AccountState(account.getConfirmedBalance(Asset.NATIVE),
				account.getBlocksMinted(), account.getLevel());
	}

	private static void assertStates(Map<String, AccountState> expectedStates,
			List<FixtureMember> members) throws Exception {
		for (FixtureMember member : members) {
			AccountState expected = expectedStates.get(member.owner.getAddress());
			assertState(member, expected.balance, expected.blocksMinted, expected.level);
		}
	}

	private static void assertState(FixtureMember member, long balance, int blocksMinted,
			int level) throws Exception {
		assertEquals(balance, member.owner.getConfirmedBalance(Asset.NATIVE));
		assertEquals(blocksMinted, member.owner.getBlocksMinted().intValue());
		assertEquals(level, member.owner.getLevel().intValue());
	}

	@SuppressWarnings("unchecked")
	private static void configureActivationWindow(int minBundlesToActivate) throws Exception {
		BlockChain blockChain = BlockChain.getInstance();
		FieldUtils.writeField(blockChain, "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchSize", BATCH_SIZE, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchAccountsBlockCount", 3, true);
		FieldUtils.writeField(blockChain, "minAccountsToActivateShareBin",
				minBundlesToActivate, true);
		Map<String, Long> existing = (Map<String, Long>) FieldUtils.readField(
				blockChain, "featureTriggers", true);
		Map<String, Long> triggers = new LinkedHashMap<>(existing);
		triggers.put("onlineNodeRewardBundlesPayoutHeight", (long) PAYOUT_HEIGHT);
		FieldUtils.writeField(blockChain, "featureTriggers", triggers, true);
	}

	private static final class FixtureMember {
		private final PrivateKeyAccount owner;
		private final PrivateKeyAccount selfShare;

		private FixtureMember(PrivateKeyAccount owner, PrivateKeyAccount selfShare) {
			this.owner = owner;
			this.selfShare = selfShare;
		}
	}

	private static final class AccountState {
		private final long balance;
		private final int blocksMinted;
		private final int level;

		private AccountState(long balance, int blocksMinted, int level) {
			this.balance = balance;
			this.blocksMinted = blocksMinted;
			this.level = level;
		}
	}
}
