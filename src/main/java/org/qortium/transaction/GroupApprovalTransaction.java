package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.BlockValidationContext;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class GroupApprovalTransaction extends Transaction {

	// Properties
	private GroupApprovalTransactionData groupApprovalTransactionData;

	// Constructors

	public GroupApprovalTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupApprovalTransactionData = (GroupApprovalTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Grab pending transaction's data: check current block first (same-block approval),
		// then repository (approval of a transaction from an earlier block)
		byte[] pendingSignature = this.groupApprovalTransactionData.getPendingSignature();
		TransactionData pendingTransactionData = BlockValidationContext.getBySignature(pendingSignature);
		if (pendingTransactionData == null) {
			pendingTransactionData = this.repository.getTransactionRepository().fromSignature(pendingSignature);
		}
		if (pendingTransactionData == null)
			return ValidationResult.TRANSACTION_UNKNOWN;

		// Check pending transaction is actually needs group approval
		if (pendingTransactionData.getApprovalStatus() == ApprovalStatus.NOT_REQUIRED)
			return ValidationResult.GROUP_APPROVAL_NOT_REQUIRED;

		// Check pending transaction is actually pending
		if (pendingTransactionData.getApprovalStatus() != ApprovalStatus.PENDING)
			return ValidationResult.GROUP_APPROVAL_DECIDED;

		Account admin = getAdmin();

		// Can't cast approval decision if not part of the group's current approval authority
		if (!Group.canApprove(this.repository, pendingTransactionData.getTxGroupId(), admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.NATIVE) < this.groupApprovalTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Find previous approval decision (if any) by this admin for pending transaction
		GroupApprovalTransactionData previousApproval = this.repository.getTransactionRepository().getLatestApproval(this.groupApprovalTransactionData.getPendingSignature(), this.groupApprovalTransactionData.getAdminPublicKey());
		
		if (previousApproval != null)
			this.groupApprovalTransactionData.setPriorReference(previousApproval.getSignature());

		// Save this transaction with updated prior reference to transaction that can help restore state
		this.repository.getTransactionRepository().save(this.groupApprovalTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Save this transaction with removed prior reference
		this.groupApprovalTransactionData.setPriorReference(null);
		this.repository.getTransactionRepository().save(this.groupApprovalTransactionData);
	}

}
