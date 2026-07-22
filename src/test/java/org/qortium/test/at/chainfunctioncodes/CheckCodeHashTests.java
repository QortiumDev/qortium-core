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
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATStateData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Bytecode-level tests for {@link ChainFunctionCode#CHECK_CODE_HASH_OF_AT_IN_B} (0x0524).
 *
 * <p>B uses the cross-AT read convention (all-zero means the calling AT itself, otherwise an AT
 * address); A1-A4 hold the expected 32-byte code hash in the same register packing SHA256-type
 * results use. The comparison target is the code hash stored at deploy time. The function never
 * errors: a non-AT or unknown address simply yields 0.</p>
 */
public class CheckCodeHashTests extends Common {

	private static final long FUNDING_AMOUNT = 1_00000000L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testMatchingCodeHashReturnsOne() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			String targetAtAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(),
					FUNDING_AMOUNT).getATAccount().getAddress();

			ATData targetAtData = repository.getATRepository().fromATAddress(targetAtAddress);
			byte[] storedCodeHash = targetAtData.getCodeHash();
			// The stored hash is SHA-256 of the code bytes - the same bytes /at/byfunction matches on
			assertArrayEquals(Crypto.digest(targetAtData.getCodeBytes()), storedCodeHash);

			String checkerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(paddedAddressBytes(targetAtAddress), storedCodeHash), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(1L, extractResult(repository, checkerAtAddress));
		}
	}

	@Test
	public void testNonMatchingCodeHashReturnsZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			String targetAtAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(),
					FUNDING_AMOUNT).getATAccount().getAddress();

			byte[] wrongCodeHash = repository.getATRepository().fromATAddress(targetAtAddress).getCodeHash().clone();
			wrongCodeHash[0] ^= 0x01;

			String checkerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(paddedAddressBytes(targetAtAddress), wrongCodeHash), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(0L, extractResult(repository, checkerAtAddress));
		}
	}

	@Test
	public void testNonAtAddressReturnsZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			byte[] anyHash = new byte[32];
			anyHash[31] = 1;

			// An ordinary (non-AT) account address must yield 0, never an error
			String checkerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(paddedAddressBytes(dilbert.getAddress()), anyHash), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			BlockUtils.mintBlock(repository);

			assertEquals(0L, extractResult(repository, checkerAtAddress));
			assertFalse(repository.getATRepository().fromATAddress(checkerAtAddress).getHadFatalError());
		}
	}

	/** An AT can verify its own code hash: all-zero B selects the calling AT itself. */
	@Test
	public void testSelfCheckReturnsOne() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			// B stays zero (self); the expected hash is the hash of this same code, computable pre-deploy
			// because the stored code hash covers code bytes only, not the data segment.
			byte[] selfCheckerCode = buildCodeHashCheckerCodeBytes(false);
			byte[] selfCodeHash = Crypto.digest(selfCheckerCode);

			String checkerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(new byte[32], selfCodeHash, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();

			assertArrayEquals(selfCodeHash,
					repository.getATRepository().fromATAddress(checkerAtAddress).getCodeHash());

			BlockUtils.mintBlock(repository);
			assertEquals(1L, extractResult(repository, checkerAtAddress));
		}
	}

	/** Pre-trigger calls fail exactly like pre-activation map calls; the exact trigger block succeeds. */
	@Test
	public void testInactiveBeforeTriggerActiveAtExactTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtCodeHashCheckHeight();
			assertTrue(triggerHeight > 3);
			int fillerBlocks = triggerHeight - 3 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			byte[] selfCheckerCode = buildCodeHashCheckerCodeBytes(false);
			byte[] selfCodeHash = Crypto.digest(selfCheckerCode);

			String preTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(new byte[32], selfCodeHash, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			String atTriggerAtAddress = AtUtils.doDeployAT(repository, deployer,
					buildCodeHashCheckerAT(new byte[32], selfCodeHash, false), FUNDING_AMOUNT)
					.getATAccount().getAddress();
			assertEquals(triggerHeight - 1, repository.getBlockRepository().getBlockchainHeight());
			assertTrue("pre-trigger code-hash check must fail like a pre-activation map call",
					repository.getATRepository().fromATAddress(preTriggerAtAddress).getHadFatalError());

			BlockUtils.mintBlock(repository);
			assertEquals(triggerHeight, repository.getBlockRepository().getBlockchainHeight());
			assertFalse(repository.getATRepository().fromATAddress(atTriggerAtAddress).getHadFatalError());
			assertEquals(1L, extractResult(repository, atTriggerAtAddress));
		}
	}

	// AT builders

	private static byte[] buildCodeHashCheckerAT(byte[] targetBytes, byte[] expectedCodeHash) {
		return buildCodeHashCheckerAT(targetBytes, expectedCodeHash, true);
	}

	/**
	 * Loads the expected code hash into A, the target into B (skipped when {@code setB} is false so
	 * B remains all-zero, selecting the calling AT itself), then stores the comparison result at
	 * data address 0.
	 */
	private static byte[] buildCodeHashCheckerAT(byte[] targetBytes, byte[] expectedCodeHash, boolean setB) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrTargetBytes = addrCounter;
		addrCounter += 4;
		final int addrExpectedHash = addrCounter;
		addrCounter += 4;
		final int addrTargetBytesPointer = addrCounter++;
		final int addrExpectedHashPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.position(addrTargetBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(targetBytes);
		dataByteBuffer.position(addrExpectedHash * MachineState.VALUE_SIZE);
		dataByteBuffer.put(expectedCodeHash);
		dataByteBuffer.putLong(addrTargetBytesPointer * MachineState.VALUE_SIZE, addrTargetBytes);
		dataByteBuffer.putLong(addrExpectedHashPointer * MachineState.VALUE_SIZE, addrExpectedHash);

		byte[] codeBytes = buildCodeHashCheckerCodeBytes(setB);

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(), (short) 0, (short) 0, 0L);
	}

	/** Code segment only, fixed layout, so a self-checking AT can hash its own code before deploy. */
	private static byte[] buildCodeHashCheckerCodeBytes(boolean setB) {
		// These data addresses match buildCodeHashCheckerAT's layout
		final int addrResult = 0;
		final int addrTargetBytesPointer = 9;
		final int addrExpectedHashPointer = 10;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A_IND, addrExpectedHashPointer));
			if (setB)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrTargetBytesPointer));
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(
					ChainFunctionCode.CHECK_CODE_HASH_OF_AT_IN_B.value, addrResult));
			codeByteBuffer.put(OpCode.FIN_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);
		return codeBytes;
	}

	// Helpers

	private static byte[] paddedAddressBytes(String address) {
		return Bytes.ensureCapacity(Base58.decode(address), 32, 0);
	}

	private static long extractResult(Repository repository, String atAddress) throws DataException {
		ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
		byte[] dataBytes = MachineState.extractDataBytes(atStateData.getStateData());
		return BitTwiddling.longFromBEBytes(dataBytes, 0);
	}
}
