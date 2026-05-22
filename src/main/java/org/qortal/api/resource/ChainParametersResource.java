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
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.BlockRewardUpdateRequest;
import org.qortal.api.model.ChainParameterMetadata;
import org.qortal.api.model.ChainParameterUpdateSummary;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.block.BlockChain;
import org.qortal.block.ChainParameter;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.data.group.GroupApprovalData;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Path("/chain-parameters")
@Tag(name = "Chain Parameters")
public class ChainParametersResource {

	@Context
	HttpServletRequest request;

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
		return buildParameterMetadata();
	}

	@GET
	@Path("/updates")
	@Operation(
			summary = "List on-chain chain-parameter update proposals",
			responses = {
					@ApiResponse(
							description = "chain-parameter update proposal summaries",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = ChainParameterUpdateSummary.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ChainParameterUpdateSummary> getChainParameterUpdates(
			@QueryParam("parameterId") Integer parameterId,
			@QueryParam("approvalStatus") ApprovalStatus approvalStatus,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("activationHeightFrom") Integer activationHeightFrom,
			@QueryParam("activationHeightTo") Integer activationHeightTo,
			@Parameter(description = "whether to include confirmed, unconfirmed or both; defaults to CONFIRMED")
			@QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		if (confirmationStatus == null)
			confirmationStatus = ConfirmationStatus.CONFIRMED;

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ChainParameterUpdateTransactionData> transactionDataList = repository.getTransactionRepository()
					.getChainParameterUpdates(parameterId, approvalStatus, txGroupId, activationHeightFrom,
							activationHeightTo, confirmationStatus, limit, offset, reverse);

			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			List<ChainParameterUpdateSummary> summaries = new ArrayList<>(transactionDataList.size());
			for (ChainParameterUpdateTransactionData transactionData : transactionDataList)
				summaries.add(buildUpdateSummary(repository, transactionData, currentHeight));

			return summaries;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
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

	private static List<ChainParameterMetadata> buildParameterMetadata() {
		List<ChainParameterMetadata> metadata = new ArrayList<>(ChainParameter.values().length);

		for (ChainParameter parameter : ChainParameter.values())
			metadata.add(new ChainParameterMetadata(
					parameter.id,
					parameter.name(),
					parameter.getValueType(),
					parameter.valueLength,
					BlockChain.getInstance().getChainParameterUpdateMinActivationDelay(),
					parameter.getDescription(),
					parameter.getBuilderPath(),
					parameter.getEffectivePath()));

		return metadata;
	}

	private static ChainParameterUpdateSummary buildUpdateSummary(Repository repository,
			ChainParameterUpdateTransactionData transactionData, int currentHeight) throws DataException {
		ChainParameterUpdateSummary summary = new ChainParameterUpdateSummary();

		summary.signature = transactionData.getSignature();
		summary.timestamp = transactionData.getTimestamp();
		summary.blockHeight = transactionData.getBlockHeight();
		summary.approvalHeight = transactionData.getApprovalHeight();
		summary.txGroupId = transactionData.getTxGroupId();
		summary.parameterId = transactionData.getParameterId();
		summary.activationHeight = transactionData.getActivationHeight();
		summary.value = transactionData.getValue();
		summary.approvalStatus = transactionData.getApprovalStatus();

		ChainParameter parameter = ChainParameter.valueOf(transactionData.getParameterId());
		if (parameter != null) {
			summary.parameterName = parameter.name();
			summary.valueType = parameter.getValueType();
			summary.amount = parameter.decodeAmountValue(transactionData.getValue());
			summary.displayValue = parameter.formatDisplayValue(transactionData.getValue());
		}

		GroupData groupData = repository.getGroupRepository().fromGroupId(transactionData.getTxGroupId());
		if (groupData != null)
			summary.approvalThreshold = groupData.getApprovalThreshold();

		GroupApprovalData approvalData = repository.getTransactionRepository().getApprovalData(transactionData.getSignature());
		if (approvalData != null) {
			summary.approvalCount = approvalData.approvingAdmins.size();
			summary.rejectionCount = approvalData.rejectingAdmins.size();
		}

		summary.approvalAuthorityCount = Group.countApprovalAuthorities(repository, transactionData.getTxGroupId());
		summary.effectiveNow = isEffectiveNow(repository, transactionData, currentHeight);

		return summary;
	}

	private static boolean isEffectiveNow(Repository repository, ChainParameterUpdateTransactionData transactionData,
			int currentHeight) throws DataException {
		if (transactionData.getApprovalStatus() != ApprovalStatus.APPROVED)
			return false;

		ChainParameterData effectiveParameterData = repository.getChainParameterRepository()
				.getEffectiveParameter(transactionData.getParameterId(), currentHeight);

		return effectiveParameterData != null
				&& Arrays.equals(effectiveParameterData.getSignature(), transactionData.getSignature());
	}
}
