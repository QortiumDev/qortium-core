package org.qortal.test.serialization;

import com.google.common.hash.HashCode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IssueAssetSerializationTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testNormalIssueAssetSerialization() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			IssueAssetTransactionData transactionData = buildTransactionData(repository, null);
			assertSerializationRoundTrip(repository, transactionData);
		}
	}

	@Test
	public void testNativeBootstrapIssueAssetSerialization() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			IssueAssetTransactionData transactionData = buildTransactionData(repository, Asset.NATIVE);
			assertSerializationRoundTrip(repository, transactionData);
		}
	}

	private static IssueAssetTransactionData buildTransactionData(Repository repository, Long requestedAssetId) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, alice.getPublicKey(), 0L, null);

		return new IssueAssetTransactionData(baseTransactionData, requestedAssetId, "SERIAL_ASSET", "serialization test asset",
				1000L * Amounts.MULTIPLIER, true, "{}", false);
	}

	private static void assertSerializationRoundTrip(Repository repository, IssueAssetTransactionData transactionData)
			throws DataException, TransformationException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.sign(alice);

		int claimedLength = TransactionTransformer.getDataLength(transactionData);
		byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
		assertEquals("Serialized ISSUE_ASSET transaction length differs from declared length", claimedLength, serializedTransaction.length);

		TransactionData deserializedTransactionData = TransactionTransformer.fromBytes(serializedTransaction);
		IssueAssetTransactionData deserializedIssueAssetData = (IssueAssetTransactionData) deserializedTransactionData;

		if (transactionData.getRequestedAssetId() == null)
			assertNull(deserializedIssueAssetData.getRequestedAssetId());
		else
			assertEquals(transactionData.getRequestedAssetId(), deserializedIssueAssetData.getRequestedAssetId());

		Transaction deserializedTransaction = Transaction.fromData(repository, deserializedTransactionData);
		deserializedTransaction.sign(alice);
		assertEquals("Deserialized ISSUE_ASSET transaction signature differs",
				HashCode.fromBytes(transactionData.getSignature()).toString(),
				HashCode.fromBytes(deserializedIssueAssetData.getSignature()).toString());

		int reclaimedLength = TransactionTransformer.getDataLength(deserializedTransactionData);
		assertEquals("Reserialized ISSUE_ASSET transaction declared length differs", claimedLength, reclaimedLength);

		byte[] reserializedTransaction = TransactionTransformer.toBytes(deserializedTransactionData);
		assertEquals("Reserialized ISSUE_ASSET transaction bytes differ",
				HashCode.fromBytes(serializedTransaction).toString(),
				HashCode.fromBytes(reserializedTransaction).toString());
	}

}
