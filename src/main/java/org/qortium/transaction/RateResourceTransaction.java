package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.rating.ResourceRatingData;
import org.qortium.data.transaction.RateResourceTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.rating.ResourceRating;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.ResourceRatingRepository;

import java.util.Collections;
import java.util.List;

public class RateResourceTransaction extends Transaction {

	private final RateResourceTransactionData rateResourceTransactionData;

	public RateResourceTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.rateResourceTransactionData = (RateResourceTransactionData) this.transactionData;
	}

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	public Account getRater() {
		return this.getCreator();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		int rating = this.rateResourceTransactionData.getRating();
		if (!ResourceRating.isRatingInRange(rating) && !ResourceRating.isNoRating(rating))
			return ValidationResult.INVALID_RATING;

		if (!ResourceRating.isRateableService(this.rateResourceTransactionData.getService()))
			return ValidationResult.INVALID_RESOURCE;

		String name = this.rateResourceTransactionData.getName();
		if (!ResourceRating.isNameValid(name))
			return ValidationResult.INVALID_NAME_LENGTH;

		if (!ResourceRating.isNormalized(name))
			return ValidationResult.NAME_NOT_NORMALIZED;

		String identifier = this.rateResourceTransactionData.getIdentifier();
		if (!ResourceRating.isIdentifierValid(identifier))
			return ValidationResult.INVALID_VALUE_LENGTH;

		if (!ResourceRating.isNormalized(identifier))
			return ValidationResult.NAME_NOT_NORMALIZED;

		ResourceRating.Target target = ResourceRating.resolveTarget(this.repository, this.rateResourceTransactionData.getService(),
				name, identifier);
		if (target == null)
			return ValidationResult.RESOURCE_DOES_NOT_EXIST;

		ResourceRatingData existingRating = this.repository.getResourceRatingRepository()
				.getRating(target.service, target.nameKey, target.identifierKey, this.rateResourceTransactionData.getRaterPublicKey());
		if (existingRating == null && ResourceRating.isNoRating(rating))
			return ValidationResult.ALREADY_RATED_RESOURCE;

		if (existingRating != null && existingRating.getRating() == rating)
			return ValidationResult.ALREADY_RATED_RESOURCE;

		Account rater = getRater();
		if (rater.getConfirmedBalance(Asset.NATIVE) < this.rateResourceTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		ResourceRating.Target target = ResourceRating.resolveTarget(this.repository, this.rateResourceTransactionData.getService(),
				this.rateResourceTransactionData.getName(), this.rateResourceTransactionData.getIdentifier());

		ResourceRatingRepository resourceRatingRepository = this.repository.getResourceRatingRepository();
		ResourceRatingData previousRatingData = resourceRatingRepository.getRating(target.service, target.nameKey, target.identifierKey,
				this.rateResourceTransactionData.getRaterPublicKey());
		if (previousRatingData != null)
			this.rateResourceTransactionData.setPreviousRating(previousRatingData.getRating());

		this.repository.getTransactionRepository().save(this.rateResourceTransactionData);

		if (ResourceRating.isNoRating(this.rateResourceTransactionData.getRating())) {
			resourceRatingRepository.delete(target.service, target.nameKey, target.identifierKey,
					this.rateResourceTransactionData.getRaterPublicKey());
			return;
		}

		resourceRatingRepository.save(new ResourceRatingData(target.service, target.nameKey, target.displayName,
				target.identifierKey, this.rateResourceTransactionData.getRaterPublicKey(), this.rateResourceTransactionData.getRating()));
	}

	@Override
	public void orphan() throws DataException {
		ResourceRating.Target target = ResourceRating.resolveTarget(this.repository, this.rateResourceTransactionData.getService(),
				this.rateResourceTransactionData.getName(), this.rateResourceTransactionData.getIdentifier());
		if (target == null)
			target = ResourceRating.fallbackTarget(this.rateResourceTransactionData.getService(), this.rateResourceTransactionData.getName(),
					this.rateResourceTransactionData.getIdentifier());

		ResourceRatingRepository resourceRatingRepository = this.repository.getResourceRatingRepository();
		Integer previousRating = this.rateResourceTransactionData.getPreviousRating();
		if (previousRating != null) {
			resourceRatingRepository.save(new ResourceRatingData(target.service, target.nameKey, target.displayName,
					target.identifierKey, this.rateResourceTransactionData.getRaterPublicKey(), previousRating));
		} else {
			resourceRatingRepository.delete(target.service, target.nameKey, target.identifierKey,
					this.rateResourceTransactionData.getRaterPublicKey());
		}

		this.rateResourceTransactionData.setPreviousRating(null);
		this.repository.getTransactionRepository().save(this.rateResourceTransactionData);
	}

}
