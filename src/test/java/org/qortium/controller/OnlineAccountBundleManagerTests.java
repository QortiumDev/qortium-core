package org.qortium.controller;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.crypto.RewardNodeIdentity;
import org.qortium.data.account.MintingAccountData;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.data.network.OnlineAccountData;
import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.network.message.GetOnlineAccountBundlesMessage;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageType;
import org.qortium.network.message.OnlineAccountBundlesMessage;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.settings.Settings;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnlineAccountBundleManagerTests extends Common {

	private OnlineAccountsManager manager;
	private Path testRoot;
	private Path identityPath;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.manager = OnlineAccountsManager.getInstance();
		this.manager.removeAllOnlineAccounts();
		this.testRoot = Files.createTempDirectory("online-account-bundle-manager");
		this.identityPath = this.testRoot.resolve("reward-node").resolve("identity.key");
	}

	@Test
	public void testProductionIdentityPathUsesActiveSettingsDirectory() {
		Path activeSettingsPath = Settings.getActiveSettingsPath();
		Path expectedIdentityPath = activeSettingsPath.getParent()
				.resolve("reward-node").resolve("identity.key");

		assertEquals(expectedIdentityPath, OnlineAccountsManager.getRewardNodeIdentityPath());
	}

	@After
	public void afterTest() throws Exception {
		if (this.manager != null)
			this.manager.removeAllOnlineAccounts();
		if (this.testRoot != null)
			FileUtils.deleteDirectory(this.testRoot.toFile());
	}

	@Test
	public void testProductionComputationIncludesThreeKeysButLegacyRemainsCapped() throws Exception {
		List<PrivateKeyAccount> rewardShareAccounts = configureThreeMintingAccounts();
		long timestamp = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
		int nextBlockHeight = nextBlockHeight();

		assertTrue(this.manager.computeOurAccountsForTimestamp(timestamp, this.identityPath));

		assertEquals("Legacy flat announcements must retain their historical two-key cap", 2,
				this.manager.getOnlineAccounts(timestamp, nextBlockHeight).size());
		List<OnlineAccountBundleData> bundles = this.manager.getOnlineAccountBundles(timestamp,
				nextBlockHeight);
		assertEquals(1, bundles.size());
		OnlineAccountBundleData bundle = bundles.get(0);
		assertEquals(3, bundle.getMembers().size());
		assertTrue(OnlineAccountBundleTransformer.verifySignatures(bundle,
				OnlineAccountBundleTransformer.ChainIdentity.current()));

		Set<String> expectedPublicKeys = rewardShareAccounts.stream()
				.map(account -> org.qortium.utils.Base58.encode(account.getPublicKey()))
				.collect(Collectors.toSet());
		Set<String> actualPublicKeys = bundle.getMembers().stream()
				.map(member -> org.qortium.utils.Base58.encode(member.getPublicKey()))
				.collect(Collectors.toSet());
		assertEquals(expectedPublicKeys, actualPublicKeys);
		for (Member member : bundle.getMembers())
			assertTrue(this.manager.verifyMemoryPoW(new OnlineAccountData(
					timestamp, null, member.getPublicKey(), member.getNonce()), null));

		CapturingPeer peer = new CapturingPeer();
		Controller.getInstance().onNetworkMessage(peer,
				new GetOnlineAccountBundlesMessage(Collections.emptyList()));
		assertEquals(1, peer.sentMessages.size());
		OnlineAccountBundlesMessage response = (OnlineAccountBundlesMessage) peer.sentMessages.get(0);
		assertEquals(1, response.getBundles().size());
		assertArrayEquals(bundle.getCommitmentHash(), response.getBundles().get(0).getCommitmentHash());

		peer.sentMessages.clear();
		GetOnlineAccountBundlesMessage.BundleIdentifier known =
				new GetOnlineAccountBundlesMessage.BundleIdentifier(timestamp, bundle.getNodePublicKey(),
						bundle.getCommitmentHash());
		Controller.getInstance().onNetworkMessage(peer,
				new GetOnlineAccountBundlesMessage(List.of(known)));
		assertEquals(1, peer.sentMessages.size());
		assertTrue(((OnlineAccountBundlesMessage) peer.sentMessages.get(0)).getBundles().isEmpty());
	}

	@Test
	public void testDeterministicSameNodeSelectionIsArrivalIndependent() {
		long timestamp = OnlineAccountsManager.toOnlineAccountTimestamp(System.currentTimeMillis());
		byte[] nodePublicKey = filled(32, 9);
		OnlineAccountBundleData oneMember = syntheticBundle(timestamp, nodePublicKey, 1, filled(32, 1));
		OnlineAccountBundleData twoMembersHighHash = syntheticBundle(timestamp, nodePublicKey, 2,
				filled(32, 0x80));
		OnlineAccountBundleData twoMembersLowHash = syntheticBundle(timestamp, nodePublicKey, 2,
				filled(32, 0x7f));

		assertTrue(this.manager.cacheValidatedOnlineAccountBundle(oneMember));
		assertTrue(this.manager.cacheValidatedOnlineAccountBundle(twoMembersHighHash));
		assertFalse("A smaller bundle cannot displace the larger winner",
				this.manager.cacheValidatedOnlineAccountBundle(oneMember));
		assertTrue("Unsigned-smallest commitment must win an equal-size tie",
				this.manager.cacheValidatedOnlineAccountBundle(twoMembersLowHash));
		assertFalse("The equal-size higher commitment must lose regardless of arrival order",
				this.manager.cacheValidatedOnlineAccountBundle(twoMembersHighHash));

		this.manager.removeAllOnlineAccounts();
		assertTrue(this.manager.cacheValidatedOnlineAccountBundle(twoMembersLowHash));
		assertFalse(this.manager.cacheValidatedOnlineAccountBundle(twoMembersHighHash));
		assertFalse(this.manager.cacheValidatedOnlineAccountBundle(oneMember));
	}

	@Test
	public void testInboundBundleIsFullyValidatedBeforeCaching() throws Exception {
		List<PrivateKeyAccount> rewardShareAccounts = configureThreeMintingAccounts();
		long timestamp = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
		int nextBlockHeight = nextBlockHeight();
		assertTrue(this.manager.computeOurAccountsForTimestamp(timestamp, this.identityPath));
		OnlineAccountBundleData validBundle = this.manager
				.getOnlineAccountBundles(timestamp, nextBlockHeight).get(0);

		this.manager.removeAllOnlineAccounts();
		byte[] invalidNodeSignature = validBundle.getNodeSignature();
		invalidNodeSignature[0] ^= 1;
		OnlineAccountBundleData invalidBundle = new OnlineAccountBundleData(
				validBundle.getProtocolVersion(), validBundle.getTimestamp(), validBundle.getNodePublicKey(),
				validBundle.getMembers(), invalidNodeSignature, validBundle.getCommitmentHash());
		Controller.getInstance().onNetworkMessage(null,
				new OnlineAccountBundlesMessage(List.of(invalidBundle)));
		this.manager.processOnlineAccountBundlesImportQueue();
		assertTrue(this.manager.getOnlineAccountBundles(timestamp, nextBlockHeight).isEmpty());

		OnlineAccountBundleData invalidPowBundle = createBundleWithInvalidPoW(timestamp,
				rewardShareAccounts.get(0));
		Controller.getInstance().onNetworkMessage(null,
				new OnlineAccountBundlesMessage(List.of(invalidPowBundle)));
		this.manager.processOnlineAccountBundlesImportQueue();
		assertTrue("A correctly signed bundle with invalid MemoryPoW must be rejected",
				this.manager.getOnlineAccountBundles(timestamp, nextBlockHeight).isEmpty());

		RewardShareData removedRewardShare;
		try (Repository repository = RepositoryManager.getRepository()) {
			Member member = validBundle.getMembers().get(0);
			removedRewardShare = repository.getAccountRepository().getRewardShare(member.getPublicKey());
			repository.getAccountRepository().delete(removedRewardShare.getMinterPublicKey(),
					removedRewardShare.getRecipient());
			repository.saveChanges();
		}
		Controller.getInstance().onNetworkMessage(null,
				new OnlineAccountBundlesMessage(List.of(validBundle)));
		this.manager.processOnlineAccountBundlesImportQueue();
		assertTrue("A signed bundle containing an ineligible member must be rejected",
				this.manager.getOnlineAccountBundles(timestamp, nextBlockHeight).isEmpty());

		try (Repository repository = RepositoryManager.getRepository()) {
			repository.getAccountRepository().save(removedRewardShare);
			repository.saveChanges();
		}
		Controller.getInstance().onNetworkMessage(null,
				new OnlineAccountBundlesMessage(List.of(validBundle)));
		this.manager.processOnlineAccountBundlesImportQueue();
		List<OnlineAccountBundleData> imported = this.manager.getOnlineAccountBundles(timestamp,
				nextBlockHeight);
		assertEquals(1, imported.size());
		assertArrayEquals(validBundle.getCommitmentHash(), imported.get(0).getCommitmentHash());
	}

	@Test
	public void testPerEpochCacheRetainsExactUnsignedSmallestNodeSet() {
		long newestTimestamp = OnlineAccountsManager.toOnlineAccountTimestamp(System.currentTimeMillis());
		long olderTimestamp = newestTimestamp - OnlineAccountsManager.getOnlineTimestampModulus();
		int candidateCount = OnlineAccountsManager.MAX_CACHED_ONLINE_ACCOUNT_BUNDLES_PER_TIMESTAMP + 26;
		Set<String> expectedNodeKeys = new HashSet<>();
		for (int i = 0; i < OnlineAccountsManager.MAX_CACHED_ONLINE_ACCOUNT_BUNDLES_PER_TIMESTAMP; ++i)
			expectedNodeKeys.add(org.qortium.utils.Base58.encode(nodePublicKey(i)));

		for (int i = candidateCount - 1; i >= 0; --i)
			this.manager.cacheValidatedOnlineAccountBundle(syntheticBundle(olderTimestamp,
					nodePublicKey(i), 1, nodePublicKey(i + 10_000)));
		assertEquals(expectedNodeKeys, inventoryNodeKeys(this.manager.getOnlineAccountBundleInventory()));

		this.manager.removeAllOnlineAccounts();
		for (int i = 0; i < candidateCount; ++i)
			this.manager.cacheValidatedOnlineAccountBundle(syntheticBundle(olderTimestamp,
					nodePublicKey(i), 1, nodePublicKey(i + 10_000)));
		assertEquals("Retained nodes must be independent of insertion order", expectedNodeKeys,
				inventoryNodeKeys(this.manager.getOnlineAccountBundleInventory()));

		assertTrue(this.manager.cacheValidatedOnlineAccountBundle(syntheticBundle(newestTimestamp,
				nodePublicKey(20_000), 1, nodePublicKey(30_000))));

		List<GetOnlineAccountBundlesMessage.BundleIdentifier> inventory =
				this.manager.getOnlineAccountBundleInventory();
		assertEquals(GetOnlineAccountBundlesMessage.MAX_IDENTIFIERS, inventory.size());
		assertEquals("The current epoch must not be starved by a full older inventory",
				newestTimestamp, inventory.get(0).getTimestamp());
		assertEquals(1, inventory.stream()
				.filter(identifier -> identifier.getTimestamp() == newestTimestamp).count());
	}

	@Test
	public void testConcurrentBundleQueueAdmissionIsHardCappedAndDrainIsBounded() throws Exception {
		int workerCount = 16;
		int bundlesPerWorker = 256;
		long timestamp = OnlineAccountsManager.toOnlineAccountTimestamp(System.currentTimeMillis());
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch ready = new CountDownLatch(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		try {
			for (int worker = 0; worker < workerCount; ++worker) {
				final int workerIndex = worker;
				futures.add(executor.submit(() -> {
					List<OnlineAccountBundleData> bundles = new ArrayList<>();
					for (int i = 0; i < bundlesPerWorker; ++i) {
						int value = workerIndex * bundlesPerWorker + i;
						bundles.add(syntheticBundle(timestamp, nodePublicKey(value), 1,
								nodePublicKey(value + 10_000)));
					}
					ready.countDown();
					start.await();
					return this.manager.queueOnlineAccountBundles(bundles);
				}));
			}

			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			int accepted = 0;
			for (Future<Integer> future : futures)
				accepted += future.get(10, TimeUnit.SECONDS);
			assertEquals(OnlineAccountsManager.MAX_QUEUED_ONLINE_ACCOUNT_BUNDLES, accepted);
			assertEquals(OnlineAccountsManager.MAX_QUEUED_ONLINE_ACCOUNT_BUNDLES,
					this.manager.getOnlineAccountBundlesImportQueueSize());
		} finally {
			executor.shutdownNow();
		}

		this.manager.processOnlineAccountBundlesImportQueue();
		assertEquals(OnlineAccountsManager.MAX_QUEUED_ONLINE_ACCOUNT_BUNDLES
					- OnlineAccountsManager.MAX_ONLINE_ACCOUNT_BUNDLES_VALIDATED_PER_CYCLE,
				this.manager.getOnlineAccountBundlesImportQueueSize());
	}

	@Test
	public void testBundleValidationBatchBoundsAggregateMemberWork() {
		long timestamp = OnlineAccountsManager.toOnlineAccountTimestamp(System.currentTimeMillis());
		List<OnlineAccountBundleData> bundles = new ArrayList<>();
		bundles.add(syntheticBundle(timestamp, nodePublicKey(10_000),
				OnlineAccountsManager.MAX_ONLINE_ACCOUNT_BUNDLE_MEMBER_OCCURRENCES_VALIDATED_PER_CYCLE,
				nodePublicKey(20_000)));
		for (int i = 0; i <= OnlineAccountsManager.MAX_ONLINE_ACCOUNT_BUNDLES_VALIDATED_PER_CYCLE; ++i)
			bundles.add(syntheticBundle(timestamp, nodePublicKey(30_000 + i), 1,
					nodePublicKey(40_000 + i)));

		assertEquals(bundles.size(), this.manager.queueOnlineAccountBundles(bundles));
		this.manager.processOnlineAccountBundlesImportQueue();
		assertEquals("One maximum-size bundle must consume the complete per-cycle member budget",
				bundles.size() - 1, this.manager.getOnlineAccountBundlesImportQueueSize());

		this.manager.processOnlineAccountBundlesImportQueue();
		assertEquals("The independent bundle-count cap must still apply to small bundles", 1,
				this.manager.getOnlineAccountBundlesImportQueueSize());
	}

	@Test
	public void testBundleMessageThreadLimitsAreExplicit() {
		assertEquals(Integer.valueOf(2),
				Settings.getInstance().getMaxThreadsForMessageType(MessageType.ONLINE_ACCOUNT_BUNDLES));
		assertEquals(Integer.valueOf(5),
				Settings.getInstance().getMaxThreadsForMessageType(MessageType.GET_ONLINE_ACCOUNT_BUNDLES));
	}

	private List<PrivateKeyAccount> configureThreeMintingAccounts() throws Exception {
		List<PrivateKeyAccount> rewardShareAccounts = new ArrayList<>();
		try (Repository repository = RepositoryManager.getRepository()) {
			for (String accountName : List.of("alice", "bob", "chloe")) {
				PrivateKeyAccount account = Common.getTestAccount(repository, accountName);
				PrivateKeyAccount rewardShareAccount = Common.getTestAccount(repository,
						accountName + "-reward-share");
				TestChainBootstrapUtils.ensureMintingGroupMember(repository, accountName);
				if (repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey()) == null)
					repository.getAccountRepository().save(new RewardShareData(account.getPublicKey(),
							account.getAddress(), account.getAddress(), rewardShareAccount.getPublicKey(), 100_00));
				repository.getAccountRepository().save(new MintingAccountData(
						rewardShareAccount.getPrivateKey(), rewardShareAccount.getPublicKey()));
				rewardShareAccounts.add(rewardShareAccount);
			}
			repository.saveChanges();
		}
		return rewardShareAccounts;
	}

	private int nextBlockHeight() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			return repository.getBlockRepository().getBlockchainHeight() + 1;
		}
	}

	private OnlineAccountBundleData createBundleWithInvalidPoW(long timestamp,
			PrivateKeyAccount memberAccount) throws Exception {
		int invalidNonce = 0;
		while (this.manager.verifyMemoryPoW(new OnlineAccountData(timestamp, null,
				memberAccount.getPublicKey(), invalidNonce), null))
			++invalidNonce;

		RewardNodeIdentity identity = RewardNodeIdentity.loadOrCreate(this.identityPath);
		List<Member> unsignedMembers = List.of(new Member(memberAccount.getPublicKey(), invalidNonce,
				null));
		OnlineAccountBundleTransformer.ChainIdentity chainIdentity =
				OnlineAccountBundleTransformer.ChainIdentity.current();
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, timestamp, identity.getPublicKey(),
				unsignedMembers);
		List<Member> signedMembers = List.of(unsignedMembers.get(0).withSignature(
				OnlineAccountBundleTransformer.signMember(memberAccount.getPrivateKey(), commitment)));
		byte[] approval = OnlineAccountBundleTransformer.computeNodeApproval(commitment, signedMembers);
		return OnlineAccountBundleTransformer.createBundle(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, timestamp, identity.getPublicKey(),
				signedMembers, identity.sign(approval));
	}

	private static OnlineAccountBundleData syntheticBundle(long timestamp, byte[] nodePublicKey,
			int memberCount, byte[] commitmentHash) {
		List<Member> members = new ArrayList<>();
		for (int i = 0; i < memberCount; ++i)
			members.add(new Member(filled(32, i + 1), i, filled(64, i + 2)));
		return new OnlineAccountBundleData(OnlineAccountBundleTransformer.PROTOCOL_VERSION, timestamp,
				nodePublicKey, members, filled(64, 7), commitmentHash);
	}

	private static byte[] filled(int length, int value) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static byte[] nodePublicKey(int value) {
		byte[] publicKey = new byte[32];
		ByteBuffer.wrap(publicKey).putInt(value);
		return publicKey;
	}

	private static Set<String> inventoryNodeKeys(
			List<GetOnlineAccountBundlesMessage.BundleIdentifier> inventory) {
		return inventory.stream()
				.map(identifier -> org.qortium.utils.Base58.encode(identifier.getNodePublicKey()))
				.collect(Collectors.toSet());
	}

	private static class CapturingPeer extends Peer {
		private final List<Message> sentMessages = new ArrayList<>();

		private CapturingPeer() {
			super(new PeerData(new PeerAddress("127.0.0.1:24892")), Peer.NETWORK);
		}

		@Override
		public boolean sendMessage(Message message) {
			this.sentMessages.add(message);
			return true;
		}
	}
}
