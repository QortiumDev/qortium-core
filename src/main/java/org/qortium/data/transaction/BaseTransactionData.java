package org.qortium.data.transaction;

import org.qortium.transaction.Transaction.ApprovalStatus;

public class BaseTransactionData extends TransactionData {

	/** Constructor for use by repository. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee,
			Integer nonce, ApprovalStatus approvalStatus, Integer blockHeight, Integer approvalHeight, byte[] signature) {
		this.timestamp = timestamp;
		this.txGroupId = txGroupId;
		this.creatorPublicKey = creatorPublicKey;
		this.fee = fee;
		this.nonce = nonce;
		this.approvalStatus = approvalStatus;
		this.blockHeight = blockHeight;
		this.approvalHeight = approvalHeight;
		this.signature = signature;
	}

	/** Constructor for use by repository. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee,
			ApprovalStatus approvalStatus, Integer blockHeight, Integer approvalHeight, byte[] signature) {
		this(timestamp, txGroupId, creatorPublicKey, fee, null, approvalStatus, blockHeight, approvalHeight, signature);
	}

	/** Constructor for use by transaction transformer. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee, Integer nonce, byte[] signature) {
		this(timestamp, txGroupId, creatorPublicKey, fee, nonce, null, null, null, signature);
	}

	/** Constructor for use by transaction transformer. */
	public BaseTransactionData(long timestamp, int txGroupId, byte[] creatorPublicKey, Long fee, byte[] signature) {
		this(timestamp, txGroupId, creatorPublicKey, fee, null, signature);
	}

}
