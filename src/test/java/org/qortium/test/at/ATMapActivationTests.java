package org.qortium.test.at;

import com.google.common.primitives.Bytes;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.at.ATMapExecutionContext;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATStateData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.transaction.DeployAtTransaction;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ATMapActivationTests extends Common {
	private static final long KEY_1 = 11L;
	private static final long KEY_2 = 22L;
	private static final long VALUE = 33L;

	@Test
	public void testMapRootAndStateHashActivateAtExactHeight() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			int triggerHeight = (int) BlockChain.getInstance().getAtMapStorageHeight();
			assertTrue(triggerHeight > 2);
			int fillerBlocks = triggerHeight - 2 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deploy = AtUtils.doDeployAT(repository, deployer, buildMapWriterAT(), 1_00000000L);
			assertEquals(triggerHeight - 1, repository.getBlockRepository().getBlockchainHeight());

			String atAddress = deploy.getATAccount().getAddress();
			ATStateData preActivation = repository.getATRepository().getLatestATState(atAddress);
			assertNull(preActivation.getMapRoot());
			assertArrayEquals(Crypto.digest(preActivation.getStateData()), preActivation.getStateHash());

			BlockUtils.mintBlock(repository);
			assertEquals(triggerHeight, repository.getBlockRepository().getBlockchainHeight());

			ATStateData activated = repository.getATRepository().getLatestATState(atAddress);
			assertEquals(Long.valueOf(VALUE), repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			byte[] expectedRoot = Crypto.digest(ByteBuffer.allocate(3 * Long.BYTES)
					.putLong(KEY_1).putLong(KEY_2).putLong(VALUE).array());
			assertArrayEquals(expectedRoot, activated.getMapRoot());
			assertArrayEquals(Crypto.digest(Bytes.concat(activated.getStateData(), activated.getMapRoot())),
					activated.getStateHash());
		}
	}

	@Test
	public void testMapFunctionRejectsBeforeActivation() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();
			int triggerHeight = (int) BlockChain.getInstance().getAtMapStorageHeight();
			int fillerBlocks = triggerHeight - 3 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, buildMapWriterAT(), 1_00000000L)
					.getATAccount().getAddress();

			BlockUtils.mintBlock(repository);
			assertEquals(triggerHeight - 1, repository.getBlockRepository().getBlockchainHeight());
			assertTrue(repository.getATRepository().fromATAddress(atAddress).getHadFatalError());
			assertNull(repository.getATRepository().getATMapValue(atAddress, KEY_1, KEY_2));
			ATStateData rejected = repository.getATRepository().getLatestATState(atAddress);
			assertNull(rejected.getMapRoot());
			assertArrayEquals(Crypto.digest(rejected.getStateData()), rejected.getStateHash());
		}
	}

	@Test
	public void testDeploymentAtActivationStoresEmptyRoot() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");

		try (Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();
			int triggerHeight = (int) BlockChain.getInstance().getAtMapStorageHeight();
			int fillerBlocks = triggerHeight - 1 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), 1_00000000L)
					.getATAccount().getAddress();
			assertEquals(triggerHeight, repository.getBlockRepository().getBlockchainHeight());
			ATStateData initial = repository.getATRepository().getLatestATState(atAddress);
			assertArrayEquals(ATMapExecutionContext.emptyMapRoot(), initial.getMapRoot());
			assertArrayEquals(Crypto.digest(Bytes.concat(initial.getStateData(), initial.getMapRoot())),
					initial.getStateHash());
		}
	}

	private static byte[] buildMapWriterAT() {
		ByteBuffer data = ByteBuffer.allocate(3 * Long.BYTES);
		data.putLong(KEY_1).putLong(KEY_2).putLong(VALUE);
		ByteBuffer code = ByteBuffer.allocate(128);
		for (int pass = 0; pass < 2; ++pass) {
			code.clear();
			try {
				code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A1, 0));
				code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A2, 1));
				code.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_A4, 2));
				code.put(OpCode.EXT_FUN.compile(ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value));
				code.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile map activation AT", e);
			}
		}
		code.flip();
		byte[] codeBytes = new byte[code.remaining()];
		code.get(codeBytes);
		return MachineState.toCreationBytes((short) 2, codeBytes, data.array(), (short) 0, (short) 0, 0L);
	}
}
