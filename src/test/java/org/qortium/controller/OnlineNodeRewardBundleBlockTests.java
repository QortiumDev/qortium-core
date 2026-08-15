package org.qortium.controller;

import io.druid.extendedset.intset.ConciseSet;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.crypto.RewardNodeIdentity;
import org.qortium.data.account.MintingAccountData;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.data.network.OnlineAccountBundleData;
import org.qortium.data.network.OnlineAccountBundleData.Member;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.transform.OnlineAccountBundleTransformer;
import org.qortium.transform.block.BlockTransformer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OnlineNodeRewardBundleBlockTests extends Common {

	private static final int CAPTURE_HEIGHT = 7;
	private static final int PAYOUT_HEIGHT = 10;

	private OnlineAccountsManager manager;
	private Path testRoot;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.manager = OnlineAccountsManager.getInstance();
		this.manager.removeAllOnlineAccounts();
		this.testRoot = Files.createTempDirectory("online-node-reward-bundle-block");
		configureActivationWindow();
	}

	@After
	public void afterTest() throws Exception {
		if (this.manager != null)
			this.manager.removeAllOnlineAccounts();
		if (this.testRoot != null)
			FileUtils.deleteDirectory(this.testRoot.toFile());
		Common.useDefaultSettings();
	}

	@Test
	public void testCaptureMintValidationAndExactPayoutCopy() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount minter = Common.getTestAccount(repository, "alice-reward-share");
			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "alice");
			repository.getAccountRepository().save(new MintingAccountData(
					minter.getPrivateKey(), minter.getPublicKey()));
			repository.saveChanges();

			long epoch = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
			assertTrue(this.manager.computeOurAccountsForTimestamp(epoch,
					this.testRoot.resolve("reward-node").resolve("identity.key")));

			BlockData genesis = repository.getBlockRepository().fromHeight(1);
			BlockData parent = new BlockData(Block.CURRENT_VERSION, genesis.getSignature(), 0, 0L,
					new byte[64], CAPTURE_HEIGHT - 1, epoch, minter.getPublicKey(), new byte[64],
					0, 0L, new byte[0], 0, null, null);
			repository.getBlockRepository().save(parent);

			Block capture = Block.mint(repository, parent, minter);
			assertNotNull(capture);
			assertEquals(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					capture.getBlockData().getVersion());
			assertEquals(CAPTURE_HEIGHT, (int) capture.getBlockData().getHeight());
			assertEquals(1, capture.getBlockData().getOnlineAccountsCount());
			assertNotNull(capture.getBlockData().getOnlineAccountBundles());
			assertFalse(capture.getBlockData().getOnlineAccountBundles().length == 0);
			assertEquals(Block.ValidationResult.OK, capture.areOnlineAccountsValid(false));

			List<OnlineAccountBundleData> capturedBundles = OnlineAccountBundleTransformer
					.fromBlockCohortBytes(capture.getBlockData().getOnlineAccountBundles(),
							OnlineAccountBundleTransformer.ChainIdentity.current());
			assertEquals(1, capturedBundles.size());
			int validNonce = capturedBundles.get(0).getMembers().get(0).getNonce();

			BlockData missingFlatUnion = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					new byte[0], 0, epoch, capture.getBlockData().getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, missingFlatUnion).areOnlineAccountsValid(false));
			assertEquals("Trusted replay still enforces the exact flat union",
					Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, missingFlatUnion).areOnlineAccountsValid(true));

			byte[] invalidNodeSignaturePayload = Arrays.copyOf(
					capture.getBlockData().getOnlineAccountBundles(),
					capture.getBlockData().getOnlineAccountBundles().length);
			invalidNodeSignaturePayload[invalidNodeSignaturePayload.length - 1] ^= 1;
			BlockData invalidSignature = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch,
					invalidNodeSignaturePayload);
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNT_SIGNATURE_INCORRECT,
					new Block(repository, invalidSignature).areOnlineAccountsValid(false));
			assertEquals("Trusted replay skips bundle signature and PoW only",
					Block.ValidationResult.OK,
					new Block(repository, invalidSignature).areOnlineAccountsValid(true));

			BlockData nonModulusEpoch = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch + 1,
					capture.getBlockData().getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, nonModulusEpoch).areOnlineAccountsValid(false));

			BlockData distantEpoch = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1,
					epoch - 3 * OnlineAccountsManager.getOnlineTimestampModulus(),
					capture.getBlockData().getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, distantEpoch).areOnlineAccountsValid(false));

			RewardNodeIdentity invalidPowIdentity = RewardNodeIdentity.loadOrCreate(
					this.testRoot.resolve("invalid-pow").resolve("identity.key"));
			int invalidNonce = 0;
			while (this.manager.verifyMemoryPoW(new org.qortium.data.network.OnlineAccountData(
					epoch, null, minter.getPublicKey(), invalidNonce), null))
				++invalidNonce;
			OnlineAccountBundleData invalidPowBundle = signedBundle(invalidPowIdentity, epoch,
					minter, invalidNonce);
			BlockData invalidPow = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch,
					cohortBytes(List.of(invalidPowBundle)));
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNT_NONCE_INCORRECT,
					new Block(repository, invalidPow).areOnlineAccountsValid(false));
			assertEquals(Block.ValidationResult.OK,
					new Block(repository, invalidPow).areOnlineAccountsValid(true));

			byte[] unknownSeed = new byte[32];
			Arrays.fill(unknownSeed, (byte) 0x55);
			PrivateKeyAccount unknownMember = new PrivateKeyAccount(repository, unknownSeed);
			OnlineAccountBundleData unknownBundle = signedBundle(invalidPowIdentity, epoch,
					unknownMember, 0);
			BlockData missingSelfShare = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch,
					cohortBytes(List.of(unknownBundle)));
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNT_UNKNOWN,
					new Block(repository, missingSelfShare).areOnlineAccountsValid(false));
			assertEquals("Trusted replay still enforces self-share existence",
					Block.ValidationResult.ONLINE_ACCOUNT_UNKNOWN,
					new Block(repository, missingSelfShare).areOnlineAccountsValid(true));

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount bobRewardShare = Common.getTestAccount(repository, "bob-reward-share");
			repository.getAccountRepository().save(new RewardShareData(bob.getPublicKey(), bob.getAddress(),
					bob.getAddress(), bobRewardShare.getPublicKey(), 100_00));
			OnlineAccountBundleData ineligibleBundle = signedBundle(invalidPowIdentity, epoch,
					bobRewardShare, 0);
			BlockData ineligibleSelfShare = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					encodeSelfShares(repository, List.of(bobRewardShare.getPublicKey())), 1, epoch,
					cohortBytes(List.of(ineligibleBundle)));
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, ineligibleSelfShare).areOnlineAccountsValid(false));
			assertEquals("Trusted replay still enforces capture eligibility",
					Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, ineligibleSelfShare).areOnlineAccountsValid(true));

			RewardNodeIdentity firstIdentity = RewardNodeIdentity.loadOrCreate(
					this.testRoot.resolve("first-node").resolve("identity.key"));
			RewardNodeIdentity secondIdentity = RewardNodeIdentity.loadOrCreate(
					this.testRoot.resolve("second-node").resolve("identity.key"));
			List<OnlineAccountBundleData> orderedBundles = new ArrayList<>(List.of(
					signedBundle(firstIdentity, epoch, minter, validNonce),
					signedBundle(secondIdentity, epoch, minter, validNonce)));
			orderedBundles.sort((left, right) -> OnlineAccountBundleData.compareUnsigned(
					left.getNodePublicKey(), right.getNodePublicKey()));
			List<OnlineAccountBundleData> reversedBundles = new ArrayList<>(orderedBundles);
			Collections.reverse(reversedBundles);
			BlockData nonCanonicalNodes = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch,
					cohortBytes(reversedBundles));
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, nonCanonicalNodes).areOnlineAccountsValid(true));

			BlockData repeatedNode = copyOnlineFields(capture.getBlockData(), CAPTURE_HEIGHT,
					capture.getBlockData().getEncodedOnlineAccounts(), 1, epoch,
					cohortBytes(List.of(orderedBundles.get(0), orderedBundles.get(0))));
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, repeatedNode).areOnlineAccountsValid(true));

			Block reminted = capture.remint(minter);
			assertNotNull(reminted);
			assertArrayEquals(capture.getBlockData().getEncodedOnlineAccounts(),
					reminted.getBlockData().getEncodedOnlineAccounts());
			assertArrayEquals(capture.getBlockData().getOnlineAccountBundles(),
					reminted.getBlockData().getOnlineAccountBundles());
			assertEquals(capture.getBlockData().getOnlineAccountsTimestamp(),
					reminted.getBlockData().getOnlineAccountsTimestamp());
			assertTrue(reminted.isSignatureValid());

			capture.sign();
			repository.getBlockRepository().save(capture.getBlockData());

			byte[] payoutParentMinterSignature = new byte[64];
			byte[] payoutParentTransactionsSignature = new byte[64];
			Arrays.fill(payoutParentMinterSignature, (byte) 1);
			Arrays.fill(payoutParentTransactionsSignature, (byte) 2);
			BlockData payoutParent = new BlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					capture.getBlockData().getSignature(), 0, 0L, payoutParentTransactionsSignature,
					PAYOUT_HEIGHT - 1, epoch, minter.getPublicKey(), payoutParentMinterSignature,
					0, 0L, new byte[0], 0, null, null);
			Block mintedPayout = Block.mint(repository, payoutParent, minter);
			assertNotNull(mintedPayout);
			BlockData payout = mintedPayout.getBlockData();
			assertEquals(Block.ValidationResult.OK,
					new Block(repository, payout).areOnlineAccountsValid(false));
			assertEquals(capture.getBlockData().getOnlineAccountsCount(),
					payout.getOnlineAccountsCount());
			assertEquals(capture.getBlockData().getOnlineAccountsTimestamp(),
					payout.getOnlineAccountsTimestamp());
			assertArrayEquals(capture.getBlockData().getOnlineAccountBundles(),
					payout.getOnlineAccountBundles());

			BlockData wrongPayoutEpoch = copyOnlineFields(payout, PAYOUT_HEIGHT,
					payout.getEncodedOnlineAccounts(), payout.getOnlineAccountsCount(),
					payout.getOnlineAccountsTimestamp() + OnlineAccountsManager.getOnlineTimestampModulus(),
					payout.getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, wrongPayoutEpoch).areOnlineAccountsValid(false));

			byte[] wrongPayoutPayloadBytes = Arrays.copyOf(payout.getOnlineAccountBundles(),
					payout.getOnlineAccountBundles().length);
			wrongPayoutPayloadBytes[wrongPayoutPayloadBytes.length - 1] ^= 1;
			BlockData wrongPayoutPayload = copyOnlineFields(payout, PAYOUT_HEIGHT,
					payout.getEncodedOnlineAccounts(), payout.getOnlineAccountsCount(),
					payout.getOnlineAccountsTimestamp(), wrongPayoutPayloadBytes);
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, wrongPayoutPayload).areOnlineAccountsValid(false));

			int capturedIndex = BlockTransformer.decodeOnlineAccounts(
					payout.getEncodedOnlineAccounts()).toArray()[0];
			byte[] wrongFlatSet = BlockTransformer.encodeOnlineAccounts(
					new ConciseSet().convert(List.of(capturedIndex + 1)));
			BlockData wrongPayoutFlatSet = copyOnlineFields(payout, PAYOUT_HEIGHT, wrongFlatSet, 1,
					payout.getOnlineAccountsTimestamp(), payout.getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, wrongPayoutFlatSet).areOnlineAccountsValid(false));

			byte[] wrongCountSet = BlockTransformer.encodeOnlineAccounts(
					new ConciseSet().convert(List.of(capturedIndex, capturedIndex + 1)));
			BlockData wrongPayoutCount = copyOnlineFields(payout, PAYOUT_HEIGHT, wrongCountSet, 2,
					payout.getOnlineAccountsTimestamp(), payout.getOnlineAccountBundles());
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID,
					new Block(repository, wrongPayoutCount).areOnlineAccountsValid(false));

			BlockData emptyNonOnlineBlock = copyOnlineFields(payout, PAYOUT_HEIGHT + 1,
					new byte[0], 0, null, null);
			assertEquals(Block.ValidationResult.OK,
					new Block(repository, emptyNonOnlineBlock).areOnlineAccountsValid(false));

			assertArrayEquals(capture.getBlockData().getEncodedOnlineAccounts(),
					payout.getEncodedOnlineAccounts());
			repository.discardChanges();
		}
	}

	private static OnlineAccountBundleData signedBundle(RewardNodeIdentity identity, long epoch,
			PrivateKeyAccount memberAccount, int nonce) throws Exception {
		List<Member> unsignedMembers = List.of(new Member(memberAccount.getPublicKey(), nonce, null));
		OnlineAccountBundleTransformer.ChainIdentity chainIdentity =
				OnlineAccountBundleTransformer.ChainIdentity.current();
		byte[] commitment = OnlineAccountBundleTransformer.computeMemberCommitment(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, epoch, identity.getPublicKey(),
				unsignedMembers);
		List<Member> signedMembers = List.of(unsignedMembers.get(0).withSignature(
				OnlineAccountBundleTransformer.signMember(memberAccount.getPrivateKey(), commitment)));
		byte[] approval = OnlineAccountBundleTransformer.computeNodeApproval(commitment, signedMembers);
		return OnlineAccountBundleTransformer.createBundle(chainIdentity,
				OnlineAccountBundleTransformer.PROTOCOL_VERSION, epoch, identity.getPublicKey(),
				signedMembers, identity.sign(approval));
	}

	private static byte[] cohortBytes(List<OnlineAccountBundleData> bundles) throws Exception {
		return OnlineAccountBundleTransformer.toBlockCohortBytes(bundles,
				OnlineAccountBundleTransformer.ChainIdentity.current());
	}

	private static byte[] encodeSelfShares(Repository repository, List<byte[]> publicKeys)
			throws Exception {
		List<byte[]> allSelfShares = repository.getAccountRepository().getSelfSharePublicKeys();
		List<Integer> indexes = new ArrayList<>();
		for (byte[] publicKey : publicKeys) {
			int index = -1;
			for (int i = 0; i < allSelfShares.size(); ++i) {
				if (Arrays.equals(publicKey, allSelfShares.get(i))) {
					index = i;
					break;
				}
			}
			if (index < 0)
				throw new AssertionError("Missing test self-share");
			indexes.add(index);
		}
		indexes.sort(null);
		return BlockTransformer.encodeOnlineAccounts(new ConciseSet().convert(indexes));
	}

	@SuppressWarnings("unchecked")
	private static void configureActivationWindow() throws IllegalAccessException {
		BlockChain blockChain = BlockChain.getInstance();
		FieldUtils.writeField(blockChain, "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchSize", PAYOUT_HEIGHT, true);
		FieldUtils.writeField(blockChain, "blockRewardBatchAccountsBlockCount",
				PAYOUT_HEIGHT - CAPTURE_HEIGHT, true);

		Map<String, Long> existing = (Map<String, Long>) FieldUtils.readField(
				blockChain, "featureTriggers", true);
		Map<String, Long> triggers = new LinkedHashMap<>(existing);
		triggers.put("onlineNodeRewardBundlesPayoutHeight", (long) PAYOUT_HEIGHT);
		FieldUtils.writeField(blockChain, "featureTriggers", triggers, true);
	}

	private static BlockData copyOnlineFields(BlockData source, int height,
			byte[] encodedOnlineAccounts, int onlineAccountsCount, Long onlineAccountsTimestamp,
			byte[] onlineAccountBundles) {
		byte[] reference = source.getReference() == null ? new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH]
				: source.getReference();
		return new BlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, reference,
				source.getTransactionCount(), source.getTotalFees(), source.getTransactionsSignature(),
				height, source.getTimestamp(), source.getMinterPublicKey(), source.getMinterSignature(),
				source.getATCount(), source.getATFees(), encodedOnlineAccounts, onlineAccountsCount,
				onlineAccountsTimestamp, onlineAccountBundles);
	}
}
