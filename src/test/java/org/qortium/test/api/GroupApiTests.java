package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.ApiException;
import org.qortium.api.resource.GroupsResource;
import org.qortium.block.BlockChain;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.LeaveGroupTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GroupApiTests extends ApiCommon {

	private GroupsResource groupsResource;

	@Before
	public void buildResource() {
		this.groupsResource = (GroupsResource) ApiCommon.buildResource(GroupsResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.groupsResource);
	}

	@Test
	public void testGetAllGroups() {
		assertNotNull(this.groupsResource.getAllGroups(null, null, null));
		assertNotNull(this.groupsResource.getAllGroups(1, 1, true));
	}

	@Test
	public void testGetBans() {
		assertNotNull(this.groupsResource.getBans(1));
	}

	@Test
	public void testGetGroup() {
		for (Boolean onlyAdmins : ALL_BOOLEAN_VALUES) {
			assertNotNull(this.groupsResource.getGroup(1, onlyAdmins, null, null, null));
			assertNotNull(this.groupsResource.getGroup(1, onlyAdmins, 1, 1, true));
		}
	}

	@Test
	public void testGetGroupData() {
		assertNotNull(this.groupsResource.getGroupData(1));
	}

	@Test
	public void testGetGroupDataIncludesConfiguredGroupRoles() {
		GroupData devGroup = this.groupsResource.getGroupData(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		GroupData mintingGroup = this.groupsResource.getGroupData(TestChainBootstrapUtils.MINTING_GROUP_ID);

		assertTrue(devGroup.isDevGroup());
		assertFalse(devGroup.isMintingGroup());
		assertFalse(mintingGroup.isDevGroup());
		assertTrue(mintingGroup.isMintingGroup());
	}

	@Test
	public void testGetGroupsByOwner() {
		assertNotNull(this.groupsResource.getGroupsByOwner(aliceAddress));
	}

	@Test
	public void testGetGroupsWithMember() {
		assertNotNull(this.groupsResource.getGroupsWithMember(aliceAddress, null, null));
	}

	@Test
	public void testGetInvitesByGroupId() {
		assertNotNull(this.groupsResource.getInvitesByGroupId(1));
	}

	@Test
	public void testGetInvitesByInvitee() {
		assertNotNull(this.groupsResource.getInvitesByInvitee(aliceAddress));
	}

	@Test
	public void testGetJoinRequests() {
		assertNotNull(this.groupsResource.getJoinRequests(1));
	}

	@Test
	public void testCreateGroupBuildsRawTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			CreateGroupTransactionData transactionData = createGroupTransactionData(alice, "api-create-group-valid");

			assertFalse(this.groupsResource.createGroup(transactionData).isEmpty());
		}
	}

	@Test
	public void testCreateGroupRejectsMissingCreatorPublicKey() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			CreateGroupTransactionData transactionData = createGroupTransactionData(alice, "api-create-group-missing-creator");
			transactionData.setGroupCreatorPublicKey(null);

			assertApiError(ApiError.TRANSACTION_INVALID, () -> this.groupsResource.createGroup(transactionData));
		}
	}

	@Test
	public void testCreateGroupRejectsInvalidCreatorPublicKey() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			CreateGroupTransactionData transactionData = createGroupTransactionData(alice, "api-create-group-invalid-creator");
			transactionData.setGroupCreatorPublicKey(new byte[] { 1, 2, 3 });

			assertApiError(ApiError.TRANSACTION_INVALID, () -> this.groupsResource.createGroup(transactionData));
		}
	}

	@Test
	public void testPublicJoinAndLeaveBuildersPreserveExactUnsignedIntent() throws Exception {
		Object mempowSettings = FieldUtils.readField(BlockChain.getInstance(), "mempowSettings", true);
		int previousDifficulty = (Integer) FieldUtils.readField(mempowSettings, "feeAlternativeDifficulty", true);
		FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", 1, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createGroup(repository, alice, "api-public-fields", "public builder", true);
			long joinTimestamp = System.currentTimeMillis();
			JoinGroupTransactionData joinData = new JoinGroupTransactionData(
					zeroFeeBase(bob, joinTimestamp), groupId);
			joinData.setSignature(new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			String unsignedJoin58 = this.groupsResource.buildPublicJoinGroup(joinData);
			JoinGroupTransactionData decodedJoin = (JoinGroupTransactionData) decodeUnsigned(unsignedJoin58);

			assertEquals(Transaction.TransactionType.JOIN_GROUP, decodedJoin.getType());
			assertEquals(joinTimestamp, decodedJoin.getTimestamp());
			assertEquals(0, decodedJoin.getTxGroupId());
			assertTrue(Arrays.equals(bob.getPublicKey(), decodedJoin.getJoinerPublicKey()));
			assertEquals(0L, decodedJoin.getFee().longValue());
			assertEquals(0, decodedJoin.getNonce());
			assertEquals(groupId, decodedJoin.getGroupId());
			assertNull(decodedJoin.getSignature());

			processPublicTransaction(repository, bob, decodedJoin);
			assertTrue(repository.getGroupRepository().memberExists(groupId, bob.getAddress()));

			long leaveTimestamp = System.currentTimeMillis();
			LeaveGroupTransactionData leaveData = new LeaveGroupTransactionData(
					zeroFeeBase(bob, leaveTimestamp), groupId);
			leaveData.setSignature(new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			String unsignedLeave58 = this.groupsResource.buildPublicLeaveGroup(leaveData);
			LeaveGroupTransactionData decodedLeave = (LeaveGroupTransactionData) decodeUnsigned(unsignedLeave58);

			assertEquals(Transaction.TransactionType.LEAVE_GROUP, decodedLeave.getType());
			assertEquals(leaveTimestamp, decodedLeave.getTimestamp());
			assertEquals(0, decodedLeave.getTxGroupId());
			assertTrue(Arrays.equals(bob.getPublicKey(), decodedLeave.getLeaverPublicKey()));
			assertEquals(0L, decodedLeave.getFee().longValue());
			assertEquals(0, decodedLeave.getNonce());
			assertEquals(groupId, decodedLeave.getGroupId());
			assertNull(decodedLeave.getSignature());

			processPublicTransaction(repository, bob, decodedLeave);
			assertFalse(repository.getGroupRepository().memberExists(groupId, bob.getAddress()));
		} finally {
			FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", previousDifficulty, true);
		}
	}

	@Test
	public void testPublicGroupBuildersRemainAvailableWhenApiIsRestricted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createGroup(repository, alice, "api-public-restrict", "public builder", true);
			JoinGroupTransactionData joinData = new JoinGroupTransactionData(
					zeroFeeBase(bob, System.currentTimeMillis()), groupId);

			FieldUtils.writeField(org.qortium.settings.Settings.getInstance(), "apiRestricted", true, true);
			assertApiError(ApiError.NON_PRODUCTION, () -> this.groupsResource.joinGroup(joinData));
			assertFalse(this.groupsResource.buildPublicJoinGroup(joinData).isEmpty());
		} finally {
			FieldUtils.writeField(org.qortium.settings.Settings.getInstance(), "apiRestricted", false, true);
		}
	}

	@Test
	public void testPublicGroupBuilderStateFailuresAreIdentifiable() throws Exception {
		Object mempowSettings = FieldUtils.readField(BlockChain.getInstance(), "mempowSettings", true);
		int previousDifficulty = (Integer) FieldUtils.readField(mempowSettings, "feeAlternativeDifficulty", true);
		FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", 1, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createGroup(repository, alice, "api-public-state", "public builder", true);
			JoinGroupTransactionData joinData = new JoinGroupTransactionData(
					zeroFeeBase(bob, System.currentTimeMillis()), groupId);
			processPublicTransaction(repository, bob, decodeUnsigned(this.groupsResource.buildPublicJoinGroup(joinData)));

			assertTransactionInvalid("ALREADY_GROUP_MEMBER",
					() -> this.groupsResource.buildPublicJoinGroup(new JoinGroupTransactionData(
							zeroFeeBase(bob, System.currentTimeMillis()), groupId)));

			LeaveGroupTransactionData leaveData = new LeaveGroupTransactionData(
					zeroFeeBase(bob, System.currentTimeMillis()), groupId);
			processPublicTransaction(repository, bob, decodeUnsigned(this.groupsResource.buildPublicLeaveGroup(leaveData)));

			assertTransactionInvalid("NOT_GROUP_MEMBER",
					() -> this.groupsResource.buildPublicLeaveGroup(new LeaveGroupTransactionData(
							zeroFeeBase(bob, System.currentTimeMillis()), groupId)));
		} finally {
			FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", previousDifficulty, true);
		}
	}

	@Test
	public void testSearchGroupsByQuery() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "api-search-open", "plain description", true);
			createGroup(repository, alice, "api-search-closed", "plain description", false);
		}

		List<GroupData> groups = this.groupsResource.searchGroups("api-search", null, null, null, null, null);

		assertEquals(List.of("api-search-closed", "api-search-open"), groupNames(groups));
		assertTrue(groups.stream().allMatch(group -> group.memberCount > 0));
	}

	@Test
	public void testSearchGroupsByOpenVisibility() throws DataException {
		createVisibilityGroups();

		List<GroupData> groups = this.groupsResource.searchGroups("api-visibility-search", null, "OPEN", null, null, null);

		assertEquals(List.of("api-visibility-search-open"), groupNames(groups));
		assertTrue(groups.get(0).isOpen());
	}

	@Test
	public void testSearchGroupsByOpenVisibilityWithoutQuery() throws DataException {
		createVisibilityGroups();

		List<GroupData> groups = this.groupsResource.searchGroups(null, null, "OPEN", null, null, null);

		assertTrue(groupNames(groups).contains("api-visibility-search-open"));
		assertFalse(groupNames(groups).contains("api-visibility-search-closed"));
		assertTrue(groups.stream().allMatch(GroupData::isOpen));
	}

	@Test
	public void testSearchGroupsByClosedVisibility() throws DataException {
		createVisibilityGroups();

		List<GroupData> groups = this.groupsResource.searchGroups("api-visibility-search", null, "CLOSED", null, null, null);

		assertEquals(List.of("api-visibility-search-closed"), groupNames(groups));
		assertFalse(groups.get(0).isOpen());
	}

	@Test
	public void testSearchGroupsRejectsInvalidVisibility() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.groupsResource.searchGroups(null, null, "PRIVATE", null, null, null));
	}

	private static void createVisibilityGroups() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "api-visibility-search-open", "api visibility search", true);
			createGroup(repository, alice, "api-visibility-search-closed", "api visibility search", false);
		}
	}

	private static int createGroup(Repository repository, PrivateKeyAccount owner, String groupName, String description, boolean isOpen) throws DataException {
		CreateGroupTransactionData transactionData = createGroupTransactionData(owner, groupName, description, isOpen);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private static CreateGroupTransactionData createGroupTransactionData(PrivateKeyAccount owner, String groupName) throws DataException {
		return createGroupTransactionData(owner, groupName, "api create group", true);
	}

	private static CreateGroupTransactionData createGroupTransactionData(PrivateKeyAccount owner, String groupName,
			String description, boolean isOpen) throws DataException {
		return new CreateGroupTransactionData(TestTransaction.generateBase(owner),
				groupName, description, isOpen, ApprovalThreshold.ONE, 10, 1440);
	}

	private static BaseTransactionData zeroFeeBase(PrivateKeyAccount account, long timestamp) {
		return new BaseTransactionData(timestamp, 0, account.getPublicKey(), 0L, 0, null);
	}

	private static TransactionData decodeUnsigned(String unsigned58) throws TransformationException {
		byte[] bytesWithEmptySignature = Bytes.concat(Base58.decode(unsigned58),
				new byte[TransactionTransformer.SIGNATURE_LENGTH]);
		TransactionData transactionData = TransactionTransformer.fromBytes(bytesWithEmptySignature);
		transactionData.setSignature(null);
		return transactionData;
	}

	private static void processPublicTransaction(Repository repository, PrivateKeyAccount account,
			TransactionData transactionData) throws DataException {
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.computeMempowFeeNonce();
		transaction.sign(account);
		assertEquals(Transaction.ValidationResult.OK, transaction.importAsUnconfirmed());
		BlockUtils.mintBlock(repository);
	}

	private static void assertTransactionInvalid(String validationResult, Runnable call) {
		try {
			call.run();
			throw new AssertionError("ApiException expected: " + validationResult);
		} catch (ApiException e) {
			assertEquals(ApiError.TRANSACTION_INVALID, ApiError.fromCode(e.error));
			assertTrue(e.message.endsWith("(" + validationResult + ")"));
		}
	}

	private static List<String> groupNames(List<GroupData> groups) {
		return groups.stream().map(GroupData::getGroupName).collect(Collectors.toList());
	}

}
