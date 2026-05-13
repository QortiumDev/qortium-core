package org.qortal.test.repository;

import org.junit.Before;
import org.junit.Test;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class VotingRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testPollEndTimePersistsInRepositoryViews() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-end-time-test";
			Long endTime = System.currentTimeMillis() + 60_000L;
			createTestPoll(repository, pollName, endTime);

			PollData fetchedPollData = repository.getVotingRepository().fromPollName(pollName);
			assertNotNull(fetchedPollData.getPollId());
			assertEquals(endTime, fetchedPollData.getEndTime());
			assertEquals(pollName, repository.getVotingRepository().fromPollId(fetchedPollData.getPollId()).getPollName());

			PollData listedPollData = repository.getVotingRepository().getAllPolls(null, null, null).stream()
					.filter(pollData -> pollData.getPollName().equals(pollName))
					.findFirst()
					.orElseThrow(() -> new AssertionError("Missing poll " + pollName));
			assertEquals(fetchedPollData.getPollId(), listedPollData.getPollId());
			assertEquals(endTime, listedPollData.getEndTime());
		}
	}

	private void createTestPoll(Repository repository, String pollName, Long endTime) throws DataException {
		List<PollOptionData> options = List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));

		PollData pollData = new PollData(
				Common.getTestAccount(repository, "alice").getPublicKey(),
				Common.getTestAccount(repository, "alice").getAddress(),
				pollName,
				"Test poll",
				options,
				System.currentTimeMillis(),
				endTime);

		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
	}

}
