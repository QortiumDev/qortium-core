package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.ApiService;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.block.BlockChain;
import org.qortium.controller.ChatNotifier;
import org.qortium.controller.Controller;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.RewardShareTransactionData;
import org.qortium.data.transaction.TransactionConfirmationTimingData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransactionsApiTests extends ApiCommon {

	private TransactionsResource transactionsResource;

	@Before
	public void buildResource() throws Exception {
		this.transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
		// processTransaction requires a recent local chain tip and, outside single-node testnet mode, peers.
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", true, true);
		Controller.getInstance().refillLatestBlocksCache();
	}

	@Test
	public void test() {
		assertNotNull(this.transactionsResource);
	}

	@Test
	public void testGetPendingTransactions() {
		for (Integer txGroupId : Arrays.asList(null, 0, 1)) {
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, null, null, null));
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, 1, 1, true));
		}
	}

	@Test
	public void testGetUnconfirmedTransactions() {
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, null, null, null));
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, 1, 1, true));
	}

	@Test
	public void testLiteGetAddressTransactionsFailsClearlyWithoutPeerData() throws Exception {
		useLiteMode();

		assertApiError(org.qortium.api.ApiError.NO_REPLY,
				() -> this.transactionsResource.getAddressTransactions(aliceAddress, null, null, null));
	}

	@Test
	public void testProcessChatStoresInDedicatedStoreOnly() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "local chat", now());
		}

		assertEquals("true", this.transactionsResource.processTransaction(rawTransaction(chatData), null));

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertNull(repository.getTransactionRepository().fromSignature(chatData.getSignature()));
			assertFalse(repository.getTransactionRepository().exists(chatData.getSignature()));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}
	}

	@Test
	public void testProcessChatNotifiesAfterDedicatedStoreSave() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "local chat notify", now());
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

		try {
			assertEquals("true", this.transactionsResource.processTransaction(rawTransaction(chatData), null));
		} finally {
			ChatNotifier.getInstance().deregister(null);
		}

		assertNotNull(notifiedChatData.get());
		assertTrue(Arrays.equals(chatData.getSignature(), notifiedChatData.get().getSignature()));
		assertTrue(storedWhenNotified.get());
	}

	@Test
	public void testProcessChatApiV2ReturnsTransactionData() throws Exception {
		useApiVersion(2);

		ChatTransactionData chatData;
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "local chat v2", now());
		}

		String response = this.transactionsResource.processTransaction(rawTransaction(chatData), null);

		assertTrue(response.contains("\"type\":\"CHAT\""));
		assertTrue(response.contains("\"signature\""));

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
		}
	}

	@Test
	public void testProcessChatRejectsInvalidSignature() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, "bad signature", now());
			chatData.setSignature(new byte[64]);
		}

		assertApiError(ApiError.INVALID_SIGNATURE,
				() -> this.transactionsResource.processTransaction(rawTransactionUnchecked(chatData), null));
	}

	@Test
	public void testProcessChatRejectsInvalidChatData() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = signedChat(repository, alice, new byte[0], now());
		}

		AtomicBoolean notified = new AtomicBoolean(false);
		ChatNotifier.getInstance().register(null, notifiedData -> notified.set(true));

		try {
			assertApiError(ApiError.TRANSACTION_INVALID,
					() -> this.transactionsResource.processTransaction(rawTransactionUnchecked(chatData), null));
		} finally {
			ChatNotifier.getInstance().deregister(null);
		}

		assertFalse(notified.get());
	}

	@Test
	public void testProcessOrdinaryTransactionStillUsesUnconfirmedPath() throws DataException, TransformationException {
		PaymentTransactionData paymentData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			paymentData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 1L);

			Transaction transaction = Transaction.fromData(repository, paymentData);
			transaction.sign(alice);
		}

		assertEquals("true", this.transactionsResource.processTransaction(rawTransaction(paymentData), null));

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertTrue(repository.getTransactionRepository().exists(paymentData.getSignature()));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().stream()
					.anyMatch(transactionData -> Arrays.equals(transactionData.getSignature(), paymentData.getSignature())));
		}
	}

	@Test
	public void testComputeMempowFeeNonceForOrdinaryTransaction() throws Exception {
		ApiCommon.installTestApiKey();
		TransactionsResource authenticatedTransactionsResource =
				(TransactionsResource) ApiCommon.buildResource(TransactionsResource.class, ApiCommon.TEST_API_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			int previousDifficulty = setFeeAlternativeDifficulty(1);

			try {
				TestAccount bob = Common.getTestAccount(repository, "bob");
				BaseTransactionData baseTransactionData = new BaseTransactionData(
						TransactionUtils.nextTimestamp(repository),
						Group.NO_GROUP,
						bob.getPublicKey(),
						0L,
						null);
				JoinGroupTransactionData joinGroupTransactionData = new JoinGroupTransactionData(baseTransactionData,
						TestChainBootstrapUtils.MINTING_GROUP_ID, null);

				String computedRawTransaction = authenticatedTransactionsResource.computeMempowFeeNonce(
						ApiCommon.TEST_API_KEY, rawTransaction(joinGroupTransactionData));
				TransactionData computedTransactionData = unsignedTransaction(computedRawTransaction);

				assertEquals(TransactionType.JOIN_GROUP, computedTransactionData.getType());
				assertNotNull(computedTransactionData.getNonceOrNull());
				assertNull(computedTransactionData.getSignature());

				Transaction transaction = Transaction.fromData(repository, computedTransactionData);
				assertEquals(Transaction.ValidationResult.OK, transaction.isFeeValid());
			} finally {
				setFeeAlternativeDifficulty(previousDifficulty);
			}
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testSearchTransactions() {
		List<TransactionType> txTypes = Arrays.asList(TransactionType.PAYMENT, TransactionType.ISSUE_ASSET);

		for (Integer startBlock : Arrays.asList(null, 1))
			for (Integer blockLimit : Arrays.asList(null, 1))
				for (Integer txGroupId : Arrays.asList(null, 1))
					for (String address : Arrays.asList(null, aliceAddress))
						for (ConfirmationStatus confirmationStatus : ConfirmationStatus.values()) {
							if (confirmationStatus != ConfirmationStatus.CONFIRMED) {
								startBlock = null;
								blockLimit = null;
							}

							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, null, null, null));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, 1, 1, true));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, null, address, confirmationStatus, 1, 1, true));
						}

		assertNotNull(this.transactionsResource.searchTransactions(null, null, null, null, aliceAddress, null, 10, null, true));
	}

	@Test
	public void testConfirmationTimingForOrdinaryTransaction()
			throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PaymentTransactionData paymentTransactionData = new PaymentTransactionData(
					TestTransaction.generateBase(alice), bob.getAddress(), 1L);

			TransactionConfirmationTimingData timing = this.transactionsResource
					.getTransactionConfirmationTiming(rawTransaction(paymentTransactionData));

			assertEquals(TransactionType.PAYMENT, timing.getTransactionType());
			assertEquals(TransactionType.PAYMENT.value, timing.getTransactionTypeValue());
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), timing.getCurrentHeight());
			assertEquals(timing.getCurrentHeight() + 1, timing.getCandidateHeight());
			assertTrue(timing.isTransactionConfirmable());
			assertTrue(timing.isConfirmableAtCandidateHeight());
			assertNull(timing.getFirstConfirmableHeight());
			assertNull(timing.getConfirmationDelayBlocks());
			assertNull(timing.getDelayReason());
		}
	}

	@Test
	public void testConfirmationTimingForRateAccountWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			RateAccountTransactionData transactionData = new RateAccountTransactionData(
					TestTransaction.generateBase(alice), bob.getPublicKey(), 4);

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.RATE_ACCOUNT);
		}
	}

	@Test
	public void testDelayedRateAccountRemainsVisibleAsUnconfirmed()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			RateAccountTransactionData transactionData = new RateAccountTransactionData(
					TestTransaction.generateBase(alice), bob.getPublicKey(), 4);

			TransactionUtils.signAndImportValid(repository, transactionData, alice);
			assertFalse(repository.getTransactionRepository().isConfirmed(transactionData.getSignature()));
			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.RATE_ACCOUNT);
			assertUnconfirmedApiContains(TransactionType.RATE_ACCOUNT, alice.getPublicKey(), transactionData);

			BlockUtils.mintBlock(repository);
			assertEquals(90, repository.getBlockRepository().getBlockchainHeight());
			assertFalse(repository.getTransactionRepository().isConfirmed(transactionData.getSignature()));
			assertUnconfirmedApiContains(TransactionType.RATE_ACCOUNT, alice.getPublicKey(), transactionData);

			mintToHeight(repository, 100);
			assertFalse(repository.getTransactionRepository().isConfirmed(transactionData.getSignature()));
			assertUnconfirmedApiContains(TransactionType.RATE_ACCOUNT, alice.getPublicKey(), transactionData);

			BlockUtils.mintBlock(repository);
			assertEquals(101, repository.getBlockRepository().getBlockchainHeight());
			assertTrue(repository.getTransactionRepository().isConfirmed(transactionData.getSignature()));
			assertUnconfirmedApiDoesNotContain(TransactionType.RATE_ACCOUNT, alice.getPublicKey(), transactionData);
		}
	}

	@Test
	public void testConfirmationTimingForRewardShareWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			TransactionData transactionData = AccountUtils.createRewardShare(repository, chloe, chloe,
					-100, 1L * Amounts.MULTIPLIER);

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.REWARD_SHARE);
		}
	}

	@Test
	public void testConfirmationTimingForTransferPrivsWindowDelay()
			throws DataException, IllegalAccessException, TransformationException {
		useShortProtectedWindow();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintToHeight(repository, 89);
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.generateRandomSeedAccount(repository);
			BaseTransactionData baseTransactionData = new BaseTransactionData(TransactionUtils.nextTimestamp(repository),
					Group.NO_GROUP, alice.getPublicKey(), 1L * Amounts.MULTIPLIER, null);
			TransferPrivsTransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData,
					recipient.getAddress());

			assertProtectedWindowDelay(repository, rawTransaction(transactionData), TransactionType.TRANSFER_PRIVS);
		}
	}

	/**
	 * A non-base58 body used to escape /convert as an uncaught NumberFormatException, which the caller
	 * saw as a bare 500. /decode has always reported the same input as INVALID_DATA.
	 */
	@Test
	public void testConvertTransactionForSigningRejectsNonBase58Body() {
		for (String nonBase58Body : new String[] { "not base58!", "0OIl", "{\"type\":\"PAYMENT\"}" }) {
			assertApiError(ApiError.INVALID_DATA,
					() -> this.transactionsResource.convertTransactionForSigning(nonBase58Body));

			// /convert must agree with its sibling endpoint on the same input.
			assertApiError(ApiError.INVALID_DATA,
					() -> this.transactionsResource.decodeTransaction(nonBase58Body, false));
		}
	}

	private void assertProtectedWindowDelay(Repository repository, String rawTransaction, TransactionType transactionType)
			throws DataException {
		TransactionConfirmationTimingData timing = this.transactionsResource
				.getTransactionConfirmationTiming(rawTransaction);

		assertEquals(transactionType, timing.getTransactionType());
		assertEquals(transactionType.value, timing.getTransactionTypeValue());
		assertEquals(repository.getBlockRepository().getBlockchainHeight(), timing.getCurrentHeight());
		assertEquals(90, timing.getCandidateHeight());
		assertTrue(timing.isTransactionConfirmable());
		assertFalse(timing.isConfirmableAtCandidateHeight());
		assertEquals(Integer.valueOf(101), timing.getFirstConfirmableHeight());
		assertEquals(Integer.valueOf(11), timing.getConfirmationDelayBlocks());
		assertEquals("PROTECTED_ONLINE_ACCOUNT_WINDOW", timing.getDelayReason());
	}

	private void assertUnconfirmedApiContains(TransactionType transactionType, byte[] creatorPublicKey,
			TransactionData expectedTransactionData) {
		assertTrue(this.transactionsResource.getUnconfirmedTransactions(Arrays.asList(transactionType),
				Base58.encode(creatorPublicKey), null, null, null).stream()
				.anyMatch(transactionData -> Arrays.equals(transactionData.getSignature(),
						expectedTransactionData.getSignature())));
	}

	private void assertUnconfirmedApiDoesNotContain(TransactionType transactionType, byte[] creatorPublicKey,
			TransactionData expectedTransactionData) {
		assertFalse(this.transactionsResource.getUnconfirmedTransactions(Arrays.asList(transactionType),
				Base58.encode(creatorPublicKey), null, null, null).stream()
				.anyMatch(transactionData -> Arrays.equals(transactionData.getSignature(),
						expectedTransactionData.getSignature())));
	}

	private static void useShortProtectedWindow() throws IllegalAccessException {
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);
	}

	private static void mintToHeight(Repository repository, int targetHeight) throws DataException {
		int blocksToMint = targetHeight - repository.getBlockRepository().getBlockchainHeight();
		if (blocksToMint > 0)
			BlockUtils.mintBlocks(repository, blocksToMint);
		assertEquals(targetHeight, repository.getBlockRepository().getBlockchainHeight());
	}

	private static String rawTransaction(TransactionData transactionData) throws TransformationException {
		return Base58.encode(TransactionTransformer.toBytes(transactionData));
	}

	private static String rawTransactionUnchecked(TransactionData transactionData) {
		try {
			return rawTransaction(transactionData);
		} catch (TransformationException e) {
			throw new RuntimeException(e);
		}
	}

	private static TransactionData unsignedTransaction(String rawBytes58) throws TransformationException {
		byte[] rawBytes = Bytes.concat(Base58.decode(rawBytes58), new byte[TransactionTransformer.SIGNATURE_LENGTH]);
		TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
		transactionData.setSignature(null);
		return transactionData;
	}

	private static int setFeeAlternativeDifficulty(int difficulty) throws IllegalAccessException {
		Object mempowSettings = FieldUtils.readField(BlockChain.getInstance(), "mempowSettings", true);
		Integer previousDifficulty = (Integer) FieldUtils.readField(mempowSettings, "feeAlternativeDifficulty", true);
		FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", difficulty, true);
		return previousDifficulty;
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, String message, long timestamp) throws DataException {
		return signedChat(repository, sender, message.getBytes(StandardCharsets.UTF_8), timestamp);
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, byte[] data, long timestamp) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				sender.getPublicKey(),
				0L,
				0,
				null);

		ChatTransactionData chatData = new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, null, data, true, false);
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
		chatTransaction.computeNonce();
		chatTransaction.sign(sender);
		return chatData;
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private void useApiVersion(int apiVersion) throws IllegalAccessException {
		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
				ApiCommon.class.getClassLoader(),
				new Class[] { HttpServletRequest.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getHeader":
							return ApiService.API_VERSION_HEADER.equals(args[0]) ? String.valueOf(apiVersion) : null;
						case "getRemoteAddr":
							return "127.0.0.1";
						case "getLocale":
							return Locale.getDefault();
						case "getHeaderNames":
							return Collections.emptyEnumeration();
						case "getMethod":
							return "POST";
						case "getRequestURI":
							return "/transactions/process";
						case "getServerName":
							return "localhost";
						case "toString":
							return "ApiVersionTestRequest";
						default:
							Class<?> returnType = method.getReturnType();
							if (returnType == boolean.class)
								return false;
							if (returnType == int.class)
								return 0;
							if (returnType == long.class)
								return 0L;
							return null;
					}
				});

		FieldUtils.writeField(this.transactionsResource, "request", request, true);
	}

}
