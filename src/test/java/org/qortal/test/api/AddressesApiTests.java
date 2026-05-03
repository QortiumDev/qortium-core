package org.qortal.test.api;

import com.google.common.primitives.Bytes;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.AddressesResource;
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
import org.qortal.test.common.TransactionUtils;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import javax.xml.bind.annotation.XmlSeeAlso;
import java.util.Arrays;
import java.util.Collections;

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

			assertEquals(unknownAccount.getAddress(), this.addressesResource.getAccountInfo(unknownAccount.getAddress()).getAddress());
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
	@Ignore(value = "No Logic Coded")
	public void testGetOnlineAccounts() {
		// Add and remove users as online checking count after minting
		// TODO: Need to construct logic
		// this.addressesResource.getOnlineAccounts(), empty Array, Size = 0
		assertNotNull(this.addressesResource.getOnlineAccounts());
		int blocksToMint = 5;
		// Add 2 accounts to the online array, mint some blocks
		// Assert number of accountsOnline == 2
		// Remove an account from onlineStatus, mint some blocks
		// Assert number of accountsOnline == 1
		// Add two accounts as online, mint some blocks
		// Asset number of accountsOnline == 3
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

}
