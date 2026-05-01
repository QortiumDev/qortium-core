package org.qortal.data.transaction;

import org.qortal.transaction.Transaction.ApprovalStatus;

public class BaseTransactionData extends TransactionData {

	/** Constructor for use by repository. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee,
			ApprovalStatus approvalStatus, Integer blockHeight, Integer approvalHeight, byte[] signature) {
		this.timestamp = timestamp;
		this.txGroupId = txGroupId;
		this.creatorPublicKey = creatorPublicKey;
		this.fee = fee;
		this.approvalStatus = approvalStatus;
		this.blockHeight = blockHeight;
		this.approvalHeight = approvalHeight;
		this.signature = signature;
	}

	/** Constructor for use by transaction transformer. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee, byte[] signature) {
		this(timestamp, txGroupId, creatorPublicKey, fee, null, null, null, signature);
	}

}
