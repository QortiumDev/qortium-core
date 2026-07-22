package org.qortium.group;

import org.qortium.transaction.Transaction.TransactionType;

public enum GroupApprovalCategory {

	GOVERNANCE,
	OPERATIONS;

	public static GroupApprovalCategory fromTransactionType(TransactionType transactionType) {
		switch (transactionType) {
		case UPDATE_GROUP:
		case SET_GROUP_AVATAR:
			case ADD_GROUP_ADMIN:
			case REMOVE_GROUP_ADMIN:
			case GROUP_KICK:
			case GROUP_BAN:
			case CANCEL_GROUP_BAN:
			case GROUP_INVITE:
			case CANCEL_GROUP_INVITE:
				return GOVERNANCE;

			default:
				return OPERATIONS;
		}
	}
}
