package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.message.GetPeersMessage;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageType;
import org.qortium.test.common.Common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PeerQdnByteBudgetTests extends Common {

	private static final int TEST_NETWORK_WITHOUT_SELECTOR = 99;

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testAtomicAdmissionAndExactlyOnceRelease() throws Exception {
		Peer.OutboundByteBudget budget = new Peer.OutboundByteBudget(1024, 8);
		List<Peer.OutboundByteBudget.Reservation> reservations =
				Collections.synchronizedList(new ArrayList<>());
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);

		for (int i = 0; i < 64; ++i) {
			executor.submit(() -> {
				try {
					start.await();
					Peer.OutboundByteBudget.Reservation reservation = budget.tryReserve(128);
					if (reservation != null)
						reservations.add(reservation);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}

		start.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

		assertEquals(8, reservations.size());
		assertEquals(1024L, budget.getReservedBytes());
		assertEquals(8, budget.getReservedMessageCount());
		assertNull(budget.tryReserve(1));

		for (Peer.OutboundByteBudget.Reservation reservation : reservations) {
			reservation.release();
			reservation.release();
		}
		assertEquals(0L, budget.getReservedBytes());
		assertEquals(0, budget.getReservedMessageCount());
	}

	@Test
	public void testTinyQdnFramesCannotConsumeReservedControlSlots() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			Peer peer = peerWithSocket(sockets.sender);
			byte[] tinyFrame = new byte[128];

			for (int i = 0; i < Peer.MAX_PENDING_QDN_MESSAGES; ++i)
				assertTrue(peer.sendPreSerializedMessage(
						i, MessageType.ARBITRARY_DATA_FILE, tinyFrame, 0));

			assertFalse(peer.sendPreSerializedMessage(
					9999, MessageType.ARBITRARY_DATA_FILE, tinyFrame, 0));
			assertEquals(Peer.MAX_PENDING_QDN_MESSAGES, peer.getPendingQdnMessageCount());
			assertEquals((long) Peer.MAX_PENDING_QDN_MESSAGES * tinyFrame.length,
					peer.getPendingQdnBytes());

			int reservedControlSlots = peer.getSendQueueCapacity() - Peer.MAX_PENDING_QDN_MESSAGES;
			for (int i = 0; i < reservedControlSlots; ++i)
				assertTrue(peer.sendMessage(new GetPeersMessage()));

			assertEquals(peer.getSendQueueCapacity(), peer.getSendQueueSize());
			assertFalse(peer.sendMessage(new GetPeersMessage()));

			peer.shutdown();
			assertEquals(0, peer.getPendingQdnMessageCount());
			assertEquals(0L, peer.getPendingQdnBytes());
		}
	}

	@Test
	public void testByteBackpressurePreservesControlMessageCapacity() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			Peer peer = peerWithSocket(sockets.sender);
			byte[] oneMiBChunk = new byte[1024 * 1024];
			int admittedChunks = (int) (Peer.MAX_PENDING_QDN_BYTES / oneMiBChunk.length);

			for (int i = 0; i < admittedChunks; ++i)
				assertTrue(peer.sendPreSerializedMessage(i, MessageType.ARBITRARY_DATA_FILE, oneMiBChunk, 0));

			assertEquals(Peer.MAX_PENDING_QDN_BYTES, peer.getPendingQdnBytes());
			assertFalse(peer.sendPreSerializedMessage(9999, MessageType.ARBITRARY_DATA_FILE, new byte[] { 1 }, 0));

			assertTrue(peer.sendMessage(new GetPeersMessage()));
			assertEquals("control traffic must not consume or be rejected by the QDN byte budget",
					Peer.MAX_PENDING_QDN_BYTES, peer.getPendingQdnBytes());

			peer.shutdown();
			assertEquals(0L, peer.getPendingQdnBytes());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testQueueDropReleasesReservation() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			Peer peer = peerWithSocket(sockets.sender);
			BlockingQueue<Message> sendQueue =
					(BlockingQueue<Message>) FieldUtils.readField(peer, "sendQueue", true);

			for (int i = 0; i < peer.getSendQueueCapacity(); ++i)
				assertTrue(sendQueue.offer(new GetPeersMessage()));

			assertFalse(peer.sendPreSerializedMessage(
					1, MessageType.ARBITRARY_DATA_FILE, new byte[1024], 0));
			assertEquals(0L, peer.getPendingQdnBytes());
			peer.shutdown();
		}
	}

	@Test
	public void testCompletedWriteReleasesReservation() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			Peer peer = peerWithSocket(sockets.sender);
			byte[] serializedChunk = new byte[4096];

			assertTrue(peer.sendPreSerializedMessage(
					1, MessageType.ARBITRARY_DATA_FILE, serializedChunk, 0));
			assertEquals(serializedChunk.length, peer.getPendingQdnBytes());

			assertFalse("small loopback write should fully drain", peer.writeChannel());
			assertEquals(0L, peer.getPendingQdnBytes());
			peer.shutdown();
		}
	}

	@Test
	public void testWriteFailureReleasesReservation() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			Peer peer = peerWithSocket(sockets.sender);
			byte[] serializedChunk = new byte[4096];

			assertTrue(peer.sendPreSerializedMessage(
					1, MessageType.ARBITRARY_DATA_FILE, serializedChunk, 0));
			sockets.sender.close();

			try {
				peer.writeChannel();
				fail("Expected write on closed channel to fail");
			} catch (IOException expected) {
				// Expected terminal write failure.
			}

			assertEquals(0L, peer.getPendingQdnBytes());
			peer.shutdown();
		}
	}

	@Test
	public void testStalledWriterRemainsBudgetedUntilDisconnect() throws Exception {
		try (SocketPair sockets = SocketPair.open()) {
			sockets.sender.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
			sockets.receiver.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
			Peer peer = peerWithSocket(sockets.sender);
			byte[] serializedChunk = new byte[(int) Peer.MAX_PENDING_QDN_BYTES];

			assertTrue(peer.sendPreSerializedMessage(
					1, MessageType.ARBITRARY_DATA_FILE, serializedChunk, 0));
			assertTrue("unread loopback socket should eventually apply write backpressure", peer.writeChannel());

			assertEquals("the queue entry has moved into outputBuffer", 0, peer.getSendQueueSize());
			assertEquals("currently-writing bytes must remain reserved",
					serializedChunk.length, peer.getPendingQdnBytes());

			peer.shutdown();
			assertEquals(0L, peer.getPendingQdnBytes());
		}
	}

	private static Peer peerWithSocket(SocketChannel socketChannel) throws Exception {
		Peer peer = new Peer(
				new PeerData(PeerAddress.fromString("127.0.0.1:24892")),
				TEST_NETWORK_WITHOUT_SELECTOR);
		FieldUtils.writeField(peer, "socketChannel", socketChannel, true);
		return peer;
	}

	private static final class SocketPair implements AutoCloseable {
		private final ServerSocketChannel server;
		private final SocketChannel sender;
		private final SocketChannel receiver;

		private SocketPair(ServerSocketChannel server, SocketChannel sender, SocketChannel receiver) {
			this.server = server;
			this.sender = sender;
			this.receiver = receiver;
		}

		private static SocketPair open() throws IOException {
			ServerSocketChannel server = ServerSocketChannel.open();
			server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			SocketChannel sender = SocketChannel.open(server.getLocalAddress());
			SocketChannel receiver = server.accept();
			sender.configureBlocking(false);
			return new SocketPair(server, sender, receiver);
		}

		@Override
		public void close() throws IOException {
			this.sender.close();
			this.receiver.close();
			this.server.close();
		}
	}
}
