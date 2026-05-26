package org.qortium.test.account;

import org.junit.Test;
import org.qortium.block.AccountTrustCategoryPolicyCodec;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryPoliciesData;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AccountTrustCategoryPolicyCodecTests {

	private static final int SUSPICIOUS_MIN_RATER_COUNT = 2;

	@Test
	public void testEncodeDecodeRoundTrip() {
		AccountTrustCategoryPoliciesData policies = samplePolicies();

		byte[] encoded = AccountTrustCategoryPolicyCodec.encode(policies, SUSPICIOUS_MIN_RATER_COUNT);
		AccountTrustCategoryPoliciesData decoded = AccountTrustCategoryPolicyCodec.decode(encoded);

		assertEquals(AccountTrustCategoryPolicyCodec.ENCODED_LENGTH, encoded.length);
		assertTrue(AccountTrustCategoryPolicyCodec.isValid(encoded, SUSPICIOUS_MIN_RATER_COUNT));
		assertPoliciesEqual(policies, decoded);
	}

	@Test
	public void testEncodedOrderMatchesCanonicalLayout() {
		byte[] encoded = AccountTrustCategoryPolicyCodec.encode(samplePolicies(), SUSPICIOUS_MIN_RATER_COUNT);
		long[] values = decodeLongs(encoded);

		assertEquals(AccountTrustCategoryPolicyCodec.ENCODED_LONG_COUNT, values.length);
		assertEquals(1_000L, values[0]);
		assertEquals(100L, values[1]);
		assertEquals(4_000L, values[6]);
		assertEquals(400L, values[7]);
		assertEquals(-500L, values[8]);
		assertEquals(250L, values[9]);
		assertEquals(5_000L, values[10]);
		assertEquals(500L, values[11]);
		assertEquals(-800L, values[16]);
		assertEquals(400L, values[17]);
		assertEquals(9_000L, values[18]);
		assertEquals(900L, values[19]);
		assertEquals(-1_200L, values[22]);
		assertEquals(600L, values[23]);
		assertEquals(13_000L, values[24]);
		assertEquals(1_300L, values[25]);
		assertEquals(-1_600L, values[28]);
		assertEquals(800L, values[29]);
	}

	@Test
	public void testInvalidByteValuesAreRejected() {
		assertFalse(AccountTrustCategoryPolicyCodec.isValid(null, SUSPICIOUS_MIN_RATER_COUNT));
		assertFalse(AccountTrustCategoryPolicyCodec.isValid(new byte[0], SUSPICIOUS_MIN_RATER_COUNT));
		assertFalse(AccountTrustCategoryPolicyCodec.isValid(
				new byte[AccountTrustCategoryPolicyCodec.ENCODED_LENGTH - 1], SUSPICIOUS_MIN_RATER_COUNT));
		assertFalse(AccountTrustCategoryPolicyCodec.isValid(
				new byte[AccountTrustCategoryPolicyCodec.ENCODED_LENGTH + 1], SUSPICIOUS_MIN_RATER_COUNT));

		assertDecodeFails(null);
		assertDecodeFails(new byte[AccountTrustCategoryPolicyCodec.ENCODED_LENGTH - 1]);
	}

	@Test
	public void testInvalidCategoryAndLevelShapesAreRejected() {
		assertInvalidPolicy(policies(player(), trainer(), manager()));
		assertInvalidPolicy(policies(subject(), subject(), player(), trainer(), manager()));
		assertInvalidPolicy(policies(category(null, -500L, 250L, level(1, 1_000L, 100L)), player(), trainer(),
				manager()));

		assertInvalidPolicy(policies(subject(
				level(1, 1_000L, 100L),
				level(2, 2_000L, 200L),
				level(3, 3_000L, 300L)), player(), trainer(), manager()));
		assertInvalidPolicy(policies(subject(
				level(1, 1_000L, 100L),
				level(1, 1_500L, 150L),
				level(2, 2_000L, 200L),
				level(3, 3_000L, 300L),
				level(4, 4_000L, 400L)), player(), trainer(), manager()));
		assertInvalidPolicy(policies(subject(
				level(1, 1_000L, 100L),
				level(2, 2_000L, 200L),
				level(3, 3_000L, 300L),
				level(4, 4_000L, 400L),
				level(5, 5_000L, 500L)), player(), trainer(), manager()));
	}

	@Test
	public void testInvalidThresholdAndCapValuesAreRejected() {
		assertInvalidPolicy(policies(subjectWithFirstLevel(level(1, 0L, 100L)), player(), trainer(), manager()));
		assertInvalidPolicy(policies(subjectWithFirstLevel(level(1, 1_000L, 0L)), player(), trainer(), manager()));
		assertInvalidPolicy(policies(subjectWithFirstLevel(level(1, 1_000L, 1_000L)), player(), trainer(), manager()));
	}

	@Test
	public void testInvalidSuspiciousValuesAreRejected() {
		assertInvalidPolicy(policies(category(AccountRatingCategory.SUBJECT, 0L, 250L, subjectLevels()), player(),
				trainer(), manager()));
		assertInvalidPolicy(policies(category(AccountRatingCategory.SUBJECT, -500L, 0L, subjectLevels()), player(),
				trainer(), manager()));
		assertInvalidPolicy(policies(category(AccountRatingCategory.SUBJECT, -500L, 500L, subjectLevels()), player(),
				trainer(), manager()));
		assertInvalidPolicy(policies(category(AccountRatingCategory.SUBJECT, -501L, 250L, subjectLevels()), player(),
				trainer(), manager()));

		assertInvalidPolicy(samplePolicies(), 0);
		assertFalse(AccountTrustCategoryPolicyCodec.isValid(
				AccountTrustCategoryPolicyCodec.encode(samplePolicies(), SUSPICIOUS_MIN_RATER_COUNT), 0));
	}

	private static void assertDecodeFails(byte[] value) {
		try {
			AccountTrustCategoryPolicyCodec.decode(value);
			fail("Expected account trust category policy decode to fail");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	private static void assertInvalidPolicy(AccountTrustCategoryPoliciesData policies) {
		assertInvalidPolicy(policies, SUSPICIOUS_MIN_RATER_COUNT);
	}

	private static void assertInvalidPolicy(AccountTrustCategoryPoliciesData policies, int suspiciousMinRaterCount) {
		try {
			AccountTrustCategoryPolicyCodec.validate(policies, suspiciousMinRaterCount);
			fail("Expected account trust category policy validation to fail");
		} catch (IllegalArgumentException e) {
			// Expected
		}

		try {
			AccountTrustCategoryPolicyCodec.encode(policies, suspiciousMinRaterCount);
			fail("Expected account trust category policy encoding to fail");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	private static void assertPoliciesEqual(AccountTrustCategoryPoliciesData expected,
			AccountTrustCategoryPoliciesData actual) {
		assertEquals(expected.getCategoryPolicies().size(), actual.getCategoryPolicies().size());

		for (int i = 0; i < expected.getCategoryPolicies().size(); ++i) {
			AccountTrustCategoryPoliciesData.CategoryPolicy expectedCategory = expected.getCategoryPolicies().get(i);
			AccountTrustCategoryPoliciesData.CategoryPolicy actualCategory = actual.getCategoryPolicies().get(i);

			assertEquals(expectedCategory.getCategory(), actualCategory.getCategory());
			assertEquals(expectedCategory.getSuspiciousThreshold(), actualCategory.getSuspiciousThreshold());
			assertEquals(expectedCategory.getSuspiciousLevelScoreCap(), actualCategory.getSuspiciousLevelScoreCap());
			assertEquals(expectedCategory.getLevels().size(), actualCategory.getLevels().size());

			for (int j = 0; j < expectedCategory.getLevels().size(); ++j) {
				AccountTrustCategoryPoliciesData.LevelPolicy expectedLevel = expectedCategory.getLevels().get(j);
				AccountTrustCategoryPoliciesData.LevelPolicy actualLevel = actualCategory.getLevels().get(j);

				assertEquals(expectedLevel.getLevel(), actualLevel.getLevel());
				assertEquals(expectedLevel.getThreshold(), actualLevel.getThreshold());
				assertEquals(expectedLevel.getLevelScoreCap(), actualLevel.getLevelScoreCap());
			}
		}
	}

	private static long[] decodeLongs(byte[] encoded) {
		ByteBuffer byteBuffer = ByteBuffer.wrap(encoded);
		long[] values = new long[encoded.length / Long.BYTES];
		for (int i = 0; i < values.length; ++i)
			values[i] = byteBuffer.getLong();

		return values;
	}

	private static AccountTrustCategoryPoliciesData samplePolicies() {
		return policies(subject(), player(), trainer(), manager());
	}

	private static AccountTrustCategoryPoliciesData policies(
			AccountTrustCategoryPoliciesData.CategoryPolicy... categoryPolicies) {
		return new AccountTrustCategoryPoliciesData(Arrays.asList(categoryPolicies));
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy subject() {
		return category(AccountRatingCategory.SUBJECT, -500L, 250L, subjectLevels());
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy subject(
			AccountTrustCategoryPoliciesData.LevelPolicy... levels) {
		return category(AccountRatingCategory.SUBJECT, -500L, 250L, levels);
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy subjectWithFirstLevel(
			AccountTrustCategoryPoliciesData.LevelPolicy firstLevel) {
		return subject(firstLevel, level(2, 2_000L, 200L), level(3, 3_000L, 300L), level(4, 4_000L, 400L));
	}

	private static AccountTrustCategoryPoliciesData.LevelPolicy[] subjectLevels() {
		return new AccountTrustCategoryPoliciesData.LevelPolicy[] {
				level(1, 1_000L, 100L),
				level(2, 2_000L, 200L),
				level(3, 3_000L, 300L),
				level(4, 4_000L, 400L)
		};
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy player() {
		return category(AccountRatingCategory.PLAYER, -800L, 400L,
				level(1, 5_000L, 500L),
				level(2, 6_000L, 600L),
				level(3, 7_000L, 700L));
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy trainer() {
		return category(AccountRatingCategory.TRAINER, -1_200L, 600L,
				level(1, 9_000L, 900L),
				level(2, 10_000L, 1_000L));
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy manager() {
		return category(AccountRatingCategory.MANAGER, -1_600L, 800L,
				level(1, 13_000L, 1_300L),
				level(2, 14_000L, 1_400L));
	}

	private static AccountTrustCategoryPoliciesData.CategoryPolicy category(AccountRatingCategory category,
			long suspiciousThreshold, long suspiciousLevelScoreCap,
			AccountTrustCategoryPoliciesData.LevelPolicy... levels) {
		return new AccountTrustCategoryPoliciesData.CategoryPolicy(category, Arrays.asList(levels),
				suspiciousThreshold, suspiciousLevelScoreCap);
	}

	private static AccountTrustCategoryPoliciesData.LevelPolicy level(int level, long threshold, long levelScoreCap) {
		return new AccountTrustCategoryPoliciesData.LevelPolicy(level, threshold, levelScoreCap);
	}
}
