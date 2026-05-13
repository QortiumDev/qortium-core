package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingLevel;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustPreviewData;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = parseOptionalPublicKey(targetPublicKey58);
			byte[] raterPublicKey = parseOptionalPublicKey(raterPublicKey58);

			return repository.getAccountRatingRepository().getRatings(targetPublicKey, raterPublicKey, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
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
			@Parameter(description = "Target account public key in Base58") @QueryParam("target") String targetPublicKey58) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] targetPublicKey = requireKnownPublicKey(repository, targetPublicKey58);
			String targetAddress = Crypto.toAddress(targetPublicKey);

			return repository.getAccountRatingRepository().getRatingSummary(targetPublicKey, targetAddress);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trust-preview")
	@Operation(
			summary = "Preview decentralized account trust evidence without changing consensus state",
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
			AccountTrustStatus trustStatus = targetAccountData == null ? AccountTrustStatus.UNVERIFIED : targetAccountData.getTrustStatus();

			List<AccountRatingData> inboundRatings = repository.getAccountRatingRepository()
					.getRatings(targetPublicKey, null, null, null, null);
			List<AccountRatingData> outboundRatings = repository.getAccountRatingRepository()
					.getRatings(null, targetPublicKey, null, null, null);

			return buildTrustPreview(targetPublicKey, targetAddress, trustStatus, inboundRatings, outboundRatings);
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
					description = "Rate another known account. UNKNOWN removes the active account rating; UNTRUSTED, KNOWN, and TRUSTED record active relationships.",
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

	private AccountTrustPreviewData buildTrustPreview(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			List<AccountRatingData> inboundRatings, List<AccountRatingData> outboundRatings) {
		int inboundTrustedCount = 0;
		int inboundKnownCount = 0;
		int inboundUntrustedCount = 0;
		int outboundTrustedCount = 0;
		int outboundKnownCount = 0;
		int outboundUntrustedCount = 0;

		Set<String> outboundPositiveTargets = new HashSet<>();
		for (AccountRatingData outboundRating : outboundRatings) {
			AccountRatingLevel ratingLevel = outboundRating.getRatingLevel();

			if (ratingLevel == AccountRatingLevel.TRUSTED) {
				++outboundTrustedCount;
				outboundPositiveTargets.add(outboundRating.getTargetAddress());
			} else if (ratingLevel == AccountRatingLevel.KNOWN) {
				++outboundKnownCount;
				outboundPositiveTargets.add(outboundRating.getTargetAddress());
			} else if (ratingLevel == AccountRatingLevel.UNTRUSTED) {
				++outboundUntrustedCount;
			}
		}

		int mutualPositiveCount = 0;
		for (AccountRatingData inboundRating : inboundRatings) {
			AccountRatingLevel ratingLevel = inboundRating.getRatingLevel();

			if (ratingLevel == AccountRatingLevel.TRUSTED) {
				++inboundTrustedCount;
				if (outboundPositiveTargets.contains(inboundRating.getRaterAddress()))
					++mutualPositiveCount;
			} else if (ratingLevel == AccountRatingLevel.KNOWN) {
				++inboundKnownCount;
				if (outboundPositiveTargets.contains(inboundRating.getRaterAddress()))
					++mutualPositiveCount;
			} else if (ratingLevel == AccountRatingLevel.UNTRUSTED) {
				++inboundUntrustedCount;
			}
		}

		return new AccountTrustPreviewData(targetPublicKey, targetAddress, trustStatus,
				inboundTrustedCount, inboundKnownCount, inboundUntrustedCount,
				outboundTrustedCount, outboundKnownCount, outboundUntrustedCount,
				mutualPositiveCount);
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
