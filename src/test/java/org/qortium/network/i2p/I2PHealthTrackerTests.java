package org.qortium.network.i2p;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class I2PHealthTrackerTests {

	@Test
	public void testInitialEvidenceIsUnknownWithoutTimestamps() {
		I2PHealthTracker tracker = new I2PHealthTracker();

		I2PHealthTracker.LeaseSetLookupEvidence evidence = tracker.getLeaseSetLookupEvidence();
		assertEquals(I2PHealthTracker.LeaseSetLookupStatus.UNKNOWN, evidence.status);
		assertNull(evidence.timestamp);
		assertNull(tracker.getLastInboundHandshakeTimestamp());
	}

	@Test
	public void testLookupStatusAndTimestampAreOneSnapshot() {
		I2PHealthTracker tracker = new I2PHealthTracker();
		long before = System.currentTimeMillis();

		tracker.recordLeaseSetLookupStatus(I2PHealthTracker.LeaseSetLookupStatus.RESOLVED);

		I2PHealthTracker.LeaseSetLookupEvidence evidence = tracker.getLeaseSetLookupEvidence();
		assertEquals(I2PHealthTracker.LeaseSetLookupStatus.RESOLVED, evidence.status);
		assertNotNull(evidence.timestamp);
		assertTrue(evidence.timestamp >= before);
		assertTrue(evidence.timestamp <= System.currentTimeMillis());
	}

	@Test
	public void testEvidenceSurvivesIndependentSessionObjects() {
		I2PHealthTracker tracker = new I2PHealthTracker();
		tracker.recordLeaseSetLookupStatus(I2PHealthTracker.LeaseSetLookupStatus.NOT_RESOLVED);
		I2PHealthTracker.LeaseSetLookupEvidence first = tracker.getLeaseSetLookupEvidence();

		// Owners retain the tracker while disposable provider instances are replaced.
		assertEquals(I2PHealthTracker.LeaseSetLookupStatus.NOT_RESOLVED,
				tracker.getLeaseSetLookupEvidence().status);
		assertEquals(first.timestamp, tracker.getLeaseSetLookupEvidence().timestamp);
	}

	@Test
	public void testInboundHandshakeTimestampIsSeparateEvidence() {
		I2PHealthTracker tracker = new I2PHealthTracker();
		long before = System.currentTimeMillis();

		tracker.recordInboundHandshake();

		Long timestamp = tracker.getLastInboundHandshakeTimestamp();
		assertNotNull(timestamp);
		assertTrue(timestamp >= before);
		assertTrue(timestamp <= System.currentTimeMillis());
		assertNull(tracker.getLeaseSetLookupEvidence().timestamp);
	}

	@Test
	public void testEachNewEvidenceSnapshotNotifiesOwner() {
		AtomicInteger notifications = new AtomicInteger();
		I2PHealthTracker tracker = new I2PHealthTracker(notifications::incrementAndGet);

		tracker.recordLeaseSetLookupStatus(I2PHealthTracker.LeaseSetLookupStatus.RESOLVED);
		tracker.recordInboundHandshake();

		assertEquals(2, notifications.get());
	}
}
