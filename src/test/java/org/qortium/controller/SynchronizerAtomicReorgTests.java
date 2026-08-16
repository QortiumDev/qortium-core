package org.qortium.controller;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.Block;
import org.qortium.block.Block.ValidationResult;
import org.qortium.data.at.ATStateData;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.Transaction;

import static org.junit.Assert.*;

public class SynchronizerAtomicReorgTests extends Common {

	private Synchronizer synchronizer;
	private boolean previousSingleNodeTestnet;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.previousSingleNodeTestnet = Settings.getInstance().isSingleNodeTestnet();
		// This suite targets transaction atomicity, not MemoryPoW. Testing blocks intentionally
		// contain random online-account nonces, so use Core's existing single-node test bypass.
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", true, true);
		this.synchronizer = Synchronizer.getInstance();
	}

	@After
	public void afterTest() throws IllegalAccessException {
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", this.previousSingleNodeTestnet, true);
	}

	@Test
	public void testInvalidLaterReplacementRestoresOriginalForkWithoutCallbacks() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			ForkFixture fixture = buildForkFixture(repository, 3);
			List<Block> invalidReplacement = new ArrayList<>(fixture.replacementBlocks);
			Block lastBlock = invalidReplacement.get(invalidReplacement.size() - 1);
			invalidReplacement.set(invalidReplacement.size() - 1,
					new InvalidBlock(repository, lastBlock, ValidationResult.TRANSACTION_INVALID));

			RecordingCallbacks callbacks = new RecordingCallbacks();
			Synchronizer.SynchronizationResult result = this.synchronizer.adoptPeerForkAtomically(
					repository, fixture.commonBlock, fixture.originalTip.getHeight(), null,
					invalidReplacement, callbacks);

			assertEquals(Synchronizer.SynchronizationResult.INVALID_DATA, result);
			assertOriginalForkRestored(repository, fixture);
			assertTrue(callbacks.orphanedTips.isEmpty());
			assertTrue(callbacks.newBlocks.isEmpty());
		}
	}

	@Test
	public void testProcessingFailureRestoresOriginalForkWithoutCallbacks() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			ForkFixture fixture = buildForkFixture(repository, 3);
			List<Block> failingReplacement = new ArrayList<>(fixture.replacementBlocks);
			Block middleBlock = failingReplacement.get(1);
			failingReplacement.set(1, new FailingAfterProcessBlock(repository, middleBlock));

			RecordingCallbacks callbacks = new RecordingCallbacks();
			try {
				this.synchronizer.adoptPeerForkAtomically(repository, fixture.commonBlock,
						fixture.originalTip.getHeight(), null, failingReplacement, callbacks);
				fail("Expected injected replacement processing failure");
			} catch (DataException e) {
				assertTrue(e.getMessage().contains("injected replacement processing failure"));
			}

			assertOriginalForkRestored(repository, fixture);
			assertTrue(callbacks.orphanedTips.isEmpty());
			assertTrue(callbacks.newBlocks.isEmpty());
		}
	}

	@Test
	public void testSuccessfulReplacementCommitsBeforeOrderedCallbacks() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			ForkFixture fixture = buildForkFixture(repository, 3);
			RecordingCallbacks callbacks = new RecordingCallbacks(repository, fixture.replacementTip.getSignature());

			Synchronizer.SynchronizationResult result = this.synchronizer.adoptPeerForkAtomically(
					repository, fixture.commonBlock, fixture.originalTip.getHeight(), null,
					fixture.replacementBlocks, callbacks);

			assertEquals(Synchronizer.SynchronizationResult.OK, result);
			assertArrayEquals(fixture.replacementTip.getSignature(),
					repository.getBlockRepository().getLastBlock().getSignature());
			assertNull(repository.getBlockRepository().fromSignature(fixture.originalTip.getSignature()));
			assertNotNull(repository.getBlockRepository().fromSignature(fixture.replacementTip.getSignature()));
			assertEquals(fixture.bobBalance + 6L,
					Common.getTestAccount(repository, "bob").getConfirmedBalance(Asset.NATIVE));
			assertEquals(3, callbacks.orphanedTips.size());
			assertEquals(3, callbacks.newBlocks.size());
			assertEquals(List.of(3, 2, 1), callbacks.orphanedTips.stream().map(BlockData::getHeight).toList());
			assertEquals(List.of(2, 3, 4), callbacks.newBlocks.stream().map(BlockData::getHeight).toList());
			assertTrue("callbacks must observe the durably adopted tip", callbacks.everyCallbackSawReplacementTip);
		}
	}

	@Test
	public void testCommitFailureRestoresOriginalForkWithoutCallbacks() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			ForkFixture fixture = buildForkFixture(repository, 3);
			AtomicBoolean failCommit = new AtomicBoolean(true);
			Repository failingRepository = (Repository) Proxy.newProxyInstance(
					Repository.class.getClassLoader(), new Class<?>[] { Repository.class }, (proxy, method, args) -> {
						if (method.getName().equals("saveChanges") && failCommit.getAndSet(false))
							throw new DataException("injected atomic commit failure");

						try {
							return method.invoke(repository, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});

			RecordingCallbacks callbacks = new RecordingCallbacks();
			try {
				this.synchronizer.adoptPeerForkAtomically(failingRepository, fixture.commonBlock,
						fixture.originalTip.getHeight(), null, fixture.replacementBlocks, callbacks);
				fail("Expected injected atomic commit failure");
			} catch (DataException e) {
				assertTrue(e.getMessage().contains("injected atomic commit failure"));
			}

			assertOriginalForkRestored(repository, fixture);
			assertTrue(callbacks.orphanedTips.isEmpty());
			assertTrue(callbacks.newBlocks.isEmpty());
		}
	}

	private ForkFixture buildForkFixture(Repository repository, int forkLength) throws Exception {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice-reward-share");
		PrivateKeyAccount aliceMinter = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount bobMinter = Common.getTestAccount(repository, "bob");
		BlockData commonBlock = new BlockData(repository.getBlockRepository().getLastBlock());

		List<Block> originalBlocks = mintDetachedBlocks(repository, alice, forkLength);
		BlockData originalTip = new BlockData(repository.getBlockRepository().getLastBlock());
		int aliceMinted = repository.getAccountRepository().getAccount(aliceMinter.getAddress()).getBlocksMinted();
		int bobMinted = repository.getAccountRepository().getAccount(bobMinter.getAddress()).getBlocksMinted();
		long bobBalance = bobMinter.getConfirmedBalance(Asset.NATIVE);

		BlockUtils.orphanToBlock(repository, commonBlock.getHeight());
		List<Block> replacementBlocks = mintDetachedPaymentBlocks(repository, forkLength);
		BlockData replacementTip = new BlockData(repository.getBlockRepository().getLastBlock());

		BlockUtils.orphanToBlock(repository, commonBlock.getHeight());
		processBlocks(repository, originalBlocks);
		replacementBlocks = replacementBlocks.stream()
				.map(block -> validatingCopy(repository, block))
				.toList();

		assertArrayEquals(originalTip.getSignature(), repository.getBlockRepository().getLastBlock().getSignature());
		return new ForkFixture(commonBlock, originalBlocks, originalTip, replacementBlocks,
				replacementTip, aliceMinted, bobMinted, bobBalance);
	}

	private static List<Block> mintDetachedBlocks(Repository repository, PrivateKeyAccount minter, int count)
			throws DataException {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < count; ++i)
			blocks.add(detachedCopy(repository, BlockMinter.mintTestingBlock(repository, minter)));
		return blocks;
	}

	private static List<Block> mintDetachedPaymentBlocks(Repository repository, int count) throws DataException {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			AccountUtils.pay(repository, "alice", "bob", i + 1L);
			blocks.add(detachedCopy(repository,
					new Block(repository, repository.getBlockRepository().getLastBlock())));
		}
		return blocks;
	}

	private static Block detachedCopy(Repository repository, Block source) throws DataException {
		List<TransactionData> transactions = source.getTransactions().stream()
				.map(Transaction::getTransactionData)
				.toList();
		List<ATStateData> atStates = new ArrayList<>(source.getATStates());
		Block copy = new Block(repository, new BlockData(source.getBlockData()), transactions, atStates);
		for (Transaction transaction : copy.getTransactions())
			transaction.setInitialApprovalStatus();
		return copy;
	}

	private static Block validatingCopy(Repository repository, Block source) {
		try {
			return new ValidatingBlock(repository, source);
		} catch (DataException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void processBlocks(Repository repository, List<Block> blocks) throws DataException {
		for (Block block : blocks) {
			block.preProcess();
			// These blocks were already fully validated when first minted. Trusted replay avoids
			// recomputing their online-account MemoryPoW after the fixture built another fork.
			assertEquals(ValidationResult.OK, block.isValid(true));
			for (Transaction transaction : block.getTransactions())
				repository.getTransactionRepository().save(transaction.getTransactionData());
			block.process();
			repository.saveChanges();
		}
	}

	private static void assertOriginalForkRestored(Repository repository, ForkFixture fixture) throws DataException {
		BlockData restoredTip = repository.getBlockRepository().getLastBlock();
		assertArrayEquals(fixture.originalTip.getSignature(), restoredTip.getSignature());
		assertNotNull(repository.getBlockRepository().fromSignature(fixture.originalTip.getSignature()));
		assertNull(repository.getBlockRepository().fromSignature(fixture.replacementTip.getSignature()));

		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
		assertEquals(fixture.aliceMinted,
				repository.getAccountRepository().getAccount(alice.getAddress()).getBlocksMinted());
		assertEquals(fixture.bobMinted,
				repository.getAccountRepository().getAccount(bob.getAddress()).getBlocksMinted());
		assertEquals(fixture.bobBalance, bob.getConfirmedBalance(Asset.NATIVE));
	}

	private record ForkFixture(BlockData commonBlock, List<Block> originalBlocks, BlockData originalTip,
			List<Block> replacementBlocks, BlockData replacementTip, int aliceMinted, int bobMinted,
			long bobBalance) {
	}

	private static final class ValidatingBlock extends Block {
		private ValidatingBlock(Repository repository, Block source) throws DataException {
			super(repository, new BlockData(source.getBlockData()),
					source.getTransactions().stream().map(Transaction::getTransactionData).toList(),
					new ArrayList<>(source.getATStates()));
			for (Transaction transaction : this.getTransactions())
				transaction.setInitialApprovalStatus();
		}

		@Override
		public ValidationResult isValid() throws DataException {
			ValidationResult result = super.isValid();
			assertEquals("replacement fixture must pass full production validation", ValidationResult.OK, result);
			return result;
		}
	}

	private static final class InvalidBlock extends Block {
		private final ValidationResult validationResult;

		private InvalidBlock(Repository repository, Block source, ValidationResult validationResult) throws DataException {
			super(repository, new BlockData(source.getBlockData()),
					source.getTransactions().stream().map(Transaction::getTransactionData).toList(),
					new ArrayList<>(source.getATStates()));
			this.validationResult = validationResult;
			for (Transaction transaction : this.getTransactions())
				transaction.setInitialApprovalStatus();
		}

		@Override
		public ValidationResult isValid() {
			return this.validationResult;
		}
	}

	private static final class FailingAfterProcessBlock extends Block {
		private FailingAfterProcessBlock(Repository repository, Block source) throws DataException {
			super(repository, new BlockData(source.getBlockData()),
					source.getTransactions().stream().map(Transaction::getTransactionData).toList(),
					new ArrayList<>(source.getATStates()));
			for (Transaction transaction : this.getTransactions())
				transaction.setInitialApprovalStatus();
		}

		@Override
		public void process() throws DataException {
			super.process();
			throw new DataException("injected replacement processing failure");
		}
	}

	private static final class RecordingCallbacks implements Synchronizer.ReorganizationCallbacks {
		private final Repository repository;
		private final List<BlockData> orphanedTips = new ArrayList<>();
		private final List<BlockData> newBlocks = new ArrayList<>();
		private final byte[] replacementTip;
		private boolean everyCallbackSawReplacementTip = true;

		private RecordingCallbacks() {
			this.repository = null;
			this.replacementTip = null;
		}

		private RecordingCallbacks(Repository repository, byte[] replacementTip) {
			this.repository = repository;
			this.replacementTip = replacementTip;
		}

		@Override
		public void onOrphanedBlock(BlockData latestBlockData) {
			this.orphanedTips.add(new BlockData(latestBlockData));
			checkCommittedTip();
		}

		@Override
		public void onNewBlock(BlockData latestBlockData) {
			this.newBlocks.add(new BlockData(latestBlockData));
			checkCommittedTip();
		}

		private void checkCommittedTip() {
			if (this.repository == null)
				return;

			try {
				BlockData currentTip = this.repository.getBlockRepository().getLastBlock();
				this.everyCallbackSawReplacementTip &= currentTip != null
						&& java.util.Arrays.equals(this.replacementTip, currentTip.getSignature());
			} catch (DataException e) {
				this.everyCallbackSawReplacementTip = false;
			}
		}
	}
}
