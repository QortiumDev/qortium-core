package org.qortium.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortium.api.ApiError;
import org.qortium.api.ApiErrors;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.Security;
import org.qortium.api.model.AccountRatingCooldownUpdateRequest;
import org.qortium.api.model.AccountTrustCategoryPoliciesUpdateRequest;
import org.qortium.api.model.AccountTrustManagerEnergyHopsUpdateRequest;
import org.qortium.api.model.AccountTrustPositiveMinBranchCountUpdateRequest;
import org.qortium.api.model.AccountTrustStartingEnergyUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinBranchCountUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinRaterCountUpdateRequest;
import org.qortium.api.model.AccountTrustSuspiciousMinRatingConfidenceUpdateRequest;
import org.qortium.api.model.BlockRewardUpdateRequest;
import org.qortium.api.model.ChainParameterEffectiveValue;
import org.qortium.api.model.ChainParameterMetadata;
import org.qortium.api.model.ChainParameterUpdateSummary;
import org.qortium.api.model.ChainParameterValidationMetadata;
import org.qortium.api.model.IntegerChainParameterUpdateRequest;
import org.qortium.api.model.MaxMapEntriesPerAtUpdateRequest;
import org.qortium.api.model.NameRegistrationUnitFeeUpdateRequest;
import org.qortium.api.model.RewardShareWeightsUpdateRequest;
import org.qortium.api.model.TrustStatusVoteWeightsUpdateRequest;
import org.qortium.api.model.UnitFeeUpdateRequest;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.block.AccountTrustCategoryPolicyCodec;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.data.account.AccountTrustCategoryPoliciesData;
import org.qortium.data.block.BlockData;
import org.qortium.data.blockchain.ChainParameterData;
import org.qortium.data.group.GroupApprovalData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.ChainParameterUpdateTransactionTransformer;
import org.qortium.utils.Base58;

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

	private void checkChainParameterBuilderAllowed() {
		Security.checkApiCallAllowed(request);

		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);
	}

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
	@Path("/effective-values")
	@Operation(
			summary = "List effective chain parameter values at a height",
			responses = {
					@ApiResponse(
							description = "effective chain parameter values",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(implementation = ChainParameterEffectiveValue.class))
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<ChainParameterEffectiveValue> getEffectiveParameterValues(@QueryParam("height") Integer height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int effectiveHeight = height == null ? repository.getBlockRepository().getBlockchainHeight() : height;
			return buildEffectiveParameterValues(repository, effectiveHeight);
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

	@GET
	@Path("/share-bin/min-accounts/{height}")
	@Operation(
			summary = "Fetch the effective minimum account count required to activate a reward share bin at a height",
			responses = {
					@ApiResponse(
							description = "minimum account count required to activate a reward share bin",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getMinAccountsToActivateShareBin(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getMinAccountsToActivateShareBin(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/at-map/max-entries/{height}")
	@Operation(summary = "Fetch the effective persistent-map entry limit per AT at a height")
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getMaxMapEntriesPerAt(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getMaxMapEntriesPerAt(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/reward-share-weights/{height}")
	@Operation(
			summary = "Fetch the effective reward share weights at a height",
			responses = {
					@ApiResponse(
							description = "10 integer weights for reward levels 1 through 10",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(type = "integer"))
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int[] getRewardShareWeights(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getRewardShareWeights(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-rating/cooldown/{height}")
	@Operation(
			summary = "Fetch the effective account rating change cooldown at a height",
			responses = {
					@ApiResponse(
							description = "account rating change cooldown in blocks",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountRatingCooldown(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/status-vote-weights/{height}")
	@Operation(
			summary = "Fetch the effective trust status vote-weight percentages at a height",
			responses = {
					@ApiResponse(
							description = "5 integer vote-weight percentages ordered as SUSPICIOUS, UNVERIFIED, BRONZE, SILVER, GOLD",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									array = @ArraySchema(schema = @Schema(type = "integer"))
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int[] getTrustStatusVoteWeights(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/starting-energy/{height}")
	@Operation(
			summary = "Fetch the effective account trust starting energy at a height",
			responses = {
					@ApiResponse(
							description = "account trust starting energy",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public long getAccountTrustStartingEnergy(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustStartingEnergy(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/manager-energy-hops/{height}")
	@Operation(
			summary = "Fetch the effective account trust manager energy hops at a height",
			responses = {
					@ApiResponse(
							description = "account trust manager energy hops",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountTrustManagerEnergyHops(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/positive-min-branch-count/{height}")
	@Operation(
			summary = "Fetch the effective positive trust branch count requirement at a height",
			responses = {
					@ApiResponse(
							description = "minimum independent positive trust branch count",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountTrustPositiveMinBranchCount(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/suspicious-min-rater-count/{height}")
	@Operation(
			summary = "Fetch the effective suspicious trust rater count requirement at a height",
			responses = {
					@ApiResponse(
							description = "minimum independent negative rater count",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountTrustSuspiciousMinRaterCount(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/suspicious-min-branch-count/{height}")
	@Operation(
			summary = "Fetch the effective suspicious trust branch count requirement at a height",
			responses = {
					@ApiResponse(
							description = "minimum independent negative trust branch count",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountTrustSuspiciousMinBranchCount(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/suspicious-min-rating-confidence/{height}")
	@Operation(
			summary = "Fetch the effective suspicious trust rating-confidence requirement at a height",
			responses = {
					@ApiResponse(
							description = "minimum negative rating confidence",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public int getAccountTrustSuspiciousMinRatingConfidence(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, height);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/account-trust/category-policies/{height}")
	@Operation(
			summary = "Fetch the effective account trust category policy table at a height",
			responses = {
					@ApiResponse(
							description = "account trust category thresholds and score caps",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = AccountTrustCategoryPoliciesData.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public AccountTrustCategoryPoliciesData getAccountTrustCategoryPolicies(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] value = BlockChain.getInstance().getAccountTrustCategoryPoliciesValue(repository, height);
			return AccountTrustCategoryPolicyCodec.decode(value);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/unit-fee/{height}")
	@Operation(
			summary = "Fetch the effective normal transaction unit fee at a height",
			responses = {
					@ApiResponse(
							description = "normal transaction unit fee amount",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public long getUnitFee(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getUnitFeeAtHeight(repository, height, getFallbackTimestampForHeight(repository, height));
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/name-registration-unit-fee/{height}")
	@Operation(
			summary = "Fetch the effective name-registration transaction unit fee at a height",
			responses = {
					@ApiResponse(
							description = "name-registration transaction unit fee amount",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "integer")
							)
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public long getNameRegistrationUnitFee(@PathParam("height") int height) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return BlockChain.getInstance().getNameRegistrationUnitFeeAtHeight(repository, height,
					getFallbackTimestampForHeight(repository, height));
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateBlockReward(BlockRewardUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildBlockRewardTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/share-bin/min-accounts/update")
	@Operation(
			summary = "Build raw, unsigned, MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = IntegerChainParameterUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateMinAccountsToActivateShareBin(IntegerChainParameterUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildIntegerTransactionData(
					updateRequest, ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/at-map/max-entries/update")
	@Operation(
			summary = "Build raw, unsigned, MAX_MAP_ENTRIES_PER_AT chain-parameter update transaction",
			requestBody = @RequestBody(required = true, content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(implementation = MaxMapEntriesPerAtUpdateRequest.class))))
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateMaxMapEntriesPerAt(MaxMapEntriesPerAtUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
					updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee,
					updateRequest.nonce, null);
			ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
					baseTransactionData, ChainParameter.MAX_MAP_ENTRIES_PER_AT.id, updateRequest.activationHeight,
					ChainParameter.MAX_MAP_ENTRIES_PER_AT.encodeIntValue(updateRequest.maxEntries));
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/reward-share-weights/update")
	@Operation(
			summary = "Build raw, unsigned, REWARD_SHARE_WEIGHTS chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = RewardShareWeightsUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateRewardShareWeights(RewardShareWeightsUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildRewardShareWeightsTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-rating/cooldown/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountRatingCooldownUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountRatingCooldown(AccountRatingCooldownUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildAccountRatingCooldownTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/status-vote-weights/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = TrustStatusVoteWeightsUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateTrustStatusVoteWeights(TrustStatusVoteWeightsUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildTrustStatusVoteWeightsTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/starting-energy/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_STARTING_ENERGY chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustStartingEnergyUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustStartingEnergy(AccountTrustStartingEnergyUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildAccountTrustStartingEnergyTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/manager-energy-hops/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_MANAGER_ENERGY_HOPS chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustManagerEnergyHopsUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustManagerEnergyHops(AccountTrustManagerEnergyHopsUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildAccountTrustManagerEnergyHopsTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/positive-min-branch-count/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustPositiveMinBranchCountUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustPositiveMinBranchCount(
			AccountTrustPositiveMinBranchCountUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustPositiveMinBranchCountTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/suspicious-min-rater-count/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustSuspiciousMinRaterCountUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustSuspiciousMinRaterCount(
			AccountTrustSuspiciousMinRaterCountUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinRaterCountTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/suspicious-min-branch-count/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustSuspiciousMinBranchCountUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustSuspiciousMinBranchCount(
			AccountTrustSuspiciousMinBranchCountUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinBranchCountTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/suspicious-min-rating-confidence/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustSuspiciousMinRatingConfidenceUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustSuspiciousMinRatingConfidence(
			AccountTrustSuspiciousMinRatingConfidenceUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustSuspiciousMinRatingConfidenceTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/account-trust/category-policies/update")
	@Operation(
			summary = "Build raw, unsigned, ACCOUNT_TRUST_CATEGORY_POLICIES chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = AccountTrustCategoryPoliciesUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateAccountTrustCategoryPolicies(AccountTrustCategoryPoliciesUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData =
					buildAccountTrustCategoryPoliciesTransactionData(repository, updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (IllegalArgumentException e) {
			throw TransactionsResource.createTransactionInvalidException(
					request, Transaction.ValidationResult.INVALID_VALUE_LENGTH);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/unit-fee/update")
	@Operation(
			summary = "Build raw, unsigned, UNIT_FEE chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = UnitFeeUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateUnitFee(UnitFeeUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildUnitFeeTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/name-registration-unit-fee/update")
	@Operation(
			summary = "Build raw, unsigned, NAME_REGISTRATION_UNIT_FEE chain-parameter update transaction",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = NameRegistrationUnitFeeUpdateRequest.class)
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
	@SecurityRequirement(name = "apiKey")
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String updateNameRegistrationUnitFee(NameRegistrationUnitFeeUpdateRequest updateRequest) {
		checkChainParameterBuilderAllowed();

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainParameterUpdateTransactionData transactionData = buildNameRegistrationUnitFeeTransactionData(updateRequest);
			return validateAndTransformUpdate(repository, transactionData);
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

	private static ChainParameterUpdateTransactionData buildIntegerTransactionData(
			IntegerChainParameterUpdateRequest updateRequest, ChainParameter parameter) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData, parameter.id,
				updateRequest.activationHeight, parameter.encodeIntValue(updateRequest.value));
	}

	private static ChainParameterUpdateTransactionData buildRewardShareWeightsTransactionData(
			RewardShareWeightsUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData, ChainParameter.REWARD_SHARE_WEIGHTS.id,
				updateRequest.activationHeight,
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(updateRequest.weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountRatingCooldownTransactionData(
			AccountRatingCooldownUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS.encodeIntValue(updateRequest.cooldownBlocks));
	}

	private static ChainParameterUpdateTransactionData buildTrustStatusVoteWeightsTransactionData(
			TrustStatusVoteWeightsUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(updateRequest.weights));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustStartingEnergyTransactionData(
			AccountTrustStartingEnergyUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(updateRequest.startingEnergy));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustManagerEnergyHopsTransactionData(
			AccountTrustManagerEnergyHopsUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(updateRequest.managerEnergyHops));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustPositiveMinBranchCountTransactionData(
			AccountTrustPositiveMinBranchCountUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(
						updateRequest.positiveMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRaterCountTransactionData(
			AccountTrustSuspiciousMinRaterCountUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(
						updateRequest.suspiciousMinRaterCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinBranchCountTransactionData(
			AccountTrustSuspiciousMinBranchCountUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(
						updateRequest.suspiciousMinBranchCount));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustSuspiciousMinRatingConfidenceTransactionData(
			AccountTrustSuspiciousMinRatingConfidenceUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, updateRequest.activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
						updateRequest.suspiciousMinRatingConfidence));
	}

	private static ChainParameterUpdateTransactionData buildAccountTrustCategoryPoliciesTransactionData(
			Repository repository, AccountTrustCategoryPoliciesUpdateRequest updateRequest) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);
		int effectiveSuspiciousMinRaterCount = BlockChain.getInstance()
				.getAccountTrustSuspiciousMinRaterCount(repository, updateRequest.activationHeight);
		byte[] value = AccountTrustCategoryPolicyCodec.encode(
				updateRequest.categoryPolicies, effectiveSuspiciousMinRaterCount);

		return new ChainParameterUpdateTransactionData(baseTransactionData,
				ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, updateRequest.activationHeight, value);
	}

	private static ChainParameterUpdateTransactionData buildUnitFeeTransactionData(UnitFeeUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData, ChainParameter.UNIT_FEE.id,
				updateRequest.activationHeight, ChainParameter.UNIT_FEE.encodeLongValue(updateRequest.unitFee));
	}

	private static ChainParameterUpdateTransactionData buildNameRegistrationUnitFeeTransactionData(
			NameRegistrationUnitFeeUpdateRequest updateRequest) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(updateRequest.timestamp,
				updateRequest.txGroupId, updateRequest.updaterPublicKey, updateRequest.fee, updateRequest.nonce, null);

		return new ChainParameterUpdateTransactionData(baseTransactionData, ChainParameter.NAME_REGISTRATION_UNIT_FEE.id,
				updateRequest.activationHeight,
				ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(updateRequest.nameRegistrationUnitFee));
	}

	private String validateAndTransformUpdate(Repository repository, ChainParameterUpdateTransactionData transactionData)
			throws DataException, TransformationException {
		Transaction transaction = Transaction.fromData(repository, transactionData);

		if (transactionData.getFee() == null)
			transactionData.setFee(transaction.calcRecommendedFee());

		Transaction.ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
		if (result != Transaction.ValidationResult.OK)
			throw TransactionsResource.createTransactionInvalidException(request, result);

		byte[] bytes = ChainParameterUpdateTransactionTransformer.toBytes(transactionData);
		return Base58.encode(bytes);
	}

	private static List<ChainParameterEffectiveValue> buildEffectiveParameterValues(Repository repository, int height)
			throws DataException {
		List<ChainParameterEffectiveValue> values = new ArrayList<>(ChainParameter.values().length);
		long fallbackTimestamp = getFallbackTimestampForHeight(repository, height);

		for (ChainParameter parameter : ChainParameter.values())
			values.add(buildEffectiveParameterValue(repository, parameter, height, fallbackTimestamp));

		return values;
	}

	private static ChainParameterEffectiveValue buildEffectiveParameterValue(Repository repository, ChainParameter parameter,
			int height, long fallbackTimestamp) throws DataException {
		ChainParameterEffectiveValue value = new ChainParameterEffectiveValue();
		value.id = parameter.id;
		value.name = parameter.name();
		value.valueType = parameter.getValueType();
		value.height = height;

		ChainParameterData activeParameter = repository.getChainParameterRepository()
				.getEffectiveParameter(parameter.id, height);
		if (activeParameter == null) {
			value.source = ChainParameterEffectiveValue.Source.CONFIG;
			populateCurrentValue(value, parameter, getConfiguredParameterValue(parameter, height, fallbackTimestamp));
		} else {
			value.source = ChainParameterEffectiveValue.Source.ON_CHAIN;
			value.signature = activeParameter.getSignature();
			value.activationHeight = activeParameter.getActivationHeight();
			populateCurrentValue(value, parameter, activeParameter.getValue());
		}

		ChainParameterData nextParameter = repository.getChainParameterRepository().getNextParameter(parameter.id, height);
		if (nextParameter != null) {
			value.nextSignature = nextParameter.getSignature();
			value.nextActivationHeight = nextParameter.getActivationHeight();
			populateNextValue(value, parameter, nextParameter.getValue());
		}

		return value;
	}

	private static byte[] getConfiguredParameterValue(ChainParameter parameter, int height, long fallbackTimestamp) {
		switch (parameter) {
			case BLOCK_REWARD:
				return parameter.encodeLongValue(BlockChain.getInstance().getRewardAtHeight(height));

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
				return parameter.encodeIntValue(BlockChain.getInstance().getMinAccountsToActivateShareBin());

			case REWARD_SHARE_WEIGHTS:
				return parameter.encodeIntArrayValue(BlockChain.getInstance().getRewardShareWeights());

			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountRatingChangeCooldownBlocks());

			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				return parameter.encodeIntArrayValue(BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents());

			case ACCOUNT_TRUST_STARTING_ENERGY:
				return parameter.encodeLongValue(BlockChain.getInstance().getAccountTrustStartingEnergy());

			case ACCOUNT_TRUST_MANAGER_ENERGY_HOPS:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountTrustManagerEnergyHops());

			case ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountTrustPositiveMinBranchCount());

			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount());

			case ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount());

			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE:
				return parameter.encodeIntValue(BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence());

			case ACCOUNT_TRUST_CATEGORY_POLICIES:
				return BlockChain.getInstance().getAccountTrustCategoryPoliciesValue();

			case MAX_MAP_ENTRIES_PER_AT:
				return parameter.encodeIntValue(BlockChain.DEFAULT_MAX_MAP_ENTRIES_PER_AT);

			case UNIT_FEE:
				return parameter.encodeLongValue(BlockChain.getInstance().getUnitFeeAtTimestamp(fallbackTimestamp));

			case NAME_REGISTRATION_UNIT_FEE:
				return parameter.encodeLongValue(BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(fallbackTimestamp));

			default:
				throw new IllegalStateException("Unsupported chain parameter: " + parameter);
		}
	}

	private static void populateCurrentValue(ChainParameterEffectiveValue effectiveValue, ChainParameter parameter,
			byte[] value) {
		effectiveValue.value = value;
		effectiveValue.amount = parameter.decodeAmountValue(value);
		effectiveValue.longValue = parameter.decodeLongParameterValue(value);
		effectiveValue.integerValue = parameter.decodeIntegerValue(value);
		effectiveValue.integerValues = parameter.decodeIntegerListValue(value);
		effectiveValue.accountTrustCategoryPolicies = parameter.decodeAccountTrustCategoryPoliciesValue(value);
		effectiveValue.displayValue = parameter.formatDisplayValue(value);
	}

	private static void populateNextValue(ChainParameterEffectiveValue effectiveValue, ChainParameter parameter,
			byte[] value) {
		effectiveValue.nextValue = value;
		effectiveValue.nextAmount = parameter.decodeAmountValue(value);
		effectiveValue.nextLongValue = parameter.decodeLongParameterValue(value);
		effectiveValue.nextIntegerValue = parameter.decodeIntegerValue(value);
		effectiveValue.nextIntegerValues = parameter.decodeIntegerListValue(value);
		effectiveValue.nextAccountTrustCategoryPolicies = parameter.decodeAccountTrustCategoryPoliciesValue(value);
		effectiveValue.nextDisplayValue = parameter.formatDisplayValue(value);
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
					parameter.getEffectivePath(),
					buildValidationMetadata(parameter)));

		return metadata;
	}

	private static ChainParameterValidationMetadata buildValidationMetadata(ChainParameter parameter) {
		return new ChainParameterValidationMetadata(
				parameter.getMinimumLongValue(),
				parameter.getMinimumIntegerValue(),
				parameter.getMaximumIntegerValue(),
				parameter.getIntegerListLength(),
				parameter.getMinimumIntegerListValue(),
				parameter.getMaximumIntegerListValue(),
				parameter.getIntegerListLabels(),
				parameter.requiresPositiveTotal(),
				parameter.requiresPositiveFirstValue(),
				parameter.requiresAnyPositiveValue());
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
			summary.longValue = parameter.decodeLongParameterValue(transactionData.getValue());
			summary.integerValue = parameter.decodeIntegerValue(transactionData.getValue());
			summary.integerValues = parameter.decodeIntegerListValue(transactionData.getValue());
			summary.accountTrustCategoryPolicies = parameter.decodeAccountTrustCategoryPoliciesValue(
					transactionData.getValue());
			summary.displayValue = parameter.formatDisplayValue(transactionData.getValue());
		}

		GroupData groupData = repository.getGroupRepository().fromGroupId(transactionData.getTxGroupId());
		if (groupData != null)
			summary.approvalThreshold = groupData.getApprovalThreshold();

		GroupApprovalData approvalData = repository.getTransactionRepository().getApprovalData(transactionData.getSignature(), currentHeight);
		if (approvalData != null) {
			summary.approvalCount = approvalData.approvingAdmins.size();
			summary.rejectionCount = approvalData.rejectingAdmins.size();
		}

		summary.approvalAuthorityCount = Group.countApprovalAuthorities(repository, transactionData.getTxGroupId(), transactionData.getType(), currentHeight);
		summary.effectiveNow = isEffectiveNow(repository, transactionData, currentHeight);

		return summary;
	}

	private static long getFallbackTimestampForHeight(Repository repository, int height) throws DataException {
		BlockData blockData = repository.getBlockRepository().fromHeight(height);
		return blockData == null ? System.currentTimeMillis() : blockData.getTimestamp();
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
