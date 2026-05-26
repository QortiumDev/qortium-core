package org.qortium.test.common;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.*;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.utils.Amounts;

public class GroupUtils {

	public static final int txGroupId = Group.NO_GROUP;
	public static final long fee = 1L * Amounts.MULTIPLIER;

	public static int createGroup(Repository repository, Object creatorAccountName, String groupName, boolean isOpen, ApprovalThreshold approvalThreshold,
				int minimumBlockDelay, int maximumBlockDelay) throws DataException {

		PrivateKeyAccount account;
		if (creatorAccountName instanceof java.lang.String) {
			account = Common.getTestAccount(repository, (String) creatorAccountName);
		}
		else if (creatorAccountName instanceof PrivateKeyAccount) {
			account = (PrivateKeyAccount) creatorAccountName;
		} else {
			account = null;
		}

		long timestamp = TransactionUtils.nextTimestamp(repository);
		String groupDescription = groupName + " (test group)";

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, account.getPublicKey(), GroupUtils.fee, null);
		TransactionData transactionData = new CreateGroupTransactionData(baseTransactionData, groupName, groupDescription, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);

		TransactionUtils.signAndMint(repository, transactionData, account);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	/**
	 * <p> Simplified GroupCreation for Testing - less parameters required
	 * </p>
	 * @param repository The blockchain database
	 * @param owner Who will own the group, type PrivateKeyAccount
	 * @param groupName String representing the published name
	 * @param isOpen Boolean to allow anyone to join
	 * @return groupID as an integer
	 * @throws DataException when error occurs
	 * @since v4.71
	*/
	public static int createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		String description = groupName + " (description)";

		Group.ApprovalThreshold approvalThreshold = Group.ApprovalThreshold.ONE;
		int minimumBlockDelay = 10;
		int maximumBlockDelay = 1440;

		return createGroup(repository, owner, groupName, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
	} // End Simplified Group Creation

	/**
	 *
	 * @param repository The block chain database
	 * @param joinerAccount Account of the person joining the group
	 * @param groupId Integer of the Group mapping
	 * @throws DataException
	 * @since v4.7.1
	 */
	public static void joinGroup(Repository repository, PrivateKeyAccount joinerAccount, int groupId) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, joinerAccount.getPublicKey(), GroupUtils.fee, null);
		TransactionData transactionData = new JoinGroupTransactionData(baseTransactionData, groupId);

		TransactionUtils.signAndMint(repository, transactionData, joinerAccount);
	}

	public static void joinGroup(Repository repository, String joinerAccountName, int groupId) throws DataException {
		PrivateKeyAccount account = Common.getTestAccount(repository, joinerAccountName);

		joinGroup(repository, account, groupId);
	}

	public static void approveTransaction(Repository repository, String accountName, byte[] pendingSignature, boolean decision) throws DataException {
		PrivateKeyAccount account = Common.getTestAccount(repository, accountName);

		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, account.getPublicKey(), GroupUtils.fee, null);
		TransactionData transactionData = new GroupApprovalTransactionData(baseTransactionData, pendingSignature, decision);

		TransactionUtils.signAndMint(repository, transactionData, account);
	}

	public static ApprovalStatus getApprovalStatus(Repository repository, byte[] signature) throws DataException {
		return repository.getTransactionRepository().fromSignature(signature).getApprovalStatus();
	}

	public static Integer getApprovalHeight(Repository repository, byte[] signature) throws DataException {
		return repository.getTransactionRepository().fromSignature(signature).getApprovalHeight();
	}

}
