package org.qortal.test.account;

import org.junit.Before;
import org.junit.Test;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;

import static org.junit.Assert.assertEquals;

public class AccountTrustStatusTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTrustStatusDefaultsAndPersists() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			assertEquals(AccountTrustStatus.UNVERIFIED, repository.getAccountRepository().getAccount(alice.getAddress()).getTrustStatus());
			assertEquals(AccountTrustStatus.UNVERIFIED, repository.getAccountRepository().getTrustStatus(alice.getAddress()));

			for (AccountTrustStatus trustStatus : AccountTrustStatus.values()) {
				repository.getAccountRepository().setTrustStatus(alice.getAddress(), trustStatus);
				repository.saveChanges();

				assertEquals(trustStatus, repository.getAccountRepository().getTrustStatus(alice.getAddress()));
				assertEquals(trustStatus, repository.getAccountRepository().getAccount(alice.getAddress()).getTrustStatus());
			}
		}
	}

	@Test
	public void testEffectiveVoteWeightMultipliers() {
		assertEquals(100, AccountTrustStatus.GOLD.calculateEffectiveVoteWeight(100));
		assertEquals(50, AccountTrustStatus.SILVER.calculateEffectiveVoteWeight(101));
		assertEquals(25, AccountTrustStatus.BRONZE.calculateEffectiveVoteWeight(101));
		assertEquals(0, AccountTrustStatus.UNVERIFIED.calculateEffectiveVoteWeight(100));
		assertEquals(0, AccountTrustStatus.SUSPICIOUS.calculateEffectiveVoteWeight(100));
	}
}
