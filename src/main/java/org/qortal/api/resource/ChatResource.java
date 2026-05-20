package org.qortal.api.resource;

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
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.PrivateGroupChatDecryptRequest;
import org.qortal.api.model.PrivateGroupChatDecryptResponse;
import org.qortal.api.model.PrivateGroupChatKeyAnnouncementRelayRequest;
import org.qortal.api.model.PrivateGroupChatKeyAnnouncementRelayResponse;
import org.qortal.api.model.PrivateGroupChatKeyRequestRequest;
import org.qortal.api.model.PrivateGroupChatKeyRequestResponse;
import org.qortal.api.model.PrivateGroupChatRotateRequest;
import org.qortal.api.model.PrivateGroupChatRotateResponse;
import org.qortal.api.model.PrivateGroupChatRotationRequestRequest;
import org.qortal.api.model.PrivateGroupChatRotationRequestResponse;
import org.qortal.api.model.PrivateGroupChatSendRequest;
import org.qortal.api.model.PrivateGroupChatSendResponse;
import org.qortal.chat.ChatService;
import org.qortal.chat.PrivateGroupChatService;
import org.qortal.crypto.Crypto;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.security.GeneralSecurityException;
import java.util.List;

import static org.qortal.data.chat.ChatMessage.Encoding;

@Path("/chat")
@Tag(name = "Chat")
public class ChatResource {

	@Context
	HttpServletRequest request;

	@GET
	@Path("/messages")
	@Operation(
		summary = "Find chat messages",
		description = "Returns CHAT messages that match criteria. Must provide EITHER 'txGroupId' OR two 'involving' addresses.",
		responses = {
			@ApiResponse(
				description = "CHAT messages",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ChatMessage.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<ChatMessage> searchChat(@QueryParam("before") Long before, @QueryParam("after") Long after,
			@QueryParam("txGroupId") Integer txGroupId,
			@QueryParam("involving") List<String> involvingAddresses,
			@QueryParam("chatreference") String chatReference,
			@QueryParam("haschatreference") Boolean hasChatReference,
			@QueryParam("sender") String sender,
			@QueryParam("encoding") Encoding encoding,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		// Check args meet expectations
		if ((txGroupId == null && involvingAddresses.size() != 2)
				|| (txGroupId != null && !involvingAddresses.isEmpty()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// Check any provided addresses are valid
		if (involvingAddresses.stream().anyMatch(address -> !Crypto.isValidAddress(address)))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] chatReferenceBytes = null;
		if (chatReference != null)
			chatReferenceBytes = Base58.decode(chatReference);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatStoreRepository().getMessagesMatchingCriteria(
					before,
					after,
					txGroupId,
					chatReferenceBytes,
					hasChatReference,
					involvingAddresses,
					sender,
					encoding,
					limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/messages/count")
	@Operation(
			summary = "Count chat messages",
			description = "Returns count of CHAT messages that match criteria. Must provide EITHER 'txGroupId' OR two 'involving' addresses.",
			responses = {
					@ApiResponse(
							description = "count of messages",
							content = @Content(
									mediaType = MediaType.TEXT_PLAIN,
									schema = @Schema(
											type = "integer"
									)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public int countChatMessages(@QueryParam("before") Long before, @QueryParam("after") Long after,
										@QueryParam("txGroupId") Integer txGroupId,
										@QueryParam("involving") List<String> involvingAddresses,
										@QueryParam("chatreference") String chatReference,
										@QueryParam("haschatreference") Boolean hasChatReference,
										@QueryParam("sender") String sender,
										@QueryParam("encoding") Encoding encoding,
										@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
										@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
										@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		// Check args meet expectations
		if ((txGroupId == null && involvingAddresses.size() != 2)
				|| (txGroupId != null && !involvingAddresses.isEmpty()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		// Check any provided addresses are valid
		if (involvingAddresses.stream().anyMatch(address -> !Crypto.isValidAddress(address)))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		byte[] chatReferenceBytes = null;
		if (chatReference != null)
			chatReferenceBytes = Base58.decode(chatReference);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatStoreRepository().countMessagesMatchingCriteria(
					before,
					after,
					txGroupId,
					chatReferenceBytes,
					hasChatReference,
					involvingAddresses,
					sender);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/message/{signature}")
	@Operation(
			summary = "Find chat message by signature",
			responses = {
					@ApiResponse(
							description = "CHAT message",
							content = @Content(
										schema = @Schema(
												implementation = ChatMessage.class
										)
							)
					)
			}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public ChatMessage getMessageBySignature(@PathParam("signature") String signature58, @QueryParam("encoding") Encoding encoding) {
		byte[] signature = Base58.decode(signature58);

		try (final Repository repository = RepositoryManager.getRepository()) {

			ChatTransactionData chatTransactionData = repository.getChatStoreRepository().fromSignature(signature);
			if (chatTransactionData == null) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, "Message not found");
			}

			return repository.getChatStoreRepository().toChatMessage(chatTransactionData, encoding);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/active/{address}")
	@Operation(
		summary = "Find active chats (group/direct) involving address",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = ActiveChats.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public ActiveChats getActiveChats(
		@PathParam("address") String address,
		@QueryParam("encoding") Encoding encoding,
		@QueryParam("haschatreference") Boolean hasChatReference
	) {
		if (address == null || !Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
	
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getChatStoreRepository().getActiveChats(address, encoding, hasChatReference);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/send")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Send encrypted private group chat message",
		description = "Creates and stores signed CHAT transactions for a Core-managed encrypted closed-group message. If the local node has no cached key for the current membership epoch, this also stores a key announcement first.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatSendRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "stored private group chat transaction signatures",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatSendResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatSendResponse sendPrivateGroupChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatSendRequest sendRequest) {
		Security.checkApiCallAllowed(request);

		if (sendRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (sendRequest.senderPrivateKey == null || sendRequest.senderPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.SendResult result = PrivateGroupChatService.getInstance().send(repository,
					sendRequest.senderPrivateKey, sendRequest.groupId, sendRequest.data, sendRequest.isText,
					sendRequest.chatReference);

			PrivateGroupChatSendResponse response = new PrivateGroupChatSendResponse();
			response.messageSignature = result.getMessageSignature();
			response.keyAnnouncementSignature = result.getKeyAnnouncementSignature();
			response.epochId = result.getEpochId();
			response.keyId = result.getKeyId();
			return response;
		} catch (PrivateGroupChatService.ValidationException e) {
			throw TransactionsResource.createTransactionInvalidException(request, e.getValidationResult());
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/decrypt")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Decrypt private group chat message",
		description = "Decrypts a stored private closed-group CHAT message using a locally cached group key.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatDecryptRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "decrypted private group chat message",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatDecryptResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSFORMATION_ERROR,
			ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatDecryptResponse decryptPrivateGroupChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatDecryptRequest decryptRequest) {
		Security.checkApiCallAllowed(request);

		if (decryptRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (decryptRequest.recipientPrivateKey == null || decryptRequest.recipientPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.DecryptResult result = PrivateGroupChatService.getInstance().decrypt(repository,
					decryptRequest.recipientPrivateKey, decryptRequest.messageSignature);

			PrivateGroupChatDecryptResponse response = new PrivateGroupChatDecryptResponse();
			response.data = result.getData();
			response.isText = result.isText();
			response.groupId = result.getGroupId();
			response.epochId = result.getEpochId();
			response.keyId = result.getKeyId();
			return response;
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/key-request")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Request private group chat key",
		description = "Publishes a signed private closed-group key request for the current membership epoch. The request can name a specific key id or ask for any usable current key.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatKeyRequestRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "stored private group chat key request signature",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatKeyRequestResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatKeyRequestResponse requestPrivateGroupChatKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatKeyRequestRequest keyRequest) {
		Security.checkApiCallAllowed(request);

		if (keyRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (keyRequest.requesterPrivateKey == null || keyRequest.requesterPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.KeyRequestResult result = PrivateGroupChatService.getInstance().requestKey(repository,
					keyRequest.requesterPrivateKey, keyRequest.groupId, keyRequest.keyId);

			PrivateGroupChatKeyRequestResponse response = new PrivateGroupChatKeyRequestResponse();
			response.requestSignature = result.getRequestSignature();
			response.epochId = result.getEpochId();
			response.keyId = result.getKeyId();
			return response;
		} catch (PrivateGroupChatService.ValidationException e) {
			throw TransactionsResource.createTransactionInvalidException(request, e.getValidationResult());
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/key-announcement/relay")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Relay private group chat key announcement",
		description = "Publishes a new CHAT transaction carrying a previously stored or cached signed private group key announcement for the current membership epoch. This relays the signed announcement envelope without exposing raw group keys.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatKeyAnnouncementRelayRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "stored relayed private group chat key announcement signature",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatKeyAnnouncementRelayResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatKeyAnnouncementRelayResponse relayPrivateGroupChatKeyAnnouncement(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatKeyAnnouncementRelayRequest relayRequest) {
		Security.checkApiCallAllowed(request);

		if (relayRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (relayRequest.relayerPrivateKey == null || relayRequest.relayerPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.KeyAnnouncementRelayResult result = PrivateGroupChatService.getInstance()
					.relayKeyAnnouncement(repository, relayRequest.relayerPrivateKey, relayRequest.groupId,
							relayRequest.epochId, relayRequest.keyId);

			PrivateGroupChatKeyAnnouncementRelayResponse response = new PrivateGroupChatKeyAnnouncementRelayResponse();
			response.announcementSignature = result.getAnnouncementSignature();
			response.epochId = result.getEpochId();
			response.keyId = result.getKeyId();
			return response;
		} catch (PrivateGroupChatService.ValidationException e) {
			throw TransactionsResource.createTransactionInvalidException(request, e.getValidationResult());
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/rotate")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Rotate local private group chat key",
		description = "Creates and stores a fresh signed private group key announcement for the current membership epoch. Future local sends use the newly announced key while older keys remain usable for decrypting older messages.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatRotateRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "fresh private group chat key announcement",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatRotateResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatRotateResponse rotatePrivateGroupChatKey(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatRotateRequest rotateRequest) {
		Security.checkApiCallAllowed(request);

		if (rotateRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (rotateRequest.rotatorPrivateKey == null || rotateRequest.rotatorPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.KeyRotationResult result = PrivateGroupChatService.getInstance().rotateKey(repository,
					rotateRequest.rotatorPrivateKey, rotateRequest.groupId);

			PrivateGroupChatRotateResponse response = new PrivateGroupChatRotateResponse();
			response.keyAnnouncementSignature = result.getKeyAnnouncementSignature();
			response.epochId = result.getEpochId();
			response.keyId = result.getKeyId();
			return response;
		} catch (PrivateGroupChatService.ValidationException e) {
			throw TransactionsResource.createTransactionInvalidException(request, e.getValidationResult());
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/private/group/rotation-request")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(
		summary = "Request private group chat key rotation",
		description = "Publishes a signed owner/admin request asking current members to stop using older keys for future sends in the current membership epoch.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PrivateGroupChatRotationRequestRequest.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "stored private group chat rotation request signature",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(
						implementation = PrivateGroupChatRotationRequestResponse.class
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_PRIVATE_KEY, ApiError.INVALID_DATA, ApiError.INVALID_CRITERIA, ApiError.TRANSACTION_INVALID,
			ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public PrivateGroupChatRotationRequestResponse requestPrivateGroupChatRotation(
			@HeaderParam(Security.API_KEY_HEADER) String apiKey,
			PrivateGroupChatRotationRequestRequest rotationRequest) {
		Security.checkApiCallAllowed(request);

		if (rotationRequest == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

		if (rotationRequest.requesterPrivateKey == null || rotationRequest.requesterPrivateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_PRIVATE_KEY);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateGroupChatService.RotationRequestResult result = PrivateGroupChatService.getInstance()
					.requestRotation(repository, rotationRequest.requesterPrivateKey, rotationRequest.groupId);

			PrivateGroupChatRotationRequestResponse response = new PrivateGroupChatRotationRequestResponse();
			response.requestSignature = result.getRequestSignature();
			response.epochId = result.getEpochId();
			return response;
		} catch (PrivateGroupChatService.ValidationException e) {
			throw TransactionsResource.createTransactionInvalidException(request, e.getValidationResult());
		} catch (PrivateGroupChatService.PrivateGroupChatException | GeneralSecurityException | IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}
	
	@POST
	@Operation(
		summary = "Build raw, unsigned, CHAT transaction",
		description = "Builds a raw, unsigned CHAT transaction but does NOT compute proof-of-work nonce. See POST /chat/compute.",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = ChatTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CHAT transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({ApiError.TRANSACTION_INVALID, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE})
	@SecurityRequirement(name = "apiKey")
	public String buildChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey, ChatTransactionData transactionData) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ValidationResult result = ChatService.getInstance().validateForBuild(repository, transactionData);
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = ChatTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/compute")
	@Operation(
		summary = "Compute nonce for raw, unsigned CHAT transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.TEXT_PLAIN,
				schema = @Schema(
					type = "string",
					description = "raw, unsigned CHAT transaction in base58 encoding",
					example = "raw transaction base58"
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CHAT transaction encoded in Base58",
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
	public String buildChat(@HeaderParam(Security.API_KEY_HEADER) String apiKey, String rawBytes58) {
		Security.checkApiCallAllowed(request);

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rawBytes = Base58.decode(rawBytes58);
			// We're expecting unsigned transaction, so append empty signature prior to decoding
			rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

			TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
			if (transactionData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			if (transactionData.getType() != TransactionType.CHAT)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);

			ChatTransactionData chatTransactionData = (ChatTransactionData) transactionData;

			ValidationResult result = ChatService.getInstance().validateForBuild(repository, chatTransactionData);
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			ChatService.getInstance().computeNonce(repository, chatTransactionData);

			result = ChatService.getInstance().validateForBuild(repository, chatTransactionData);
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			// Strip zeroed signature
			transactionData.setSignature(null);

			byte[] bytes = ChatTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
