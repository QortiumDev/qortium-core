package org.qortium.test.at;

import org.ciyam.at.MachineState;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.data.at.ATMapEntryData;
import org.qortium.data.at.ATStateData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.DeployAtTransaction;

import java.util.List;

import static org.junit.Assert.*;

public class AtRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGetATStateAtHeightWithData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			Integer testHeight = 8;
			ATStateData atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetATStateAtHeightWithoutData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			int maxHeight = 8;
			Integer testHeight = maxHeight - 2;

			// Trim AT state data
			repository.getATRepository().rebuildLatestAtStates(maxHeight);
			repository.getATRepository().trimAtStates(2, maxHeight, 1000);

			ATStateData atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetLatestATStateWithData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			Integer testHeight = blockchainHeight;
			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetLatestATStatePostTrimming() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			int maxHeight = blockchainHeight + 100; // more than latest block height
			Integer testHeight = blockchainHeight;

			// Trim AT state data
			repository.getATRepository().rebuildLatestAtStates(maxHeight);
			// COMMIT to check latest AT states persist / TEMPORARY table interaction
			repository.saveChanges();

			repository.getATRepository().trimAtStates(2, maxHeight, 1000);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(testHeight, atStateData.getHeight());
			// We should always have the latest AT state data available
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testOrphanTrimmedATStates() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
			int maxTrimHeight = blockchainHeight - 4;
			Integer testHeight = maxTrimHeight + 1;

			// Trim AT state data (using a max height of maxTrimHeight + 1, so it is beyond the trimmed range)
			repository.getATRepository().rebuildLatestAtStates(maxTrimHeight + 1);
			repository.saveChanges();
			repository.getATRepository().trimAtStates(2, maxTrimHeight, 1000);

			// Orphan 3 blocks
			// This leaves one more untrimmed block, so the latest AT state should be available
			BlockUtils.orphanBlocks(repository, 3);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			assertEquals(testHeight, atStateData.getHeight());

			// We should always have the latest AT state data available
			assertNotNull(atStateData.getStateData());

			// Orphan 1 more block
			DataException exception;
			try {
				exception = assertThrows(DataException.class, () -> BlockUtils.orphanBlocks(repository, 1));
			} finally {
				repository.discardChanges();
			}

			// Ensure that a DataException is thrown because there is no more AT states data available
			assertEquals(String.format("Can't find previous AT state data for %s", atAddress), exception.getMessage());

			// FUTURE: we may be able to retain unique AT states when trimming, to avoid this exception
			// and allow orphaning back through blocks with trimmed AT states.
		}
	}

	@Test
	public void testGetMatchingFinalATStatesWithoutDataValue() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			Integer testHeight = blockchainHeight;

			ATData atData = repository.getATRepository().fromATAddress(atAddress);

			byte[] codeHash = atData.getCodeHash();
			Boolean isFinished = Boolean.FALSE;
			Integer dataByteOffset = null;
			Long expectedValue = null;
			Integer minimumFinalHeight = null;
			Integer limit = null;
			Integer offset = null;
			Boolean reverse = null;

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(
					codeHash,
					null,
					null,
					isFinished,
					dataByteOffset,
					expectedValue,
					minimumFinalHeight,
					limit, offset, reverse);

			assertEquals(false, atStates.isEmpty());
			assertEquals(1, atStates.size());

			ATStateData atStateData = atStates.get(0);
			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetMatchingFinalATStatesWithDataValue() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			Integer testHeight = blockchainHeight;

			ATData atData = repository.getATRepository().fromATAddress(atAddress);

			byte[] codeHash = atData.getCodeHash();
			Boolean isFinished = Boolean.FALSE;
			Integer dataByteOffset = MachineState.HEADER_LENGTH + 0;
			Long expectedValue = 0L;
			Integer minimumFinalHeight = null;
			Integer limit = null;
			Integer offset = null;
			Boolean reverse = null;

			List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(
					codeHash,
					null,
					null,
					isFinished,
					dataByteOffset,
					expectedValue,
					minimumFinalHeight,
					limit, offset, reverse);

			assertEquals(false, atStates.isEmpty());
			assertEquals(1, atStates.size());

			ATStateData atStateData = atStates.get(0);
			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetBlockATStatesAtHeightWithData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			Integer testHeight = 8;
			List<ATStateData> atStates = repository.getATRepository().getBlockATStatesAtHeight(testHeight);

			assertEquals(false, atStates.isEmpty());
			assertEquals(1, atStates.size());

			ATStateData atStateData = atStates.get(0);
			assertEquals(testHeight, atStateData.getHeight());
			// getBlockATStatesAtHeight never returns actual AT state data anyway
			assertNull(atStateData.getStateData());
		}
	}

	@Test
	public void testGetBlockATStatesAtHeightWithoutData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			int maxHeight = 8;
			Integer testHeight = maxHeight - 2;

			// Trim AT state data
			repository.getATRepository().rebuildLatestAtStates(maxHeight);
			repository.getATRepository().trimAtStates(2, maxHeight, 1000);

			List<ATStateData> atStates = repository.getATRepository().getBlockATStatesAtHeight(testHeight);

			assertEquals(false, atStates.isEmpty());
			assertEquals(1, atStates.size());

			ATStateData atStateData = atStates.get(0);
			assertEquals(testHeight, atStateData.getHeight());
			// getBlockATStatesAtHeight never returns actual AT state data anyway
			assertNull(atStateData.getStateData());
		}
	}

	@Test
	public void testSaveATStateWithData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			Integer testHeight = blockchainHeight - 2;
			ATStateData atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());

			repository.getATRepository().save(atStateData);
			repository.saveChanges();

			atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());
		}
	}

	@Test
	public void testSaveATStateWithoutData() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// Mint a few blocks
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();

			Integer testHeight = blockchainHeight - 2;
			ATStateData atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNotNull(atStateData.getStateData());

			// Clear data
			ATStateData newAtStateData = new ATStateData(atStateData.getATAddress(),
					atStateData.getHeight(),
					/*StateData*/ null,
					atStateData.getStateHash(),
					atStateData.getMapRoot(),
					atStateData.getFees(),
					atStateData.isInitial(),
					atStateData.getSleepUntilMessageTimestamp());
			repository.getATRepository().save(newAtStateData);
			repository.saveChanges();

			atStateData = repository.getATRepository().getATStateAtHeight(atAddress, testHeight);

			assertEquals(testHeight, atStateData.getHeight());
			assertNull(atStateData.getStateData());
		}
	}

	@Test
	public void testATMapChangesApplyAndRevertInOrder() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes,
					1_00000000L);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			int firstHeight = repository.getBlockRepository().getBlockchainHeight() + 1;

			repository.getATRepository().saveATMapChanges(firstHeight, List.of(
					new ATMapChangeData(atAddress, 5L, 7L, null, 10L),
					new ATMapChangeData(atAddress, -1L, 2L, null, 30L),
					new ATMapChangeData(atAddress, 5L, 7L, 10L, 20L)));

			assertEquals(Long.valueOf(20L), repository.getATRepository().getATMapValue(atAddress, 5L, 7L));
			assertEquals(Long.valueOf(30L), repository.getATRepository().getATMapValue(atAddress, -1L, 2L));
			assertEquals(2, repository.getATRepository().getATMapEntryCount(atAddress));

			List<ATMapEntryData> entries = repository.getATRepository().getATMapEntries(atAddress);
			assertEquals(2, entries.size());
			assertEquals(-1L, entries.get(0).getKey1());
			assertEquals(5L, entries.get(1).getKey1());

			int secondHeight = firstHeight + 1;
			repository.getATRepository().saveATMapChanges(secondHeight, List.of(
					new ATMapChangeData(atAddress, 5L, 7L, 20L, null),
					new ATMapChangeData(atAddress, 9L, 9L, null, 90L)));
			assertNull(repository.getATRepository().getATMapValue(atAddress, 5L, 7L));
			assertEquals(Long.valueOf(90L), repository.getATRepository().getATMapValue(atAddress, 9L, 9L));

			repository.getATRepository().revertATMapChanges(secondHeight);
			assertEquals(Long.valueOf(20L), repository.getATRepository().getATMapValue(atAddress, 5L, 7L));
			assertNull(repository.getATRepository().getATMapValue(atAddress, 9L, 9L));

			repository.getATRepository().revertATMapChanges(firstHeight);
			assertEquals(0, repository.getATRepository().getATMapEntryCount(atAddress));
			assertNull(repository.getATRepository().getATMapValue(atAddress, -1L, 2L));
			repository.saveChanges();
		}
	}

	@Test
	public void testATMapBatchRejectsStalePreviousValueWithoutPartialApply() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes,
					1_00000000L);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			int height = repository.getBlockRepository().getBlockchainHeight() + 1;

			assertThrows(DataException.class, () -> repository.getATRepository().saveATMapChanges(height, List.of(
					new ATMapChangeData(atAddress, 1L, 1L, null, 11L),
					new ATMapChangeData(atAddress, 2L, 2L, 999L, 22L))));

			assertEquals(0, repository.getATRepository().getATMapEntryCount(atAddress));
			assertNull(repository.getATRepository().getATMapValue(atAddress, 1L, 1L));
			repository.getATRepository().revertATMapChanges(height);
			assertEquals(0, repository.getATRepository().getATMapEntryCount(atAddress));
		}
	}

	@Test
	public void testATStateMapRootRoundTrip() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes,
					1_00000000L);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			ATStateData original = repository.getATRepository().getLatestATState(atAddress);
			byte[] mapRoot = new byte[32];
			mapRoot[0] = 42;

			ATStateData withMapRoot = new ATStateData(original.getATAddress(), original.getHeight(),
					original.getStateData(), original.getStateHash(), mapRoot, original.getFees(), original.isInitial(),
					original.getSleepUntilMessageTimestamp());
			repository.getATRepository().save(withMapRoot);

			ATStateData reloaded = repository.getATRepository().getLatestATState(atAddress);
			assertArrayEquals(mapRoot, reloaded.getMapRoot());
			repository.saveChanges();
		}
	}

	@Test
	public void testATMapRejectsZeroAndUnchangedJournalValues() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, creationBytes, 1_00000000L)
					.getATAccount().getAddress();
			int height = repository.getBlockRepository().getBlockchainHeight() + 1;

			assertThrows(IllegalArgumentException.class, () -> repository.getATRepository().saveATMapChanges(height,
					List.of(new ATMapChangeData(atAddress, 1L, 2L, null, 0L))));
			assertThrows(IllegalArgumentException.class, () -> repository.getATRepository().saveATMapChanges(height,
					List.of(new ATMapChangeData(atAddress, 1L, 2L, 3L, 3L))));
		}
	}

	@Test
	public void testATMapJournalPrunesOnlyPastOrphanHistory() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, creationBytes, 1_00000000L)
					.getATAccount().getAddress();
			int firstHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			int secondHeight = firstHeight + 1;
			repository.getATRepository().saveATMapChanges(firstHeight,
					List.of(new ATMapChangeData(atAddress, 1L, 2L, null, 10L)));
			repository.getATRepository().saveATMapChanges(secondHeight,
					List.of(new ATMapChangeData(atAddress, 1L, 2L, 10L, 20L)));

			assertEquals(1, repository.getATRepository().pruneATMapChanges(firstHeight, firstHeight));
			repository.getATRepository().revertATMapChanges(secondHeight);
			assertEquals(Long.valueOf(10L), repository.getATRepository().getATMapValue(atAddress, 1L, 2L));
			repository.getATRepository().revertATMapChanges(firstHeight);
			assertEquals(Long.valueOf(10L), repository.getATRepository().getATMapValue(atAddress, 1L, 2L));
			repository.discardChanges();
		}
	}

	@Test
	public void testATMapRootVerifierDetectsCorruptServingRows() throws DataException {
		byte[] creationBytes = AtUtils.buildSimpleAT();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			String atAddress = AtUtils.doDeployAT(repository, deployer, creationBytes, 1_00000000L)
					.getATAccount().getAddress();
			repository.getATRepository().verifyATMapRoots();

			int height = repository.getBlockRepository().getBlockchainHeight() + 1;
			repository.getATRepository().saveATMapChanges(height,
					List.of(new ATMapChangeData(atAddress, 1L, 2L, null, 3L)));
			assertThrows(DataException.class, () -> repository.getATRepository().verifyATMapRoots());
			repository.getATRepository().revertATMapChanges(height);
			repository.getATRepository().verifyATMapRoots();
			repository.discardChanges();
		}
	}
}
