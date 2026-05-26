package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.transaction.RewardShareTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RewardShareTransaction extends Transaction {

	public static final int MAX_SHARE = 100 * 100; // unscaled

	// Properties

	private RewardShareTransactionData rewardShareTransactionData;
	private boolean haveCheckedForExistingRewardShare = false;
	private RewardShareData existingRewardShareData = null;

	// Constructors

	public RewardShareTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.rewardShareTransactionData = (RewardShareTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.rewardShareTransactionData.getRecipient());
	}

	private RewardShareData getExistingRewardShare() throws DataException {
		if (!this.haveCheckedForExistingRewardShare) {
			this.haveCheckedForExistingRewardShare = true;

			// Look up any existing reward-share (using transaction's reward-share public key)
			this.existingRewardShareData = this.repository.getAccountRepository().getRewardShare(this.rewardShareTransactionData.getRewardSharePublicKey());

			if (this.existingRewardShareData == null)
				// No luck, try looking up existing reward-share using minting & recipient account info
				this.existingRewardShareData = this.repository.getAccountRepository().getRewardShare(this.rewardShareTransactionData.getMinterPublicKey(), this.rewardShareTransactionData.getRecipient());
		}

		return this.existingRewardShareData;
	}

	private boolean doesRewardShareMatch(RewardShareData rewardShareData) {
		return rewardShareData.getRecipient().equals(this.rewardShareTransactionData.getRecipient())
				&& Arrays.equals(rewardShareData.getMinterPublicKey(), this.rewardShareTransactionData.getMinterPublicKey())
				&& Arrays.equals(rewardShareData.getRewardSharePublicKey(), this.rewardShareTransactionData.getRewardSharePublicKey());
	}

	private int calculateResultingExternalSharePercent(RewardShareData existingRewardShareData,
			boolean isCancellingSharePercent, boolean isRecipientAlsoMinter) throws DataException {
		int totalSharePercent = 0;

		for (RewardShareData rewardShareData : this.repository.getAccountRepository()
				.getRewardShares(this.rewardShareTransactionData.getMinterPublicKey())) {
			if (!rewardShareData.isSelfShare())
				totalSharePercent += rewardShareData.getSharePercent();
		}

		if (existingRewardShareData != null && !existingRewardShareData.isSelfShare())
			totalSharePercent -= existingRewardShareData.getSharePercent();

		if (!isCancellingSharePercent && !isRecipientAlsoMinter)
			totalSharePercent += this.rewardShareTransactionData.getSharePercent();

		return totalSharePercent;
	}

	// Navigation

	public PublicKeyAccount getMintingAccount() {
		return this.getCreator();
	}

	public Account getRecipient() {
		return new Account(this.repository, this.rewardShareTransactionData.getRecipient());
	}

	// Processing

	@Override
	public ValidationResult isFeeValid() throws DataException {
		// Look up any existing reward-share (using transaction's reward-share public key)
		RewardShareData existingRewardShareData = this.getExistingRewardShare();

		// If we have an existing reward-share then minter/recipient/reward-share-public-key should all match.
		// This is to prevent malicious actors using multiple (fake) reward-share public keys for the same minter/recipient combo,
		// or reusing the same reward-share public key for a different minter/recipient pair.
		if (existingRewardShareData != null && !this.doesRewardShareMatch(existingRewardShareData))
			return ValidationResult.INVALID_PUBLIC_KEY;

		final boolean isRecipientAlsoMinter = getCreator().getAddress().equals(this.rewardShareTransactionData.getRecipient());
		final boolean isCancellingSharePercent = this.rewardShareTransactionData.getSharePercent() < 0;

		// Fee can be zero if self-share, and not cancelling
		if (isRecipientAlsoMinter && !isCancellingSharePercent && this.transactionData.getFee() >= 0)
			return ValidationResult.OK;

		return super.isFeeValid();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check reward-share public key is correct length
		if (this.rewardShareTransactionData.getRewardSharePublicKey().length != Transformer.PUBLIC_KEY_LENGTH)
			return ValidationResult.INVALID_PUBLIC_KEY;

		// Check recipient address is valid
		if (!Crypto.isValidAddress(this.rewardShareTransactionData.getRecipient()))
			return ValidationResult.INVALID_ADDRESS;

		PublicKeyAccount creator = getCreator();
		Account recipient = getRecipient();
		final boolean isCancellingSharePercent = this.rewardShareTransactionData.getSharePercent() < 0;

		// Positive percentage values only matter when rewards are actually shared with a different recipient.
		final boolean isRecipientAlsoMinter = creator.getAddress().equals(recipient.getAddress());
		if (!isRecipientAlsoMinter && this.rewardShareTransactionData.getSharePercent() > MAX_SHARE)
			return ValidationResult.INVALID_REWARD_SHARE_PERCENT;

		// Look up any existing reward-share (using transaction's reward-share public key)
		RewardShareData existingRewardShareData = this.getExistingRewardShare();

		// If we have an existing reward-share then minter/recipient/reward-share-public-key should all match.
		// This is to prevent malicious actors using multiple (fake) reward-share public keys for the same minter/recipient combo,
		// or reusing the same reward-share public key for a different minter/recipient pair.
		if (existingRewardShareData != null && !this.doesRewardShareMatch(existingRewardShareData))
			return ValidationResult.INVALID_PUBLIC_KEY;

		if (!isCancellingSharePercent && !isRecipientAlsoMinter
				&& this.calculateResultingExternalSharePercent(existingRewardShareData, isCancellingSharePercent,
				isRecipientAlsoMinter) > MAX_SHARE)
			return ValidationResult.INVALID_REWARD_SHARE_PERCENT;

		if (existingRewardShareData == null) {
			// This is a new reward-share

			// Deleting a non-existent reward-share makes no sense
			if (isCancellingSharePercent)
				return ValidationResult.REWARD_SHARE_UNKNOWN;

			// Check the account hasn't reached the maximum number of reward-shares
			int rewardShareCount = this.repository.getAccountRepository().countRewardShares(creator.getPublicKey());

			int maxRewardShares = BlockChain.getInstance().getMaxRewardSharesAtTimestamp(this.rewardShareTransactionData.getTimestamp());
			if (rewardShareCount >= maxRewardShares)
				return ValidationResult.MAXIMUM_REWARD_SHARES;

		} else {
			// This transaction intends to modify/terminate an existing reward-share.

			// Modifying an existing self-share signing-key record is pointless. Deleting one is OK.
			if (isRecipientAlsoMinter && !isCancellingSharePercent)
				return ValidationResult.SELF_SHARE_EXISTS;
		}

		// Check creator has enough funds to cover any declared fee, including initial self-shares.
		if (creator.getConfirmedBalance(Asset.NATIVE) < this.rewardShareTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public boolean isConfirmableAtHeight(int height) {
		// Once batch reward distribution is active, do not confirm reward-share changes
		// inside online-account capture blocks or distribution blocks.
		if (Block.isBatchRewardDistributionActive(height)
				&& (Block.isOnlineAccountsBlock(height) || Block.isBatchRewardDistributionBlock(height))) {
			return false;
		}

		return true;
	}

	@Override
	public void process() throws DataException {
		PublicKeyAccount mintingAccount = getMintingAccount();

		// Grab any previous share info for orphaning purposes
		RewardShareData rewardShareData = this.repository.getAccountRepository().getRewardShare(mintingAccount.getPublicKey(),
				this.rewardShareTransactionData.getRecipient());

		if (rewardShareData != null)
			this.rewardShareTransactionData.setPreviousSharePercent(rewardShareData.getSharePercent());

		// Save this transaction, with previous share info
		this.repository.getTransactionRepository().save(this.rewardShareTransactionData);

		final boolean isSharePercentNegative = this.rewardShareTransactionData.getSharePercent() < 0;

		// Negative share is actually a request to delete existing reward-share
		if (isSharePercentNegative) {
			this.repository.getAccountRepository().delete(mintingAccount.getPublicKey(), this.rewardShareTransactionData.getRecipient());
		} else {
			boolean isRecipientAlsoMinter = mintingAccount.getAddress().equals(this.rewardShareTransactionData.getRecipient());
			int sharePercent = isRecipientAlsoMinter ? 0 : this.rewardShareTransactionData.getSharePercent();

			// Save reward-share info. Self-shares are signing-key records, so their percentage is ignored.
			rewardShareData = new RewardShareData(mintingAccount.getPublicKey(), mintingAccount.getAddress(),
					this.rewardShareTransactionData.getRecipient(), this.rewardShareTransactionData.getRewardSharePublicKey(),
					sharePercent);
			this.repository.getAccountRepository().save(rewardShareData);
		}
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		super.processReferencesAndFees();
	}

	@Override
	public void orphan() throws DataException {
		// Revert
		PublicKeyAccount mintingAccount = getMintingAccount();

		if (this.rewardShareTransactionData.getPreviousSharePercent() != null) {
			// Revert previous sharing arrangement
			RewardShareData rewardShareData = new RewardShareData(mintingAccount.getPublicKey(), mintingAccount.getAddress(),
					this.rewardShareTransactionData.getRecipient(), this.rewardShareTransactionData.getRewardSharePublicKey(),
					this.rewardShareTransactionData.getPreviousSharePercent());

			this.repository.getAccountRepository().save(rewardShareData);
		} else {
			// No previous arrangement so simply delete
			this.repository.getAccountRepository().delete(mintingAccount.getPublicKey(), this.rewardShareTransactionData.getRecipient());
		}

		// Save this transaction, with removed previous share info
		this.rewardShareTransactionData.setPreviousSharePercent(null);
		this.repository.getTransactionRepository().save(this.rewardShareTransactionData);
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		super.orphanReferencesAndFees();
	}

}
