package org.qortium.block;

import io.druid.extendedset.intset.ConciseSet;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.data.blockchain.ChainParameterData;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.block.BlockTransformer;
import org.qortium.transaction.Transaction;
import org.qortium.utils.NTP;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NodeRewardBundleTrustOrphanTests extends Common {

	private static final int PAYOUT_HEIGHT = 10;
	private static final int BATCH_SIZE = 10;
	private static final int CAPTURE_START_HEIGHT = PAYOUT_HEIGHT - 3;

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
	public void testOrphanRestoresEnteringPayoutTrustBeforeReconstructingPlan() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount seed = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount minter = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount firstRater = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount secondRater = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount target = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount targetSelfShare = Common.getTestAccount(repository,
					"chloe-reward-share");

			repository.getAccountRepository().save(new RewardShareData(target.getPublicKey(),
					target.getAddress(), target.getAddress(), targetSelfShare.getPublicKey(), 100_00));
			AccountUtils.setMintingData(repository, "chloe", 10, 1);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(
					repository, seed, List.of(firstRater, secondRater));
			AccountTrustTestUtils.saveAccountRating(repository, firstRater, target,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.saveAccountRating(repository, secondRater, target,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
			assertEquals(AccountTrustStatus.UNVERIFIED,
					trustSnapshot(repository, target).getMappedTrustStatus());

			Long now = NTP.getTime();
			assertNotNull(now);
			long epoch = org.qortium.controller.OnlineAccountsManager
					.toOnlineAccountTimestamp(now);
			OnlineAccountBundleData targetBundle = bundle(epoch, 1,
					List.of(targetSelfShare.getPublicKey()));
			byte[] payload = OnlineAccountBundleTransformer.toBlockCohortBytes(
					List.of(targetBundle), OnlineAccountBundleTransformer.ChainIdentity.current());
			byte[] encodedOnlineAccounts = encodeSelfShares(repository,
					List.of(targetSelfShare.getPublicKey()));
			seedSyntheticCaptureWindow(repository, now, epoch, minter, payload,
					encodedOnlineAccounts);

			long initialTargetBalance = target.getConfirmedBalance(Asset.NATIVE);
			int initialBlocksMinted = target.getBlocksMinted();
			int initialLevel = target.getLevel();

			// Model an already-approved update scheduled for the payout height. Approval mechanics
			// are covered separately; this test isolates the entering-height trust boundary.
			saveChainParameterActivation(repository, seed, now,
					ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT, 1);
			repository.saveChanges();
			assertEquals(2, BlockChain.getInstance()
					.getAccountTrustSuspiciousMinBranchCount(repository, PAYOUT_HEIGHT - 1));
			assertEquals(1, BlockChain.getInstance()
					.getAccountTrustSuspiciousMinBranchCount(repository, PAYOUT_HEIGHT));
			AccountTrustDerivationData payoutHeightTrust = AccountTrustDerivation
					.deriveAll(repository, PAYOUT_HEIGHT).stream()
					.filter(data -> target.getAddress().equals(data.getAccountAddress()))
					.findFirst().orElseThrow();
			String payoutHeightTrustDetails = payoutHeightTrust.getCategories().stream()
					.filter(category -> category.getCategory() == AccountRatingCategory.SUBJECT)
					.map(category -> String.format("score=%d levelScore=%d impacts=%s",
							category.getScore(), category.getLevelScore(), category.getImpacts().stream()
									.map(impact -> String.format("%d/%d/%s", impact.getRatingConfidence(),
											impact.getImpact(), impact.getTrustBranchKeys()))
									.toList()))
					.findFirst().orElse("missing SUBJECT category");
			assertEquals(payoutHeightTrustDetails, AccountTrustStatus.SUSPICIOUS,
					payoutHeightTrust.getDerivedTrustStatus());

			Block payout = Block.mint(repository,
					repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT - 1), minter);
			assertNotNull(payout);
			payout.sign();
			payout.clearOnlineAccountsValidationCache();
			assertEquals(Block.ValidationResult.OK, payout.isValid());
			assertTrue(payout.hasTrustSnapshotAffectingParameterActivation(PAYOUT_HEIGHT));

			long expectedReward = BlockChain.getInstance().getRewardAtHeight(repository,
					PAYOUT_HEIGHT) * BATCH_SIZE;
			assertEquals(100_000_000_000L, expectedReward);

			payout.process();
			repository.saveChanges();

			assertEquals(initialTargetBalance + expectedReward,
					target.getConfirmedBalance(Asset.NATIVE));
			assertEquals(initialBlocksMinted + BATCH_SIZE, target.getBlocksMinted().intValue());
			assertEquals(initialLevel, target.getLevel().intValue());
			AccountTrustSnapshotData suspiciousSnapshot = trustSnapshot(repository, target);
			assertEquals(PAYOUT_HEIGHT, suspiciousSnapshot.getSnapshotHeight());
			assertEquals(AccountTrustStatus.SUSPICIOUS,
					suspiciousSnapshot.getMappedTrustStatus());
			assertFalse(new org.qortium.account.Account(repository, target.getAddress())
					.canMint(false));

			// Reload the block so orphaning cannot reuse the forward payout plan cache.
			BlockUtils.orphanLastBlock(repository);

			assertEquals(initialTargetBalance, target.getConfirmedBalance(Asset.NATIVE));
			assertEquals(initialBlocksMinted, target.getBlocksMinted().intValue());
			assertEquals(initialLevel, target.getLevel().intValue());
			AccountTrustSnapshotData restoredSnapshot = trustSnapshot(repository, target);
			assertEquals(PAYOUT_HEIGHT - 1, restoredSnapshot.getSnapshotHeight());
			assertEquals(AccountTrustStatus.UNVERIFIED,
					restoredSnapshot.getMappedTrustStatus());
			assertTrue(new org.qortium.account.Account(repository, target.getAddress())
					.canMint(false));
			Block remintedPayout = Block.mint(repository,
					repository.getBlockRepository().fromHeight(PAYOUT_HEIGHT - 1), minter);
			assertNotNull(remintedPayout);
			remintedPayout.sign();
			remintedPayout.clearOnlineAccountsValidationCache();
			assertEquals(Block.ValidationResult.OK, remintedPayout.isValid());
			remintedPayout.process();
			repository.saveChanges();

			assertEquals(initialTargetBalance + expectedReward,
					target.getConfirmedBalance(Asset.NATIVE));
			assertEquals(initialBlocksMinted + BATCH_SIZE, target.getBlocksMinted().intValue());
			assertEquals(AccountTrustStatus.SUSPICIOUS,
					trustSnapshot(repository, target).getMappedTrustStatus());

			BlockUtils.orphanLastBlock(repository);
			repository.discardChanges();
		}
	}

	private static AccountTrustSnapshotData trustSnapshot(Repository repository,
			PrivateKeyAccount account) throws Exception {
		AccountTrustSnapshotData snapshot = repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(account.getAddress(), AccountRatingCategory.SUBJECT);
		assertNotNull(snapshot);
		return snapshot;
	}

	private static void saveChainParameterActivation(Repository repository,
			PrivateKeyAccount signer, long timestamp, ChainParameter parameter, int value)
			throws Exception {
		byte[] parameterValue = parameter.encodeIntValue(value);
		assertTrue(parameter.isValidValue(repository, PAYOUT_HEIGHT, parameterValue));
		BaseTransactionData parameterBase = new BaseTransactionData(timestamp, Group.NO_GROUP,
				signer.getPublicKey(), 0L, null);
		ChainParameterUpdateTransactionData transactionData =
				new ChainParameterUpdateTransactionData(parameterBase, parameter.id,
						PAYOUT_HEIGHT, parameterValue);
		Transaction.fromData(repository, transactionData).sign(signer);
		transactionData.setApprovalStatus(Transaction.ApprovalStatus.APPROVED);
		repository.getTransactionRepository().save(transactionData);
		repository.getChainParameterRepository().save(new ChainParameterData(
				transactionData.getSignature(), parameter.id, PAYOUT_HEIGHT, parameterValue));
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
				throw new AssertionError("Missing self-share in trust-orphan test");
			indexes.add(index);
		}
		indexes.sort(null);
		return BlockTransformer.encodeOnlineAccounts(new ConciseSet().convert(indexes));
	}

	private static void seedSyntheticCaptureWindow(Repository repository, long now, long epoch,
			PrivateKeyAccount minter, byte[] payload, byte[] encodedOnlineAccounts) throws Exception {
		byte[] reference = repository.getBlockRepository().fromHeight(1).getSignature();
		long firstTimestamp = now - PAYOUT_HEIGHT * 60_000L;
		for (int height = 2; height < PAYOUT_HEIGHT; ++height) {
			int version = height >= CAPTURE_START_HEIGHT
					? Block.ONLINE_NODE_REWARD_BUNDLES_VERSION : Block.CURRENT_VERSION;
			BlockData blockData = syntheticBlockData(version, height,
					firstTimestamp + height * 60_000L, minter, height);
			blockData.setReference(reference);
			if (height >= CAPTURE_START_HEIGHT) {
				blockData.setOnlineAccountBundles(payload);
				blockData.setOnlineAccountsTimestamp(epoch);
				blockData.setOnlineAccountsCount(1);
				blockData.setEncodedOnlineAccounts(encodedOnlineAccounts);
			}
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

	@SuppressWarnings("unchecked")
	private static void configureActivationWindow() throws Exception {
		BlockChain blockChain = BlockChain.getInstance();
		FieldUtils.writeField(blockChain, "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchSize", BATCH_SIZE, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchAccountsBlockCount", 3, true);
		Map<String, Long> existing = (Map<String, Long>) FieldUtils.readField(
				blockChain, "featureTriggers", true);
		Map<String, Long> triggers = new LinkedHashMap<>(existing);
		triggers.put("onlineNodeRewardBundlesPayoutHeight", (long) PAYOUT_HEIGHT);
		FieldUtils.writeField(blockChain, "featureTriggers", triggers, true);
	}
}
