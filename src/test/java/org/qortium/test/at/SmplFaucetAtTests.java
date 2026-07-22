package org.qortium.test.at;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests running the REAL SMPL faucet AT creation bytes against the
 * map-enabled test chain: deploy, real MESSAGE claims, block minting and orphaning.
 * <p>
 * The artifact under test is the canonical Previewnet "exactly once per account"
 * SMPL faucet (spec: qortium-casino docs/FAUCET_AT_V1_SMPL.md). Its consensus-critical
 * per-claim ordering is: GET claim marker, balance check, SET marker, GET readback,
 * PAY — an account must never be marked claimed and unpaid, and a payment must never
 * precede its marker. These tests assert the resulting on-chain OUTCOMES: a successful
 * claim uses most of the 500-step round budget, so multiple claims in one block settle
 * over later rounds and only ever produce one payment per account.
 */
public class SmplFaucetAtTests extends Common {

	/**
	 * Canonical faucet creation bytes (hex), embedded verbatim.
	 * Provenance: qortium-casino repo, at/faucet-v1-creation-bytes.txt, built by
	 * at/src/main/java/org/qortium/at/casino/FaucetV1.java (grant = 1 SMPL = 100000000 raw).
	 * SHA-256 of the decoded 484 bytes must be
	 * 4eff8a441f8312ce3bb5ececa8830cc9ebf39f74e2ff26e3b646bc4074380522 —
	 * verified by {@link #faucetCreationBytes()} before every use.
	 */
	private static final String FAUCET_CREATION_BYTES_HEX =
			"000200000148001100000000000000000000000035030100000001330503000000013303040000000135012500000002"
			+ "1e00000002eb3503070000000135030500000003240000000300000004de32030a32012832030b350127000000021b00"
			+ "000002283505300000000536053100000006000000053705330000000200000005000000062835010000000007350101"
			+ "00000008350102000000093501030000000a3402040000000b0000000c3501040000000d3501050000000e3201213301"
			+ "100000000d3301110000000e3506000000000f1b0000000f0b1a0000000e350530000000053605310000000600000005"
			+ "2100000006000000000f1a0000000e330113000000103206013201213301100000000d3301110000000e350600000000"
			+ "0f1e0000000f0b1a0000000e3301160000000733011700000008330118000000093301190000000a3705330000000200"
			+ "000005000000001a0000000e0000000005f5e10000000000000000000000000000000000000000000000000000000000"
			+ "000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
			+ "000000000000000000000007000000000000002000000000000000000000000000000000000000000000000000000000"
			+ "00000001";

	private static final String FAUCET_CREATION_BYTES_SHA256 =
			"4eff8a441f8312ce3bb5ececa8830cc9ebf39f74e2ff26e3b646bc4074380522";

	/** 1 SMPL grant, 1e8-scaled raw units (indivisible assets still use raw scaling on-chain). */
	private static final long GRANT_AMOUNT = 1L * Amounts.MULTIPLIER;
	private static final long SMPL_SUPPLY = 1000L * Amounts.MULTIPLIER;
	private static final long PREFUND_AMOUNT = 10L * Amounts.MULTIPLIER;
	private static final long NATIVE_FEE_RESERVE = 5L * Amounts.MULTIPLIER;

	/** A successful claim uses most of the round budget, so allow a few rounds for settlement. */
	private static final int MAX_SETTLE_BLOCKS = 4;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	// Case 1: happy path
	@Test
	public void testHappyPathClaimPaysGrantAndRecordsMarker() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			Account atAccount = deploy.getATAccount();
			String atAddress = atAccount.getAddress();
			assertEquals(PREFUND_AMOUNT, atAccount.getConfirmedBalance(smplAssetId));

			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			sendClaimMessage(repository, bob, atAddress);
			assertTrue("claim marker should appear within settlement window",
					mintUntilMarker(repository, atAddress, bobKey));

			assertEquals("sender must receive exactly one grant",
					bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(PREFUND_AMOUNT - GRANT_AMOUNT, atAccount.getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals(1, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 2: repeat claim in a later block is ignored
	@Test
	public void testRepeatClaimFromSameAccountIsIgnored() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			String atAddress = deploy.getATAccount().getAddress();
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			sendClaimMessage(repository, bob, atAddress);
			assertTrue(mintUntilMarker(repository, atAddress, bobKey));
			long bobAfterFirst = bob.getConfirmedBalance(smplAssetId);
			long atAfterFirst = deploy.getATAccount().getConfirmedBalance(smplAssetId);

			sendClaimMessage(repository, bob, atAddress);
			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS);

			assertEquals("no second payment", bobAfterFirst, bob.getConfirmedBalance(smplAssetId));
			assertEquals(atAfterFirst, deploy.getATAccount().getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals("map unchanged", 1, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 3: two claims from one account confirmed in the same block still pay exactly once.
	// The AT can only settle one claim per execution round (~448 of 500 steps), so the second
	// message is necessarily processed in a later round and must see the recorded marker.
	@Test
	public void testSameBlockDoubleClaimPaysExactlyOnce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			String atAddress = deploy.getATAccount().getAddress();
			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			importClaimMessage(repository, bob, atAddress);
			importClaimMessage(repository, bob, atAddress);
			BlockUtils.mintBlock(repository); // both claims confirm in this one block

			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS); // enough rounds for both to be processed

			assertEquals("exactly one payment total",
					bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(PREFUND_AMOUNT - GRANT_AMOUNT, deploy.getATAccount().getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals(1, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 4: distinct accounts each claim once, under distinct map keys
	@Test
	public void testDistinctAccountsEachReceiveOneGrantWithDistinctKeys() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			String atAddress = deploy.getATAccount().getAddress();

			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long chloeBefore = chloe.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());
			long[] chloeKey = claimKeyForAddress(chloe.getAddress());
			assertTrue("claim keys must be distinct",
					bobKey[0] != chloeKey[0] || bobKey[1] != chloeKey[1]);

			sendClaimMessage(repository, bob, atAddress);
			assertTrue(mintUntilMarker(repository, atAddress, bobKey));

			sendClaimMessage(repository, chloe, atAddress);
			assertTrue(mintUntilMarker(repository, atAddress, chloeKey));

			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(chloeBefore + GRANT_AMOUNT, chloe.getConfirmedBalance(smplAssetId));
			assertEquals(PREFUND_AMOUNT - 2 * GRANT_AMOUNT, deploy.getATAccount().getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, chloeKey));
			assertEquals(2, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 5: unfunded claims are ignored WITHOUT writing a marker, and resume after top-up.
	// This is the invariant that an account can never end up marked claimed and unpaid.
	@Test
	public void testUnfundedClaimLeavesNoMarkerAndSucceedsAfterTopUp() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			// Deploy with NO working-asset prefund: the faucet is alive but cannot pay.
			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, 0L);
			String atAddress = deploy.getATAccount().getAddress();
			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			sendClaimMessage(repository, bob, atAddress);
			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS);

			assertEquals("no payment while unfunded", bobBefore, bob.getConfirmedBalance(smplAssetId));
			assertNull("no marker may be written for an unpaid claim", getMarker(repository, atAddress, bobKey));
			assertEquals(0, repository.getATRepository().getATMapEntryCount(atAddress));

			// Top up the faucet, then the SAME account claims again and must succeed.
			transferSmpl(repository, alice, atAddress, smplAssetId, PREFUND_AMOUNT);

			sendClaimMessage(repository, bob, atAddress);
			assertTrue("claim must succeed after top-up", mintUntilMarker(repository, atAddress, bobKey));

			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
		}
	}

	// Case 6: at the per-AT map entry cap, the readback guard blocks payment AND marker;
	// raising the cap through the governance chain-parameter path lets the same account succeed.
	// (The cap can never be lowered below its previous effective value — 500 by default — so the
	// map is pre-seeded to the cap directly, the same technique ATMapStorageTests uses.)
	@Test
	public void testCapFullClaimPaysNothingUntilGovernanceRaisesCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			String atAddress = deploy.getATAccount().getAddress();
			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			// Fill the map to the default cap with synthetic entries (distinct from any claim key).
			List<ATMapChangeData> fillerEntries = new ArrayList<>();
			for (int i = 0; i < BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT; ++i)
				fillerEntries.add(new ATMapChangeData(atAddress, i, 0L, null, (long) i + 1));
			repository.getATRepository().saveATMapChanges(0, fillerEntries);
			repository.saveChanges();

			sendClaimMessage(repository, bob, atAddress);
			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS);

			assertEquals("cap-blocked claim must not pay", bobBefore, bob.getConfirmedBalance(smplAssetId));
			assertNull("cap-blocked claim must not leave a marker", getMarker(repository, atAddress, bobKey));
			assertEquals(BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT,
					repository.getATRepository().getATMapEntryCount(atAddress));

			// Governance path: dev-group-approved MAX_MAP_ENTRIES_PER_AT update (500 -> 501).
			int raisedCap = BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT + 1;
			int activationHeight = raiseMapEntryCap(repository, alice, raisedCap);
			assertEquals(raisedCap, BlockChain.getInstance().getMaxMapEntriesPerAt(repository,
					repository.getBlockRepository().getBlockchainHeight()));
			assertTrue(repository.getBlockRepository().getBlockchainHeight() >= activationHeight);

			// The same account claims again and must now succeed.
			sendClaimMessage(repository, bob, atAddress);
			assertTrue("claim must succeed once the cap is raised", mintUntilMarker(repository, atAddress, bobKey));

			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals(raisedCap, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 7: a MESSAGE from the creator sweeps the whole remaining working-asset balance
	// back to the creator and finishes the AT; later claims pay nothing.
	@Test
	public void testCreatorMessageSweepsBalanceAndFinishes() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			Account atAccount = deploy.getATAccount();
			String atAddress = atAccount.getAddress();

			// One ordinary claim first, so the sweep is of a partially drained balance.
			long[] bobKey = claimKeyForAddress(bob.getAddress());
			sendClaimMessage(repository, bob, atAddress);
			assertTrue(mintUntilMarker(repository, atAddress, bobKey));

			long aliceBefore = alice.getConfirmedBalance(smplAssetId);
			long remaining = atAccount.getConfirmedBalance(smplAssetId);
			assertEquals(PREFUND_AMOUNT - GRANT_AMOUNT, remaining);

			sendClaimMessage(repository, alice, atAddress); // creator message: shutdown
			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS);

			assertEquals("creator receives the whole remaining working-asset balance",
					aliceBefore + remaining, alice.getConfirmedBalance(smplAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(smplAssetId));
			assertTrue("AT must be finished after creator shutdown",
					repository.getATRepository().fromATAddress(atAddress).getIsFinished());

			// Subsequent claims pay nothing: the finished AT never runs again.
			long chloeBefore = chloe.getConfirmedBalance(smplAssetId);
			long[] chloeKey = claimKeyForAddress(chloe.getAddress());
			sendClaimMessage(repository, chloe, atAddress);
			BlockUtils.mintBlocks(repository, MAX_SETTLE_BLOCKS);

			assertEquals(chloeBefore, chloe.getConfirmedBalance(smplAssetId));
			assertNull(getMarker(repository, atAddress, chloeKey));
			assertEquals(1, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Case 8: orphaning the block that settled a claim removes the marker and restores both
	// balances; re-processing settles the identical claim again deterministically.
	@Test
	public void testOrphanedClaimRollsBackMarkerAndBalancesThenReplays() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			Account atAccount = deploy.getATAccount();
			String atAddress = atAccount.getAddress();

			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long atBefore = atAccount.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			sendClaimMessage(repository, bob, atAddress);
			int messageHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue(mintUntilMarker(repository, atAddress, bobKey));
			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));

			// Orphan the block(s) that settled the claim — but never the message block itself,
			// so the confirmed claim MESSAGE stays on chain for deterministic re-processing.
			while (getMarker(repository, atAddress, bobKey) != null) {
				assertTrue("claim settled in a later block than its message",
						repository.getBlockRepository().getBlockchainHeight() > messageHeight);
				BlockUtils.orphanLastBlock(repository);
			}

			assertNull("orphaning must remove the claim marker", getMarker(repository, atAddress, bobKey));
			assertEquals("orphaning must restore the sender's balance",
					bobBefore, bob.getConfirmedBalance(smplAssetId));
			assertEquals("orphaning must restore the AT's balance",
					atBefore, atAccount.getConfirmedBalance(smplAssetId));

			// Re-process: the same confirmed message settles again with the same outcome.
			assertTrue("re-processed claim must settle again", mintUntilMarker(repository, atAddress, bobKey));
			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(atBefore - GRANT_AMOUNT, atAccount.getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
		}
	}

	// Case 9: activation rehearsal at the test chain's atMapStorageHeight trigger — the
	// Previewnet 69,999 -> 70,000 analogue. Pre-trigger the faucet's first map opcode is
	// rejected (fatal error, no marker, no payment, no map root); a faucet deployed at the
	// trigger height works normally. Modeled on ATMapActivationTests' boundary technique.
	@Test
	public void testActivationBoundaryRejectsClaimsBeforeTriggerAndPaysAfter() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtMapStorageHeight();
			assertTrue("test chain's map trigger must leave room for pre-trigger activity", triggerHeight > 6);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long smplAssetId = issueSmpl(repository);

			// Deploy and claim strictly before the trigger: the claim's first map opcode
			// (GET_MAP_VALUE_KEYS_IN_A) must be rejected as a clean fatal error.
			DeployAtTransaction earlyDeploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			String earlyAtAddress = earlyDeploy.getATAccount().getAddress();
			long bobBefore = bob.getConfirmedBalance(smplAssetId);
			long[] bobKey = claimKeyForAddress(bob.getAddress());

			sendClaimMessage(repository, bob, earlyAtAddress);
			BlockUtils.mintBlock(repository); // the AT wakes and hits the map opcode here
			assertTrue("pre-trigger scenario must complete before the trigger height",
					repository.getBlockRepository().getBlockchainHeight() < triggerHeight);

			assertTrue("map opcodes must fail fatally before activation",
					repository.getATRepository().fromATAddress(earlyAtAddress).getHadFatalError());
			assertEquals("no payment before activation", bobBefore, bob.getConfirmedBalance(smplAssetId));
			assertNull(getMarker(repository, earlyAtAddress, bobKey));
			assertNull("no map root is committed before activation",
					repository.getATRepository().getLatestATState(earlyAtAddress).getMapRoot());

			// Advance to just before the trigger, then deploy at exactly the trigger height.
			int fillerBlocks = triggerHeight - 1 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			DeployAtTransaction deploy = deployFaucet(repository, alice, smplAssetId, PREFUND_AMOUNT);
			assertEquals("deployment must land exactly at the trigger height",
					triggerHeight, repository.getBlockRepository().getBlockchainHeight());
			String atAddress = deploy.getATAccount().getAddress();

			sendClaimMessage(repository, bob, atAddress);
			assertTrue("claim must settle at/after the trigger height",
					mintUntilMarker(repository, atAddress, bobKey));

			assertEquals(bobBefore + GRANT_AMOUNT, bob.getConfirmedBalance(smplAssetId));
			assertEquals(Long.valueOf(1L), getMarker(repository, atAddress, bobKey));
			assertEquals(1, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	// Artifact plumbing

	/** Decodes the embedded creation bytes, insisting on the canonical SHA-256 first. */
	private static byte[] faucetCreationBytes() {
		byte[] creationBytes = hexToBytes(FAUCET_CREATION_BYTES_HEX);
		assertEquals("embedded artifact must be the canonical 484-byte faucet", 484, creationBytes.length);
		assertArrayEquals("embedded artifact hash must match the canonical faucet build",
				hexToBytes(FAUCET_CREATION_BYTES_SHA256), Crypto.digest(creationBytes));
		return creationBytes;
	}

	private static byte[] hexToBytes(String hex) {
		byte[] bytes = new byte[hex.length() / 2];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		return bytes;
	}

	/**
	 * Computes the faucet's claim key for an address, mirroring the chain-side packing:
	 * PUT_ADDRESS_FROM_TX_IN_A_INTO_B zero-pads the 25 decoded address bytes to 32
	 * (ChainATAPI.putAddressFromTransactionInAIntoB) and the registers hold those bytes
	 * as big-endian longs, so the claim key is the first 16 bytes of SHA256 over exactly
	 * that padded packing, split big-endian into (key1, key2).
	 */
	private static long[] claimKeyForAddress(String address) {
		byte[] packedAddress = Bytes.ensureCapacity(Base58.decode(address), 32, 0);
		ByteBuffer digest = ByteBuffer.wrap(Crypto.digest(packedAddress));
		return new long[] { digest.getLong(), digest.getLong() };
	}

	// Chain plumbing

	private static long issueSmpl(Repository repository) throws DataException {
		// SMPL is indivisible; quantities and amounts are still 1e8-scaled raw longs on-chain.
		return AssetUtils.issueAsset(repository, "alice", "SMPL", SMPL_SUPPLY, false);
	}

	private static DeployAtTransaction deployFaucet(Repository repository, PrivateKeyAccount deployer,
			long smplAssetId, long prefundAmount) throws DataException {
		return AtUtils.doDeployAT(repository, deployer, faucetCreationBytes(), prefundAmount, smplAssetId,
				NATIVE_FEE_RESERVE);
	}

	private static Long getMarker(Repository repository, String atAddress, long[] claimKey) throws DataException {
		return repository.getATRepository().getATMapValue(atAddress, claimKey[0], claimKey[1]);
	}

	/** Mints until the claim marker appears, bounded by {@link #MAX_SETTLE_BLOCKS}. */
	private static boolean mintUntilMarker(Repository repository, String atAddress, long[] claimKey)
			throws DataException {
		for (int i = 0; i < MAX_SETTLE_BLOCKS; ++i) {
			if (getMarker(repository, atAddress, claimKey) != null)
				return true;

			BlockUtils.mintBlock(repository);
		}

		return getMarker(repository, atAddress, claimKey) != null;
	}

	private static void sendClaimMessage(Repository repository, PrivateKeyAccount sender, String atAddress)
			throws DataException {
		importClaimMessage(repository, sender, atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void importClaimMessage(Repository repository, PrivateKeyAccount sender, String atAddress)
			throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		int version = Transaction.getVersionByTimestamp(timestamp);
		byte[] messageData = "claim".getBytes(StandardCharsets.UTF_8);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
				sender.getPublicKey(), null, null);
		TransactionData transactionData = new MessageTransactionData(baseTransactionData, version, 0, atAddress,
				0L, null, messageData, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, transactionData);
		transactionData.setFee(messageTransaction.calcRecommendedFee());

		assertEquals(ValidationResult.OK, TransactionUtils.signAndImport(repository, transactionData, sender));
	}

	private static void transferSmpl(Repository repository, PrivateKeyAccount sender, String recipient,
			long assetId, long amount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
				sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new org.qortium.data.transaction.TransferAssetTransactionData(
				baseTransactionData, recipient, amount, assetId);

		TransactionUtils.signAndMint(repository, transactionData, sender);
	}

	/**
	 * Raises MAX_MAP_ENTRIES_PER_AT through the real governance path: a dev-group
	 * chain-parameter update transaction, group approval, settlement, then minting through
	 * the activation height. Returns the activation height.
	 */
	private static int raiseMapEntryCap(Repository repository, PrivateKeyAccount updater, int newCap)
			throws DataException {
		int settlementBlocks = Math.max(2, repository.getGroupRepository()
				.fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID).getMinimumBlockDelay() + 1);
		int activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ settlementBlocks
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 2;

		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.MAX_MAP_ENTRIES_PER_AT.id, activationHeight,
				ChainParameter.MAX_MAP_ENTRIES_PER_AT.encodeIntValue(newCap));
		TransactionUtils.signAndMint(repository, transactionData, updater);

		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, settlementBlocks);
		assertEquals(Transaction.ApprovalStatus.APPROVED,
				GroupUtils.getApprovalStatus(repository, transactionData.getSignature()));

		int blocksToActivation = activationHeight - repository.getBlockRepository().getBlockchainHeight();
		if (blocksToActivation > 0)
			BlockUtils.mintBlocks(repository, blocksToActivation);

		return activationHeight;
	}
}
