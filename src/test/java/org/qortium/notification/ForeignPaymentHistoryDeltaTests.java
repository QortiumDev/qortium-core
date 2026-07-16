package org.qortium.notification;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ForeignPaymentHistoryDeltaTests {

	@Test
	public void testCandidateIsOnlyMarkedAfterSuccessfulClassification() {
		ForeignPaymentHistoryDelta delta = new ForeignPaymentHistoryDelta(10);
		delta.baseline(List.of(entry("old", 100)));

		List<ForeignPaymentHistoryDelta.Entry> added = delta.candidates(
				List.of(entry("old", 101), entry("new", 0)));

		assertEquals(1, added.size());
		assertEquals("new", added.get(0).txHash);
		assertEquals("A failed fetch must leave the transaction eligible for retry", 1,
				delta.candidates(List.of(entry("new", 0))).size());

		delta.markSeen("new");
		assertTrue(delta.candidates(List.of(entry("new", 102))).isEmpty());
	}

	@Test
	public void testRemovedHistoryDoesNotReplayAfterReconnect() {
		ForeignPaymentHistoryDelta delta = new ForeignPaymentHistoryDelta(10);
		delta.baseline(List.of(entry("existing", 100)));

		assertTrue(delta.candidates(List.of()).isEmpty());
		assertTrue(delta.candidates(List.of(entry("existing", 101))).isEmpty());
	}

	@Test
	public void testSeenSetIsBounded() {
		ForeignPaymentHistoryDelta delta = new ForeignPaymentHistoryDelta(2);
		delta.markSeen("one");
		delta.markSeen("two");
		delta.markSeen("three");

		assertEquals(2, delta.size());
		assertEquals(1, delta.candidates(List.of(entry("one", 1))).size());
		assertTrue(delta.candidates(List.of(entry("two", 1), entry("three", 1))).isEmpty());
	}

	private static ForeignPaymentHistoryDelta.Entry entry(String txHash, int height) {
		return new ForeignPaymentHistoryDelta.Entry(txHash, height);
	}
}
