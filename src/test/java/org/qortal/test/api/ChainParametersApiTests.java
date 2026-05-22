package org.qortal.test.api;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.model.BlockRewardUpdateRequest;
import org.qortal.api.model.ChainParameterMetadata;
import org.qortal.api.resource.ChainParametersResource;
import org.qortal.block.ChainParameter;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChainParametersApiTests extends ApiCommon {

	private ChainParametersResource chainParametersResource;

	@Before
	public void buildResource() {
		this.chainParametersResource = (ChainParametersResource) ApiCommon.buildResource(ChainParametersResource.class);
	}

	@Test
	public void testChainParameterMetadataListsBlockReward() {
		List<ChainParameterMetadata> parameters = this.chainParametersResource.getChainParameters();

		assertEquals(1, parameters.size());

		ChainParameterMetadata blockReward = parameters.get(0);
		assertEquals(ChainParameter.BLOCK_REWARD.id, blockReward.id);
		assertEquals(ChainParameter.BLOCK_REWARD.name(), blockReward.name);
		assertEquals("AMOUNT", blockReward.valueType);
		assertEquals(Long.BYTES, blockReward.valueLength);
		assertEquals("/chain-parameters/block-reward/update", blockReward.builderPath);
		assertEquals("/chain-parameters/block-reward/{height}", blockReward.effectivePath);
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
	public void testBuildBlockRewardUpdateRejectsNegativeReward() throws DataException {
		BlockRewardUpdateRequest request;

		try (final Repository repository = RepositoryManager.getRepository()) {
			request = buildBlockRewardUpdateRequest(repository, -1L);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chainParametersResource.updateBlockReward(request));
	}

	private static BlockRewardUpdateRequest buildBlockRewardUpdateRequest(Repository repository, long reward) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		BlockRewardUpdateRequest request = new BlockRewardUpdateRequest();
		request.timestamp = System.currentTimeMillis();
		request.txGroupId = TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID;
		request.updaterPublicKey = alice.getPublicKey();
		request.activationHeight = repository.getBlockRepository().getBlockchainHeight() + 100;
		request.reward = reward;

		return request;
	}

	private static ChainParameterUpdateTransactionData decodeRawTransaction(String rawTransaction) throws TransformationException {
		byte[] rawBytes = Base58.decode(rawTransaction);
		byte[] signedLengthBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

		return (ChainParameterUpdateTransactionData) TransactionTransformer.fromBytes(signedLengthBytes);
	}
}
