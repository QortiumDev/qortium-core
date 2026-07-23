package org.qortium.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AvatarTransactionTests extends Common {

	private Map<String, Long> previousFeatureTriggers;

	@Before
	@SuppressWarnings("unchecked")
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		BlockChain blockChain = BlockChain.getInstance();
		this.previousFeatureTriggers = (Map<String, Long>) FieldUtils.readField(blockChain, "featureTriggers", true);
		Map<String, Long> featureTriggers = new LinkedHashMap<>(this.previousFeatureTriggers);
		featureTriggers.put("avatarTransactionsHeight", 1L);
		FieldUtils.writeField(blockChain, "featureTriggers", featureTriggers, true);
	}

	@After
	public void afterTest() throws Exception {
		try {
			Common.orphanCheck();
		} finally {
			FieldUtils.writeField(BlockChain.getInstance(), "featureTriggers", this.previousFeatureTriggers, true);
		}
	}

	@Test
	public void testAccountAvatarPointersRoundTripAndOrphan() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			AvatarData first = new AvatarData(Service.THUMBNAIL, "designer-one", "alice");
			AvatarData second = new AvatarData(Service.IMAGE, "designer-two", "");

			SetAccountAvatarTransactionData firstTx = new SetAccountAvatarTransactionData(
					TestTransaction.generateBase(alice), first);
			TransactionUtils.signAndMint(repository, firstTx, alice);
			assertAvatarEquals(first, repository.getAccountRepository().getAvatar(alice.getAddress()));

			SetAccountAvatarTransactionData secondTx = new SetAccountAvatarTransactionData(
					TestTransaction.generateBase(alice), second);
			TransactionUtils.signAndMint(repository, secondTx, alice);
			assertAvatarEquals(second, repository.getAccountRepository().getAvatar(alice.getAddress()));

			TransactionData stored = repository.getTransactionRepository().fromSignature(secondTx.getSignature());
			assertAvatarEquals(first, ((SetAccountAvatarTransactionData) stored).getPreviousAvatar());

			BlockUtils.orphanLastBlock(repository);
			assertAvatarEquals(first, repository.getAccountRepository().getAvatar(alice.getAddress()));

			BlockUtils.orphanLastBlock(repository);
			assertNull(repository.getAccountRepository().getAvatar(alice.getAddress()));
		}
	}

	@Test
	public void testGroupAvatarSurvivesUpdateChainAndOrphans() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "avatar-chain-group", true,
					ApprovalThreshold.ONE, 10, 40);
			AvatarData first = new AvatarData(Service.THUMBNAIL, "designer-one", "group");
			AvatarData second = new AvatarData(Service.IMAGE, "designer-two", "");

			SetGroupAvatarTransactionData firstAvatarTx = new SetGroupAvatarTransactionData(
					TestTransaction.generateBase(alice), groupId, first);
			TransactionUtils.signAndMint(repository, firstAvatarTx, alice);

			UpdateGroupTransactionData updateTx = new UpdateGroupTransactionData(
					TestTransaction.generateBase(alice), groupId, "updated description", false,
					ApprovalThreshold.PCT40, 20, 60);
			TransactionUtils.signAndMint(repository, updateTx, alice);

			SetGroupAvatarTransactionData secondAvatarTx = new SetGroupAvatarTransactionData(
					TestTransaction.generateBase(alice), groupId, second);
			TransactionUtils.signAndMint(repository, secondAvatarTx, alice);
			assertGroupState(repository, groupId, second, "updated description", false,
					ApprovalThreshold.PCT40, 20, 60);

			SetGroupAvatarTransactionData clearAvatarTx = new SetGroupAvatarTransactionData(
					TestTransaction.generateBase(alice), groupId, null);
			TransactionUtils.signAndMint(repository, clearAvatarTx, alice);
			assertGroupState(repository, groupId, null, "updated description", false,
					ApprovalThreshold.PCT40, 20, 60);

			TransactionData stored = repository.getTransactionRepository().fromSignature(secondAvatarTx.getSignature());
			assertAvatarEquals(second, ((SetGroupAvatarTransactionData) stored).getAvatar());

			BlockUtils.orphanLastBlock(repository);
			assertGroupState(repository, groupId, second, "updated description", false,
					ApprovalThreshold.PCT40, 20, 60);

			BlockUtils.orphanLastBlock(repository);
			assertGroupState(repository, groupId, first, "updated description", false,
					ApprovalThreshold.PCT40, 20, 60);

			BlockUtils.orphanLastBlock(repository);
			assertGroupState(repository, groupId, first, "avatar-chain-group (test group)", true,
					ApprovalThreshold.ONE, 10, 40);

			BlockUtils.orphanLastBlock(repository);
			assertGroupState(repository, groupId, null, "avatar-chain-group (test group)", true,
					ApprovalThreshold.ONE, 10, 40);
		}
	}

	private static void assertGroupState(Repository repository, int groupId, AvatarData avatar, String description,
			boolean isOpen, ApprovalThreshold threshold, int minimumDelay, int maximumDelay) throws DataException {
		GroupData group = repository.getGroupRepository().fromGroupId(groupId);
		assertAvatarEquals(avatar, group.getAvatar());
		assertEquals(description, group.getDescription());
		assertEquals(isOpen, group.isOpen());
		assertEquals(threshold, group.getApprovalThreshold());
		assertEquals(minimumDelay, group.getMinimumBlockDelay());
		assertEquals(maximumDelay, group.getMaximumBlockDelay());
	}

	private static void assertAvatarEquals(AvatarData expected, AvatarData actual) {
		if (expected == null) {
			assertNull(actual);
			return;
		}
		assertEquals(expected.getService(), actual.getService());
		assertEquals(expected.getName(), actual.getName());
		assertEquals(expected.getIdentifier() == null ? "" : expected.getIdentifier(), actual.getIdentifier());
	}
}
