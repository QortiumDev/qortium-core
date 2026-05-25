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
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.rating.ResourceRatingSummaryData;
import org.qortal.data.transaction.RateResourceTransactionData;
import org.qortal.rating.ResourceRating;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.RateResourceTransactionTransformer;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;

@Path("/resource-ratings")
@Tag(name = "Resource Ratings")
public class ResourceRatingsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
			summary = "List resource rating summaries",
			responses = {
					@ApiResponse(
							description = "resource rating summaries",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = ResourceRatingSummaryData.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<ResourceRatingSummaryData> getResourceRatings(
			@Parameter(description = "Optional QDN service filter") @QueryParam("service") String serviceName,
			@Parameter(description = "Optional QDN resource name filter") @QueryParam("name") String name,
			@Parameter(description = "Optional QDN resource identifier filter") @QueryParam("identifier") String identifier,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Service service = parseOptionalService(serviceName);
			String nameKey = name == null ? null : ResourceRating.toNameKey(name);
			String identifierKey = identifier == null ? null : ResourceRating.toIdentifierKey(identifier);

			if (name != null && (!ResourceRating.isNameValid(name) || !ResourceRating.isNormalized(name)))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			if (identifier != null && (!ResourceRating.isIdentifierValid(identifier) || !ResourceRating.isNormalized(identifier)))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			return repository.getResourceRatingRepository().getRatingSummaries(service, nameKey, identifierKey, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/summary")
	@Operation(
			summary = "Get one resource rating summary",
			responses = {
					@ApiResponse(
							description = "resource rating summary",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = ResourceRatingSummaryData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public ResourceRatingSummaryData getResourceRatingSummary(
			@Parameter(description = "QDN service") @QueryParam("service") String serviceName,
			@Parameter(description = "QDN resource name") @QueryParam("name") String name,
			@Parameter(description = "QDN resource identifier") @QueryParam("identifier") String identifier) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ResourceRating.Target target = requireExistingTarget(repository, serviceName, name, identifier);
			ResourceRatingSummaryData summary = repository.getResourceRatingRepository()
					.getRatingSummary(target.service, target.nameKey, target.displayName, target.identifierKey);

			if (summary.getRatingCount() == 0)
				return ResourceRatingSummaryData.empty(target.service, target.displayName, target.identifierKey);

			return summary;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/rate")
	@Operation(
			summary = "Build raw, unsigned, RATE_RESOURCE transaction",
			requestBody = @RequestBody(
					required = true,
					description = "Rate an existing public QDN resource. rating 0 removes the active rating; ratings 1 through 10 record an active rating.",
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = RateResourceTransactionData.class)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, RATE_RESOURCE transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "string")
							)
					)
			}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String rateResource(RateResourceTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			Transaction.ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != Transaction.ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RateResourceTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private Service parseOptionalService(String serviceName) {
		if (serviceName == null)
			return null;

		Service service = parseService(serviceName);
		if (!ResourceRating.isRateableService(service))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return service;
	}

	private ResourceRating.Target requireExistingTarget(Repository repository, String serviceName, String name, String identifier) throws DataException {
		Service service = parseService(serviceName);
		if (!ResourceRating.isRateableService(service))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (!ResourceRating.isNameValid(name) || !ResourceRating.isNormalized(name))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (!ResourceRating.isIdentifierValid(identifier) || !ResourceRating.isNormalized(identifier))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		ResourceRating.Target target = ResourceRating.resolveTarget(repository, service, name, identifier);
		if (target == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return target;
	}

	private Service parseService(String serviceName) {
		if (serviceName == null || serviceName.trim().isEmpty())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try {
			return Service.valueOf(serviceName.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

}
