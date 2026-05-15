package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.AccountTrustWeight;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.RateAccountTransactionTransformer;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Path("/account-ratings")
@Tag(name = "Account Ratings")
public class AccountRatingsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
			summary = "List active directed account ratings",
			responses = {
					@ApiResponse(
							description = "active account ratings",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = AccountRatingData.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<AccountRatingData> getAccountRatings(
			@Parameter(description = "Optional target account public key in Base58") @QueryParam("target") String targetPublicKey58,
			@Parameter(description = "Optional rater account public key in Base58") @QueryParam("rater") String raterPublicKey58,
			@Parameter(description = "Optional account rating category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = parseOptionalPublicKey(targetPublicKey58);
			byte[] raterPublicKey = parseOptionalPublicKey(raterPublicKey58);
			AccountRatingCategory category = parseOptionalCategory(categoryName);

			return repository.getAccountRatingRepository().getRatings(targetPublicKey, raterPublicKey, category, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	public List<AccountRatingData> getAccountRatings(String targetPublicKey58, String raterPublicKey58,
			Integer limit, Integer offset, Boolean reverse) {
		return getAccountRatings(targetPublicKey58, raterPublicKey58, null, limit, offset, reverse);
	}

	@GET
	@Path("/summary")
	@Operation(
			summary = "Get inbound active account rating counts for one target account",
			responses = {
					@ApiResponse(
							description = "account rating summary",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountRatingSummaryData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountRatingSummaryData getAccountRatingSummary(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58,
			@Parameter(description = "Optional account rating category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);
			AccountRatingCategory category = parseOptionalCategory(categoryName);

			return repository.getAccountRatingRepository().getRatingSummary(targetPublicKey, targetAddress, category);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	public AccountRatingSummaryData getAccountRatingSummary(String targetPublicKey58) {
		return getAccountRatingSummary(targetPublicKey58, null);
	}

	@GET
	@Path("/trust-preview")
	@Operation(
			summary = "Preview active account trust evidence from the decentralized trust graph",
			responses = {
					@ApiResponse(
							description = "account trust preview",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustPreviewData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountTrustPreviewData getAccountTrustPreview(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);

			AccountData targetAccountData = repository.getAccountRepository().getAccount(targetAddress);
			AccountTrustStatus storedTrustStatus = targetAccountData == null ? AccountTrustStatus.UNVERIFIED : targetAccountData.getTrustStatus();
			AccountTrustSnapshotData activeTrustSnapshot = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshot(targetAddress, AccountTrustWeight.ACTIVE_WEIGHT_CATEGORY);
			AccountTrustStatus activeTrustStatus = AccountTrustWeight.statusFromSnapshot(activeTrustSnapshot);

			List<AccountRatingData> inboundRatings = repository.getAccountRatingRepository()
					.getRatings(targetPublicKey, null, AccountRatingCategory.SUBJECT, null, null, null);
			List<AccountRatingData> outboundRatings = repository.getAccountRatingRepository()
					.getRatings(null, targetPublicKey, AccountRatingCategory.SUBJECT, null, null, null);
			AccountTrustDerivation.Result derivation = AccountTrustDerivation.derive(repository, targetAddress);

			return buildTrustPreview(repository, targetPublicKey, targetAddress, activeTrustStatus, storedTrustStatus,
					inboundRatings, outboundRatings, derivation);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-derivation")
	@Operation(
			summary = "List stored or live Aura-style account trust derivation rows",
			responses = {
					@ApiResponse(
							description = "account trust derivation rows",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = AccountTrustDerivationData.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<AccountTrustDerivationData> getAccountTrustDerivation(
			@Parameter(description = "Optional final Subject-derived trust status: GOLD, SILVER, BRONZE, UNVERIFIED, or SUSPICIOUS") @QueryParam("status") String statusName,
			@Parameter(description = "Optional category used for level filtering and sorting: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName,
			@Parameter(description = "Optional filter for current minting seed membership") @QueryParam("seedMember") Boolean seedMember,
			@Parameter(description = "Optional minimum level in the selected category") @QueryParam("minLevel") Integer minLevel,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse,
			@Parameter(description = "If true, recalculate from active account ratings instead of reading stored block snapshots") @QueryParam("live") Boolean live) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountTrustStatus status = parseOptionalTrustStatus(statusName);
			AccountRatingCategory sortCategory = parseOptionalCategory(categoryName);
			if (sortCategory == null)
				sortCategory = AccountRatingCategory.SUBJECT;

			validateTrustDerivationCriteria(minLevel, limit, offset);

			List<AccountTrustDerivationData> derivedAccounts = Boolean.TRUE.equals(live)
					? AccountTrustDerivation.deriveAll(repository)
					: buildTrustDerivationFromSnapshots(repository.getAccountRatingRepository()
							.getTrustDerivationSnapshots(null, null, null));
			return filterTrustDerivation(derivedAccounts, status, sortCategory, seedMember, minLevel, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	public List<AccountTrustDerivationData> getAccountTrustDerivation(String statusName, String categoryName, Boolean seedMember,
			Integer minLevel, Integer limit, Integer offset, Boolean reverse) {
		return getAccountTrustDerivation(statusName, categoryName, seedMember, minLevel, limit, offset, reverse, null);
	}

	@GET
	@Path("/trust-snapshots")
	@Operation(
			summary = "List stored Aura-style account trust snapshot rows",
			responses = {
					@ApiResponse(
							description = "account trust snapshot rows",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = AccountTrustSnapshotData.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<AccountTrustSnapshotData> getAccountTrustSnapshots(
			@Parameter(description = "Optional account address") @QueryParam("account") String account,
			@Parameter(description = "Optional category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName,
			@Parameter(description = "Optional mapped trust status: GOLD, SILVER, BRONZE, UNVERIFIED, or SUSPICIOUS") @QueryParam("status") String statusName,
			@Parameter(description = "Optional filter for current minting seed membership") @QueryParam("seedMember") Boolean seedMember,
			@Parameter(description = "Optional minimum level") @QueryParam("minLevel") Integer minLevel,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String accountAddress = parseOptionalAddress(account);
			AccountRatingCategory category = parseOptionalCategory(categoryName);
			AccountTrustStatus status = parseOptionalTrustStatus(statusName);

			validateTrustDerivationCriteria(minLevel, limit, offset);

			List<AccountTrustSnapshotData> snapshots = accountAddress == null
					? repository.getAccountRatingRepository().getTrustDerivationSnapshots(null, null, null)
					: repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress);
			return filterTrustSnapshots(snapshots, category, status, seedMember, minLevel, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/rate")
	@Operation(
			summary = "Build raw, unsigned, RATE_ACCOUNT transaction",
			requestBody = @RequestBody(
					required = true,
					description = "Rate another known account. rating 0 removes the active rating; ratings -4 through -1 record negative confidence and ratings 1 through 4 record positive confidence.",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = RateAccountTransactionData.class)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, RATE_ACCOUNT transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "string")
							)
					)
			}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String rateAccount(RateAccountTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			Transaction.ValidationResult result = transaction.isValidUnconfirmed();
			if (result != Transaction.ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RateAccountTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private AccountTrustPreviewData buildTrustPreview(Repository repository, byte[] targetPublicKey, String targetAddress,
			AccountTrustStatus activeTrustStatus, AccountTrustStatus storedTrustStatus, List<AccountRatingData> inboundRatings,
			List<AccountRatingData> outboundRatings, AccountTrustDerivation.Result derivation)
			throws DataException {
		AccountTrustPreviewData.RatingCounts inboundCounts = new AccountTrustPreviewData.RatingCounts();
		AccountTrustPreviewData.RatingCounts outboundCounts = new AccountTrustPreviewData.RatingCounts();
		List<AccountTrustPreviewData.EvaluatorImpact> evaluatorImpacts = new ArrayList<>();

		Set<String> outboundPositiveTargets = new HashSet<>();
		for (AccountRatingData outboundRating : outboundRatings) {
			int rating = outboundRating.getRating();

			outboundCounts.addRating(rating);
			if (AccountRating.isPositive(rating))
				outboundPositiveTargets.add(outboundRating.getTargetAddress());
		}

		int mutualPositiveCount = 0;
		for (AccountRatingData inboundRating : inboundRatings) {
			int rating = inboundRating.getRating();

			inboundCounts.addRating(rating);
			if (AccountRating.isPositive(rating) && outboundPositiveTargets.contains(inboundRating.getRaterAddress()))
				++mutualPositiveCount;

			evaluatorImpacts.add(buildEvaluatorImpact(repository, inboundRating));
		}

		evaluatorImpacts.sort(Comparator
				.comparingInt(AccountRatingsResource::getImpactMagnitude)
				.reversed()
				.thenComparing(AccountTrustPreviewData.EvaluatorImpact::getRaterAddress));

		return new AccountTrustPreviewData(targetPublicKey, targetAddress, activeTrustStatus, storedTrustStatus,
				inboundCounts, outboundCounts, mutualPositiveCount, evaluatorImpacts, activeTrustStatus, derivation.isMintingSeedMember(),
				derivation.getCategories());
	}

	private AccountTrustPreviewData.EvaluatorImpact buildEvaluatorImpact(Repository repository, AccountRatingData ratingData)
			throws DataException {
		AccountData raterAccountData = repository.getAccountRepository().getAccount(ratingData.getRaterAddress());
		AccountTrustStatus storedTrustStatus = raterAccountData == null ? AccountTrustStatus.UNVERIFIED : raterAccountData.getTrustStatus();
		int rawVoteWeight = raterAccountData == null ? 0 : raterAccountData.getBlocksMinted();
		int storedEffectiveVoteWeight = storedTrustStatus.calculateEffectiveVoteWeight(rawVoteWeight);
		AccountTrustSnapshotData activeTrustSnapshot = repository.getAccountRatingRepository()
				.getTrustDerivationSnapshot(ratingData.getRaterAddress(), AccountTrustWeight.ACTIVE_WEIGHT_CATEGORY);
		AccountTrustStatus activeTrustStatus = AccountTrustWeight.statusFromSnapshot(activeTrustSnapshot);
		int effectiveVoteWeight = AccountTrustWeight.calculateEffectiveVoteWeight(rawVoteWeight, activeTrustSnapshot);
		int impact = AccountRating.calculateImpact(ratingData.getRating(), effectiveVoteWeight);
		int storedImpact = AccountRating.calculateImpact(ratingData.getRating(), storedEffectiveVoteWeight);

		return new AccountTrustPreviewData.EvaluatorImpact(ratingData.getRaterPublicKey(), ratingData.getRaterAddress(),
				activeTrustStatus, rawVoteWeight, effectiveVoteWeight, storedTrustStatus, storedEffectiveVoteWeight,
				ratingData.getRating(), impact, storedImpact);
	}

	private static int getImpactMagnitude(AccountTrustPreviewData.EvaluatorImpact evaluatorImpact) {
		long magnitude = Math.abs((long) evaluatorImpact.getImpact());
		if (magnitude > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;

		return (int) magnitude;
	}

	private List<AccountTrustDerivationData> buildTrustDerivationFromSnapshots(List<AccountTrustSnapshotData> snapshots) {
		Map<String, List<AccountTrustSnapshotData>> snapshotsByAccount = new LinkedHashMap<>();
		for (AccountTrustSnapshotData snapshot : snapshots)
			snapshotsByAccount.computeIfAbsent(snapshot.getAccountAddress(), ignored -> new ArrayList<>()).add(snapshot);

		List<AccountTrustDerivationData> derivedAccounts = new ArrayList<>();
		for (List<AccountTrustSnapshotData> accountSnapshots : snapshotsByAccount.values()) {
			Map<AccountRatingCategory, AccountTrustSnapshotData> snapshotsByCategory = new EnumMap<>(AccountRatingCategory.class);
			for (AccountTrustSnapshotData snapshot : accountSnapshots)
				snapshotsByCategory.put(snapshot.getCategory(), snapshot);

			AccountTrustSnapshotData subjectSnapshot = snapshotsByCategory.get(AccountRatingCategory.SUBJECT);
			AccountTrustSnapshotData firstSnapshot = accountSnapshots.get(0);
			AccountTrustStatus derivedTrustStatus = subjectSnapshot == null
					? AccountTrustStatus.UNVERIFIED
					: subjectSnapshot.getMappedTrustStatus();
			List<AccountTrustPreviewData.CategoryTrust> categories = new ArrayList<>();

			for (AccountRatingCategory category : AccountRatingCategory.values()) {
				AccountTrustSnapshotData snapshot = snapshotsByCategory.get(category);
				if (snapshot == null)
					categories.add(new AccountTrustPreviewData.CategoryTrust(category, 0L, 0,
							AccountTrustStatus.UNVERIFIED, new AccountTrustPreviewData.RatingCounts(), new ArrayList<>()));
				else
					categories.add(new AccountTrustPreviewData.CategoryTrust(snapshot.getCategory(), snapshot.getScore(),
							snapshot.getLevelScore(), snapshot.getLevelScoreCap(), snapshot.getLevel(),
							snapshot.getMappedTrustStatus(), snapshot.getInboundRatings(), new ArrayList<>()));
			}

			derivedAccounts.add(new AccountTrustDerivationData(firstSnapshot.getAccountPublicKey(),
					firstSnapshot.getAccountAddress(), derivedTrustStatus, firstSnapshot.isMintingSeedMember(),
					firstSnapshot.getSnapshotHeight(), firstSnapshot.getSnapshotTimestamp(), false, categories));
		}

		return derivedAccounts;
	}

	private List<AccountTrustDerivationData> filterTrustDerivation(List<AccountTrustDerivationData> derivedAccounts,
			AccountTrustStatus status, AccountRatingCategory sortCategory, Boolean seedMember, Integer minLevel,
			Integer limit, Integer offset, Boolean reverse) {
		List<AccountTrustDerivationData> filteredAccounts = new ArrayList<>();

		for (AccountTrustDerivationData derivedAccount : derivedAccounts) {
			if (status != null && derivedAccount.getDerivedTrustStatus() != status)
				continue;

			if (seedMember != null && derivedAccount.isMintingSeedMember() != seedMember)
				continue;

			if (minLevel != null && getCategoryLevel(derivedAccount, sortCategory) < minLevel)
				continue;

			filteredAccounts.add(derivedAccount);
		}

		filteredAccounts.sort((left, right) -> compareTrustDerivationRows(left, right, sortCategory));
		if (Boolean.TRUE.equals(reverse))
			Collections.reverse(filteredAccounts);

		return pageTrustDerivation(filteredAccounts, limit, offset);
	}

	private List<AccountTrustSnapshotData> filterTrustSnapshots(List<AccountTrustSnapshotData> snapshots,
			AccountRatingCategory category, AccountTrustStatus status, Boolean seedMember, Integer minLevel,
			Integer limit, Integer offset, Boolean reverse) {
		List<AccountTrustSnapshotData> filteredSnapshots = new ArrayList<>();

		for (AccountTrustSnapshotData snapshot : snapshots) {
			if (category != null && snapshot.getCategory() != category)
				continue;

			if (status != null && snapshot.getMappedTrustStatus() != status)
				continue;

			if (seedMember != null && snapshot.isMintingSeedMember() != seedMember)
				continue;

			if (minLevel != null && snapshot.getLevel() < minLevel)
				continue;

			filteredSnapshots.add(snapshot);
		}

		filteredSnapshots.sort(Comparator.comparing(AccountTrustSnapshotData::getAccountAddress)
				.thenComparing(AccountTrustSnapshotData::getCategory));
		if (Boolean.TRUE.equals(reverse))
			Collections.reverse(filteredSnapshots);

		return pageTrustSnapshots(filteredSnapshots, limit, offset);
	}

	private static int compareTrustDerivationRows(AccountTrustDerivationData left, AccountTrustDerivationData right,
			AccountRatingCategory sortCategory) {
		int compare = Integer.compare(getCategoryLevel(right, sortCategory), getCategoryLevel(left, sortCategory));
		if (compare != 0)
			return compare;

		compare = Long.compare(getCategoryScore(right, sortCategory), getCategoryScore(left, sortCategory));
		if (compare != 0)
			return compare;

		return left.getAccountAddress().compareTo(right.getAccountAddress());
	}

	private static int getCategoryLevel(AccountTrustDerivationData derivedAccount, AccountRatingCategory category) {
		AccountTrustPreviewData.CategoryTrust categoryTrust = getCategoryTrust(derivedAccount, category);
		return categoryTrust == null ? 0 : categoryTrust.getLevel();
	}

	private static long getCategoryScore(AccountTrustDerivationData derivedAccount, AccountRatingCategory category) {
		AccountTrustPreviewData.CategoryTrust categoryTrust = getCategoryTrust(derivedAccount, category);
		return categoryTrust == null ? 0L : categoryTrust.getScore();
	}

	private static AccountTrustPreviewData.CategoryTrust getCategoryTrust(AccountTrustDerivationData derivedAccount,
			AccountRatingCategory category) {
		for (AccountTrustPreviewData.CategoryTrust categoryTrust : derivedAccount.getCategories())
			if (categoryTrust.getCategory() == category)
				return categoryTrust;

		return null;
	}

	private List<AccountTrustDerivationData> pageTrustDerivation(List<AccountTrustDerivationData> derivedAccounts,
			Integer limit, Integer offset) {
		int fromIndex = offset == null ? 0 : offset;
		if (fromIndex >= derivedAccounts.size())
			return new ArrayList<>();

		int toIndex = derivedAccounts.size();
		if (limit != null)
			toIndex = (int) Math.min((long) derivedAccounts.size(), (long) fromIndex + limit);

		return new ArrayList<>(derivedAccounts.subList(fromIndex, toIndex));
	}

	private List<AccountTrustSnapshotData> pageTrustSnapshots(List<AccountTrustSnapshotData> snapshots,
			Integer limit, Integer offset) {
		int fromIndex = offset == null ? 0 : offset;
		if (fromIndex >= snapshots.size())
			return new ArrayList<>();

		int toIndex = snapshots.size();
		if (limit != null)
			toIndex = (int) Math.min((long) snapshots.size(), (long) fromIndex + limit);

		return new ArrayList<>(snapshots.subList(fromIndex, toIndex));
	}

	private void validateTrustDerivationCriteria(Integer minLevel, Integer limit, Integer offset) {
		if (minLevel != null && minLevel < -1)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (limit != null && limit < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (offset != null && offset < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
	}

	private byte[] parseOptionalPublicKey(String publicKey58) {
		if (publicKey58 == null || publicKey58.trim().isEmpty())
			return null;

		return parsePublicKey(publicKey58);
	}

	private byte[] parsePublicKey(String publicKey58) {
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58.trim());
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
		}

		if (publicKey == null || publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		return publicKey;
	}

	private AccountRatingCategory parseOptionalCategory(String categoryName) {
		AccountRatingCategory category = AccountRatingCategory.parse(categoryName);
		if (categoryName != null && !categoryName.trim().isEmpty() && category == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return category;
	}

	private String parseOptionalAddress(String address) {
		if (address == null || address.trim().isEmpty())
			return null;

		String trimmedAddress = address.trim();
		if (!Crypto.isValidAddress(trimmedAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return trimmedAddress;
	}

	private AccountTrustStatus parseOptionalTrustStatus(String statusName) {
		if (statusName == null || statusName.trim().isEmpty())
			return null;

		try {
			return AccountTrustStatus.valueOf(statusName.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	private byte[] requireKnownPublicKey(Repository repository, String publicKey58) throws DataException {
		if (publicKey58 == null || publicKey58.trim().isEmpty())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] publicKey = parsePublicKey(publicKey58);
		AccountData accountData = repository.getAccountRepository().getAccount(Crypto.toAddress(publicKey));
		if (accountData == null || accountData.getPublicKey() == null || !Arrays.equals(publicKey, accountData.getPublicKey()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return publicKey;
	}
}
