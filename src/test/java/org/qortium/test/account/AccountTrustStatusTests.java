package org.qortium.test.account;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

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
		assertEquals(70, AccountTrustStatus.SILVER.calculateEffectiveVoteWeight(101));
		assertEquals(40, AccountTrustStatus.BRONZE.calculateEffectiveVoteWeight(101));
		assertEquals(0, AccountTrustStatus.UNVERIFIED.calculateEffectiveVoteWeight(100));
		assertEquals(0, AccountTrustStatus.SUSPICIOUS.calculateEffectiveVoteWeight(100));
	}
}
