package org.qortium.test.repository;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PollSearchTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSearchByPollName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "atlas-poll-search-one", "plain description", now - 3_000L, null, "alice");
			createTestPoll(repository, "atlas-poll-search-two", "plain description", now - 2_000L, null, "alice");

			List<PollData> polls = repository.getVotingRepository().searchPolls("ATLAS-poll", false, null, null,
					null, null, null, now, null, null, null);

			assertEquals(List.of("atlas-poll-search-one", "atlas-poll-search-two"), pollNames(polls));
			assertTrue(polls.stream().allMatch(poll -> poll.getPollId() != null));
		}
	}

	@Test
	public void testSearchByDescription() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "description-poll-one", "contains rare-needle text", now - 3_000L, null, "alice");
			createTestPoll(repository, "description-poll-two", "plain description", now - 2_000L, null, "alice");

			List<PollData> polls = repository.getVotingRepository().searchPolls("rare-needle", false, null, null,
					null, null, null, now, null, null, null);

			assertEquals(List.of("description-poll-one"), pollNames(polls));
		}
	}

	@Test
	public void testPrefixOnlySearch() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "prefix-poll-alpha", "plain description", now - 3_000L, null, "alice");
			createTestPoll(repository, "middle-prefix-poll", "plain description", now - 2_000L, null, "alice");

			List<PollData> containsPolls = repository.getVotingRepository().searchPolls("prefix-poll", false, null,
					null, null, null, null, now, null, null, null);
			List<PollData> prefixPolls = repository.getVotingRepository().searchPolls("prefix-poll", true, null,
					null, null, null, null, now, null, null, null);

			assertEquals(List.of("prefix-poll-alpha", "middle-prefix-poll"), pollNames(containsPolls));
			assertEquals(List.of("prefix-poll-alpha"), pollNames(prefixPolls));
		}
	}

	@Test
	public void testOwnerFilter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "owner-poll-alice", "owner search", now - 3_000L, null, "alice");
			createTestPoll(repository, "owner-poll-bob", "owner search", now - 2_000L, null, "bob");

			String aliceAddress = Common.getTestAccount(repository, "alice").getAddress();
			List<PollData> polls = repository.getVotingRepository().searchPolls("owner-poll", false, aliceAddress,
					null, null, null, null, now, null, null, null);

			assertEquals(List.of("owner-poll-alice"), pollNames(polls));
		}
	}

	@Test
	public void testStatusFilters() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "status-poll-open", "status search", now - 3_000L, null, "alice");
			createTestPoll(repository, "status-poll-timed-open", "status search", now - 2_000L, now + 60_000L, "alice");
			createTestPoll(repository, "status-poll-closed", "status search", now - 1_000L, now, "alice");

			List<PollData> allPolls = repository.getVotingRepository().searchPolls("status-poll", false, null,
					null, null, null, null, now, null, null, null);
			List<PollData> openPolls = repository.getVotingRepository().searchPolls("status-poll", false, null,
					false, null, null, null, now, null, null, null);
			List<PollData> closedPolls = repository.getVotingRepository().searchPolls("status-poll", false, null,
					true, null, null, null, now, null, null, null);

			assertEquals(List.of("status-poll-open", "status-poll-timed-open", "status-poll-closed"), pollNames(allPolls));
			assertEquals(List.of("status-poll-open", "status-poll-timed-open"), pollNames(openPolls));
			assertEquals(List.of("status-poll-closed"), pollNames(closedPolls));
		}
	}

	@Test
	public void testHasEndTimeFilter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "end-time-poll-none", "end time search", now - 3_000L, null, "alice");
			createTestPoll(repository, "end-time-poll-set", "end time search", now - 2_000L, now + 60_000L, "alice");

			List<PollData> pollsWithEndTime = repository.getVotingRepository().searchPolls("end-time-poll", false,
					null, null, true, null, null, now, null, null, null);
			List<PollData> pollsWithoutEndTime = repository.getVotingRepository().searchPolls("end-time-poll", false,
					null, null, false, null, null, now, null, null, null);

			assertEquals(List.of("end-time-poll-set"), pollNames(pollsWithEndTime));
			assertEquals(List.of("end-time-poll-none"), pollNames(pollsWithoutEndTime));
		}
	}

	@Test
	public void testPublishedTimestampRange() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "range-poll-alpha", "range search", 1_000L, null, "alice");
			createTestPoll(repository, "range-poll-beta", "range search", 2_000L, null, "alice");
			createTestPoll(repository, "range-poll-gamma", "range search", 3_000L, null, "alice");

			List<PollData> polls = repository.getVotingRepository().searchPolls("range-poll", false, null, null,
					null, 1_500L, 2_500L, now, null, null, null);

			assertEquals(List.of("range-poll-beta"), pollNames(polls));
		}
	}

	@Test
	public void testLimitOffsetAndReverse() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			long now = latestBlockTimestamp(repository);
			createTestPoll(repository, "slice-poll-alpha", "slice search", 1_000L, null, "alice");
			createTestPoll(repository, "slice-poll-beta", "slice search", 2_000L, null, "alice");
			createTestPoll(repository, "slice-poll-gamma", "slice search", 3_000L, null, "alice");

			List<PollData> polls = repository.getVotingRepository().searchPolls("slice-poll", false, null, null,
					null, null, null, now, 1, 1, true);

			assertEquals(List.of("slice-poll-beta"), pollNames(polls));
		}
	}

	@Test
	public void testNoMatchReturnsEmptyList() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<PollData> polls = repository.getVotingRepository().searchPolls("not-a-real-poll-query", false, null,
					null, null, null, null, latestBlockTimestamp(repository), null, null, null);

			assertTrue(polls.isEmpty());
		}
	}

	private static void createTestPoll(Repository repository, String pollName, String description, long published,
			Long endTime, String ownerAccountName) throws DataException {
		TestAccount creator = Common.getTestAccount(repository, "alice");
		TestAccount owner = Common.getTestAccount(repository, ownerAccountName);
		List<PollOptionData> options = List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));

		repository.getVotingRepository().save(new PollData(
				creator.getPublicKey(),
				owner.getAddress(),
				pollName,
				description,
				options,
				published,
				endTime));
		repository.saveChanges();
	}

	private static long latestBlockTimestamp(Repository repository) throws DataException {
		return repository.getBlockRepository().getLastBlock().getTimestamp();
	}

	private static List<String> pollNames(List<PollData> polls) {
		return polls.stream().map(PollData::getPollName).collect(Collectors.toList());
	}

}
