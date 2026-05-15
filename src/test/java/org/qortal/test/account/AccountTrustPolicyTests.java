package org.qortal.test.account;

import org.junit.Test;
import org.qortal.account.AccountTrustPolicy;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustStatus;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class AccountTrustPolicyTests {

	@Test
	public void testStatusMapping() {
		assertEquals(AccountTrustStatus.SUSPICIOUS, AccountTrustPolicy.mapLevelToStatus(-1));
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(0));
		assertEquals(AccountTrustStatus.BRONZE, AccountTrustPolicy.mapLevelToStatus(1));
		assertEquals(AccountTrustStatus.SILVER, AccountTrustPolicy.mapLevelToStatus(2));
		assertEquals(AccountTrustStatus.GOLD, AccountTrustPolicy.mapLevelToStatus(3));
		assertEquals(AccountTrustStatus.GOLD, AccountTrustPolicy.mapLevelToStatus(4));
	}

	@Test
	public void testPolicyConstants() {
		assertEquals(1_000_000L, AccountTrustPolicy.STARTING_ENERGY);
		assertEquals(4, AccountTrustPolicy.MANAGER_ENERGY_HOPS);
		assertEquals(AccountRatingCategory.SUBJECT, AccountTrustPolicy.ACTIVE_WEIGHT_CATEGORY);
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRaterCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRatingConfidence());
	}

	@Test
	public void testManagerThresholdsAndCaps() {
		assertThresholdAndCap(AccountRatingCategory.MANAGER, 1, 1_000L, 500L);
		assertThresholdAndCap(AccountRatingCategory.MANAGER, 2, 200_000L, 100_000L);
		assertEquals(-1_000L, AccountTrustPolicy.getSuspiciousThreshold(AccountRatingCategory.MANAGER));
		assertEquals(500L, AccountTrustPolicy.getSuspiciousLevelScoreCap(AccountRatingCategory.MANAGER));
	}

	@Test
	public void testTrainerThresholdsAndCaps() {
		assertThresholdAndCap(AccountRatingCategory.TRAINER, 1, 500_000L, 250_000L);
		assertThresholdAndCap(AccountRatingCategory.TRAINER, 2, 1_000_000L, 500_000L);
		assertEquals(-500_000L, AccountTrustPolicy.getSuspiciousThreshold(AccountRatingCategory.TRAINER));
		assertEquals(250_000L, AccountTrustPolicy.getSuspiciousLevelScoreCap(AccountRatingCategory.TRAINER));
	}

	@Test
	public void testPlayerThresholdsAndCaps() {
		assertThresholdAndCap(AccountRatingCategory.PLAYER, 1, 1_000_000L, 500_000L);
		assertThresholdAndCap(AccountRatingCategory.PLAYER, 2, 2_000_000L, 1_000_000L);
		assertThresholdAndCap(AccountRatingCategory.PLAYER, 3, 3_000_000L, 1_500_000L);
		assertEquals(-1_000_000L, AccountTrustPolicy.getSuspiciousThreshold(AccountRatingCategory.PLAYER));
		assertEquals(500_000L, AccountTrustPolicy.getSuspiciousLevelScoreCap(AccountRatingCategory.PLAYER));
	}

	@Test
	public void testSubjectThresholdsAndCaps() {
		assertThresholdAndCap(AccountRatingCategory.SUBJECT, 1, 10_000_000L, 5_000_000L);
		assertThresholdAndCap(AccountRatingCategory.SUBJECT, 2, 50_000_000L, 25_000_000L);
		assertThresholdAndCap(AccountRatingCategory.SUBJECT, 3, 100_000_000L, 50_000_000L);
		assertThresholdAndCap(AccountRatingCategory.SUBJECT, 4, 150_000_000L, 75_000_000L);
		assertEquals(-10_000_000L, AccountTrustPolicy.getSuspiciousThreshold(AccountRatingCategory.SUBJECT));
		assertEquals(5_000_000L, AccountTrustPolicy.getSuspiciousLevelScoreCap(AccountRatingCategory.SUBJECT));
	}

	@Test
	public void testSingleTrustedNegativeImpactDoesNotMakeSuspicious() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-512_000_000L, Collections.singletonList(impact("r1", 3, -4, -512_000_000L)));

		assertEquals(0, decision.getLevel());
		assertEquals(-5_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	@Test
	public void testTwoMediumNegativeImpactsMakeSuspicious() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-256_000_000L, Arrays.asList(
						impact("r1", 3, -2, -128_000_000L),
						impact("r2", 3, -2, -128_000_000L)));

		assertEquals(-1, decision.getLevel());
		assertEquals(-10_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.SUSPICIOUS, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	@Test
	public void testLowConfidenceNegativeImpactsDoNotMeetSuspiciousRaterRequirement() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-128_000_000L, Arrays.asList(
						impact("r1", 3, -1, -64_000_000L),
						impact("r2", 3, -1, -64_000_000L)));

		assertEquals(0, decision.getLevel());
		assertEquals(-10_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	@Test
	public void testSinglePositiveImpactDoesNotQualifyThroughCap() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				4_000_000L, Collections.singletonList(impact("r1", 0, 4, 4_000_000L)));

		assertEquals(0, decision.getLevel());
		assertEquals(500L, decision.getLevelScore());
		assertEquals(500L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	@Test
	public void testTwoPositiveImpactsQualifyThroughCap() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L),
						impact("r2", 0, 1, 500_000L)));

		assertEquals(2, decision.getLevel());
		assertEquals(200_000L, decision.getLevelScore());
		assertEquals(100_000L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.SILVER, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	private static void assertThresholdAndCap(AccountRatingCategory category, int level, long expectedThreshold,
			long expectedCap) {
		assertEquals(expectedThreshold, AccountTrustPolicy.getLevelThreshold(category, level));
		assertEquals(expectedCap, AccountTrustPolicy.getLevelScoreCap(category, level));
	}

	private static AccountTrustPreviewData.CategoryImpact impact(String raterAddress, int evaluatorLevel, int rating,
			long impact) {
		return new AccountTrustPreviewData.CategoryImpact(null, raterAddress, evaluatorLevel, 0L, rating, impact);
	}
}
