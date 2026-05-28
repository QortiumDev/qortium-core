package org.qortium.controller.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataFileManagerTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTimedOutChunkRequestCanRetrySamePeer() {
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		String signature58 = "retrySignature";
		String hash58 = "retryHash";
		String peerAddress = "127.0.0.1:12345";
		long now = 1_000_000L;

		manager.clearTriedPeersForSignature(signature58);
		manager.arbitraryDataFileRequests.remove(hash58);
		manager.arbitraryDataFileRequests.put(hash58, now - ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT - 1);
		manager.recordChunkRequested(hash58, signature58, peerAddress);

		assertEquals(1, manager.getInFlightRequestCountForSignature(signature58));
		assertTrue(manager.getTriedPeersForChunk(signature58, hash58).contains(peerAddress));

		manager.cleanupRequestCache(now);

		assertEquals(0, manager.getInFlightRequestCountForSignature(signature58));
		assertFalse(manager.getTriedPeersForChunk(signature58, hash58).contains(peerAddress));
	}

	@Test
	public void testInFlightRequestCountIsScopedBySignature() {
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		String signatureA = "signatureA";
		String signatureB = "signatureB";

		manager.clearTriedPeersForSignature(signatureA);
		manager.clearTriedPeersForSignature(signatureB);
		manager.recordChunkRequested("hashA1", signatureA, "127.0.0.1:10001");
		manager.recordChunkRequested("hashA2", signatureA, "127.0.0.1:10002");
		manager.recordChunkRequested("hashB1", signatureB, "127.0.0.1:10003");

		try {
			assertEquals(2, manager.getInFlightRequestCountForSignature(signatureA));
			assertEquals(1, manager.getInFlightRequestCountForSignature(signatureB));
		} finally {
			manager.clearChunkReceived("hashA1", signatureA);
			manager.clearChunkReceived("hashA2", signatureA);
			manager.clearChunkReceived("hashB1", signatureB);
		}
	}
}
