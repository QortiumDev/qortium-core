package org.qortium.test.account;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryImpactData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustPolicyCalibrationScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testPermissiveProfilePromotesBorderlinePositiveEvidence() throws Exception {
		loadPermissiveProfile();

		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				8_000_000L, Arrays.asList(
						impact("p1", 1, 1, 4_000_000L),
						impact("p2", 1, 1, 4_000_000L)));

		assertSubjectDecision(decision, 1, AccountTrustStatus.BRONZE);
		assertEquals(8_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
	}

	@Test
	public void testCurrentProfileKeepsBorderlinePositiveEvidenceUnverified() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				8_000_000L, Arrays.asList(
						impact("p1", 1, 1, 4_000_000L),
						impact("p2", 1, 1, 4_000_000L)));

		assertSubjectDecision(decision, 0, AccountTrustStatus.UNVERIFIED);
		assertEquals(8_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
	}

	@Test
	public void testCurrentProfilePromotesStrongIndependentEvidenceToGold() {
		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				100_000_000L, Arrays.asList(
						impact("p1", 2, 2, 50_000_000L),
						impact("p2", 2, 2, 50_000_000L)));

		assertSubjectDecision(decision, 3, AccountTrustStatus.GOLD);
		assertEquals(100_000_000L, decision.getLevelScore());
		assertEquals(50_000_000L, decision.getLevelScoreCap());
	}

	@Test
	public void testStrictProfileRequiresThreePositiveBranches() throws Exception {
		loadStrictProfile();

		AccountTrustPolicy.LevelDecision twoBranchDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, 100_000_000L, Arrays.asList(
						impact("p1", 2, 2, 50_000_000L),
						impact("p2", 2, 2, 50_000_000L)));
		assertSubjectDecision(twoBranchDecision, 0, AccountTrustStatus.UNVERIFIED);

		AccountTrustPolicy.LevelDecision threeBranchDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, 150_000_000L, Arrays.asList(
						impact("p1", 2, 2, 50_000_000L),
						impact("p2", 2, 2, 50_000_000L),
						impact("p3", 2, 2, 50_000_000L)));
		assertSubjectDecision(threeBranchDecision, 3, AccountTrustStatus.GOLD);
		assertEquals(150_000_000L, threeBranchDecision.getLevelScore());
		assertEquals(50_000_000L, threeBranchDecision.getLevelScoreCap());
	}

	@Test
	public void testPermissiveProfileStillRejectsSameBranchPositiveEvidence() throws Exception {
		loadPermissiveProfile();

		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				100_000_000L, Arrays.asList(
						impact("p1", 2, 2, 50_000_000L, "shared-branch"),
						impact("p2", 2, 2, 50_000_000L, "shared-branch")));

		assertSubjectDecision(decision, 0, AccountTrustStatus.UNVERIFIED);
	}

	@Test
	public void testPermissiveProfileAllowsLowConfidenceSuspiciousEvidence() throws Exception {
		loadPermissiveProfile();

		AccountTrustPolicy.LevelDecision decision = AccountTrustPolicy.decideLevel(AccountRatingCategory.SUBJECT,
				-12_000_000L, Arrays.asList(
						impact("n1", 3, -1, -6_000_000L),
						impact("n2", 3, -1, -6_000_000L)));

		assertSubjectDecision(decision, -1, AccountTrustStatus.SUSPICIOUS);
		assertEquals(-10_000_000L, decision.getLevelScore());
		assertEquals(5_000_000L, decision.getLevelScoreCap());
	}

	@Test
	public void testCurrentProfileRequiresMediumConfidenceSuspiciousEvidence() {
		AccountTrustPolicy.LevelDecision lowConfidenceDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -12_000_000L, Arrays.asList(
						impact("n1", 3, -1, -6_000_000L),
						impact("n2", 3, -1, -6_000_000L)));
		assertSubjectDecision(lowConfidenceDecision, 0, AccountTrustStatus.UNVERIFIED);

		AccountTrustPolicy.LevelDecision mediumConfidenceDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -12_000_000L, Arrays.asList(
						impact("n1", 3, -2, -6_000_000L),
						impact("n2", 3, -2, -6_000_000L)));
		assertSubjectDecision(mediumConfidenceDecision, -1, AccountTrustStatus.SUSPICIOUS);
		assertEquals(-10_000_000L, mediumConfidenceDecision.getLevelScore());
		assertEquals(5_000_000L, mediumConfidenceDecision.getLevelScoreCap());
	}

	@Test
	public void testStrictProfileRequiresThreeHighConfidenceSuspiciousBranches() throws Exception {
		loadStrictProfile();

		AccountTrustPolicy.LevelDecision twoRaterDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -12_000_000L, Arrays.asList(
						impact("n1", 3, -2, -6_000_000L),
						impact("n2", 3, -2, -6_000_000L)));
		assertSubjectDecision(twoRaterDecision, 0, AccountTrustStatus.UNVERIFIED);

		AccountTrustPolicy.LevelDecision threeMediumConfidenceDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -18_000_000L, Arrays.asList(
						impact("n1", 3, -2, -6_000_000L),
						impact("n2", 3, -2, -6_000_000L),
						impact("n3", 3, -2, -6_000_000L)));
		assertSubjectDecision(threeMediumConfidenceDecision, 0, AccountTrustStatus.UNVERIFIED);

		AccountTrustPolicy.LevelDecision threeRaterDecision = AccountTrustPolicy.decideLevel(
				AccountRatingCategory.SUBJECT, -18_000_000L, Arrays.asList(
						impact("n1", 3, -3, -6_000_000L),
						impact("n2", 3, -3, -6_000_000L),
						impact("n3", 3, -3, -6_000_000L)));
		assertSubjectDecision(threeRaterDecision, -1, AccountTrustStatus.SUSPICIOUS);
		assertEquals(-15_000_000L, threeRaterDecision.getLevelScore());
		assertEquals(5_000_000L, threeRaterDecision.getLevelScoreCap());
	}

	private static void loadPermissiveProfile() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"{ \"level\": 1, \"threshold\": 10000000, \"cap\": 5000000 }",
				"{ \"level\": 1, \"threshold\": 8000000, \"cap\": 5000000 }");
		config = replaceRequired(config,
				"\"suspiciousMinRatingConfidence\": 2",
				"\"suspiciousMinRatingConfidence\": 1");
		loadTemporaryConfig(config);
	}

	private static void loadStrictProfile() throws Exception {
		String config = replaceRequired(loadDefaultTestChainConfig(),
				"\"positiveMinBranchCount\": 2",
				"\"positiveMinBranchCount\": 3");
		config = replaceRequired(config,
				"\"suspiciousMinRaterCount\": 2",
				"\"suspiciousMinRaterCount\": 3");
		config = replaceRequired(config,
				"\"suspiciousMinBranchCount\": 2",
				"\"suspiciousMinBranchCount\": 3");
		config = replaceRequired(config,
				"\"suspiciousMinRatingConfidence\": 2",
				"\"suspiciousMinRatingConfidence\": 3");
		loadTemporaryConfig(config);
	}

	private static void assertSubjectDecision(AccountTrustPolicy.LevelDecision decision, int expectedLevel,
			AccountTrustStatus expectedStatus) {
		assertEquals(expectedLevel, decision.getLevel());
		assertEquals(expectedStatus, AccountTrustPolicy.mapLevelToStatus(decision.getLevel()));
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

	private static void loadTemporaryConfig(String config) throws Exception {
		Path tempDir = Files.createTempDirectory("account-trust-policy-calibration");
		Path configPath = tempDir.resolve("blockchain.json");

		Files.writeString(configPath, config, StandardCharsets.UTF_8);
		BlockChain.fileInstance(tempDir.toString() + java.io.File.separator, configPath.getFileName().toString());
		Files.deleteIfExists(configPath);
		Files.deleteIfExists(tempDir);
	}
}
