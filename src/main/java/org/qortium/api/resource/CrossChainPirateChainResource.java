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
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;
import org.qortium.utils.Base58;
import org.qortium.controller.PirateChainWalletController;
import org.qortium.controller.ZcashFamilyWalletController;
import org.qortium.crosschain.*;
import org.qortium.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
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
			summary = "Start PirateChain Electrum Connections",
			description = "Start PirateChain Electrum Connections",
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
		Settings.getInstance().enableWallet("ARRR");
		PirateChainWalletController pirate = PirateChainWalletController.getInstance();
		boolean started = pirate != null && pirate.startController();

		return Boolean.toString(started);
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
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
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

		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	static long selectWalletBalance(PirateChainBalance balances, boolean verified)
			throws ForeignBlockchainException {
		if (balances == null)
			throw new ForeignBlockchainException("Unable to determine balance");
		if (!verified)
			return balances.zbalance;
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
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<SimpleTransaction> getPirateChainWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletTransactions(entropy58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	@POST
	@Path("/send")
	@Operation(
		summary = "Sends ARRR from wallet",
		description = "Currently supports 'legacy' P2PKH PirateChain addresses and Native SegWit (P2WPKH) addresses. Supply BIP32 'm' private key in base58, starting with 'xprv' for mainnet, 'tprv' for testnet",
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
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "transaction hash"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String sendPirateChain(@HeaderParam(Security.API_KEY_HEADER) String apiKey, PirateChainSendRequest pirateChainSendRequest) {
		Security.checkApiCallAllowed(request);

		if (pirateChainSendRequest.arrrAmount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (pirateChainSendRequest.feePerByte != null && pirateChainSendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.sendCoins(pirateChainSendRequest);

		} catch (ForeignBlockchainException e) {
			// TODO
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
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
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getPirateChainWalletAddress(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String entropy58) {
		Security.checkApiCallAllowed(request);

		PirateChain pirateChain = PirateChain.getInstance();

		try {
			return pirateChain.getWalletAddress(entropy58);
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
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public Response getPirateChainSyncStatus(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@Parameter(description = "If true, return a stable structured status")
			@QueryParam("json") Boolean json, String entropy58) {
		Security.checkApiCallAllowed(request);

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
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE, e.getMessage());
		}
	}

	static PirateChainSyncStatus toStructuredStatus(ZcashFamilyWalletController.WalletSyncStatus status) {
		return new PirateChainSyncStatus(PirateChainSyncStatus.State.valueOf(status.getState().name()),
				status.getMessage(), status.getSyncedBlocks(), status.getTotalBlocks(), status.isRestartRequired());
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
