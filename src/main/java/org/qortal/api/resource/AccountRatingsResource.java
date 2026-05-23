package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.AccountRatingValidation;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.AccountTrustPolicy;
import org.qortal.account.AccountTrustWeight;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingCooldownData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingImpactPreviewData;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustExplanationData;
import org.qortal.data.account.AccountTrustPolicyData;
import org.qortal.data.account.AccountTrustCategoryData;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustRatingCountsData;
import org.qortal.data.account.AccountTrustProfileData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.account.AccountTrustStatusChangeData;
import org.qortal.data.account.AccountTrustSummaryData;
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

	private static final int TRUST_EXPLANATION_IMPACT_LIMIT = 5;

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
	@Path("/cooldown")
	@Operation(
			summary = "Get account rating cooldown status for one rater, target, and category edge",
			responses = {
					@ApiResponse(
							description = "account rating cooldown status",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountRatingCooldownData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountRatingCooldownData getAccountRatingCooldown(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58,
			@Parameter(description = "Rater account public key in Base58") @QueryParam("rater") String raterPublicKey58,
			@Parameter(description = "Optional account rating category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			byte[] raterPublicKey = requirePublicKey(raterPublicKey58);
			AccountRatingCategory category = parseOptionalCategory(categoryName);

			return buildAccountRatingCooldown(repository, targetPublicKey, raterPublicKey, category);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/preview")
	@Operation(
			summary = "Preview the live trust impact of a proposed account rating",
			responses = {
					@ApiResponse(
							description = "account rating impact preview",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountRatingImpactPreviewData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountRatingImpactPreviewData getAccountRatingImpactPreview(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58,
			@Parameter(description = "Rater account public key in Base58") @QueryParam("rater") String raterPublicKey58,
			@Parameter(description = "Optional account rating category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName,
			@Parameter(description = "Proposed rating: -4 through -1, 0 to remove, or 1 through 4") @QueryParam("rating") Integer rating) {
		if (rating == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requirePublicKey(targetPublicKey58);
			byte[] raterPublicKey = requirePublicKey(raterPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);
			String raterAddress = Crypto.toAddress(raterPublicKey);
			AccountRatingCategory category = parseOptionalCategory(categoryName);
			if (category == null)
				category = AccountRatingCategory.SUBJECT;

			AccountRatingData activeRatingData = repository.getAccountRatingRepository()
					.getRating(targetPublicKey, raterPublicKey, category);
			Integer activeRating = activeRatingData == null ? null : activeRatingData.getRating();
			AccountRatingCooldownData cooldown = buildAccountRatingCooldown(repository, targetPublicKey, raterPublicKey,
					category);
			Transaction.ValidationResult validationResult = AccountRatingValidation.validateRatingChange(repository,
					targetPublicKey, raterPublicKey, category, rating, cooldown.getCandidateChangeHeight());

			AccountTrustDerivation.Result currentResult = AccountTrustDerivation.derive(repository, targetAddress);
			AccountTrustDerivation.Result previewResult = currentResult;
			Integer previewActiveRating = activeRating;
			if (validationResult == Transaction.ValidationResult.OK) {
				AccountRatingData ratingOverlay = new AccountRatingData(targetPublicKey, targetAddress, raterPublicKey,
						raterAddress, category, rating);
				previewResult = AccountTrustDerivation.deriveWithRatingOverlay(repository, targetAddress, ratingOverlay);
				previewActiveRating = AccountRating.isActive(rating) ? rating : null;
			}

			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, currentHeight);
			AccountTrustDerivationData currentTrust = buildLiveTrustDerivationData(targetPublicKey, targetAddress,
					currentResult, voteWeightPercents);
			AccountTrustDerivationData previewTrust = buildLiveTrustDerivationData(targetPublicKey, targetAddress,
					previewResult, voteWeightPercents);

			return new AccountRatingImpactPreviewData(targetPublicKey, raterPublicKey, category, rating, activeRating,
					previewActiveRating, validationResult, cooldown, currentTrust, previewTrust,
					getCategoryTrust(currentTrust, category), getCategoryTrust(previewTrust, category));
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-policy")
	@Operation(
			summary = "Get active account trust policy settings",
			responses = {
					@ApiResponse(
							description = "account trust policy",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustPolicyData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public AccountTrustPolicyData getAccountTrustPolicy() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			return buildTrustPolicy(repository, currentHeight);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-summary")
	@Operation(
			summary = "Get stored account trust network summary",
			responses = {
					@ApiResponse(
							description = "account trust network summary",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustSummaryData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public AccountTrustSummaryData getAccountTrustSummary() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-changes")
	@Operation(
			summary = "List stored account trust level and status changes",
			responses = {
					@ApiResponse(
							description = "account trust level and status changes",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = AccountTrustStatusChangeData.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<AccountTrustStatusChangeData> getAccountTrustStatusChanges(
			@Parameter(description = "Optional account address") @QueryParam("account") String account,
			@Parameter(description = "Optional category: SUBJECT, PLAYER, TRAINER, or MANAGER") @QueryParam("category") String categoryName,
			@Parameter(description = "Optional previous mapped trust status: GOLD, SILVER, BRONZE, UNVERIFIED, or SUSPICIOUS") @QueryParam("previousStatus") String previousStatusName,
			@Parameter(description = "Optional new mapped trust status: GOLD, SILVER, BRONZE, UNVERIFIED, or SUSPICIOUS") @QueryParam("newStatus") String newStatusName,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(description = "If true, return oldest changes first. The default is newest changes first.") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String accountAddress = parseOptionalAddress(account);
			AccountRatingCategory category = parseOptionalCategory(categoryName);
			AccountTrustStatus previousStatus = parseOptionalTrustStatus(previousStatusName);
			AccountTrustStatus newStatus = parseOptionalTrustStatus(newStatusName);

			validateTrustDerivationCriteria(null, limit, offset);

			return repository.getAccountRatingRepository().getTrustStatusChanges(accountAddress, category, previousStatus,
					newStatus, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-profile")
	@Operation(
			summary = "Get one account's stored trust profile",
			responses = {
					@ApiResponse(
							description = "account trust profile",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustProfileData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountTrustProfileData getAccountTrustProfile(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);

			return buildTrustProfile(repository, targetPublicKey, targetAddress);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-explanation")
	@Operation(
			summary = "Explain one account's active Aura-style trust status",
			responses = {
					@ApiResponse(
							description = "account trust explanation",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustExplanationData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public AccountTrustExplanationData getAccountTrustExplanation(
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58,
			@Parameter(description = "If true, recalculate from active account ratings instead of explaining stored block snapshots") @QueryParam("live") Boolean live) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);
			AccountTrustDerivation.Result liveDerivation = AccountTrustDerivation.derive(repository, targetAddress);
			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, currentHeight);

			return Boolean.TRUE.equals(live)
					? buildLiveTrustExplanation(targetPublicKey, targetAddress, liveDerivation, voteWeightPercents)
					: buildStoredTrustExplanation(repository, targetPublicKey, targetAddress, liveDerivation, voteWeightPercents);
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

			if (Boolean.TRUE.equals(live)) {
				List<AccountTrustDerivationData> derivedAccounts = AccountTrustDerivation.deriveAll(repository);
				return filterTrustDerivation(derivedAccounts, status, sortCategory, seedMember, minLevel, limit, offset,
						reverse);
			}

			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, currentHeight);

			return buildTrustDerivationFromSnapshots(repository.getAccountRatingRepository()
					.getTrustDerivationSnapshotsForDerivation(status, sortCategory, seedMember, minLevel, limit, offset,
							reverse), voteWeightPercents);
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

			return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress, category, status,
					seedMember, minLevel, limit, offset, reverse);
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

	private AccountRatingCooldownData buildAccountRatingCooldown(Repository repository, byte[] targetPublicKey,
			byte[] raterPublicKey, AccountRatingCategory category) throws DataException {
		String targetAddress = Crypto.toAddress(targetPublicKey);
		String raterAddress = Crypto.toAddress(raterPublicKey);
		if (category == null)
			category = AccountRatingCategory.SUBJECT;

		AccountRatingData activeRatingData = repository.getAccountRatingRepository()
				.getRating(targetPublicKey, raterPublicKey, category);
		Integer activeRating = activeRatingData == null ? null : activeRatingData.getRating();
		Integer latestRatingChangeHeight = repository.getAccountRatingRepository()
				.getLatestRatingChangeHeight(targetPublicKey, raterPublicKey, category);
		int currentHeight = repository.getBlockRepository().getBlockchainHeight();
		int candidateChangeHeight = currentHeight + 1;
		int cooldownBlocks = AccountTrustPolicy.getAccountRatingChangeCooldownBlocks(repository, candidateChangeHeight);
		int earliestAllowedHeight = calculateEarliestAllowedHeight(candidateChangeHeight, latestRatingChangeHeight,
				cooldownBlocks);
		int blocksRemaining = Math.max(0, earliestAllowedHeight - candidateChangeHeight);
		boolean canChangeNow = blocksRemaining == 0;

		return new AccountRatingCooldownData(targetPublicKey, targetAddress, raterPublicKey, raterAddress, category,
				activeRating, cooldownBlocks, latestRatingChangeHeight, currentHeight, candidateChangeHeight,
				earliestAllowedHeight, blocksRemaining, canChangeNow);
	}

	private AccountTrustPolicyData buildTrustPolicy(Repository repository, int height) throws DataException {
		int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, height);
		List<AccountTrustPolicyData.StatusVoteWeight> statusVoteWeights = new ArrayList<>();
		for (AccountTrustStatus status : AccountTrustStatus.values())
			statusVoteWeights.add(new AccountTrustPolicyData.StatusVoteWeight(status,
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, status)));

		List<AccountTrustPolicyData.CategoryPolicy> categoryPolicies = new ArrayList<>();
		for (AccountRatingCategory category : AccountRatingCategory.values())
			categoryPolicies.add(buildTrustCategoryPolicy(category, voteWeightPercents));

		return new AccountTrustPolicyData(AccountTrustPolicy.getActiveWeightCategory(),
				AccountTrustPolicy.getStartingEnergy(repository, height),
				AccountTrustPolicy.getManagerEnergyHops(repository, height),
				AccountTrustPolicy.getPositiveMinBranchCount(),
				AccountTrustPolicy.getSuspiciousMinRaterCount(), AccountTrustPolicy.getSuspiciousMinBranchCount(),
				AccountTrustPolicy.getSuspiciousMinRatingConfidence(),
				AccountTrustPolicy.getAccountRatingChangeCooldownBlocks(repository, height), statusVoteWeights, categoryPolicies);
	}

	private AccountTrustPolicyData.CategoryPolicy buildTrustCategoryPolicy(AccountRatingCategory category,
			int[] voteWeightPercents) {
		List<AccountTrustPolicyData.LevelPolicy> levels = new ArrayList<>();
		for (int level = 1; level <= getMaximumConfiguredLevel(category); ++level) {
			AccountTrustStatus mappedStatus = AccountTrustPolicy.mapLevelToStatus(level);
			levels.add(new AccountTrustPolicyData.LevelPolicy(level, mappedStatus,
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, mappedStatus),
					AccountTrustPolicy.getLevelThreshold(category, level), AccountTrustPolicy.getLevelScoreCap(category, level)));
		}

		return new AccountTrustPolicyData.CategoryPolicy(category, levels, AccountTrustPolicy.getSuspiciousThreshold(category),
				AccountTrustPolicy.getSuspiciousLevelScoreCap(category));
	}

	private int calculateEarliestAllowedHeight(int candidateChangeHeight, Integer latestRatingChangeHeight,
			int cooldownBlocks) {
		if (cooldownBlocks <= 0 || latestRatingChangeHeight == null)
			return candidateChangeHeight;

		return Math.max(candidateChangeHeight, latestRatingChangeHeight + cooldownBlocks);
	}

	private AccountTrustProfileData buildTrustProfile(Repository repository, byte[] targetPublicKey, String targetAddress)
			throws DataException {
		AccountRatingCategory activeCategory = AccountTrustWeight.getActiveWeightCategory();
		List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
				.getTrustDerivationSnapshots(targetAddress);
		Map<AccountRatingCategory, AccountTrustSnapshotData> snapshotsByCategory = new EnumMap<>(AccountRatingCategory.class);

		for (AccountTrustSnapshotData snapshot : snapshots)
			snapshotsByCategory.put(snapshot.getCategory(), snapshot);

		AccountTrustSnapshotData activeSnapshot = snapshotsByCategory.get(activeCategory);
		AccountTrustStatus activeTrustStatus = AccountTrustWeight.statusFromSnapshot(activeSnapshot);
		AccountData targetAccountData = repository.getAccountRepository().getAccount(targetAddress);
		int blocksMinted = targetAccountData == null ? 0 : targetAccountData.getBlocksMinted();
		int currentHeight = repository.getBlockRepository().getBlockchainHeight();
		int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, currentHeight);
		int trustWeightPercent = AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, activeTrustStatus);
		int effectiveVoteWeight = AccountTrustPolicy.calculateEffectiveVoteWeight(voteWeightPercents, blocksMinted,
				activeTrustStatus);
		AccountTrustSnapshotData referenceSnapshot = activeSnapshot == null && !snapshots.isEmpty()
				? snapshots.get(0)
				: activeSnapshot;
		boolean mintingSeedMember = referenceSnapshot != null && referenceSnapshot.isMintingSeedMember();
		Integer snapshotHeight = activeSnapshot == null ? null : activeSnapshot.getSnapshotHeight();
		Long snapshotTimestamp = activeSnapshot == null ? null : activeSnapshot.getSnapshotTimestamp();
		Map<AccountRatingCategory, AccountTrustRatingCountsData> outboundCountsByCategory =
				buildOutboundRatingCountsByCategory(repository, targetPublicKey);

		return new AccountTrustProfileData(targetPublicKey, targetAddress, activeTrustStatus, trustWeightPercent,
				blocksMinted, effectiveVoteWeight, activeCategory, mintingSeedMember, snapshotHeight, snapshotTimestamp,
				buildCategoryProfiles(snapshotsByCategory, outboundCountsByCategory, voteWeightPercents));
	}

	private AccountTrustDerivationData buildLiveTrustDerivationData(byte[] targetPublicKey, String targetAddress,
			AccountTrustDerivation.Result derivation, int[] voteWeightPercents) {
		return new AccountTrustDerivationData(targetPublicKey, targetAddress, derivation.getDerivedTrustStatus(),
				AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, derivation.getDerivedTrustStatus()),
				derivation.isMintingSeedMember(), null, null, true, derivation.getCategories());
	}

	private List<AccountTrustProfileData.CategoryProfile> buildCategoryProfiles(
			Map<AccountRatingCategory, AccountTrustSnapshotData> snapshotsByCategory,
			Map<AccountRatingCategory, AccountTrustRatingCountsData> outboundCountsByCategory,
			int[] voteWeightPercents) {
		List<AccountTrustProfileData.CategoryProfile> categories = new ArrayList<>();

		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			AccountTrustSnapshotData snapshot = snapshotsByCategory.get(category);
			AccountTrustRatingCountsData outboundCounts = outboundCountsByCategory.get(category);

			if (snapshot == null) {
				categories.add(new AccountTrustProfileData.CategoryProfile(category, 0L, 0L, 0L, 0,
						AccountTrustStatus.UNVERIFIED,
						AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, AccountTrustStatus.UNVERIFIED),
						new AccountTrustRatingCountsData(), outboundCounts, null, null));
				continue;
			}

			categories.add(new AccountTrustProfileData.CategoryProfile(snapshot.getCategory(), snapshot.getScore(),
					snapshot.getLevelScore(), snapshot.getLevelScoreCap(), snapshot.getLevel(),
					snapshot.getMappedTrustStatus(),
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, snapshot.getMappedTrustStatus()),
					snapshot.getInboundRatings(), outboundCounts, snapshot.getSnapshotHeight(), snapshot.getSnapshotTimestamp()));
		}

		return categories;
	}

	private Map<AccountRatingCategory, AccountTrustRatingCountsData> buildOutboundRatingCountsByCategory(
			Repository repository, byte[] raterPublicKey) throws DataException {
		Map<AccountRatingCategory, AccountTrustRatingCountsData> countsByCategory =
				new EnumMap<>(AccountRatingCategory.class);

		for (AccountRatingCategory category : AccountRatingCategory.values())
			countsByCategory.put(category, new AccountTrustRatingCountsData());

		List<AccountRatingData> outboundRatings = repository.getAccountRatingRepository()
				.getRatings(null, raterPublicKey, null, null, null, null);
		for (AccountRatingData rating : outboundRatings)
			countsByCategory.get(rating.getCategory()).addRating(rating.getRating());

		return countsByCategory;
	}

	private AccountTrustExplanationData buildLiveTrustExplanation(byte[] targetPublicKey, String targetAddress,
			AccountTrustDerivation.Result liveDerivation, int[] voteWeightPercents) {
		AccountRatingCategory activeCategory = AccountTrustWeight.getActiveWeightCategory();
		AccountTrustCategoryData activeCategoryTrust = getCategoryTrust(liveDerivation.getCategories(), activeCategory);
		AccountTrustStatus activeTrustStatus = activeCategoryTrust == null
				? AccountTrustStatus.UNVERIFIED
				: activeCategoryTrust.getMappedTrustStatus();

		return new AccountTrustExplanationData(targetPublicKey, targetAddress, activeTrustStatus,
				AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, activeTrustStatus), activeCategory,
				liveDerivation.isMintingSeedMember(), null, null, true,
				buildCategoryExplanations(liveDerivation.getCategories(), liveDerivation.getCategories(), voteWeightPercents));
	}

	private AccountTrustExplanationData buildStoredTrustExplanation(Repository repository, byte[] targetPublicKey,
			String targetAddress, AccountTrustDerivation.Result liveDerivation, int[] voteWeightPercents) throws DataException {
		AccountRatingCategory activeCategory = AccountTrustWeight.getActiveWeightCategory();
		List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
				.getTrustDerivationSnapshots(targetAddress);
		Map<AccountRatingCategory, AccountTrustSnapshotData> snapshotsByCategory = new EnumMap<>(AccountRatingCategory.class);

		for (AccountTrustSnapshotData snapshot : snapshots)
			snapshotsByCategory.put(snapshot.getCategory(), snapshot);

		AccountTrustSnapshotData activeSnapshot = snapshotsByCategory.get(activeCategory);
		AccountTrustStatus activeTrustStatus = activeSnapshot == null
				? AccountTrustStatus.UNVERIFIED
				: activeSnapshot.getMappedTrustStatus();
		AccountTrustSnapshotData referenceSnapshot = activeSnapshot == null && !snapshots.isEmpty()
				? snapshots.get(0)
				: activeSnapshot;
		boolean mintingSeedMember = referenceSnapshot == null ? liveDerivation.isMintingSeedMember() : referenceSnapshot.isMintingSeedMember();
		Integer snapshotHeight = referenceSnapshot == null ? null : referenceSnapshot.getSnapshotHeight();
		Long snapshotTimestamp = referenceSnapshot == null ? null : referenceSnapshot.getSnapshotTimestamp();

		return new AccountTrustExplanationData(targetPublicKey, targetAddress, activeTrustStatus,
				AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, activeTrustStatus), activeCategory,
				mintingSeedMember, snapshotHeight, snapshotTimestamp, false,
				buildCategoryExplanations(buildCategoryTrustsFromSnapshots(snapshotsByCategory),
						liveDerivation.getCategories(), voteWeightPercents));
	}

	private List<AccountTrustCategoryData> buildCategoryTrustsFromSnapshots(
			Map<AccountRatingCategory, AccountTrustSnapshotData> snapshotsByCategory) {
		List<AccountTrustCategoryData> categories = new ArrayList<>();

		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			AccountTrustSnapshotData snapshot = snapshotsByCategory.get(category);
			if (snapshot == null) {
				categories.add(new AccountTrustCategoryData(category, 0L, 0,
						AccountTrustStatus.UNVERIFIED, new AccountTrustRatingCountsData(), new ArrayList<>()));
				continue;
			}

			categories.add(new AccountTrustCategoryData(snapshot.getCategory(), snapshot.getScore(),
					snapshot.getLevelScore(), snapshot.getLevelScoreCap(), snapshot.getLevel(), snapshot.getMappedTrustStatus(),
					snapshot.getInboundRatings(), new ArrayList<>()));
		}

		return categories;
	}

	private List<AccountTrustExplanationData.CategoryExplanation> buildCategoryExplanations(
			List<AccountTrustCategoryData> statusCategories,
			List<AccountTrustCategoryData> impactCategories,
			int[] voteWeightPercents) {
		List<AccountTrustExplanationData.CategoryExplanation> explanations = new ArrayList<>();

		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			AccountTrustCategoryData statusCategory = getCategoryTrust(statusCategories, category);
			AccountTrustCategoryData impactCategory = getCategoryTrust(impactCategories, category);
			List<AccountTrustCategoryImpactData> impacts = impactCategory == null
					? new ArrayList<>()
					: impactCategory.getImpacts();

			if (statusCategory == null)
				statusCategory = new AccountTrustCategoryData(category, 0L, 0,
						AccountTrustStatus.UNVERIFIED, new AccountTrustRatingCountsData(), new ArrayList<>());

			explanations.add(buildCategoryExplanation(statusCategory, impacts, voteWeightPercents));
		}

		return explanations;
	}

	private AccountTrustExplanationData.CategoryExplanation buildCategoryExplanation(
			AccountTrustCategoryData categoryTrust, List<AccountTrustCategoryImpactData> impacts,
			int[] voteWeightPercents) {
		AccountRatingCategory category = categoryTrust.getCategory();
		List<AccountTrustExplanationData.ConfiguredLevel> configuredLevels = buildConfiguredLevels(category);
		List<AccountTrustExplanationData.Requirement> requirements = buildTrustRequirements(category, categoryTrust.getScore(), impacts);

		return new AccountTrustExplanationData.CategoryExplanation(category, categoryTrust.getScore(),
				categoryTrust.getLevelScore(), categoryTrust.getLevelScoreCap(), categoryTrust.getLevel(),
				categoryTrust.getMappedTrustStatus(),
				AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, categoryTrust.getMappedTrustStatus()),
				categoryTrust.getInboundRatings(), configuredLevels,
				AccountTrustPolicy.getSuspiciousThreshold(category), AccountTrustPolicy.getSuspiciousLevelScoreCap(category),
				AccountTrustPolicy.getPositiveMinBranchCount(), AccountTrustPolicy.getSuspiciousMinRaterCount(),
				AccountTrustPolicy.getSuspiciousMinBranchCount(), AccountTrustPolicy.getSuspiciousMinRatingConfidence(),
				requirements, getTopImpacts(impacts, true), getTopImpacts(impacts, false));
	}

	private List<AccountTrustExplanationData.ConfiguredLevel> buildConfiguredLevels(AccountRatingCategory category) {
		List<AccountTrustExplanationData.ConfiguredLevel> levels = new ArrayList<>();

		for (int level = 1; level <= getMaximumConfiguredLevel(category); ++level)
			levels.add(new AccountTrustExplanationData.ConfiguredLevel(level,
					AccountTrustPolicy.getLevelThreshold(category, level), AccountTrustPolicy.getLevelScoreCap(category, level)));

		return levels;
	}

	private List<AccountTrustExplanationData.Requirement> buildTrustRequirements(AccountRatingCategory category,
			long rawScore, List<AccountTrustCategoryImpactData> impacts) {
		List<AccountTrustExplanationData.Requirement> requirements = new ArrayList<>();
		long suspiciousScore = calculateCappedScore(impacts, AccountTrustPolicy.getSuspiciousLevelScoreCap(category));
		long suspiciousThreshold = AccountTrustPolicy.getSuspiciousThreshold(category);
		long negativeRaterCount = countNegativeRaters(impacts, AccountTrustPolicy.getSuspiciousMinRatingConfidence());
		long negativeBranchCount = countNegativeTrustBranches(impacts, AccountTrustPolicy.getSuspiciousMinRatingConfidence());

		requirements.add(new AccountTrustExplanationData.Requirement("suspicious.threshold",
				suspiciousScore <= suspiciousThreshold, Long.toString(suspiciousScore), Long.toString(suspiciousThreshold),
				"Capped signed score must be at or below the Suspicious threshold."));
		requirements.add(new AccountTrustExplanationData.Requirement("suspicious.independent-raters",
				negativeRaterCount >= AccountTrustPolicy.getSuspiciousMinRaterCount(), Long.toString(negativeRaterCount),
				Long.toString(AccountTrustPolicy.getSuspiciousMinRaterCount()),
				"Distinct negative raters must meet the configured minimum confidence."));
		requirements.add(new AccountTrustExplanationData.Requirement("suspicious.independent-branches",
				negativeBranchCount >= AccountTrustPolicy.getSuspiciousMinBranchCount(), Long.toString(negativeBranchCount),
				Long.toString(AccountTrustPolicy.getSuspiciousMinBranchCount()),
				"Distinct negative trust branches must meet the configured minimum confidence."));
		requirements.add(new AccountTrustExplanationData.Requirement("positive.raw-score",
				rawScore >= 0L, Long.toString(rawScore), ">= 0",
				"Positive trust levels are only considered when raw score is non-negative."));

		for (int level = 1; level <= getMaximumConfiguredLevel(category); ++level) {
			long cap = AccountTrustPolicy.getLevelScoreCap(category, level);
			long levelScore = calculateCappedScore(impacts, cap);
			long threshold = AccountTrustPolicy.getLevelThreshold(category, level);
			long positiveBranchCount = countPositiveTrustBranches(impacts);

			requirements.add(new AccountTrustExplanationData.Requirement("level." + level + ".threshold",
					levelScore >= threshold, Long.toString(levelScore), Long.toString(threshold),
					"Capped score must reach this level's threshold."));
			requirements.add(new AccountTrustExplanationData.Requirement("level." + level + ".independent-branches",
					positiveBranchCount >= AccountTrustPolicy.getPositiveMinBranchCount(),
					Long.toString(positiveBranchCount), Long.toString(AccountTrustPolicy.getPositiveMinBranchCount()),
					"Distinct positive trust branches must meet the configured minimum."));

			AccountTrustExplanationData.Requirement supportRequirement = buildPositiveSupportRequirement(category, level, impacts);
			if (supportRequirement != null)
				requirements.add(supportRequirement);
		}

		return requirements;
	}

	private AccountTrustExplanationData.Requirement buildPositiveSupportRequirement(AccountRatingCategory category,
			int level, List<AccountTrustCategoryImpactData> impacts) {
		switch (category) {
			case PLAYER:
				if (level == 2)
					return positiveSupportRequirement("level.2.positive-support", hasPositiveImpact(impacts, 1, 2),
							countPositiveImpacts(impacts, 1, 2), ">= 1", "At least one level-1 evaluator with medium positive confidence is required.");
				if (level == 3)
					return positiveSupportRequirement("level.3.positive-support", hasPositiveImpact(impacts, 2, 3)
									|| countPositiveImpacts(impacts, 2, 2) >= 2,
							countPositiveImpacts(impacts, 2, 2), ">= 1 high-confidence or >= 2 medium-confidence",
							"Level 3 needs stronger positive support from level-2 evaluators.");
				return null;

			case SUBJECT:
				if (level == 1)
					return positiveSupportRequirement("level.1.positive-support", hasPositiveImpact(impacts, 1, 1),
							countPositiveImpacts(impacts, 1, 1), ">= 1",
							"Subject Bronze needs at least one positive evaluator at level 1.");
				if (level == 2)
					return positiveSupportRequirement("level.2.positive-support", hasPositiveImpact(impacts, 1, 2),
							countPositiveImpacts(impacts, 1, 2), ">= 1",
							"Subject Silver needs at least one medium-confidence positive evaluator at level 1.");
				if (level == 3)
					return positiveSupportRequirement("level.3.positive-support", hasPositiveImpact(impacts, 2, 3)
									|| countPositiveImpacts(impacts, 2, 2) >= 2,
							countPositiveImpacts(impacts, 2, 2), ">= 1 high-confidence or >= 2 medium-confidence",
							"Subject Gold threshold level 3 needs stronger support from level-2 evaluators.");
				if (level == 4)
					return positiveSupportRequirement("level.4.positive-support", hasPositiveImpact(impacts, 3, 3)
									|| countPositiveImpacts(impacts, 3, 2) >= 2,
							countPositiveImpacts(impacts, 3, 2), ">= 1 high-confidence or >= 2 medium-confidence",
							"Subject level 4 needs stronger support from level-3 evaluators.");
				return null;

			case MANAGER:
			case TRAINER:
			default:
				return null;
		}
	}

	private AccountTrustExplanationData.Requirement positiveSupportRequirement(String name, boolean passed, long actual,
			String required, String description) {
		return new AccountTrustExplanationData.Requirement(name, passed, Long.toString(actual), required, description);
	}

	private int getMaximumConfiguredLevel(AccountRatingCategory category) {
		switch (category) {
			case MANAGER:
			case TRAINER:
				return 2;

			case PLAYER:
				return 3;

			case SUBJECT:
			default:
				return 4;
		}
	}

	private long calculateCappedScore(List<AccountTrustCategoryImpactData> impacts, long cap) {
		long score = 0L;
		for (AccountTrustCategoryImpactData impact : impacts) {
			long impactValue = impact.getImpact();
			if (impactValue > cap)
				impactValue = cap;
			else if (impactValue < -cap)
				impactValue = -cap;

			score = saturatedAdd(score, impactValue);
		}

		return score;
	}

	private long saturatedAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0)
			return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;

		return result;
	}

	private long countNegativeRaters(List<AccountTrustCategoryImpactData> impacts, int minConfidence) {
		return impacts.stream()
				.filter(impact -> impact.getRatingConfidence() >= minConfidence && impact.getImpact() < 0)
				.map(AccountTrustCategoryImpactData::getRaterAddress)
				.distinct()
				.count();
	}

	private long countNegativeTrustBranches(List<AccountTrustCategoryImpactData> impacts, int minConfidence) {
		Set<String> trustBranchKeys = new HashSet<>();
		for (AccountTrustCategoryImpactData impact : impacts) {
			if (impact.getRatingConfidence() < minConfidence || impact.getImpact() >= 0)
				continue;

			if (impact.getTrustBranchKeys() != null)
				trustBranchKeys.addAll(impact.getTrustBranchKeys());
		}

		return trustBranchKeys.size();
	}

	private long countPositiveTrustBranches(List<AccountTrustCategoryImpactData> impacts) {
		Set<String> trustBranchKeys = new HashSet<>();
		for (AccountTrustCategoryImpactData impact : impacts) {
			if (impact.getImpact() <= 0)
				continue;

			if (impact.getTrustBranchKeys() != null)
				trustBranchKeys.addAll(impact.getTrustBranchKeys());
		}

		return trustBranchKeys.size();
	}

	private boolean hasPositiveImpact(List<AccountTrustCategoryImpactData> impacts, int minLevel, int minConfidence) {
		return impacts.stream().anyMatch(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0);
	}

	private long countPositiveImpacts(List<AccountTrustCategoryImpactData> impacts, int minLevel, int minConfidence) {
		return impacts.stream().filter(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0).count();
	}

	private List<AccountTrustCategoryImpactData> getTopImpacts(
			List<AccountTrustCategoryImpactData> impacts, boolean positive) {
		List<AccountTrustCategoryImpactData> filteredImpacts = new ArrayList<>();
		for (AccountTrustCategoryImpactData impact : impacts) {
			if (positive && impact.getImpact() > 0)
				filteredImpacts.add(impact);
			else if (!positive && impact.getImpact() < 0)
				filteredImpacts.add(impact);
		}

		filteredImpacts.sort(Comparator
				.comparingLong((AccountTrustCategoryImpactData impact) -> Math.abs(impact.getImpact()))
				.reversed()
				.thenComparing(AccountTrustCategoryImpactData::getRaterAddress));

		if (filteredImpacts.size() > TRUST_EXPLANATION_IMPACT_LIMIT)
			return new ArrayList<>(filteredImpacts.subList(0, TRUST_EXPLANATION_IMPACT_LIMIT));

		return filteredImpacts;
	}

	private List<AccountTrustDerivationData> buildTrustDerivationFromSnapshots(List<AccountTrustSnapshotData> snapshots,
			int[] voteWeightPercents) {
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
			List<AccountTrustCategoryData> categories = new ArrayList<>();

			for (AccountRatingCategory category : AccountRatingCategory.values()) {
				AccountTrustSnapshotData snapshot = snapshotsByCategory.get(category);
				if (snapshot == null)
					categories.add(new AccountTrustCategoryData(category, 0L, 0,
							AccountTrustStatus.UNVERIFIED, new AccountTrustRatingCountsData(), new ArrayList<>()));
				else
					categories.add(new AccountTrustCategoryData(snapshot.getCategory(), snapshot.getScore(),
							snapshot.getLevelScore(), snapshot.getLevelScoreCap(), snapshot.getLevel(),
							snapshot.getMappedTrustStatus(), snapshot.getInboundRatings(), new ArrayList<>()));
			}

			derivedAccounts.add(new AccountTrustDerivationData(firstSnapshot.getAccountPublicKey(),
					firstSnapshot.getAccountAddress(), derivedTrustStatus,
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, derivedTrustStatus),
					firstSnapshot.isMintingSeedMember(), firstSnapshot.getSnapshotHeight(),
					firstSnapshot.getSnapshotTimestamp(), false, categories));
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
		AccountTrustCategoryData categoryTrust = getCategoryTrust(derivedAccount, category);
		return categoryTrust == null ? 0 : categoryTrust.getLevel();
	}

	private static long getCategoryScore(AccountTrustDerivationData derivedAccount, AccountRatingCategory category) {
		AccountTrustCategoryData categoryTrust = getCategoryTrust(derivedAccount, category);
		return categoryTrust == null ? 0L : categoryTrust.getScore();
	}

	private static AccountTrustCategoryData getCategoryTrust(AccountTrustDerivationData derivedAccount,
			AccountRatingCategory category) {
		for (AccountTrustCategoryData categoryTrust : derivedAccount.getCategories())
			if (categoryTrust.getCategory() == category)
				return categoryTrust;

		return null;
	}

	private static AccountTrustCategoryData getCategoryTrust(List<AccountTrustCategoryData> categories,
			AccountRatingCategory category) {
		for (AccountTrustCategoryData categoryTrust : categories)
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

	private byte[] requirePublicKey(String publicKey58) {
		if (publicKey58 == null || publicKey58.trim().isEmpty())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return parsePublicKey(publicKey58);
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
