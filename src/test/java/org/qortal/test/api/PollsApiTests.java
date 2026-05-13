package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.PollVotes;
import org.qortal.api.resource.PollsResource;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.UpdatePollTransactionData;
import org.qortal.data.transaction.VoteOnPollTransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.voting.Poll;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PollsApiTests extends ApiCommon {

	private PollsResource pollsResource;

	@Before
	public void buildResource() {
		this.pollsResource = (PollsResource) ApiCommon.buildResource(PollsResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.pollsResource);
	}

	@Test
	public void testSearchPollsByQuery() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = repository.getBlockRepository().getLastBlock().getTimestamp();
			createTestPoll(repository, "api-poll-search-open", "plain description", now - 2_000L, null, "alice");
			createTestPoll(repository, "api-poll-search-closed", "plain description", now - 1_000L, now, "alice");
		}

		List<PollData> polls = this.pollsResource.searchPolls("api-poll-search", null, null, null,
				null, null, null, null, null, null);

		assertEquals(List.of("api-poll-search-open", "api-poll-search-closed"), pollNames(polls));
	}

	@Test
	public void testSearchPollsByOpenStatus() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = repository.getBlockRepository().getLastBlock().getTimestamp();
			createTestPoll(repository, "api-poll-status-open", "status search", now - 2_000L, null, "alice");
			createTestPoll(repository, "api-poll-status-closed", "status search", now - 1_000L, now, "alice");
		}

		List<PollData> polls = this.pollsResource.searchPolls("api-poll-status", null, null, "OPEN",
				null, null, null, null, null, null);

		assertEquals(List.of("api-poll-status-open"), pollNames(polls));
	}

	@Test
	public void testSearchPollsRejectsInvalidStatus() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.pollsResource.searchPolls(null, null, null, "ENDED",
						null, null, null, null, null, null));
	}

	@Test
	public void testSearchPollsRejectsInvalidOwner() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.pollsResource.searchPolls(null, null, "not-an-address", null,
						null, null, null, null, null, null));
	}

	@Test
	public void testSearchPollsRejectsInvalidTimestampRange() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.pollsResource.searchPolls(null, null, null, null,
						null, 2_000L, 1_000L, null, null, null));
	}

	@Test
	public void testGetPollById() throws DataException {
		PollData pollData;
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-id-api-test";
			createTestPoll(repository, pollName);
			pollData = repository.getVotingRepository().fromPollName(pollName);
		}

		PollData response = this.pollsResource.getPollDataById(pollData.getPollId());

		assertEquals(pollData.getPollId(), response.getPollId());
		assertEquals(pollData.getPollName(), response.getPollName());
	}

	@Test
	public void testGetPollByIdRejectsMissingPoll() {
		assertApiError(ApiError.POLL_NO_EXISTS, () -> this.pollsResource.getPollDataById(Integer.MAX_VALUE));
	}

	@Test
	public void testGetPollVotesIncludesVoterAddress() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-voter-address-test";
			createTestPoll(repository, pollName);

			VoteOnPollData vote = new VoteOnPollData(pollName, Common.getTestAccount(repository, "alice").getPublicKey(), 1);
			repository.getVotingRepository().save(vote);
			repository.saveChanges();

			PollVotes response = this.pollsResource.getPollVotes(pollName, false);

			assertNotNull(response);
			assertNotNull(response.votes);
			assertEquals(1, response.votes.size());
			assertEquals(aliceAddress, response.votes.get(0).getVoterAddress());
			assertNotNull(response.votes.get(0).getVoterPublicKey());

			PollVotes countsOnlyResponse = this.pollsResource.getPollVotes(pollName, true);
			assertNotNull(countsOnlyResponse);
			assertNull(countsOnlyResponse.votes);
			assertEquals(Integer.valueOf(1), countsOnlyResponse.totalVotes);
		}
	}

	@Test
	public void testGetPollVotesByIdMatchesNameLookup() throws DataException {
		PollData pollData;
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-votes-by-id-test";
			createTestPoll(repository, pollName);
			pollData = repository.getVotingRepository().fromPollName(pollName);

			VoteOnPollData vote = new VoteOnPollData(pollName, Common.getTestAccount(repository, "alice").getPublicKey(), 1);
			repository.getVotingRepository().save(vote);
			repository.saveChanges();
		}

		PollVotes byName = this.pollsResource.getPollVotes(pollData.getPollName(), true);
		PollVotes byId = this.pollsResource.getPollVotesById(pollData.getPollId(), true);

		assertEquals(byName.totalVotes, byId.totalVotes);
		assertEquals(byName.totalWeight, byId.totalWeight);
		assertEquals(byName.rawTotalWeight, byId.rawTotalWeight);
		assertEquals(findOptionWeight(byName.voteWeights, "1"), findOptionWeight(byId.voteWeights, "1"));
	}

	@Test
	public void testVoteOnPollEndpointBuildsUnsignedVoteRemovalTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-vote-removal-api-test";
			createTestPoll(repository, pollName);
			PollData pollData = repository.getVotingRepository().fromPollName(pollName);
			TestAccount alice = Common.getTestAccount(repository, "alice");

			repository.getVotingRepository().save(new VoteOnPollData(pollData.getPollId(), alice.getPublicKey(), 1));
			repository.saveChanges();

			VoteOnPollTransactionData transactionData = new VoteOnPollTransactionData(
					TestTransaction.generateBase(alice), pollData.getPollId(), Poll.NO_VOTE_OPTION_INDEX);
			String rawTransaction = this.pollsResource.VoteOnPoll(transactionData);

			assertNotNull(rawTransaction);
			assertFalse(rawTransaction.isEmpty());
		}
	}

	@Test
	public void testUpdatePollEndpointBuildsUnsignedTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-update-api-test";
			createTestPoll(repository, pollName);
			PollData pollData = repository.getVotingRepository().fromPollName(pollName);
			TestAccount alice = Common.getTestAccount(repository, "alice");

			List<PollOptionData> updatedOptions = List.of(
					new PollOptionData("Approve"),
					new PollOptionData("Reject"));
			UpdatePollTransactionData transactionData = new UpdatePollTransactionData(
					TestTransaction.generateBase(alice), pollData.getPollId(), "poll-update-api-renamed",
					"Updated by API test", updatedOptions, null);
			String rawTransaction = this.pollsResource.UpdatePoll(transactionData);

			assertNotNull(rawTransaction);
			assertFalse(rawTransaction.isEmpty());
		}
	}

	@Test
	public void testVoteWeightsUseCurrentTrustStatus() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-trust-weight-test";
			createTestPoll(repository, pollName);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount unverified = createUnverifiedVoteAccount(repository, 100);

			setVoteAccount(repository, "alice", 100, AccountTrustStatus.GOLD);
			setVoteAccount(repository, "bob", 101, AccountTrustStatus.SILVER);
			setVoteAccount(repository, "chloe", 101, AccountTrustStatus.BRONZE);
			setVoteAccount(repository, "dilbert", 100, AccountTrustStatus.SUSPICIOUS);

			repository.getVotingRepository().save(new VoteOnPollData(pollName, alice.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 2));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, chloe.getPublicKey(), 2));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, dilbert.getPublicKey(), 3));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, unverified.getPublicKey(), 3));
			repository.saveChanges();

			PollVotes pollVotes = this.pollsResource.getPollVotes(pollName, true);
			assertEquals(Integer.valueOf(5), pollVotes.totalVotes);
			assertEquals(Integer.valueOf(175), pollVotes.totalWeight);
			assertEquals(Integer.valueOf(502), pollVotes.rawTotalWeight);
			assertNull(pollVotes.voteDetails);
			assertEquals(100, findOptionWeight(pollVotes.voteWeights, "1"));
			assertEquals(75, findOptionWeight(pollVotes.voteWeights, "2"));
			assertEquals(0, findOptionWeight(pollVotes.voteWeights, "3"));
			assertEquals(100, findOptionRawWeight(pollVotes.voteWeights, "1"));
			assertEquals(202, findOptionRawWeight(pollVotes.voteWeights, "2"));
			assertEquals(200, findOptionRawWeight(pollVotes.voteWeights, "3"));

			PollVotes fullPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertNotNull(fullPollVotes.voteDetails);
			assertEquals(5, fullPollVotes.voteDetails.size());
			assertEquals(Integer.valueOf(502), fullPollVotes.rawTotalWeight);

			PollVotes.VoteDetail bobVoteDetail = findVoteDetail(fullPollVotes.voteDetails, bob.getAddress());
			assertEquals(Integer.valueOf(2), bobVoteDetail.optionIndex);
			assertEquals(Integer.valueOf(101), bobVoteDetail.rawVoteWeight);
			assertEquals(AccountTrustStatus.SILVER.name(), bobVoteDetail.trustStatus);
			assertEquals(Integer.valueOf(AccountTrustStatus.SILVER.getValue()), bobVoteDetail.trustStatusValue);
			assertEquals(Integer.valueOf(50), bobVoteDetail.trustWeightPercent);
			assertEquals(Integer.valueOf(50), bobVoteDetail.effectiveVoteWeight);

			PollVotes.VoteDetail unverifiedVoteDetail = findVoteDetail(fullPollVotes.voteDetails, unverified.getAddress());
			assertEquals(Integer.valueOf(3), unverifiedVoteDetail.optionIndex);
			assertEquals(Integer.valueOf(100), unverifiedVoteDetail.rawVoteWeight);
			assertEquals(AccountTrustStatus.UNVERIFIED.name(), unverifiedVoteDetail.trustStatus);
			assertEquals(Integer.valueOf(0), unverifiedVoteDetail.effectiveVoteWeight);

			repository.getAccountRepository().setTrustStatus(bob.getAddress(), AccountTrustStatus.GOLD);
			repository.saveChanges();

			PollVotes updatedPollVotes = this.pollsResource.getPollVotes(pollName, true);
			assertEquals(Integer.valueOf(5), updatedPollVotes.totalVotes);
			assertEquals(Integer.valueOf(226), updatedPollVotes.totalWeight);
			assertEquals(Integer.valueOf(502), updatedPollVotes.rawTotalWeight);
			assertEquals(100, findOptionWeight(updatedPollVotes.voteWeights, "1"));
			assertEquals(126, findOptionWeight(updatedPollVotes.voteWeights, "2"));
			assertEquals(202, findOptionRawWeight(updatedPollVotes.voteWeights, "2"));
		}
	}

	@Test
	public void testClosedPollVotesUseFrozenWeights() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-frozen-weight-test";
			long endTime = repository.getBlockRepository().getLastBlock().getTimestamp() + 1;
			createTestPoll(repository, pollName, endTime);

			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			setVoteAccount(repository, "bob", 101, AccountTrustStatus.SILVER);
			setVoteAccount(repository, "chloe", 101, AccountTrustStatus.BRONZE);

			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, chloe.getPublicKey(), 2));
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			assertNotNull(repository.getVotingRepository().getFrozenPollResults(pollName));

			PollVotes closedPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertEquals(Integer.valueOf(2), closedPollVotes.totalVotes);
			assertEquals(Integer.valueOf(75), closedPollVotes.totalWeight);
			assertEquals(Integer.valueOf(202), closedPollVotes.rawTotalWeight);
			assertEquals(50, findOptionWeight(closedPollVotes.voteWeights, "1"));
			assertEquals(25, findOptionWeight(closedPollVotes.voteWeights, "2"));
			assertEquals(101, findOptionRawWeight(closedPollVotes.voteWeights, "1"));
			assertEquals(101, findOptionRawWeight(closedPollVotes.voteWeights, "2"));

			PollVotes.VoteDetail bobVoteDetail = findVoteDetail(closedPollVotes.voteDetails, bob.getAddress());
			assertEquals(AccountTrustStatus.SILVER.name(), bobVoteDetail.trustStatus);
			assertEquals(Integer.valueOf(50), bobVoteDetail.trustWeightPercent);
			assertEquals(Integer.valueOf(50), bobVoteDetail.effectiveVoteWeight);

			setVoteAccount(repository, "bob", 1000, AccountTrustStatus.GOLD);
			setVoteAccount(repository, "chloe", 1000, AccountTrustStatus.GOLD);

			PollVotes updatedPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertEquals(Integer.valueOf(2), updatedPollVotes.totalVotes);
			assertEquals(Integer.valueOf(75), updatedPollVotes.totalWeight);
			assertEquals(Integer.valueOf(202), updatedPollVotes.rawTotalWeight);
			assertEquals(50, findOptionWeight(updatedPollVotes.voteWeights, "1"));
			assertEquals(25, findOptionWeight(updatedPollVotes.voteWeights, "2"));
		}
	}

	private void setVoteAccount(Repository repository, String accountName, int blocksMinted, AccountTrustStatus trustStatus) throws DataException {
		TestAccount account = Common.getTestAccount(repository, accountName);
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		accountData.setPublicKey(account.getPublicKey());
		accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.getAccountRepository().setTrustStatus(account.getAddress(), trustStatus);
		repository.saveChanges();
	}

	private PrivateKeyAccount createUnverifiedVoteAccount(Repository repository, int blocksMinted) throws DataException {
		byte[] privateKey = new byte[32];
		Arrays.fill(privateKey, (byte) 7);
		PrivateKeyAccount account = new PrivateKeyAccount(repository, privateKey);
		AccountData accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		repository.getAccountRepository().ensureAccount(accountData);
		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.saveChanges();

		return account;
	}

	private int findOptionWeight(List<PollVotes.OptionWeight> voteWeights, String optionName) {
		return voteWeights.stream()
				.filter(optionWeight -> optionWeight.optionName.equals(optionName))
				.findFirst()
				.map(optionWeight -> optionWeight.voteWeight)
				.orElseThrow(() -> new AssertionError("Missing vote weight for option " + optionName));
	}

	private int findOptionRawWeight(List<PollVotes.OptionWeight> voteWeights, String optionName) {
		return voteWeights.stream()
				.filter(optionWeight -> optionWeight.optionName.equals(optionName))
				.findFirst()
				.map(optionWeight -> optionWeight.rawVoteWeight)
				.orElseThrow(() -> new AssertionError("Missing raw vote weight for option " + optionName));
	}

	private PollVotes.VoteDetail findVoteDetail(List<PollVotes.VoteDetail> voteDetails, String voterAddress) {
		return voteDetails.stream()
				.filter(voteDetail -> voteDetail.voterAddress.equals(voterAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing vote detail for " + voterAddress));
	}

	private void createTestPoll(Repository repository, String pollName) throws DataException {
		createTestPoll(repository, pollName, null);
	}

	private void createTestPoll(Repository repository, String pollName, Long endTime) throws DataException {
		createTestPoll(repository, pollName, "Test poll", System.currentTimeMillis(), endTime, "alice");
	}

	private void createTestPoll(Repository repository, String pollName, String description, long published,
			Long endTime, String ownerAccountName) throws DataException {
		List<PollOptionData> options = List.of(
				new PollOptionData("1"),
				new PollOptionData("2"),
				new PollOptionData("3"));

		TestAccount owner = Common.getTestAccount(repository, ownerAccountName);

		PollData pollData = new PollData(
				Common.getTestAccount(repository, "alice").getPublicKey(),
				owner.getAddress(),
				pollName,
				description,
				options,
				published,
				endTime);

		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
	}

	private static List<String> pollNames(List<PollData> polls) {
		return polls.stream().map(PollData::getPollName).collect(Collectors.toList());
	}

}
