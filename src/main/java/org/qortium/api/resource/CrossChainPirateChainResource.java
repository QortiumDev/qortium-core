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
import org.qortium.api.model.crosschain.ForeignCoinStatus;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainSendContract;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.api.model.crosschain.PirateChainSendResult;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;
import org.qortium.api.model.crosschain.PirateChainWalletInitializationRequest;
import org.qortium.api.model.crosschain.PirateChainWalletInitializationResult;
import org.qortium.utils.Base58;
import org.qortium.controller.PirateChainWalletController;
import org.qortium.controller.ZcashFamilyWalletController;
import org.qortium.crosschain.*;
import org.qortium.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/crosschain/arrr")
@Tag(name = "Cross-Chain (Pirate Chain)")
public class CrossChainPirateChainResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/status")
	@Operation(
			summary = "Returns wallet status, connected server count and known server count",
			description = "Returns the status of the wallet and the number of electrumX servers available/connected",
			responses = {
					@ApiResponse(
							content = @Content(
									schema = @Schema(
											implementation = ForeignCoinStatus.class
									)
							)
					)
			}
	)
	public ForeignCoinStatus getPirateStatus() {
		PirateChainWalletController pirateWallet = PirateChainWalletController.getInstance();
		boolean isEnabled = pirateWallet != null;
		int connections = 0;
		int known = 0;
		PirateChain pirate = isEnabled ? PirateChain.getInstance() : null;
		if (pirate != null && pirate.getBlockchainProvider() instanceof ElectrumX) {
			connections = ((ElectrumX) pirate.getBlockchainProvider()).getConnectedServerCount();
			known = ((ElectrumX) pirate.getBlockchainProvider()).getKnownServerCount();
		} else if (pirate != null && pirate.getBlockchainProvider() instanceof ZcashFamilyLightClient lightClient) {
			connections = lightClient.getCurrentServer() == null ? 0 : 1;
			known = lightClient.getServers().size();
		}

		return new ForeignCoinStatus(isEnabled, connections, known);
	}

	@POST
	@Path("/start")
	@Operation(
			summary = "Start the Pirate Chain wallet controller",
			description = "Enable and start the Pirate Chain wallet controller",
			responses = {
					@ApiResponse(
							description = "true if Pirate Wallet Started",
							content = @Content(
									schema = @Schema(
											type = "string"
									)
							)
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String startPirateChainSingleton(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey) {

		Security.checkApiCallAllowed(request);
		boolean started = PirateChainWalletController.startInstance();

		return Boolean.toString(started);
	}

	@POST
	@Path("/stop")
	@Operation(
			summary = "Stop the Pirate Chain wallet controller",
			description = "Cancel any active Pirate Chain wallet scan and stop the wallet controller without stopping Core",
			responses = {
					@ApiResponse(
							description = "true if the wallet controller stopped cleanly",
							content = @Content(schema = @Schema(type = "string"))
					)
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String stopPirateChainSingleton(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey) {

		Security.checkApiCallAllowed(request);
		boolean stopped = PirateChainWalletController.stopWallet();

		return Boolean.toString(stopped);
	}

    public static class WalletSessionRequest {
        public String entropy58;
        public String operation;
        public String expectedRevision;
    }

    @javax.xml.bind.annotation.XmlAccessorType(javax.xml.bind.annotation.XmlAccessType.FIELD)
    public static class WalletSessionContract {
        public String contract = PirateChainWalletController.SESSION_CONTRACT;
        public int scanStartProtocolVersion = 1;
        public java.util.List<String> scanModes = java.util.List.of("RESUME", "RESTORE_FROM_HEIGHT", "NEW_AT_CURRENT_TIP");
    }

    @GET
    @Path("/walletsession")
    @javax.ws.rs.Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "apiKey")
    public WalletSessionContract walletSessionContract(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
        Security.checkApiCallAllowed(request);
        return new WalletSessionContract();
    }

    @POST
    @Path("/walletsession")
    @javax.ws.rs.Consumes(MediaType.APPLICATION_JSON)
    @javax.ws.rs.Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "apiKey")
    @Operation(summary = "Inspect or explicitly activate this ARRR wallet; ordinary reads never switch ownership")
    public PirateChainWalletController.WalletSession walletSession(
            @HeaderParam(Security.API_KEY_HEADER) String apiKey, WalletSessionRequest value) {
        Security.checkApiCallAllowed(request);
        if (value == null || !isValidEntropy(value.entropy58) ||
                !("status".equals(value.operation) || "activate".equals(value.operation)) ||
                ("activate".equals(value.operation) && (value.expectedRevision == null || !value.expectedRevision.matches("[a-f0-9-]{36}"))))
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
        try {
            return "activate".equals(value.operation)
                    ? PirateChainWalletController.activateWallet(value.entropy58, value.expectedRevision)
                    : PirateChainWalletController.walletSession(value.entropy58);
        } catch (ForeignBlockchainException e) {
            ApiError error = e.getMessage() != null && e.getMessage().contains("ARRR_SESSION_CHANGED")
                    ? ApiError.OPERATION_IN_PROGRESS : ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE;
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, error, e.getMessage());
        }
    }

	@POST
	@Path("/initialize")
	@Operation(
			summary = "Initialize Pirate Chain wallet scanning at an explicit height or current tip",
			description = "Local-operator endpoint: requires the API key, a loopback remote address, and the "
					+ "Unified Pirate wallet. NEW_AT_CURRENT_TIP is a deliberate assertion that the deterministic "
					+ "wallet has no historical receipts. Core persists the exact validated tip before native wallet "
					+ "creation and reuses it on exact retries. Existing, legacy, or conservatively initialized "
					+ "namespaces are rejected; ordinary wallet access remains conservatively initialized.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = PirateChainWalletInitializationRequest.class)
					)
			),
			responses = {
					@ApiResponse(content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = PirateChainWalletInitializationResult.class)))
			}
	)
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	@javax.ws.rs.Consumes(MediaType.APPLICATION_JSON)
	@javax.ws.rs.Produces(MediaType.APPLICATION_JSON)
	public PirateChainWalletInitializationResult initializeWallet(
            @HeaderParam(Security.API_KEY_HEADER) String apiKey, java.io.InputStream body) throws java.io.IOException {
        Security.checkApiCallAllowed(request);
        Security.requireLoopbackRequest(request);
        return initializeKnownNewWallet(apiKey, CrossChainWalletResource.initializationBody(body));
    }

    public PirateChainWalletInitializationResult initializeKnownNewWallet(
            String apiKey, PirateChainWalletInitializationRequest initializationRequest) {
		Security.checkApiCallAllowed(request);
		Security.requireLoopbackRequest(request);

        if (initializationRequest != null && initializationRequest.expectedRevision != null &&
                !initializationRequest.expectedRevision.matches("[a-f0-9-]{36}"))
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		String validationError = validateWalletInitializationRequest(initializationRequest);
		if (validationError != null)
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					validationError);
		if (!Settings.getInstance().isPirateChainWalletUnified())
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					"Known-new initialization requires the Unified Pirate wallet");

		try {
			PirateChainWalletController.KnownNewInitialization result =
					"RESTORE_FROM_HEIGHT".equals(initializationRequest.initializationMode)
                            ? PirateChainWalletController.initializeWalletFromHeight(initializationRequest.entropy58, initializationRequest.expectedRevision, initializationRequest.restoreHeight)
                            : PirateChainWalletController.initializeKnownNewWallet(initializationRequest.entropy58, initializationRequest.expectedRevision);
			return new PirateChainWalletInitializationResult(initializationRequest.initializationMode,
					result.birthdayHeight());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request,
					(e.getMessage() != null && e.getMessage().contains("ARRR_SESSION_CHANGED") ? ApiError.OPERATION_IN_PROGRESS : ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE), e.getMessage());
		}
	}

	static String validateWalletInitializationRequest(PirateChainWalletInitializationRequest initializationRequest) {
		if (initializationRequest == null)
			return "Missing request body";

		byte[] entropyBytes;
		try {
			entropyBytes = initializationRequest.entropy58 == null
					? null : Base58.decode(initializationRequest.entropy58);
		} catch (RuntimeException e) {
			entropyBytes = null;
		}
		if (entropyBytes == null || entropyBytes.length != 32)
			return "Invalid entropy bytes";
		if ("RESTORE_FROM_HEIGHT".equals(initializationRequest.initializationMode)) {
            if (initializationRequest.restoreHeight == null || initializationRequest.restoreHeight < 1
                    || initializationRequest.restoreHeight > 500_000_000 || initializationRequest.expectedRevision == null)
                return "Restore requires a height and wallet revision";
        } else if (!"NEW_AT_CURRENT_TIP".equals(initializationRequest.initializationMode)
                || initializationRequest.restoreHeight != null) return "Initialization mode must be NEW_AT_CURRENT_TIP or RESTORE_FROM_HEIGHT";

		return null;
	}

	@GET
	@Path("/height")
	@Operation(
		summary = "Returns current PirateChain block height",
		description = "Returns the height of the most recent block in the PirateChain chain.",
		responses = {
			@ApiResponse(
				content = @Content(
					schema = @Schema(
						type = "number"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	public String getPirateChainHeight() {
		PirateChain pirateChain = PirateChain.getInstance();

		try {
			Integer height = pirateChain.getBlockchainHeight();
			if (height == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			return height.toString();

		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/walletbalance")
	@Operation(
		summary = "Returns ARRR balance",
		description = "Supply 32 bytes of entropy, Base58 encoded",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
						type = "string",
						description = "32 bytes of entropy, Base58 encoded",
						example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "balance (satoshis)"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
			ApiError.FOREIGN_BLOCKCHAIN_BALANCE_UNAVAILABLE, ApiError.OPERATION_IN_PROGRESS})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "If true, return verified available balance instead of total balance")
			@QueryParam("verified") Boolean verified, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			if (pirateChain == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			PirateChainBalance balances = pirateChain.getWalletBalances(entropy58);
			return Long.toString(selectWalletBalance(balances, Boolean.TRUE.equals(verified)));

		} catch (ForeignBlockchainException.WalletBusyException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS, e.getMessage());
		} catch (ForeignBlockchainException.BalanceUnavailableException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_UNAVAILABLE, e.getMessage());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	/** Stable, machine-readable reason for a verified balance that the active backend cannot determine. */
	static final String ARRR_VERIFIED_BALANCE_UNAVAILABLE = "ARRR_VERIFIED_BALANCE_UNAVAILABLE";

	static long selectWalletBalance(PirateChainBalance balances, boolean verified)
			throws ForeignBlockchainException {
		if (balances == null)
			throw new ForeignBlockchainException("Unable to determine balance");
		if (!verified)
			return balances.zbalance;
		if (!balances.verifiedBalanceKnown)
			throw new ForeignBlockchainException.BalanceUnavailableException(ARRR_VERIFIED_BALANCE_UNAVAILABLE);
		return balances.verified_zbalance;
	}

	@POST
	@Path("/wallettransactions")
	@Operation(
		summary = "Returns transactions",
		description = "Supply 32 bytes of entropy, Base58 encoded",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
						type = "string",
						description = "32 bytes of entropy, Base58 encoded",
						example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(array = @ArraySchema( schema = @Schema( implementation = SimpleTransaction.class ) ) )
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.OPERATION_IN_PROGRESS})
	@SecurityRequirement(name = "apiKey")
	public List<SimpleTransaction> getPirateChainWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletTransactions(entropy58);
		} catch (ForeignBlockchainException.WalletBusyException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS, e.getMessage());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@GET
	@Path("/sendcontract")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Describes this node's ARRR send contract (protocol version, fixed fee, limits)",
		description = "Static contract for POST /crosschain/arrr/send. Carries no wallet state and needs no entropy.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PirateChainSendContract.class))
			)
		}
	)
	@SecurityRequirement(name = "apiKey")
	public PirateChainSendContract getPirateChainSendContract(@HeaderParam(Security.API_KEY_HEADER) String apiKey) {
		Security.checkApiCallAllowed(request);
		var contract = new PirateChainSendContract(PirateChain.getSendFeeAtomic());
        contract.network = Settings.getInstance().getPirateChainNet().name();
        return contract;
	}

	@POST
	@Path("/send")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Admits an idempotent ARRR send operation (protocol version 2)",
		description = "Durably reserves a request before native execution. Returns HTTP 202 while pending, "
				+ "or HTTP 200 for an existing terminal operation. Reusing the same key and payment returns the "
				+ "same operation; changing the payment conflicts. Poll the operation or look it up by key after "
				+ "a lost response. UNRESOLVED means the payment may already have been broadcast: do not retry "
				+ "with a new key. It blocks this wallet's spending, including trade funding. Fixed fee: 10000 atomic.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(implementation = PirateChainSendRequest.class)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = org.qortium.api.model.crosschain.PirateChainSendOperation.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS,
			ApiError.FOREIGN_WALLET_NOT_READY, ApiError.OPERATION_IN_PROGRESS, ApiError.FOREIGN_SEND_CONFLICT, ApiError.FOREIGN_SEND_STORAGE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE,
			ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public javax.ws.rs.core.Response sendPirateChain(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PirateChainSendRequest sendRequest) {
		Security.checkApiCallAllowed(request);
		SendRequestRejection rejection = validateSendRequest(sendRequest);
		if (rejection != null)
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, rejection.error, rejection.message());
		if (!Settings.getInstance().isPirateChainWalletUnified())
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					PirateChain.WALLET_MODE_UNSUPPORTED_REASON);
		try {
			String network = Settings.getInstance().getPirateChainNet().name();
            if (sendRequest.expectedNetwork != null && !network.equals(sendRequest.expectedNetwork))
                throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "ARRR_SEND_NETWORK_MISMATCH");
			var input = new org.qortium.crosschain.PirateChainSendService.Request(sendRequest.entropy58,
					org.qortium.crosschain.PirateChainSendJournal.walletIdentity(sendRequest.entropy58), network,
					sendRequest.idempotencyKey, sendRequest.receivingAddress,
					PirateChainAmountAdapter.parseAtomic(sendRequest.arrrAmount), sendRequest.memo, PirateChain.getSendFeeAtomic());
			var op = org.qortium.crosschain.PirateChainSendRuntime.service().submit(input,
					() -> org.qortium.crosschain.PirateChainSendRuntime.checkReservedAdmission(sendRequest.entropy58));
			boolean pending = op.phase() == org.qortium.crosschain.PirateChainSendJournal.Phase.ACCEPTED
					|| op.phase() == org.qortium.crosschain.PirateChainSendJournal.Phase.NATIVE_STARTED;
			return javax.ws.rs.core.Response.status(pending ? 202 : 200)
					.location(java.net.URI.create("/crosschain/arrr/send/" + op.operationId()))
					.entity(new org.qortium.api.model.crosschain.PirateChainSendOperation(op)).build();
		} catch (org.qortium.crosschain.PirateChainSendJournal.ConflictException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_SEND_CONFLICT, "ARRR_SEND_REQUEST_CONFLICT");
		} catch (java.io.IOException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_SEND_STORAGE_ISSUE, "ARRR_SEND_STORAGE_UNAVAILABLE");
		} catch (ForeignBlockchainException.WalletBusyException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS, e.getMessage());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_WALLET_NOT_READY,
					e instanceof ForeignBlockchainException.WalletNotReadyException ? e.getMessage() : "ARRR_WALLET_NOT_READY");
		}
	}

	@GET
	@Path("/send/{operationId}")
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FOREIGN_SEND_NOT_FOUND, ApiError.FOREIGN_SEND_STORAGE_ISSUE})
	@Produces(MediaType.APPLICATION_JSON)
	@SecurityRequirement(name = "apiKey")
	public org.qortium.api.model.crosschain.PirateChainSendOperation getSendOperation(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("operationId") String operationId) {
		Security.checkApiCallAllowed(request);
		if (operationId == null || !CANONICAL_UUID.matcher(operationId).matches())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		try {
			var op = org.qortium.crosschain.PirateChainSendRuntime.service().get(operationId);
			if (op == null) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_SEND_NOT_FOUND);
			return new org.qortium.api.model.crosschain.PirateChainSendOperation(op);
		} catch (java.io.IOException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_SEND_STORAGE_ISSUE, "ARRR_SEND_STORAGE_UNAVAILABLE");
		}
	}

	@POST
	@Path("/sendlookup/{idempotencyKey}")
    @ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_SEND_NOT_FOUND, ApiError.FOREIGN_SEND_STORAGE_ISSUE})
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.APPLICATION_JSON)
	@SecurityRequirement(name = "apiKey")
	public org.qortium.api.model.crosschain.PirateChainSendOperation lookupSendOperation(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("idempotencyKey") String key, String entropy58) {
		Security.checkApiCallAllowed(request);
		if (!isValidEntropy(entropy58)) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		if (key == null || !CANONICAL_UUID.matcher(key).matches())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		try {
			var op = org.qortium.crosschain.PirateChainSendRuntime.service().lookup(
					org.qortium.crosschain.PirateChainSendJournal.walletIdentity(entropy58), key);
			if (op == null) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_SEND_NOT_FOUND);
			return new org.qortium.api.model.crosschain.PirateChainSendOperation(op);
		} catch (java.io.IOException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_SEND_STORAGE_ISSUE, "ARRR_SEND_STORAGE_UNAVAILABLE");
		}
	}

	@POST
	@Path("/sendreadiness")
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.APPLICATION_JSON)
	@SecurityRequirement(name = "apiKey")
	public org.qortium.api.model.crosschain.PirateChainSendReadiness getSendReadiness(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);
		if (!isValidEntropy(entropy58)) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);
		var result = new org.qortium.api.model.crosschain.PirateChainSendReadiness();
		result.network = Settings.getInstance().getPirateChainNet().name();
		try {
			result.blockingOperationId = org.qortium.crosschain.PirateChainSendRuntime.service().blocking(
					org.qortium.crosschain.PirateChainSendJournal.walletIdentity(entropy58));
			org.qortium.crosschain.PirateChainSendRuntime.checkAdmission(entropy58);
			result.sendAllowed = true;
		} catch (java.io.IOException e) { result.reason = "ARRR_SEND_STORAGE_UNAVAILABLE"; }
		catch (ForeignBlockchainException e) { result.reason = result.blockingOperationId != null ? "ARRR_SEND_UNRESOLVED_OR_PENDING" : "ARRR_WALLET_NOT_READY"; }
		return result;
	}

	/** Canonical lowercase UUID (8-4-4-4-12 hex); uppercase, braces and URN prefixes are rejected. */
	private static final java.util.regex.Pattern CANONICAL_UUID =
			java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

	/** One rejected send request: the API error to raise and a stable reason token for the message. */
	static final class SendRequestRejection {
		public final ApiError error;
		public final String reason;
		private final String detail;

		SendRequestRejection(ApiError error, String reason, String detail) {
			this.error = error;
			this.reason = reason;
			this.detail = detail;
		}

		/** Stable token first so clients can match on it, then a short human explanation. */
		String message() {
			return this.reason + ": " + this.detail;
		}
	}

	/**
	 * Validates a send request before any wallet work, in a fixed order so a client sees one
	 * deterministic error at a time: body (115) -> entropy (128) -> feePerByte (125) -> idempotencyKey
	 * (125) -> amount (125) -> address (102) -> memo (115). Nothing here logs or returns the field
	 * values themselves.
	 *
	 * @return null when valid, else the rejection to raise
	 */
	static SendRequestRejection validateSendRequest(PirateChainSendRequest sendRequest) {
		if (sendRequest == null)
			return new SendRequestRejection(ApiError.INVALID_DATA, "MISSING_BODY", "a JSON request body is required");

		if (!isValidEntropy(sendRequest.entropy58))
			return new SendRequestRejection(ApiError.INVALID_PRIVATE_KEY, "ENTROPY_INVALID",
					"entropy58 must be exactly 32 Base58-encoded bytes");

		if (sendRequest.feePerByte != null)
			return new SendRequestRejection(ApiError.INVALID_CRITERIA, "FEE_PER_BYTE_UNSUPPORTED",
					"feePerByte is not supported; the fee is fixed (see /crosschain/arrr/sendcontract)");

		if (sendRequest.idempotencyKey == null || !CANONICAL_UUID.matcher(sendRequest.idempotencyKey).matches())
			return new SendRequestRejection(ApiError.INVALID_CRITERIA, "IDEMPOTENCY_KEY_INVALID",
					"idempotencyKey must be a canonical lowercase UUID");

		try {
			PirateChainAmountAdapter.parseAtomic(sendRequest.arrrAmount);
		} catch (IllegalArgumentException e) {
			return new SendRequestRejection(ApiError.INVALID_CRITERIA, e.getMessage(),
					"arrrAmount must be positive plain decimal text with at most 8 decimals");
		}

		String address = sendRequest.receivingAddress;
		if (address == null || address.isBlank())
			return new SendRequestRejection(ApiError.INVALID_ADDRESS, "RECIPIENT_INVALID",
					"receivingAddress is required");
		// Classify by prefix (ignoring case and surrounding whitespace) so a client can tell "wrong
		// address type" from "malformed Sapling address"; the canonical check below is strict.
		if (!address.strip().toLowerCase(java.util.Locale.ROOT).startsWith("zs1"))
			return new SendRequestRejection(ApiError.INVALID_ADDRESS, "RECIPIENT_UNSUPPORTED",
					"only Sapling (zs1...) recipients are supported");
		if (!PirateChain.isCanonicalSaplingAddress(address))
			return new SendRequestRejection(ApiError.INVALID_ADDRESS, "RECIPIENT_INVALID",
					"receivingAddress must be a canonical lowercase Sapling address");

		String memoReason = PirateChain.validateMemo(sendRequest.memo);
		if (memoReason != null)
			return new SendRequestRejection(ApiError.INVALID_DATA, memoReason,
					"memo must be at most 512 UTF-8 bytes of well-formed text without control characters");

		return null;
	}


	@POST
	@Path("/walletaddress")
	@Operation(
			summary = "Returns main wallet address",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(type = "string", description = "Pirate Chain wallet address")))
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, ApiError.OPERATION_IN_PROGRESS})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletAddress(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletAddress(entropy58);
		} catch (ForeignBlockchainException.WalletBusyException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS, e.getMessage());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/walletprivatekey")
	@Operation(
			summary = "Returns main wallet private key",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Private Key String"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainPrivateKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getPrivateKey(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/walletseedphrase")
	@Operation(
			summary = "Returns main wallet seedphrase",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "Wallet Seedphrase String"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletSeed(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletSeed(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/syncstatus")
	@Operation(
			summary = "Returns synchronization status",
			description = "Supply 32 bytes of entropy, Base58 encoded",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "string",
									description = "32 bytes of entropy, Base58 encoded",
									example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV"
							)
					)
			),
			responses = {
					@ApiResponse(content = {
							@Content(mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(type = "string", description = "legacy synchronization status")),
							@Content(mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = PirateChainSyncStatus.class))
					})
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE,
			ApiError.OPERATION_IN_PROGRESS})
	@SecurityRequirement(name = "apiKey")
	public Response getPirateChainSyncStatus(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "If true, return a stable structured status")
			@QueryParam("json") Boolean json, String entropy58) {
		Security.checkApiCallAllowed(request);

		// The structured (JSON) status is wallet-bound: it carries balances and an identity hash, so
		// it must never be served for an absent/malformed entropy body, which would otherwise fall
		// through to "whichever wallet happens to be currently active" with no ownership check at
		// all. The legacy plain-text contract only ever returns a message string, never balances or
		// identity, so it keeps its existing permissive (possibly entropy-less) behavior.
		if (Boolean.TRUE.equals(json) && !isValidEntropy(entropy58))
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_DATA,
					"Invalid entropy bytes");

		try {
			ZcashFamilyWalletController.WalletSyncStatus status;
			if (!Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE)) {
				status = ZcashFamilyWalletController.WalletSyncStatus.disabled("Pirate Chain wallet is disabled");
			} else {
				PirateChain pirateChain = PirateChain.getInstance();
				status = pirateChain == null
						? ZcashFamilyWalletController.WalletSyncStatus.disabled("Pirate Chain wallet is disabled")
						: pirateChain.getSyncStatusDetails(entropy58);
			}

			if (Boolean.TRUE.equals(json)) {
				return Response.ok(toStructuredStatus(status), MediaType.APPLICATION_JSON_TYPE).build();
			}

			return Response.ok(status.getMessage(), MediaType.TEXT_PLAIN_TYPE).build();
		} catch (ForeignBlockchainException.WalletBusyException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.OPERATION_IN_PROGRESS, e.getMessage());
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	private static boolean isValidEntropy(String entropy58) {
		if (entropy58 == null)
			return false;
		try {
			byte[] entropyBytes = Base58.decode(entropy58);
			return entropyBytes != null && entropyBytes.length == 32;
		} catch (RuntimeException e) {
			return false;
		}
	}

	static PirateChainSyncStatus toStructuredStatus(ZcashFamilyWalletController.WalletSyncStatus status) {
		String backendMode = Settings.getInstance().isPirateChainWalletUnified() ? "unified" : "legacy";
		PirateChainSyncStatus.LastError lastError = status.getLastErrorCode() == null ? null
				: new PirateChainSyncStatus.LastError(status.getLastErrorCode(), status.getLastErrorMessage());

		return new PirateChainSyncStatus(PirateChainSyncStatus.State.valueOf(status.getState().name()),
				status.getMessage(), status.getSyncedBlocks(), status.getTotalBlocks(), status.isRestartRequired(),
				status.getRecoveryState(), status.getScannedHeight(), status.getTipHeight(),
				status.getTotalBalanceAtomic(), status.getVerifiedBalanceAtomic(), status.getObservedAt(),
				status.isStale(), backendMode, status.getWalletIdentityHash(), lastError);
	}

	@POST
	@Path("/recovery/import")
	@Operation(
			summary = "Imports one verified external spending key into the Unified ARRR wallet",
			description = "Local-operator endpoint: requires the API key AND a loopback remote address AND the "
					+ "opt-in Unified Pirate wallet. Exactly one pool spending key is imported per call, proven "
					+ "against its canonical expected address and sequential index before any mutation. The native "
					+ "wallet is authoritative for every ownership, network, case, and birthday rule; an exact "
					+ "retry of the same request is safe and reports alreadyImported. The response never contains "
					+ "key material. A present requiredRescanFromHeight means a historical rescan is still owed "
					+ "before recovered funds are visible and spendable; the field is omitted when none is pending.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(implementation = PirateChainVerifiedRecoveryRequest.class)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(implementation = PirateChainVerifiedRecoveryResult.class)
							)
					)
			}
	)
	@ApiErrors({ApiError.UNAUTHORIZED, ApiError.INVALID_CRITERIA, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	@javax.ws.rs.Consumes(MediaType.APPLICATION_JSON)
	@javax.ws.rs.Produces(MediaType.APPLICATION_JSON)
	public PirateChainVerifiedRecoveryResult importVerifiedRecoveryKey(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PirateChainVerifiedRecoveryRequest recoveryRequest) {
		Security.checkApiCallAllowed(request);
		Security.requireLoopbackRequest(request);

		if (!Settings.getInstance().isPirateChainWalletUnified())
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					"Verified recovery requires the opt-in Unified Pirate wallet");

		String validationError = validateVerifiedRecoveryRequest(recoveryRequest);
		if (validationError != null)
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					validationError);

		PirateChain pirateChain = PirateChain.getInstance();
		if (pirateChain == null)
			throw ApiExceptionFactory.INSTANCE.createCustomException(request,
					ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, "Pirate Chain wallet is disabled");

		try {
			return pirateChain.importVerifiedSpendingKey(recoveryRequest);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request,
					ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	/**
	 * Fast-fail pre-checks mirroring the upstream request contract. Returns a stable message,
	 * or null when the request may be forwarded. The native wallet remains authoritative;
	 * nothing here may accept what upstream rejects. Messages never include request content.
	 */
	static String validateVerifiedRecoveryRequest(PirateChainVerifiedRecoveryRequest recoveryRequest) {
		if (recoveryRequest == null)
			return "Missing request body";

		byte[] entropyBytes;
		try {
			entropyBytes = recoveryRequest.entropy58 == null ? null : Base58.decode(recoveryRequest.entropy58);
		} catch (RuntimeException e) {
			entropyBytes = null;
		}
		if (entropyBytes == null || entropyBytes.length != 32)
			return "Invalid entropy bytes";

		if (!"sapling".equals(recoveryRequest.pool) && !"ironwood".equals(recoveryRequest.pool))
			return "Pool must be sapling or ironwood";

		if (recoveryRequest.spendingKey == null || recoveryRequest.spendingKey.isBlank()
				|| isMixedCase(recoveryRequest.spendingKey))
			return "Invalid spending key encoding";

		if (recoveryRequest.expectedAddress == null || recoveryRequest.expectedAddress.isBlank()
				|| isMixedCase(recoveryRequest.expectedAddress))
			return "Invalid expected address encoding";

		if (recoveryRequest.addressIndex == null || recoveryRequest.addressIndex < 0
				|| recoveryRequest.addressIndex > 4096)
			return "Address index must be between 0 and 4096";

		if (recoveryRequest.birthdayHeight == null || recoveryRequest.birthdayHeight < 1)
			return "Birthday height must be greater than zero";

		// The label is deliberately NOT pre-validated: upstream's Unicode-aware trim and
		// 100-byte limit are authoritative, and a divergent Java pre-check could reject
		// labels the native wallet accepts. The label passes through verbatim.

		return null;
	}

	/** Bech32 rejects mixed-case strings; failing fast avoids a pointless native round trip. */
	static boolean isMixedCase(String value) {
		boolean hasUpper = false;
		boolean hasLower = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			hasUpper |= Character.isUpperCase(c);
			hasLower |= Character.isLowerCase(c);
		}
		return hasUpper && hasLower;
	}

	@GET
	@Path("/serverinfos")
	@Operation(
			summary = "Returns current PirateChain server configuration",
			description = "Returns current PirateChain server locations and use status",
			responses = {
					@ApiResponse(
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(
											implementation = ServerConfigurationInfo.class
									)
							)
					)
			}
	)
	public ServerConfigurationInfo getServerConfiguration() {

		return CrossChainUtils.buildServerConfigurationInfo(PirateChain.getInstance());
	}

	@GET
	@Path("/serverconnectionhistory")
	@Operation(
			summary = "Returns Pirate Chain server connection history",
			description = "Returns Pirate Chain server connection history",
			responses = {
					@ApiResponse(
							content = @Content(array = @ArraySchema( schema = @Schema( implementation = ServerConnectionInfo.class ) ) )
					)
			}
	)
	public List<ServerConnectionInfo> getServerConnectionHistory() {

		return CrossChainUtils.buildServerConnectionHistory(PirateChain.getInstance());
	}

	@POST
	@Path("/addserver")
	@Operation(
			summary = "Add server to list of Pirate Chain servers",
			description = "Add server to list of Pirate Chain servers",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if added, false if not added",
							content = @Content(
									schema = @Schema(
											type = "string"
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String addServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			PirateLightClient.Server server = new PirateLightClient.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			if( CrossChainUtils.addServer( PirateChain.getInstance(), server )) {
				return "true";
			}
			else {
				return "false";
			}
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/removeserver")
	@Operation(
			summary = "Remove server from list of Pirate Chain servers",
			description = "Remove server from list of Pirate Chain servers",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "true if removed, otherwise",
							content = @Content(
									schema = @Schema(
											type = "string"
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String removeServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			PirateLightClient.Server server = new PirateLightClient.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			if( CrossChainUtils.removeServer( PirateChain.getInstance(), server ) ) {

				return "true";
			}
			else {
				return "false";
			}
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/setcurrentserver")
	@Operation(
			summary = "Set current Pirate Chain server",
			description = "Set current Pirate Chain server",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON,
							schema = @Schema(
									implementation = ServerInfo.class
							)
					)
			),
			responses = {
					@ApiResponse(
							description = "connection info",
							content = @Content(
									mediaType = MediaType.APPLICATION_JSON,
									schema = @Schema(
											implementation = ServerConnectionInfo.class
									)
							)
					)
			}

	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public ServerConnectionInfo setCurrentServerInfo(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		if( serverInfo.getConnectionType() == null ||
				serverInfo.getHostName() == null) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		try {
			return CrossChainUtils.setCurrentServer( PirateChain.getInstance(), serverInfo );
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
		catch (Exception e) {
			return new ServerConnectionInfo(
					serverInfo,
					CrossChainUtils.CORE_API_CALL,
					true,
					false,
					System.currentTimeMillis(),
					CrossChainUtils.getNotes(e));
		}
	}

	@GET
	@Path("/feekb")
	@Operation(
			summary = "Returns PirateChain fee per Kb.",
			description = "Returns PirateChain fee per Kb.",
			responses = {
					@ApiResponse(
							content = @Content(
									schema = @Schema(
											type = "number"
									)
							)
					)
			}
	)
	public String getPirateChainFeePerKb() {
		PirateChain pirateChain = PirateChain.getInstance();

		return String.valueOf(pirateChain.getFeePerKb().value);
	}

	@POST
	@Path("/updatefeekb")
	@Operation(
			summary = "Sets PirateChain fee per Kb.",
			description = "Sets PirateChain fee per Kb.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "number",
									description = "the fee per Kb",
									example = "100"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA})
	public String setPirateChainFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return CrossChainUtils.setFeePerKb(pirateChain, fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feerequired")
	@Operation(
			summary = "The total fee required for unlocking ARRR to the trade offer creator.",
			description = "The total fee required for unlocking ARRR to the trade offer creator.",
			responses = {
					@ApiResponse(
							content = @Content(
									schema = @Schema(
											type = "number"
									)
							)
					)
			}
	)
	public String getPirateChainFeeRequired() {
		PirateChain pirateChain = PirateChain.getInstance();

		return String.valueOf(pirateChain.getFeeRequired());
	}

	@POST
	@Path("/updatefeerequired")
	@Operation(
			summary = "The total fee required for unlocking ARRR to the trade offer creator.",
			description = "This is in sats for a transaction that is approximately 300 kB in size.",
			requestBody = @RequestBody(
					required = true,
					content = @Content(
							mediaType = MediaType.TEXT_PLAIN,
							schema = @Schema(
									type = "number",
									description = "the fee",
									example = "100"
							)
					)
			),
			responses = {
					@ApiResponse(
							content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number", description = "fee"))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA})
	public String setPirateChainFeeRequired(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String fee) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return CrossChainUtils.setFeeRequired(pirateChain, fee);
		}
		catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}
}
