package org.qortal.test.minting;

import org.junit.Before;
import org.junit.Test;
import org.qortal.asset.Asset;
import org.qortal.data.asset.AssetData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeRewardAssetTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGenesisNativeAssetUsesReservedId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			AssetData nativeAssetData = repository.getAssetRepository().fromAssetName("NATIVE");

			assertEquals(Asset.NATIVE, (long) nativeAssetData.getAssetId());
			assertTrue(repository.getAssetRepository().assetExists(Asset.NATIVE));
		}
	}

	@Test
	public void testNativeRewardsSkippedUntilNativeAssetExists() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.getAssetRepository().delete(Asset.NATIVE);
			repository.saveChanges();

			assertNativeAssetAbsent(repository);
			int startingHeight = repository.getBlockRepository().getBlockchainHeight();

			BlockUtils.mintBlock(repository);

			assertEquals(startingHeight + 1, repository.getBlockRepository().getBlockchainHeight());
			assertNativeAssetAbsent(repository);

			BlockUtils.orphanLastBlock(repository);

			assertEquals(startingHeight, repository.getBlockRepository().getBlockchainHeight());
			assertNativeAssetAbsent(repository);
		}
	}

	private static void assertNativeAssetAbsent(Repository repository) throws DataException {
		assertFalse(repository.getAssetRepository().assetExists(Asset.NATIVE));
		assertTrue(repository.getAccountRepository().getAssetBalances(Asset.NATIVE, false).isEmpty());
	}

}
