package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountPenaltyData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BlocksMintedPenaltyBehaviorTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testFounderPrivilegesIgnorePenalty() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			AccountData aliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			assertTrue("Alice should be a founder in the default test chain", Account.isFounder(aliceData.getFlags()));

			applyPenalty(repository, alice.getAddress(), -5_000_000);

			assertTrue("Founder minting should ignore blocksMintedPenalty", alice.canMint(false));
			assertTrue("Founder reward-share eligibility should ignore blocksMintedPenalty", alice.canRewardShare());
			assertEquals("Founder effective minting level should ignore blocksMintedPenalty",
					BlockChain.getInstance().getFounderEffectiveMintingLevel(), alice.getEffectiveMintingLevel());
		}
	}

	@Test
	public void testTransferPrivsValidationIgnoresPenalty() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.generateRandomSeedAccount(repository);

			applyPenalty(repository, alice.getAddress(), -5_000_000);

			BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice);
			TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipient.getAddress());
			Transaction transaction = Transaction.fromData(repository, transactionData);

			assertEquals("Transfer-privs validation should ignore blocksMintedPenalty",
					Transaction.ValidationResult.OK, transaction.isValid());
		}
	}

	private void applyPenalty(Repository repository, String address, int penalty) throws DataException {
		repository.getAccountRepository().updateBlocksMintedPenalties(Set.of(new AccountPenaltyData(address, penalty)));
		repository.saveChanges();
	}
}
