package org.qortium.test;

import org.junit.Test;
import org.qortium.transaction.Transaction.TransactionType;

import static org.junit.Assert.*;

public class TransactionTypeTests {

	@Test
	public void testActiveTransactionIdsResolve() {
		assertEquals(TransactionType.GENESIS, TransactionType.valueOf(1));
		assertEquals(TransactionType.PUBLICIZE, TransactionType.valueOf(19));
		assertEquals(TransactionType.AT, TransactionType.valueOf(21));
		assertEquals(TransactionType.CHAIN_PARAMETER_UPDATE, TransactionType.valueOf(48));
		assertEquals(TransactionType.SET_GROUP_AVATAR, TransactionType.valueOf(49));
		assertEquals(TransactionType.SET_ACCOUNT_AVATAR, TransactionType.valueOf(50));
	}

	@Test
	public void testReservedTransactionIdsRemainInactive() {
		assertReservedInactive(20, "AIRDROP");
		assertReservedInactive(36, "ACCOUNT_FLAGS");
		assertReservedInactive(37, "ENABLE_FORGING");
		assertReservedInactive(39, "ACCOUNT_LEVEL");
	}

	@Test
	public void testUnknownTransactionIdsAreNotReserved() {
		assertNull(TransactionType.valueOf(0));
		assertFalse(TransactionType.isReservedId(0));
		assertNull(TransactionType.getReservedIdDescription(0));

		// 49 and 50 are now SET_GROUP_AVATAR / SET_ACCOUNT_AVATAR; 51 is the first free id.
		assertNull(TransactionType.valueOf(51));
		assertFalse(TransactionType.isReservedId(51));
		assertNull(TransactionType.getReservedIdDescription(51));
	}

	private static void assertReservedInactive(int value, String historicalName) {
		assertNull(TransactionType.valueOf(value));
		assertTrue(TransactionType.isReservedId(value));
		assertTrue(TransactionType.getReservedIdDescription(value).contains(historicalName));
	}

}
