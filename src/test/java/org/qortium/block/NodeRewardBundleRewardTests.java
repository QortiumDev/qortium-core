package org.qortium.block;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.account.AccountData;
import org.qortium.data.block.BlockData;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.group.Group;
import org.qortium.settings.Settings;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
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
import static org.junit.Assert.assertTrue;

public class NodeRewardBundleRewardTests extends Common {

	private static final int PAYOUT_HEIGHT = 100;
	private static final int BATCH_SIZE = 100;

	@Before
	public void beforeTest() throws Exception {
		Common.useSettings("test-settings-v2-reward-levels.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		configureActivationWindow();
	}

	@After
	public void afterTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testOneAllocationPerNodeCreditsExternalSharesAndOrphansExactly() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			for (PrivateKeyAccount account : List.of(alice, bob, chloe))
				repository.getAccountRepository().ensureAccount(new AccountData(account.getAddress(),
						account.getPublicKey(), Group.NO_GROUP, 0, 0));
			PrivateKeyAccount dilbertSelfShare = Common.getTestAccount(repository, "dilbert-reward-share");
			repository.getAccountRepository().save(new RewardShareData(bob.getPublicKey(),
					bob.getAddress(), bob.getAddress(), bobSelfShare.getPublicKey(), 100_00));
			repository.getAccountRepository().save(new RewardShareData(chloe.getPublicKey(),
					chloe.getAddress(), chloe.getAddress(), chloeSelfShare.getPublicKey(), 100_00));
			repository.getAccountRepository().save(new RewardShareData(dilbert.getPublicKey(),
					dilbert.getAddress(), dilbert.getAddress(), dilbertSelfShare.getPublicKey(), 100_00));

			// Two keys on one node rise to level 4; the other two-key node remains at level 2.
			// A threshold of two must count nodes, not the two keys in the higher bin, and roll
			// that entire node down until both nodes share one active bin.
			for (String accountName : List.of("alice", "bob"))
				AccountUtils.setMintingData(repository, accountName, 50, 2);
			for (String accountName : List.of("chloe", "dilbert"))
				AccountUtils.setMintingData(repository, accountName, 0, 0);

			byte[] externalRewardSharePublicKey = alice.getRewardSharePrivateKey(
					bob.getPublicKey());
			PrivateKeyAccount externalRewardShare = new PrivateKeyAccount(repository,
					externalRewardSharePublicKey);
			repository.getAccountRepository().save(new RewardShareData(alice.getPublicKey(),
					alice.getAddress(), bob.getAddress(), externalRewardShare.getPublicKey(), 20_00));

			long epoch = 600_000L;
			OnlineAccountBundleData higherLevelNode = bundle(epoch, 1,
					List.of(aliceSelfShare.getPublicKey(), bobSelfShare.getPublicKey()));
			OnlineAccountBundleData lowerLevelNode = bundle(epoch, 2,
					List.of(chloeSelfShare.getPublicKey(), dilbertSelfShare.getPublicKey()));
			List<OnlineAccountBundleData> bundles = new ArrayList<>(List.of(
					higherLevelNode, lowerLevelNode));
			bundles.sort((left, right) -> OnlineAccountBundleData.compareUnsigned(
					left.getNodePublicKey(), right.getNodePublicKey()));
			byte[] payload = OnlineAccountBundleTransformer.toBlockCohortBytes(bundles,
					OnlineAccountBundleTransformer.ChainIdentity.current());

			seedSyntheticChain(repository, PAYOUT_HEIGHT - 1, epoch, aliceSelfShare);
			Map<String, Long> initialBalances = balances(repository, "alice", "bob", "chloe", "dilbert");
			Map<String, Integer> initialBlocksMinted = blocksMinted(repository,
					"alice", "bob", "chloe", "dilbert");
			Map<String, Integer> initialLevels = levels(repository,
					"alice", "bob", "chloe", "dilbert");

			BlockData captureData = syntheticBlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					PAYOUT_HEIGHT - 3, epoch, aliceSelfShare, PAYOUT_HEIGHT - 3);
			captureData.setOnlineAccountBundles(payload);
			captureData.setOnlineAccountsTimestamp(epoch);
			captureData.setOnlineAccountsCount(4);
			captureData.setEncodedOnlineAccounts(encodeSelfShares(repository,
					List.of(aliceSelfShare.getPublicKey(), bobSelfShare.getPublicKey(),
							chloeSelfShare.getPublicKey(), dilbertSelfShare.getPublicKey())));
			repository.getBlockRepository().save(captureData);
			repository.saveChanges();

			Block payout = Block.mint(repository,
					repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT - 1), aliceSelfShare);
			assertEquals(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					payout.getBlockData().getVersion());
			long batchReward = BlockChain.getInstance().getRewardAtHeight(repository, PAYOUT_HEIGHT)
					* BATCH_SIZE;
			payout.sign();
			payout.process();
			repository.saveChanges();

			assertEquals(initialBlocksMinted.get("alice") + 50,
					AccountUtils.getBlocksMinted(repository, "alice"));
			assertEquals(initialBlocksMinted.get("bob") + 50,
					AccountUtils.getBlocksMinted(repository, "bob"));
			assertEquals(initialBlocksMinted.get("chloe") + 50,
					AccountUtils.getBlocksMinted(repository, "chloe"));
			assertEquals(initialBlocksMinted.get("dilbert") + 50,
					AccountUtils.getBlocksMinted(repository, "dilbert"));
			assertEquals(4, Common.getTestAccount(repository, "alice").getLevel().intValue());
			assertEquals(4, Common.getTestAccount(repository, "bob").getLevel().intValue());
			assertEquals(2, Common.getTestAccount(repository, "chloe").getLevel().intValue());
			assertEquals(2, Common.getTestAccount(repository, "dilbert").getLevel().intValue());

			long perNode = batchReward / 2;
			long perMember = perNode / 2;
			long aliceExternalShare = perMember * 20 / 100;
			assertEquals(initialBalances.get("alice") + perMember - aliceExternalShare,
					balance(repository, "alice"));
			assertEquals(initialBalances.get("bob") + perMember + aliceExternalShare,
					balance(repository, "bob"));
			assertEquals(initialBalances.get("chloe") + perMember,
					balance(repository, "chloe"));
			assertEquals(initialBalances.get("dilbert") + perMember,
					balance(repository, "dilbert"));

			List<byte[]> historicalKeys = repository.getBlockRepository()
					.getOnlineRewardSharePublicKeys(PAYOUT_HEIGHT);
			assertEquals(4, historicalKeys.size());
			for (byte[] expectedKey : List.of(aliceSelfShare.getPublicKey(),
					bobSelfShare.getPublicKey(), chloeSelfShare.getPublicKey(),
					dilbertSelfShare.getPublicKey()))
				assertTrue(historicalKeys.stream().anyMatch(actual -> Arrays.equals(expectedKey, actual)));

			// The local historical index is absolute and must not reinterpret positional indices
			// after the active self-share set changes. Restore state before orphaning this tip.
			repository.getAccountRepository().delete(bob.getPublicKey(), bob.getAddress());
			assertEquals(4, repository.getBlockRepository()
					.getOnlineRewardSharePublicKeys(PAYOUT_HEIGHT).size());
			repository.getAccountRepository().save(new RewardShareData(bob.getPublicKey(),
					bob.getAddress(), bob.getAddress(), bobSelfShare.getPublicKey(), 100_00));

			new Block(repository, repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT)).orphan();
			repository.saveChanges();
			for (String accountName : List.of("alice", "bob", "chloe", "dilbert")) {
				assertEquals(initialBalances.get(accountName).longValue(), balance(repository, accountName));
				assertEquals(initialBlocksMinted.get(accountName).intValue(),
						AccountUtils.getBlocksMinted(repository, accountName));
				assertEquals(initialLevels.get(accountName).intValue(),
						Common.getTestAccount(repository, accountName).getLevel().intValue());
			}

			repository.discardChanges();
		}
	}

	@Test
	public void testMissingNativeAssetStillCreditsAndOrphansExactly() throws Exception {
		Common.useSettings("test-settings-v2-no-native-asset.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		configureActivationWindow();

		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			for (PrivateKeyAccount account : List.of(alice, bob, chloe))
				repository.getAccountRepository().ensureAccount(new AccountData(account.getAddress(),
						account.getPublicKey(), Group.NO_GROUP, 0, 0));
			for (String accountName : List.of("alice", "bob", "chloe"))
				TestChainBootstrapUtils.ensureMintingGroupMember(repository, accountName);
			repository.getAccountRepository().save(new RewardShareData(alice.getPublicKey(),
					alice.getAddress(), alice.getAddress(), aliceSelfShare.getPublicKey(), 100_00));
			repository.getAccountRepository().save(new RewardShareData(bob.getPublicKey(),
					bob.getAddress(), bob.getAddress(), bobSelfShare.getPublicKey(), 100_00));
			repository.getAccountRepository().save(new RewardShareData(chloe.getPublicKey(),
					chloe.getAddress(), chloe.getAddress(), chloeSelfShare.getPublicKey(), 100_00));
			for (String accountName : List.of("alice", "bob", "chloe"))
				AccountUtils.setMintingData(repository, accountName, 0, 0);

			long epoch = 600_000L;
			OnlineAccountBundleData bundle = bundle(epoch, 1, List.of(
					aliceSelfShare.getPublicKey(), bobSelfShare.getPublicKey(),
					chloeSelfShare.getPublicKey()));
			byte[] payload = OnlineAccountBundleTransformer.toBlockCohortBytes(List.of(bundle),
					OnlineAccountBundleTransformer.ChainIdentity.current());
			seedSyntheticChain(repository, PAYOUT_HEIGHT - 1, epoch, aliceSelfShare);

			BlockData captureData = syntheticBlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					PAYOUT_HEIGHT - 3, epoch, aliceSelfShare, PAYOUT_HEIGHT - 3);
			captureData.setOnlineAccountBundles(payload);
			captureData.setOnlineAccountsTimestamp(epoch);
			captureData.setOnlineAccountsCount(3);
			captureData.setEncodedOnlineAccounts(encodeSelfShares(repository, List.of(
					aliceSelfShare.getPublicKey(), bobSelfShare.getPublicKey(),
					chloeSelfShare.getPublicKey())));
			repository.getBlockRepository().save(captureData);
			repository.saveChanges();

			BlockData payoutData = syntheticBlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					PAYOUT_HEIGHT, epoch, aliceSelfShare, PAYOUT_HEIGHT);
			payoutData.setOnlineAccountBundles(payload);
			payoutData.setOnlineAccountsTimestamp(epoch);
			payoutData.setOnlineAccountsCount(3);
			payoutData.setEncodedOnlineAccounts(captureData.getEncodedOnlineAccounts());
			Block payout = new Block(repository, payoutData);
			payout.ourAtStates = new ArrayList<>();
			payout.process();
			repository.saveChanges();

			assertTrue(!repository.getAssetRepository().assetExists(Asset.NATIVE));
			for (String accountName : List.of("alice", "bob", "chloe")) {
				assertEquals(33, AccountUtils.getBlocksMinted(repository, accountName));
				assertEquals(2, Common.getTestAccount(repository, accountName).getLevel().intValue());
			}

			new Block(repository, repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT)).orphan();
			repository.saveChanges();
			assertTrue(!repository.getAssetRepository().assetExists(Asset.NATIVE));
			for (String accountName : List.of("alice", "bob", "chloe")) {
				assertEquals(0, AccountUtils.getBlocksMinted(repository, accountName));
				assertEquals(0, Common.getTestAccount(repository, accountName).getLevel().intValue());
			}

			repository.discardChanges();
		}
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
				throw new AssertionError("Missing self-share in reward test");
			indexes.add(index);
		}
		indexes.sort(null);
		return BlockTransformer.encodeOnlineAccounts(
				new io.druid.extendedset.intset.ConciseSet().convert(indexes));
	}

	private static void seedSyntheticChain(Repository repository, int targetHeight, long timestamp,
			PrivateKeyAccount minter) throws Exception {
		byte[] reference = repository.getBlockRepository().fromHeight(1).getSignature();
		for (int height = 2; height <= targetHeight; ++height) {
			BlockData blockData = syntheticBlockData(Block.CURRENT_VERSION, height, timestamp,
					minter, height);
			blockData.setReference(reference);
			repository.getBlockRepository().save(blockData);
			reference = blockData.getSignature();
		}
		repository.saveChanges();
	}

	private static BlockData syntheticBlockData(int version, int height, long timestamp,
			PrivateKeyAccount minter, int marker) {
		byte[] minterSignature = new byte[64];
		byte[] transactionsSignature = new byte[64];
		Arrays.fill(minterSignature, (byte) marker);
		Arrays.fill(transactionsSignature, (byte) (marker + 1));
		return new BlockData(version, new byte[128], 0, 0L, transactionsSignature,
				height, timestamp, minter.getPublicKey(), minterSignature, 0, 0L,
				new byte[0], 0, null, null);
	}

	private static Map<String, Long> balances(Repository repository, String... accountNames)
			throws Exception {
		Map<String, Long> balances = new LinkedHashMap<>();
		for (String accountName : accountNames)
			balances.put(accountName, balance(repository, accountName));
		return balances;
	}

	private static long balance(Repository repository, String accountName) throws Exception {
		return Common.getTestAccount(repository, accountName).getConfirmedBalance(Asset.NATIVE);
	}

	private static Map<String, Integer> blocksMinted(Repository repository, String... accountNames)
			throws Exception {
		Map<String, Integer> blocksMinted = new LinkedHashMap<>();
		for (String accountName : accountNames)
			blocksMinted.put(accountName, AccountUtils.getBlocksMinted(repository, accountName));
		return blocksMinted;
	}

	private static Map<String, Integer> levels(Repository repository, String... accountNames)
			throws Exception {
		Map<String, Integer> levels = new LinkedHashMap<>();
		for (String accountName : accountNames)
			levels.put(accountName, Common.getTestAccount(repository, accountName).getLevel());
		return levels;
	}

	@SuppressWarnings("unchecked")
	private static void configureActivationWindow() throws Exception {
		BlockChain blockChain = BlockChain.getInstance();
		FieldUtils.writeField(blockChain, "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchSize", BATCH_SIZE, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchAccountsBlockCount", 3, true);
		FieldUtils.writeField(blockChain, "minAccountsToActivateShareBin", 2, true);
		Map<String, Long> existing = (Map<String, Long>) FieldUtils.readField(
				blockChain, "featureTriggers", true);
		Map<String, Long> triggers = new LinkedHashMap<>(existing);
		triggers.put("onlineNodeRewardBundlesPayoutHeight", (long) PAYOUT_HEIGHT);
		FieldUtils.writeField(blockChain, "featureTriggers", triggers, true);
	}
}
