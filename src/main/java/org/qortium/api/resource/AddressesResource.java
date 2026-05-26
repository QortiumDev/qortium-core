package org.qortium.api.resource;

import com.google.common.primitives.Bytes;
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
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.*;
import org.qortium.api.model.ApiOnlineAccount;
import org.qortium.api.model.RewardShareKeyRequest;
import org.qortium.asset.Asset;
import org.qortium.controller.LiteNode;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.*;
import org.qortium.data.network.OnlineAccountData;
import org.qortium.data.network.OnlineAccountLevel;
import org.qortium.data.transaction.PublicizeTransactionData;
import org.qortium.data.transaction.RewardShareTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transaction.PublicizeTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.PublicizeTransactionTransformer;
import org.qortium.transform.transaction.RewardShareTransactionTransformer;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.transform.transaction.TransferPrivsTransactionTransformer;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Path("/addresses")
@Tag(name = "Addresses")
public class AddressesResource {

	@Context
	HttpServletRequest request;
	
	@GET
	@Path("/{address}")
	@Operation(
		summary = "Return general account information for the given address",
		responses = {
			@ApiResponse(
				description = "general account information",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AccountData.class))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.NO_REPLY, ApiError.LITE_DATA_CONFLICT, ApiError.REPOSITORY_ISSUE})
	public AccountData getAccountInfo(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (Settings.getInstance().isLite()) {
			LiteNode.LiteDataResult<AccountData> result = LiteNode.getInstance().fetchAccountDataResult(address);
			switch (result.getStatus()) {
				case AGREED:
					return result.getValue();

				case UNKNOWN:
					return new AccountData(address);

				case CONFLICTED:
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.LITE_DATA_CONFLICT, "Conflicting lite peer account data");

				case UNAVAILABLE:
				default:
					throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.NO_REPLY, "No lite peer account data available");
			}
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);
			// Valid addresses can receive funds before their public key is known on chain.
			if (accountData == null)
				accountData = new AccountData(address);

			int currentHeight = repository.getBlockRepository().getBlockchainHeight();
			AccountTrustSnapshotData trustSnapshot = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshot(address, AccountRatingCategory.SUBJECT);
			AccountTrustStatus trustStatus = trustSnapshot == null
					? AccountTrustStatus.UNVERIFIED
					: trustSnapshot.getMappedTrustStatus();
			accountData.setTrustSnapshot(trustSnapshot,
					AccountTrustPolicy.getVoteWeightPercent(repository, currentHeight, trustStatus));

			return accountData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/validate/{address}")
	@Operation(
		summary = "Validates the given address",
		description = "Returns true/false.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "boolean"))
			)
		}
	)
	public boolean validate(@PathParam("address") String address) {
		return Crypto.isValidAddress(address);
	}

	@GET
	@Path("/online")
	@Operation(
		summary = "Return currently 'online' accounts",
		responses = {
			@ApiResponse(
				description = "online accounts",
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ApiOnlineAccount.class)))
			)
		}
	)
	@ApiErrors({ApiError.PUBLIC_KEY_NOT_FOUND, ApiError.REPOSITORY_ISSUE})
	public List<ApiOnlineAccount> getOnlineAccounts() {
		List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts();

		// Map OnlineAccountData entries to OnlineAccount via reward-share data
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<ApiOnlineAccount> apiOnlineAccounts = new ArrayList<>();

			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(onlineAccountData.getPublicKey());
				if (rewardShareData == null)
					// This shouldn't happen?
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.PUBLIC_KEY_NOT_FOUND);

				apiOnlineAccounts.add(new ApiOnlineAccount(onlineAccountData.getTimestamp(), onlineAccountData.getSignature(), onlineAccountData.getPublicKey(),
						rewardShareData.getMintingAccount(), rewardShareData.getRecipient()));
			}

			return apiOnlineAccounts;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/online/levels")
	@Operation(
			summary = "Return currently 'online' accounts counts, grouped by level",
			responses = {
					@ApiResponse(
							description = "online accounts",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = ApiOnlineAccount.class)))
					)
			}
	)
	@ApiErrors({ApiError.PUBLIC_KEY_NOT_FOUND, ApiError.REPOSITORY_ISSUE})
	public List<OnlineAccountLevel> getOnlineAccountsByLevel() {
		List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<OnlineAccountLevel> onlineAccountLevels = new ArrayList<>();

			// Prepopulate all levels
			for (int i=0; i<=10; i++)
				onlineAccountLevels.add(new OnlineAccountLevel(i, 0));

			for (OnlineAccountData onlineAccountData : onlineAccounts) {
				try {
					final Integer minterLevel = Account.getRewardShareEffectiveMintingLevelIfMinting(repository, onlineAccountData.getPublicKey());
					if (minterLevel == null)
						continue;

					OnlineAccountLevel onlineAccountLevel = onlineAccountLevels.stream()
							.filter(a -> a.getLevel() == minterLevel)
							.findFirst().orElse(null);

					// Note: I don't think we can use the level as the List index here because there will be gaps.
					// So we are forced to manually look up the existing item each time.
					// There's probably a nice shorthand java way of doing this, but this approach gets the same result.

					if (onlineAccountLevel == null) {
						// No entry exists for this level yet, so create one
						onlineAccountLevel = new OnlineAccountLevel(minterLevel, 1);
						onlineAccountLevels.add(onlineAccountLevel);
					}
					else {
						// Already exists - so increment the count
						int existingCount = onlineAccountLevel.getCount();
						onlineAccountLevel.setCount(++existingCount);

						// Then replace the existing item
						int index = onlineAccountLevels.indexOf(onlineAccountLevel);
						onlineAccountLevels.set(index, onlineAccountLevel);
					}

				} catch (DataException e) {
                }
			}

			// Sort by level
			onlineAccountLevels.sort(Comparator.comparingInt(OnlineAccountLevel::getLevel));

			return onlineAccountLevels;

		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/balance/{address}")
	@Operation(
		summary = "Returns account balance",
		description = "Returns account's native asset balance, or of other specified asset",
		responses = {
			@ApiResponse(
				description = "the balance",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string", format = "number"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.INVALID_HEIGHT, ApiError.NO_REPLY, ApiError.LITE_DATA_CONFLICT, ApiError.REPOSITORY_ISSUE})
	public BigDecimal getBalance(@PathParam("address") String address,
			@QueryParam("assetId") Long assetId) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			if (assetId == null)
				assetId = Asset.NATIVE;
			else if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (Settings.getInstance().isLite()) {
				LiteNode.LiteDataResult<AccountBalanceData> result = LiteNode.getInstance().fetchAccountBalanceResult(address, assetId);
				switch (result.getStatus()) {
					case AGREED:
						return Amounts.toBigDecimal(result.getValue().getBalance());

					case UNKNOWN:
						return Amounts.toBigDecimal(0L);

					case CONFLICTED:
						throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.LITE_DATA_CONFLICT, "Conflicting lite peer balance data");

					case UNAVAILABLE:
					default:
						throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.NO_REPLY, "No lite peer balance data available");
				}
			}

			Account account = new Account(repository, address);
			return Amounts.toBigDecimal(account.getConfirmedBalance(assetId));
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/publickey/{address}")
	@Operation(
		summary = "Get public key of address",
		description = "Returns the base58-encoded account public key of the given address, or \"false\" if address not known or has no public key.",
		responses = {
			@ApiResponse(
				description = "the public key",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public String getPublicKey(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null)
				return "false";

			byte[] publicKey = accountData.getPublicKey();
			if (publicKey == null)
				return "false";

			return Base58.encode(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/convert/{publickey}")
	@Operation(
		summary = "Convert public key into address",
		description = "Returns account address based on supplied public key. Expects base58-encoded, 32-byte public key.",
		responses = {
			@ApiResponse(
				description = "the address",
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PUBLIC_KEY, ApiError.REPOSITORY_ISSUE})
	public String fromPublicKey(@PathParam("publickey") String publicKey58) {
		// Decode public key
		byte[] publicKey;
		try {
			publicKey = Base58.decode(publicKey58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY, e);
		}

		// Correct size for public key?
		if (publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return Crypto.toAddress(publicKey);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/rewardshares")
	@Operation(
		summary = "List reward-share relationships",
		description = "Returns self-share minting-key records and payout reward-share records.",
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = RewardShareData.class)))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<RewardShareData> getRewardShares(@QueryParam("minters") List<String> mintingAccounts,
			@QueryParam("recipients") List<String> recipientAccounts,
			@QueryParam("involving") List<String> addresses,
			@Parameter(
			ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getAccountRepository().findRewardShares(mintingAccounts, recipientAccounts, addresses, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/rewardsharekey")
	@Operation(
		summary = "Calculate reward-share record private key",
		description = "Calculates the private key for a reward-share record. Only self-share keys can be added to BlockMinter; non-self reward shares are payout records.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareKeyRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(type = "string"))
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_PUBLIC_KEY, ApiError.REPOSITORY_ISSUE})
	public String calculateRewardShareKey(RewardShareKeyRequest rewardShareKeyRequest) {
		byte[] mintingPrivateKey = rewardShareKeyRequest.mintingAccountPrivateKey;
		byte[] recipientPublicKey = rewardShareKeyRequest.recipientAccountPublicKey;

		if (mintingPrivateKey == null || mintingPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		if (recipientPublicKey == null || recipientPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PUBLIC_KEY);

		PrivateKeyAccount mintingAccount = new PrivateKeyAccount(null, mintingPrivateKey);

		byte[] rewardSharePrivateKey = mintingAccount.getRewardSharePrivateKey(recipientPublicKey);

		return Base58.encode(rewardSharePrivateKey);
	}

	@POST
	@Path("/rewardshare")
	@Operation(
		summary = "Build raw, unsigned, REWARD_SHARE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RewardShareTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, REWARD_SHARE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String rewardShare(RewardShareTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RewardShareTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/transferprivs")
	@Operation(
		summary = "Build raw, unsigned, TRANSFER_PRIVS transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TransferPrivsTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, TRANSFER_PRIVS transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String transferPrivs(TransferPrivsTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = TransferPrivsTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/publicize")
	@Operation(
		summary = "Build raw, unsigned, PUBLICIZE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PublicizeTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, PUBLICIZE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.NON_PRODUCTION, ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	public String publicize(PublicizeTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK && result != ValidationResult.INSUFFICIENT_FEE)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = PublicizeTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/publicize/compute")
	@Operation(
		summary = "Compute nonce for raw, unsigned PUBLICIZE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, unsigned PUBLICIZE transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, PUBLICIZE transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.INVALID_DATA, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String computePublicize(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String rawBytes58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			// We're expecting unsigned transaction, so append empty signature prior to decoding
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			if (transactionData.getType() != TransactionType.PUBLICIZE)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			PublicizeTransaction publicizeTransaction = (PublicizeTransaction) Transaction.fromData(repository, transactionData);

			// Quicker validity check first before we compute nonce
			ValidationResult result = publicizeTransaction.isValid();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			publicizeTransaction.computeNonce();

			// Re-check, but ignores signature
			result = publicizeTransaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			// Strip zeroed signature
			transactionData.setSignature(null);

			byte[] bytes = PublicizeTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/sponsorship/{address}")
	@Operation(
			summary = "Returns sponsorship statistics for an account",
			description = "Returns sponsorship statistics for an account, excluding the recipients that get real reward shares",
			responses = {
					@ApiResponse(
							description = "the statistics",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SponsorshipReport.class))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,  ApiError.REPOSITORY_ISSUE})
	public SponsorshipReport getSponsorshipReport(
			@PathParam("address") String address,
			@QueryParam(("realRewardShareRecipient")) String[] realRewardShareRecipients) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			SponsorshipReport report = repository.getAccountRepository().getSponsorshipReport(address, realRewardShareRecipients);
			// Not found?
			if (report == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			return report;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/sponsorship/{address}/sponsor")
	@Operation(
			summary = "Returns sponsorship statistics for an account's sponsor",
			description = "Returns sponsorship statistics for an account's sponsor, excluding the recipients that get real reward shares",
			responses = {
					@ApiResponse(
							description = "the statistics",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SponsorshipReport.class))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,  ApiError.REPOSITORY_ISSUE})
	public SponsorshipReport getSponsorshipReportForSponsor(
			@PathParam("address") String address,
			@QueryParam("realRewardShareRecipient") String[] realRewardShareRecipients) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {

			// get sponsor
			Optional<String> sponsor = repository.getAccountRepository().getSponsor(address);

			// if there is not sponsor, throw error
			if(sponsor.isEmpty()) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			// get report for sponsor
			SponsorshipReport report = repository.getAccountRepository().getSponsorshipReport(sponsor.get(), realRewardShareRecipients);

			// Not found?
			if (report == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			return report;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/mintership/{address}")
	@Operation(
			summary = "Returns mintership statistics for an account",
			description = "Returns mintership statistics for an account",
			responses = {
					@ApiResponse(
							description = "the statistics",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = MintershipReport.class))
					)
			}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN,  ApiError.REPOSITORY_ISSUE})
	public MintershipReport getMintershipReport(@PathParam("address") String address,
												@QueryParam("realRewardShareRecipient") String[] realRewardShareRecipients ) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {

			// get sponsorship report for minter, fetch a list of one minter
			SponsorshipReport report = repository.getAccountRepository().getMintershipReport(address, account -> List.of(account));

			// Not found?
			if (report == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			// since the report is for one minter, must get sponsee count separately
			int sponseeCount = repository.getAccountRepository().getSponseeAddresses(address, realRewardShareRecipients).size();

			// since the report is for one minter, must get the first name from a array of names that should be size 1
			String name = report.getNames().length > 0 ? report.getNames()[0] : null;

			// transform sponsorship report to mintership report
			MintershipReport mintershipReport
				= new MintershipReport(
					report.getAddress(),
					report.getLevel(),
					report.getBlocksMinted(),
					report.isTransfer(),
					name,
					sponseeCount,
					report.getAvgBalance(),
					report.getArbitraryCount(),
					report.getTransferAssetCount(),
					report.getTransferPrivsCount(),
					report.getSellCount(),
					report.getSellAmount(),
					report.getBuyCount(),
					report.getBuyAmount()
			);

			return mintershipReport;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/levels/{minLevel}")
	@Operation(
			summary = "Return accounts with levels greater than or equal to input",
			responses = {
					@ApiResponse(
							description = "online accounts",
							content = @Content(mediaType = MediaType.APPLICATION_JSON, array = @ArraySchema(schema = @Schema(implementation = AddressLevelPairing.class)))
					)
			}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})

	public List<AddressLevelPairing> getAddressLevelPairings(@PathParam("minLevel") int minLevel) {

		try (final Repository repository = RepositoryManager.getRepository()) {

			// get the level address pairings
			List<AddressLevelPairing> pairings = repository.getAccountRepository().getAddressLevelPairings(minLevel);

			return pairings;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}
}
