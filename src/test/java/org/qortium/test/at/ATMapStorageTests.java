package org.qortium.test.at;

import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.account.Account;
import org.qortium.at.ATMapExecutionContext;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.data.at.ATStateData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ATMapStorageTests extends Common {

	private static final long KEY_1 = -7L;
	private static final long KEY_2 = 42L;
	private static final long VALUE = 123456789L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSameBlockCrossAtReadPersistsAndOrphans() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction writerDeploy = AtUtils.doDeployAT(repository, deployer, buildWriterAT(), 1_00000000L);
			String writerAddress = writerDeploy.getATAccount().getAddress();

			DeployAtTransaction readerDeploy = AtUtils.doDeployAT(repository, deployer,
					buildReaderAT(writerAddress), 1_00000000L);
			String readerAddress = readerDeploy.getATAccount().getAddress();

			// The writer's first round stopped before SET while the reader was deployed. In this
			// block the older writer publishes first and the reader must see the shared overlay.
			BlockUtils.mintBlock(repository);

			assertEquals(Long.valueOf(VALUE), repository.getATRepository().getATMapValue(writerAddress, KEY_1, KEY_2));
			ATStateData readerState = repository.getATRepository().getLatestATState(readerAddress);
			assertEquals(VALUE, ByteBuffer.wrap(readerState.getStateData()).getLong(MachineState.HEADER_LENGTH + 7 * Long.BYTES));

			ATStateData writerState = repository.getATRepository().getLatestATState(writerAddress);
			assertArrayEquals(recomputeSingleEntryRoot(KEY_1, KEY_2, VALUE), writerState.getMapRoot());

			BlockUtils.orphanLastBlock(repository);
			assertNull(repository.getATRepository().getATMapValue(writerAddress, KEY_1, KEY_2));
		}
	}

	@Test
	public void testOverlayRollsBackRoundAndCollapsesFinalChanges() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), 1_00000000L)
					.getATAccount().getAddress();

			ATMapExecutionContext context = new ATMapExecutionContext(repository);
			context.beginRound(atAddress);
			context.setValue(atAddress, 1L, 2L, 3L, 1);
			assertEquals(3L, context.getValue(atAddress, 1L, 2L));
			context.rollbackRound();
			assertEquals(0L, context.getValue(atAddress, 1L, 2L));
			assertEquals(0, context.getChanges().size());

			context.beginRound(atAddress);
			context.setValue(atAddress, 1L, 2L, 3L, 1);
			// Cap rejection, overwrite and delete all retain ordinary semantics in one round.
			context.setValue(atAddress, 9L, 9L, 10L, 1);
			context.setValue(atAddress, 1L, 2L, 4L, 1);
			context.setValue(atAddress, 1L, 2L, 0L, 1);
			context.setValue(atAddress, 1L, 2L, 5L, 1);
			context.commitRound();

			List<ATMapChangeData> changes = context.getChanges();
			assertEquals(1, changes.size());
			assertNull(changes.get(0).getPreviousValue());
			assertEquals(Long.valueOf(5L), changes.get(0).getNewValue());
			assertNull("accepted overlay changes must not write before block processing",
					repository.getATRepository().getATMapValue(atAddress, 1L, 2L));
		}
	}

	@Test
	public void testMintedCandidateKeepsMapWritesOutOfRepositoryUntilProcessing() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildImmediateWriterAT(), 1_00000000L)
					.getATAccount().getAddress();
			PrivateKeyAccount minter = Common.getTestAccount(repository, "alice-reward-share");

			Block candidate = Block.mint(repository, repository.getBlockRepository().getLastBlock(), minter);
			assertEquals(1, candidate.getATStates().size());
			assertArrayEquals(recomputeSingleEntryRoot(KEY_1, KEY_2, VALUE),
					candidate.getATStates().get(0).getMapRoot());
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
		}
	}

	@Test
	public void testCanonicalRootUsesSignedKeyOrdering() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), 1_00000000L)
					.getATAccount().getAddress();
			ATMapExecutionContext context = new ATMapExecutionContext(repository);
			context.beginRound(atAddress);
			context.setValue(atAddress, 5L, -2L, 50L, 10);
			context.setValue(atAddress, -1L, 9L, 10L, 10);
			context.setValue(atAddress, 5L, -3L, 40L, 10);

			ByteBuffer canonical = ByteBuffer.allocate(9 * Long.BYTES);
			canonical.putLong(-1L).putLong(9L).putLong(10L);
			canonical.putLong(5L).putLong(-3L).putLong(40L);
			canonical.putLong(5L).putLong(-2L).putLong(50L);
			assertArrayEquals(Crypto.digest(canonical.array()), context.getMapRoot(atAddress));
			context.rollbackRound();
		}
	}

	@Test
	public void testMapEntryCapStartsAtFiveHundredAndCannotDecrease() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight() + 10;
			assertEquals(BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT,
					BlockChain.getInstance().getMaxMapEntriesPerAt(repository, height));
			assertEquals(true, ChainParameter.MAX_MAP_ENTRIES_PER_AT.isValidValue(repository, height,
					ChainParameter.MAX_MAP_ENTRIES_PER_AT.encodeIntValue(500)));
			assertEquals(false, ChainParameter.MAX_MAP_ENTRIES_PER_AT.isValidValue(repository, height,
					ChainParameter.MAX_MAP_ENTRIES_PER_AT.encodeIntValue(499)));
		}
	}

	@Test
	public void testCorePricesCreatesAndNonGrowingWritesDifferently() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildMutationPricingAT(), 1_00000000L)
					.getATAccount().getAddress();

			BlockUtils.mintBlock(repository);
			assertEquals(Long.valueOf(VALUE), repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(131)), repository.getATRepository().getLatestATState(atAddress).getFees());

			BlockUtils.mintBlock(repository);
			assertEquals(Long.valueOf(VALUE), repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(11)), repository.getATRepository().getLatestATState(atAddress).getFees());

			BlockUtils.mintBlock(repository);
			assertEquals(Long.valueOf(VALUE + 1), repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(21)), repository.getATRepository().getLatestATState(atAddress).getFees());

			BlockUtils.mintBlock(repository);
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(21)), repository.getATRepository().getLatestATState(atAddress).getFees());
		}
	}

	@Test
	public void testInsufficientPremiumBudgetLeavesMapUntouchedUntilNextRound() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildBudgetBlockedAT(), 1_00000000L)
					.getATAccount().getAddress();

			BlockUtils.mintBlock(repository);
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(401)), repository.getATRepository().getLatestATState(atAddress).getFees());

			BlockUtils.mintBlock(repository);
			assertEquals(Long.valueOf(VALUE), repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(101)), repository.getATRepository().getLatestATState(atAddress).getFees());
		}
	}

	@Test
	public void testMalformedSetSignaturePaysOrdinaryCostAndCannotWrite() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildMalformedSetAT(), 1_00000000L)
					.getATAccount().getAddress();

			BlockUtils.mintBlock(repository);
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(Long.valueOf(feeForSteps(40)), repository.getATRepository().getLatestATState(atAddress).getFees());
			assertEquals(true, repository.getATRepository().fromATAddress(atAddress).getHadFatalError());
		}
	}

	@Test
	public void testFullCapRejectsCreateAtOrdinaryCost() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildImmediateWriterAT(), 1_00000000L)
					.getATAccount().getAddress();
			List<ATMapChangeData> initialEntries = new java.util.ArrayList<>();
			for (int i = 0; i < BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT; ++i)
				initialEntries.add(new ATMapChangeData(atAddress, i, 0L, null, (long) i + 1));
			repository.getATRepository().saveATMapChanges(0, initialEntries);
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			assertEquals(BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT,
					repository.getATRepository().getATMapEntryCount(atAddress));
			assertEquals(Long.valueOf(feeForSteps(41)), repository.getATRepository().getLatestATState(atAddress).getFees());
		}
	}

	private static byte[] buildWriterAT() {
		ByteBuffer data = ByteBuffer.allocate(3 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE);

		ByteBuffer code = ByteBuffer.allocate(256);
		compileTwice(code, () -> {
			code.put(OpCode.SLP_IMD.compile());
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A1, 0));
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A2, 1));
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, 2));
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static byte[] buildImmediateWriterAT() {
		ByteBuffer data = ByteBuffer.allocate(3 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE);
		ByteBuffer code = ByteBuffer.allocate(256);
		compileTwice(code, () -> {
			putMapRegisters(code);
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static byte[] buildMutationPricingAT() {
		ByteBuffer data = ByteBuffer.allocate(5 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE).putLong(VALUE + 1);
		ByteBuffer code = ByteBuffer.allocate(256);
		compileTwice(code, () -> {
			putMapRegisters(code);
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.SLP_IMD.compile());
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.SLP_IMD.compile());
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, 3));
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.SLP_IMD.compile());
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, 4));
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static byte[] buildBudgetBlockedAT() {
		ByteBuffer data = ByteBuffer.allocate(3 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE);
		ByteBuffer code = ByteBuffer.allocate(512);
		compileTwice(code, () -> {
			putMapRegisters(code);
			for (int i = 0; i < 371; ++i)
				code.put(OpCode.NOP.compile());
			code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static byte[] buildMalformedSetAT() {
		ByteBuffer data = ByteBuffer.allocate(4 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE).putLong(0L);
		ByteBuffer code = ByteBuffer.allocate(256);
		compileTwice(code, () -> {
			putMapRegisters(code);
			code.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value, 3));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static void putMapRegisters(ByteBuffer code) throws CompilationException {
		code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A1, 0));
		code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A2, 1));
		code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, 2));
	}

	private static byte[] buildReaderAT(String writerAddress) {
		ByteBuffer data = ByteBuffer.allocate(8 * Long.BYTES);
		data.put(Base58.decode(writerAddress));
		data.put(new byte[32 - Account.ADDRESS_LENGTH]);
		data.putLong(0L); // cell 4 points SET_B_IND at the four address cells
		data.putLong(KEY_1).putLong(KEY_2).putLong(0L);

		ByteBuffer code = ByteBuffer.allocate(256);
		compileTwice(code, () -> {
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, 4));
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A1, 5));
			code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A2, 6));
			code.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_MAP_VALUE_KEYS_IN_A.value, 7));
			code.put(OpCode.FIN_IMD.compile());
		});
		return creationBytes(code, data);
	}

	private static byte[] creationBytes(ByteBuffer code, ByteBuffer data) {
		code.flip();
		byte[] codeBytes = new byte[code.remaining()];
		code.get(codeBytes);
		return MachineState.toCreationBytes((short) 2, codeBytes, data.array(), (short) 0, (short) 0, 0L);
	}

	private static byte[] recomputeSingleEntryRoot(long key1, long key2, long value) {
		return Crypto.digest(ByteBuffer.allocate(3 * Long.BYTES).putLong(key1).putLong(key2).putLong(value).array());
	}

	private static long feeForSteps(int steps) {
		return steps * BlockChain.getInstance().getCiyamAtSettings().feePerStep;
	}

	private static void compileTwice(ByteBuffer code, Compilable compilable) {
		for (int pass = 0; pass < 2; ++pass) {
			code.clear();
			try {
				compilable.compile();
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile map AT", e);
			}
		}
	}

	@FunctionalInterface
	private interface Compilable {
		void compile() throws CompilationException;
	}
}
