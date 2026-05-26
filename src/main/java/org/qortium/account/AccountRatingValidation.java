package org.qortium.account;

import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.Transaction;
import org.qortium.transform.Transformer;

import java.util.Arrays;

public final class AccountRatingValidation {

	private AccountRatingValidation() {
	}

	public static Transaction.ValidationResult validateRatingChange(Repository repository, byte[] targetPublicKey,
			byte[] raterPublicKey, AccountRatingCategory category, int rating, int candidateChangeHeight)
			throws DataException {
		if (!AccountRating.isValid(rating))
			return Transaction.ValidationResult.INVALID_ACCOUNT_RATING;

		if (category == null)
			return Transaction.ValidationResult.INVALID_ACCOUNT_RATING;

		if (!isPublicKeyLengthValid(targetPublicKey) || !isPublicKeyLengthValid(raterPublicKey))
			return Transaction.ValidationResult.INVALID_PUBLIC_KEY;

		if (Arrays.equals(targetPublicKey, raterPublicKey))
			return Transaction.ValidationResult.CANNOT_RATE_SELF;

		String targetAddress = Crypto.toAddress(targetPublicKey);
		AccountData targetAccountData = repository.getAccountRepository().getAccount(targetAddress);
		if (targetAccountData == null || targetAccountData.getPublicKey() == null
				|| !Arrays.equals(targetPublicKey, targetAccountData.getPublicKey()))
			return Transaction.ValidationResult.PUBLIC_KEY_UNKNOWN;

		AccountRatingData existingRating = repository.getAccountRatingRepository()
				.getRating(targetPublicKey, raterPublicKey, category);
		if (existingRating == null && rating == AccountRating.NO_RATING)
			return Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED;

		if (existingRating != null && existingRating.getRating() == rating)
			return Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED;

		if (isRatingChangeTooSoon(repository, targetPublicKey, raterPublicKey, category, candidateChangeHeight))
			return Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON;

		return Transaction.ValidationResult.OK;
	}

	public static boolean isRatingChangeTooSoon(Repository repository, byte[] targetPublicKey, byte[] raterPublicKey,
			AccountRatingCategory category, int candidateChangeHeight) throws DataException {
		int cooldownBlocks = AccountTrustPolicy.getAccountRatingChangeCooldownBlocks(repository, candidateChangeHeight);
		if (cooldownBlocks <= 0)
			return false;

		Integer latestChangeHeight = repository.getAccountRatingRepository()
				.getLatestRatingChangeHeight(targetPublicKey, raterPublicKey, category);
		if (latestChangeHeight == null)
			return false;

		return candidateChangeHeight < latestChangeHeight + cooldownBlocks;
	}

	private static boolean isPublicKeyLengthValid(byte[] publicKey) {
		return publicKey != null && publicKey.length == Transformer.PUBLIC_KEY_LENGTH;
	}
}
