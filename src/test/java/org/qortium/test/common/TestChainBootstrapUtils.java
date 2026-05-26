package org.qortium.test.common;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.group.GroupAdminData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

public class TestChainBootstrapUtils {

	public static final int DEVELOPMENT_GROUP_ID = 1;
	public static final int MINTING_GROUP_ID = 2;

	public static void ensureDefaultTestChainBootstrap(Repository repository) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount aliceRewardShare = Common.getTestAccount(repository, "alice-reward-share");

		ensureMintingGroupMember(repository, "alice");
		ensureGroupAdmin(repository, MINTING_GROUP_ID, alice.getAddress());
		ensureGroupAdmin(repository, DEVELOPMENT_GROUP_ID, alice.getAddress());

		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(aliceRewardShare.getPublicKey());
		if (rewardShareData == null) {
			rewardShareData = new RewardShareData(alice.getPublicKey(), alice.getAddress(), alice.getAddress(),
					aliceRewardShare.getPublicKey(), 100_00);
			repository.getAccountRepository().save(rewardShareData);
		}
	}

	public static void ensureMintingGroupMember(Repository repository, String accountName) throws DataException {
		ensureGroupMember(repository, MINTING_GROUP_ID, Common.getTestAccount(repository, accountName).getAddress());
	}

	public static void ensureDevelopmentAdmin(Repository repository, String accountName) throws DataException {
		String address = Common.getTestAccount(repository, accountName).getAddress();
		ensureGroupMember(repository, DEVELOPMENT_GROUP_ID, address);

		if (!repository.getGroupRepository().adminExists(DEVELOPMENT_GROUP_ID, address)) {
			ensureGroupAdmin(repository, DEVELOPMENT_GROUP_ID, address);
		}
	}

	private static void ensureGroupAdmin(Repository repository, int groupId, String address) throws DataException {
		ensureGroupMember(repository, groupId, address);

		if (repository.getGroupRepository().adminExists(groupId, address))
			return;

		GroupData groupData = getGroupData(repository, groupId);
		repository.getGroupRepository().save(new GroupAdminData(groupId, address, groupData.getReference()));
	}

	private static void ensureGroupMember(Repository repository, int groupId, String address) throws DataException {
		if (repository.getGroupRepository().memberExists(groupId, address))
			return;

		GroupData groupData = getGroupData(repository, groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, address, groupData.getCreated(), groupData.getReference()));
	}

	private static GroupData getGroupData(Repository repository, int groupId) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			throw new DataException(String.format("Test bootstrap group %d does not exist", groupId));

		return groupData;
	}
}
