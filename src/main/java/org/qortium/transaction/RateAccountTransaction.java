package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.account.AccountRatingValidation;
import org.qortium.asset.Asset;
import org.qortium.block.Block;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.AccountRatingRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;

import java.util.Collections;
import java.util.List;

public class RateAccountTransaction extends Transaction {

	private final RateAccountTransactionData rateAccountTransactionData;

	public RateAccountTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.rateAccountTransactionData = (RateAccountTransactionData) this.transactionData;
	}

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		if (!isPublicKeyLengthValid(targetPublicKey))
			return Collections.emptyList();

		return Collections.singletonList(Crypto.toAddress(targetPublicKey));
	}

	public Account getRater() {
		return this.getCreator();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		AccountRatingCategory category = this.rateAccountTransactionData.getCategory();
		int rating = this.rateAccountTransactionData.getRating();

		ValidationResult validationResult = AccountRatingValidation.validateRatingChange(this.repository, targetPublicKey,
				raterPublicKey, category, rating, getCurrentChangeHeight());
		if (validationResult != ValidationResult.OK)
			return validationResult;

		Account rater = getRater();
		if (rater.getConfirmedBalance(Asset.NATIVE) < this.rateAccountTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public boolean isConfirmableAtHeight(int height) {
		if (Block.isBatchRewardDistributionActive(height)
				&& (Block.isOnlineAccountsBlock(height) || Block.isBatchRewardDistributionBlock(height)))
			return false;

		return true;
	}

	@Override
	public void process() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		AccountRatingCategory category = this.rateAccountTransactionData.getCategory();
		int rating = this.rateAccountTransactionData.getRating();

		AccountRatingRepository accountRatingRepository = this.repository.getAccountRatingRepository();
		AccountRatingData previousRatingData = accountRatingRepository.getRating(targetPublicKey, raterPublicKey, category);
		if (previousRatingData != null)
			this.rateAccountTransactionData.setPreviousRating(previousRatingData.getRating());

		this.rateAccountTransactionData.setRatingChangeHeight(this.repository.getBlockRepository().getBlockchainHeight() + 1);
		this.repository.getTransactionRepository().save(this.rateAccountTransactionData);

		if (rating == AccountRating.NO_RATING) {
			accountRatingRepository.delete(targetPublicKey, raterPublicKey, category);
			return;
		}

		accountRatingRepository.save(new AccountRatingData(targetPublicKey, raterPublicKey, category, rating));
	}

	@Override
	public void orphan() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		AccountRatingCategory category = this.rateAccountTransactionData.getCategory();
		AccountRatingRepository accountRatingRepository = this.repository.getAccountRatingRepository();

		Integer previousRating = this.rateAccountTransactionData.getPreviousRating();
		if (previousRating != null) {
			if (AccountRating.isActive(previousRating))
				accountRatingRepository.save(new AccountRatingData(targetPublicKey, raterPublicKey, category, previousRating));
			else
				accountRatingRepository.delete(targetPublicKey, raterPublicKey, category);
		} else {
			accountRatingRepository.delete(targetPublicKey, raterPublicKey, category);
		}

		this.rateAccountTransactionData.setPreviousRating(null);
		this.rateAccountTransactionData.setRatingChangeHeight(null);
		this.repository.getTransactionRepository().save(this.rateAccountTransactionData);
	}

	private int getCurrentChangeHeight() throws DataException {
		if (this.rateAccountTransactionData.getRatingChangeHeight() != null)
			return this.rateAccountTransactionData.getRatingChangeHeight();

		return this.repository.getBlockRepository().getBlockchainHeight() + 1;
	}

	private static boolean isPublicKeyLengthValid(byte[] publicKey) {
		return publicKey != null && publicKey.length == Transformer.PUBLIC_KEY_LENGTH;
	}
}
