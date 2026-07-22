package org.qortium.test.at.chainfunctioncodes;

import com.google.common.primitives.Bytes;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.BlockChain;
import org.qortium.data.at.ATStateData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bytecode-level tests for {@link ChainFunctionCode#GET_BALANCE_FROM_ACCOUNT_IN_B} (0x0523).
 *
 * <p>The opcode reads live repository state, which during block execution is deterministically the
 * parent block's state: every AT in a block runs (in pinned oldest-AT-first order) before ANY of
 * that block's transactions - including AT-generated payments - are processed. A payment included
 * in the same block therefore only becomes visible to balance reads from the following block.</p>
 */
public class GetAccountBalanceTests extends Common {

	private static final Random RANDOM = new Random();
	private static final long FUNDING_AMOUNT = 1_00000000L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testNativeBalanceFromAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(paddedAddressBytes(dilbert.getAddress()), Asset.NATIVE, false),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(dilbert.getConfirmedBalance(Asset.NATIVE), extractResult(repository, atAddress));
		}
	}

	@Test
	public void testNativeBalanceFromPublicKey() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(dilbert.getPublicKey(), Asset.NATIVE, false),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(dilbert.getConfirmedBalance(Asset.NATIVE), extractResult(repository, atAddress));
		}
	}

	@Test
	public void testIssuedAssetBalance() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			long assetId = AssetUtils.issueAsset(repository, "bob", "balance-query-test", 5000_00000000L, true);
			long bobAssetBalance = bob.getConfirmedBalance(assetId);
			assertTrue(bobAssetBalance > 0);

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(paddedAddressBytes(bob.getAddress()), assetId, false),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(bobAssetBalance, extractResult(repository, atAddress));
		}
	}

	@Test
	public void testUnknownAccountBalanceIsZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			byte[] accountBytes = new byte[32];
			RANDOM.nextBytes(accountBytes);

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(accountBytes, Asset.NATIVE, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(0L, extractResult(repository, atAddress));
		}
	}

	@Test
	public void testUnknownAssetIdIsZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(paddedAddressBytes(dilbert.getAddress()), 987654L, false),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(0L, extractResult(repository, atAddress));
		}
	}

	@Test
	public void testNegativeAssetIdIsZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			String atAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(paddedAddressBytes(dilbert.getAddress()), -1L, false),
					FUNDING_AMOUNT).getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(0L, extractResult(repository, atAddress));
		}
	}

	/**
	 * Same-block semantics: a reader AT sees the parent block's balance even when an earlier AT in
	 * the same block pays the watched account, because all ATs run before any of the block's
	 * transactions (including AT-generated payments) are processed. The payment becomes visible in
	 * the next block, and orphaning restores the prior reads.
	 */
	@Test
	public void testSameBlockAtPaymentInvisibleUntilNextBlockAndOrphanRestores() throws DataException {
		final long payAmount = 3_00000000L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			long chloeBefore = chloe.getConfirmedBalance(Asset.NATIVE);

			// Deploy both ATs in the SAME block, with the payer strictly older so it executes first
			long payerTimestamp = TransactionUtils.nextTimestamp(repository);
			importDeploy(repository, alice, buildImmediatePayerAT(paddedAddressBytes(chloe.getAddress()), payAmount),
					10_00000000L, payerTimestamp);
			DeployAtTransaction readerDeploy = importDeploy(repository, bob,
					buildBalanceReaderAT(paddedAddressBytes(chloe.getAddress()), Asset.NATIVE, true),
					FUNDING_AMOUNT, payerTimestamp + 1);
			String readerAddress = readerDeploy.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			// Block H: payer pays chloe, reader (younger, same block) still reads the parent state
			BlockUtils.mintBlock(repository);
			assertEquals("same-block AT payment must not be visible to the same-block read",
					chloeBefore, extractResult(repository, readerAddress));
			assertEquals("payment must have been processed by the end of block H",
					chloeBefore + payAmount, chloe.getConfirmedBalance(Asset.NATIVE));

			// Block H+1: the payment is now visible
			BlockUtils.mintBlock(repository);
			assertEquals(chloeBefore + payAmount, extractResult(repository, readerAddress));

			// Orphaning H+1 restores the prior read
			BlockUtils.orphanLastBlock(repository);
			assertEquals("orphan must restore the prior balance read",
					chloeBefore, extractResult(repository, readerAddress));
		}
	}

	/** Pre-trigger calls fail exactly like pre-activation map calls; the exact trigger block succeeds. */
	@Test
	public void testInactiveBeforeTriggerActiveAtExactTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtBalanceQueryHeight();
			assertTrue(triggerHeight > 3);
			int fillerBlocks = triggerHeight - 3 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			byte[] accountBytes = paddedAddressBytes(dilbert.getAddress());

			String preTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(accountBytes, Asset.NATIVE, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			String atTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildBalanceReaderAT(accountBytes, Asset.NATIVE, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			assertEquals(triggerHeight - 1, repository.getBlockRepository().getBlockchainHeight());
			assertTrue("pre-trigger balance query must fail like a pre-activation map call",
					repository.getATRepository().fromATAddress(preTriggerAtAddress).getHadFatalError());

			BlockUtils.mintBlock(repository);
			assertEquals(triggerHeight, repository.getBlockRepository().getBlockchainHeight());
			assertFalse(repository.getATRepository().fromATAddress(atTriggerAtAddress).getHadFatalError());
			assertEquals(dilbert.getConfirmedBalance(Asset.NATIVE), extractResult(repository, atTriggerAtAddress));
		}
	}

	// AT builders

	/**
	 * Reads the balance of {@code assetId} for the account in B into data address 0.
	 * One-shot form finishes immediately; looping form re-reads every block.
	 */
	private static byte[] buildBalanceReaderAT(byte[] accountBytes, long assetId, boolean looping) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrAccountBytes = addrCounter;
		addrCounter += 4;
		final int addrAccountBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);
		dataByteBuffer.position(addrAccountBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(accountBytes);
		dataByteBuffer.putLong(addrAccountBytesPointer * MachineState.VALUE_SIZE, addrAccountBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrAccountBytesPointer));
			if (looping) {
				codeByteBuffer.put(OpCode.SET_PCS.compile());
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(
						ChainFunctionCode.GET_BALANCE_FROM_ACCOUNT_IN_B.value, addrResult, addrAssetId));
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} else {
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(
						ChainFunctionCode.GET_BALANCE_FROM_ACCOUNT_IN_B.value, addrResult, addrAssetId));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			}
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/** Pays a fixed native amount to the recipient on its first (and only) execution. */
	private static byte[] buildImmediatePayerAT(byte[] recipientBytes, long payAmount) {
		int addrCounter = 0;
		final int addrPayAmount = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrPayAmount * MachineState.VALUE_SIZE, payAmount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(recipientBytes);
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrPayAmount));
			codeByteBuffer.put(OpCode.FIN_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	// Helpers

	/** Imports (but does not mint) a DEPLOY_AT with an explicit timestamp, so same-block ATs have a pinned age order. */
	private static DeployAtTransaction importDeploy(Repository repository, PrivateKeyAccount deployer,
			byte[] creationBytes, long fundingAmount, long timestamp) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
				deployer.getPublicKey(), null, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, "Test AT",
				"Test AT", "Test", "TEST", creationBytes, fundingAmount, Asset.NATIVE, 0L);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());

		assertEquals(ValidationResult.OK,
				TransactionUtils.signAndImport(repository, deployAtTransactionData, deployer));

		return deployAtTransaction;
	}

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
}
