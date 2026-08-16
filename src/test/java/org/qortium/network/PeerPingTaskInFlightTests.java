package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.message.Message;
import org.qortium.test.common.Common;
import org.qortium.utils.ExecuteProduceConsume.Task;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PeerPingTaskInFlightTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testSlowPingPreventsOverlappingTaskAndReopensAfterCompletion() throws Exception {
		Peer peer = new NullResponsePeer();
		FieldUtils.writeField(peer, "lastPingSent", 0L, true);

		Task first = peer.getPingTask(40_001L);
		assertNotNull(first);
		assertTrue(peer.isPingTaskInFlight());

		assertNull(peer.getPingTask(80_002L));

		first.perform();
		assertFalse(peer.isPingTaskInFlight());
		assertNotNull(peer.getPingTask(80_002L));
		peer.completePingTask();
	}

	@Test
	public void testInterruptedPingAlwaysReopensScheduling() throws Exception {
		Peer peer = new InterruptingPeer();
		FieldUtils.writeField(peer, "lastPingSent", 0L, true);
		Task task = peer.getPingTask(40_001L);
		assertNotNull(task);

		try {
			task.perform();
			fail("Expected ping interruption");
		} catch (InterruptedException expected) {
			// Expected.
		}

		assertFalse(peer.isPingTaskInFlight());
		assertNotNull(peer.getPingTask(80_002L));
		peer.completePingTask();
	}

	private static PeerData peerData() {
		return new PeerData(PeerAddress.fromString(B32));
	}

	private static final class NullResponsePeer extends Peer {
		private NullResponsePeer() {
			super(peerData(), Peer.NETWORK);
		}

		@Override
		public Message getResponseWithTimeout(Message message, int timeout) {
			return null;
		}
	}

	private static final class InterruptingPeer extends Peer {
		private InterruptingPeer() {
			super(peerData(), Peer.NETWORK);
		}

		@Override
		public Message getResponseWithTimeout(Message message, int timeout) throws InterruptedException {
			throw new InterruptedException("synthetic ping interruption");
		}
	}
}
