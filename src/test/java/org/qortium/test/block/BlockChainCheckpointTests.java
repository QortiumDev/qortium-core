package org.qortium.test.block;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.block.BlockChain;
import org.qortium.data.block.BlockData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers consensus-checkpoint validation in {@link BlockChain#validate()}:
 * a matching checkpoint passes, and a mismatched checkpoint on an already-synced chain must NOT
 * trigger a destructive rebuild/resync (the agreed no-loop behaviour — a wrong checkpoint would
 * otherwise wipe every node, since peers carry the same data).
 */
public class BlockChainCheckpointTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		// Clear any injected checkpoints so later tests in the suite see the unmodified config.
		try {
			setCheckpoints(Collections.emptyList());
		} catch (IllegalAccessException e) {
			// ignore
		}
		Common.useDefaultSettings();
	}

	@Test
	public void testMatchingCheckpointPasses() throws Exception {
		int heightBefore = mintSyncedChain();

		// Pin a checkpoint at height 2 with that block's ACTUAL signature.
		String genuineSig;
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData block2 = repository.getBlockRepository().fromHeight(2);
			assertNotNull(block2);
			genuineSig = Base58.encode(block2.getSignature());
		}
		setCheckpoints(List.of(checkpoint(2, genuineSig)));

		// A matching checkpoint must validate cleanly and leave the chain untouched.
		BlockChain.validate();
		assertEquals("matching checkpoint must not change the chain", heightBefore, currentHeight());
	}

	@Test
	public void testMismatchedCheckpointOnSyncedChainDoesNotWipe() throws Exception {
		int heightBefore = mintSyncedChain();

		// Pin a WRONG signature at an existing height. On a synced chain (checkHeight >= 3) this must
		// log an error but must NOT rebuild/resync — otherwise a bad checkpoint would loop the network.
		byte[] wrongSignature = new byte[Transformer.SIGNATURE_LENGTH];
		setCheckpoints(List.of(checkpoint(2, Base58.encode(wrongSignature))));

		BlockChain.validate();
		assertEquals("checkpoint mismatch on a synced chain must not wipe/resync", heightBefore, currentHeight());
	}

	/** Mint a few blocks so the chain is populated and synced (height >= 3). */
	private static int mintSyncedChain() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (int i = 0; i < 4; i++)
				BlockUtils.mintBlock(repository);
			int height = repository.getBlockRepository().getBlockchainHeight();
			assertTrue("expected a synced chain (height >= 3)", height >= 3);
			return height;
		}
	}

	private static int currentHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getBlockRepository().getBlockchainHeight();
		}
	}

	private static BlockChain.Checkpoint checkpoint(int height, String signature) {
		BlockChain.Checkpoint checkpoint = new BlockChain.Checkpoint();
		checkpoint.height = height;
		checkpoint.signature = signature;
		return checkpoint;
	}

	private static void setCheckpoints(List<BlockChain.Checkpoint> checkpoints) throws IllegalAccessException {
		FieldUtils.writeField(BlockChain.getInstance(), "checkpoints", checkpoints, true);
	}
}
