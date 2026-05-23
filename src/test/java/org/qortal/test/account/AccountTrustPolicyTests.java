package org.qortal.test.account;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.AccountTrustPolicy;
import org.qortal.api.resource.AccountRatingsResource;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustPolicyData;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AccountTrustPolicyTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

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
		AccountTrustPolicy.DecisionSettings decisionSettings = AccountTrustPolicy.getDecisionSettings();

		assertEquals(1_000_000L, AccountTrustPolicy.getStartingEnergy());
		assertEquals(4, AccountTrustPolicy.getManagerEnergyHops());
		assertEquals(AccountRatingCategory.SUBJECT, AccountTrustPolicy.getActiveWeightCategory());
		assertEquals(2, AccountTrustPolicy.getPositiveMinBranchCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRaterCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinBranchCount());
		assertEquals(2, AccountTrustPolicy.getSuspiciousMinRatingConfidence());
		assertEquals(2, decisionSettings.getPositiveMinBranchCount());
		assertEquals(2, decisionSettings.getSuspiciousMinRaterCount());
		assertEquals(2, decisionSettings.getSuspiciousMinBranchCount());
		assertEquals(2, decisionSettings.getSuspiciousMinRatingConfidence());
		assertEquals(1440, AccountTrustPolicy.getAccountRatingChangeCooldownBlocks());
	}

	@Test
	public void testConfiguredVoteWeightPercentages() {
		assertEquals(100, AccountTrustPolicy.getVoteWeightPercent(AccountTrustStatus.GOLD));
		assertEquals(70, AccountTrustPolicy.getVoteWeightPercent(AccountTrustStatus.SILVER));
		assertEquals(40, AccountTrustPolicy.getVoteWeightPercent(AccountTrustStatus.BRONZE));
		assertEquals(0, AccountTrustPolicy.getVoteWeightPercent(AccountTrustStatus.UNVERIFIED));
		assertEquals(0, AccountTrustPolicy.getVoteWeightPercent(AccountTrustStatus.SUSPICIOUS));
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
	public void testTwoMediumNegativeImpactsFromSameBranchDoNotMakeSuspicious() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-256_000_000L, Arrays.asList(
						impact("r1", 3, -2, -128_000_000L, "shared-branch"),
						impact("r2", 3, -2, -128_000_000L, "shared-branch")));

		assertEquals(0, decision.getLevel());
		assertEquals(-10_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
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

	@Test
	public void testTwoPositiveImpactsFromSameBranchDoNotQualifyThroughBranchRequirement() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L, "shared-branch"),
						impact("r2", 0, 1, 500_000L, "shared-branch")));

		assertEquals(0, decision.getLevel());
		assertEquals(1_000L, decision.getLevelScore());
		assertEquals(500L, decision.getLevelScoreCap());
		assertEquals(AccountTrustStatus.UNVERIFIED, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
	}

	@Test
	public void testDecisionSettingsOverrideChangesLevelDecision() {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L),
						impact("r2", 0, 1, 500_000L)));
		assertEquals(2, defaultDecision.getLevel());

		AccountTrustPolicy.DecisionSettings strictSettings = new AccountTrustPolicy.DecisionSettings(3, 2, 2, 2);
		AccountTrustPolicy.LevelDecision strictDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L),
						impact("r2", 0, 1, 500_000L)),
				strictSettings);

		assertEquals(0, strictDecision.getLevel());
		assertEquals(1_000L, strictDecision.getLevelScore());
		assertEquals(500L, strictDecision.getLevelScoreCap());
	}

	@Test
	public void testCustomVoteWeightPolicyChangesEffectiveWeight() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"status\": \"SILVER\", \"percent\": 70 }",
				"{ \"status\": \"SILVER\", \"percent\": 60 }");
		loadTemporaryConfig(config);

		assertEquals(60, AccountTrustStatus.SILVER.getVoteWeightPercent());
		assertEquals(60, AccountTrustStatus.SILVER.calculateEffectiveVoteWeight(100));
	}

	@Test
	public void testCustomLevelPolicyChangesLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.PLAYER,
				600_000L, Arrays.asList(
						impact("r1", 0, 1, 300_000L),
						impact("r2", 0, 1, 300_000L)));
		assertEquals(0, defaultDecision.getLevel());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"level\": 1, \"threshold\": 1000000, \"cap\": 500000 }",
				"{ \"level\": 1, \"threshold\": 600000, \"cap\": 300000 }");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision customDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.PLAYER,
				600_000L, Arrays.asList(
						impact("r1", 0, 1, 300_000L),
						impact("r2", 0, 1, 300_000L)));
		assertEquals(1, customDecision.getLevel());
		assertEquals(600_000L, customDecision.getLevelScore());
		assertEquals(300_000L, customDecision.getLevelScoreCap());
	}

	@Test
	public void testCustomPositiveBranchCountChangesLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L),
						impact("r2", 0, 1, 500_000L)));
		assertEquals(2, defaultDecision.getLevel());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"positiveMinBranchCount\": 2",
				"\"positiveMinBranchCount\": 3");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision customDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.MANAGER,
				1_000_000L, Arrays.asList(
						impact("r1", 0, 1, 500_000L),
						impact("r2", 0, 1, 500_000L)));

		assertEquals(0, customDecision.getLevel());
		assertEquals(1_000L, customDecision.getLevelScore());
		assertEquals(500L, customDecision.getLevelScoreCap());
	}

	@Test
	public void testCustomSuspiciousBranchCountChangesLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-256_000_000L, Arrays.asList(
						impact("r1", 3, -2, -128_000_000L),
						impact("r2", 3, -2, -128_000_000L)));
		assertEquals(-1, defaultDecision.getLevel());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousMinBranchCount\": 2",
				"\"suspiciousMinBranchCount\": 3");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision customDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-256_000_000L, Arrays.asList(
						impact("r1", 3, -2, -128_000_000L),
						impact("r2", 3, -2, -128_000_000L)));

		assertEquals(0, customDecision.getLevel());
		assertEquals(-10_000_000L, customDecision.getLevelScore());
		assertEquals(5_000_000L, customDecision.getLevelScoreCap());
	}

	@Test
	public void testCalibrationMatrixVoteMultipliersChangeEffectiveWeights() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"status\": \"BRONZE\", \"percent\": 40 }",
				"{ \"status\": \"BRONZE\", \"percent\": 35 }");
		config = replaceRequired(config,
				"{ \"status\": \"SILVER\", \"percent\": 70 }",
				"{ \"status\": \"SILVER\", \"percent\": 60 }");
		config = replaceRequired(config,
				"{ \"status\": \"GOLD\", \"percent\": 100 }",
				"{ \"status\": \"GOLD\", \"percent\": 90 }");
		loadTemporaryConfig(config);

		assertEquals(350, AccountTrustPolicy.calculateEffectiveVoteWeight(1000, AccountTrustStatus.BRONZE));
		assertEquals(600, AccountTrustPolicy.calculateEffectiveVoteWeight(1000, AccountTrustStatus.SILVER));
		assertEquals(900, AccountTrustPolicy.calculateEffectiveVoteWeight(1000, AccountTrustStatus.GOLD));
		assertEquals(0, AccountTrustPolicy.calculateEffectiveVoteWeight(1000, AccountTrustStatus.UNVERIFIED));
		assertEquals(0, AccountTrustPolicy.calculateEffectiveVoteWeight(1000, AccountTrustStatus.SUSPICIOUS));
	}

	@Test
	public void testCalibrationMatrixPositiveCapsChangeLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				12_000_000L, Arrays.asList(
						impact("r1", 1, 1, 6_000_000L),
						impact("r2", 1, 1, 6_000_000L)));
		assertEquals(1, defaultDecision.getLevel());
		assertEquals(10_000_000L, defaultDecision.getLevelScore());
		assertEquals(5_000_000L, defaultDecision.getLevelScoreCap());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"level\": 1, \"threshold\": 10000000, \"cap\": 5000000 }",
				"{ \"level\": 1, \"threshold\": 10000000, \"cap\": 4000000 }");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision customDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				12_000_000L, Arrays.asList(
						impact("r1", 1, 1, 6_000_000L),
						impact("r2", 1, 1, 6_000_000L)));
		assertEquals(0, customDecision.getLevel());
		assertEquals(8_000_000L, customDecision.getLevelScore());
		assertEquals(4_000_000L, customDecision.getLevelScoreCap());
	}

	@Test
	public void testCalibrationMatrixSuspiciousCapsChangeCappedScore() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-12_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(-1, defaultDecision.getLevel());
		assertEquals(-10_000_000L, defaultDecision.getLevelScore());
		assertEquals(5_000_000L, defaultDecision.getLevelScoreCap());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousCap\": 5000000",
				"\"suspiciousCap\": 6000000");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision customDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-12_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(-1, customDecision.getLevel());
		assertEquals(-12_000_000L, customDecision.getLevelScore());
		assertEquals(6_000_000L, customDecision.getLevelScoreCap());
	}

	@Test
	public void testCalibrationMatrixSuspiciousRaterCountChangesLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-18_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(-1, defaultDecision.getLevel());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousMinRaterCount\": 2",
				"\"suspiciousMinRaterCount\": 3");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision twoRaterDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-18_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(0, twoRaterDecision.getLevel());
		assertEquals(-10_000_000L, twoRaterDecision.getLevelScore());

		AccountTrustPolicy.LevelDecision threeRaterDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-18_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L),
						impact("r3", 3, -2, -6_000_000L)));
		assertEquals(-1, threeRaterDecision.getLevel());
		assertEquals(-15_000_000L, threeRaterDecision.getLevelScore());
	}

	@Test
	public void testCalibrationMatrixSuspiciousConfidenceChangesLevelDecision() throws Exception {
		AccountTrustPolicy.LevelDecision defaultDecision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-12_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(-1, defaultDecision.getLevel());

		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousMinRatingConfidence\": 2",
				"\"suspiciousMinRatingConfidence\": 3");
		loadTemporaryConfig(config);

		AccountTrustPolicy.LevelDecision mediumConfidenceDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -12_000_000L, Arrays.asList(
						impact("r1", 3, -2, -6_000_000L),
						impact("r2", 3, -2, -6_000_000L)));
		assertEquals(0, mediumConfidenceDecision.getLevel());
		assertEquals(-10_000_000L, mediumConfidenceDecision.getLevelScore());

		AccountTrustPolicy.LevelDecision highConfidenceDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -12_000_000L, Arrays.asList(
						impact("r1", 3, -3, -6_000_000L),
						impact("r2", 3, -3, -6_000_000L)));
		assertEquals(-1, highConfidenceDecision.getLevel());
		assertEquals(-10_000_000L, highConfidenceDecision.getLevelScore());
	}

	@Test
	public void testTrustPolicyEndpointReflectsCustomConfig() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"status\": \"SILVER\", \"percent\": 70 }",
				"{ \"status\": \"SILVER\", \"percent\": 65 }");
		config = replaceRequired(config,
				"{ \"level\": 1, \"threshold\": 1000000, \"cap\": 500000 }",
				"{ \"level\": 1, \"threshold\": 600000, \"cap\": 300000 }");
		config = replaceRequired(config,
				"\"positiveMinBranchCount\": 2",
				"\"positiveMinBranchCount\": 3");
		config = replaceRequired(config,
				"\"suspiciousMinBranchCount\": 2",
				"\"suspiciousMinBranchCount\": 3");
		config = replaceRequired(config,
				"\"accountRatingChangeCooldownBlocks\": 1440",
				"\"accountRatingChangeCooldownBlocks\": 720");
		loadTemporaryConfig(config);

		AccountTrustPolicyData policy = new AccountRatingsResource().getAccountTrustPolicy();

		assertEquals(65, findStatusVoteWeight(policy, AccountTrustStatus.SILVER).getVoteWeightPercent());
		assertEquals(3, policy.getPositiveMinBranchCount());
		assertEquals(3, policy.getSuspiciousMinBranchCount());
		assertEquals(720, policy.getAccountRatingChangeCooldownBlocks());
		AccountTrustPolicyData.LevelPolicy playerLevelOne = findLevelPolicy(
				findCategoryPolicy(policy, AccountRatingCategory.PLAYER), 1);
		assertEquals(600_000L, playerLevelOne.getThreshold());
		assertEquals(300_000L, playerLevelOne.getLevelScoreCap());
	}

	@Test
	public void testCustomAccountRatingCooldownCanBeDisabled() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"accountRatingChangeCooldownBlocks\": 1440",
				"\"accountRatingChangeCooldownBlocks\": 0");
		loadTemporaryConfig(config);

		assertEquals(0, AccountTrustPolicy.getAccountRatingChangeCooldownBlocks());
	}

	@Test
	public void testMissingTrustSettingsRejected() throws Exception {
		assertInvalidConfig(removeAccountTrustSettings(loadDefaultTestChainConfig()),
				"No \"accountTrustSettings\" entry found");
	}

	@Test
	public void testInvalidTrustVoteWeightRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"status\": \"GOLD\", \"percent\": 100 }",
				"{ \"status\": \"GOLD\", \"percent\": 101 }");

		assertInvalidConfig(config, "Account trust vote weight percent must be between 0 and 100");
	}

	@Test
	public void testDuplicateTrustCategoryPolicyRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"category\": \"TRAINER\"",
				"\"category\": \"MANAGER\"");

		assertInvalidConfig(config, "Duplicate account trust category policy: MANAGER");
	}

	@Test
	public void testInvalidTrustCapRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"level\": 1, \"threshold\": 1000, \"cap\": 500 }",
				"{ \"level\": 1, \"threshold\": 1000, \"cap\": 0 }");

		assertInvalidConfig(config, "Account trust level cap must be positive and less than the threshold");
	}

	@Test
	public void testInvalidSuspiciousThresholdRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousThreshold\": -1000",
				"\"suspiciousThreshold\": 1000");

		assertInvalidConfig(config, "Account trust suspicious threshold must be negative");
	}

	@Test
	public void testInvalidPositiveBranchCountRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"positiveMinBranchCount\": 2",
				"\"positiveMinBranchCount\": 0");

		assertInvalidConfig(config, "\"accountTrustSettings.positiveMinBranchCount\" must be greater than 0");
	}

	@Test
	public void testInvalidSuspiciousBranchCountRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"suspiciousMinBranchCount\": 2",
				"\"suspiciousMinBranchCount\": -1");

		assertInvalidConfig(config, "\"accountTrustSettings.suspiciousMinBranchCount\" must not be negative");
	}

	@Test
	public void testInvalidAccountRatingCooldownRejected() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"accountRatingChangeCooldownBlocks\": 1440",
				"\"accountRatingChangeCooldownBlocks\": -1");

		assertInvalidConfig(config, "\"accountTrustSettings.accountRatingChangeCooldownBlocks\" must not be negative");
	}

	private static void assertThresholdAndCap(AccountRatingCategory category, int level, long expectedThreshold,
			long expectedCap) {
		assertEquals(expectedThreshold, AccountTrustPolicy.getLevelThreshold(category, level));
		assertEquals(expectedCap, AccountTrustPolicy.getLevelScoreCap(category, level));
	}

	private static AccountTrustCategoryImpactData impact(String raterAddress, int evaluatorLevel, int rating,
			long impact) {
		return impact(raterAddress, evaluatorLevel, rating, impact, raterAddress + "-branch");
	}

	private static AccountTrustCategoryImpactData impact(String raterAddress, int evaluatorLevel, int rating,
			long impact, String... trustBranchKeys) {
		return new AccountTrustCategoryImpactData(null, raterAddress, evaluatorLevel, 0L, rating, impact,
				Arrays.asList(trustBranchKeys));
	}

	private static AccountTrustPolicyData.StatusVoteWeight findStatusVoteWeight(AccountTrustPolicyData policy,
			AccountTrustStatus status) {
		return policy.getStatusVoteWeights().stream()
				.filter(statusVoteWeight -> statusVoteWeight.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing status vote weight " + status));
	}

	private static AccountTrustPolicyData.CategoryPolicy findCategoryPolicy(AccountTrustPolicyData policy,
			AccountRatingCategory category) {
		return policy.getCategoryPolicies().stream()
				.filter(categoryPolicy -> categoryPolicy.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category policy " + category));
	}

	private static AccountTrustPolicyData.LevelPolicy findLevelPolicy(AccountTrustPolicyData.CategoryPolicy categoryPolicy,
			int level) {
		return categoryPolicy.getLevels().stream()
				.filter(levelPolicy -> levelPolicy.getLevel() == level)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing level policy " + level));
	}

	private static String loadDefaultTestChainConfig() throws Exception {
		URL testChainUrl = Common.class.getClassLoader().getResource("test-chain-v2.json");
		assertNotNull(testChainUrl);
		return Files.readString(Paths.get(testChainUrl.toURI()), StandardCharsets.UTF_8);
	}

	private static String replaceRequired(String config, String target, String replacement) {
		String updatedConfig = config.replace(target, replacement);
		assertTrue("Config replacement target not found: " + target, !updatedConfig.equals(config));
		return updatedConfig;
	}

	private static String removeAccountTrustSettings(String config) {
		String startMarker = "\t\"accountTrustSettings\": {";
		String endMarker = "\t\"unitFees\": [";
		int start = config.indexOf(startMarker);
		int end = config.indexOf(endMarker, start);

		assertTrue("accountTrustSettings start marker not found", start >= 0);
		assertTrue("accountTrustSettings end marker not found", end > start);

		return config.substring(0, start) + config.substring(end);
	}

	private static void loadTemporaryConfig(String config) throws Exception {
		Path tempDir = Files.createTempDirectory("account-trust-config");
		Path configPath = tempDir.resolve("blockchain.json");

		Files.writeString(configPath, config, StandardCharsets.UTF_8);
		BlockChain.fileInstance(tempDir.toString() + java.io.File.separator, configPath.getFileName().toString());
		Files.deleteIfExists(configPath);
		Files.deleteIfExists(tempDir);
	}

	private static void assertInvalidConfig(String config, String expectedMessageFragment) throws Exception {
		Path tempDir = Files.createTempDirectory("account-trust-config");
		Path configPath = tempDir.resolve("blockchain.json");

		try {
			Files.writeString(configPath, config, StandardCharsets.UTF_8);
			BlockChain.fileInstance(tempDir.toString() + java.io.File.separator, configPath.getFileName().toString());
			fail("Expected invalid blockchain config");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains(expectedMessageFragment));
		} finally {
			Files.deleteIfExists(configPath);
			Files.deleteIfExists(tempDir);
		}
	}
}
