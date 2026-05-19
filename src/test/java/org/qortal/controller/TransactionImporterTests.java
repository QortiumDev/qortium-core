package org.qortal.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.network.message.TransactionMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.transaction.ChatTransaction;
import org.qortal.utils.NTP;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
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

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

}
