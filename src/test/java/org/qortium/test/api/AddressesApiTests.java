package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.AddressesResource;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.network.OnlineAccountLevel;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.PublicizeTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlSeeAlso;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AddressesApiTests extends ApiCommon {

	private AddressesResource addressesResource;

	@Before
	public void buildResource() {
		this.addressesResource = (AddressesResource) ApiCommon.buildResource(AddressesResource.class);
	}

	@Test
	public void testGetAccountInfo() {
		assertNotNull(this.addressesResource.getAccountInfo(aliceAddress));
	}

	@Test
	public void testGetAccountInfoReturnsPlaceholderForValidUnknownAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Account unknownAccount = Common.generateRandomSeedAccount(repository);
			assertNull(repository.getAccountRepository().getAccount(unknownAccount.getAddress()));

			AccountData accountInfo = this.addressesResource.getAccountInfo(unknownAccount.getAddress());
			assertEquals(unknownAccount.getAddress(), accountInfo.getAddress());
			assertEquals(AccountTrustStatus.UNVERIFIED, accountInfo.getTrustStatus());
			assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), accountInfo.getTrustStatusValue());
			assertEquals(0, accountInfo.getTrustWeightPercent());
			assertTrue(accountInfo.isTrustAllowsMinting());
			assertEquals(0, accountInfo.getEffectiveVoteWeight());
			assertNull(accountInfo.getTrustSnapshotHeight());
			assertNull(accountInfo.getTrustSnapshotTimestamp());
		}
	}

	@Test
	public void testLiteGetAccountInfoFailsClearlyWithoutPeerData() throws Exception {
		useLiteMode();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Account unknownAccount = Common.generateRandomSeedAccount(repository);
			assertNull(repository.getAccountRepository().getAccount(unknownAccount.getAddress()));

			assertApiError(ApiError.NO_REPLY, () -> this.addressesResource.getAccountInfo(unknownAccount.getAddress()));
		}
	}

	@Test
	public void testLiteGetAccountInfoDoesNotUseLocalRepositoryAccountData() throws Exception {
		useLiteMode();

		assertApiError(ApiError.NO_REPLY, () -> this.addressesResource.getAccountInfo(aliceAddress));
	}

	@Test
	public void testLiteGetBalanceFailsClearlyWithoutPeerData() throws Exception {
		useLiteMode();

		assertApiError(ApiError.NO_REPLY, () -> this.addressesResource.getBalance(aliceAddress, null));
	}

	@Test
	public void testLiteDataConflictApiErrorIsRegistered() {
		assertEquals(ApiError.LITE_DATA_CONFLICT, ApiError.fromCode(ApiError.LITE_DATA_CONFLICT.getCode()));
	}

	@Test
	public void testGetAccountInfoIncludesTrustAuditFields() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);

			AccountData accountData = repository.getAccountRepository().getAccount(aliceAddress);
			accountData.setBlocksMinted(101);

			repository.getAccountRepository().setMintedBlockCount(accountData);
			repository.saveChanges();

			AccountData accountInfo = this.addressesResource.getAccountInfo(aliceAddress);
			assertEquals(AccountTrustStatus.SILVER, accountInfo.getTrustStatus());
			assertEquals(AccountTrustStatus.SILVER.getValue(), accountInfo.getTrustStatusValue());
			assertEquals(70, accountInfo.getTrustWeightPercent());
			assertTrue(accountInfo.isTrustAllowsMinting());
			assertEquals(70, accountInfo.getEffectiveVoteWeight());
			assertNotNull(accountInfo.getTrustSnapshotHeight());
			assertNotNull(accountInfo.getTrustSnapshotTimestamp());
		}
	}

	@Test
	public void testGetPublicKeyReturnsFalseForValidUnknownAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Account unknownAccount = Common.generateRandomSeedAccount(repository);
			assertNull(repository.getAccountRepository().getAccount(unknownAccount.getAddress()));

			assertEquals("false", this.addressesResource.getPublicKey(unknownAccount.getAddress()));
		}
	}

	@Test
	public void testGetOnlineAccounts() {
		OnlineAccountsManager.getInstance().removeAllOnlineAccounts();

		assertTrue(this.addressesResource.getOnlineAccounts().isEmpty());

		List<OnlineAccountLevel> onlineAccountLevels = this.addressesResource.getOnlineAccountsByLevel();
		assertEquals(11, onlineAccountLevels.size());

		for (int level = 0; level <= 10; ++level) {
			assertEquals(level, onlineAccountLevels.get(level).getLevel());
			assertEquals(0, onlineAccountLevels.get(level).getCount());
		}
	}

	@Test
	public void testGetRewardShares() {
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), null, null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(null, Collections.singletonList(aliceAddress), null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(null, null, Collections.singletonList(aliceAddress), null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), 1, 1, true));
	}

	@Test
	public void testTransferPrivsTransactionDataIsRegistered() {
		XmlSeeAlso xmlSeeAlso = TransactionData.class.getAnnotation(XmlSeeAlso.class);

		assertNotNull(xmlSeeAlso);
		assertTrue(Arrays.asList(xmlSeeAlso.value()).contains(TransferPrivsTransactionData.class));
	}

	@Test
	public void testTransferPrivsBuilder() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount sender = Common.getTestAccount(repository, "alice");
			Account recipient = Common.generateRandomSeedAccount(repository);
			long fee = 1L * Amounts.MULTIPLIER;
			long timestamp = TransactionUtils.nextTimestamp(repository);

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
			TransferPrivsTransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipient.getAddress());

			String rawBytes58 = this.addressesResource.transferPrivs(transactionData);
			byte[] rawBytes = Base58.decode(rawBytes58);
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData decodedTransactionData = TransactionTransformer.fromBytes(rawBytes);
			assertTrue(decodedTransactionData instanceof TransferPrivsTransactionData);

			TransferPrivsTransactionData decodedTransferPrivsTransactionData = (TransferPrivsTransactionData) decodedTransactionData;
			assertArrayEquals(sender.getPublicKey(), decodedTransferPrivsTransactionData.getSenderPublicKey());
			assertEquals(recipient.getAddress(), decodedTransferPrivsTransactionData.getRecipient());
			assertEquals(fee, decodedTransferPrivsTransactionData.getFee().longValue());
		}
	}

	@Test
	public void testPublicizeBuilderAllowsMissingMempowNonce() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount sender = Common.generateRandomSeedAccount(repository);
			long timestamp = TransactionUtils.nextTimestamp(repository);

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), 0L, null);
			PublicizeTransactionData transactionData = new PublicizeTransactionData(baseTransactionData, 0);
			transactionData.setNonce((Integer) null);

			String rawBytes58 = this.addressesResource.publicize(transactionData);
			byte[] rawBytes = Base58.decode(rawBytes58);
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData decodedTransactionData = TransactionTransformer.fromBytes(rawBytes);
			assertTrue(decodedTransactionData instanceof PublicizeTransactionData);

			PublicizeTransactionData decodedPublicizeTransactionData = (PublicizeTransactionData) decodedTransactionData;
			assertArrayEquals(sender.getPublicKey(), decodedPublicizeTransactionData.getSenderPublicKey());
			assertEquals(0L, decodedPublicizeTransactionData.getFee().longValue());
		}
	}

	private void createDerivedSilverSubjectSnapshot(Repository repository, TestAccount alice, TestAccount bob,
			TestAccount chloe, TestAccount dilbert) throws DataException {
		AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);
	}

}
