package org.qortium.test.at.chainfunctioncodes;

import com.google.common.primitives.Bytes;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.at.ATStateData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bytecode-level tests for {@link ChainFunctionCode#GET_TRUST_STATUS_FROM_ACCOUNT_IN_B} (0x0522).
 *
 * <p>The opcode must read only the trust snapshot materialized by block processing
 * ({@code Account.getTrustStatus()}), never a tip-relative live derivation, so an AT executing
 * in block H sees trust state as of the end of block H-1.</p>
 */
public class GetTrustStatusTests extends Common {

	private static final long FUNDING_AMOUNT = 1_00000000L;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
	}

	@Test
	public void testTrustStatusReflectsEachStoredSnapshotValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			byte[] accountBytes = paddedAddressBytes(bob.getAddress());

			// Initial snapshot population, so later blocks don't re-derive over our fixtures
			BlockUtils.mintBlock(repository);

			for (AccountTrustStatus status : AccountTrustStatus.values()) {
				AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
						AccountTrustTestUtils.subjectTrustSnapshot(bob, status));

				String atAddress = AtUtils.doDeployAT(repository, deployer,
						buildOneShotTrustReaderAT(accountBytes), FUNDING_AMOUNT).getATAccount().getAddress();
				BlockUtils.mintBlock(repository);

				assertEquals("stored status " + status + " must round-trip through the opcode",
						status.getValue(), extractResult(repository, atAddress));
			}
		}
	}

	@Test
	public void testTrustStatusFromPublicKey() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			BlockUtils.mintBlock(repository);
			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.GOLD));

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotTrustReaderAT(bob.getPublicKey()), FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(AccountTrustStatus.GOLD.getValue(), extractResult(repository, atAddress));
		}
	}

	@Test
	public void testUnknownAccountIsUnverified() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			// Valid address encoding, but the account has never appeared on chain
			PrivateKeyAccount unknownAccount = Common.generateRandomSeedAccount(repository);
			byte[] accountBytes = paddedAddressBytes(unknownAccount.getAddress());

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotTrustReaderAT(accountBytes), FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), extractResult(repository, atAddress));
		}
	}

	@Test
	public void testTrustQueryChargesOrdinaryFunctionFee() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			byte[] accountBytes = paddedAddressBytes(bob.getAddress());

			String trustAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotTrustReaderAT(accountBytes), FUNDING_AMOUNT).getATAccount().getAddress();
			String levelAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotReaderAT(accountBytes, ChainFunctionCode.GET_ACCOUNT_LEVEL_FROM_ACCOUNT_IN_B),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			BlockChain.CiyamAtSettings ciyamAtSettings = BlockChain.getInstance().getCiyamAtSettings();
			long trustFees = repository.getATRepository().getLatestATState(trustAtAddress).getFees();
			long levelFees = repository.getATRepository().getLatestATState(levelAtAddress).getFees();

			// No trust-specific pre-charge: identical bytecode shape must cost exactly the same as
			// the long-established account-level query, and exactly the ordinary per-call step price.
			assertEquals(levelFees, trustFees);
			assertEquals((2L * ciyamAtSettings.stepsPerFunctionCall + 1L) * ciyamAtSettings.feePerStep, trustFees);
		}
	}

	/**
	 * A rating change confirmed in block H must be invisible to the H AT round (ATs run against the
	 * parent snapshot) and visible in H+1; orphaning the changing block must restore the prior reads.
	 */
	@Test
	public void testSnapshotTimingAcrossBlocksAndOrphanRestore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			// Initial snapshot population
			BlockUtils.mintBlock(repository);

			// Build the derived-SILVER rating web for alice, withholding its final subject edge
			// (dilbert -> alice) so that edge can arrive later as a real RATE_ACCOUNT transaction.
			saveSilverWebWithoutFinalSubjectEdge(repository, alice, bob, chloe, dilbert);
			repository.saveChanges();
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustStatus statusBefore = storedSubjectStatus(repository, alice.getAddress());
			assertNotEquals("fixture must leave alice below SILVER until the final edge lands",
					AccountTrustStatus.SILVER, statusBefore);

			// Looping reader: re-reads alice's trust status every block
			String atAddress = AtUtils.doDeployAT(repository, alice,
					buildLoopingTrustReaderAT(paddedAddressBytes(alice.getAddress())), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);
			assertEquals(statusBefore.getValue(), extractResult(repository, atAddress));

			// Block H: the final subject edge confirms, so block processing refreshes the snapshot...
			RateAccountTransactionData ratingData = new RateAccountTransactionData(
					TestTransaction.generateBase(dilbert), alice.getPublicKey(), AccountRatingCategory.SUBJECT, 2);
			TransactionUtils.signAndMint(repository, ratingData, dilbert);

			assertEquals(AccountTrustStatus.SILVER, storedSubjectStatus(repository, alice.getAddress()));
			// ...but the AT round in H already ran against the parent snapshot
			assertEquals("AT in block H must not see the block-H rating change",
					statusBefore.getValue(), extractResult(repository, atAddress));

			// H+1: the refreshed snapshot is now visible
			BlockUtils.mintBlock(repository);
			assertEquals(AccountTrustStatus.SILVER.getValue(), extractResult(repository, atAddress));

			// Orphan H+1 and H: the rating edge is removed and the snapshot recomputed at the prior height
			BlockUtils.orphanBlocks(repository, 2);
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			assertEquals(statusBefore, storedSubjectStatus(repository, alice.getAddress()));
			BlockUtils.mintBlock(repository);
			assertEquals("opcode must restore the prior result after orphaning",
					statusBefore.getValue(), extractResult(repository, atAddress));
		}
	}

	/** Bootstrap/rebuild parity: wiping snapshots and re-deriving from on-chain ratings must give identical reads. */
	@Test
	public void testSnapshotRebuildFromOnChainRatingsMatchesOpcode() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			BlockUtils.mintBlock(repository);
			AccountTrustTestUtils.saveDerivedSilverSubjectRatings(repository, alice, bob, chloe, dilbert);
			repository.saveChanges();
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
			assertEquals(AccountTrustStatus.SILVER, storedSubjectStatus(repository, alice.getAddress()));

			String atAddress = AtUtils.doDeployAT(repository, alice,
					buildLoopingTrustReaderAT(paddedAddressBytes(alice.getAddress())), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);
			assertEquals(AccountTrustStatus.SILVER.getValue(), extractResult(repository, atAddress));

			// Deliberately stale/absent snapshot state, then deterministic rebuild from on-chain inputs
			repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Collections.emptyList(),
					repository.getBlockRepository().getBlockchainHeight(),
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertEquals(AccountTrustStatus.SILVER, storedSubjectStatus(repository, alice.getAddress()));
			BlockUtils.mintBlock(repository);
			assertEquals("rebuilt snapshots must produce identical opcode output",
					AccountTrustStatus.SILVER.getValue(), extractResult(repository, atAddress));
		}
	}

	/** Pre-trigger calls fail exactly like pre-activation map calls; the exact trigger block succeeds. */
	@Test
	public void testInactiveBeforeTriggerActiveAtExactTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtTrustStatusHeight();
			assertTrue(triggerHeight > 3);
			int fillerBlocks = triggerHeight - 3 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			byte[] accountBytes = paddedAddressBytes(bob.getAddress());

			// First AT runs pre-trigger (at triggerHeight - 1) and must fail fatally
			String preTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotTrustReaderAT(accountBytes), FUNDING_AMOUNT).getATAccount().getAddress();

			// Second AT deploys in the pre-trigger block and first runs in the exact trigger block
			String atTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildOneShotTrustReaderAT(accountBytes), FUNDING_AMOUNT).getATAccount().getAddress();
			assertEquals(triggerHeight - 1, repository.getBlockRepository().getBlockchainHeight());
			assertTrue("pre-trigger trust query must fail like a pre-activation map call",
					repository.getATRepository().fromATAddress(preTriggerAtAddress).getHadFatalError());

			BlockUtils.mintBlock(repository);
			assertEquals(triggerHeight, repository.getBlockRepository().getBlockchainHeight());
			assertFalse(repository.getATRepository().fromATAddress(atTriggerAtAddress).getHadFatalError());
			assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), extractResult(repository, atTriggerAtAddress));
		}
	}

	/** Integrated trust-gated faucet: refuse below BRONZE; SET claim marker, read it back, only then pay. */
	@Test
	public void testTrustGatedFaucetPaysBronzeAndRefusesUnverified() throws DataException {
		final long payAmount = 2_00000000L;
		final long faucetFunding = 10_00000000L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			BlockUtils.mintBlock(repository);
			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.BRONZE));

			// BRONZE claimant: marker set, readback nonzero, payment made
			long bobBefore = bob.getConfirmedBalance(0L);
			String bronzeFaucetAddress = AtUtils.doDeployAT(repository, deployer,
					buildTrustGatedFaucetAT(paddedAddressBytes(bob.getAddress()), payAmount), faucetFunding)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals("BRONZE claimant must be paid", bobBefore + payAmount, bob.getConfirmedBalance(0L));
			assertEquals(Long.valueOf(1L),
					repository.getATRepository().getATMapValue(bronzeFaucetAddress, CLAIM_KEY_1, CLAIM_KEY_2));

			// UNVERIFIED claimant (chloe has no snapshot): refused before any map write or payment
			long chloeBefore = chloe.getConfirmedBalance(0L);
			String refusedFaucetAddress = AtUtils.doDeployAT(repository, deployer,
					buildTrustGatedFaucetAT(paddedAddressBytes(chloe.getAddress()), payAmount), faucetFunding)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals("below-BRONZE claimant must not be paid", chloeBefore, chloe.getConfirmedBalance(0L));
			assertNull("refused claim must leave no marker",
					repository.getATRepository().getATMapValue(refusedFaucetAddress, CLAIM_KEY_1, CLAIM_KEY_2));
		}
	}

	// AT builders

	private static byte[] buildOneShotTrustReaderAT(byte[] accountBytes) {
		return buildOneShotReaderAT(accountBytes, ChainFunctionCode.GET_TRUST_STATUS_FROM_ACCOUNT_IN_B);
	}

	/** Reads the chain function once for the account in B, stores the result at data address 0, then finishes. */
	private static byte[] buildOneShotReaderAT(byte[] accountBytes, ChainFunctionCode functionCode) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAccountBytes = addrCounter;
		addrCounter += 4;
		final int addrAccountBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.position(addrAccountBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(accountBytes);
		dataByteBuffer.putLong(addrAccountBytesPointer * MachineState.VALUE_SIZE, addrAccountBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrAccountBytesPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(functionCode.value, addrResult));
			codeByteBuffer.put(OpCode.FIN_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/** Re-reads the trust status of the account in B every block, storing the result at data address 0. */
	private static byte[] buildLoopingTrustReaderAT(byte[] accountBytes) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAccountBytes = addrCounter;
		addrCounter += 4;
		final int addrAccountBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.position(addrAccountBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(accountBytes);
		dataByteBuffer.putLong(addrAccountBytesPointer * MachineState.VALUE_SIZE, addrAccountBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrAccountBytesPointer));
			codeByteBuffer.put(OpCode.SET_PCS.compile());
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(
					ChainFunctionCode.GET_TRUST_STATUS_FROM_ACCOUNT_IN_B.value, addrResult));
			codeByteBuffer.put(OpCode.STP_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static final long CLAIM_KEY_1 = 77L;
	private static final long CLAIM_KEY_2 = 88L;

	/**
	 * Trust-gated faucet: refuse a claimant below BRONZE, otherwise SET a claim marker in the AT's
	 * persistent map, read it back, and only pay if the marker persisted (SET -> readback -> PAY).
	 */
	private static byte[] buildTrustGatedFaucetAT(byte[] claimantBytes, long payAmount) {
		int addrCounter = 0;
		final int addrStatusResult = addrCounter++;
		final int addrMapReadback = addrCounter++;
		final int addrClaimantBytes = addrCounter;
		addrCounter += 4;
		final int addrClaimantBytesPointer = addrCounter++;
		final int addrKey1 = addrCounter++;
		final int addrKey2 = addrCounter++;
		final int addrMarker = addrCounter++;
		final int addrBronzeThreshold = addrCounter++;
		final int addrPayAmount = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.position(addrClaimantBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(claimantBytes);
		dataByteBuffer.putLong(addrClaimantBytesPointer * MachineState.VALUE_SIZE, addrClaimantBytes);
		dataByteBuffer.putLong(addrKey1 * MachineState.VALUE_SIZE, CLAIM_KEY_1);
		dataByteBuffer.putLong(addrKey2 * MachineState.VALUE_SIZE, CLAIM_KEY_2);
		dataByteBuffer.putLong(addrMarker * MachineState.VALUE_SIZE, 1L);
		dataByteBuffer.putLong(addrBronzeThreshold * MachineState.VALUE_SIZE, AccountTrustStatus.BRONZE.getValue());
		dataByteBuffer.putLong(addrPayAmount * MachineState.VALUE_SIZE, payAmount);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelRefuse = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrClaimantBytesPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(
						ChainFunctionCode.GET_TRUST_STATUS_FROM_ACCOUNT_IN_B.value, addrStatusResult));
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrStatusResult, addrBronzeThreshold,
						OpCode.calcOffset(codeByteBuffer, labelRefuse)));

				// Claim marker: SET, then read back from our own map (zero B selects self)
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.CLEAR_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A1, addrKey1));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A2, addrKey2));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, addrMarker));
				codeByteBuffer.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(
						ChainFunctionCode.GET_MAP_VALUE_KEYS_IN_A.value, addrMapReadback));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrMapReadback,
						OpCode.calcOffset(codeByteBuffer, labelRefuse)));

				// Marker persisted: pay the claimant
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrClaimantBytesPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrPayAmount));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				labelRefuse = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	// Helpers

	private static byte[] toCreationBytes(ByteBuffer codeByteBuffer, ByteBuffer dataByteBuffer) {
		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	private static byte[] paddedAddressBytes(String address) {
		return Bytes.ensureCapacity(Base58.decode(address), 32, 0);
	}

	private static long extractResult(Repository repository, String atAddress) throws DataException {
		ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
		byte[] dataBytes = MachineState.extractDataBytes(atStateData.getStateData());
		return BitTwiddling.longFromBEBytes(dataBytes, 0);
	}

	private static AccountTrustStatus storedSubjectStatus(Repository repository, String address) throws DataException {
		List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
				.getTrustDerivationSnapshots(address);
		return snapshots.stream()
				.filter(snapshot -> snapshot.getCategory() == AccountRatingCategory.SUBJECT)
				.findFirst()
				.map(AccountTrustSnapshotData::getMappedTrustStatus)
				.orElse(AccountTrustStatus.UNVERIFIED);
	}

	/**
	 * Replicates {@link AccountTrustTestUtils#saveDerivedSilverSubjectRatings} but withholds the final
	 * subject edge (player/dilbert -> subject/alice), so a later real RATE_ACCOUNT transaction can
	 * complete the web inside a block.
	 */
	private static void saveSilverWebWithoutFinalSubjectEdge(Repository repository, PrivateKeyAccount subject,
			PrivateKeyAccount manager, PrivateKeyAccount trainer, PrivateKeyAccount player) throws DataException {
		PrivateKeyAccount managerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount trainerPeer = Common.generateRandomSeedAccount(repository);
		PrivateKeyAccount playerPeer = Common.generateRandomSeedAccount(repository);

		AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, subject, Arrays.asList(manager, managerPeer));

		AccountTrustTestUtils.saveAccountRating(repository, manager, trainer, AccountRatingCategory.TRAINER, 4);
		AccountTrustTestUtils.saveAccountRating(repository, managerPeer, trainer, AccountRatingCategory.TRAINER, 4);
		AccountTrustTestUtils.saveAccountRating(repository, manager, trainerPeer, AccountRatingCategory.TRAINER, 4);
		AccountTrustTestUtils.saveAccountRating(repository, managerPeer, trainerPeer, AccountRatingCategory.TRAINER, 4);

		AccountTrustTestUtils.saveAccountRating(repository, trainer, player, AccountRatingCategory.PLAYER, 2);
		AccountTrustTestUtils.saveAccountRating(repository, trainerPeer, player, AccountRatingCategory.PLAYER, 2);
		AccountTrustTestUtils.saveAccountRating(repository, trainer, playerPeer, AccountRatingCategory.PLAYER, 1);
		AccountTrustTestUtils.saveAccountRating(repository, trainerPeer, playerPeer, AccountRatingCategory.PLAYER, 1);

		AccountTrustTestUtils.saveAccountRating(repository, playerPeer, subject, AccountRatingCategory.SUBJECT, 2);
		// withheld: player -> subject SUBJECT 2 (arrives later as a real transaction)
	}
}
