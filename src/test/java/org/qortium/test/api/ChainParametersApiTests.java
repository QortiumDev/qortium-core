package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.model.AccountRatingCooldownUpdateRequest;
import org.qortium.api.model.AccountTrustCategoryPoliciesUpdateRequest;
import org.qortium.api.model.AccountTrustManagerEnergyHopsUpdateRequest;
import org.qortium.api.model.AccountTrustPositiveMinBranchCountUpdateRequest;
import org.qortium.api.model.AccountTrustStartingEnergyUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinBranchCountUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinRaterCountUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinRatingConfidenceUpdateRequest;
import org.qortium.api.model.BlockRewardUpdateRequest;
import org.qortium.api.model.ChainParameterEffectiveValue;
import org.qortium.api.model.ChainParameterMetadata;
import org.qortium.api.model.ChainParameterUpdateSummary;
import org.qortium.api.model.IntegerChainParameterUpdateRequest;
import org.qortium.api.model.NameRegistrationUnitFeeUpdateRequest;
import org.qortium.api.model.RewardShareWeightsUpdateRequest;
import org.qortium.api.model.TrustStatusVoteWeightsUpdateRequest;
import org.qortium.api.model.UnitFeeUpdateRequest;
import org.qortium.api.resource.ChainParametersResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.block.AccountTrustCategoryPolicyCodec;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryPoliciesData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChainParametersApiTests extends ApiCommon {

	private ChainParametersResource chainParametersResource;

	@Before
	public void buildResource() {
		this.chainParametersResource = (ChainParametersResource) ApiCommon.buildResource(ChainParametersResource.class);
	}

	@Test
	public void testChainParameterMetadataListsBlockReward() {
		List<ChainParameterMetadata> parameters = this.chainParametersResource.getChainParameters();

		assertEquals(14, parameters.size());

		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.BLOCK_REWARD), ChainParameter.BLOCK_REWARD);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN),
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.UNIT_FEE), ChainParameter.UNIT_FEE);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.NAME_REGISTRATION_UNIT_FEE),
				ChainParameter.NAME_REGISTRATION_UNIT_FEE);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.REWARD_SHARE_WEIGHTS),
				ChainParameter.REWARD_SHARE_WEIGHTS);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS),
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS),
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY),
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS),
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT),
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE);
		assertMetadataMatchesParameter(findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES),
				ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES);

		assertEquals(Long.valueOf(0L), findMetadata(parameters, ChainParameter.BLOCK_REWARD).validation.minimumLongValue);
		assertEquals(Long.valueOf(0L), findMetadata(parameters, ChainParameter.UNIT_FEE).validation.minimumLongValue);
		assertEquals(Long.valueOf(0L),
				findMetadata(parameters, ChainParameter.NAME_REGISTRATION_UNIT_FEE).validation.minimumLongValue);
		assertEquals(Long.valueOf(1L),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY).validation.minimumLongValue);
		assertEquals(Integer.valueOf(0),
				findMetadata(parameters, ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(0),
				findMetadata(parameters, ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(1),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(1),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(1),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(0),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(1),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE).validation.minimumIntegerValue);
		assertEquals(Integer.valueOf(4),
				findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE)
						.validation.maximumIntegerValue);

		ChainParameterMetadata rewardWeights = findMetadata(parameters, ChainParameter.REWARD_SHARE_WEIGHTS);
		assertEquals(Integer.valueOf(10), rewardWeights.validation.integerListLength);
		assertArrayEquals(new String[] {
				"Level 1", "Level 2", "Level 3", "Level 4", "Level 5",
				"Level 6", "Level 7", "Level 8", "Level 9", "Level 10"
		}, rewardWeights.validation.integerListLabels);
		assertTrue(rewardWeights.validation.requiresPositiveTotal);
		assertTrue(rewardWeights.validation.requiresPositiveFirstValue);
		assertFalse(rewardWeights.validation.requiresAnyPositiveValue);

		ChainParameterMetadata trustVoteWeights = findMetadata(parameters, ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS);
		assertEquals(Integer.valueOf(5), trustVoteWeights.validation.integerListLength);
		assertEquals(Integer.valueOf(100), trustVoteWeights.validation.maximumIntegerListValue);
		assertArrayEquals(new String[] { "SUSPICIOUS", "UNVERIFIED", "BRONZE", "SILVER", "GOLD" },
				trustVoteWeights.validation.integerListLabels);
		assertFalse(trustVoteWeights.validation.requiresPositiveTotal);
		assertFalse(trustVoteWeights.validation.requiresPositiveFirstValue);
		assertTrue(trustVoteWeights.validation.requiresAnyPositiveValue);
	}

	@Test
	public void testBuildBlockRewardUpdateUsesAmountValue() throws DataException, TransformationException {
		long reward = 12L * Amounts.MULTIPLIER + 34_000_000L;
		BlockRewardUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildBlockRewardUpdateRequest(repository, reward);
		}

		String rawTransaction = this.chainParametersResource.updateBlockReward(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.BLOCK_REWARD.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.BLOCK_REWARD.encodeLongValue(reward), transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildRewardShareWeightsUpdateUsesIntegerListValue() throws DataException, TransformationException {
		int[] weights = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
		RewardShareWeightsUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildRewardShareWeightsUpdateRequest(repository, weights);
		}

		String rawTransaction = this.chainParametersResource.updateRewardShareWeights(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.REWARD_SHARE_WEIGHTS.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(weights), transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildTrustStatusVoteWeightsUpdateUsesIntegerListValue()
			throws DataException, TransformationException {
		int[] weights = new int[] { 0, 5, 45, 75, 100 };
		TrustStatusVoteWeightsUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildTrustStatusVoteWeightsUpdateRequest(repository, weights);
		}

		String rawTransaction = this.chainParametersResource.updateTrustStatusVoteWeights(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(weights),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustStartingEnergyUpdateUsesLongValue() throws DataException, TransformationException {
		long startingEnergy = 1_234_567L;
		AccountTrustStartingEnergyUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustStartingEnergyUpdateRequest(repository, startingEnergy);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustStartingEnergy(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(startingEnergy),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustManagerEnergyHopsUpdateUsesIntegerValue()
			throws DataException, TransformationException {
		int managerEnergyHops = 5;
		AccountTrustManagerEnergyHopsUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustManagerEnergyHopsUpdateRequest(repository, managerEnergyHops);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustManagerEnergyHops(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(managerEnergyHops),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustPositiveMinBranchCountUpdateUsesIntegerValue()
			throws DataException, TransformationException {
		int positiveMinBranchCount = 3;
		AccountTrustPositiveMinBranchCountUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustPositiveMinBranchCountUpdateRequest(repository, positiveMinBranchCount);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustPositiveMinBranchCount(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(positiveMinBranchCount),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinRaterCountUpdateUsesIntegerValue()
			throws DataException, TransformationException {
		int suspiciousMinRaterCount = 3;
		AccountTrustSuspiciousMinRaterCountUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustSuspiciousMinRaterCountUpdateRequest(repository, suspiciousMinRaterCount);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustSuspiciousMinRaterCount(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(suspiciousMinRaterCount),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinBranchCountUpdateUsesIntegerValue()
			throws DataException, TransformationException {
		int suspiciousMinBranchCount = 0;
		AccountTrustSuspiciousMinBranchCountUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustSuspiciousMinBranchCountUpdateRequest(repository, suspiciousMinBranchCount);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustSuspiciousMinBranchCount(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(suspiciousMinBranchCount),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinRatingConfidenceUpdateUsesIntegerValue()
			throws DataException, TransformationException {
		int suspiciousMinRatingConfidence = 3;
		AccountTrustSuspiciousMinRatingConfidenceUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustSuspiciousMinRatingConfidenceUpdateRequest(repository, suspiciousMinRatingConfidence);
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustSuspiciousMinRatingConfidence(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
				suspiciousMinRatingConfidence), transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountTrustCategoryPoliciesUpdateUsesStructuredValue()
			throws DataException, TransformationException {
		AccountTrustCategoryPoliciesUpdateRequest request;
		byte[] expectedValue;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustCategoryPoliciesUpdateRequest(repository,
					buildCategoryPoliciesWithLevelThreshold(AccountRatingCategory.SUBJECT, 1,
							BlockChain.getInstance().getAccountTrustSettings()
									.getLevelThreshold(AccountRatingCategory.SUBJECT, 1) + 1_000_000L));
			expectedValue = AccountTrustCategoryPolicyCodec.encode(request.categoryPolicies,
					BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(
							repository, request.activationHeight));
		}

		String rawTransaction = this.chainParametersResource.updateAccountTrustCategoryPolicies(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(expectedValue, transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildMinAccountsToActivateShareBinUpdateUsesIntegerValue() throws DataException, TransformationException {
		IntegerChainParameterUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildMinAccountsToActivateShareBinUpdateRequest(repository, 12);
		}

		String rawTransaction = this.chainParametersResource.updateMinAccountsToActivateShareBin(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(request.value), transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildAccountRatingCooldownUpdateUsesCooldownBlocksValue() throws DataException, TransformationException {
		AccountRatingCooldownUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountRatingCooldownUpdateRequest(repository, 720);
		}

		String rawTransaction = this.chainParametersResource.updateAccountRatingCooldown(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.encodeIntValue(request.cooldownBlocks),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildUnitFeeUpdateUsesAmountValue() throws DataException, TransformationException {
		long unitFee = 1_234_567L;
		UnitFeeUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildUnitFeeUpdateRequest(repository, unitFee);
		}

		String rawTransaction = this.chainParametersResource.updateUnitFee(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.UNIT_FEE.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.UNIT_FEE.encodeLongValue(unitFee), transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testBuildNameRegistrationUnitFeeUpdateUsesAmountValue() throws DataException, TransformationException {
		long nameRegistrationUnitFee = 125L * Amounts.MULTIPLIER;
		NameRegistrationUnitFeeUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildNameRegistrationUnitFeeUpdateRequest(repository, nameRegistrationUnitFee);
		}

		String rawTransaction = this.chainParametersResource.updateNameRegistrationUnitFee(request);
		ChainParameterUpdateTransactionData transactionData = decodeRawTransaction(rawTransaction);

		assertEquals(ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, transactionData.getParameterId());
		assertEquals(request.activationHeight, transactionData.getActivationHeight());
		assertEquals(request.timestamp, transactionData.getTimestamp());
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, transactionData.getTxGroupId());
		assertArrayEquals(request.updaterPublicKey, transactionData.getUpdaterPublicKey());
		assertArrayEquals(ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(nameRegistrationUnitFee),
				transactionData.getValue());
		assertNotNull(transactionData.getFee());
	}

	@Test
	public void testGetMinAccountsToActivateShareBinReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, height),
					this.chainParametersResource.getMinAccountsToActivateShareBin(height));
		}
	}

	@Test
	public void testGetUnitFeeReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getUnitFeeAtHeight(repository, height, System.currentTimeMillis()),
					this.chainParametersResource.getUnitFee(height));
		}
	}

	@Test
	public void testGetNameRegistrationUnitFeeReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, height, System.currentTimeMillis()),
					this.chainParametersResource.getNameRegistrationUnitFee(height));
		}
	}

	@Test
	public void testGetRewardShareWeightsReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertArrayEquals(BlockChain.getInstance().getRewardShareWeights(repository, height),
					this.chainParametersResource.getRewardShareWeights(height));
		}
	}

	@Test
	public void testGetAccountRatingCooldownReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, height),
					this.chainParametersResource.getAccountRatingCooldown(height));
		}
	}

	@Test
	public void testGetTrustStatusVoteWeightsReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertArrayEquals(BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, height),
					this.chainParametersResource.getTrustStatusVoteWeights(height));
		}
	}

	@Test
	public void testGetAccountTrustStartingEnergyReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustStartingEnergy(repository, height),
					this.chainParametersResource.getAccountTrustStartingEnergy(height));
		}
	}

	@Test
	public void testGetAccountTrustManagerEnergyHopsReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, height),
					this.chainParametersResource.getAccountTrustManagerEnergyHops(height));
		}
	}

	@Test
	public void testGetAccountTrustPositiveMinBranchCountReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, height),
					this.chainParametersResource.getAccountTrustPositiveMinBranchCount(height));
		}
	}

	@Test
	public void testGetAccountTrustSuspiciousMinRaterCountReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, height),
					this.chainParametersResource.getAccountTrustSuspiciousMinRaterCount(height));
		}
	}

	@Test
	public void testGetAccountTrustSuspiciousMinBranchCountReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, height),
					this.chainParametersResource.getAccountTrustSuspiciousMinBranchCount(height));
		}
	}

	@Test
	public void testGetAccountTrustSuspiciousMinRatingConfidenceReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertEquals(BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, height),
					this.chainParametersResource.getAccountTrustSuspiciousMinRatingConfidence(height));
		}
	}

	@Test
	public void testGetAccountTrustCategoryPoliciesReturnsEffectiveValue() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = repository.getBlockRepository().getBlockchainHeight();
			AccountTrustCategoryPoliciesData categoryPolicies =
					this.chainParametersResource.getAccountTrustCategoryPolicies(height);

			assertAccountTrustCategoryPoliciesEqual(
					AccountTrustCategoryPolicyCodec.decode(
							BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, height)),
					categoryPolicies);
		}
	}

	@Test
	public void testEffectiveParameterValuesReturnConfigSourcesWithoutProposals() throws DataException {
		int height;
		long fallbackTimestamp;

		try (final Repository repository = RepositoryManager.getRepository()) {
			height = repository.getBlockRepository().getBlockchainHeight();
			fallbackTimestamp = repository.getBlockRepository().fromHeight(height).getTimestamp();
		}

		List<ChainParameterEffectiveValue> values = this.chainParametersResource.getEffectiveParameterValues(null);

		assertEquals(14, values.size());
		assertConfigEffectiveValue(values, ChainParameter.BLOCK_REWARD, height,
				ChainParameter.BLOCK_REWARD.encodeLongValue(BlockChain.getInstance().getRewardAtHeight(height)));
		assertConfigEffectiveValue(values, ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN, height,
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(
						BlockChain.getInstance().getMinAccountsToActivateShareBin()));
		assertConfigEffectiveValue(values, ChainParameter.UNIT_FEE, height,
				ChainParameter.UNIT_FEE.encodeLongValue(BlockChain.getInstance().getUnitFeeAtTimestamp(fallbackTimestamp)));
		assertConfigEffectiveValue(values, ChainParameter.NAME_REGISTRATION_UNIT_FEE, height,
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(
						BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(fallbackTimestamp)));
		assertConfigEffectiveValue(values, ChainParameter.REWARD_SHARE_WEIGHTS, height,
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(BlockChain.getInstance().getRewardShareWeights()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS, height,
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.encodeIntValue(
						BlockChain.getInstance().getAccountRatingChangeCooldownBlocks()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS, height,
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(
						BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY, height,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(
						BlockChain.getInstance().getAccountTrustStartingEnergy()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS, height,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(
						BlockChain.getInstance().getAccountTrustManagerEnergyHops()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT, height,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(
						BlockChain.getInstance().getAccountTrustPositiveMinBranchCount()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT, height,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(
						BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT, height,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(
						BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE, height,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
						BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence()));
		assertConfigEffectiveValue(values, ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES, height,
				BlockChain.getInstance().getAccountTrustCategoryPoliciesValue());
	}

	@Test
	public void testEffectiveParameterValuesShowScheduledAndActiveOnChainOverlay() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		int scheduledLookupHeight;
		int activationHeight;
		long updatedReward = 4L * Amounts.MULTIPLIER;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			activationHeight = getActivationHeightSafelyAfterApproval(repository, 20);
			transactionData = buildBlockRewardUpdateTransaction(repository, alice, activationHeight, updatedReward);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
			BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));

			scheduledLookupHeight = repository.getBlockRepository().getBlockchainHeight();
			assertTrue(scheduledLookupHeight < activationHeight);
		}

		List<ChainParameterEffectiveValue> scheduledValues = this.chainParametersResource
				.getEffectiveParameterValues(scheduledLookupHeight);
		ChainParameterEffectiveValue scheduledReward = findEffectiveValue(scheduledValues, ChainParameter.BLOCK_REWARD);

		assertEquals(ChainParameterEffectiveValue.Source.CONFIG, scheduledReward.source);
		assertNull(scheduledReward.signature);
		assertNull(scheduledReward.activationHeight);
		assertCurrentDecodedValue(scheduledReward, ChainParameter.BLOCK_REWARD,
				ChainParameter.BLOCK_REWARD.encodeLongValue(BlockChain.getInstance().getRewardAtHeight(scheduledLookupHeight)));
		assertArrayEquals(transactionData.getSignature(), scheduledReward.nextSignature);
		assertEquals(Integer.valueOf(activationHeight), scheduledReward.nextActivationHeight);
		assertNextDecodedValue(scheduledReward, ChainParameter.BLOCK_REWARD,
				ChainParameter.BLOCK_REWARD.encodeLongValue(updatedReward));

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
		}

		List<ChainParameterEffectiveValue> activeValues = this.chainParametersResource
				.getEffectiveParameterValues(activationHeight);
		ChainParameterEffectiveValue activeReward = findEffectiveValue(activeValues, ChainParameter.BLOCK_REWARD);

		assertEquals(ChainParameterEffectiveValue.Source.ON_CHAIN, activeReward.source);
		assertArrayEquals(transactionData.getSignature(), activeReward.signature);
		assertEquals(Integer.valueOf(activationHeight), activeReward.activationHeight);
		assertCurrentDecodedValue(activeReward, ChainParameter.BLOCK_REWARD,
				ChainParameter.BLOCK_REWARD.encodeLongValue(updatedReward));
		assertNull(activeReward.nextSignature);
		assertNull(activeReward.nextActivationHeight);
		assertNull(activeReward.nextValue);
		assertEquals(updatedReward, this.chainParametersResource.getBlockReward(activationHeight));
	}

	@Test
	public void testBuildBlockRewardUpdateRejectsNegativeReward() throws DataException {
		BlockRewardUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildBlockRewardUpdateRequest(repository, -1L);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateBlockReward(request));
	}

	@Test
	public void testBuildMinAccountsToActivateShareBinUpdateRejectsNegativeValue() throws DataException {
		IntegerChainParameterUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildMinAccountsToActivateShareBinUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateMinAccountsToActivateShareBin(request));
	}

	@Test
	public void testBuildAccountRatingCooldownUpdateRejectsNegativeValue() throws DataException {
		AccountRatingCooldownUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountRatingCooldownUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateAccountRatingCooldown(request));
	}

	@Test
	public void testBuildUnitFeeUpdateRejectsNegativeValue() throws DataException {
		UnitFeeUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildUnitFeeUpdateRequest(repository, -1L);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateUnitFee(request));
	}

	@Test
	public void testBuildNameRegistrationUnitFeeUpdateRejectsNegativeValue() throws DataException {
		NameRegistrationUnitFeeUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildNameRegistrationUnitFeeUpdateRequest(repository, -1L);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateNameRegistrationUnitFee(request));
	}

	@Test
	public void testBuildRewardShareWeightsUpdateRejectsInvalidWeights() throws DataException {
		RewardShareWeightsUpdateRequest shortRequest;
		RewardShareWeightsUpdateRequest negativeRequest;
		RewardShareWeightsUpdateRequest zeroLevelOneRequest;
		RewardShareWeightsUpdateRequest zeroRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			shortRequest = buildRewardShareWeightsUpdateRequest(repository, new int[] { 1, 2, 3 });
			negativeRequest = buildRewardShareWeightsUpdateRequest(repository,
					new int[] { 1, 2, 3, 4, 5, -6, 7, 8, 9, 10 });
			zeroLevelOneRequest = buildRewardShareWeightsUpdateRequest(repository,
					new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 });
			zeroRequest = buildRewardShareWeightsUpdateRequest(repository,
					new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateRewardShareWeights(shortRequest));
		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateRewardShareWeights(negativeRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateRewardShareWeights(zeroLevelOneRequest));
		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateRewardShareWeights(zeroRequest));
	}

	@Test
	public void testBuildTrustStatusVoteWeightsUpdateRejectsInvalidWeights() throws DataException {
		TrustStatusVoteWeightsUpdateRequest shortRequest;
		TrustStatusVoteWeightsUpdateRequest negativeRequest;
		TrustStatusVoteWeightsUpdateRequest excessiveRequest;
		TrustStatusVoteWeightsUpdateRequest zeroRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			shortRequest = buildTrustStatusVoteWeightsUpdateRequest(repository, new int[] { 0, 40, 70 });
			negativeRequest = buildTrustStatusVoteWeightsUpdateRequest(repository, new int[] { 0, -1, 40, 70, 100 });
			excessiveRequest = buildTrustStatusVoteWeightsUpdateRequest(repository, new int[] { 0, 10, 40, 70, 101 });
			zeroRequest = buildTrustStatusVoteWeightsUpdateRequest(repository, new int[] { 0, 0, 0, 0, 0 });
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateTrustStatusVoteWeights(shortRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateTrustStatusVoteWeights(negativeRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateTrustStatusVoteWeights(excessiveRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateTrustStatusVoteWeights(zeroRequest));
	}

	@Test
	public void testBuildAccountTrustStartingEnergyUpdateRejectsNonPositiveValue() throws DataException {
		AccountTrustStartingEnergyUpdateRequest zeroRequest;
		AccountTrustStartingEnergyUpdateRequest negativeRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			zeroRequest = buildAccountTrustStartingEnergyUpdateRequest(repository, 0L);
			negativeRequest = buildAccountTrustStartingEnergyUpdateRequest(repository, -1L);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustStartingEnergy(zeroRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustStartingEnergy(negativeRequest));
	}

	@Test
	public void testBuildAccountTrustManagerEnergyHopsUpdateRejectsNonPositiveValue() throws DataException {
		AccountTrustManagerEnergyHopsUpdateRequest zeroRequest;
		AccountTrustManagerEnergyHopsUpdateRequest negativeRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			zeroRequest = buildAccountTrustManagerEnergyHopsUpdateRequest(repository, 0);
			negativeRequest = buildAccountTrustManagerEnergyHopsUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustManagerEnergyHops(zeroRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustManagerEnergyHops(negativeRequest));
	}

	@Test
	public void testBuildAccountTrustPositiveMinBranchCountUpdateRejectsNonPositiveValue() throws DataException {
		AccountTrustPositiveMinBranchCountUpdateRequest zeroRequest;
		AccountTrustPositiveMinBranchCountUpdateRequest negativeRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			zeroRequest = buildAccountTrustPositiveMinBranchCountUpdateRequest(repository, 0);
			negativeRequest = buildAccountTrustPositiveMinBranchCountUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustPositiveMinBranchCount(zeroRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustPositiveMinBranchCount(negativeRequest));
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinRaterCountUpdateRejectsNonPositiveValue() throws DataException {
		AccountTrustSuspiciousMinRaterCountUpdateRequest zeroRequest;
		AccountTrustSuspiciousMinRaterCountUpdateRequest negativeRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			zeroRequest = buildAccountTrustSuspiciousMinRaterCountUpdateRequest(repository, 0);
			negativeRequest = buildAccountTrustSuspiciousMinRaterCountUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRaterCount(zeroRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRaterCount(negativeRequest));
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinRaterCountUpdateRejectsPolicyIncompatibleValue()
			throws DataException {
		AccountTrustSuspiciousMinRaterCountUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustSuspiciousMinRaterCountUpdateRequest(repository, 1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRaterCount(request));
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinBranchCountUpdateRejectsNegativeValue() throws DataException {
		AccountTrustSuspiciousMinBranchCountUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustSuspiciousMinBranchCountUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinBranchCount(request));
	}

	@Test
	public void testBuildAccountTrustSuspiciousMinRatingConfidenceUpdateRejectsOutOfRangeValue() throws DataException {
		AccountTrustSuspiciousMinRatingConfidenceUpdateRequest zeroRequest;
		AccountTrustSuspiciousMinRatingConfidenceUpdateRequest excessiveRequest;
		AccountTrustSuspiciousMinRatingConfidenceUpdateRequest negativeRequest;

		try (final Repository repository = RepositoryManager.getRepository()) {
			zeroRequest = buildAccountTrustSuspiciousMinRatingConfidenceUpdateRequest(repository, 0);
			excessiveRequest = buildAccountTrustSuspiciousMinRatingConfidenceUpdateRequest(repository, 5);
			negativeRequest = buildAccountTrustSuspiciousMinRatingConfidenceUpdateRequest(repository, -1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRatingConfidence(zeroRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRatingConfidence(excessiveRequest));
		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustSuspiciousMinRatingConfidence(negativeRequest));
	}

	@Test
	public void testBuildAccountTrustCategoryPoliciesUpdateRejectsInvalidPolicies() throws DataException {
		AccountTrustCategoryPoliciesUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildAccountTrustCategoryPoliciesUpdateRequest(repository, null);
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chainParametersResource.updateAccountTrustCategoryPolicies(request));
	}

	@Test
	public void testBuildBlockRewardUpdateRejectsTooCloseActivationHeight() throws DataException {
		BlockRewardUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildBlockRewardUpdateRequest(repository, Amounts.MULTIPLIER);
			request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
					+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay();
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateBlockReward(request));
	}

	@Test
	public void testChainParameterUpdatesReturnsEmptyListWithoutProposals() {
		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, null, null, null, null);

		assertTrue(summaries.isEmpty());
	}

	@Test
	public void testPendingBlockRewardProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		long reward = 3L * Amounts.MULTIPLIER;
		ChainParameterUpdateTransactionData transactionData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildBlockRewardUpdateTransaction(repository, alice, activationHeight, reward);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(transactionData.getTimestamp(), summary.timestamp);
		assertNotNull(summary.blockHeight);
		assertEquals(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, summary.txGroupId);
		assertEquals(ChainParameter.BLOCK_REWARD.id, summary.parameterId);
		assertEquals(ChainParameter.BLOCK_REWARD.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("AMOUNT", summary.valueType);
		assertEquals("3.00000000", summary.displayValue);
		assertEquals(Long.valueOf(reward), summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingMinAccountsProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildMinAccountsToActivateShareBinUpdateTransaction(repository, alice, activationHeight, 4);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, summary.parameterId);
		assertEquals(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER", summary.valueType);
		assertEquals("4", summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertEquals(Integer.valueOf(4), summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountRatingCooldownProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildAccountRatingCooldownUpdateTransaction(repository, alice, activationHeight, 720);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER", summary.valueType);
		assertEquals("720", summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertEquals(Integer.valueOf(720), summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingUnitFeeProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		long unitFee = 1_234_567L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildUnitFeeUpdateTransaction(repository, alice, activationHeight, unitFee);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.UNIT_FEE.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.UNIT_FEE.id, summary.parameterId);
		assertEquals(ChainParameter.UNIT_FEE.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("AMOUNT", summary.valueType);
		assertEquals("0.01234567", summary.displayValue);
		assertEquals(Long.valueOf(unitFee), summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingNameRegistrationUnitFeeProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		long nameRegistrationUnitFee = 125L * Amounts.MULTIPLIER;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildNameRegistrationUnitFeeUpdateTransaction(repository, alice, activationHeight, nameRegistrationUnitFee);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, summary.parameterId);
		assertEquals(ChainParameter.NAME_REGISTRATION_UNIT_FEE.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("AMOUNT", summary.valueType);
		assertEquals("125.00000000", summary.displayValue);
		assertEquals(Long.valueOf(nameRegistrationUnitFee), summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingRewardShareWeightsProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		int[] weights = new int[] { 10, 9, 8, 7, 6, 5, 4, 3, 2, 1 };

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildRewardShareWeightsUpdateTransaction(repository, alice, activationHeight, weights);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.REWARD_SHARE_WEIGHTS.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.REWARD_SHARE_WEIGHTS.id, summary.parameterId);
		assertEquals(ChainParameter.REWARD_SHARE_WEIGHTS.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER_LIST", summary.valueType);
		assertEquals(Arrays.toString(weights), summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertArrayEquals(weights, summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingTrustStatusVoteWeightsProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		int[] weights = new int[] { 0, 15, 50, 80, 100 };

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildTrustStatusVoteWeightsUpdateTransaction(repository, alice, activationHeight, weights);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER_LIST", summary.valueType);
		assertEquals(Arrays.toString(weights), summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertArrayEquals(weights, summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountTrustCategoryPoliciesProposalSummaryShowsDecodedValueAndVoteCounts()
			throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		AccountTrustCategoryPoliciesData categoryPolicies = buildCategoryPoliciesWithLevelThreshold(
				AccountRatingCategory.SUBJECT, 1,
				BlockChain.getInstance().getAccountTrustSettings()
						.getLevelThreshold(AccountRatingCategory.SUBJECT, 1) + 1_000_000L);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildAccountTrustCategoryPoliciesUpdateTransaction(repository, alice,
					activationHeight, categoryPolicies);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals(ChainParameter.VALUE_TYPE_ACCOUNT_TRUST_CATEGORY_POLICIES, summary.valueType);
		assertNull(summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertNull(summary.integerValue);
		assertNull(summary.integerValues);
		assertAccountTrustCategoryPoliciesEqual(categoryPolicies, summary.accountTrustCategoryPolicies);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountTrustStartingEnergyProposalSummaryShowsDecodedValueAndVoteCounts() throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		long startingEnergy = 1_234_567L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildAccountTrustStartingEnergyUpdateTransaction(repository, alice, activationHeight, startingEnergy);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("LONG", summary.valueType);
		assertEquals("1234567", summary.displayValue);
		assertNull(summary.amount);
		assertEquals(Long.valueOf(startingEnergy), summary.longValue);
		assertNull(summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountTrustManagerEnergyHopsProposalSummaryShowsDecodedValueAndVoteCounts()
			throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		int managerEnergyHops = 5;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildAccountTrustManagerEnergyHopsUpdateTransaction(repository, alice, activationHeight,
					managerEnergyHops);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER", summary.valueType);
		assertEquals("5", summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertEquals(Integer.valueOf(managerEnergyHops), summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountTrustPositiveMinBranchCountProposalSummaryShowsDecodedValueAndVoteCounts()
			throws DataException {
		ChainParameterUpdateTransactionData transactionData;
		int positiveMinBranchCount = 3;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildAccountTrustPositiveMinBranchCountUpdateTransaction(repository, alice, activationHeight,
					positiveMinBranchCount);
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, null, null, null, null, null, null, null,
				null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, summary.parameterId);
		assertEquals(ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER", summary.valueType);
		assertEquals("3", summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertEquals(Integer.valueOf(positiveMinBranchCount), summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	@Test
	public void testPendingAccountTrustSuspiciousDecisionProposalSummariesShowDecodedValuesAndVoteCounts()
			throws DataException {
		ChainParameterUpdateTransactionData raterCountTransactionData;
		ChainParameterUpdateTransactionData branchCountTransactionData;
		ChainParameterUpdateTransactionData ratingConfidenceTransactionData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			raterCountTransactionData = buildAccountTrustSuspiciousMinRaterCountUpdateTransaction(repository, alice,
					activationHeight, 3);
			TransactionUtils.signAndMint(repository, raterCountTransactionData, alice);

			branchCountTransactionData = buildAccountTrustSuspiciousMinBranchCountUpdateTransaction(repository, alice,
					activationHeight + 1, 0);
			TransactionUtils.signAndMint(repository, branchCountTransactionData, alice);

			ratingConfidenceTransactionData = buildAccountTrustSuspiciousMinRatingConfidenceUpdateTransaction(repository,
					alice, activationHeight + 2, 3);
			TransactionUtils.signAndMint(repository, ratingConfidenceTransactionData, alice);
		}

		assertPendingIntegerSummary(raterCountTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT, 3);
		assertPendingIntegerSummary(branchCountTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT, 0);
		assertPendingIntegerSummary(ratingConfidenceTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE, 3);
	}

	@Test
	public void testChainParameterUpdatesCanIncludeUnconfirmedProposals() throws DataException {
		ChainParameterUpdateTransactionData transactionData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;

			transactionData = buildBlockRewardUpdateTransaction(repository, alice, activationHeight, 6L * Amounts.MULTIPLIER);
			TransactionUtils.signAndImportValid(repository, transactionData, alice);
		}

		List<ChainParameterUpdateSummary> confirmedSummaries = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, null, null, null, null);
		assertTrue(confirmedSummaries.isEmpty());

		List<ChainParameterUpdateSummary> unconfirmedSummaries = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, ConfirmationStatus.UNCONFIRMED, null, null, null);
		assertEquals(1, unconfirmedSummaries.size());
		assertArrayEquals(transactionData.getSignature(), unconfirmedSummaries.get(0).signature);

		List<ChainParameterUpdateSummary> bothSummaries = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, ConfirmationStatus.BOTH, null, null, null);
		assertEquals(1, bothSummaries.size());
		assertArrayEquals(transactionData.getSignature(), bothSummaries.get(0).signature);
	}

	@Test
	public void testChainParameterUpdateFiltersAndApprovedSummary() throws DataException {
		ChainParameterUpdateTransactionData approvedTransactionData;
		ChainParameterUpdateTransactionData pendingTransactionData;
		int approvedActivationHeight;
		int pendingActivationHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int settlementBlockCount = getApprovalSettlementBlockCount(repository);

			approvedActivationHeight = getActivationHeightSafelyAfterApproval(repository, 20);
			approvedTransactionData = buildBlockRewardUpdateTransaction(repository, alice,
					approvedActivationHeight, 4L * Amounts.MULTIPLIER);
			TransactionUtils.signAndMint(repository, approvedTransactionData, alice);

			GroupUtils.approveTransaction(repository, "alice", approvedTransactionData.getSignature(), true);
			BlockUtils.mintBlocks(repository, settlementBlockCount);

			pendingActivationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;
			pendingTransactionData = buildBlockRewardUpdateTransaction(repository, alice,
					pendingActivationHeight, 5L * Amounts.MULTIPLIER);
			TransactionUtils.signAndMint(repository, pendingTransactionData, alice);
		}

		List<ChainParameterUpdateSummary> approvedSummaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.BLOCK_REWARD.id, ApprovalStatus.APPROVED, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID,
				approvedActivationHeight, approvedActivationHeight, ConfirmationStatus.CONFIRMED, null, null, null);
		assertEquals(1, approvedSummaries.size());

		ChainParameterUpdateSummary approvedSummary = approvedSummaries.get(0);
		assertArrayEquals(approvedTransactionData.getSignature(), approvedSummary.signature);
		assertEquals(ApprovalStatus.APPROVED, approvedSummary.approvalStatus);
		assertNotNull(approvedSummary.approvalHeight);
		assertEquals(1, approvedSummary.approvalCount);
		assertEquals(0, approvedSummary.rejectionCount);
		assertEquals("4.00000000", approvedSummary.displayValue);
		assertFalse(approvedSummary.effectiveNow);

		try (final Repository repository = RepositoryManager.getRepository()) {
			int blocksToActivation = approvedActivationHeight - repository.getBlockRepository().getBlockchainHeight();
			BlockUtils.mintBlocks(repository, blocksToActivation);
		}

		List<ChainParameterUpdateSummary> effectiveSummaries = this.chainParametersResource.getChainParameterUpdates(
				ChainParameter.BLOCK_REWARD.id, ApprovalStatus.APPROVED, null, approvedActivationHeight,
				approvedActivationHeight, null, null, null, null);
		assertEquals(1, effectiveSummaries.size());
		assertTrue(effectiveSummaries.get(0).effectiveNow);

		List<ChainParameterUpdateSummary> pendingSummaries = this.chainParametersResource.getChainParameterUpdates(
				null, ApprovalStatus.PENDING, null, pendingActivationHeight, pendingActivationHeight,
				null, null, null, null);
		assertEquals(1, pendingSummaries.size());
		assertArrayEquals(pendingTransactionData.getSignature(), pendingSummaries.get(0).signature);

		List<ChainParameterUpdateSummary> firstSummary = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, null, 1, 0, false);
		assertEquals(1, firstSummary.size());
		assertArrayEquals(approvedTransactionData.getSignature(), firstSummary.get(0).signature);

		List<ChainParameterUpdateSummary> newestSummary = this.chainParametersResource.getChainParameterUpdates(
				null, null, null, null, null, null, 1, 0, true);
		assertEquals(1, newestSummary.size());
		assertArrayEquals(pendingTransactionData.getSignature(), newestSummary.get(0).signature);
	}

	private static AccountTrustCategoryPoliciesData buildCategoryPoliciesWithLevelThreshold(
			AccountRatingCategory updatedCategory, int updatedLevel, long updatedThreshold) {
		AccountTrustCategoryPoliciesData categoryPolicies = AccountTrustCategoryPolicyCodec.decode(
				BlockChain.getInstance().getAccountTrustCategoryPoliciesValue());

		ArrayList<AccountTrustCategoryPoliciesData.CategoryPolicy> updatedPolicies = new ArrayList<>();
		for (AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy : categoryPolicies.getCategoryPolicies()) {
			ArrayList<AccountTrustCategoryPoliciesData.LevelPolicy> updatedLevels = new ArrayList<>();
			for (AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy : categoryPolicy.getLevels()) {
				long threshold = categoryPolicy.getCategory() == updatedCategory && levelPolicy.getLevel() == updatedLevel
						? updatedThreshold
						: levelPolicy.getThreshold();
				updatedLevels.add(new AccountTrustCategoryPoliciesData.LevelPolicy(levelPolicy.getLevel(), threshold,
						levelPolicy.getLevelScoreCap()));
			}

			updatedPolicies.add(new AccountTrustCategoryPoliciesData.CategoryPolicy(categoryPolicy.getCategory(),
					updatedLevels, categoryPolicy.getSuspiciousThreshold(),
					categoryPolicy.getSuspiciousLevelScoreCap()));
		}

		return new AccountTrustCategoryPoliciesData(updatedPolicies);
	}

	private static BlockRewardUpdateRequest buildBlockRewardUpdateRequest(Repository repository, long reward) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		BlockRewardUpdateRequest request = new BlockRewardUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.reward = reward;

		return request;
	}

	private static IntegerChainParameterUpdateRequest buildMinAccountsToActivateShareBinUpdateRequest(
			Repository repository, int value) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		IntegerChainParameterUpdateRequest request = new IntegerChainParameterUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.value = value;

		return request;
	}

	private static RewardShareWeightsUpdateRequest buildRewardShareWeightsUpdateRequest(
			Repository repository, int[] weights) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		RewardShareWeightsUpdateRequest request = new RewardShareWeightsUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.weights = weights;

		return request;
	}

	private static TrustStatusVoteWeightsUpdateRequest buildTrustStatusVoteWeightsUpdateRequest(
			Repository repository, int[] weights) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		TrustStatusVoteWeightsUpdateRequest request = new TrustStatusVoteWeightsUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.weights = weights;

		return request;
	}

	private static AccountTrustStartingEnergyUpdateRequest buildAccountTrustStartingEnergyUpdateRequest(
			Repository repository, long startingEnergy) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustStartingEnergyUpdateRequest request = new AccountTrustStartingEnergyUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.startingEnergy = startingEnergy;

		return request;
	}

	private static AccountTrustManagerEnergyHopsUpdateRequest buildAccountTrustManagerEnergyHopsUpdateRequest(
			Repository repository, int managerEnergyHops) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustManagerEnergyHopsUpdateRequest request = new AccountTrustManagerEnergyHopsUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.managerEnergyHops = managerEnergyHops;

		return request;
	}

	private static AccountTrustPositiveMinBranchCountUpdateRequest buildAccountTrustPositiveMinBranchCountUpdateRequest(
			Repository repository, int positiveMinBranchCount) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustPositiveMinBranchCountUpdateRequest request =
				new AccountTrustPositiveMinBranchCountUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.positiveMinBranchCount = positiveMinBranchCount;

		return request;
	}

	private static AccountTrustSuspiciousMinRaterCountUpdateRequest buildAccountTrustSuspiciousMinRaterCountUpdateRequest(
			Repository repository, int suspiciousMinRaterCount) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustSuspiciousMinRaterCountUpdateRequest request =
				new AccountTrustSuspiciousMinRaterCountUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.suspiciousMinRaterCount = suspiciousMinRaterCount;

		return request;
	}

	private static AccountTrustSuspiciousMinBranchCountUpdateRequest buildAccountTrustSuspiciousMinBranchCountUpdateRequest(
			Repository repository, int suspiciousMinBranchCount) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustSuspiciousMinBranchCountUpdateRequest request =
				new AccountTrustSuspiciousMinBranchCountUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.suspiciousMinBranchCount = suspiciousMinBranchCount;

		return request;
	}

	private static AccountTrustSuspiciousMinRatingConfidenceUpdateRequest
			buildAccountTrustSuspiciousMinRatingConfidenceUpdateRequest(Repository repository,
			int suspiciousMinRatingConfidence) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustSuspiciousMinRatingConfidenceUpdateRequest request =
				new AccountTrustSuspiciousMinRatingConfidenceUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.suspiciousMinRatingConfidence = suspiciousMinRatingConfidence;

		return request;
	}

	private static AccountTrustCategoryPoliciesUpdateRequest buildAccountTrustCategoryPoliciesUpdateRequest(
			Repository repository, AccountTrustCategoryPoliciesData categoryPolicies) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountTrustCategoryPoliciesUpdateRequest request = new AccountTrustCategoryPoliciesUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.categoryPolicies = categoryPolicies;

		return request;
	}

	private static AccountRatingCooldownUpdateRequest buildAccountRatingCooldownUpdateRequest(
			Repository repository, int cooldownBlocks) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		AccountRatingCooldownUpdateRequest request = new AccountRatingCooldownUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.cooldownBlocks = cooldownBlocks;

		return request;
	}

	private static UnitFeeUpdateRequest buildUnitFeeUpdateRequest(Repository repository, long unitFee) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		UnitFeeUpdateRequest request = new UnitFeeUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.unitFee = unitFee;

		return request;
	}

	private static NameRegistrationUnitFeeUpdateRequest buildNameRegistrationUnitFeeUpdateRequest(
			Repository repository, long nameRegistrationUnitFee) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		NameRegistrationUnitFeeUpdateRequest request = new NameRegistrationUnitFeeUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ 100;
		request.nameRegistrationUnitFee = nameRegistrationUnitFee;

		return request;
	}

	private static ChainParameterUpdateTransactionData decodeRawTransaction(String rawTransaction) throws TransformationException {
		byte[] rawBytes = Base58.decode(rawTransaction);
		byte[] signedLengthBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

		return (ChainParameterUpdateTransactionData) TransactionTransformer.fromBytes(signedLengthBytes);
	}

	private static ChainParameterUpdateTransactionData buildBlockRewardUpdateTransaction(Repository repository,
			PrivateKeyAccount updater, int activationHeight, long reward) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.BLOCK_REWARD.id, activationHeight, ChainParameter.BLOCK_REWARD.encodeLongValue(reward));
	}

	private static ChainParameterUpdateTransactionData buildMinAccountsToActivateShareBinUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int value) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.id, activationHeight,
				ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(value));
	}

	private static ChainParameterUpdateTransactionData buildRewardShareWeightsUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int[] weights) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.REWARD_SHARE_WEIGHTS.id, activationHeight,
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountRatingCooldownUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int cooldownBlocks) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, activationHeight,
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.encodeIntValue(cooldownBlocks));
	}

	private static ChainParameterUpdateTransactionData buildTrustStatusVoteWeightsUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int[] weights) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustStartingEnergyUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, long startingEnergy) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(startingEnergy));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustManagerEnergyHopsUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int managerEnergyHops)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(managerEnergyHops));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustPositiveMinBranchCountUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int positiveMinBranchCount)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(positiveMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRaterCountUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int suspiciousMinRaterCount)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(suspiciousMinRaterCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinBranchCountUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int suspiciousMinBranchCount)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(suspiciousMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRatingConfidenceUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, int suspiciousMinRatingConfidence)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
						suspiciousMinRatingConfidence));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustCategoryPoliciesUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight,
			AccountTrustCategoryPoliciesData categoryPolicies) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, activationHeight,
				AccountTrustCategoryPolicyCodec.encode(categoryPolicies,
						BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, activationHeight)));
	}

	private static ChainParameterUpdateTransactionData buildUnitFeeUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, long unitFee) throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.UNIT_FEE.id, activationHeight, ChainParameter.UNIT_FEE.encodeLongValue(unitFee));
	}

	private static ChainParameterUpdateTransactionData buildNameRegistrationUnitFeeUpdateTransaction(
			Repository repository, PrivateKeyAccount updater, int activationHeight, long nameRegistrationUnitFee)
			throws DataException {
		return new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(updater, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.id, activationHeight,
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(nameRegistrationUnitFee));
	}

	private static ChainParameterMetadata findMetadata(List<ChainParameterMetadata> metadata, ChainParameter parameter) {
		for (ChainParameterMetadata entry : metadata)
			if (entry.id == parameter.id)
				return entry;

		throw new AssertionError("Missing metadata for " + parameter.name());
	}

	private static ChainParameterEffectiveValue findEffectiveValue(List<ChainParameterEffectiveValue> values,
			ChainParameter parameter) {
		for (ChainParameterEffectiveValue value : values)
			if (value.id == parameter.id)
				return value;

		throw new AssertionError("Missing effective value for " + parameter.name());
	}

	private static void assertMetadataMatchesParameter(ChainParameterMetadata metadata, ChainParameter parameter) {
		assertEquals(parameter.id, metadata.id);
		assertEquals(parameter.name(), metadata.name);
		assertEquals(parameter.getValueType(), metadata.valueType);
		assertEquals(parameter.valueLength, metadata.valueLength);
		assertEquals(BlockChain.getInstance().getChainParameterUpdateMinActivationDelay(), metadata.minimumActivationDelay);
		assertEquals(parameter.getDescription(), metadata.description);
		assertEquals(parameter.getBuilderPath(), metadata.builderPath);
		assertEquals(parameter.getEffectivePath(), metadata.effectivePath);
		assertNotNull(metadata.validation);
		assertEquals(parameter.getMinimumLongValue(), metadata.validation.minimumLongValue);
		assertEquals(parameter.getMinimumIntegerValue(), metadata.validation.minimumIntegerValue);
		assertEquals(parameter.getMaximumIntegerValue(), metadata.validation.maximumIntegerValue);
		assertEquals(parameter.getIntegerListLength(), metadata.validation.integerListLength);
		assertEquals(parameter.getMinimumIntegerListValue(), metadata.validation.minimumIntegerListValue);
		assertEquals(parameter.getMaximumIntegerListValue(), metadata.validation.maximumIntegerListValue);
		assertArrayEquals(parameter.getIntegerListLabels(), metadata.validation.integerListLabels);
		assertEquals(parameter.requiresPositiveTotal(), metadata.validation.requiresPositiveTotal);
		assertEquals(parameter.requiresPositiveFirstValue(), metadata.validation.requiresPositiveFirstValue);
		assertEquals(parameter.requiresAnyPositiveValue(), metadata.validation.requiresAnyPositiveValue);
	}

	private static void assertConfigEffectiveValue(List<ChainParameterEffectiveValue> values, ChainParameter parameter,
			int height, byte[] expectedValue) {
		ChainParameterEffectiveValue value = findEffectiveValue(values, parameter);

		assertEquals(parameter.id, value.id);
		assertEquals(parameter.name(), value.name);
		assertEquals(parameter.getValueType(), value.valueType);
		assertEquals(height, value.height);
		assertEquals(ChainParameterEffectiveValue.Source.CONFIG, value.source);
		assertNull(value.signature);
		assertNull(value.activationHeight);
		assertCurrentDecodedValue(value, parameter, expectedValue);
		assertNull(value.nextSignature);
		assertNull(value.nextActivationHeight);
		assertNull(value.nextValue);
		assertNull(value.nextDisplayValue);
		assertNull(value.nextAmount);
		assertNull(value.nextLongValue);
		assertNull(value.nextIntegerValue);
		assertNull(value.nextIntegerValues);
		assertNull(value.nextAccountTrustCategoryPolicies);
	}

	private void assertPendingIntegerSummary(ChainParameterUpdateTransactionData transactionData,
			ChainParameter parameter, int expectedValue) {
		List<ChainParameterUpdateSummary> summaries = this.chainParametersResource.getChainParameterUpdates(
				parameter.id, null, null, null, null, null, null, null, null);

		assertEquals(1, summaries.size());

		ChainParameterUpdateSummary summary = summaries.get(0);
		assertArrayEquals(transactionData.getSignature(), summary.signature);
		assertEquals(parameter.id, summary.parameterId);
		assertEquals(parameter.name(), summary.parameterName);
		assertEquals(transactionData.getActivationHeight(), summary.activationHeight);
		assertArrayEquals(transactionData.getValue(), summary.value);
		assertEquals("INTEGER", summary.valueType);
		assertEquals(Integer.toString(expectedValue), summary.displayValue);
		assertNull(summary.amount);
		assertNull(summary.longValue);
		assertEquals(Integer.valueOf(expectedValue), summary.integerValue);
		assertNull(summary.integerValues);
		assertEquals(ApprovalStatus.PENDING, summary.approvalStatus);
		assertEquals(ApprovalThreshold.PCT40, summary.approvalThreshold);
		assertEquals(0, summary.approvalCount);
		assertEquals(0, summary.rejectionCount);
		assertEquals(1, summary.approvalAuthorityCount);
		assertFalse(summary.effectiveNow);
	}

	private static void assertCurrentDecodedValue(ChainParameterEffectiveValue value, ChainParameter parameter,
			byte[] expectedValue) {
		assertArrayEquals(expectedValue, value.value);
		assertEquals(parameter.decodeAmountValue(expectedValue), value.amount);
		assertEquals(parameter.decodeLongParameterValue(expectedValue), value.longValue);
		assertEquals(parameter.decodeIntegerValue(expectedValue), value.integerValue);
		assertArrayEquals(parameter.decodeIntegerListValue(expectedValue), value.integerValues);
		assertAccountTrustCategoryPoliciesEqual(
				parameter.decodeAccountTrustCategoryPoliciesValue(expectedValue),
				value.accountTrustCategoryPolicies);
		assertEquals(parameter.formatDisplayValue(expectedValue), value.displayValue);
	}

	private static void assertNextDecodedValue(ChainParameterEffectiveValue value, ChainParameter parameter,
			byte[] expectedValue) {
		assertArrayEquals(expectedValue, value.nextValue);
		assertEquals(parameter.decodeAmountValue(expectedValue), value.nextAmount);
		assertEquals(parameter.decodeLongParameterValue(expectedValue), value.nextLongValue);
		assertEquals(parameter.decodeIntegerValue(expectedValue), value.nextIntegerValue);
		assertArrayEquals(parameter.decodeIntegerListValue(expectedValue), value.nextIntegerValues);
		assertAccountTrustCategoryPoliciesEqual(
				parameter.decodeAccountTrustCategoryPoliciesValue(expectedValue),
				value.nextAccountTrustCategoryPolicies);
		assertEquals(parameter.formatDisplayValue(expectedValue), value.nextDisplayValue);
	}

	private static void assertAccountTrustCategoryPoliciesEqual(AccountTrustCategoryPoliciesData expected,
			AccountTrustCategoryPoliciesData actual) {
		if (expected == null || actual == null) {
			assertNull(expected);
			assertNull(actual);
			return;
		}

		assertArrayEquals(AccountTrustCategoryPolicyCodec.encode(expected, Integer.MAX_VALUE),
				AccountTrustCategoryPolicyCodec.encode(actual, Integer.MAX_VALUE));
	}

	private static int getApprovalSettlementBlockCount(Repository repository) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private static int getActivationHeightSafelyAfterApproval(Repository repository, int extraBlocks) throws DataException {
		return repository.getBlockRepository().getBlockchainHeight()
				+ getApprovalSettlementBlockCount(repository)
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ extraBlocks;
	}
}
