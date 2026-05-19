package org.qortal.chat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.PaymentTestTransaction;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
			assertNotNull(repository.getChatStoreRepository().fromSignature(currentSignature));
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

}
