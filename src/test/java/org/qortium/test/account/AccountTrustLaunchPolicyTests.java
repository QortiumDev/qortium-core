package org.qortium.test.account;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustLaunchPolicyTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testLaunchTrustPolicyProfileDefaults() {
		assertEquals(AccountRatingCategory.SUBJECT, AccountTrustPolicy.getActiveWeightCategory());
		assertEquals(1_000_000L, AccountTrustPolicy.getStartingEnergy());
		assertEquals(4, AccountTrustPolicy.getManagerEnergyHops());
		assertEquals(2, AccountTrustPolicy.getPositiveMinBranchCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRaterCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinBranchCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRatingConfidence());
		assertEquals(1440, AccountTrustPolicy.getAccountRatingChangeCooldownBlocks());

		assertStatusPolicy(AccountTrustStatus.GOLD, 100, true);
		assertStatusPolicy(AccountTrustStatus.SILVER, 70, true);
		assertStatusPolicy(AccountTrustStatus.BRONZE, 40, true);
		assertStatusPolicy(AccountTrustStatus.UNVERIFIED, 0, true);
		assertStatusPolicy(AccountTrustStatus.SUSPICIOUS, 0, false);

		assertCategoryPolicy(AccountRatingCategory.MANAGER, -1_000L, 500L,
				level(1, 1_000L, 500L),
				level(2, 200_000L, 100_000L));
		assertCategoryPolicy(AccountRatingCategory.TRAINER, -500_000L, 250_000L,
				level(1, 500_000L, 250_000L),
				level(2, 1_000_000L, 500_000L));
		assertCategoryPolicy(AccountRatingCategory.PLAYER, -1_000_000L, 500_000L,
				level(1, 1_000_000L, 500_000L),
				level(2, 2_000_000L, 1_000_000L),
				level(3, 3_000_000L, 1_500_000L));
		assertCategoryPolicy(AccountRatingCategory.SUBJECT, -10_000_000L, 5_000_000L,
				level(1, 10_000_000L, 5_000_000L),
				level(2, 50_000_000L, 25_000_000L),
				level(3, 100_000_000L, 50_000_000L),
				level(4, 150_000_000L, 75_000_000L));
	}

	@Test
	public void testPackagedMainAndDefaultTestChainUseSameLaunchTrustPolicy() throws Exception {
		JSONObject mainTrustSettings = readResourceJson("blockchain.json").getJSONObject("accountTrustSettings");
		JSONObject testTrustSettings = readResourceJson("test-chain-v2.json").getJSONObject("accountTrustSettings");

		assertTrue("Main blockchain.json and default test-chain-v2.json accountTrustSettings must match",
				mainTrustSettings.similar(testTrustSettings));
	}

	private static void assertStatusPolicy(AccountTrustStatus status, int expectedVoteWeightPercent,
			boolean expectedMintingAllowed) {
		assertEquals(expectedVoteWeightPercent, AccountTrustPolicy.getVoteWeightPercent(status));
		assertEquals(expectedMintingAllowed, status.canMint());
	}

	private static void assertCategoryPolicy(AccountRatingCategory category, long expectedSuspiciousThreshold,
			long expectedSuspiciousCap, LevelExpectation... levels) {
		assertEquals(expectedSuspiciousThreshold, AccountTrustPolicy.getSuspiciousThreshold(category));
		assertEquals(expectedSuspiciousCap, AccountTrustPolicy.getSuspiciousLevelScoreCap(category));

		for (LevelExpectation level : levels) {
			assertEquals(level.threshold, AccountTrustPolicy.getLevelThreshold(category, level.level));
			assertEquals(level.cap, AccountTrustPolicy.getLevelScoreCap(category, level.level));
		}
	}

	private static LevelExpectation level(int level, long threshold, long cap) {
		return new LevelExpectation(level, threshold, cap);
	}

	private static JSONObject readResourceJson(String resourceName) throws Exception {
		URL resourceUrl = Common.class.getClassLoader().getResource(resourceName);
		assertNotNull(resourceUrl);
		return new JSONObject(Files.readString(Paths.get(resourceUrl.toURI()), StandardCharsets.UTF_8));
	}

	private static final class LevelExpectation {
		private final int level;
		private final long threshold;
		private final long cap;

		private LevelExpectation(int level, long threshold, long cap) {
			this.level = level;
			this.threshold = threshold;
			this.cap = cap;
		}
	}
}
