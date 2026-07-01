package org.qortium.test.serialization;

import com.google.common.hash.HashCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.ATTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.transaction.AtTestTransaction;
import org.qortium.transaction.AtTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AtSerializationTests extends Common {

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
    }

    @After
    public void afterTest() throws DataException {
        Common.orphanCheck();
    }


    @Test
    public void testPaymentTypeAtSerialization() throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Build PAYMENT-type AT transaction
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ATTransactionData transactionData = (ATTransactionData) AtTestTransaction.paymentType(repository, signingAccount, true);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            final int claimedLength = TransactionTransformer.getDataLength(transactionData);
            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            assertEquals("Serialized PAYMENT-type AT transaction length differs from declared length", claimedLength, serializedTransaction.length);

            TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
            // Re-sign
            Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
            deserializedTransaction.sign(signingAccount);
            assertEquals("Deserialized PAYMENT-type AT transaction signature differs", Base58.encode(transactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));

            // Re-serialize to check new length and bytes
            final int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
            assertEquals("Reserialized PAYMENT-type AT transaction declared length differs", claimedLength, reclaimedLength);

            byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
            assertEquals("Reserialized PAYMENT-type AT transaction bytes differ", HashCode.fromBytes(serializedTransaction).toString(), HashCode.fromBytes(reserializedTransaction).toString());
        }
    }

    @Test
    public void testMessageTypeAtSerialization() throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Build MESSAGE-type AT transaction
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ATTransactionData transactionData = (ATTransactionData) AtTestTransaction.messageType(repository, signingAccount, true);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            // MESSAGE-type AT transactions use Qortium's typed baseline layout
            assertEquals(Transaction.CURRENT_VERSION, Transaction.getVersionByTimestamp(transactionData.getTimestamp()));

            final int claimedLength = TransactionTransformer.getDataLength(transactionData);
            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            assertEquals("Serialized MESSAGE-type AT transaction length differs from declared length", claimedLength, serializedTransaction.length);

            TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
            // Re-sign
            Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
            deserializedTransaction.sign(signingAccount);
            assertEquals("Deserialized MESSAGE-type AT transaction signature differs", Base58.encode(transactionData.getSignature()), Base58.encode(deserializedTransactionData.getSignature()));

            // Re-serialize to check new length and bytes
            final int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
            assertEquals("Reserialized MESSAGE-type AT transaction declared length differs", claimedLength, reclaimedLength);

            byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
            assertEquals("Reserialized MESSAGE-type AT transaction bytes differ", HashCode.fromBytes(serializedTransaction).toString(), HashCode.fromBytes(reserializedTransaction).toString());
        }
    }

    @Test
    public void testEmptyMessageTypeAtSerialization() throws DataException, TransformationException {
        roundTripMessageType(new byte[0]);
    }

    @Test
    public void testMaxSizeMessageTypeAtSerialization() throws DataException, TransformationException {
        byte[] message = new byte[AtTransaction.MAX_DATA_SIZE];
        for (int i = 0; i < message.length; ++i)
            message[i] = (byte) i;

        roundTripMessageType(message);
    }

    @Test
    public void testMessageTypeAtRejectsNegativeMessageLength() throws DataException, TransformationException {
        assertMutatedMessageLengthRejected(-1);
    }

    @Test
    public void testMessageTypeAtRejectsExcessiveMessageLength() throws DataException, TransformationException {
        assertMutatedMessageLengthRejected(AtTransaction.MAX_DATA_SIZE + 1);
    }

    @Test
    public void testMessageTypeAtRejectsMessageLengthOverrun() throws DataException, TransformationException {
        assertMutatedMessageLengthRejected(AtTransaction.MAX_DATA_SIZE);
    }

    private void roundTripMessageType(byte[] message) throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ATTransactionData transactionData = (ATTransactionData) AtTestTransaction.messageType(repository, signingAccount, true, message);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);

            assertArrayEquals(message, ((ATTransactionData) deserializedTransactionData).getMessage());
            assertEquals(TransactionTransformer.getDataLength(transactionData),
                    TransactionTransformer.getDataLength(deserializedTransactionData));
            assertEquals(HashCode.fromBytes(serializedTransaction).toString(),
                    HashCode.fromBytes(TransactionTransformer.toBytes(deserializedTransactionData)).toString());
        }
    }

    private void assertMutatedMessageLengthRejected(int declaredMessageLength) throws DataException, TransformationException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
            ATTransactionData transactionData = (ATTransactionData) AtTestTransaction.messageType(repository, signingAccount, true);
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(signingAccount);

            byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
            ByteBuffer.wrap(serializedTransaction).putInt(messageLengthOffset(), declaredMessageLength);

            try {
                TransactionTransformer.fromBytes(serializedTransaction);
            } catch (TransformationException e) {
                return;
            }

            fail("Expected malformed AT message length to be rejected");
        }
    }

    private static int messageLengthOffset() {
        return Transformer.INT_LENGTH
                + Transformer.TIMESTAMP_LENGTH
                + Transformer.ADDRESS_LENGTH
                + Transformer.ADDRESS_LENGTH
                + Transformer.INT_LENGTH;
    }

}
