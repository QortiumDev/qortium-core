package org.qortium.test.at;

import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Boundary coverage for {@code atSweepAssetsOnFinishHeight}. An AT that finishes while holding a
 * third asset (neither its configured working asset nor the native fee balance) must, from the
 * trigger height on, sweep that asset back to its creator instead of stranding it forever, while
 * pre-trigger behaviour is unchanged (the third asset stays trapped in the finished AT).
 *
 * <p>The test chain sets the trigger at height 8, so a finish before height 8 exercises the legacy
 * path and a finish at/after height 8 exercises the sweep — the Previewnet 69,999 -> 70,000 analogue.
 */
public class ATSweepAssetsOnFinishTests extends Common {

	private static final long X_PREFUND = 10L * Amounts.MULTIPLIER;
	private static final long Y_TRANSFER = 7L * Amounts.MULTIPLIER;
	private static final long ASSET_SUPPLY = 1000L * Amounts.MULTIPLIER;
	private static final long NATIVE_FEE_RESERVE = 5L * Amounts.MULTIPLIER;

	@Before
	public void beforeTest() throws DataException {
		// useDefaultSettings first so the fixed NTP offset is installed; the per-test useSettings switch
		// to the sweep-boundary chain keeps that synced clock (useSettings alone does not set NTP).
		Common.useDefaultSettings();
	}

	/** Before the trigger, a finished AT strands a third asset it holds; the creator does not get it back. */
	@Test
	public void testThirdAssetIsStrandedBeforeTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-sweep-assets.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtSweepAssetsOnFinishHeight();
			assertTrue("sweep trigger must leave room for pre-trigger activity", triggerHeight > 4);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long workingAssetId = AssetUtils.issueAsset(repository, "alice", "SWEEP-X", ASSET_SUPPLY, true);
			long thirdAssetId = AssetUtils.issueAsset(repository, "alice", "SWEEP-Y", ASSET_SUPPLY, true);

			DeployAtTransaction deploy = AtUtils.doDeployAT(repository, alice, buildWaitThenFinishAT(), X_PREFUND,
					workingAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deploy.getATAccount();
			String atAddress = atAccount.getAddress();

			long aliceThirdBefore = alice.getConfirmedBalance(thirdAssetId);

			// Fund the AT with the third asset; the transfer is also the transaction that wakes the AT.
			transferAssetToAddress(repository, alice, atAddress, thirdAssetId, Y_TRANSFER);
			assertTrue("AT must finish before the sweep trigger height",
					mintUntilFinished(repository, atAddress));
			assertTrue("finish must occur before the sweep trigger height",
					repository.getBlockRepository().getBlockchainHeight() < triggerHeight);

			assertEquals("third asset must remain stranded in the finished AT before the trigger",
					Y_TRANSFER, atAccount.getConfirmedBalance(thirdAssetId));
			assertEquals("creator must NOT get the third asset back before the trigger",
					aliceThirdBefore - Y_TRANSFER, alice.getConfirmedBalance(thirdAssetId));
			// Configured working asset is still refunded, as before.
			assertEquals("configured working asset must be refunded to creator as before",
					0L, atAccount.getConfirmedBalance(workingAssetId));
		}
	}

	/** From the trigger, a finished AT sweeps a third asset it holds back to its creator, leaving nothing behind. */
	@Test
	public void testThirdAssetIsSweptToCreatorAtOrAfterTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-sweep-assets.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtSweepAssetsOnFinishHeight();

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long workingAssetId = AssetUtils.issueAsset(repository, "alice", "SWEEP-X", ASSET_SUPPLY, true);
			long thirdAssetId = AssetUtils.issueAsset(repository, "alice", "SWEEP-Y", ASSET_SUPPLY, true);

			// Advance so the AT's finish lands at/after the trigger height.
			int fillerBlocks = triggerHeight - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			DeployAtTransaction deploy = AtUtils.doDeployAT(repository, alice, buildWaitThenFinishAT(), X_PREFUND,
					workingAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deploy.getATAccount();
			String atAddress = atAccount.getAddress();

			long aliceThirdBefore = alice.getConfirmedBalance(thirdAssetId);

			transferAssetToAddress(repository, alice, atAddress, thirdAssetId, Y_TRANSFER);
			assertTrue("AT must finish at or after the sweep trigger height",
					mintUntilFinished(repository, atAddress));
			assertTrue("finish must occur at or after the sweep trigger height",
					repository.getBlockRepository().getBlockchainHeight() >= triggerHeight);

			assertEquals("third asset must be swept out of the finished AT from the trigger",
					0L, atAccount.getConfirmedBalance(thirdAssetId));
			assertEquals("creator must receive the whole third-asset balance back",
					aliceThirdBefore, alice.getConfirmedBalance(thirdAssetId));
			assertEquals("configured working asset must still be refunded to creator",
					0L, atAccount.getConfirmedBalance(workingAssetId));
		}
	}

	/**
	 * An AT that waits for a transaction addressed to it and then finishes. Waiting (rather than
	 * finishing on the very first round) lets the triggering block's third-asset transfer be credited
	 * before {@code onFinished} runs, so the finish deterministically occurs in a block we control the
	 * height of, with the third asset already held by the AT.
	 */
	private static byte[] buildWaitThenFinishAT() {
		final int addrLastTxTimestamp = 0;
		final int addrNoTransaction = 1;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(2 * MachineState.VALUE_SIZE);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(256);
		Integer labelFinish = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				// If a transaction was found (addrNoTransaction == 0), branch to the finish; else fall
				// through and sleep until the next block, retrying.
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelFinish)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelFinish = codeByteBuffer.position();
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	/** Mints blocks until the AT reports finished, bounded so a stuck AT fails the test instead of looping. */
	private static boolean mintUntilFinished(Repository repository, String atAddress) throws DataException {
		for (int i = 0; i < 6; ++i) {
			if (repository.getATRepository().fromATAddress(atAddress).getIsFinished())
				return true;

			BlockUtils.mintBlock(repository);
		}

		return repository.getATRepository().fromATAddress(atAddress).getIsFinished();
	}

	/** Imports a TRANSFER_ASSET to an arbitrary address (the AT) and mints the block it confirms in. */
	private static void transferAssetToAddress(Repository repository, PrivateKeyAccount sender, String recipient,
			long assetId, long amount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
				sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);

		assertEquals(ValidationResult.OK, TransactionUtils.signAndImport(repository, transactionData, sender));
		BlockUtils.mintBlock(repository);
	}
}
