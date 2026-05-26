package org.qortium.chat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.bouncycastle.util.encoders.Base64;
import org.qortium.api.resource.ChatResource;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.PaymentTestTransaction;
import org.qortium.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatCleanupManagerTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ChatCleanupManager.getInstance().shutdown();
	}

	@After
	public void afterTest() {
		ChatCleanupManager.getInstance().shutdown();
	}

	@Test
	public void testCleanupOnceRemovesExpiredChatsOnly() throws DataException {
		long now = now();
		long expiredTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod() - 1L;
		byte[] expiredSignature = signature(1);
		byte[] currentSignature = signature(2);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			repository.getChatStoreRepository().save(chat(alice, expiredSignature, "expired", expiredTimestamp));
			repository.getChatStoreRepository().save(chat(alice, currentSignature, "current", now));
			repository.saveChanges();
		}

		ChatCleanupManager.getInstance().cleanupOnce();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNull(repository.getChatStoreRepository().fromSignature(expiredSignature));
			ChatTransactionData currentChatData = repository.getChatStoreRepository().fromSignature(currentSignature);
			assertNotNull(currentChatData);

			ChatMessage currentMessage = repository.getChatStoreRepository().toChatMessage(currentChatData, ChatMessage.Encoding.BASE64);
			assertEquals(Base64.toBase64String(bytes("current")), currentMessage.getData());
		}
	}

	@Test
	public void testCleanupDoesNotTouchNormalTransactions() throws DataException {
		byte[] paymentSignature;
		long now = now();
		long expiredTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod() - 1L;
		byte[] expiredChatSignature = signature(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			repository.getChatStoreRepository().save(chat(alice, expiredChatSignature, "expired", expiredTimestamp));
			repository.saveChanges();

			TransactionData paymentData = PaymentTestTransaction.randomTransaction(repository, alice, true);
			TransactionUtils.signAndImportValid(repository, paymentData, alice);
			paymentSignature = paymentData.getSignature();
		}

		ChatCleanupManager.getInstance().cleanupOnce();

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNull(repository.getChatStoreRepository().fromSignature(expiredChatSignature));
			assertNotNull(repository.getTransactionRepository().fromSignature(paymentSignature));
		}
	}

	@Test
	public void testCleanupWhileReadingChatDoesNotBreakCurrentMessages() throws Exception {
		final int readerCount = 4;
		long now = now();
		long expiredTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod() - 1L;
		byte[] expiredSignature = signature(4);
		byte[] currentSignature = signature(5);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			repository.getChatStoreRepository().save(chat(alice, expiredSignature, "expired", expiredTimestamp));
			repository.getChatStoreRepository().save(chat(alice, currentSignature, "current", now));
			repository.saveChanges();
		}

		runConcurrently(readerCount + 1, index -> {
			if (index == 0) {
				ChatCleanupManager.getInstance().cleanupOnce();
				return;
			}

			ChatResource chatResource = (ChatResource) ApiCommon.buildResource(ChatResource.class);
			for (int i = 0; i < 10; ++i) {
				List<ChatMessage> messages = chatResource.searchChat(
						null, null, Group.NO_GROUP, Collections.emptyList(), null, null, null,
						ChatMessage.Encoding.BASE64, null, null, null);

				assertTrue(messages.stream()
						.anyMatch(message -> Arrays.equals(message.getSignature(), currentSignature)));
			}
		});

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNull(repository.getChatStoreRepository().fromSignature(expiredSignature));
			assertNotNull(repository.getChatStoreRepository().fromSignature(currentSignature));
		}
	}

	@Test
	public void testStartAndShutdownAreIdempotentAndRestartable() {
		ChatCleanupManager manager = ChatCleanupManager.getInstance();

		manager.start();
		manager.start();
		manager.shutdown();
		manager.shutdown();
		manager.start();
		manager.shutdown();
	}

	private static ChatTransactionData chat(TestAccount sender, byte[] signature, String message, long timestamp) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, null,
				message.getBytes(StandardCharsets.UTF_8), true, false);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private static byte[] signature(int value) {
		byte[] signature = new byte[64];
		signature[63] = (byte) value;
		return signature;
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
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

	@FunctionalInterface
	private interface ConcurrentAction {
		void run(int index) throws Exception;
	}

}
