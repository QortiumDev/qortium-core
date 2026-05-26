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
import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.ApiErrors;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.CrossChainTradeFilters;
import org.qortium.api.Security;
import org.qortium.api.model.crosschain.TradeBotLockLocalRequest;
import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.api.model.crosschain.TradeBotRespondRequest;
import org.qortium.api.model.crosschain.TradeBotRespondRequests;
import org.qortium.asset.Asset;
import org.qortium.data.asset.AssetData;
import org.qortium.controller.Controller;
import org.qortium.controller.tradebot.AcctTradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv5TradeBot;
import org.qortium.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortium.controller.tradebot.TradeBot;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyACCTv4;
import org.qortium.crosschain.BitcoinyACCTv5;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.ForeignBlockchain;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.TradeDirection;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.transaction.Transaction;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;
import org.qortium.utils.Amounts;
import org.qortium.utils.NTP;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/crosschain/tradebot")
@Tag(name = "Cross-Chain (Trade-Bot)")
public class CrossChainTradeBotResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "List current trade-bot states",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TradeBotData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<TradeBotData> getTradeBotStates(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(
					description = "Limit to specific blockchain",
					example = "LITECOIN",
					schema = @Schema(type = "string")
				) @QueryParam("foreignBlockchain") String foreignBlockchain,
			@Parameter(
					description = "Limit to foreign/foreign trade-bot entries where this blockchain is being offered",
					example = "BITCOIN",
					schema = @Schema(type = "string")
			) @QueryParam("offeredForeignBlockchain") String offeredForeignBlockchain,
			@Parameter(
					description = "Limit to foreign/foreign trade-bot entries where this blockchain is being requested",
					example = "LITECOIN",
					schema = @Schema(type = "string")
			) @QueryParam("requestedForeignBlockchain") String requestedForeignBlockchain) {
		Security.checkApiCallAllowed(request);
		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = resolveForeignBlockchainFilter(foreignBlockchain);
		ForeignBlockchainRegistry.Entry offeredForeignBlockchainEntry = resolveForeignBlockchainFilter(offeredForeignBlockchain);
		ForeignBlockchainRegistry.Entry requestedForeignBlockchainEntry = resolveForeignBlockchainFilter(requestedForeignBlockchain);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

			if (foreignBlockchainEntry == null && offeredForeignBlockchainEntry == null && requestedForeignBlockchainEntry == null)
				return allTradeBotData;

			return allTradeBotData.stream()
					.filter(tradeBotData -> CrossChainTradeFilters.matchesTradeBotData(tradeBotData,
							foreignBlockchainEntry, offeredForeignBlockchainEntry, requestedForeignBlockchainEntry))
					.collect(Collectors.toList());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/create")
	@Operation(
		summary = "Create a trade offer (trade-bot entry)",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotCreateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.INVALID_CRITERIA, ApiError.INSUFFICIENT_BALANCE, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.BLOCKCHAIN_NEEDS_SYNC, ApiError.REPOSITORY_ISSUE, ApiError.ORDER_SIZE_TOO_SMALL})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotCreator(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotCreateRequest tradeBotCreateRequest) {
		Security.checkApiCallAllowed(request);
		validateCreatorPublicKey(tradeBotCreateRequest);

		TradeDirection tradeDirection = tradeBotCreateRequest.getTradeDirection();
		if (tradeDirection == TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
			return tradeBotCreatorForeignForeign(tradeBotCreateRequest);

		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = tradeBotCreateRequest.resolveForeignBlockchain();
		if (foreignBlockchainEntry == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		ForeignBlockchain foreignBlockchain = foreignBlockchainEntry.getInstance();

		if (tradeDirection == TradeDirection.SELL_LOCAL && !foreignBlockchain.isValidAddress(tradeBotCreateRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeDirection == TradeDirection.SELL_FOREIGN && !Crypto.isValidAddress(tradeBotCreateRequest.receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeDirection == TradeDirection.SELL_FOREIGN && tradeBotCreateRequest.tradeTimeout < 120)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeDirection != TradeDirection.SELL_FOREIGN && tradeBotCreateRequest.tradeTimeout < 60)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeBotCreateRequest.foreignAmount == null || tradeBotCreateRequest.foreignAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeBotCreateRequest.foreignAmount < foreignBlockchain.getMinimumOrderAmount())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeBotCreateRequest.localAmount <= 0 || tradeBotCreateRequest.fundingLocalAmount < 0 || tradeBotCreateRequest.nativeFeeReserve < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeDirection == TradeDirection.SELL_LOCAL && tradeBotCreateRequest.fundingLocalAmount < tradeBotCreateRequest.localAmount)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		long minFillLocalAmount = tradeBotCreateRequest.minFillLocalAmount != null ? tradeBotCreateRequest.minFillLocalAmount : tradeBotCreateRequest.localAmount;
		long maxFillLocalAmount = tradeBotCreateRequest.maxFillLocalAmount != null ? tradeBotCreateRequest.maxFillLocalAmount : tradeBotCreateRequest.localAmount;
		if (minFillLocalAmount <= 0 || maxFillLocalAmount < minFillLocalAmount || maxFillLocalAmount > tradeBotCreateRequest.localAmount)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeDirection == TradeDirection.SELL_FOREIGN) {
			Bitcoiny bitcoiny = foreignBlockchainEntry.getBitcoinyInstance();
			if (bitcoiny == null || tradeBotCreateRequest.foreignKey == null || !bitcoiny.isValidWalletKey(tradeBotCreateRequest.foreignKey))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			if (tradeBotCreateRequest.fundingLocalAmount != 0 || tradeBotCreateRequest.nativeFeeReserve <= 0)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

			if ((tradeBotCreateRequest.minFillLocalAmount != null && tradeBotCreateRequest.minFillLocalAmount != tradeBotCreateRequest.localAmount)
					|| (tradeBotCreateRequest.maxFillLocalAmount != null && tradeBotCreateRequest.maxFillLocalAmount != tradeBotCreateRequest.localAmount))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Do some simple checking first
			Account creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);
			AssetData localAssetData = repository.getAssetRepository().fromAssetId(tradeBotCreateRequest.localAssetId);

			if (localAssetData == null || localAssetData.isUnspendable())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (!localAssetData.isDivisible()
					&& (tradeBotCreateRequest.localAmount % Amounts.MULTIPLIER != 0
					|| tradeBotCreateRequest.fundingLocalAmount % Amounts.MULTIPLIER != 0
					|| minFillLocalAmount % Amounts.MULTIPLIER != 0
					|| maxFillLocalAmount % Amounts.MULTIPLIER != 0))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			if (tradeDirection == TradeDirection.SELL_LOCAL && creator.getConfirmedBalance(tradeBotCreateRequest.localAssetId) < tradeBotCreateRequest.fundingLocalAmount)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INSUFFICIENT_BALANCE);

			if (tradeBotCreateRequest.nativeFeeReserve > 0 && creator.getConfirmedBalance(Asset.NATIVE) < tradeBotCreateRequest.nativeFeeReserve)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INSUFFICIENT_BALANCE);

			byte[] unsignedBytes = TradeBot.getInstance().createTrade(repository, tradeBotCreateRequest);
			if (unsignedBytes == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			return Base58.encode(unsignedBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	private String tradeBotCreatorForeignForeign(TradeBotCreateRequest tradeBotCreateRequest) {
		validateForeignForeignCreateRequest(tradeBotCreateRequest);

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Account creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);

			if (tradeBotCreateRequest.nativeFeeReserve > 0 && creator.getConfirmedBalance(Asset.NATIVE) < tradeBotCreateRequest.nativeFeeReserve)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INSUFFICIENT_BALANCE);

			byte[] unsignedBytes = TradeBot.getInstance().createTrade(repository, tradeBotCreateRequest);
			if (unsignedBytes == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			return Base58.encode(unsignedBytes);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/respond")
	@Operation(
		summary = "Respond to a trade offer. NOTE: WILL SPEND FUNDS!)",
		description = "Start a new trade-bot entry to respond to chosen trade offer.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotRespondRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.BLOCKCHAIN_NEEDS_SYNC, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotResponder(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotRespondRequest tradeBotRespondRequest) {
		Security.checkApiCallAllowed(request);

		return createTradeBotResponse(tradeBotRespondRequest);
	}

	@POST
	@Path("/locklocal")
	@Operation(
		summary = "Build the local asset lock transaction for a reverse trade",
		description = "For SELL_FOREIGN offers, this follows the reservation step after the maker's foreign HTLC has been funded.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TradeBotLockLocalRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.BLOCKCHAIN_NEEDS_SYNC, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotLockLocal(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotLockLocalRequest tradeBotLockLocalRequest) {
		Security.checkApiCallAllowed(request);

		if (tradeBotLockLocalRequest.atAddress == null || !Crypto.isValidAtAddress(tradeBotLockLocalRequest.atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (tradeBotLockLocalRequest.responderPublicKey == null || tradeBotLockLocalRequest.responderPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, tradeBotLockLocalRequest.atAddress);

			ACCT acct = TradeBot.getInstance().getAcctUsingAtData(atData);
			if (!(acct instanceof BitcoinyACCTv5))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
			if (crossChainTradeData == null || crossChainTradeData.tradeDirection != TradeDirection.SELL_FOREIGN)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			if (hasPendingMessageToAt(repository, tradeBotLockLocalRequest.atAddress))
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Trade has an existing request pending.");

			byte[] unsignedBytes = BitcoinyACCTv5TradeBot.getInstance().buildLocalLockTransaction(repository, atData, crossChainTradeData,
					tradeBotLockLocalRequest.responderPublicKey);
			return Base58.encode(unsignedBytes);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		}
	}

	@POST
	@Path("/respondmultiple")
	@Operation(
			summary = "Respond to multiple trade offers. NOTE: WILL SPEND FUNDS!)",
			description = "Start a new trade-bot entry to respond to chosen trade offers. Pirate Chain is not supported and will throw an invalid criteria error.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = TradeBotRespondRequests.class
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.BLOCKCHAIN_NEEDS_SYNC, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotResponderMultiple(@HeaderParam(Security.API_KEY_HEADER) String apiKey, TradeBotRespondRequests tradeBotRespondRequest) {
		Security.checkApiCallAllowed(request);

		return createTradeBotResponseMultiple(tradeBotRespondRequest);
	}

	private ForeignBlockchainRegistry.Entry resolveForeignBlockchainFilter(String foreignBlockchain) {
		if (foreignBlockchain == null)
			return null;

		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = ForeignBlockchainRegistry.fromString(foreignBlockchain);
		if (foreignBlockchainEntry == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return foreignBlockchainEntry;
	}

	private boolean hasPendingMessageToAt(Repository repository, String atAddress) throws DataException {
		List<Transaction.TransactionType> txTypes = List.of(Transaction.TransactionType.MESSAGE);
		List<TransactionData> unconfirmed = repository.getTransactionRepository().getUnconfirmedTransactions(txTypes, null, 0, 0, false);
		for (TransactionData transactionData : unconfirmed) {
			MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;
			if (Objects.equals(messageTransactionData.getRecipient(), atAddress))
				return true;
		}

		return false;
	}

	private void validateForeignForeignCreateRequest(TradeBotCreateRequest tradeBotCreateRequest) {
		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = tradeBotCreateRequest.resolveOfferedForeignBlockchain();
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = tradeBotCreateRequest.resolveRequestedForeignBlockchain();
		if (!BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (offeredForeignBlockchain.name().equals(requestedForeignBlockchain.name()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Bitcoiny offeredBitcoiny = offeredForeignBlockchain.getBitcoinyInstance();
		Bitcoiny requestedBitcoiny = requestedForeignBlockchain.getBitcoinyInstance();
		if (offeredBitcoiny == null || requestedBitcoiny == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try {
			BitcoinyForeignForeignTradeBot.validateForeignForeignHtlcAmounts(offeredBitcoiny,
					tradeBotCreateRequest.offeredForeignAmount, requestedBitcoiny, tradeBotCreateRequest.requestedForeignAmount);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);
		}

		if (tradeBotCreateRequest.localAmount != 0 || tradeBotCreateRequest.fundingLocalAmount != 0
				|| tradeBotCreateRequest.nativeFeeReserve < 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_SIZE_TOO_SMALL);

		if (tradeBotCreateRequest.tradeTimeout < BitcoinyForeignForeignTradeBot.MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeBotCreateRequest.offeredForeignKey == null || !offeredBitcoiny.isValidWalletKey(tradeBotCreateRequest.offeredForeignKey))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (!isValidP2pkhAddress(requestedBitcoiny, tradeBotCreateRequest.requestedForeignReceivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
	}

	private void validateCreatorPublicKey(TradeBotCreateRequest tradeBotCreateRequest) {
		if (tradeBotCreateRequest == null || tradeBotCreateRequest.creatorPublicKey == null
				|| tradeBotCreateRequest.creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);
	}

	private void validateForeignForeignResponseRequest(CrossChainTradeData tradeData, TradeBotRespondRequest tradeBotRespondRequest) {
		if (tradeBotRespondRequest.fillLocalAmount != null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = ForeignBlockchainRegistry.fromString(tradeData.offeredForeignBlockchain);
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = ForeignBlockchainRegistry.fromString(tradeData.requestedForeignBlockchain);
		if (!BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain)
				|| offeredForeignBlockchain.name().equals(requestedForeignBlockchain.name()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Bitcoiny offeredBitcoiny = offeredForeignBlockchain.getBitcoinyInstance();
		Bitcoiny requestedBitcoiny = requestedForeignBlockchain.getBitcoinyInstance();
		if (offeredBitcoiny == null || requestedBitcoiny == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (tradeBotRespondRequest.requestedForeignKey == null || !requestedBitcoiny.isValidWalletKey(tradeBotRespondRequest.requestedForeignKey))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (!isValidP2pkhAddress(offeredBitcoiny, tradeBotRespondRequest.offeredForeignReceivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
	}

	private static boolean isValidP2pkhAddress(Bitcoiny bitcoiny, String address) {
		try {
			return BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), address).isP2PKH();
		} catch (IllegalArgumentException | NullPointerException e) {
			return false;
		}
	}

	private String createTradeBotResponse(TradeBotRespondRequest tradeBotRespondRequest) {
		final String atAddress = tradeBotRespondRequest.atAddress;

		if (atAddress == null || !Crypto.isValidAtAddress(atAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
		if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

		// Extract data from cross-chain trading AT
		try (final Repository repository = RepositoryManager.getRepository()) {
			ATData atData = fetchAtDataWithChecking(repository, atAddress);

			// TradeBot uses AT's code hash to map to ACCT
			ACCT acct = TradeBot.getInstance().getAcctUsingAtData(atData);
			if (acct == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atData);
			if (crossChainTradeData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			if (crossChainTradeData.tradeDirection == TradeDirection.SELL_LOCAL) {
				Bitcoiny bitcoiny = ForeignBlockchainRegistry.getBitcoinyInstance(crossChainTradeData.foreignBlockchain);
				if (bitcoiny == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (tradeBotRespondRequest.foreignKey == null || !bitcoiny.isValidWalletKey(tradeBotRespondRequest.foreignKey))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

				if (tradeBotRespondRequest.receivingAddress == null || !Crypto.isValidAddress(tradeBotRespondRequest.receivingAddress))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
			} else if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN) {
				Bitcoiny bitcoiny = ForeignBlockchainRegistry.getBitcoinyInstance(crossChainTradeData.foreignBlockchain);
				if (bitcoiny == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (!(acct instanceof BitcoinyACCTv5))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (tradeBotRespondRequest.responderPublicKey == null || tradeBotRespondRequest.responderPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

				if (tradeBotRespondRequest.receivingAddress == null || !bitcoiny.isValidAddress(tradeBotRespondRequest.receivingAddress))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
			} else if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN_FOR_FOREIGN) {
				if (!(acct instanceof BitcoinyForeignForeignACCTv1))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				validateForeignForeignResponseRequest(crossChainTradeData, tradeBotRespondRequest);
			} else {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}

			if (!crossChainTradeData.isFillableOffer())
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

			// Check if there is a buy or a cancel request in progress for this trade
			if (hasPendingMessageToAt(repository, atAddress))
				// There is a pending request for this trade, so block this buy attempt to reduce the risk of refunds
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Trade has an existing buy request or is pending cancellation.");

			if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN) {
				byte[] unsignedBytes = BitcoinyACCTv5TradeBot.getInstance().startResponse(repository, atData, crossChainTradeData,
						tradeBotRespondRequest.responderPublicKey, tradeBotRespondRequest.receivingAddress);
				return Base58.encode(unsignedBytes);
			}

			AcctTradeBot.ResponseResult result;
			if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
				result = TradeBot.getInstance().startResponse(repository, atData, acct, crossChainTradeData,
						tradeBotRespondRequest.requestedForeignKey, tradeBotRespondRequest.offeredForeignReceivingAddress, null);
			else
				result = TradeBot.getInstance().startResponse(repository, atData, acct, crossChainTradeData,
						tradeBotRespondRequest.foreignKey, tradeBotRespondRequest.receivingAddress, tradeBotRespondRequest.fillLocalAmount);

			switch (result) {
				case OK:
					return "true";

				case BALANCE_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

				case NETWORK_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

				case INVALID_CRITERIA:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				case TRADE_ALREADY_EXISTS:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				default:
					return "false";
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	private String createTradeBotResponseMultiple(TradeBotRespondRequests respondRequests) {
		try (final Repository repository = RepositoryManager.getRepository()) {

			if (respondRequests.foreignKey == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

			List<CrossChainTradeData> crossChainTradeDataList = new ArrayList<>(respondRequests.addresses.size());
			Optional<ACCT> acct = Optional.empty();
			Optional<ForeignBlockchainRegistry.Entry> foreignBlockchain = Optional.empty();
			Optional<Long> localAssetId = Optional.empty();

			for(String atAddress : respondRequests.addresses ) {

				if (atAddress == null || !Crypto.isValidAtAddress(atAddress))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

				if (respondRequests.receivingAddress == null || !Crypto.isValidAddress(respondRequests.receivingAddress))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

				final Long minLatestBlockTimestamp = NTP.getTime() - (60 * 60 * 1000L);
				if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.BLOCKCHAIN_NEEDS_SYNC);

				// Extract data from cross-chain trading AT
				ATData atData = fetchAtDataWithChecking(repository, atAddress);

				// TradeBot uses AT's code hash to map to ACCT
				ACCT acctUsingAtData = TradeBot.getInstance().getAcctUsingAtData(atData);
				if (acctUsingAtData == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
				if (acctUsingAtData instanceof BitcoinyACCTv4)
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Multiple response batching is not supported for split-fill offers.");
				CrossChainTradeData crossChainTradeData = acctUsingAtData.populateTradeData(repository, atData);
				if (crossChainTradeData == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
				if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN)
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Multiple response batching is not supported for reverse offers.");
				if (crossChainTradeData.tradeDirection == TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Multiple response batching is not supported for foreign/foreign offers.");

				ForeignBlockchainRegistry.Entry blockchain = ForeignBlockchainRegistry.fromRegisteredBitcoinyString(crossChainTradeData.foreignBlockchain);
				if (blockchain == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				Bitcoiny bitcoiny = blockchain.getBitcoinyInstance();
				if (bitcoiny == null)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (acct.isEmpty())
					acct = Optional.of(acctUsingAtData);
				else if (!acctUsingAtData.getCodeBytesHash().equals(acct.get().getCodeBytesHash()))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (foreignBlockchain.isEmpty())
					foreignBlockchain = Optional.of(blockchain);
				else if (foreignBlockchain.get() != blockchain)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (localAssetId.isEmpty())
					localAssetId = Optional.of(crossChainTradeData.localAssetId);
				else if (localAssetId.get() != crossChainTradeData.localAssetId)
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				if (!bitcoiny.isValidWalletKey(respondRequests.foreignKey))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

				if (!crossChainTradeData.isFillableOffer())
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				// Check if there is a buy or a cancel request in progress for this trade
				if (hasPendingMessageToAt(repository, atAddress))
					// There is a pending request for this trade, so block this buy attempt to reduce the risk of refunds
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Trade has an existing buy request or is pending cancellation.");

				crossChainTradeDataList.add(crossChainTradeData);
			}

			AcctTradeBot.ResponseResult result
					= TradeBot.getInstance().startResponseMultiple(
						repository,
						acct.get(),
						crossChainTradeDataList,
						respondRequests.receivingAddress,
						respondRequests.foreignKey,
						foreignBlockchain.get().getBitcoinyInstance());

			switch (result) {
				case OK:
					return "true";

				case BALANCE_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

				case NETWORK_ISSUE:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

				case INVALID_CRITERIA:
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

				default:
					return "false";
			}
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
		}
	}

	@DELETE
	@Operation(
		summary = "Delete completed trade",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					example = "93MB2qRDNVLxbmmPuYpLdAqn3u2x9ZhaVZK5wELHueP8"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String tradeBotDelete(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String tradePrivateKey58) {
		Security.checkApiCallAllowed(request);

		final byte[] tradePrivateKey;
		try {
			tradePrivateKey = Base58.decode(tradePrivateKey58);

			if (tradePrivateKey.length != 32)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Handed off to TradeBot
			return TradeBot.getInstance().deleteEntry(repository, tradePrivateKey) ? "true" : "false";
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private ATData fetchAtDataWithChecking(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		if (atData == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

		// No point sending message to AT that's finished
		if (atData.getIsFinished())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return atData;
	}

}
