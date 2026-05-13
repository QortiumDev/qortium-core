package org.qortal.transaction;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingLevel;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.AccountRatingRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transform.Transformer;

import java.util.Arrays;
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
		AccountRatingLevel ratingLevel = AccountRatingLevel.valueOf(this.rateAccountTransactionData.getRating());
		if (ratingLevel == null)
			return ValidationResult.INVALID_ACCOUNT_RATING;

		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		if (!isPublicKeyLengthValid(targetPublicKey))
			return ValidationResult.INVALID_PUBLIC_KEY;

		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		if (Arrays.equals(targetPublicKey, raterPublicKey))
			return ValidationResult.CANNOT_RATE_SELF;

		String targetAddress = Crypto.toAddress(targetPublicKey);
		AccountData targetAccountData = this.repository.getAccountRepository().getAccount(targetAddress);
		if (targetAccountData == null || targetAccountData.getPublicKey() == null
				|| !Arrays.equals(targetPublicKey, targetAccountData.getPublicKey()))
			return ValidationResult.PUBLIC_KEY_UNKNOWN;

		AccountRatingData existingRating = this.repository.getAccountRatingRepository().getRating(targetPublicKey, raterPublicKey);
		if (existingRating == null && ratingLevel == AccountRatingLevel.UNKNOWN)
			return ValidationResult.ACCOUNT_RATING_UNCHANGED;

		if (existingRating != null && existingRating.getRatingLevel() == ratingLevel)
			return ValidationResult.ACCOUNT_RATING_UNCHANGED;

		Account rater = getRater();
		if (rater.getConfirmedBalance(Asset.NATIVE) < this.rateAccountTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		AccountRatingLevel ratingLevel = AccountRatingLevel.valueOf(this.rateAccountTransactionData.getRating());

		AccountRatingRepository accountRatingRepository = this.repository.getAccountRatingRepository();
		AccountRatingData previousRatingData = accountRatingRepository.getRating(targetPublicKey, raterPublicKey);
		if (previousRatingData != null)
			this.rateAccountTransactionData.setPreviousRating(previousRatingData.getRatingValue());

		this.repository.getTransactionRepository().save(this.rateAccountTransactionData);

		if (ratingLevel == AccountRatingLevel.UNKNOWN) {
			accountRatingRepository.delete(targetPublicKey, raterPublicKey);
			return;
		}

		accountRatingRepository.save(new AccountRatingData(targetPublicKey, raterPublicKey, ratingLevel));
	}

	@Override
	public void orphan() throws DataException {
		byte[] targetPublicKey = this.rateAccountTransactionData.getTargetPublicKey();
		byte[] raterPublicKey = this.rateAccountTransactionData.getRaterPublicKey();
		AccountRatingRepository accountRatingRepository = this.repository.getAccountRatingRepository();

		Integer previousRating = this.rateAccountTransactionData.getPreviousRating();
		if (previousRating != null) {
			AccountRatingLevel previousRatingLevel = AccountRatingLevel.valueOf(previousRating);
			if (previousRatingLevel != null && previousRatingLevel.isActive())
				accountRatingRepository.save(new AccountRatingData(targetPublicKey, raterPublicKey, previousRatingLevel));
			else
				accountRatingRepository.delete(targetPublicKey, raterPublicKey);
		} else {
			accountRatingRepository.delete(targetPublicKey, raterPublicKey);
		}

		this.rateAccountTransactionData.setPreviousRating(null);
		this.repository.getTransactionRepository().save(this.rateAccountTransactionData);
	}

	private static boolean isPublicKeyLengthValid(byte[] publicKey) {
		return publicKey != null && publicKey.length == Transformer.PUBLIC_KEY_LENGTH;
	}
}
