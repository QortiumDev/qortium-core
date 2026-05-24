package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.asset.Asset;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.group.GroupData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PreviewChainTests extends Common {

	private static final String PREVIEW_SETTINGS = "test-settings-preview.json";
	private static final String SEED_ACCOUNT_ADDRESS = "QXhkAy3zNBQwzxxJLLP9u42Ec2XvASyvf3";
	private static final String SEED_MINTING_PUBLIC_KEY = "6Ue5kRXpHbNrQguXjpHuTu6RzPiqZrYLStJ6gmBDobov";
	private static final String LOCAL_ACCOUNT_ADDRESS = "QaLdnApWW3hps1qXM8cpsL1pVgw7RtyJmN";
	private static final String LOCAL_MINTING_PUBLIC_KEY = "6DdhEueMEopFphx81ywZ5WWdZHCUDERi9J43rjQDtvMV";

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings(PREVIEW_SETTINGS);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testPreviewGenesisStartsWithoutNativeAssetOrPrefunds() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertFalse(repository.getAssetRepository().assetExists(Asset.NATIVE));
			assertTrue(repository.getAccountRepository().getAssetBalances(Asset.NATIVE, false).isEmpty());

			GroupData developmentGroup = repository.getGroupRepository().fromGroupId(1);
			assertNotNull(developmentGroup);
			assertEquals("development", developmentGroup.getGroupName());
			assertEquals(Group.NULL_OWNER_ADDRESS, developmentGroup.getOwner());
			assertTrue(developmentGroup.isOpen());

			GroupData mintingGroup = repository.getGroupRepository().fromGroupId(2);
			assertNotNull(mintingGroup);
			assertEquals("minting", mintingGroup.getGroupName());
			assertEquals(Group.NULL_OWNER_ADDRESS, mintingGroup.getOwner());
			assertTrue(mintingGroup.isOpen());

			assertPreviewMintingAuthorization(repository, SEED_ACCOUNT_ADDRESS, SEED_MINTING_PUBLIC_KEY);
			assertPreviewMintingAuthorization(repository, LOCAL_ACCOUNT_ADDRESS, LOCAL_MINTING_PUBLIC_KEY);
		}
	}

	private static void assertPreviewMintingAuthorization(Repository repository, String accountAddress,
			String mintingPublicKey58) throws DataException {
		assertTrue(repository.getGroupRepository().memberExists(2, accountAddress));

		RewardShareData rewardShareData = repository.getAccountRepository()
				.getRewardShare(Base58.decode(mintingPublicKey58));
		assertNotNull(rewardShareData);
		assertEquals(accountAddress, rewardShareData.getMinter());
		assertEquals(accountAddress, rewardShareData.getRecipient());
		assertEquals(0, rewardShareData.getSharePercent());
	}

}
