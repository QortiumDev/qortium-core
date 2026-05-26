package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;
import org.qortium.utils.Groups;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class JoinGroupTransaction extends Transaction {

	// Properties
	private JoinGroupTransactionData joinGroupTransactionData;

	// Constructors

	public JoinGroupTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.joinGroupTransactionData = (JoinGroupTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getJoiner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.joinGroupTransactionData.getGroupId();

		// Check group exists
		if (!this.repository.getGroupRepository().groupExists(groupId))
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		PublicKeyAccount joiner = getCreator();

		if (this.repository.getGroupRepository().memberExists(groupId, joiner.getAddress()))
			return ValidationResult.ALREADY_GROUP_MEMBER;

		// Check member is not banned
		if (this.repository.getGroupRepository().banExists(groupId, joiner.getAddress(), this.joinGroupTransactionData.getTimestamp()))
			return ValidationResult.BANNED_FROM_GROUP;

		// Check join request doesn't already exist
		if (this.repository.getGroupRepository().joinRequestExists(groupId, joiner.getAddress()))
			return ValidationResult.JOIN_REQUEST_EXISTS;

		ValidationResult mintingAuthorizationResult = this.isMintingAuthorizationValid(joiner, groupId);
		if (mintingAuthorizationResult != ValidationResult.OK)
			return mintingAuthorizationResult;

		// Check joiner has enough funds
		if (joiner.getConfirmedBalance(Asset.NATIVE) < this.joinGroupTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	private ValidationResult isMintingAuthorizationValid(PublicKeyAccount joiner, int groupId) throws DataException {
		byte[] mintingPublicKey = this.joinGroupTransactionData.getMintingPublicKey();
		if (mintingPublicKey == null)
			return ValidationResult.OK;

		if (mintingPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			return ValidationResult.INVALID_PUBLIC_KEY;

		int confirmationHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		if (!Groups.getGroupIdsToMint(BlockChain.getInstance(), confirmationHeight).contains(groupId))
			return ValidationResult.INVALID_GROUP_ID;

		RewardShareData existingRewardShareData = this.repository.getAccountRepository().getRewardShare(mintingPublicKey);
		if (existingRewardShareData == null)
			return ValidationResult.OK;

		boolean matchesJoiner = Arrays.equals(existingRewardShareData.getMinterPublicKey(), joiner.getPublicKey())
				&& existingRewardShareData.getMinter().equals(joiner.getAddress())
				&& existingRewardShareData.getRecipient().equals(joiner.getAddress());

		return matchesJoiner ? ValidationResult.OK : ValidationResult.INVALID_PUBLIC_KEY;
	}


	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.joinGroupTransactionData.getGroupId());
		group.join(this.joinGroupTransactionData);

		this.createMintingAuthorizationIfNeeded();

		// Save this transaction with cached references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.joinGroupTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.joinGroupTransactionData.getGroupId());
		group.unjoin(this.joinGroupTransactionData);

		this.deleteMintingAuthorizationIfCreated();

		// Save this transaction with removed references
		this.repository.getTransactionRepository().save(this.joinGroupTransactionData);
	}

	private void createMintingAuthorizationIfNeeded() throws DataException {
		byte[] mintingPublicKey = this.joinGroupTransactionData.getMintingPublicKey();
		if (mintingPublicKey == null) {
			this.joinGroupTransactionData.setMintingAuthorizationCreated(false);
			return;
		}

		PublicKeyAccount joiner = getCreator();
		RewardShareData existingSelfShareData = this.repository.getAccountRepository()
				.getRewardShare(joiner.getPublicKey(), joiner.getAddress());
		if (existingSelfShareData != null) {
			this.joinGroupTransactionData.setMintingAuthorizationCreated(false);
			return;
		}

		RewardShareData mintingAuthorizationData = new RewardShareData(joiner.getPublicKey(), joiner.getAddress(),
				joiner.getAddress(), mintingPublicKey, 0);
		this.repository.getAccountRepository().save(mintingAuthorizationData);
		this.joinGroupTransactionData.setMintingAuthorizationCreated(true);
	}

	private void deleteMintingAuthorizationIfCreated() throws DataException {
		if (!this.joinGroupTransactionData.isMintingAuthorizationCreated())
			return;

		PublicKeyAccount joiner = getCreator();
		this.repository.getAccountRepository().delete(joiner.getPublicKey(), joiner.getAddress());
		this.joinGroupTransactionData.setMintingAuthorizationCreated(false);
	}

}
