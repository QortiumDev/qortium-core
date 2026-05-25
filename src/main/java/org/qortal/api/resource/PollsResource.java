package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.account.AccountTrustPolicy;
import org.qortal.account.AccountTrustWeight;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.PollVotes;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.CreatePollTransactionData;
import org.qortal.data.transaction.UpdatePollTransactionData;
import org.qortal.data.transaction.VoteOnPollTransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.PollVoteWeightData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.CreatePollTransactionTransformer;
import org.qortal.transform.transaction.UpdatePollTransactionTransformer;
import org.qortal.transform.transaction.VoteOnPollTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.voting.Poll;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/polls")
@Tag(name = "Polls")
public class PollsResource {
    @Context
    HttpServletRequest request;

    private enum PollSearchStatus {
            ALL(null),
            OPEN(false),
            CLOSED(true);

            private final Boolean isClosed;

            PollSearchStatus(Boolean isClosed) {
                    this.isClosed = isClosed;
            }

            private Boolean isClosed() {
                    return this.isClosed;
            }
    }

    @GET
    @Operation(
            summary = "List all polls",
            responses = {
                    @ApiResponse(
                            description = "poll info",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    array = @ArraySchema(schema = @Schema(implementation = PollData.class))
                            )
                    )
            }
    )
    @ApiErrors({ApiError.REPOSITORY_ISSUE})
    public List<PollData> getAllPolls(@Parameter(
            ref = "limit"
    ) @QueryParam("limit") Integer limit, @Parameter(
            ref = "offset"
    ) @QueryParam("offset") Integer offset, @Parameter(
            ref = "reverse"
    ) @QueryParam("reverse") Boolean reverse) {
            try (final Repository repository = RepositoryManager.getRepository()) {
		    List<PollData> allPollData = repository.getVotingRepository().getAllPolls(limit, offset, reverse);
		    return allPollData;
            } catch (DataException e) {
		    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    @GET
    @Path("/search")
    @Operation(
            summary = "Search polls",
            responses = {
                    @ApiResponse(
                            description = "poll info",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    array = @ArraySchema(schema = @Schema(implementation = PollData.class))
                            )
                    )
            }
    )
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.REPOSITORY_ISSUE})
    public List<PollData> searchPolls(
            @Parameter(description = "Search query for poll name or description") @QueryParam("query") String query,
            @Parameter(description = "Prefix only (if true, only the beginning of fields are matched)") @QueryParam("prefixOnly") Boolean prefixOnly,
            @Parameter(description = "Owner address filter") @QueryParam("owner") String owner,
            @Parameter(description = "Poll status filter: ALL, OPEN, or CLOSED") @QueryParam("status") String status,
            @Parameter(description = "Filter for polls with or without an end time") @QueryParam("hasEndTime") Boolean hasEndTime,
            @Parameter(description = "Minimum published timestamp") @QueryParam("fromTimestamp") Long fromTimestamp,
            @Parameter(description = "Maximum published timestamp") @QueryParam("toTimestamp") Long toTimestamp,
            @Parameter(ref = "limit") @QueryParam("limit") Integer limit,
            @Parameter(ref = "offset") @QueryParam("offset") Integer offset,
            @Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {
            PollSearchStatus searchStatus = parsePollSearchStatus(status);
            String ownerFilter = parsePollOwnerFilter(owner);

            if (fromTimestamp != null && toTimestamp != null && fromTimestamp > toTimestamp)
                    throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
                            "fromTimestamp must not be greater than toTimestamp");

            try (final Repository repository = RepositoryManager.getRepository()) {
                    long latestBlockTimestamp = repository.getBlockRepository().getLastBlock().getTimestamp();
                    return repository.getVotingRepository().searchPolls(query, Boolean.TRUE.equals(prefixOnly), ownerFilter,
                            searchStatus.isClosed(), hasEndTime, fromTimestamp, toTimestamp, latestBlockTimestamp,
                            limit, offset, reverse);
            } catch (ApiException e) {
                    throw e;
            } catch (DataException e) {
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    private PollSearchStatus parsePollSearchStatus(String status) {
            if (status == null || status.trim().isEmpty())
                    return PollSearchStatus.ALL;

            try {
                    return PollSearchStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                    throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
                            "Status must be ALL, OPEN or CLOSED");
            }
    }

    private String parsePollOwnerFilter(String owner) {
            if (owner == null || owner.trim().isEmpty())
                    return null;

            String trimmedOwner = owner.trim();
            if (!Crypto.isValidAddress(trimmedOwner))
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

            return trimmedOwner;
    }

    @GET
    @Path("/id/{pollId}")
    @Operation(
            summary = "Info on poll by ID",
            responses = {
                    @ApiResponse(
                            description = "poll info",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(implementation = PollData.class)
                            )
                    )
            }
    )
    @ApiErrors({ApiError.REPOSITORY_ISSUE})
    public PollData getPollDataById(@PathParam("pollId") int pollId) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                    PollData pollData = repository.getVotingRepository().fromPollId(pollId);
                    if (pollData == null)
                            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.POLL_NO_EXISTS);

                    return pollData;
            } catch (ApiException e) {
                    throw e;
            } catch (DataException e) {
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    @GET
    @Path("/{pollName}")
    @Operation(
            summary = "Info on poll",
            responses = {
                    @ApiResponse(
                            description = "poll info",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(implementation = PollData.class)
                            )
                    )
            }
    )
    @ApiErrors({ApiError.REPOSITORY_ISSUE})
    public PollData getPollData(@PathParam("pollName") String pollName) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                    PollData pollData = repository.getVotingRepository().fromPollName(pollName);
                    if (pollData == null)
                            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.POLL_NO_EXISTS);

                    return pollData;
            } catch (ApiException e) {
                    throw e;
            } catch (DataException e) {
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    @GET
    @Path("/votes/id/{pollId}")
    @Operation(
            summary = "Votes on poll by ID",
            responses = {
                    @ApiResponse(
                            description = "poll votes",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(implementation = PollVotes.class)
                            )
                    )
            }
    )
    @ApiErrors({ApiError.REPOSITORY_ISSUE})
    public PollVotes getPollVotesById(@PathParam("pollId") int pollId, @QueryParam("onlyCounts") Boolean onlyCounts) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                    PollData pollData = repository.getVotingRepository().fromPollId(pollId);
                    if (pollData == null)
                            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.POLL_NO_EXISTS);

                    return getPollVotes(repository, pollData, onlyCounts);
            } catch (ApiException e) {
                    throw e;
            } catch (DataException e) {
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    @GET
    @Path("/votes/{pollName}")
    @Operation(
            summary = "Votes on poll",
            responses = {
                    @ApiResponse(
                            description = "poll votes",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON,
                                    schema = @Schema(implementation = PollVotes.class)
                            )
                    )
            }
    )
    @ApiErrors({ApiError.REPOSITORY_ISSUE})
    public PollVotes getPollVotes(@PathParam("pollName") String pollName, @QueryParam("onlyCounts") Boolean onlyCounts) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                    PollData pollData = repository.getVotingRepository().fromPollName(pollName);
                    if (pollData == null)
                            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.POLL_NO_EXISTS);

                    return getPollVotes(repository, pollData, onlyCounts);
            } catch (ApiException e) {
                    throw e;
            } catch (DataException e) {
                    throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
            }
    }

    @POST
    @Path("/create")
    @Operation(
            summary = "Build raw, unsigned, CREATE_POLL transaction",
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CreatePollTransactionData.class
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            description = "raw, unsigned, CREATE_POLL transaction encoded in Base58",
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
    public String CreatePoll(CreatePollTransactionData transactionData) {
        if (Settings.getInstance().isApiRestricted())
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

        try (final Repository repository = RepositoryManager.getRepository()) {
            Transaction transaction = Transaction.fromData(repository, transactionData);

            Transaction.ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
            if (result != Transaction.ValidationResult.OK)
                throw TransactionsResource.createTransactionInvalidException(request, result);

            byte[] bytes = CreatePollTransactionTransformer.toBytes(transactionData);
            return Base58.encode(bytes);
        } catch (TransformationException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
        } catch (DataException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
    }

    @POST
    @Path("/vote")
    @Operation(
            summary = "Build raw, unsigned, VOTE_ON_POLL transaction",
            requestBody = @RequestBody(
                    required = true,
                    description = "Vote on a poll by stable pollId. optionIndex 0 removes the active vote; real poll options start at 1.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = VoteOnPollTransactionData.class
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            description = "raw, unsigned, VOTE_ON_POLL transaction encoded in Base58",
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
    public String VoteOnPoll(VoteOnPollTransactionData transactionData) {
        if (Settings.getInstance().isApiRestricted())
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

        try (final Repository repository = RepositoryManager.getRepository()) {
            Transaction transaction = Transaction.fromData(repository, transactionData);

            Transaction.ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
            if (result != Transaction.ValidationResult.OK)
                throw TransactionsResource.createTransactionInvalidException(request, result);

            byte[] bytes = VoteOnPollTransactionTransformer.toBytes(transactionData);
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
            summary = "Build raw, unsigned, UPDATE_POLL transaction",
            requestBody = @RequestBody(
                    required = true,
                    description = "Update a poll by stable pollId. Full edits are allowed before active votes exist; polls with active votes can only extend an existing future end time, and closed polls cannot be updated.",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = UpdatePollTransactionData.class
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            description = "raw, unsigned, UPDATE_POLL transaction encoded in Base58",
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
    public String UpdatePoll(UpdatePollTransactionData transactionData) {
        if (Settings.getInstance().isApiRestricted())
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

        try (final Repository repository = RepositoryManager.getRepository()) {
            Transaction transaction = Transaction.fromData(repository, transactionData);

            Transaction.ValidationResult result = transaction.isValidUnconfirmedForUnsignedBuild();
            if (result != Transaction.ValidationResult.OK)
                throw TransactionsResource.createTransactionInvalidException(request, result);

            byte[] bytes = UpdatePollTransactionTransformer.toBytes(transactionData);
            return Base58.encode(bytes);
        } catch (TransformationException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
        } catch (DataException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
    }

    private PollVotes getPollVotes(Repository repository, PollData pollData, Boolean onlyCounts) throws DataException {
            boolean countsOnly = onlyCounts != null && onlyCounts;
            long latestBlockTimestamp = repository.getBlockRepository().getLastBlock().getTimestamp();
            PollDataWithVotes frozenPollResults = getFrozenPollResultsForClosedPoll(repository, pollData, latestBlockTimestamp);
            if (frozenPollResults != null)
                    return buildFrozenPollVotesResponse(repository, pollData, countsOnly, frozenPollResults);

            List<VoteOnPollData> votes = repository.getVotingRepository().getVotes(pollData.getPollId());

            // Initialize map for counting votes
            Map<String, Integer> voteCountMap = new HashMap<>();
            for (PollOptionData optionData : pollData.getPollOptions()) {
                    voteCountMap.put(optionData.getOptionName(), 0);
            }
            // Initialize map for counting vote weights
            Map<String, Integer> voteWeightMap = new HashMap<>();
            Map<String, Integer> rawVoteWeightMap = new HashMap<>();
            for (PollOptionData optionData : pollData.getPollOptions()) {
                    voteWeightMap.put(optionData.getOptionName(), 0);
                    rawVoteWeightMap.put(optionData.getOptionName(), 0);
            }

            List<PollVotes.VoteDetail> voteDetails = countsOnly ? null : new ArrayList<>();
            int totalVotes = 0;
            int totalWeight = 0;
            int rawTotalWeight = 0;
            int currentHeight = repository.getBlockRepository().getBlockchainHeight();
            int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, currentHeight);
            for (VoteOnPollData vote : votes) {
                    String voter = Crypto.toAddress(vote.getVoterPublicKey());
                    AccountData voterData = repository.getAccountRepository().getAccount(voter);
                    int rawVoteWeight = voterData == null ? 0 : voterData.getBlocksMinted();
                    AccountTrustSnapshotData activeTrustSnapshot = repository.getAccountRatingRepository()
                            .getTrustDerivationSnapshot(voter, AccountTrustWeight.getActiveWeightCategory());
                    AccountTrustStatus activeTrustStatus = AccountTrustWeight.statusFromSnapshot(activeTrustSnapshot);
                    int trustWeightPercent = AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, activeTrustStatus);
                    int voteWeight = AccountTrustWeight.calculateEffectiveVoteWeight(voteWeightPercents, rawVoteWeight,
                            activeTrustSnapshot);

                    int optionIndex = vote.getOptionIndex();
                    if (optionIndex <= Poll.NO_VOTE_OPTION_INDEX || optionIndex > pollData.getPollOptions().size())
                            continue;

                    String selectedOption = pollData.getPollOptions().get(optionIndex - 1).getOptionName();
                    if (voteCountMap.containsKey(selectedOption)) {
                            voteCountMap.put(selectedOption, voteCountMap.get(selectedOption) + 1);
                            voteWeightMap.put(selectedOption, voteWeightMap.get(selectedOption) + voteWeight);
                            rawVoteWeightMap.put(selectedOption, rawVoteWeightMap.get(selectedOption) + rawVoteWeight);
                            totalVotes++;
                            totalWeight += voteWeight;
                            rawTotalWeight += rawVoteWeight;

                            if (voteDetails != null) {
                                    voteDetails.add(new PollVotes.VoteDetail(
                                            voter,
                                            vote.getOptionIndex(),
                                            rawVoteWeight,
                                            activeTrustStatus.name(),
                                            activeTrustStatus.getValue(),
                                            trustWeightPercent,
                                            voteWeight,
                                            activeTrustSnapshot == null ? null : activeTrustSnapshot.getSnapshotHeight(),
                                            activeTrustSnapshot == null ? null : activeTrustSnapshot.getSnapshotTimestamp()));
                            }
                    }
            }

            // Convert map to list of VoteInfo
            List<PollVotes.OptionCount> voteCounts = buildOptionCounts(voteCountMap);
            // Convert map to list of WeightInfo
            List<PollVotes.OptionWeight> voteWeights = buildOptionWeights(voteWeightMap, rawVoteWeightMap);

            if (countsOnly) {
                    return new PollVotes(null, totalVotes, totalWeight, rawTotalWeight, voteCounts, voteWeights, null);
            } else {
                    return new PollVotes(votes, totalVotes, totalWeight, rawTotalWeight, voteCounts, voteWeights, voteDetails);
            }
    }
    
    private PollDataWithVotes getFrozenPollResultsForClosedPoll(Repository repository, PollData pollData, long latestBlockTimestamp) throws DataException {
            if (!pollData.isClosedAt(latestBlockTimestamp))
                    return null;

            return repository.getVotingRepository().getFrozenPollResults(pollData.getPollName());
    }

    private PollVotes buildFrozenPollVotesResponse(Repository repository, PollData pollData, boolean countsOnly, PollDataWithVotes frozenPollResults) throws DataException {
            List<PollVotes.OptionCount> voteCounts = buildOptionCounts(frozenPollResults.getVoteCountMap());
            List<PollVotes.OptionWeight> voteWeights = buildOptionWeights(frozenPollResults.getVoteWeightMap(), frozenPollResults.getRawVoteWeightMap());
            List<VoteOnPollData> votes = countsOnly ? null : repository.getVotingRepository().getVotes(pollData.getPollId());
            List<PollVotes.VoteDetail> voteDetails = countsOnly ? null : buildVoteDetails(repository.getVotingRepository().getFrozenPollVoteWeights(pollData.getPollName()));

            return new PollVotes(votes, frozenPollResults.getTotalVotes(), frozenPollResults.getTotalWeight(),
                    frozenPollResults.getRawTotalWeight(), voteCounts, voteWeights, voteDetails);
    }

    private List<PollVotes.OptionCount> buildOptionCounts(Map<String, Integer> voteCountMap) {
            return voteCountMap.entrySet().stream()
                    .map(entry -> new PollVotes.OptionCount(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
    }

    private List<PollVotes.OptionWeight> buildOptionWeights(Map<String, Integer> voteWeightMap, Map<String, Integer> rawVoteWeightMap) {
            return voteWeightMap.entrySet().stream()
                    .map(entry -> new PollVotes.OptionWeight(entry.getKey(), entry.getValue(), rawVoteWeightMap.getOrDefault(entry.getKey(), 0)))
                    .collect(Collectors.toList());
    }

    private List<PollVotes.VoteDetail> buildVoteDetails(List<PollVoteWeightData> voteWeights) {
            return voteWeights.stream()
                    .map(voteWeight -> new PollVotes.VoteDetail(
                            Crypto.toAddress(voteWeight.getVoterPublicKey()),
                            voteWeight.getOptionIndex(),
                            voteWeight.getRawVoteWeight(),
                            voteWeight.getTrustStatus().name(),
                            voteWeight.getTrustStatus().getValue(),
                            voteWeight.getTrustWeightPercent(),
                            voteWeight.getEffectiveVoteWeight()))
                    .collect(Collectors.toList());
    }

}
