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
import org.qortium.api.model.PublicNameCapabilities;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.BuyNameTransactionData;
import org.qortium.data.transaction.CancelSellNameTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
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

	@Test
	public void testPublicNameCapabilitiesMatchChainSettings() {
		PublicNameCapabilities capabilities = this.namesResource.getPublicNameCapabilities();

		assertEquals(1, capabilities.protocolVersion);
		assertEquals(List.of("REGISTER_NAME", "UPDATE_NAME", "SELL_NAME", "CANCEL_SELL_NAME", "BUY_NAME"),
				capabilities.actions);
		assertEquals(BlockChain.getInstance().getMempowFeeAlternativeDifficulty(),
				capabilities.mempowFeeAlternativeDifficulty);
	}

	@Test
	public void testPublicNameBuildersMatchProtectedBuilders() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			RegisterNameTransactionData registerData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity", "{}");
			assertEquals(this.namesResource.registerName(registerData),
					this.namesResource.buildPublicRegisterName(registerData));

			// The remaining builders need the name on chain first.
			RegisterNameTransactionData mintedRegister = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity", "{}");
			mintedRegister.setFee(new RegisterNameTransaction(null, null).getUnitFee(mintedRegister.getTimestamp()));
			TransactionUtils.signAndMint(repository, mintedRegister, alice);

			UpdateNameTransactionData updateData = new UpdateNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity", "public-name-parity-2", "{}");
			assertEquals(this.namesResource.updateName(updateData),
					this.namesResource.buildPublicUpdateName(updateData));

			SellNameTransactionData sellData = new SellNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity", 1_23456789L);
			assertEquals(this.namesResource.sellName(sellData),
					this.namesResource.buildPublicSellName(sellData));

			// Cancel and buy need an active sale on chain.
			SellNameTransactionData mintedSell = new SellNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity", 1_23456789L);
			TransactionUtils.signAndMint(repository, mintedSell, alice);

			CancelSellNameTransactionData cancelData = new CancelSellNameTransactionData(
					TestTransaction.generateBase(alice), "public-name-parity");
			assertEquals(this.namesResource.cancelSellName(cancelData),
					this.namesResource.buildPublicCancelSellName(cancelData));

			BuyNameTransactionData buyData = new BuyNameTransactionData(
					TestTransaction.generateBase(bob), "public-name-parity", 1_23456789L, alice.getAddress());
			assertEquals(this.namesResource.buyName(buyData),
					this.namesResource.buildPublicBuyName(buyData));
		}
	}

	@Test
	public void testPublicRegisterBuilderStillWorksWhenApiIsRestricted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			RegisterNameTransactionData registerData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), "public-restricted-name", "{}");

			FieldUtils.writeField(Settings.getInstance(), "apiRestricted", true, true);
			assertApiError(ApiError.NON_PRODUCTION, () -> this.namesResource.registerName(registerData));
			assertTrue(!this.namesResource.buildPublicRegisterName(registerData).isEmpty());
		} finally {
			FieldUtils.writeField(Settings.getInstance(), "apiRestricted", false, true);
		}
	}

	@Test
	public void testPublicBuildersProduceLocallySignableProcessableNameTransactions() throws Exception {
		int previousDifficulty = setFeeAlternativeDifficulty(1);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			String name = "public-name-process";
			long price = 1_23456789L;

			// Every name type supports the zero-fee MemoryPoW alternative, so
			// the whole lifecycle runs through the public keyless builders:
			// register, update, sell, cancel, sell again, buy.
			processPublicTransaction(repository, alice, this.namesResource.buildPublicRegisterName(
					new RegisterNameTransactionData(zeroFeeBase(alice), name, "{}")));
			BlockUtils.mintBlock(repository);

			processPublicTransaction(repository, alice, this.namesResource.buildPublicUpdateName(
					new UpdateNameTransactionData(zeroFeeBase(alice), name, name, "{\"updated\":true}")));
			BlockUtils.mintBlock(repository);

			processPublicTransaction(repository, alice, this.namesResource.buildPublicSellName(
					new SellNameTransactionData(zeroFeeBase(alice), name, price)));
			BlockUtils.mintBlock(repository);

			processPublicTransaction(repository, alice, this.namesResource.buildPublicCancelSellName(
					new CancelSellNameTransactionData(zeroFeeBase(alice), name)));
			BlockUtils.mintBlock(repository);

			processPublicTransaction(repository, alice, this.namesResource.buildPublicSellName(
					new SellNameTransactionData(zeroFeeBase(alice), name, price)));
			BlockUtils.mintBlock(repository);

			processPublicTransaction(repository, bob, this.namesResource.buildPublicBuyName(
					new BuyNameTransactionData(zeroFeeBase(bob), name, price, alice.getAddress())));
			BlockUtils.mintBlock(repository);

			assertEquals(bob.getAddress(), this.namesResource.getName(name).getOwner());
		} finally {
			setFeeAlternativeDifficulty(previousDifficulty);
		}
	}

	private static BaseTransactionData zeroFeeBase(PrivateKeyAccount account) {
		return new BaseTransactionData(System.currentTimeMillis(), 0, account.getPublicKey(), 0L, 0, null);
	}

	private static void processPublicTransaction(Repository repository, PrivateKeyAccount account, String unsigned58)
			throws DataException, TransformationException {
		byte[] bytesWithEmptySignature = Bytes.concat(Base58.decode(unsigned58), new byte[TransactionTransformer.SIGNATURE_LENGTH]);
		TransactionData transactionData = TransactionTransformer.fromBytes(bytesWithEmptySignature);
		transactionData.setSignature(null);
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.computeMempowFeeNonce();
		transaction.sign(account);
		assertEquals(Transaction.ValidationResult.OK, transaction.importAsUnconfirmed());
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
