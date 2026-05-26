package org.qortium.api.resource;

import io.swagger.v3.oas.annotations.Operation;
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
import org.qortium.api.model.crosschain.AddressRequest;
import org.qortium.api.model.crosschain.BitcoinySendRequest;
import org.qortium.api.model.crosschain.ForeignCoinStatus;
import org.qortium.crosschain.AddressInfo;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ElectrumX;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.ServerConfigurationInfo;
import org.qortium.crosschain.ServerConnectionInfo;
import org.qortium.crosschain.ServerInfo;
import org.qortium.crosschain.SimpleTransaction;
import org.qortium.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path("/crosschain/{blockchain}")
@Tag(name = "Cross-Chain (Bitcoiny)")
public class CrossChainBitcoinyResource {

	@Context
	HttpServletRequest request;

	private ForeignBlockchainRegistry.Entry getBitcoinyEntry(String blockchain) {
		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = ForeignBlockchainRegistry.fromRegisteredBitcoinyString(blockchain);
		if (foreignBlockchainEntry == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		return foreignBlockchainEntry;
	}

	private Bitcoiny getBitcoiny(String blockchain) {
		Bitcoiny bitcoiny = getBitcoinyEntry(blockchain).getBitcoinyInstance();
		if (bitcoiny == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

		return bitcoiny;
	}

	@GET
	@Path("/status")
	@Operation(
			summary = "Returns wallet status, connected server count and known server count",
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(implementation = ForeignCoinStatus.class)))
			}
	)
	public ForeignCoinStatus getWalletStatus(@PathParam("blockchain") String blockchain) {
		Bitcoiny bitcoiny = getBitcoinyEntry(blockchain).getBitcoinyInstance();
		boolean isEnabled = bitcoiny != null;
		int connections = 0;
		int known = 0;

		if (isEnabled && bitcoiny.getBlockchainProvider() instanceof ElectrumX) {
			connections = ((ElectrumX) bitcoiny.getBlockchainProvider()).getConnectedServerCount();
			known = ((ElectrumX) bitcoiny.getBlockchainProvider()).getKnownServerCount();
		}

		return new ForeignCoinStatus(isEnabled, connections, known);
	}

	@POST
	@Path("/start")
	@Operation(
			summary = "Start Electrum connections for a Bitcoiny chain",
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "string")))
			}
	)
	@SecurityRequirement(name = "apiKey")
	public String startWalletSingleton(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain) {
		Security.checkApiCallAllowed(request);

		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = getBitcoinyEntry(blockchain);
		Settings.getInstance().enableWallet(foreignBlockchainEntry.getCurrencyCode());
		Bitcoiny bitcoiny = foreignBlockchainEntry.getBitcoinyInstance();

		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		return Boolean.toString(bitcoiny != null);
	}

	@GET
	@Path("/height")
	@Operation(
			summary = "Returns current foreign block height",
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "number")))
			}
	)
	@ApiErrors({ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	public String getHeight(@PathParam("blockchain") String blockchain) {
		Bitcoiny bitcoiny = getBitcoiny(blockchain);

		try {
			Integer height = bitcoiny.getBlockchainHeight();
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
			summary = "Returns balance for hierarchical, deterministic BIP32 wallet",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			),
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string")))
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String getWalletBalance(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, String key58) {
		Security.checkApiCallAllowed(request);

		Bitcoiny bitcoiny = getBitcoiny(blockchain);
		if (!bitcoiny.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			Long balance = bitcoiny.getWalletBalance(key58);
			if (balance == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);

			return balance.toString();
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/wallettransactions")
	@Operation(
			summary = "Returns transactions for hierarchical, deterministic BIP32 wallet",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			),
			responses = {
					@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = SimpleTransaction.class))))
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<SimpleTransaction> getWalletTransactions(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, String key58) {
		Security.checkApiCallAllowed(request);

		Bitcoiny bitcoiny = getBitcoiny(blockchain);
		if (!bitcoiny.isValidDeterministicKey(key58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return bitcoiny.getWalletTransactions(key58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/addressinfos")
	@Operation(
			summary = "Returns address information for hierarchical, deterministic BIP32 wallet",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AddressRequest.class))
			),
			responses = {
					@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = AddressInfo.class))))
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public List<AddressInfo> getAddressInfos(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, AddressRequest addressRequest) {
		Security.checkApiCallAllowed(request);

		Bitcoiny bitcoiny = getBitcoiny(blockchain);
		if (!bitcoiny.isValidDeterministicKey(addressRequest.xpub58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try {
			return bitcoiny.getWalletAddressInfos(addressRequest.xpub58);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}
	}

	@POST
	@Path("/send")
	@Operation(
			summary = "Sends funds from hierarchical, deterministic BIP32 wallet to specific address",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = BitcoinySendRequest.class))
			),
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", description = "transaction hash")))
			}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String send(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, BitcoinySendRequest bitcoinySendRequest) {
		Security.checkApiCallAllowed(request);

		if (bitcoinySendRequest.amount <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (bitcoinySendRequest.feePerByte != null && bitcoinySendRequest.feePerByte <= 0)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		Bitcoiny bitcoiny = getBitcoiny(blockchain);
		String receivingAddress = bitcoiny.normalizeAddress(bitcoinySendRequest.receivingAddress);

		if (!bitcoiny.isValidAddress(receivingAddress))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (!bitcoiny.isValidDeterministicKey(bitcoinySendRequest.xprv58))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		BitcoinySignedTransaction spendTransaction = bitcoiny.buildSpendTransaction(bitcoinySendRequest.xprv58,
				receivingAddress,
				bitcoinySendRequest.amount,
				bitcoinySendRequest.feePerByte);

		if (spendTransaction == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE);

		try {
			bitcoiny.broadcastTransaction(spendTransaction);
		} catch (ForeignBlockchainException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
		}

		return spendTransaction.getTxHash();
	}

	@GET
	@Path("/serverinfos")
	@Operation(
			summary = "Returns current server configuration",
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerConfigurationInfo.class)))
			}
	)
	public ServerConfigurationInfo getServerConfiguration(@PathParam("blockchain") String blockchain) {
		return CrossChainUtils.buildServerConfigurationInfo(getBitcoiny(blockchain));
	}

	@GET
	@Path("/serverconnectionhistory")
	@Operation(
			summary = "Returns server connection history",
			responses = {
					@ApiResponse(content = @Content(array = @ArraySchema(schema = @Schema(implementation = ServerConnectionInfo.class))))
			}
	)
	public List<ServerConnectionInfo> getServerConnectionHistory(@PathParam("blockchain") String blockchain) {
		return CrossChainUtils.buildServerConnectionHistory(getBitcoiny(blockchain));
	}

	@POST
	@Path("/addserver")
	@Operation(
			summary = "Add server to list of servers and persist it in settings",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))
			),
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "string")))
			}
	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String addServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			ElectrumX.Server server = new ElectrumX.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			return Boolean.toString(CrossChainUtils.addServer(getBitcoiny(blockchain), server));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/removeserver")
	@Operation(
			summary = "Remove server from list of servers and persist the override in settings",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))
			),
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "string")))
			}
	)
	@ApiErrors({ApiError.INVALID_DATA})
	@SecurityRequirement(name = "apiKey")
	public String removeServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		try {
			ElectrumX.Server server = new ElectrumX.Server(
					serverInfo.getHostName(),
					ChainableServer.ConnectionType.valueOf(serverInfo.getConnectionType()),
					serverInfo.getPort()
			);

			return Boolean.toString(CrossChainUtils.removeServer(getBitcoiny(blockchain), server));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
			return "false";
		}
	}

	@POST
	@Path("/setcurrentserver")
	@Operation(
			summary = "Set current server",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerInfo.class))
			),
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ServerConnectionInfo.class)))
			}
	)
	@ApiErrors({ApiError.INVALID_DATA, ApiError.UNAUTHORIZED})
	@SecurityRequirement(name = "apiKey")
	public ServerConnectionInfo setCurrentServer(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, ServerInfo serverInfo) {
		Security.checkApiCallAllowed(request);

		if (serverInfo.getConnectionType() == null || serverInfo.getHostName() == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		try {
			ServerConnectionInfo serverConnectionInfo = CrossChainUtils.setCurrentServer(getBitcoiny(blockchain), serverInfo);
			if (serverConnectionInfo != null)
				return serverConnectionInfo;

			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.UNAUTHORIZED);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		} catch (Exception e) {
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
			summary = "Returns fee per Kb",
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "number")))
			}
	)
	public String getFeePerKb(@PathParam("blockchain") String blockchain) {
		return String.valueOf(getBitcoiny(blockchain).getFeePerKb().value);
	}

	@POST
	@Path("/updatefeekb")
	@Operation(
			summary = "Sets fee per Kb",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number"))
			),
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number")))
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA})
	@SecurityRequirement(name = "apiKey")
	public String setFeePerKb(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, String fee) {
		Security.checkApiCallAllowed(request);

		try {
			return CrossChainUtils.setFeePerKb(getBitcoiny(blockchain), fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

	@GET
	@Path("/feerequired")
	@Operation(
			summary = "Returns total fee required for unlocking the foreign chain side of a trade",
			responses = {
					@ApiResponse(content = @Content(schema = @Schema(type = "number")))
			}
	)
	public String getFeeRequired(@PathParam("blockchain") String blockchain) {
		return String.valueOf(getBitcoiny(blockchain).getFeeRequired());
	}

	@POST
	@Path("/updatefeerequired")
	@Operation(
			summary = "Sets total fee required for unlocking the foreign chain side of a trade",
			requestBody = @RequestBody(
					required = true,
					content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number"))
			),
			responses = {
					@ApiResponse(content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "number")))
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA})
	@SecurityRequirement(name = "apiKey")
	public String setFeeRequired(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			@PathParam("blockchain") String blockchain, String fee) {
		Security.checkApiCallAllowed(request);

		try {
			return CrossChainUtils.setFeeRequired(getBitcoiny(blockchain), fee);
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		}
	}

}
