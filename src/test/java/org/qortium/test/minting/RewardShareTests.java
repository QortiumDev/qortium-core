package org.qortium.test.minting;

import org.qortium.account.Account;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class RewardShareTests extends Common {

	private static final int CANCEL_SHARE_PERCENT = -1;
	private static final int MAX_REWARD_SHARES = 100;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "dilbert");
			repository.saveChanges();
		}
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateRewardShare() throws DataException {
		final int sharePercent = 12_80; // 12.80%

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Create reward-share
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			// Confirm reward-share info set correctly

			// Fetch using reward-share public key
			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(rewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), rewardShareData.getRecipient());
			assertEquals("Incorrect share percentage", sharePercent, rewardShareData.getSharePercent());

			// Fetch using minter public key and recipient address combination
			rewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(rewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), rewardShareData.getRecipient());
			assertEquals("Incorrect share percentage", sharePercent, rewardShareData.getSharePercent());

			// Delete reward-share
			byte[] newRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", CANCEL_SHARE_PERCENT);
			PrivateKeyAccount newRewardShareAccount = new PrivateKeyAccount(repository, newRewardSharePrivateKey);

			// Confirm reward-share keys match
			assertEquals("Reward-share private keys differ", Base58.encode(rewardSharePrivateKey), Base58.encode(newRewardSharePrivateKey));
			assertEquals("Reward-share public keys differ", Base58.encode(rewardShareAccount.getPublicKey()), Base58.encode(newRewardShareAccount.getPublicKey()));

			// Confirm reward-share no longer exists in repository

			// Fetch using reward-share public key
			RewardShareData newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Orphan last block to restore prior reward-share
			BlockUtils.orphanLastBlock(repository);

			// Confirm reward-share restored correctly

			// Fetch using reward-share public key
			newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNotNull("Reward-share should have been restored", newRewardShareData);
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(newRewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), newRewardShareData.getRecipient());
			assertEquals("Incorrect share percentage", sharePercent, newRewardShareData.getSharePercent());

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNotNull("Reward-share should have been restored", newRewardShareData);
			assertEquals("Incorrect minter public key", Base58.encode(aliceAccount.getPublicKey()), Base58.encode(newRewardShareData.getMinterPublicKey()));
			assertEquals("Incorrect recipient", bobAccount.getAddress(), newRewardShareData.getRecipient());
			assertEquals("Incorrect share percentage", sharePercent, newRewardShareData.getSharePercent());

			// Orphan another block to remove initial reward-share
			BlockUtils.orphanLastBlock(repository);

			// Confirm reward-share no longer exists

			// Fetch using reward-share public key
			newRewardShareData = repository.getAccountRepository().getRewardShare(rewardShareAccount.getPublicKey());
			assertNull("Reward-share shouldn't exist", newRewardShareData);

			// Fetch using minter public key and recipient address combination
			newRewardShareData = repository.getAccountRepository().getRewardShare(aliceAccount.getPublicKey(), bobAccount.getAddress());
			assertNull("Reward-share shouldn't exist", newRewardShareData);
		}
	}

	@Test
	public void testNegativeInitialShareInvalid() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create invalid REWARD_SHARE transaction with initial negative reward share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, "alice", "bob", CANCEL_SHARE_PERCENT);

			// Confirm transaction is invalid
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult validationResult = transaction.isValidUnconfirmed();
			assertNotSame("Creating reward-share with 'cancel' share-percent should be invalid", ValidationResult.OK, validationResult);
		}
	}

	@Test
	public void testSelfShare() throws DataException {
		final String testAccountName = "dilbert";

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, testAccountName);
			// byte[] rewardSharePrivateKey = aliceAccount.getRewardSharePrivateKey(aliceAccount.getPublicKey());
			// PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			// Create self-reward-share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, 100_00);
			Transaction transaction = Transaction.fromData(repository, transactionData);

			// Confirm self-share is valid
			ValidationResult validationResult = transaction.isValidUnconfirmed();
			assertEquals("Initial self-share should be valid", ValidationResult.OK, validationResult);

			// Check zero fee is valid
			transactionData.setFee(0L);
			validationResult = transaction.isValidUnconfirmed();
			assertEquals("Zero-fee self-share should be valid", ValidationResult.OK, validationResult);

			// A self-share with a positive fee still has to be fundable
			transactionData.setFee(signingAccount.getConfirmedBalance(Asset.NATIVE) + 1L);
			validationResult = transaction.isValidUnconfirmed();
			assertEquals("Underfunded self-share fee should be rejected", ValidationResult.NO_BALANCE, validationResult);

			// Restore zero-fee self-share before minting it
			transactionData.setFee(0L);
			validationResult = transaction.isValidUnconfirmed();
			assertEquals("Zero-fee self-share should still be valid", ValidationResult.OK, validationResult);

			TransactionUtils.signAndMint(repository, transactionData, signingAccount);

			// Subsequent non-terminating (0% share) self-reward-share should be invalid
			TransactionData newTransactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, 99_00);
			Transaction newTransaction = Transaction.fromData(repository, newTransactionData);

			// Confirm subsequent self-reward-share is actually invalid
			validationResult = newTransaction.isValidUnconfirmed();
			assertNotSame("Subsequent self-share should be invalid", ValidationResult.OK, validationResult);

			// Recheck with zero fee
			newTransactionData.setFee(0L);
			validationResult = newTransaction.isValidUnconfirmed();
			assertNotSame("Subsequent zero-fee self-share should be invalid", ValidationResult.OK, validationResult);

			// Subsequent terminating (negative share) self-reward-share should be OK
			newTransactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, CANCEL_SHARE_PERCENT);
			newTransaction = Transaction.fromData(repository, newTransactionData);

			// Confirm terminating reward-share with fee is valid
			validationResult = newTransaction.isValidUnconfirmed();
			assertEquals("Subsequent self-share cancel should be valid", ValidationResult.OK, validationResult);

			// Confirm terminating reward-share with zero fee is invalid
			newTransactionData.setFee(0L);
			validationResult = newTransaction.isValidUnconfirmed();
			assertNotSame("Subsequent zero-fee self-share cancel should be invalid", ValidationResult.OK, validationResult);
		}
	}

	@Test
	public void testSelfSharePercentIsIgnored() throws DataException {
		final String testAccountName = "dilbert";
		final int ignoredSharePercent = 250_00;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, testAccountName);

			TransactionData transactionData = AccountUtils.createRewardShare(repository, testAccountName, testAccountName, ignoredSharePercent);
			Transaction transaction = Transaction.fromData(repository, transactionData);

			assertEquals("Self-share percentage should not be validated as a payout split",
					ValidationResult.OK, transaction.isValidUnconfirmed());

			TransactionUtils.signAndMint(repository, transactionData, signingAccount);

			RewardShareData rewardShareData = repository.getAccountRepository()
					.getRewardShare(signingAccount.getPublicKey(), signingAccount.getAddress());
			assertNotNull(rewardShareData);
			assertEquals("Self-share percentage should be normalized", 0, rewardShareData.getSharePercent());
		}
	}

	@Test
	public void testPayoutRewardSharesDoNotOccupySelfShareIndexes() throws DataException {
		final int sharePercent = 12_80;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			RewardShareData aliceSelfShare = repository.getAccountRepository()
					.getRewardShare(aliceAccount.getPublicKey(), aliceAccount.getAddress());
			assertNotNull("Alice self-share should exist in the test chain", aliceSelfShare);

			Integer aliceIndexBefore = repository.getAccountRepository()
					.getSelfShareIndex(aliceSelfShare.getRewardSharePublicKey());
			assertNotNull("Alice self-share should be indexable", aliceIndexBefore);

			List<byte[]> selfSharePublicKeysBefore = repository.getAccountRepository().getSelfSharePublicKeys();
			assertNotNull("Self-share public keys should exist", selfSharePublicKeysBefore);
			int selfShareCountBefore = selfSharePublicKeysBefore.size();

			byte[] payoutRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount payoutRewardShareAccount = new PrivateKeyAccount(repository, payoutRewardSharePrivateKey);
			RewardShareData payoutRewardShare = repository.getAccountRepository()
					.getRewardShare(payoutRewardShareAccount.getPublicKey());
			assertNotNull("Payout reward-share should exist", payoutRewardShare);
			assertFalse("Payout reward-share should not be a self-share", payoutRewardShare.isSelfShare());

			assertNull("Payout reward-share should not have a self-share index",
					repository.getAccountRepository().getSelfShareIndex(payoutRewardShare.getRewardSharePublicKey()));
			assertEquals("Payout reward-share should not report a minting level",
					0, Account.getRewardShareEffectiveMintingLevel(repository, payoutRewardShare.getRewardSharePublicKey()));

			List<byte[]> selfSharePublicKeysAfter = repository.getAccountRepository().getSelfSharePublicKeys();
			assertEquals("Payout reward-share should not change self-share index count",
					selfShareCountBefore, selfSharePublicKeysAfter.size());
			assertFalse("Payout reward-share should not appear in self-share public key list",
					selfSharePublicKeysAfter.stream()
							.anyMatch(publicKey -> Arrays.equals(publicKey, payoutRewardShare.getRewardSharePublicKey())));

			Integer aliceIndexAfter = repository.getAccountRepository()
					.getSelfShareIndex(aliceSelfShare.getRewardSharePublicKey());
			assertEquals("Payout reward-share should not shift Alice self-share index", aliceIndexBefore, aliceIndexAfter);

			RewardShareData indexedAliceSelfShare = repository.getAccountRepository().getSelfShareByIndex(aliceIndexAfter);
			assertArrayEquals("Alice self-share should still resolve from the same index",
					aliceSelfShare.getRewardSharePublicKey(), indexedAliceSelfShare.getRewardSharePublicKey());

			List<RewardShareData> indexedSelfShares = repository.getAccountRepository()
					.getSelfSharesByIndexes(new int[] { aliceIndexAfter });
			assertEquals(1, indexedSelfShares.size());
			assertArrayEquals("Batch self-share index lookup should resolve Alice self-share",
					aliceSelfShare.getRewardSharePublicKey(), indexedSelfShares.get(0).getRewardSharePublicKey());

			assertEquals("Payout recipient should still be recorded", bobAccount.getAddress(), payoutRewardShare.getRecipient());
		}
	}

	@Test
	public void testRewardShareRecordsDoNotGrantMintingEligibility() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount outsiderAccount = Common.generateRandomSeedAccount(repository);
			Account alice = new Account(repository, aliceAccount.getAddress());
			Account outsider = new Account(repository, outsiderAccount.getAddress());

			assertTrue("Default test minting member should be able to mint", alice.canMint(false));
			assertTrue("Default test minting member should not need a name to mint", repository.getNameRepository().getNamesByOwner(alice.getAddress()).isEmpty());

			TransactionData aliceRewardShareData = AccountUtils.createRewardShare(repository, "alice", "bob", 0);
			Transaction aliceRewardShare = Transaction.fromData(repository, aliceRewardShareData);
			assertEquals("Minting group member reward-share should be valid", ValidationResult.OK, aliceRewardShare.isValidUnconfirmed());

			assertNull("Random outsider should not exist before publishing a zero-fee self-share",
					repository.getAccountRepository().getAccount(outsiderAccount.getAddress()));

			TransactionData outsiderSelfShareData = AccountUtils.createRewardShare(repository, outsiderAccount, outsiderAccount, 100_00, 0L);
			Transaction outsiderSelfShare = Transaction.fromData(repository, outsiderSelfShareData);
			assertEquals("Non-member zero-fee self-share should be valid", ValidationResult.OK, outsiderSelfShare.isValidUnconfirmed());

			TransactionUtils.signAndMint(repository, outsiderSelfShareData, outsiderAccount);

			assertFalse("Non-member should not be able to mint", outsider.canMint(false));

			RewardShareData outsiderSelfShareRecord = repository.getAccountRepository()
					.getRewardShare(outsiderAccount.getPublicKey(), outsiderAccount.getAddress());
			assertNotNull("Non-member self-share should be recorded", outsiderSelfShareRecord);
			assertFalse("Non-member self-share should not be allowed to mint",
					Account.canRewardShareMint(repository, outsiderSelfShareRecord.getRewardSharePublicKey()));

			AccountUtils.pay(repository, aliceAccount, outsiderAccount.getAddress(), 2 * AccountUtils.fee);

			TransactionData outsiderRewardShareData = AccountUtils.createRewardShare(repository, outsiderAccount, bobAccount, 0, AccountUtils.fee);
			Transaction outsiderRewardShare = Transaction.fromData(repository, outsiderRewardShareData);
			assertEquals("Non-member reward-share should be valid", ValidationResult.OK, outsiderRewardShare.isValidUnconfirmed());

			TransactionUtils.signAndMint(repository, outsiderRewardShareData, outsiderAccount);

			assertFalse("Non-member should still not be able to mint", outsider.canMint(false));

			RewardShareData outsiderBobShareRecord = repository.getAccountRepository()
					.getRewardShare(outsiderAccount.getPublicKey(), bobAccount.getAddress());
			assertNotNull("Non-member reward-share should be recorded", outsiderBobShareRecord);
			assertFalse("Non-member reward-share should not be allowed to mint",
					Account.canRewardShareMint(repository, outsiderBobShareRecord.getRewardSharePublicKey()));
		}
	}

	@Test
	public void testExternalShareTotalCannotExceedFullReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");

			AccountUtils.rewardShare(repository, "alice", "bob", 60_00);
			AccountUtils.rewardShare(repository, "alice", "chloe", 40_00);

			TransactionData overTotalTransactionData = AccountUtils.createRewardShare(repository, "alice", "dilbert", 1_00);
			Transaction overTotalTransaction = Transaction.fromData(repository, overTotalTransactionData);
			assertEquals("External reward-share total above 100% should be rejected",
					ValidationResult.INVALID_REWARD_SHARE_PERCENT, overTotalTransaction.isValidUnconfirmed());

			TransactionData overTotalUpdateData = AccountUtils.createRewardShare(repository, "alice", "bob", 61_00);
			Transaction overTotalUpdate = Transaction.fromData(repository, overTotalUpdateData);
			assertEquals("Reward-share update above 100% total should be rejected",
					ValidationResult.INVALID_REWARD_SHARE_PERCENT, overTotalUpdate.isValidUnconfirmed());

			TransactionData cancelChloeData = AccountUtils.createRewardShare(repository, "alice", "chloe", CANCEL_SHARE_PERCENT);
			TransactionUtils.signAndMint(repository, cancelChloeData, aliceAccount);

			Transaction validUpdate = Transaction.fromData(repository, overTotalUpdateData);
			assertEquals("Cancelling a share should free external share capacity",
					ValidationResult.OK, validUpdate.isValidUnconfirmed());
		}
	}

	@Test
	public void testCreateRewardSharesAtBaselineLimit() throws DataException {
		final int sharePercent = 0;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Create reward shares up to the current baseline limit.
			for (int i=0; i<MAX_REWARD_SHARES; i++) {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			}

			// Next reward share should fail because we've reached the current baseline limit.
			AssertionError assertionError = null;
			try {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			} catch (AssertionError e) {
				assertionError = e;
			}
			assertNotNull("Transaction should be invalid", assertionError);
			assertTrue("Transaction should be invalid due to reaching maximum reward shares", assertionError.getMessage().contains("MAXIMUM_REWARD_SHARES"));
		}
	}

	@Test
	public void testCreateRewardSharesInRewardShareFixture() throws DataException {
		Common.useSettings("test-settings-v2-reward-shares.json");

		final int sharePercent = 0;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Create reward shares up to the configured fixture limit.
			for (int i=0; i<MAX_REWARD_SHARES; i++) {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			}

			// Next reward share should fail because we've reached the simple maximum share limit.
			AssertionError assertionError = null;
			try {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			} catch (AssertionError e) {
				assertionError = e;
			}
			assertNotNull("Transaction should be invalid", assertionError);
			assertTrue("Transaction should be invalid due to reaching maximum reward shares", assertionError.getMessage().contains("MAXIMUM_REWARD_SHARES"));
		}
	}

	@Test
	public void testCreateSelfAndRewardSharesAtBaselineLimit() throws DataException {
		Common.useSettings("test-settings-v2-reward-shares.json");

		final int sharePercent = 0;

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Create reward shares up to one below the configured fixture limit.
			for (int i=0; i<MAX_REWARD_SHARES - 1; i++) {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			}

			// Create a self share, which should succeed as it simply counts toward the same overall share limit
			AccountUtils.rewardShare(repository, dilbertAccount, dilbertAccount, sharePercent);

			// Next reward share should fail because we've reached the limit (including the self share).
			AssertionError assertionError = null;
			try {
				AccountUtils.rewardShare(repository, dilbertAccount, Common.generateRandomSeedAccount(repository), sharePercent);
			} catch (AssertionError e) {
				assertionError = e;
			}
			assertNotNull("Transaction should be invalid", assertionError);
			assertTrue("Transaction should be invalid due to reaching maximum reward shares", assertionError.getMessage().contains("MAXIMUM_REWARD_SHARES"));
		}
	}

}
