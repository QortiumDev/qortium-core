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

		assertNull(TransactionType.valueOf(49));
		assertFalse(TransactionType.isReservedId(49));
		assertNull(TransactionType.getReservedIdDescription(49));
	}

	private static void assertReservedInactive(int value, String historicalName) {
		assertNull(TransactionType.valueOf(value));
		assertTrue(TransactionType.isReservedId(value));
		assertTrue(TransactionType.getReservedIdDescription(value).contains(historicalName));
	}

}
