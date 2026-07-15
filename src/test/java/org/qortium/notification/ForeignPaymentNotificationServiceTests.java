package org.qortium.notification;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ForeignPaymentNotificationServiceTests {

	@Test
	public void testDerivationStopsAtPerRuleCeiling() {
		int derivedAddressCount = 8; // Initial receive/change batch for a lookahead increment of three.
		while (true) {
			int pairCount = ForeignPaymentNotificationService.nextDerivationPairCount(derivedAddressCount);
			if (pairCount == 0)
				break;
			derivedAddressCount += pairCount * 2;
		}

		assertEquals(ForeignPaymentNotificationService.MAX_DERIVED_ADDRESSES_PER_RULE, derivedAddressCount);
		assertEquals(0, ForeignPaymentNotificationService.nextDerivationPairCount(derivedAddressCount));
	}

	@Test
	public void testSessionAndGlobalWatchCaps() {
		assertNull(ForeignPaymentNotificationService.validateRuleCounts(
				ForeignPaymentNotificationService.MAX_RULES_PER_SESSION,
				ForeignPaymentNotificationService.MAX_ACTIVE_WATCH_RULES));
		String sessionError = ForeignPaymentNotificationService.validateRuleCounts(
				ForeignPaymentNotificationService.MAX_RULES_PER_SESSION + 1,
				ForeignPaymentNotificationService.MAX_RULES_PER_SESSION + 1);
		assertNotNull(sessionError);
		assertTrue(sessionError.contains("per websocket session"));

		String globalError = ForeignPaymentNotificationService.validateRuleCounts(
				1, ForeignPaymentNotificationService.MAX_ACTIVE_WATCH_RULES + 1);
		assertNotNull(globalError);
		assertTrue(globalError.contains("across websocket sessions"));
	}

	@Test(expected = IOException.class)
	public void testOversizedHistoryIsProtocolError() throws Exception {
		List<Object> history = new ArrayList<>(
				ForeignPaymentNotificationService.MAX_HISTORY_ENTRIES_PER_SCRIPTHASH + 1);
		for (int index = 0;
				 index <= ForeignPaymentNotificationService.MAX_HISTORY_ENTRIES_PER_SCRIPTHASH; index++)
			history.add(null);

		ForeignPaymentNotificationService.parseHistory(history);
	}
}
