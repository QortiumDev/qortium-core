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
import org.qortal.api.model.BlockRewardUpdateRequest;
import org.qortal.api.model.ChainParameterMetadata;
import org.qortal.block.BlockChain;
import org.qortal.block.ChainParameter;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.data.transaction.BaseTransactionData;
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
import java.util.Collections;
import java.util.List;

@Path("/chain-parameters")
@Tag(name = "Chain Parameters")
public class ChainParametersResource {

	@Context
	HttpServletRequest request;

	private static final List<ChainParameterMetadata> PARAMETERS = Collections.singletonList(
			new ChainParameterMetadata(
					ChainParameter.BLOCK_REWARD.id,
					ChainParameter.BLOCK_REWARD.name(),
					"AMOUNT",
					ChainParameter.BLOCK_REWARD.valueLength,
					"Height-based block reward amount, expressed as a normal decimal amount in the public builder and stored on chain as an 8-byte signed long.",
					"/chain-parameters/block-reward/update",
					"/chain-parameters/block-reward/{height}"));

	@GET
	@Operation(
			summary = "List supported on-chain chain parameters",
			responses = {
					@ApiResponse(
							description = "supported chain parameter metadata",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = ChainParameterMetadata.class)
							)
					)
			}
	)
	public List<ChainParameterMetadata> getChainParameters() {
		return PARAMETERS;
	}

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
	@Path("/block-reward/update")
	@Operation(
			summary = "Build raw, unsigned, BLOCK_REWARD chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = BlockRewardUpdateRequest.class)
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
	public String updateBlockReward(BlockRewardUpdateRequest updateRequest) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildBlockRewardTransactionData(updateRequest);
			Transaction transaction = Transaction.fromData(repository, transactionData);

			if (updateRequest.fee == null)
				transactionData.setFee(transaction.calcRecommendedFee());

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

	private static ChainParameterUpdateTransactionData buildBlockRewardTransactionData(BlockRewardUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData, ChainParameter.BLOCK_REWARD.id,
				updateRequest.activationHeight, ChainParameter.BLOCK_REWARD.encodeLongValue(updateRequest.reward));
	}
}
