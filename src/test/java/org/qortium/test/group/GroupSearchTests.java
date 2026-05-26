package org.qortium.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupSearchTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testSearchByGroupName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "atlas-name-search-open", "plain description", true);
			createGroup(repository, alice, "atlas-name-search-closed", "plain description", false);

			List<GroupData> groups = repository.getGroupRepository().searchGroups("ATLAS-name", false, null, null, null, null);

			assertEquals(List.of("atlas-name-search-closed", "atlas-name-search-open"), groupNames(groups));
		}
	}

	@Test
	public void testSearchByDescription() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "description-search-one", "contains rare-needle text", true);
			createGroup(repository, alice, "description-search-two", "plain description", true);

			List<GroupData> groups = repository.getGroupRepository().searchGroups("rare-needle", false, null, null, null, null);

			assertEquals(List.of("description-search-one"), groupNames(groups));
		}
	}

	@Test
	public void testPrefixOnlySearch() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "prefix-search-alpha", "plain description", true);
			createGroup(repository, alice, "middle-prefix-search", "plain description", true);

			List<GroupData> containsGroups = repository.getGroupRepository().searchGroups("prefix-search", false, null, null, null, null);
			List<GroupData> prefixGroups = repository.getGroupRepository().searchGroups("prefix-search", true, null, null, null, null);

			assertEquals(List.of("middle-prefix-search", "prefix-search-alpha"), groupNames(containsGroups));
			assertEquals(List.of("prefix-search-alpha"), groupNames(prefixGroups));
		}
	}

	@Test
	public void testVisibilityFilters() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "visibility-search-open", "visibility search", true);
			createGroup(repository, alice, "visibility-search-closed", "visibility search", false);

			List<GroupData> allGroups = repository.getGroupRepository().searchGroups("visibility-search", false, null, null, null, null);
			List<GroupData> openGroups = repository.getGroupRepository().searchGroups("visibility-search", false, true, null, null, null);
			List<GroupData> closedGroups = repository.getGroupRepository().searchGroups("visibility-search", false, false, null, null, null);

			assertEquals(List.of("visibility-search-closed", "visibility-search-open"), groupNames(allGroups));
			assertEquals(List.of("visibility-search-open"), groupNames(openGroups));
			assertEquals(List.of("visibility-search-closed"), groupNames(closedGroups));
			assertTrue(openGroups.get(0).isOpen());
			assertFalse(closedGroups.get(0).isOpen());
		}
	}

	@Test
	public void testLimitOffsetAndReverse() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			createGroup(repository, alice, "slice-search-alpha", "slice search", true);
			createGroup(repository, alice, "slice-search-beta", "slice search", true);
			createGroup(repository, alice, "slice-search-gamma", "slice search", true);

			List<GroupData> groups = repository.getGroupRepository().searchGroups("slice-search", false, null, 1, 1, true);

			assertEquals(List.of("slice-search-beta"), groupNames(groups));
		}
	}

	@Test
	public void testNoMatchReturnsEmptyList() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> groups = repository.getGroupRepository().searchGroups("not-a-real-group-query", false, null, null, null, null);

			assertTrue(groups.isEmpty());
		}
	}

	private static int createGroup(Repository repository, PrivateKeyAccount owner, String groupName, String description, boolean isOpen) throws DataException {
		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner),
				groupName, description, isOpen, ApprovalThreshold.ONE, 10, 1440);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private static List<String> groupNames(List<GroupData> groups) {
		return groups.stream().map(GroupData::getGroupName).collect(Collectors.toList());
	}
}
