package org.qortium.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortium.api.ApiError;
import org.qortium.api.ApiErrors;
import org.qortium.api.ApiException;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.model.GroupKickInfo;
import org.qortium.api.model.GroupMembers;
import org.qortium.api.model.GroupMembers.MemberInfo;
import org.qortium.api.model.GroupWithJoinRequests;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.*;
import org.qortium.data.transaction.*;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.*;
import org.qortium.utils.Base58;
import org.qortium.utils.Groups;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Path("/groups")
@Tag(name = "Groups")
public class GroupsResource {

	@Context
	HttpServletRequest request;

	private enum GroupSearchVisibility {
		ALL(null),
		OPEN(true),
		CLOSED(false);

		private final Boolean isOpen;

		GroupSearchVisibility(Boolean isOpen) {
			this.isOpen = isOpen;
		}

		private Boolean isOpen() {
			return this.isOpen;
		}
	}

	@GET
	@Operation(
		summary = "List all groups",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getAllGroups(@Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> allGroupData = repository.getGroupRepository().getAllGroups(limit, offset, reverse);
			populateGroupApiFields(repository, allGroupData);
			return allGroupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/search")
	@Operation(
		summary = "Search groups",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<GroupData> searchGroups(
			@Parameter(description = "Search query for group name or description") @QueryParam("query") String query,
			@Parameter(description = "Prefix only (if true, only the beginning of fields are matched)") @QueryParam("prefixOnly") Boolean prefixOnly,
			@Parameter(description = "Visibility filter: ALL, OPEN, or CLOSED") @QueryParam("visibility") String visibility,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		GroupSearchVisibility searchVisibility = parseGroupSearchVisibility(visibility);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> groups = repository.getGroupRepository().searchGroups(query, Boolean.TRUE.equals(prefixOnly),
					searchVisibility.isOpen(), limit, offset, reverse);
			populateGroupApiFields(repository, groups);
			return groups;
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	private GroupSearchVisibility parseGroupSearchVisibility(String visibility) {
		if (visibility == null || visibility.trim().isEmpty())
			return GroupSearchVisibility.ALL;

		try {
			return GroupSearchVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
					"Visibility must be ALL, OPEN or CLOSED");
		}
	}

	private void populateGroupApiFields(Repository repository, List<GroupData> groups) {
		groups.forEach(groupData -> {
			try {
				groupData.memberCount = repository.getGroupRepository().countGroupMembers(groupData.getGroupId());
			} catch (DataException e) {
				// Exclude memberCount for this group
			}
		});

		try {
			List<String> owners = groups.stream().map(GroupData::getOwner).distinct().collect(Collectors.toList());
			Map<String, String> primaryNamesByOwner = repository.getNameRepository().getPrimaryNamesByOwners(owners);
			groups.forEach(groupData -> groupData.setOwnerPrimaryName(primaryNamesByOwner.get(groupData.getOwner())));
		} catch (DataException e) {
			// Leave ownerPrimaryName null
		}

		try {
			int nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			List<Integer> mintingGroupIds = Groups.getGroupIdsToMint(BlockChain.getInstance(), nextHeight);
			groups.forEach(groupData -> groupData.setIsMintingGroup(mintingGroupIds.contains(groupData.getGroupId())));
		} catch (DataException e) {
			// Leave isMintingGroup null
		}
	}

	@GET
	@Path("/owner/{address}")
	@Operation(
		summary = "List all groups owned by address",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getGroupsByOwner(@PathParam("address") String owner) {
		if (!Crypto.isValidAddress(owner))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> groups = repository.getGroupRepository().getGroupsByOwner(owner);
			populateGroupApiFields(repository, groups);
			return groups;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/member/{address}")
	@Operation(
		summary = "List all groups where address is a member",
		description = "By default returns groups where the address is a member. Use adminOnly=true to return only groups where the address is an admin. Use ownerOnly=true to return only groups where the address is the owner.",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupData> getGroupsWithMember(
			@PathParam("address") String member,
			@Parameter(description = "If true, return only groups where the address is an admin") @QueryParam("adminOnly") Boolean adminOnly,
			@Parameter(description = "If true, return only groups where the address is the owner") @QueryParam("ownerOnly") Boolean ownerOnly) {
		if (!Crypto.isValidAddress(member))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> allGroupData;
			if (Boolean.TRUE.equals(ownerOnly)) {
				allGroupData = repository.getGroupRepository().getGroupsByOwner(member);
			} else if (Boolean.TRUE.equals(adminOnly)) {
				allGroupData = repository.getGroupRepository().getGroupsByAdmin(member);
			} else {
				allGroupData = repository.getGroupRepository().getGroupsWithMember(member);
			}
			populateGroupApiFields(repository, allGroupData);

			return allGroupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/kicks/member")
	@Operation(
		summary = "List group kicks for a member",
		description = "Returns all kick transactions where the given address was kicked from a group. Only confirmed kicks are returned.",
		responses = {
			@ApiResponse(
				description = "list of kick summaries (member, groupId, reason, timestamp)",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupKickInfo.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
	public List<GroupKickInfo> getGroupKicks(
			@Parameter(description = "Address of the kicked member", required = true) @QueryParam("address") String address,
			@Parameter(description = "Optional group ID to filter by") @QueryParam("groupId") Integer groupId,
			@Parameter(description = "Only return kicks with timestamp strictly before this (ms since epoch)") @QueryParam("before") Long before,
			@Parameter(description = "Only return kicks with timestamp strictly after this (ms since epoch)") @QueryParam("after") Long after,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
		if (address == null || address.isEmpty())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
		if (before != null && before < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		if (after != null && after < 1500000000000L)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupKickSummaryData> list = repository.getTransactionRepository().getGroupKicks(address, groupId, before, after, limit, offset, reverse);
			return list.stream()
					.map(k -> new GroupKickInfo(k.getMember(), k.getGroupId(), k.getReason(), k.getTimestamp()))
					.collect(Collectors.toList());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/{groupid}")
	@Operation(
		summary = "Info on group",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(implementation = GroupData.class)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public GroupData getGroupData(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
			if (groupData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.GROUP_UNKNOWN);

			populateGroupApiFields(repository, Collections.singletonList(groupData));
			return groupData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/members/{groupid}")
	@Operation(
		summary = "List group members",
		responses = {
			@ApiResponse(
				description = "group info",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					schema = @Schema(implementation = GroupMembers.class)
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public GroupMembers getGroup(@PathParam("groupid") int groupId, @QueryParam("onlyAdmins") Boolean onlyAdmins,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit, @Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref="reverse") @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getGroupRepository().groupExists(groupId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.GROUP_UNKNOWN);

			int adminCount = repository.getGroupRepository().countGroupAdmins(groupId);
			int memberCount = repository.getGroupRepository().countGroupMembers(groupId);

			if (onlyAdmins != null && onlyAdmins) {
				// Shortcut
				List<GroupAdminData> admins = repository.getGroupRepository().getGroupAdmins(groupId, limit, offset, reverse);

				// Convert form
				List<MemberInfo> membersInfo = admins.stream().map(admin -> new MemberInfo(admin.getAdmin(), null, true)).collect(Collectors.toList());

				try {
					List<String> addresses = membersInfo.stream().map(m -> m.member).collect(Collectors.toList());
					Map<String, String> primaryNames = repository.getNameRepository().getPrimaryNamesByOwners(addresses);
					membersInfo.forEach(m -> m.setPrimaryName(primaryNames.get(m.member)));
				} catch (DataException e) {
					// Leave primaryName null
				}

				return new GroupMembers(membersInfo, memberCount, adminCount);
			}

			final List<GroupAdminData> admins = repository.getGroupRepository().getGroupAdmins(groupId, limit, offset, reverse);

			List<GroupMemberData> members = repository.getGroupRepository().getGroupMembers(groupId, limit, offset, reverse);

			// Convert form
			Predicate<GroupMemberData> memberIsAdmin = member -> admins.stream().anyMatch(admin -> admin.getAdmin().equals(member.getMember()));
			List<MemberInfo> membersInfo = members.stream().map(member -> new MemberInfo(member.getMember(), member.getJoined(), memberIsAdmin.test(member))).collect(Collectors.toList());

			try {
				List<String> addresses = membersInfo.stream().map(m -> m.member).collect(Collectors.toList());
				Map<String, String> primaryNames = repository.getNameRepository().getPrimaryNamesByOwners(addresses);
				membersInfo.forEach(m -> m.setPrimaryName(primaryNames.get(m.member)));
			} catch (DataException e) {
				// Leave primaryName null
			}

			return new GroupMembers(membersInfo, memberCount, adminCount);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/create")
	@Operation(
		summary = "Build raw, unsigned, CREATE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CreateGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CREATE_GROUP transaction encoded in Base58",
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
	public String createGroup(CreateGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CreateGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/update")
	@Operation(
		summary = "Build raw, unsigned, UPDATE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = UpdateGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, UPDATE_GROUP transaction encoded in Base58",
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
	public String updateGroup(UpdateGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = UpdateGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/addadmin")
	@Operation(
		summary = "Build raw, unsigned, ADD_GROUP_ADMIN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = AddGroupAdminTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, ADD_GROUP_ADMIN transaction encoded in Base58",
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
	public String addGroupAdmin(AddGroupAdminTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = AddGroupAdminTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/removeadmin")
	@Operation(
		summary = "Build raw, unsigned, REMOVE_GROUP_ADMIN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = RemoveGroupAdminTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, REMOVE_GROUP_ADMIN transaction encoded in Base58",
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
	public String removeGroupAdmin(RemoveGroupAdminTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = RemoveGroupAdminTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/ban")
	@Operation(
		summary = "Build raw, unsigned, GROUP_BAN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupBanTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_BAN transaction encoded in Base58",
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
	public String groupBan(GroupBanTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupBanTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/ban/cancel")
	@Operation(
		summary = "Build raw, unsigned, CANCEL_GROUP_BAN transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CancelGroupBanTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CANCEL_GROUP_BAN transaction encoded in Base58",
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
	public String cancelGroupBan(CancelGroupBanTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CancelGroupBanTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/kick")
	@Operation(
		summary = "Build raw, unsigned, GROUP_KICK transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupKickTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_KICK transaction encoded in Base58",
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
	public String groupKick(GroupKickTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupKickTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/invite")
	@Operation(
		summary = "Build raw, unsigned, GROUP_INVITE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupInviteTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_INVITE transaction encoded in Base58",
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
	public String groupInvite(GroupInviteTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupInviteTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/invite/cancel")
	@Operation(
		summary = "Build raw, unsigned, CANCEL_GROUP_INVITE transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CancelGroupInviteTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CANCEL_GROUP_INVITE transaction encoded in Base58",
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
	public String cancelGroupInvite(CancelGroupInviteTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CancelGroupInviteTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/join")
	@Operation(
		summary = "Build raw, unsigned, JOIN_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = JoinGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, JOIN_GROUP transaction encoded in Base58",
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
	public String joinGroup(JoinGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = JoinGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/leave")
	@Operation(
		summary = "Build raw, unsigned, LEAVE_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = LeaveGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, LEAVE_GROUP transaction encoded in Base58",
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
	public String leaveGroup(LeaveGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = LeaveGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/invites/{address}")
	@Operation(
		summary = "Pending group invites",
		responses = {
			@ApiResponse(
				description = "group invite",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupInviteData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupInviteData> getInvitesByInvitee(@PathParam("address") String invitee) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getInvitesByInvitee(invitee);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/invites/group/{groupid}")
	@Operation(
		summary = "Pending group invites",
		responses = {
			@ApiResponse(
				description = "group invite",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupInviteData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupInviteData> getInvitesByGroupId(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getInvitesByGroupId(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/joinrequests/{groupid}")
	@Operation(
		summary = "Pending group join requests",
		responses = {
			@ApiResponse(
				description = "group join requests",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupJoinRequestData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupJoinRequestData> getJoinRequests(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getGroupJoinRequests(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/joinrequests/address/{address}")
	@Operation(
		summary = "Pending group join requests (by joiner address)",
		responses = {
			@ApiResponse(
				description = "group join requests",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupJoinRequestData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupJoinRequestData> getJoinRequestsByAddress(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getJoinRequestsByJoiner(address);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/joinrequests/admin/{address}")
	@Operation(
		summary = "Groups where address is admin, with pending join requests for each",
		description = "Returns all groups where the given address is an admin, and the pending join requests for each group.",
		responses = {
			@ApiResponse(
				description = "list of group with its join requests",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupWithJoinRequests.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.INVALID_ADDRESS, ApiError.REPOSITORY_ISSUE})
	public List<GroupWithJoinRequests> getAdminGroupsWithJoinRequests(@PathParam("address") String address) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<GroupData> groups = repository.getGroupRepository().getGroupsByAdmin(address);
			if (groups.isEmpty())
				return new ArrayList<>();

			try {
				List<String> owners = groups.stream().map(GroupData::getOwner).distinct().collect(Collectors.toList());
				Map<String, String> primaryNamesByOwner = repository.getNameRepository().getPrimaryNamesByOwners(owners);
				groups.forEach(g -> g.setOwnerPrimaryName(primaryNamesByOwner.get(g.getOwner())));
			} catch (DataException e) {
				// Leave ownerPrimaryName null
			}

			List<Integer> groupIds = groups.stream().map(GroupData::getGroupId).collect(Collectors.toList());
			List<GroupJoinRequestData> allJoinRequests = repository.getGroupRepository().getJoinRequestsByGroupIds(groupIds);
			Map<Integer, List<GroupJoinRequestData>> joinRequestsByGroupId = allJoinRequests.stream()
					.collect(Collectors.groupingBy(GroupJoinRequestData::getGroupId));

			List<GroupWithJoinRequests> result = new ArrayList<>(groups.size());
			for (GroupData group : groups) {
				List<GroupJoinRequestData> joinRequests = joinRequestsByGroupId.getOrDefault(group.getGroupId(), new ArrayList<>());
				result.add(new GroupWithJoinRequests(group, joinRequests));
			}
			return result;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/bans/{groupid}")
	@Operation(
		summary = "Current group join bans",
		responses = {
			@ApiResponse(
				description = "group bans",
				content = @Content(
					mediaType = MediaType.APPLICATION_JSON,
					array = @ArraySchema(schema = @Schema(implementation = GroupBanData.class))
				)
			)
		}
	)
	@ApiErrors({ApiError.REPOSITORY_ISSUE})
	public List<GroupBanData> getBans(@PathParam("groupid") int groupId) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			return repository.getGroupRepository().getGroupBans(groupId);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/approval")
	@Operation(
		summary = "Build raw, unsigned, GROUP_APPROVAL transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = GroupApprovalTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, GROUP_APPROVAL transaction encoded in Base58",
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
	public String groupApproval(GroupApprovalTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = GroupApprovalTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/setdefault")
	@Operation(
		summary = "Build raw, unsigned, SET_GROUP transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = SetGroupTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, SET_GROUP transaction encoded in Base58",
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
	public String setGroup(SetGroupTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = SetGroupTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
