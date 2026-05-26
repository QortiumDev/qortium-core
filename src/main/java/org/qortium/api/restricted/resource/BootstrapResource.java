package org.qortium.api.restricted.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.ApiError;
import org.qortium.api.ApiErrors;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.Security;
import org.qortium.repository.Bootstrap;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.concurrent.atomic.AtomicBoolean;


@Path("/bootstrap")
@Tag(name = "Bootstrap")
public class BootstrapResource {

	private static final Logger LOGGER = LogManager.getLogger(BootstrapResource.class);
	private static final AtomicBoolean BOOTSTRAP_OPERATION_IN_PROGRESS = new AtomicBoolean(false);

	@Context
	HttpServletRequest request;

	@POST
	@Path("/create")
	@Operation(
		summary = "Create bootstrap",
		description = "Builds a bootstrap file for distribution",
		responses = {
			@ApiResponse(
				description = "path to file on success, an exception on failure",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.OPERATION_IN_PROGRESS, ApiError.REPOSITORY_ISSUE})
	public String createBootstrap(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		if (!tryAcquireBootstrapOperation())
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS,
					"Bootstrap operation is already running");

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {

				Bootstrap bootstrap = new Bootstrap(repository);
				try {
					bootstrap.checkRepositoryState();
				} catch (DataException e) {
					LOGGER.info("Not ready to create bootstrap: {}", e.getMessage());
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
				}
				bootstrap.validateBlockchain();
				return bootstrap.create();

			} catch (Exception e) {
				LOGGER.info("Unable to create bootstrap", e);
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
			}
		} finally {
			releaseBootstrapOperation();
		}
	}

	@GET
	@Path("/validate")
	@Operation(
			summary = "Validate blockchain",
			description = "Useful to check database integrity prior to creating or after installing a bootstrap. " +
					"This process is intensive and can take over an hour to run.",
			responses = {
					@ApiResponse(
							description = "true if valid, false if invalid",
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.OPERATION_IN_PROGRESS, ApiError.REPOSITORY_ISSUE})
	public boolean validateBootstrap(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);

		if (!tryAcquireBootstrapOperation())
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS,
					"Bootstrap operation is already running");

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {

				Bootstrap bootstrap = new Bootstrap(repository);
				return bootstrap.validateCompleteBlockchain();

			} catch (DataException e) {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE);
			}
		} finally {
			releaseBootstrapOperation();
		}
	}

	static boolean tryAcquireBootstrapOperation() {
		return BOOTSTRAP_OPERATION_IN_PROGRESS.compareAndSet(false, true);
	}

	static void releaseBootstrapOperation() {
		BOOTSTRAP_OPERATION_IN_PROGRESS.set(false);
	}

	static boolean isBootstrapOperationInProgress() {
		return BOOTSTRAP_OPERATION_IN_PROGRESS.get();
	}
}
