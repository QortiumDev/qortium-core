package org.qortium.test.serialization;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.transaction.ChatTestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;

import static org.junit.Assert.*;

public class ChatSerializationTests {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }


    @Test
    public void testChatSerializationWithChatReference() throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Build MESSAGE-type AT transaction with chatReference
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ChatTransactionData transactionData = (ChatTransactionData) ChatTestTransaction.randomTransaction(repository, signingAccount, true);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            assertNotNull(transactionData.getChatReference());

            final int claimedLength = TransactionTransformer.getDataLength(transactionData);
            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            assertEquals("Serialized CHAT transaction length differs from declared length", claimedLength, serializedTransaction.length);

            TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
            // Re-sign
            Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
            deserializedTransaction.sign(signingAccount);
            assertEquals("Deserialized CHAT transaction signature differs", Base58.encode(transactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));

            // Re-serialize to check new length and bytes
            final int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
            assertEquals("Reserialized CHAT transaction declared length differs", claimedLength, reclaimedLength);

            byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
            assertEquals("Reserialized CHAT transaction bytes differ", HashCode.fromBytes(serializedTransaction).toString(), HashCode.fromBytes(reserializedTransaction).toString());

            // Deserialized chat reference must match initial chat reference
            ChatTransactionData deserializedChatTransactionData = (ChatTransactionData) deserializedTransactionData;
            assertNotNull(deserializedChatTransactionData.getChatReference());
            assertArrayEquals(deserializedChatTransactionData.getChatReference(), transactionData.getChatReference());
        }
    }

    @Test
    public void testChatSerializationWithoutChatReference() throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Build MESSAGE-type AT transaction without chatReference
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ChatTransactionData transactionData = (ChatTransactionData) ChatTestTransaction.randomTransaction(repository, signingAccount, true);
            transactionData.setChatReference(null);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            assertNull(transactionData.getChatReference());

            final int claimedLength = TransactionTransformer.getDataLength(transactionData);
            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            assertEquals("Serialized CHAT transaction length differs from declared length", claimedLength, serializedTransaction.length);

            TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
            // Re-sign
            Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
            deserializedTransaction.sign(signingAccount);
            assertEquals("Deserialized CHAT transaction signature differs", Base58.encode(transactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));

            // Re-serialize to check new length and bytes
            final int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
            assertEquals("Reserialized CHAT transaction declared length differs", claimedLength, reclaimedLength);

            byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
            assertEquals("Reserialized CHAT transaction bytes differ", HashCode.fromBytes(serializedTransaction).toString(), HashCode.fromBytes(reserializedTransaction).toString());

            // Deserialized chat reference must match initial chat reference
            ChatTransactionData deserializedChatTransactionData = (ChatTransactionData) deserializedTransactionData;
            assertNull(deserializedChatTransactionData.getChatReference());
            assertArrayEquals(deserializedChatTransactionData.getChatReference(), transactionData.getChatReference());
        }
    }

	@Test
	public void testChatSerializationPreservesChatReferenceWithOldTimestamp() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
			ChatTransactionData originalTransactionData = (ChatTransactionData) ChatTestTransaction.randomTransaction(repository, signingAccount, true);

			BaseTransactionData baseTransactionData = new BaseTransactionData(
					1_500_000_000_000L,
					originalTransactionData.getTxGroupId(),
					originalTransactionData.getCreatorPublicKey(),
					originalTransactionData.getFee(),
					originalTransactionData.getSignature());
			ChatTransactionData transactionData = new ChatTransactionData(baseTransactionData, originalTransactionData.getSender(),
					originalTransactionData.getNonce(), originalTransactionData.getRecipient(), originalTransactionData.getChatReference(),
					originalTransactionData.getData(), originalTransactionData.getIsText(), originalTransactionData.getIsEncrypted());

			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(signingAccount);

			final int claimedLength = TransactionTransformer.getDataLength(transactionData);
			byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
			assertEquals("Serialized CHAT transaction length differs from declared length", claimedLength, serializedTransaction.length);

			ChatTransactionData deserializedTransactionData = (ChatTransactionData) TransactionTransformer.fromBytes(serializedTransaction);
			assertNotNull(deserializedTransactionData.getChatReference());
			assertArrayEquals("Old timestamp should still preserve chat reference", transactionData.getChatReference(), deserializedTransactionData.getChatReference());
		}
	}

}
