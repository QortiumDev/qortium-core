package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.message.ArbitraryDataFileWantMessage;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageException;
import org.qortium.network.message.MessageType;
import org.qortium.test.common.Common;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerSendManagerBackpressureTests extends Common {

	private static final int CHUNK_COUNT = 17;
	private static final int CHUNK_SIZE = 1024 * 1024;
	private static final int LIGHTWEIGHT_CHUNK_SIZE = 128;

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testReliablePushLargerThanPeerBudgetRetriesUntilAllChunksAreAccepted() throws Exception {
		assertTrue((long) CHUNK_COUNT * CHUNK_SIZE > Peer.MAX_PENDING_QDN_BYTES);

		RetryPeer peer = new RetryPeer(CHUNK_COUNT);
		PeerSendManager manager = new PeerSendManager(peer, true);
		byte[] sharedSerializedChunk = new byte[CHUNK_SIZE];

		try {
			for (int i = 0; i < CHUNK_COUNT; ++i) {
				int messageId = i;
				assertTrue(manager.queueMessageFactoryWithRetry(
						() -> new FixedSerializedMessage(messageId, sharedSerializedChunk),
						CHUNK_SIZE,
						"chunk-" + messageId));
			}

			assertTrue("manager should observe peer byte/count backpressure",
					peer.firstRejection.await(5, TimeUnit.SECONDS));
			assertEquals(0, peer.acceptedCount.get());
			AtomicInteger activeSenderMessages =
					(AtomicInteger) FieldUtils.readField(manager, "activeSenderMessages", true);
			assertTrue(activeSenderMessages.get() > 0);
			assertTrue("a sender-local reliable retry must remain visible as pending work",
					manager.hasPendingMessages());

			peer.accepting.set(true);
			assertTrue("all reliable push chunks should be retried and admitted",
					peer.allAccepted.await(5, TimeUnit.SECONDS));
			assertEquals(CHUNK_COUNT, peer.acceptedCount.get());
			assertTrue("at least one chunk should have been attempted more than once",
					peer.attemptCount.get() > CHUNK_COUNT);
			waitForReliablePendingCount(manager, 0);
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	@Test
	public void testReliableDeduplicationIsScopedByResourceSignature() throws Exception {
		RetryPeer peer = new RetryPeer(2);
		PeerSendManager manager = new PeerSendManager(peer, true);
		byte[] sharedSerializedChunk = new byte[LIGHTWEIGHT_CHUNK_SIZE];
		String rawHash = "same-raw-hash";
		String signatureAKey = "signature-a:" + rawHash;
		String signatureBKey = "signature-b:" + rawHash;

		try {
			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new FixedSerializedMessage(1, sharedSerializedChunk),
					LIGHTWEIGHT_CHUNK_SIZE,
					rawHash,
					signatureAKey));
			assertTrue("same hash under a different signature is a distinct payload",
					manager.queueMessageFactoryWithRetry(
							() -> new FixedSerializedMessage(2, sharedSerializedChunk),
							LIGHTWEIGHT_CHUNK_SIZE,
							rawHash,
							signatureBKey));
			assertFalse("same signature and hash must still deduplicate",
					manager.queueMessageFactoryWithRetry(
							() -> new FixedSerializedMessage(3, sharedSerializedChunk),
							LIGHTWEIGHT_CHUNK_SIZE,
							rawHash,
							signatureAKey));

			assertEquals(2, manager.getPendingReliableMessageCount());
			assertTrue(manager.isHashQueued(rawHash));
			assertEquals("raw hash tracking remains unique while reference-counted",
					1, manager.getTrackedHashCount());
			assertTrue(peer.firstRejection.await(5, TimeUnit.SECONDS));

			peer.accepting.set(true);
			assertTrue(peer.allAccepted.await(5, TimeUnit.SECONDS));
			waitForReliablePendingCount(manager, 0);
			assertFalse(manager.isHashQueued(rawHash));
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	@Test
	public void testActiveHashTrackingSurvivesAgeCleanup() throws Exception {
		RetryPeer peer = new RetryPeer(1);
		PeerSendManager manager = new PeerSendManager(peer, true);
		byte[] sharedSerializedChunk = new byte[LIGHTWEIGHT_CHUNK_SIZE];
		String rawHash = "aged-active-hash";

		try {
			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new FixedSerializedMessage(1, sharedSerializedChunk),
					LIGHTWEIGHT_CHUNK_SIZE,
					rawHash,
					"signature:" + rawHash));
			assertTrue(peer.firstRejection.await(5, TimeUnit.SECONDS));

			@SuppressWarnings("unchecked")
			Map<String, Object> queuedHashes =
					(Map<String, Object>) FieldUtils.readField(manager, "queuedHashes", true);
			Object state = queuedHashes.get(rawHash);
			FieldUtils.writeField(state, "lastQueuedTimestamp",
					System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(61), true);

			manager.cleanupStaleHashTracking();

			assertTrue("age cleanup must not remove active ownership", manager.isHashQueued(rawHash));
			assertEquals(1, manager.getTrackedHashCount());
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	@Test
	public void testTerminalDiskPathsReleaseHashTracking() throws Exception {
		RetryPeer peer = new RetryPeer(0);
		PeerSendManager manager = new PeerSendManager(peer, true);

		try {
			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> null, LIGHTWEIGHT_CHUNK_SIZE,
					"factory-null", "signature:factory-null"));
			waitForReliablePendingCount(manager, 0);
			assertEquals(0, manager.getTrackedHashCount());

			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new NullSerializedMessage(2), LIGHTWEIGHT_CHUNK_SIZE,
					"serialize-null", "signature:serialize-null"));
			waitForReliablePendingCount(manager, 0);
			assertEquals(0, manager.getTrackedHashCount());

			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new ThrowingSerializedMessage(3), LIGHTWEIGHT_CHUNK_SIZE,
					"serialize-error", "signature:serialize-error"));
			waitForReliablePendingCount(manager, 0);
			assertEquals(0, manager.getTrackedHashCount());
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}

		RetryPeer disconnectPeer = new RetryPeer(0);
		PeerSendManager disconnectManager = new PeerSendManager(disconnectPeer, true);
		CountDownLatch factoryStarted = new CountDownLatch(1);
		CountDownLatch releaseFactory = new CountDownLatch(1);
		try {
			assertTrue(disconnectManager.queueMessageFactoryWithRetry(
					() -> {
						factoryStarted.countDown();
						try {
							releaseFactory.await();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return null;
						}
						return new FixedSerializedMessage(4, new byte[LIGHTWEIGHT_CHUNK_SIZE]);
					},
					LIGHTWEIGHT_CHUNK_SIZE,
					"disconnect", "signature:disconnect"));
			assertTrue(factoryStarted.await(5, TimeUnit.SECONDS));

			disconnectPeer.getSocketChannel().close();
			releaseFactory.countDown();

			waitForReliablePendingCount(disconnectManager, 0);
			assertEquals(0, disconnectManager.getTrackedHashCount());
		} finally {
			releaseFactory.countDown();
			disconnectManager.shutdown();
			disconnectPeer.getSocketChannel().close();
		}
	}

	@Test
	public void testReliablePendingFactoriesAreDeduplicatedAndCappedPerPeer() throws Exception {
		int reliableLimit = PeerSendManager.MAX_PENDING_RELIABLE_MESSAGES;
		RetryPeer peer = new RetryPeer(reliableLimit);
		PeerSendManager manager = new PeerSendManager(peer, true);
		byte[] sharedSerializedChunk = new byte[LIGHTWEIGHT_CHUNK_SIZE];

		try {
			assertEquals(ArbitraryDataFileWantMessage.MAX_HASHES_PER_MESSAGE, reliableLimit);
			assertEquals(reliableLimit, manager.getPendingReliableMessageLimit());
			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new FixedSerializedMessage(0, sharedSerializedChunk),
					LIGHTWEIGHT_CHUNK_SIZE,
					"chunk-0"));
			assertFalse("same chunk must not retain a second lazy factory",
					manager.queueMessageFactoryWithRetry(
							() -> new FixedSerializedMessage(10_000, sharedSerializedChunk),
							LIGHTWEIGHT_CHUNK_SIZE,
							"chunk-0"));

			for (int i = 1; i < reliableLimit; ++i) {
				int messageId = i;
				assertTrue(manager.queueMessageFactoryWithRetry(
						() -> new FixedSerializedMessage(messageId, sharedSerializedChunk),
						LIGHTWEIGHT_CHUNK_SIZE,
						"chunk-" + messageId));
			}

			for (int i = reliableLimit; i < reliableLimit + 100; ++i) {
				int messageId = i;
				assertFalse("over-limit reliable factories must not be retained",
						manager.queueMessageFactoryWithRetry(
								() -> new FixedSerializedMessage(messageId, sharedSerializedChunk),
								LIGHTWEIGHT_CHUNK_SIZE,
								"chunk-" + messageId));
			}

			assertTrue("senders should be pinned while the fake peer rejects admission",
					peer.firstRejection.await(5, TimeUnit.SECONDS));
			assertEquals("all reliable pipeline stages together must stay capped",
					reliableLimit, manager.getPendingReliableMessageCount());
			assertEquals("reliable WANT metadata has a separate protocol-sized budget",
					0, manager.getPendingOrdinaryFactoryMessageCount());

			peer.accepting.set(true);
			assertTrue("every admitted reliable message should eventually progress",
					peer.allAccepted.await(30, TimeUnit.SECONDS));
			assertEquals(reliableLimit, peer.acceptedCount.get());
			waitForReliablePendingCount(manager, 0);
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	@Test
	public void testGeneralLazyFactoryQueueHonorsDocumentedCapacity() throws Exception {
		int factoryLimit = PeerSendManager.MAX_PENDING_ORDINARY_FACTORY_MESSAGES;
		RetryPeer peer = new RetryPeer(0);
		PeerSendManager manager = new PeerSendManager(peer, true);
		CountDownLatch factoriesStarted = new CountDownLatch(2);
		CountDownLatch releaseFactories = new CountDownLatch(1);
		byte[] sharedSerializedChunk = new byte[CHUNK_SIZE];
		MessageFactory blockingFactory = () -> {
			factoriesStarted.countDown();
			try {
				releaseFactories.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			return new FixedSerializedMessage(0, sharedSerializedChunk);
		};

		try {
			manager.queueMessageFactory(blockingFactory, CHUNK_SIZE);
			manager.queueMessageFactory(blockingFactory, CHUNK_SIZE);
			assertTrue(factoriesStarted.await(5, TimeUnit.SECONDS));

			for (int i = 2; i < factoryLimit + 5; ++i)
				manager.queueMessageFactory(blockingFactory, CHUNK_SIZE);

			assertEquals(factoryLimit, manager.getPendingOrdinaryFactoryMessageLimit());
			assertEquals("active factory loads plus queued closures must stay capped",
					factoryLimit, manager.getPendingOrdinaryFactoryMessageCount());
			assertEquals(factoryLimit - 2, manager.getQueueMessageSize());
		} finally {
			releaseFactories.countDown();
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	@Test
	public void testShutdownClosesReliableAdmissionAndReleasesRetryToken() throws Exception {
		RetryPeer peer = new RetryPeer(1);
		PeerSendManager manager = new PeerSendManager(peer, true);
		byte[] sharedSerializedChunk = new byte[CHUNK_SIZE];

		try {
			assertTrue(manager.queueMessageFactoryWithRetry(
					() -> new FixedSerializedMessage(0, sharedSerializedChunk),
					CHUNK_SIZE,
					"chunk-0"));
			assertTrue(peer.firstRejection.await(5, TimeUnit.SECONDS));

			manager.shutdown();

			assertEquals(0, manager.getPendingReliableMessageCount());
			assertEquals(0, manager.getPendingOrdinaryFactoryMessageCount());
			assertFalse("shutdown manager must reject post-clear admission",
					manager.queueMessageFactoryWithRetry(
							() -> new FixedSerializedMessage(1, sharedSerializedChunk),
							CHUNK_SIZE,
							"chunk-1"));
		} finally {
			manager.shutdown();
			peer.getSocketChannel().close();
		}
	}

	private static void waitForReliablePendingCount(PeerSendManager manager, int expected)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (manager.getPendingReliableMessageCount() != expected
				&& System.nanoTime() < deadline) {
			Thread.sleep(10L);
		}
		assertEquals(expected, manager.getPendingReliableMessageCount());
	}

	private static final class RetryPeer extends Peer {
		private static final int TEST_NETWORK_WITHOUT_SELECTOR = 99;

		private final AtomicBoolean accepting = new AtomicBoolean();
		private final AtomicInteger attemptCount = new AtomicInteger();
		private final AtomicInteger acceptedCount = new AtomicInteger();
		private final CountDownLatch firstRejection = new CountDownLatch(1);
		private final CountDownLatch allAccepted;

		private RetryPeer(int expectedMessages) throws Exception {
			super(new PeerData(PeerAddress.fromString("127.0.0.1:24894")),
					TEST_NETWORK_WITHOUT_SELECTOR);
			SocketChannel socketChannel = SocketChannel.open();
			FieldUtils.writeField(this, "socketChannel", socketChannel, true);
			FieldUtils.writeField(this, "resolvedAddress",
					new InetSocketAddress("127.0.0.1", 24894), true);
			this.allAccepted = new CountDownLatch(expectedMessages);
		}

		@Override
		public boolean sendPreSerializedMessage(int messageId, MessageType messageType,
											  byte[] serializedBytes, int timeout) {
			this.attemptCount.incrementAndGet();
			if (!this.accepting.get()) {
				this.firstRejection.countDown();
				return false;
			}

			this.acceptedCount.incrementAndGet();
			this.allAccepted.countDown();
			return true;
		}
	}

	private static final class FixedSerializedMessage extends Message {
		private final byte[] serializedBytes;

		private FixedSerializedMessage(int id, byte[] serializedBytes) {
			super(id, MessageType.ARBITRARY_DATA_FILE);
			this.serializedBytes = serializedBytes;
		}

		@Override
		public byte[] toBytes() throws MessageException {
			return this.serializedBytes;
		}
	}

	private static final class NullSerializedMessage extends Message {
		private NullSerializedMessage(int id) {
			super(id, MessageType.ARBITRARY_DATA_FILE);
		}

		@Override
		public byte[] toBytes() {
			return null;
		}
	}

	private static final class ThrowingSerializedMessage extends Message {
		private ThrowingSerializedMessage(int id) {
			super(id, MessageType.ARBITRARY_DATA_FILE);
		}

		@Override
		public byte[] toBytes() throws MessageException {
			throw new MessageException("test serialization failure");
		}
	}
}
