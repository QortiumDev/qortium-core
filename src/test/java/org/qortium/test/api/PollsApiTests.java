package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.model.PollVotes;
import org.qortium.api.resource.PollsResource;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.data.voting.VoteOnPollData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.voting.Poll;

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
			assertEquals(Integer.valueOf(1), countsOnlyResponse.totalVoters);
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
			assertEquals(byName.totalVoters, byId.totalVoters);
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
	public void testVoteWeightsUseDerivedTrustStatus() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-trust-weight-test";
			createTestPoll(repository, pollName);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount suspicious = Common.generateRandomSeedAccount(repository);

			AccountTrustTestUtils.setBlocksMinted(repository, alice, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, chloe, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, dilbert, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, suspicious, 100);

			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(alice, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.SILVER),
					AccountTrustTestUtils.subjectTrustSnapshot(chloe, AccountTrustStatus.BRONZE),
					AccountTrustTestUtils.subjectTrustSnapshot(dilbert, AccountTrustStatus.UNVERIFIED),
					AccountTrustTestUtils.subjectTrustSnapshot(suspicious, AccountTrustStatus.SUSPICIOUS));

			repository.getVotingRepository().save(new VoteOnPollData(pollName, alice.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, chloe.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, dilbert.getPublicKey(), 2));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, suspicious.getPublicKey(), 2));
			repository.saveChanges();

			PollVotes pollVotes = this.pollsResource.getPollVotes(pollName, true);
			assertEquals(Integer.valueOf(5), pollVotes.totalVotes);
			assertEquals(Integer.valueOf(5), pollVotes.totalVoters);
			assertEquals(Integer.valueOf(210), pollVotes.totalWeight);
			assertEquals(Integer.valueOf(500), pollVotes.rawTotalWeight);
			assertNull(pollVotes.voteDetails);
			assertEquals(210, findOptionWeight(pollVotes.voteWeights, "1"));
			assertEquals(0, findOptionWeight(pollVotes.voteWeights, "2"));
			assertEquals(0, findOptionWeight(pollVotes.voteWeights, "3"));
			assertEquals(300, findOptionRawWeight(pollVotes.voteWeights, "1"));
			assertEquals(200, findOptionRawWeight(pollVotes.voteWeights, "2"));
			assertEquals(0, findOptionRawWeight(pollVotes.voteWeights, "3"));

			PollVotes fullPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertNotNull(fullPollVotes.voteDetails);
			assertEquals(5, fullPollVotes.voteDetails.size());
			assertEquals(Integer.valueOf(500), fullPollVotes.rawTotalWeight);

			assertVoteDetail(fullPollVotes.voteDetails, alice, 1, AccountTrustStatus.GOLD, 100, 100, true);
			assertVoteDetail(fullPollVotes.voteDetails, bob, 1, AccountTrustStatus.SILVER, 100, 70, true);
			assertVoteDetail(fullPollVotes.voteDetails, chloe, 1, AccountTrustStatus.BRONZE, 100, 40, true);
			assertVoteDetail(fullPollVotes.voteDetails, dilbert, 2, AccountTrustStatus.UNVERIFIED, 100, 0, true);
			assertVoteDetail(fullPollVotes.voteDetails, suspicious, 2, AccountTrustStatus.SUSPICIOUS, 100, 0, true);
		}
	}

	@Test
	public void testMultiOptionVotesExposeTotalVotersAndSelectedIndexes() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-multi-option-api-test";
			createTestPoll(repository, pollName);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			AccountTrustTestUtils.setBlocksMinted(repository, alice, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 100);

			repository.getVotingRepository().save(new VoteOnPollData(pollName, alice.getPublicKey(), List.of(1, 2)));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), List.of(2, 3)));
			repository.saveChanges();

			PollVotes pollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertEquals(Integer.valueOf(4), pollVotes.totalVotes);
			assertEquals(Integer.valueOf(2), pollVotes.totalVoters);
			assertEquals(Integer.valueOf(400), pollVotes.rawTotalWeight);
			assertEquals(100, findOptionRawWeight(pollVotes.voteWeights, "1"));
			assertEquals(200, findOptionRawWeight(pollVotes.voteWeights, "2"));
			assertEquals(100, findOptionRawWeight(pollVotes.voteWeights, "3"));
			assertEquals(2, pollVotes.voteDetails.size());

			PollVotes.VoteDetail aliceDetail = findVoteDetail(pollVotes.voteDetails, alice.getAddress());
			assertEquals(Integer.valueOf(1), aliceDetail.optionIndex);
			assertEquals(List.of(1, 2), aliceDetail.optionIndexes);
			PollVotes.VoteDetail bobDetail = findVoteDetail(pollVotes.voteDetails, bob.getAddress());
			assertEquals(List.of(2, 3), bobDetail.optionIndexes);
		}
	}

	@Test
	public void testClosedPollVotesUseFrozenWeights() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-frozen-weight-test";
			long endTime = repository.getBlockRepository().getLastBlock().getTimestamp() + 1;
			createTestPoll(repository, pollName, endTime);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount suspicious = Common.generateRandomSeedAccount(repository);

			AccountTrustTestUtils.setBlocksMinted(repository, alice, 99);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, chloe, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, dilbert, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, suspicious, 100);

			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(alice, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.SILVER),
					AccountTrustTestUtils.subjectTrustSnapshot(chloe, AccountTrustStatus.BRONZE),
					AccountTrustTestUtils.subjectTrustSnapshot(dilbert, AccountTrustStatus.UNVERIFIED),
					AccountTrustTestUtils.subjectTrustSnapshot(suspicious, AccountTrustStatus.SUSPICIOUS));

			repository.getVotingRepository().save(new VoteOnPollData(pollName, alice.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, chloe.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, dilbert.getPublicKey(), 2));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, suspicious.getPublicKey(), 2));
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			assertNotNull(repository.getVotingRepository().getFrozenPollResults(pollName));

			PollVotes closedPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertEquals(Integer.valueOf(5), closedPollVotes.totalVotes);
			assertEquals(Integer.valueOf(5), closedPollVotes.totalVoters);
			assertEquals(Integer.valueOf(210), closedPollVotes.totalWeight);
			assertEquals(Integer.valueOf(500), closedPollVotes.rawTotalWeight);
			assertEquals(210, findOptionWeight(closedPollVotes.voteWeights, "1"));
			assertEquals(0, findOptionWeight(closedPollVotes.voteWeights, "2"));
			assertEquals(300, findOptionRawWeight(closedPollVotes.voteWeights, "1"));
			assertEquals(200, findOptionRawWeight(closedPollVotes.voteWeights, "2"));

			assertVoteDetail(closedPollVotes.voteDetails, alice, 1, AccountTrustStatus.GOLD, 100, 100, false);
			assertVoteDetail(closedPollVotes.voteDetails, bob, 1, AccountTrustStatus.SILVER, 100, 70, false);
			assertVoteDetail(closedPollVotes.voteDetails, chloe, 1, AccountTrustStatus.BRONZE, 100, 40, false);
			assertVoteDetail(closedPollVotes.voteDetails, dilbert, 2, AccountTrustStatus.UNVERIFIED, 100, 0, false);
			assertVoteDetail(closedPollVotes.voteDetails, suspicious, 2, AccountTrustStatus.SUSPICIOUS, 100, 0, false);

			AccountTrustTestUtils.setBlocksMinted(repository, alice, 1000);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 1000);
			AccountTrustTestUtils.setBlocksMinted(repository, chloe, 1000);
			AccountTrustTestUtils.setBlocksMinted(repository, dilbert, 1000);
			AccountTrustTestUtils.setBlocksMinted(repository, suspicious, 1000);
			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(alice, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(chloe, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(dilbert, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(suspicious, AccountTrustStatus.GOLD));

			PollVotes updatedPollVotes = this.pollsResource.getPollVotes(pollName, false);
			assertEquals(Integer.valueOf(5), updatedPollVotes.totalVotes);
			assertEquals(Integer.valueOf(5), updatedPollVotes.totalVoters);
			assertEquals(Integer.valueOf(210), updatedPollVotes.totalWeight);
			assertEquals(Integer.valueOf(500), updatedPollVotes.rawTotalWeight);
			assertEquals(210, findOptionWeight(updatedPollVotes.voteWeights, "1"));
			assertEquals(0, findOptionWeight(updatedPollVotes.voteWeights, "2"));
		}
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

	private void assertVoteDetail(List<PollVotes.VoteDetail> voteDetails, PrivateKeyAccount voter, int optionIndex,
			AccountTrustStatus trustStatus, int rawWeight, int effectiveWeight, boolean hasLiveSnapshotMetadata) {
		PollVotes.VoteDetail voteDetail = findVoteDetail(voteDetails, voter.getAddress());
		assertEquals(Integer.valueOf(optionIndex), voteDetail.optionIndex);
		assertEquals(List.of(optionIndex), voteDetail.optionIndexes);
		assertEquals(Integer.valueOf(rawWeight), voteDetail.rawVoteWeight);
		assertEquals(trustStatus.name(), voteDetail.trustStatus);
		assertEquals(Integer.valueOf(trustStatus.getValue()), voteDetail.trustStatusValue);
		assertEquals(Integer.valueOf(trustStatus.getVoteWeightPercent()), voteDetail.trustWeightPercent);
		assertEquals(Integer.valueOf(effectiveWeight), voteDetail.effectiveVoteWeight);

		if (hasLiveSnapshotMetadata) {
			assertNotNull(voteDetail.trustSnapshotHeight);
			assertNotNull(voteDetail.trustSnapshotTimestamp);
		} else {
			assertNull(voteDetail.trustSnapshotHeight);
			assertNull(voteDetail.trustSnapshotTimestamp);
		}
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
