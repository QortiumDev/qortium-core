package org.qortal.test.account;

import org.junit.Before;
import org.junit.Test;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccountTrustStatusTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testAccountDataDefaultsToUnverifiedSnapshotTrust() {
		AccountData accountData = new AccountData("Qaddress");

		assertEquals(AccountTrustStatus.UNVERIFIED, accountData.getTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), accountData.getTrustStatusValue());
		assertEquals(0, accountData.getTrustWeightPercent());
		assertTrue(accountData.isTrustAllowsMinting());
		assertEquals(0, accountData.getEffectiveVoteWeight());
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
