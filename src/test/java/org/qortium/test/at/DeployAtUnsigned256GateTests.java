package org.qortium.test.at;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deployment-side gate for AT creation version 3 (unsigned 256-bit A/B arithmetic).
 * <p>
 * The AT runtime gates <i>execution</i> of the version-3 function codes on the AT's creation
 * version, but that alone does not make the upgrade safe: without a deployment gate, a version-3
 * DEPLOY_AT would be accepted by nodes running the newer runtime and rejected as invalid creation
 * bytes by every node still on the older one, splitting the chain. These tests pin the agreed
 * behaviour either side of the activation height.
 * <p>
 * The deciding height must come from the node's own chain tip plus one, never from the height a
 * block claims for itself - the claimed height is not covered by the block signature, so a peer can
 * relabel the same signed block. This fixture is the test-chain analogue of the live Previewnet
 * 69,999 -> 70,000 boundary.
 */
public class DeployAtUnsigned256GateTests extends Common {

	/** Matches {@code atUnsigned256ArithmeticHeight} in test-chain-v2-at-unsigned256.json. */
	private static final int TRIGGER_HEIGHT = 8;

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-unsigned256.json");
		// useSettings (unlike useDefaultSettings) does not prime the clock, and minting needs it
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@After
	public void afterTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTriggerHeightMatchesFixture() {
		assertEquals("Test fixture and BlockChain must agree on the activation height",
				TRIGGER_HEIGHT, BlockChain.getInstance().getAtUnsigned256ArithmeticHeight());
	}

	@Test
	public void testVersion3DeploymentRejectedBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Leave the chain tip below the trigger, so the block this transaction would land in
			// (tip + 1) is still before activation.
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 2);

			assertEquals("Deploying a version-3 AT before activation must be rejected",
					Transaction.ValidationResult.AT_VERSION_NOT_YET_ACTIVE,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 3)));
		}
	}

	@Test
	public void testVersion3DeploymentAcceptedAtTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Tip is one below the trigger, so this transaction's block IS the activation block.
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 1);

			assertEquals("Deploying a version-3 AT at the activation height must be accepted",
					Transaction.ValidationResult.OK,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 3)));
		}
	}

	@Test
	public void testVersion3DeploymentAcceptedAboveTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT + 3);

			assertEquals("Deploying a version-3 AT after activation must stay accepted",
					Transaction.ValidationResult.OK,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 3)));
		}
	}

	@Test
	public void testVersion2DeploymentUnaffectedBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 2);

			assertEquals("The gate must not disturb version-2 deployment below the trigger",
					Transaction.ValidationResult.OK,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 2)));
		}
	}

	@Test
	public void testVersion2DeploymentUnaffectedAtTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 1);

			assertEquals("The gate must not disturb version-2 deployment at the trigger",
					Transaction.ValidationResult.OK,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 2)));
		}
	}

	@Test
	public void testVersion1DeploymentStillRejectedForItsOwnReason() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT + 3);

			// Version 1 is rejected by the pre-existing "< 2" check, not by the new gate, and that
			// must remain true on both sides of the trigger.
			assertEquals("Version-1 ATs must keep failing as invalid creation bytes",
					Transaction.ValidationResult.INVALID_CREATION_BYTES,
					validateDeploy(repository, AtUtils.buildSimpleAT((short) 1)));
		}
	}

	/**
	 * Block validation calls {@code transaction.isValid()} directly (Block.areTransactionsValid),
	 * not {@code isValidUnconfirmed()}. Assert the gate on that exact entry point too, so a
	 * version-3 AT cannot reach a block through a path the mempool assertions do not cover.
	 */
	@Test
	public void testVersion3RejectedOnTheBlockValidationEntryPointBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 2);

			assertEquals("Block-level validation must reject a version-3 AT below the trigger",
					Transaction.ValidationResult.AT_VERSION_NOT_YET_ACTIVE,
					buildDeploy(repository, AtUtils.buildSimpleAT((short) 3)).isValid());
		}
	}

	@Test
	public void testVersion3AcceptedOnTheBlockValidationEntryPointAtTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 1);

			assertEquals("Block-level validation must accept a version-3 AT at the trigger",
					Transaction.ValidationResult.OK,
					buildDeploy(repository, AtUtils.buildSimpleAT((short) 3)).isValid());
		}
	}

	/**
	 * The version field is read from the first two creation bytes, but the transformer accepts a
	 * length of one. That must be a clean rejection, not an ArrayIndexOutOfBoundsException - the
	 * transaction importer and synchronizer catch only DataException, so an escaping runtime
	 * exception would kill those threads for the life of the process.
	 */
	@Test
	public void testSingleByteCreationBytesRejectedCleanly() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT + 3);

			assertEquals("One-byte creation bytes must be rejected, not throw",
					Transaction.ValidationResult.INVALID_CREATION_BYTES,
					buildDeploy(repository, new byte[] {0}).isValid());
		}
	}

	/**
	 * A creation version beyond anything the pinned runtime knows must be refused here, not left to
	 * MachineState - otherwise the accept/reject answer depends on which jar a node carries, which
	 * is the very split this gate exists to close.
	 */
	@Test
	public void testUnknownFutureCreationVersionRejectedEvenAboveTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT + 3);

			// Take valid version-3 bytes and relabel the header as version 4.
			byte[] futureVersionBytes = AtUtils.buildSimpleAT((short) 3);
			futureVersionBytes[0] = 0;
			futureVersionBytes[1] = 4;

			assertEquals("An unknown future creation version must be rejected regardless of height",
					Transaction.ValidationResult.INVALID_CREATION_BYTES,
					buildDeploy(repository, futureVersionBytes).isValid());
		}
	}

	/** Mints until the chain tip sits exactly at {@code targetTipHeight}. */
	private static void mintUpToTipHeight(Repository repository, int targetTipHeight) throws DataException {
		TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
		repository.saveChanges();

		int fillerBlocks = targetTipHeight - repository.getBlockRepository().getBlockchainHeight();
		if (fillerBlocks > 0)
			BlockUtils.mintBlocks(repository, fillerBlocks);

		assertEquals("Test setup should leave the chain tip where the case expects it",
				targetTipHeight, repository.getBlockRepository().getBlockchainHeight());
	}

	/**
	 * Validates a DEPLOY_AT without minting it, so the deciding height stays the current tip plus
	 * one rather than being advanced by the act of deploying.
	 */
	private static Transaction.ValidationResult validateDeploy(Repository repository, byte[] creationBytes)
			throws DataException {
		return buildDeploy(repository, creationBytes).isValidUnconfirmed();
	}

	/** Builds an unsigned, unminted DEPLOY_AT so either validation entry point can be exercised. */
	private static DeployAtTransaction buildDeploy(Repository repository, byte[] creationBytes)
			throws DataException {
		PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");

		DeployAtTransactionData transactionData = new DeployAtTransactionData(
				TestTransaction.generateBase(deployer),
				"Test AT",
				"Test AT",
				"Test",
				"TEST",
				creationBytes,
				1_00000000L,
				Asset.NATIVE
		);

		DeployAtTransaction transaction = new DeployAtTransaction(repository, transactionData);
		transactionData.setFee(transaction.calcRecommendedFee());

		return transaction;
	}

	@Test
	public void testVersion3DeploymentActuallyMintsAtTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintUpToTipHeight(repository, TRIGGER_HEIGHT - 1);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deployAtTransaction =
					AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT((short) 3), 1_00000000L);

			assertTrue("The version-3 AT should exist once its activation block is minted",
					repository.getATRepository().exists(deployAtTransaction.getATAccount().getAddress()));
		}
	}
}
