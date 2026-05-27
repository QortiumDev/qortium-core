package org.qortium.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.*;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.MessageTransactionTransformer;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.NTP;

import java.util.List;
import static org.junit.Assert.*;

public class MessageTests extends Common {

	private static final int TEST_POW_BUFFER_SIZE = 8 * 1024;
	private static final int TEST_POW_DIFFICULTY = 4;
	private static final String recipient = Common.getTestAccount(null, "bob").getAddress();


	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void validityTests() throws DataException {
		// with recipient, with amount
		assertTrue(isValid(Group.NO_GROUP, recipient, 123L, Asset.NATIVE));

		// with recipient, no amount
		assertTrue(isValid(Group.NO_GROUP, recipient, 0L, null));

		// no recipient (message to group), no amount
		assertTrue(isValid(Group.NO_GROUP, null, 0L, null));

		// can't have amount if no recipient!
		assertFalse(isValid(Group.NO_GROUP, null, 123L, Asset.NATIVE));

		// Alice is part of group 1
		assertTrue(isValid(1, null, 0L, null));

		int newGroupId;
		try (final Repository repository = RepositoryManager.getRepository()) {
			newGroupId = GroupUtils.createGroup(repository, "chloe", "non-alice-group", false, ApprovalThreshold.ONE, 10, 1440);
		}

		// Alice is not part of new group
		assertFalse(isValid(newGroupId, null, 0L, null));
	}

	@Test
	public void noFeeNoNonce() throws DataException {
		testFeeNonce(false, false, false);
	}

	@Test
	public void withFeeNoNonce() throws DataException {
		testFeeNonce(true, false, true);
	}

	@Test
	public void noFeeWithNonce() throws DataException {
		testFeeNonce(false, true, true);
	}

	@Test
	public void withFeeWithNonce() throws DataException {
		testFeeNonce(true, true, true);
	}

	@Test
	public void withRecipentNoAmount() throws DataException {
		testMessage(Group.NO_GROUP, recipient, 0L, null);
	}

	@Test
	public void withRecipientWithAmount() throws DataException {
		testMessage(Group.NO_GROUP, recipient, 123L, Asset.NATIVE);
	}

	@Test
	public void noRecipentNoAmount() throws DataException {
		testMessage(Group.NO_GROUP, null, 0L, null);
	}

	@Test
	public void noRecipentNoAmountWithGroup() throws DataException {
		testMessage(1, null, 0L, null);
	}

	@Test
	public void atRecipientNoFeeWithNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String atRecipient = deployAt();
			MessageTransaction transaction = testFeeNonce(repository, false, true, atRecipient, true);

			// Transaction should be confirmable because it's to an AT.
			assertTrue(transaction.isConfirmable());
			assertTrue(transaction.isSignatureValid());
			importUnconfirmed(transaction);
			assertEquals(BlockChain.getInstance().getMessagePowDifficultyConfirmable(), transaction.getPoWDifficulty());
		}
	}

	@Test
	public void regularRecipientNoFeeWithNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Transaction should not be present in db yet
			List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, recipient, null, null, null);
			assertTrue(messageTransactionsData.isEmpty());

			MessageTransaction transaction = testFeeNonce(repository, false, true, recipient, true);

			// Transaction shouldn't be confirmable because it's not to an AT.
			assertFalse(transaction.isConfirmable());
			assertTrue(transaction.isSignatureValid());
			importUnconfirmed(transaction);
			assertEquals(BlockChain.getInstance().getMessagePowDifficultyUnconfirmable(), transaction.getPoWDifficulty());

			// Transaction should be found when trade bot searches for it
			messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, recipient, null, null, null);
			assertEquals(1, messageTransactionsData.size());
		}
	}

	@Test
	public void noRecipientNoFeeWithNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MessageTransaction transaction = testFeeNonce(repository, false, true, null, true);

			// Transaction shouldn't be confirmable because it's not to an AT.
			assertFalse(transaction.isConfirmable());
			assertTrue(transaction.isSignatureValid());
			importUnconfirmed(transaction);
			assertEquals(BlockChain.getInstance().getMessagePowDifficultyUnconfirmable(), transaction.getPoWDifficulty());
		}
	}

	@Test
	public void atRecipientWithFeeNoNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String atRecipient = deployAt();
			MessageTransaction transaction = testFeeNonce(repository, true, false, atRecipient, true);

			// Transaction should be confirmable because it's to an AT, and therefore should be present in a block
			assertTrue(transaction.isConfirmable());
			TransactionUtils.signAndMint(repository, transaction.getTransactionData(), alice);
			assertTrue(isTransactionConfirmed(repository, transaction));
			assertEquals(BlockChain.getInstance().getMessagePowDifficultyConfirmable(), transaction.getPoWDifficulty());

			BlockUtils.orphanLastBlock(repository);
		}
	}

	@Test
	public void regularRecipientWithFeeNoNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			MessageTransaction transaction = testFeeNonce(repository, true, false, recipient, true);

			// Transaction shouldn't be confirmable because it's not to an AT, and therefore shouldn't be present in a block
			assertFalse(transaction.isConfirmable());
			TransactionUtils.signAndMint(repository, transaction.getTransactionData(), alice);
			assertFalse(isTransactionConfirmed(repository, transaction));
			assertEquals(BlockChain.getInstance().getMessagePowDifficultyUnconfirmable(), transaction.getPoWDifficulty());

			BlockUtils.orphanLastBlock(repository);
		}
	}

	@Test
	public void serializationTests() throws DataException, TransformationException {
		// with recipient, with amount
		testSerialization(recipient, 123L, Asset.NATIVE);

		// with recipient, no amount
		testSerialization(recipient, 0L, null);

		// no recipient (message to group), no amount
		testSerialization(null, 0L, null);
	}

	private String deployAt() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			byte[] creationBytes = AtUtils.buildSimpleAT();
			long fundingAmount = 1_00000000L;
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, fundingAmount);

			String address = deployAtTransaction.getATAccount().getAddress();
			assertNotNull(address);
			return address;
		}
	}

	private boolean isTransactionConfirmed(Repository repository, MessageTransaction transaction) throws DataException {
		TransactionData queriedTransactionData = repository.getTransactionRepository().fromSignature(transaction.getTransactionData().getSignature());
		return queriedTransactionData.getBlockHeight() != null && queriedTransactionData.getBlockHeight() > 0;
	}

	private boolean isValid(int txGroupId, String recipient, long amount, Long assetId) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int nonce = 0;
			byte[] data = new byte[1];
			boolean isText = false;
			boolean isEncrypted = false;

			BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice, txGroupId);
			MessageTransactionData transactionData = buildMessageTransactionData(baseTransactionData, nonce, recipient, amount, assetId, data, isText, isEncrypted);

			Transaction transaction = new MessageTransaction(repository, transactionData);

			return transaction.isValidUnconfirmed() == ValidationResult.OK;
		}
	}

	private MessageTransaction testFeeNonce(boolean withFee, boolean withNonce, boolean isValid) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return testFeeNonce(repository, withFee, withNonce, recipient, isValid);
		}
	}

	private MessageTransaction testFeeNonce(Repository repository, boolean withFee, boolean withNonce, String recipient, boolean isValid) throws DataException {
		TestAccount alice = Common.getTestAccount(repository, "alice");

		int txGroupId = 0;
		int nonce = 0;
		long amount = 0;
		long assetId = Asset.NATIVE;
		byte[] data = new byte[1];
		boolean isText = false;
		boolean isEncrypted = false;

		BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice, txGroupId);
		MessageTransactionData transactionData = buildMessageTransactionData(baseTransactionData, nonce, recipient, amount, assetId, data, isText, isEncrypted);

		MessageTransaction transaction = new FastPoWMessageTransaction(repository, transactionData);

		if (withFee)
			transactionData.setFee(transaction.calcRecommendedFee());
		else
			transactionData.setFee(0L);

		if (withNonce) {
			transaction.computeNonce();
		} else {
			transactionData.setNonce(-1);
		}

		transaction.sign(alice);

		assertEquals(isValid, transaction.isSignatureValid());

		return transaction;
	}

	private void importUnconfirmed(MessageTransaction transaction) throws DataException {
		assertEquals(ValidationResult.OK, transaction.importAsUnconfirmed());
	}

	private MessageTransaction testMessage(int txGroupId, String recipient, long amount, Long assetId) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int nonce = 0;
			byte[] data = new byte[1];
			boolean isText = false;
			boolean isEncrypted = false;

			BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice, txGroupId);
			MessageTransactionData transactionData = buildMessageTransactionData(baseTransactionData, nonce, recipient, amount, assetId, data, isText, isEncrypted);

			TransactionUtils.signAndMint(repository, transactionData, alice);

			BlockUtils.orphanLastBlock(repository);

			return new MessageTransaction(repository, transactionData);
		}
	}

	private void testSerialization(String recipient, long amount, Long assetId) throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int nonce = 0;
			byte[] data = new byte[1];
			boolean isText = false;
			boolean isEncrypted = false;

			BaseTransactionData baseTransactionData = TestTransaction.generateBase(alice);
			MessageTransactionData expectedTransactionData = buildMessageTransactionData(baseTransactionData, nonce, recipient, amount, assetId, data, isText, isEncrypted);

			Transaction transaction = new MessageTransaction(repository, expectedTransactionData);
			transaction.sign(alice);

			MessageTransactionTransformer.getDataLength(expectedTransactionData);
			byte[] transactionBytes = MessageTransactionTransformer.toBytes(expectedTransactionData);

			TransactionData transactionData = TransactionTransformer.fromBytes(transactionBytes);
			assertEquals(TransactionType.MESSAGE, transactionData.getType());

			MessageTransactionData actualTransactionData = (MessageTransactionData) transactionData;

			assertEquals(expectedTransactionData.getRecipient(), actualTransactionData.getRecipient());
			assertEquals(expectedTransactionData.getAmount(), actualTransactionData.getAmount());
			assertEquals(expectedTransactionData.getAssetId(), actualTransactionData.getAssetId());
		}
	}

	private static MessageTransactionData buildMessageTransactionData(BaseTransactionData baseTransactionData, int nonce, String recipient, long amount, Long assetId, byte[] data, boolean isText, boolean isEncrypted) {
		int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
		return new MessageTransactionData(baseTransactionData, version, nonce, recipient, amount, assetId, data, isText, isEncrypted);
	}

	private static class FastPoWMessageTransaction extends MessageTransaction {
		private FastPoWMessageTransaction(Repository repository, TransactionData transactionData) {
			super(repository, transactionData);
		}

		@Override
		protected int getPoWBufferSize() {
			return TEST_POW_BUFFER_SIZE;
		}

		@Override
		protected int getPoWNonceDifficulty() {
			return TEST_POW_DIFFICULTY;
		}
	}

}
