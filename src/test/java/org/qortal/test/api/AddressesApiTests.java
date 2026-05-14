package org.qortal.test.api;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.AddressesResource;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.network.OnlineAccountLevel;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PublicizeTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import javax.xml.bind.annotation.XmlSeeAlso;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
			assertEquals(AccountTrustStatus.UNVERIFIED, accountInfo.getDerivedTrustStatus());
			assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), accountInfo.getDerivedTrustStatusValue());
			assertEquals(0, accountInfo.getDerivedTrustWeightPercent());
			assertTrue(accountInfo.isDerivedTrustAllowsMinting());
			assertEquals(0, accountInfo.getDerivedEffectiveVoteWeight());
			assertNull(accountInfo.getDerivedSnapshotHeight());
			assertNull(accountInfo.getDerivedSnapshotTimestamp());
		}
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
			repository.getAccountRepository().setTrustStatus(aliceAddress, AccountTrustStatus.GOLD);
			repository.saveChanges();

			AccountData accountInfo = this.addressesResource.getAccountInfo(aliceAddress);
			assertEquals(AccountTrustStatus.GOLD, accountInfo.getTrustStatus());
			assertEquals(AccountTrustStatus.GOLD.getValue(), accountInfo.getTrustStatusValue());
			assertEquals(100, accountInfo.getTrustWeightPercent());
			assertTrue(accountInfo.isTrustAllowsMinting());
			assertEquals(101, accountInfo.getEffectiveVoteWeight());
			assertEquals(AccountTrustStatus.SILVER, accountInfo.getDerivedTrustStatus());
			assertEquals(AccountTrustStatus.SILVER.getValue(), accountInfo.getDerivedTrustStatusValue());
			assertEquals(50, accountInfo.getDerivedTrustWeightPercent());
			assertTrue(accountInfo.isDerivedTrustAllowsMinting());
			assertEquals(50, accountInfo.getDerivedEffectiveVoteWeight());
			assertNotNull(accountInfo.getDerivedSnapshotHeight());
			assertNotNull(accountInfo.getDerivedSnapshotTimestamp());

			repository.getAccountRepository().setTrustStatus(aliceAddress, AccountTrustStatus.SUSPICIOUS);
			repository.saveChanges();

			AccountData suspiciousAccountInfo = this.addressesResource.getAccountInfo(aliceAddress);
			assertEquals(AccountTrustStatus.SUSPICIOUS, suspiciousAccountInfo.getTrustStatus());
			assertEquals(AccountTrustStatus.SUSPICIOUS.getValue(), suspiciousAccountInfo.getTrustStatusValue());
			assertEquals(0, suspiciousAccountInfo.getTrustWeightPercent());
			assertFalse(suspiciousAccountInfo.isTrustAllowsMinting());
			assertEquals(0, suspiciousAccountInfo.getEffectiveVoteWeight());
			assertEquals(AccountTrustStatus.SILVER, suspiciousAccountInfo.getDerivedTrustStatus());
			assertEquals(50, suspiciousAccountInfo.getDerivedTrustWeightPercent());
			assertEquals(50, suspiciousAccountInfo.getDerivedEffectiveVoteWeight());
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
		saveAccountRating(repository, alice, bob, AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, bob, chloe, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, chloe, dilbert, AccountRatingCategory.PLAYER, 4);
		saveAccountRating(repository, dilbert, alice, AccountRatingCategory.SUBJECT, 4);
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(), category, rating));
	}

}
