package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.ApiException;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.network.message.GetTransactionMessage;
import org.qortium.network.message.GetUnconfirmedTransactionsMessage;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageException;
import org.qortium.network.message.TransactionMessage;
import org.qortium.network.message.TransactionSignaturesMessage;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransactionImporterTests extends Common {

	private TransactionImporter transactionImporter;

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", true, true);
		Controller.getInstance().refillLatestBlocksCache();
		this.transactionImporter = new TransactionImporter();
	}

	@After
	public void afterTest() throws DataException {
		if (this.transactionImporter != null)
			this.transactionImporter.shutdown();

		ChatNotifier.getInstance().deregister(null);
		Common.orphanCheck();
	}

	@Test
	public void testPeerChatIngressStoresInDedicatedStoreOnlyAndNotifiesAfterStore() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "peer chat");
		}

		AtomicReference<ChatTransactionData> notifiedChatData = new AtomicReference<>();
		AtomicBoolean storedWhenNotified = new AtomicBoolean(false);

		ChatNotifier.getInstance().register(null, notifiedData -> {
			notifiedChatData.set(notifiedData);

			try (final Repository repository = RepositoryManager.getRepository()) {
				storedWhenNotified.set(repository.getChatStoreRepository().fromSignature(notifiedData.getSignature()) != null);
			} catch (DataException e) {
				throw new RuntimeException(e);
			}
		});

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertNull(repository.getTransactionRepository().fromSignature(chatData.getSignature()));
			assertFalse(repository.getTransactionRepository().exists(chatData.getSignature()));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}

		assertNotNull(notifiedChatData.get());
		assertArrayEquals(chatData.getSignature(), notifiedChatData.get().getSignature());
		assertTrue(storedWhenNotified.get());
	}

	@Test
	public void testPeerChatIngressDropsInvalidSignatureWithoutNotification() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "invalid peer chat");
			chatData.setSignature(new byte[64]);
		}

		AtomicBoolean notified = new AtomicBoolean(false);
		ChatNotifier.getInstance().register(null, notifiedData -> notified.set(true));

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertFalse(repository.getTransactionRepository().exists(chatData.getSignature()));
		}

		assertFalse(notified.get());
	}

	@Test
	public void testPeerChatIngressDeduplicatesQueuedSignatures() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "duplicate peer chat");
		}

		AtomicInteger notificationCount = new AtomicInteger();
		ChatNotifier.getInstance().register(null, notifiedData -> notificationCount.incrementAndGet());

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
		}

		assertEquals(1, notificationCount.get());
	}

	@Test
	public void testQueuedPeerChatAlreadyStoredByLocalApiIsIgnoredWithoutSecondNotification() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "api wins duplicate race");
		}

		AtomicInteger notificationCount = new AtomicInteger();
		AtomicBoolean storedWhenNotified = new AtomicBoolean(false);
		ChatNotifier.getInstance().register(null, notifiedData -> {
			notificationCount.incrementAndGet();

			try (final Repository repository = RepositoryManager.getRepository()) {
				storedWhenNotified.set(repository.getChatStoreRepository().fromSignature(notifiedData.getSignature()) != null);
			} catch (DataException e) {
				throw new RuntimeException(e);
			}
		});

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		assertTrue(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));

		TransactionsResource transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
		assertEquals("true", transactionsResource.processTransaction(rawTransaction(chatData), null));

		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertFalse(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}

		assertEquals(1, notificationCount.get());
		assertTrue(storedWhenNotified.get());
	}

	@Test
	public void testConcurrentLocalApiChatSubmissionsStoreAndNotifyOnce() throws Exception {
		final int workerCount = 8;
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "concurrent api duplicate chat");
		}

		String rawChat = rawTransaction(chatData);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger duplicateFailureCount = new AtomicInteger();
		AtomicInteger notificationCount = new AtomicInteger();
		AtomicBoolean storedWhenNotified = new AtomicBoolean(false);

		ChatNotifier.getInstance().register(null, notifiedData -> {
			notificationCount.incrementAndGet();

			try (final Repository repository = RepositoryManager.getRepository()) {
				storedWhenNotified.set(repository.getChatStoreRepository().fromSignature(notifiedData.getSignature()) != null);
			} catch (DataException e) {
				throw new RuntimeException(e);
			}
		});

		runConcurrently(workerCount, index -> {
			TransactionsResource transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
			try {
				assertEquals("true", transactionsResource.processTransaction(rawChat, null));
				successCount.incrementAndGet();
			} catch (ApiException e) {
				if (ApiError.fromCode(e.error) == ApiError.TRANSACTION_INVALID) {
					duplicateFailureCount.incrementAndGet();
					return;
				}

				throw e;
			}
		});

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}

		assertEquals(1, successCount.get());
		assertEquals(workerCount - 1, duplicateFailureCount.get());
		assertEquals(1, notificationCount.get());
		assertTrue(storedWhenNotified.get());
	}

	@Test
	public void testConcurrentPeerChatMessagesDeduplicateQueueAndNotifyOnce() throws Exception {
		final int workerCount = 8;
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "concurrent peer duplicate chat");
		}

		AtomicInteger notificationCount = new AtomicInteger();
		ChatNotifier.getInstance().register(null, notifiedData -> notificationCount.incrementAndGet());

		runConcurrently(workerCount, index -> this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData)));

		assertTrue(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));

		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertFalse(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
		}

		assertEquals(1, notificationCount.get());
	}

	@Test
	public void testConcurrentPeerQueueAndLocalApiSubmitStoreAndNotifyOnce() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "concurrent api peer duplicate chat");
		}

		String rawChat = rawTransaction(chatData);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger notificationCount = new AtomicInteger();

		ChatNotifier.getInstance().register(null, notifiedData -> notificationCount.incrementAndGet());

		runConcurrently(2, index -> {
			if (index == 0) {
				this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
				return;
			}

			TransactionsResource transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
			assertEquals("true", transactionsResource.processTransaction(rawChat, null));
			successCount.incrementAndGet();
		});

		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertFalse(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}

		assertEquals(1, successCount.get());
		assertEquals(1, notificationCount.get());
	}

	@Test
	public void testPeerChatIngressAlreadyStoredDoesNotNotifyAgain() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "already stored peer chat");
			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();
		}

		AtomicInteger notificationCount = new AtomicInteger();
		ChatNotifier.getInstance().register(null, notifiedData -> notificationCount.incrementAndGet());

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		this.transactionImporter.processChatTransactionsInQueue();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertFalse(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));
			assertEquals(1, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Collections.emptyList(), null));
		}

		assertEquals(0, notificationCount.get());
	}

	@Test
	public void testImporterShutdownIsSafeWithQueuedPeerChat() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "queued during shutdown");
		}

		this.transactionImporter.onNetworkTransactionMessage(null, inboundTransactionMessage(chatData));
		assertTrue(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));

		this.transactionImporter.shutdown();
	}

	@Test
	public void testGetTransactionReturnsStoredChatFromDedicatedStore() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "lookup stored chat");

			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();
		}

		CapturingPeer peer = new CapturingPeer();
		this.transactionImporter.onNetworkGetTransactionMessage(peer, inboundGetTransactionMessage(chatData.getSignature()));
		this.transactionImporter.processNetworkGetTransactionMessages();

		waitForSentMessages(peer, 1);

		assertEquals(1, peer.getSentMessages().size());
		TransactionMessage transactionMessage = (TransactionMessage) parseOutgoing(peer.getSentMessages().get(0));
		assertTrue(transactionMessage.getTransactionData() instanceof ChatTransactionData);
		assertArrayEquals(chatData.getSignature(), transactionMessage.getTransactionData().getSignature());
	}

	@Test
	public void testGetUnconfirmedTransactionsIncludesStoredChatSignatures() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "inventory stored chat");

			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();
		}

		CapturingPeer peer = new CapturingPeer();
		this.transactionImporter.onNetworkGetUnconfirmedTransactionsMessage(peer, new GetUnconfirmedTransactionsMessage());
		this.transactionImporter.processNetworkGetUnconfirmedTransactionsMessages();

		assertEquals(1, peer.getSentMessages().size());
		TransactionSignaturesMessage signaturesMessage = (TransactionSignaturesMessage) parseOutgoing(peer.getSentMessages().get(0));
		assertTrue(signaturesMessage.getSignatures().stream()
				.anyMatch(signature -> Arrays.equals(signature, chatData.getSignature())));
	}

	@Test
	public void testTransactionSignaturesDoesNotRequestStoredChat() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "advertised stored chat");

			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();
		}

		CapturingPeer peer = new CapturingPeer();
		this.transactionImporter.onNetworkTransactionSignaturesMessage(peer,
				inboundTransactionSignaturesMessage(Collections.singletonList(chatData.getSignature())));
		this.transactionImporter.processNetworkTransactionSignaturesMessage();

		assertTrue(peer.getSentMessages().isEmpty());
	}

	@Test
	public void testTransactionSignaturesDoesNotRequestQueuedPeerChat() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "advertised queued chat");
		}

		CapturingPeer peer = new CapturingPeer();
		this.transactionImporter.onNetworkTransactionMessage(peer, inboundTransactionMessage(chatData));
		assertTrue(this.transactionImporter.incomingChatTransactionQueueContains(chatData.getSignature()));

		this.transactionImporter.onNetworkTransactionSignaturesMessage(peer,
				inboundTransactionSignaturesMessage(Collections.singletonList(chatData.getSignature())));
		this.transactionImporter.processNetworkTransactionSignaturesMessage();

		assertTrue(peer.getSentMessages().isEmpty());
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, String message) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				now(),
				Group.NO_GROUP,
				sender.getPublicKey(),
				0L,
				0,
				null);

		ChatTransactionData chatData = new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, null,
				message.getBytes(StandardCharsets.UTF_8), true, false);

		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
		chatTransaction.computeNonce();
		chatTransaction.sign(sender);

		return chatData;
	}

	private static TransactionMessage inboundTransactionMessage(TransactionData transactionData) throws Exception {
		Constructor<TransactionMessage> constructor = TransactionMessage.class.getDeclaredConstructor(int.class, TransactionData.class);
		constructor.setAccessible(true);
		return constructor.newInstance(-1, transactionData);
	}

	private static GetTransactionMessage inboundGetTransactionMessage(byte[] signature) throws Exception {
		Constructor<GetTransactionMessage> constructor = GetTransactionMessage.class.getDeclaredConstructor(int.class, byte[].class);
		constructor.setAccessible(true);
		return constructor.newInstance(-1, signature);
	}

	private static TransactionSignaturesMessage inboundTransactionSignaturesMessage(List<byte[]> signatures) throws Exception {
		Constructor<TransactionSignaturesMessage> constructor = TransactionSignaturesMessage.class.getDeclaredConstructor(int.class, List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(-1, signatures);
	}

	private static String rawTransaction(TransactionData transactionData) throws TransformationException {
		return Base58.encode(TransactionTransformer.toBytes(transactionData));
	}

	private static Message parseOutgoing(Message message) throws MessageException {
		return Message.fromByteBuffer(ByteBuffer.wrap(message.toBytes()));
	}

	private static void waitForSentMessages(CapturingPeer peer, int count) throws InterruptedException {
		long timeout = System.currentTimeMillis() + 2_000L;
		while (peer.getSentMessages().size() < count && System.currentTimeMillis() < timeout) {
			Thread.sleep(10L);
		}
	}

	private static void runConcurrently(int workerCount, ConcurrentAction action) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch readyLatch = new CountDownLatch(workerCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		for (int i = 0; i < workerCount; ++i) {
			final int index = i;
			executor.submit(() -> {
				readyLatch.countDown();

				try {
					startLatch.await();
					action.run(index);
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			});
		}

		assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
		startLatch.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

		rethrowFailure(failure.get());
	}

	private static void rethrowFailure(Throwable failure) throws Exception {
		if (failure == null)
			return;

		if (failure instanceof Exception)
			throw (Exception) failure;

		if (failure instanceof Error)
			throw (Error) failure;

		throw new AssertionError(failure);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	@FunctionalInterface
	private interface ConcurrentAction {
		void run(int index) throws Exception;
	}

	private static class CapturingPeer extends Peer {

		private final List<Message> sentMessages = Collections.synchronizedList(new ArrayList<>());
		private boolean disconnected;

		private CapturingPeer() {
			super(new PeerData(new PeerAddress("127.0.0.1:9084")), Peer.NETWORK);
		}

		@Override
		public boolean sendMessage(Message message) {
			this.sentMessages.add(message);
			return true;
		}

		@Override
		public void disconnect(String reason) {
			this.disconnected = true;
		}

		private List<Message> getSentMessages() {
			synchronized (this.sentMessages) {
				return new ArrayList<>(this.sentMessages);
			}
		}

		@SuppressWarnings("unused")
		private boolean isDisconnected() {
			return this.disconnected;
		}

	}

}
