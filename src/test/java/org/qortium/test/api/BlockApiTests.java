package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.BlocksResource;
import org.qortium.block.GenesisBlock;
import org.qortium.data.account.AccountData;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class BlockApiTests extends ApiCommon {

	private BlocksResource blocksResource;

	@Before
	public void buildResource() {
		this.blocksResource = (BlocksResource) ApiCommon.buildResource(BlocksResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.blocksResource);
	}

	@Test
	public void testGetBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] signatureBytes = GenesisBlock.getInstance(repository).getSignature();
			String signature = Base58.encode(signatureBytes);

			assertNotNull(this.blocksResource.getBlock(signature, true));
		}
	}

	@Test
	public void testGetBlockTransactions() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] signatureBytes = GenesisBlock.getInstance(repository).getSignature();
			String signature = Base58.encode(signatureBytes);

			assertNotNull(this.blocksResource.getBlockTransactions(signature, null, null, null));
			assertNotNull(this.blocksResource.getBlockTransactions(signature, 1, 1, true));
		}
	}

	@Test
	public void testGetHeight() {
		assertNotNull(this.blocksResource.getHeight());
	}

	@Test
	public void testGetBlockHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] signatureBytes = GenesisBlock.getInstance(repository).getSignature();
			String signature = Base58.encode(signatureBytes);

			assertNotNull(this.blocksResource.getHeight(signature));
		}
	}

	@Test
	public void testGetBlockByHeight() {
		assertNotNull(this.blocksResource.getByHeight(1, true));
	}

	@Test
	public void testGetBlockByTimestamp() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData genesisBlockData = GenesisBlock.getInstance(repository).getBlockData();

			BlockData blockData = this.blocksResource.getByTimestamp(genesisBlockData.getTimestamp(), false);
			assertNotNull(blockData);
			assertEquals(Integer.valueOf(1), blockData.getHeight());

			BlockData mintedBlockData = BlockUtils.mintBlock(repository).getBlockData();
			blockData = this.blocksResource.getByTimestamp(mintedBlockData.getTimestamp(), false);
			assertNotNull(blockData);
			assertEquals(mintedBlockData.getHeight(), blockData.getHeight());
		}
	}

	@Test
	public void testGetBlockRange() {
		assertNotNull(this.blocksResource.getBlockRange(1, 1, false, false));

		List<Integer> testValues = Arrays.asList(null, Integer.valueOf(1));

		for (Integer startHeight : testValues)
			for (Integer endHeight : testValues)
				for (Integer count : testValues) {
					if (startHeight != null && endHeight != null && count != null) {
						assertApiError(ApiError.INVALID_CRITERIA, () -> this.blocksResource.getBlockSummaries(startHeight, endHeight, count));
						continue;
					}

					assertNotNull(this.blocksResource.getBlockSummaries(startHeight, endHeight, count));
				}
	}

	@Test
	public void testGetBlockSigners() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			BlockUtils.mintBlock(repository);

			List<String> addresses = Arrays.asList(aliceAddress, mintingAccount.getAddress(), bobAddress);

			assertNotNull(this.blocksResource.getBlockSigners(Collections.emptyList(), null, null, null));
			assertNotNull(this.blocksResource.getBlockSigners(addresses, null, null, null));
			assertNotNull(this.blocksResource.getBlockSigners(Collections.emptyList(), 1, 1, true));
			assertNotNull(this.blocksResource.getBlockSigners(addresses, 1, 1, true));
		}
	}

	@Test
	public void testGetBlockSummariesBySigner() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			repository.getAccountRepository().setDefaultGroupId(new AccountData(alice.getAddress(), alice.getPublicKey(), Group.NO_GROUP, 0, 0));
			repository.saveChanges();
			BlockUtils.mintBlock(repository);

			List<BlockSummaryData> summaries = this.blocksResource.getBlockSummariesBySigner(aliceAddress, null, null, null);
			assertNotNull(summaries);
			assertFalse(summaries.isEmpty());
			assertEquals(aliceAddress, summaries.get(0).getMinterAddress());

			assertNotNull(this.blocksResource.getBlockSummariesBySigner(aliceAddress, 1, 1, true));
		}
	}

	@Test
	public void testBlockSummariesIncludeMinterAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int height = BlockUtils.mintBlock(repository).getBlockData().getHeight();

			List<BlockSummaryData> summaries = this.blocksResource.getBlockSummaries(height, height + 1, null);

			assertEquals(1, summaries.size());
			assertEquals(aliceAddress, summaries.get(0).getMinterAddress());
		}
	}

}
