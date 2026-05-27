package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.model.NameSummary;
import org.qortium.api.resource.NamesResource;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NamesApiTests extends ApiCommon {

	private NamesResource namesResource;

	@Before
	public void before() throws DataException {
		Common.useDefaultSettings();

		this.namesResource = (NamesResource) ApiCommon.buildResource(NamesResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.namesResource);
	}

	@Test
	public void testGetAllNames() {
		assertNotNull(this.namesResource.getAllNames(null, null, null, null));
		assertNotNull(this.namesResource.getAllNames(1L, 1, 1, true));
	}

	@Test
	public void testGetNamesByAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			assertNotNull(this.namesResource.getNamesByAddress(alice.getAddress(), null, null, null));
			assertNotNull(this.namesResource.getNamesByAddress(alice.getAddress(), 1, 1, true));
		}
	}

	@Test
	public void testGetName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			assertNotNull(this.namesResource.getName(name));
		}
	}

	@Test
	public void testGetPrimaryNamesByAddresses() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			String name = "test-name";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			List<NameSummary> primaryNames = this.namesResource.getPrimaryNamesByAddresses(
					Arrays.asList(bob.getAddress(), alice.getAddress(), alice.getAddress()));

			assertEquals(3, primaryNames.size());
			assertEquals(bob.getAddress(), primaryNames.get(0).getOwner());
			assertNull(primaryNames.get(0).getName());
			assertEquals(alice.getAddress(), primaryNames.get(1).getOwner());
			assertEquals(name, primaryNames.get(1).getName());
			assertEquals(alice.getAddress(), primaryNames.get(2).getOwner());
			assertEquals(name, primaryNames.get(2).getName());
		}
	}

	@Test
	public void testGetPrimaryNamesByAddressesRejectsInvalidInput() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.namesResource.getPrimaryNamesByAddresses(null));

		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.namesResource.getPrimaryNamesByAddresses(Collections.emptyList()));

		assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.namesResource.getPrimaryNamesByAddresses(Collections.singletonList("not-an-address")));
	}

	@Test
	public void testRegisterNameBuilderAllowsPendingMempowFeeNonce() throws Exception {
		ApiCommon.installTestApiKey();
		TransactionsResource transactionsResource =
				(TransactionsResource) ApiCommon.buildResource(TransactionsResource.class, ApiCommon.TEST_API_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			int previousDifficulty = setFeeAlternativeDifficulty(1);

			try {
				PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
				RegisterNameTransactionData transactionData = new RegisterNameTransactionData(
						TestTransaction.generateBase(alice), "mempow-api-name", "{}");
				transactionData.setFee(0L);

				Transaction transaction = Transaction.fromData(repository, transactionData);
				assertEquals(Transaction.ValidationResult.INSUFFICIENT_FEE, transaction.isValidUnconfirmed());
				assertEquals(Transaction.ValidationResult.OK, transaction.isValidUnconfirmedForUnsignedBuild());

				String rawTransaction = this.namesResource.registerName(transactionData);
				TransactionData decodedTransactionData = unsignedTransaction(rawTransaction);

				assertEquals(TransactionType.REGISTER_NAME, decodedTransactionData.getType());
				assertEquals(Long.valueOf(0L), decodedTransactionData.getFee());
				assertEquals(Integer.valueOf(0), decodedTransactionData.getNonceOrNull());
				assertNull(decodedTransactionData.getSignature());

				String computedRawTransaction = transactionsResource.computeMempowFeeNonce(
						ApiCommon.TEST_API_KEY, rawTransaction);
				TransactionData computedTransactionData = unsignedTransaction(computedRawTransaction);

				assertEquals(TransactionType.REGISTER_NAME, computedTransactionData.getType());
				assertEquals(Long.valueOf(0L), computedTransactionData.getFee());
				assertNotNull(computedTransactionData.getNonceOrNull());
				assertTrue(computedTransactionData.getNonceOrNull() >= 0);
				assertNull(computedTransactionData.getSignature());

				Transaction computedTransaction = Transaction.fromData(repository, computedTransactionData);
				assertEquals(Transaction.ValidationResult.OK, computedTransaction.isFeeValid());
			} finally {
				setFeeAlternativeDifficulty(previousDifficulty);
			}
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testRegisterNameBuilderRejectsInvalidMempowFeeState() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			RegisterNameTransactionData missingFeeTransactionData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "mempow-missing-fee", "{}");
			missingFeeTransactionData.setFee(null);
			assertApiError(ApiError.TRANSACTION_INVALID,
					() -> this.namesResource.registerName(missingFeeTransactionData));

			RegisterNameTransactionData negativeFeeTransactionData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "mempow-negative-fee", "{}");
			negativeFeeTransactionData.setFee(-1L);
			assertApiError(ApiError.TRANSACTION_INVALID,
					() -> this.namesResource.registerName(negativeFeeTransactionData));

			RegisterNameTransactionData invalidNonceTransactionData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "mempow-invalid-nonce", "{}");
			invalidNonceTransactionData.setFee(0L);
			invalidNonceTransactionData.setNonce(-1);
			assertApiError(ApiError.TRANSACTION_INVALID,
					() -> this.namesResource.registerName(invalidNonceTransactionData));
		}
	}

	@Test
	public void testLiteGetPrimaryNamesByAddressesFailsClearly() throws Exception {
		useLiteMode();

		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.namesResource.getPrimaryNamesByAddresses(Collections.singletonList(aliceAddress)));
	}

	@Test
	public void testLiteGetNamesByAddressFailsClearlyWithoutPeerData() throws Exception {
		useLiteMode();

		assertApiError(org.qortium.api.ApiError.NO_REPLY,
				() -> this.namesResource.getNamesByAddress(aliceAddress, null, null, null));
	}

	@Test
	public void testLiteGetNameFailsClearlyWithoutPeerData() throws Exception {
		useLiteMode();

		assertApiError(org.qortium.api.ApiError.NO_REPLY, () -> this.namesResource.getName("test-name"));
	}

	@Test
	public void testGetAllAssets() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			long price = 1_23456789L;

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Sell-name
			transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			assertNotNull(this.namesResource.getNamesForSale(null, null, null));
			assertNotNull(this.namesResource.getNamesForSale(1, 1, true));
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

}
