package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.block.BlockChain;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChainParameterUpdateTransactionTransformer;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

@Path("/chain-parameters")
@Tag(name = "Chain Parameters")
public class ChainParametersResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/effective/{parameterId}")
	@Operation(
			summary = "Fetch the approved on-chain parameter update active at a height",
			responses = {
					@ApiResponse(
							description = "approved on-chain parameter update, if one exists",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = ChainParameterData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public ChainParameterData getEffectiveParameter(@PathParam("parameterId") int parameterId,
			@QueryParam("height") Integer height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int effectiveHeight = height == null ? repository.getBlockRepository().getBlockchainHeight() : height;
			return repository.getChainParameterRepository().getEffectiveParameter(parameterId, effectiveHeight);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/block-reward/{height}")
	@Operation(
			summary = "Fetch the effective block reward at a height",
			responses = {
					@ApiResponse(
							description = "block reward amount",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public long getBlockReward(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getRewardAtHeight(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/update")
	@Operation(
			summary = "Build raw, unsigned, CHAIN_PARAMETER_UPDATE transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = ChainParameterUpdateTransactionData.class)
					)
			),
			responses = {
					@ApiResponse(
							description = "raw, unsigned, CHAIN_PARAMETER_UPDATE transaction encoded in Base58",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "string")
							)
					)
			}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateChainParameter(ChainParameterUpdateTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			Transaction.ValidationResult result = transaction.isValidUnconfirmed();
			if (result != Transaction.ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ChainParameterUpdateTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}
}
