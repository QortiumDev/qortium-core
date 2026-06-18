package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.GroupsResource;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

	private static List<String> groupNames(List<GroupData> groups) {
		return groups.stream().map(GroupData::getGroupName).collect(Collectors.toList());
	}

}
