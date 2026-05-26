package org.qortium.test.repository;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.data.voting.VoteOnPollData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
			assertEquals(List.of("Yes", "No"), pollOptionNames(fetchedPollData));
			assertEquals(pollName, repository.getVotingRepository().fromPollId(fetchedPollData.getPollId()).getPollName());

			PollData listedPollData = repository.getVotingRepository().getAllPolls(null, null, null).stream()
					.filter(pollData -> pollData.getPollName().equals(pollName))
					.findFirst()
					.orElseThrow(() -> new AssertionError("Missing poll " + pollName));
			assertEquals(fetchedPollData.getPollId(), listedPollData.getPollId());
			assertEquals(endTime, listedPollData.getEndTime());
		}
	}

	@Test
	public void testPollStateTablesUsePollIdForeignKeys() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertTrue("Polls should use poll_id as primary key", tableHasPrimaryKey(repository, "Polls", "poll_id"));
			assertFalse("Polls should not use poll_name as primary key", tableHasPrimaryKey(repository, "Polls", "poll_name"));
			assertTrue("Polls should keep poll_name unique", tableHasUniqueIndex(repository, "Polls", "poll_name"));
			assertTrue("VoteOnPollTransactions should use poll_id", tableHasColumn(repository, "VoteOnPollTransactions", "poll_id"));
			assertFalse("VoteOnPollTransactions should not keep poll_name", tableHasColumn(repository, "VoteOnPollTransactions", "poll_name"));
			assertTrue("UpdatePollTransactions should use poll_id", tableHasColumn(repository, "UpdatePollTransactions", "poll_id"));
			assertTrue("UpdatePollTransactionOptions should store option rows", tableHasColumn(repository, "UpdatePollTransactionOptions", "option_name"));
			assertTrue("UpdatePollTransactionPreviousOptions should store previous option rows",
					tableHasColumn(repository, "UpdatePollTransactionPreviousOptions", "option_name"));

			for (String tableName : List.of("PollOptions", "PollVotes", "PollFrozenResults", "PollFrozenVoteDetails")) {
				assertTrue(tableName + " should use poll_id", tableHasColumn(repository, tableName, "poll_id"));
				assertFalse(tableName + " should not keep poll_name", tableHasColumn(repository, tableName, "poll_name"));
				assertTrue(tableName + " should reference Polls.poll_id", tableHasImportedKey(repository, tableName, "poll_id", "Polls", "poll_id"));
			}
		}
	}

	@Test
	public void testPollVotesRoundTripThroughIdBackedTables() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "poll-id-backed-votes";
			createTestPoll(repository, pollName, null);
			TestAccount bob = Common.getTestAccount(repository, "bob");

			PollData pollData = repository.getVotingRepository().fromPollName(pollName);
			assertNotNull(pollData.getPollId());
			assertEquals(List.of("Yes", "No"), pollOptionNames(pollData));

			repository.getVotingRepository().save(new VoteOnPollData(pollData.getPollId(), bob.getPublicKey(), 1));
			repository.saveChanges();

			VoteOnPollData fetchedVote = repository.getVotingRepository().getVote(pollName, bob.getPublicKey());
			assertNotNull(fetchedVote);
			assertEquals(pollData.getPollId(), fetchedVote.getPollId());
			assertEquals(pollName, fetchedVote.getPollName());
			assertEquals(1, fetchedVote.getOptionIndex());
			assertEquals(1, repository.getVotingRepository().getVote(pollData.getPollId(), bob.getPublicKey()).getOptionIndex());
			assertEquals(1, repository.getVotingRepository().getVotes(pollName).size());
			assertEquals(1, repository.getVotingRepository().getVotes(pollData.getPollId()).size());

			repository.getVotingRepository().delete(pollData.getPollId(), bob.getPublicKey());
			repository.saveChanges();

			assertNull(repository.getVotingRepository().getVote(pollName, bob.getPublicKey()));
			assertNull(repository.getVotingRepository().getVote(pollData.getPollId(), bob.getPublicKey()));
			assertEquals(0, repository.getVotingRepository().getVotes(pollName).size());
			assertEquals(0, repository.getVotingRepository().getVotes(pollData.getPollId()).size());
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

	private List<String> pollOptionNames(PollData pollData) {
		return pollData.getPollOptions().stream()
				.map(PollOptionData::getOptionName)
				.collect(Collectors.toList());
	}

	private boolean tableHasColumn(Repository repository, String tableName, String columnName) throws SQLException {
		try (ResultSet resultSet = repository.getConnection().getMetaData().getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
			return resultSet.next();
		}
	}

	private boolean tableHasImportedKey(Repository repository, String tableName, String columnName, String referencedTableName, String referencedColumnName) throws SQLException {
		DatabaseMetaData metaData = repository.getConnection().getMetaData();
		try (ResultSet resultSet = metaData.getImportedKeys(null, null, tableName.toUpperCase())) {
			while (resultSet.next()) {
				String foreignKeyColumnName = resultSet.getString("FKCOLUMN_NAME");
				String primaryKeyTableName = resultSet.getString("PKTABLE_NAME");
				String primaryKeyColumnName = resultSet.getString("PKCOLUMN_NAME");

				if (columnName.equalsIgnoreCase(foreignKeyColumnName)
						&& referencedTableName.equalsIgnoreCase(primaryKeyTableName)
						&& referencedColumnName.equalsIgnoreCase(primaryKeyColumnName))
					return true;
			}
		}

		return false;
	}

	private boolean tableHasPrimaryKey(Repository repository, String tableName, String columnName) throws SQLException {
		DatabaseMetaData metaData = repository.getConnection().getMetaData();
		try (ResultSet resultSet = metaData.getPrimaryKeys(null, null, tableName.toUpperCase())) {
			while (resultSet.next()) {
				String primaryKeyColumnName = resultSet.getString("COLUMN_NAME");
				if (columnName.equalsIgnoreCase(primaryKeyColumnName))
					return true;
			}
		}

		return false;
	}

	private boolean tableHasUniqueIndex(Repository repository, String tableName, String columnName) throws SQLException {
		DatabaseMetaData metaData = repository.getConnection().getMetaData();
		try (ResultSet resultSet = metaData.getIndexInfo(null, null, tableName.toUpperCase(), true, false)) {
			while (resultSet.next()) {
				String indexColumnName = resultSet.getString("COLUMN_NAME");
				if (columnName.equalsIgnoreCase(indexColumnName))
					return true;
			}
		}

		return false;
	}

}
