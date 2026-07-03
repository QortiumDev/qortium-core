package org.qortium.test;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.controller.arbitrary.ArbitraryDataCacheManager;
import org.qortium.crosschain.BitcoinyACCTv3;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.account.AccountData;
import org.qortium.data.chat.ChatMessage;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.repository.hsqldb.HSQLDBDatabaseUpdates;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class RepositoryTests extends Common {

	private static final Logger LOGGER = LogManager.getLogger(RepositoryTests.class);

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGetRepository() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository);
		}
	}

	@Test
	public void testBackupAcceptsSafeNames() throws DataException, TimeoutException {
		String[] safeBackupNames = new String[] { "backup", "bootstrap", "backup_2026-05-28" };

		for (String safeBackupName : safeBackupNames)
			try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
				hsqldb.backup(true, safeBackupName, 10 * 1000L);
			}
	}

	@Test
	public void testBackupRejectsUnsafeNames() throws DataException, TimeoutException {
		String[] unsafeBackupNames = new String[] { null, "", " ", "../evil", "foo/bar", "foo'bar" };

		for (String unsafeBackupName : unsafeBackupNames)
			try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
				try {
					hsqldb.backup(true, unsafeBackupName, 10 * 1000L);
					fail("Unsafe backup name should be rejected: " + unsafeBackupName);
				} catch (DataException e) {
					assertTrue(e.getMessage().contains("Invalid backup name"));
				}
			}
	}

	@Test
	public void testPopulateLatestSignaturesOnEmptyRepository() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:empty-qdn-signatures-" + System.nanoTime();

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);
			HSQLDBDatabaseUpdates.updateDatabase(connection);

			try (Statement statement = connection.createStatement()) {
				statement.executeUpdate("UPDATE DatabaseInfo SET latest_signature_populated = 0");
			}
			connection.commit();

			ArbitraryDataCacheManager.populateLatestSignaturesIfNecessary(connection);

			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT latest_signature_populated FROM DatabaseInfo")) {
				assertTrue(resultSet.next());
				assertEquals(1, resultSet.getInt(1));
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void testFreshRepositoryInitializesToQortiumBaseline() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:fresh-qortium-baseline-" + System.nanoTime();

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);

			assertEquals(0, HSQLDBDatabaseUpdates.fetchDatabaseVersion(connection));
			assertTrue(HSQLDBDatabaseUpdates.updateDatabase(connection));
			assertEquals(HSQLDBDatabaseUpdates.CURRENT_SCHEMA_VERSION, HSQLDBDatabaseUpdates.fetchDatabaseVersion(connection));

			assertFalse(HSQLDBDatabaseUpdates.updateDatabase(connection));
			assertEquals(HSQLDBDatabaseUpdates.CURRENT_SCHEMA_VERSION, HSQLDBDatabaseUpdates.fetchDatabaseVersion(connection));

			try (ResultSet resultSet = connection.getMetaData().getColumns(null, "PUBLIC", "CHATMESSAGES", "PRIVATE_GROUP_ENVELOPE_TYPE")) {
				assertTrue(resultSet.next());
			}

			try (ResultSet resultSet = connection.getMetaData().getTables(null, "PUBLIC", "CHAINPARAMETERUPDATES", null)) {
				assertTrue(resultSet.next());
			}

			assertColumnSize(connection, "POLLS", "POLL_NAME", 400);
			assertColumnSize(connection, "POLLOPTIONS", "OPTION_NAME", 400);
			assertColumnExists(connection, "POLLS", "START_WHEN");
			assertColumnExists(connection, "CREATEPOLLTRANSACTIONS", "START_WHEN");
			assertColumnExists(connection, "UPDATEPOLLTRANSACTIONS", "NEW_START_WHEN");
			assertColumnExists(connection, "UPDATEPOLLTRANSACTIONS", "PREVIOUS_START_WHEN");

			try (ResultSet resultSet = connection.getMetaData().getTables(null, "PUBLIC", "VOTEONPOLLTRANSACTIONOPTIONS", null)) {
				assertTrue(resultSet.next());
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void testCurrentSchemaAddsArbitraryTransactionCreatedWhen() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:arbitrary-created-when-current-schema-" + System.nanoTime();
		byte[] signature = new byte[] {7, 8, 9};

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TYPE PUBLIC.EPOCHMILLIS AS BIGINT");
				statement.execute("CREATE TYPE PUBLIC.SIGNATURE AS VARBINARY(64)");
				statement.execute("CREATE TABLE PUBLIC.DATABASEINFO(VERSION INTEGER NOT NULL)");
				statement.execute("INSERT INTO PUBLIC.DATABASEINFO VALUES(" + HSQLDBDatabaseUpdates.CURRENT_SCHEMA_VERSION + ")");
				statement.execute("CREATE TABLE PUBLIC.TRANSACTIONS(SIGNATURE PUBLIC.SIGNATURE PRIMARY KEY, CREATED_WHEN PUBLIC.EPOCHMILLIS NOT NULL)");
				statement.execute("CREATE TABLE PUBLIC.ARBITRARYTRANSACTIONS(SIGNATURE PUBLIC.SIGNATURE PRIMARY KEY, NAME VARCHAR(400))");
			}

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"INSERT INTO Transactions(signature, created_when) VALUES (?, ?)")) {
				preparedStatement.setBytes(1, signature);
				preparedStatement.setLong(2, 123456789L);
				preparedStatement.executeUpdate();
			}

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"INSERT INTO ArbitraryTransactions(signature, name) VALUES (?, ?)")) {
				preparedStatement.setBytes(1, signature);
				preparedStatement.setString(2, "legacy-name");
				preparedStatement.executeUpdate();
			}
			connection.commit();

			assertFalse(HSQLDBDatabaseUpdates.updateDatabase(connection));

			assertColumnExists(connection, "ARBITRARYTRANSACTIONS", "CREATED_WHEN");
			assertIndexExists(connection, "ARBITRARYTRANSACTIONS", "ARBITRARYNAMECREATEDINDEX");

			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT created_when FROM ArbitraryTransactions")) {
				assertTrue(resultSet.next());
				assertEquals(123456789L, resultSet.getLong(1));
				assertFalse(resultSet.next());
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void testVersionThreeRepositorySchemaVersionIsUnsupported() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:unsupported-version-three-schema-" + System.nanoTime();

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE DatabaseInfo (version INTEGER NOT NULL)");
				statement.execute("INSERT INTO DatabaseInfo VALUES (3)");
			}
			connection.commit();

			try {
				HSQLDBDatabaseUpdates.updateDatabase(connection);
				fail("Schema version 3 should not be treated as the Qortium baseline");
			} catch (SQLException e) {
				assertTrue(e.getMessage().contains("Unsupported HSQLDB repository schema version 3"));
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void testQortiumVersionOneRepositoryUpgradesToVersionTwo() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:qortium-v1-schema-upgrade-" + System.nanoTime();

		byte[] signature = new byte[] {1, 2, 3};
		byte[] voter = new byte[] {4, 5, 6};

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);
			createMinimalVersionOnePollSchema(connection);

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"INSERT INTO VoteOnPollTransactions(signature, option_index, previous_option_index) VALUES (?, ?, ?)")) {
				preparedStatement.setBytes(1, signature);
				preparedStatement.setInt(2, 2);
				preparedStatement.setInt(3, 1);
				preparedStatement.executeUpdate();
			}

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"INSERT INTO PollVotes(poll_id, voter, option_index) VALUES (?, ?, ?)")) {
				preparedStatement.setInt(1, 1);
				preparedStatement.setBytes(2, voter);
				preparedStatement.setInt(3, 1);
				preparedStatement.executeUpdate();
			}
			connection.commit();

			assertFalse(HSQLDBDatabaseUpdates.updateDatabase(connection));
			assertEquals(HSQLDBDatabaseUpdates.CURRENT_SCHEMA_VERSION, HSQLDBDatabaseUpdates.fetchDatabaseVersion(connection));

			assertColumnSize(connection, "POLLS", "POLL_NAME", 400);
			assertColumnSize(connection, "POLLOPTIONS", "OPTION_NAME", 400);
			assertColumnExists(connection, "POLLS", "START_WHEN");
			assertColumnExists(connection, "CREATEPOLLTRANSACTIONS", "START_WHEN");
			assertColumnExists(connection, "UPDATEPOLLTRANSACTIONS", "NEW_START_WHEN");
			assertColumnExists(connection, "UPDATEPOLLTRANSACTIONS", "PREVIOUS_START_WHEN");

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"SELECT option_index FROM VoteOnPollTransactionOptions WHERE signature = ?")) {
				preparedStatement.setBytes(1, signature);
				try (ResultSet resultSet = preparedStatement.executeQuery()) {
					assertTrue(resultSet.next());
					assertEquals(2, resultSet.getInt(1));
					assertFalse(resultSet.next());
				}
			}

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"SELECT option_index FROM VoteOnPollTransactionPreviousOptions WHERE signature = ?")) {
				preparedStatement.setBytes(1, signature);
				try (ResultSet resultSet = preparedStatement.executeQuery()) {
					assertTrue(resultSet.next());
					assertEquals(1, resultSet.getInt(1));
					assertFalse(resultSet.next());
				}
			}

			try (PreparedStatement preparedStatement = connection.prepareStatement(
					"INSERT INTO PollVotes(poll_id, voter, option_index) VALUES (?, ?, ?)")) {
				preparedStatement.setInt(1, 1);
				preparedStatement.setBytes(2, voter);
				preparedStatement.setInt(3, 2);
				preparedStatement.executeUpdate();
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void testInheritedRepositorySchemaVersionIsUnsupported() throws Exception {
		String connectionUrl = "jdbc:hsqldb:mem:inherited-schema-version-" + System.nanoTime();

		try (Connection connection = DriverManager.getConnection(connectionUrl, "SA", "")) {
			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE DatabaseInfo (version INTEGER NOT NULL)");
				statement.execute("INSERT INTO DatabaseInfo VALUES (73)");
			}
			connection.commit();

			try {
				HSQLDBDatabaseUpdates.updateDatabase(connection);
				fail("Inherited repository schema versions should not be upgraded");
			} catch (SQLException e) {
				assertTrue(e.getMessage().contains("Unsupported HSQLDB repository schema version 73"));
			}

			try (Statement statement = connection.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
	}

	private static void createMinimalVersionOnePollSchema(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE TYPE PUBLIC.EPOCHMILLIS AS BIGINT");
			statement.execute("CREATE TYPE PUBLIC.SIGNATURE AS VARBINARY(64)");
			statement.execute("CREATE TABLE PUBLIC.DATABASEINFO(VERSION INTEGER NOT NULL)");
			statement.execute("INSERT INTO PUBLIC.DATABASEINFO VALUES(1)");
			statement.execute("CREATE TABLE PUBLIC.CREATEPOLLTRANSACTIONS(SIGNATURE VARBINARY(64) PRIMARY KEY,POLL_NAME VARCHAR(128))");
			statement.execute("CREATE TABLE PUBLIC.CREATEPOLLTRANSACTIONOPTIONS(SIGNATURE VARBINARY(64),OPTION_INDEX TINYINT,OPTION_NAME VARCHAR(80))");
			statement.execute("CREATE TABLE PUBLIC.POLLS(POLL_ID INTEGER NOT NULL PRIMARY KEY,POLL_NAME VARCHAR(128),REDUCED_POLL_NAME VARCHAR(128))");
			statement.execute("CREATE TABLE PUBLIC.POLLOPTIONS(POLL_ID INTEGER NOT NULL,OPTION_INDEX TINYINT,OPTION_NAME VARCHAR(80))");
			statement.execute("CREATE TABLE PUBLIC.POLLVOTES(POLL_ID INTEGER NOT NULL,VOTER VARBINARY(32),OPTION_INDEX TINYINT NOT NULL,PRIMARY KEY(POLL_ID,VOTER))");
			statement.execute("CREATE TABLE PUBLIC.POLLFROZENRESULTS(POLL_ID INTEGER NOT NULL,OPTION_INDEX TINYINT)");
			statement.execute("CREATE TABLE PUBLIC.POLLFROZENVOTEDETAILS(POLL_ID INTEGER NOT NULL,VOTER VARBINARY(32),OPTION_INDEX TINYINT NOT NULL,PRIMARY KEY(POLL_ID,VOTER))");
			statement.execute("CREATE TABLE PUBLIC.UPDATEPOLLTRANSACTIONS(SIGNATURE VARBINARY(64) PRIMARY KEY,NEW_POLL_NAME VARCHAR(128),PREVIOUS_POLL_NAME VARCHAR(128))");
			statement.execute("CREATE TABLE PUBLIC.UPDATEPOLLTRANSACTIONOPTIONS(SIGNATURE VARBINARY(64),OPTION_INDEX TINYINT,OPTION_NAME VARCHAR(80))");
			statement.execute("CREATE TABLE PUBLIC.UPDATEPOLLTRANSACTIONPREVIOUSOPTIONS(SIGNATURE VARBINARY(64),OPTION_INDEX TINYINT,OPTION_NAME VARCHAR(80))");
			statement.execute("CREATE TABLE PUBLIC.VOTEONPOLLTRANSACTIONS(SIGNATURE VARBINARY(64) PRIMARY KEY,OPTION_INDEX TINYINT NOT NULL,PREVIOUS_OPTION_INDEX TINYINT)");
		}
	}

	private static void assertColumnExists(Connection connection, String tableName, String columnName) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getColumns(null, "PUBLIC", tableName, columnName)) {
			assertTrue(String.format("Column %s.%s should exist", tableName, columnName), resultSet.next());
		}
	}

	private static void assertColumnSize(Connection connection, String tableName, String columnName, int expectedSize) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getColumns(null, "PUBLIC", tableName, columnName)) {
			assertTrue(String.format("Column %s.%s should exist", tableName, columnName), resultSet.next());
			assertEquals(expectedSize, resultSet.getInt("COLUMN_SIZE"));
		}
	}

	private static void assertIndexExists(Connection connection, String tableName, String indexName) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getIndexInfo(null, "PUBLIC", tableName, false, false)) {
			while (resultSet.next()) {
				if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME")))
					return;
			}
		}

		fail(String.format("Index %s.%s should exist", tableName, indexName));
	}

	@Test
	public void testMultipleInstances() throws DataException {
		int n_instances = 5;
		Repository[] repositories = new Repository[n_instances];

		for (int i = 0; i < n_instances; ++i) {
			repositories[i] = RepositoryManager.getRepository();
			assertNotNull(repositories[i]);
		}

		for (int i = 0; i < n_instances; ++i) {
			repositories[i].close();
			repositories[i] = null;
		}
	}

	@Test
	public void testAccessAfterClose() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNotNull(repository);

			repository.close();

			try {
				repository.discardChanges();
				fail();
			} catch (NullPointerException | DataException e) {
			}

			LOGGER.warn("Expect \"repository already closed\" complaint below");
		}
	}

	@Test
	public void testDeadlock() {
		// Open connection 1
		try (final Repository repository1 = RepositoryManager.getRepository()) {

			// Do a database 'read'
			Account account1 = Common.getTestAccount(repository1, "alice");
			account1.getDefaultGroupId();

			// Open connection 2
			try (final Repository repository2 = RepositoryManager.getRepository()) {
				// Update account in 2
				Account account2 = Common.getTestAccount(repository2, "alice");
				account2.setConfirmedBalance(Asset.NATIVE, 1234L);
				repository2.saveChanges();
			}

			repository1.discardChanges();

			// Update account in 1
			account1.setConfirmedBalance(Asset.NATIVE, 5678L);
			repository1.saveChanges();
		} catch (DataException e) {
			fail("deadlock bug");
		}
	}

	@Test
	public void testUpdateReadDeadlock() {
		// Open connection 1
		try (final Repository repository1 = RepositoryManager.getRepository()) {
			// Mint blocks so we have data (online account signatures) to work with
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository1);

			// Perform database 'update', but don't commit at this stage
			repository1.getBlockRepository().trimOldOnlineAccountsSignatures(1, 10);

			// Open connection 2
			try (final Repository repository2 = RepositoryManager.getRepository()) {
				// Perform database read on same blocks - this should not deadlock
				repository2.getBlockRepository().getTimestampFromHeight(5);
			}

			// Save updates - this should not deadlock
			repository1.saveChanges();
		} catch (DataException e) {
			fail("deadlock bug");
		}
	}

	@Test
	public void testTrimDeadlock() {
		ExecutorService executor = Executors.newCachedThreadPool();
		CountDownLatch readyLatch = new CountDownLatch(1);
		CountDownLatch updateLatch = new CountDownLatch(1);
		CountDownLatch syncLatch = new CountDownLatch(1);

		// Open connection 1
		try (final HSQLDBRepository repository1 = (HSQLDBRepository) RepositoryManager.getRepository()) {
			// Read AT states trim height
			int atTrimHeight = repository1.getATRepository().getAtTrimHeight();
			repository1.discardChanges();

			// Open connection 2
			try (final HSQLDBRepository repository2 = (HSQLDBRepository) RepositoryManager.getRepository()) {
				// Read online signatures trim height
				int onlineSignaturesTrimHeight = repository2.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
				repository2.discardChanges();

				Future<Boolean> f2 = executor.submit(() -> {
					Object trimHeightsLock = extractTrimHeightsLock(repository2);
					System.out.println(String.format("f2: repository2's trimHeightsLock object: %s", trimHeightsLock));

					// Update online signatures trim height (implicit commit)
					synchronized (trimHeightsLock) {
						try {
							System.out.println("f2: updating online signatures trim height...");
							// simulate: repository2.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(onlineSignaturesTrimHeight);
							String updateSql = "UPDATE DatabaseInfo SET online_signatures_trim_height = ?";
							PreparedStatement pstmt = repository2.prepareStatement(updateSql);
							pstmt.setInt(1, onlineSignaturesTrimHeight);
							pstmt.executeUpdate();
							// But no commit/saveChanges yet to force HSQLDB error

							System.out.println("f2: readyLatch.countDown()");
							readyLatch.countDown();

							// wait for other thread to be ready to hit sync block
							System.out.println("f2: waiting for f1 syncLatch...");
							syncLatch.await();

							// hang on to trimHeightsLock to force other thread to wait (if code is correct), or to fail (if code is faulty)
							System.out.println("f2: updateLatch.await(<with timeout>)");
							if (!updateLatch.await(500L, TimeUnit.MILLISECONDS)) { // long enough for other thread to reach synchronized block
								// wait period expired suggesting no concurrent access, i.e. code is correct
								System.out.println("f2: updateLatch.await() timed out");

								System.out.println("f2: saveChanges()");
								repository2.saveChanges();

								return Boolean.TRUE;
							}

							System.out.println("f2: saveChanges()");
							repository2.saveChanges();

							// Early exit from wait period suggests concurrent access, i.e. code faulty
							return Boolean.FALSE;
						} catch (InterruptedException | SQLException e) {
							System.out.println("f2: exception: " + e.getMessage());
							return Boolean.FALSE;
						}
					}
				});

				System.out.println("waiting for f2 readyLatch...");
				readyLatch.await();
				System.out.println("launching f1...");

				Future<Boolean> f1 = executor.submit(() -> {
					Object trimHeightsLock = extractTrimHeightsLock(repository1);
					System.out.println(String.format("f1: repository1's trimHeightsLock object: %s", trimHeightsLock));

					System.out.println("f1: syncLatch.countDown()");
					syncLatch.countDown();

					// Update AT states trim height (implicit commit)
					synchronized (trimHeightsLock) {
						try {
							System.out.println("f1: updating AT trim height...");
							// simulate: repository1.getATRepository().setAtTrimHeight(atTrimHeight);
							String updateSql = "UPDATE DatabaseInfo SET AT_trim_height = ?";
							PreparedStatement pstmt = repository1.prepareStatement(updateSql);
							pstmt.setInt(1, atTrimHeight);
							pstmt.executeUpdate();
							System.out.println("f1: saveChanges()");
							repository1.saveChanges();

							System.out.println("f1: updateLatch.countDown()");
							updateLatch.countDown();

							return Boolean.TRUE;
						} catch (SQLException e) {
							System.out.println("f1: exception: " + e.getMessage());
							return Boolean.FALSE;
						}
					}
				});

				if (Boolean.TRUE != f1.get())
					fail("concurrency bug - simultaneous update of DatabaseInfo table");

				if (Boolean.TRUE != f2.get())
					fail("concurrency bug - not synchronized on same object?");
			} catch (InterruptedException e) {
				fail("concurrency bug: " + e.getMessage());
			} catch (ExecutionException e) {
				fail("concurrency bug: " + e.getMessage());
			}
		} catch (DataException e) {
			fail("database bug");
		}
	}

	private static Object extractTrimHeightsLock(HSQLDBRepository repository) {
		try {
			Field trimHeightsLockField = repository.getClass().getDeclaredField("trimHeightsLock");
			trimHeightsLockField.setAccessible(true);
			return trimHeightsLockField.get(repository);
		} catch (IllegalArgumentException | NoSuchFieldException | SecurityException | IllegalAccessException e) {
			fail();
			return null;
		}
	}

	/** Check that the <i>sub-query</i> used to fetch highest block height is optimized by HSQLDB. */
	@Test
	public void testBlockHeightSpeed() throws DataException, SQLException {
		final int mintBlockCount = 10000;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint some blocks
			System.out.println(String.format("Minting %d test blocks - should take approx. 10 seconds...", mintBlockCount));

			long beforeBigMint = System.currentTimeMillis();
			for (int i = 0; i < mintBlockCount; ++i)
				BlockUtils.mintBlock(repository);

			System.out.println(String.format("Minting %d blocks actually took %d seconds", mintBlockCount, (System.currentTimeMillis() - beforeBigMint) / 1000L));

			final HSQLDBRepository hsqldb = (HSQLDBRepository) repository;

			// Too slow:
			testSql(hsqldb, "SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks", false);

			// Fast but if there are no rows, then no result is returned, which causes some triggers to fail:
			testSql(hsqldb, "SELECT IFNULL(height, 0) + 1 FROM (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1)", true);

			// Too slow:
			testSql(hsqldb, "SELECT COUNT(*) + 1 FROM Blocks", false);

			// 2-stage, using cached value:
			hsqldb.prepareStatement("DROP TABLE IF EXISTS TestNextBlockHeight").execute();
			hsqldb.prepareStatement("CREATE TABLE TestNextBlockHeight (height INT NOT NULL)").execute();
			hsqldb.prepareStatement("INSERT INTO TestNextBlockHeight VALUES (SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks)").execute();

			// 1: Check fetching cached next block height is fast:
			testSql(hsqldb, "SELECT height from TestNextBlockHeight", true);

			// 2: Check updating NextBlockHeight (typically called via trigger) is fast:
			testSql(hsqldb, "UPDATE TestNextBlockHeight SET height = (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1)", true);
		}
	}

	/** Test proper action of interrupt inside an HSQLDB statement. */
	@Test
	public void testInterrupt() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

		try (final Repository repository = RepositoryManager.getRepository()) {
			final Thread testThread = Thread.currentThread();
			System.out.println(String.format("Thread ID: %s", testThread.getId()));

			// Queue interrupt
			executor.schedule(() -> testThread.interrupt(), 1000L, TimeUnit.MILLISECONDS);

			// Set rollback on interrupt
			@SuppressWarnings("resource")
			final HSQLDBRepository hsqldb = (HSQLDBRepository) repository;
			hsqldb.prepareStatement("SET DATABASE TRANSACTION ROLLBACK ON INTERRUPT TRUE").execute();

			// Create SQL procedure that calls hsqldbSleep() to block HSQLDB so we can interrupt()
			hsqldb.prepareStatement("CREATE PROCEDURE sleep(IN millis INT) LANGUAGE JAVA DETERMINISTIC NO SQL EXTERNAL NAME 'CLASSPATH:org.qortium.test.RepositoryTests.hsqldbSleep'").execute();

			// Execute long-running statement
			hsqldb.prepareStatement("CALL sleep(2000)").execute();

			if (!testThread.isInterrupted())
				// We should not reach here
				fail("Interrupt was swallowed");

			// Do not leak the deliberately queued interrupt into later test/repository cleanup.
			Thread.interrupted();
		} catch (DataException | SQLException e) {
			throw new AssertionError("Exception during interrupted HSQLDB statement", e);
		} finally {
			executor.shutdownNow();
			Thread.interrupted();
		}
	}

	/**
	 * Test HSQLDB bug-fix for INSERT INTO...ON DUPLICATE KEY UPDATE... bug
	 * <p>
	 * @see <A HREF="https://sourceforge.net/p/hsqldb/discussion/73674/thread/d8d35adb5d/">Behaviour of 'ON DUPLICATE KEY UPDATE'</A> SourceForge discussion
	 */
	@Test
	public void testOnDuplicateKeyUpdateBugFix() throws SQLException, DataException {
		ResultSet resultSet;

		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			hsqldb.prepareStatement("DROP TABLE IF EXISTS bugtest").execute();
			hsqldb.prepareStatement("CREATE TABLE bugtest (id INT NOT NULL, counter INT NOT NULL, PRIMARY KEY(id))").execute();

			// No existing row, so new row's "counter" is set to value from VALUES clause, i.e. 1
			hsqldb.prepareStatement("INSERT INTO bugtest (id, counter) VALUES (1, 1) ON DUPLICATE KEY UPDATE counter = counter + 1").execute();
			resultSet = hsqldb.checkedExecute("SELECT counter FROM bugtest WHERE id = 1");
			assertNotNull(resultSet);
			assertEquals(1, resultSet.getInt(1));

			// Prior to bug-fix, "counter = counter + 1" would always use the 100 from VALUES, instead of existing row's value, for "counter"
			hsqldb.prepareStatement("INSERT INTO bugtest (id, counter) VALUES (1, 100) ON DUPLICATE KEY UPDATE counter = counter + 1").execute();
			resultSet = hsqldb.checkedExecute("SELECT counter FROM bugtest WHERE id = 1");
			assertNotNull(resultSet);
			// Prior to bug-fix, this would be 100 + 1 = 101
			assertEquals(2, resultSet.getInt(1));
		}
	}

	/**
	 * Test HSQLDB bug-fix for "General Error" in non-fully-qualified columns inside LATERAL()
	 * <p>
	 * @see <A HREF="https://sourceforge.net/p/hsqldb/bugs/1580/">#1580 General error with LATERAL and transitive join column</A> SourceForge ticket
	 */
	@Test
	public void testOnLateralGeneralError() {
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			hsqldb.prepareStatement("DROP TABLE IF EXISTS tableA").execute();
			hsqldb.prepareStatement("DROP TABLE IF EXISTS tableB").execute();
			hsqldb.prepareStatement("DROP TABLE IF EXISTS tableC").execute();

			hsqldb.prepareStatement("CREATE TABLE tableA (col1 INT)").execute();
			hsqldb.prepareStatement("CREATE TABLE tableB (col1 INT)").execute();
			hsqldb.prepareStatement("CREATE TABLE tableC (col2 INT, PRIMARY KEY (col2))").execute();

			// Prior to bug-fix #1580 this would throw a General Error SQL Exception
			hsqldb.prepareStatement("SELECT col3 FROM tableA JOIN tableB USING (col1) CROSS JOIN LATERAL(SELECT col2 FROM tableC WHERE col2 = col1) AS tableC (col3)").execute();
		} catch (SQLException | DataException e) {
			fail("HSQLDB bug #1580");
		}
	}

	/** Specifically test LATERAL() usage in Asset repository */
	@Test
	public void testAssetLateral() {
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			List<Long> assetIds = Collections.emptyList();
			List<Long> otherAssetIds = Collections.emptyList();
			Integer limit = null;
			Integer offset = null;
			Boolean reverse = null;

			hsqldb.getAssetRepository().getRecentTrades(assetIds, otherAssetIds, limit, offset, reverse);
		} catch (DataException e) {
			fail("HSQLDB bug #1580");
		}
	}

	/** Specifically test LATERAL() usage in AT repository */
	@Test
	public void testAtLateral() {
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			byte[] codeHash = BitcoinyACCTv3.CODE_BYTES_HASH;
			Boolean isFinished = null;
			Integer dataByteOffset = null;
			Long expectedValue = null;
			Integer minimumFinalHeight = 2;
			Integer limit = null;
			Integer offset = null;
			Boolean reverse = null;

			hsqldb.getATRepository().getMatchingFinalATStates(codeHash,null, null, isFinished, dataByteOffset, expectedValue, minimumFinalHeight, limit, offset, reverse);
		} catch (DataException e) {
			fail("HSQLDB bug #1580");
		}
	}

	/** Specifically test LATERAL() usage in Chat repository with hasChatReference */
	@Test
	public void testChatLateral() {
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			String address = Crypto.toAddress(new byte[32]);

			// Test without hasChatReference
			hsqldb.getChatRepository().getActiveChats(address, ChatMessage.Encoding.BASE58, null);

			// Test with hasChatReference = true
			hsqldb.getChatRepository().getActiveChats(address, ChatMessage.Encoding.BASE58, true);

			// Test with hasChatReference = false
			hsqldb.getChatRepository().getActiveChats(address, ChatMessage.Encoding.BASE58, false);
		} catch (DataException e) {
			fail("HSQLDB bug #1580");
		}
	}

	/** Test batched DELETE */
	@Test
	public void testBatchedDelete() {
		// Generate test data
		List<Object[]> batchedObjects = new ArrayList<>();
		for (int i = 0; i < 100; ++i)
			batchedObjects.add(new Object[] { String.valueOf(i), 1L });

		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {
			hsqldb.deleteBatch("AccountBalances", "account = ? AND asset_id = ?", batchedObjects);
			hsqldb.discardChanges();
		} catch (DataException | SQLException e) {
			fail("Batched delete failed: " + e.getMessage());
		}
	}

	@Test
	public void testDefrag() throws DataException, TimeoutException {
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

			this.populateWithRandomData(hsqldb);

			hsqldb.performPeriodicMaintenance(10 * 1000L);

		}
	}

	@Test
	public void testDefragOnDisk() throws DataException, TimeoutException {
		Common.useSettingsAndDb(testSettingsFilename, false);

		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

			this.populateWithRandomData(hsqldb);

			hsqldb.performPeriodicMaintenance(10 * 1000L);

		}
	}

	@Test
	public void testMultipleDefrags() throws DataException, TimeoutException {
		// Mint some more blocks to populate the database
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

			this.populateWithRandomData(hsqldb);

			for (int i = 0; i < 10; i++) {
				hsqldb.performPeriodicMaintenance(10 * 1000L);
			}
		}
	}

	@Test
	public void testMultipleDefragsOnDisk() throws DataException, TimeoutException {
		Common.useSettingsAndDb(testSettingsFilename, false);

		// Mint some more blocks to populate the database
		try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

			this.populateWithRandomData(hsqldb);

			for (int i = 0; i < 10; i++) {
				hsqldb.performPeriodicMaintenance(10 * 1000L);
			}
		}
	}

	@Test
	public void testMultipleDefragsWithDifferentData() throws DataException, TimeoutException {
		for (int i=0; i<10; i++) {
			try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

				this.populateWithRandomData(hsqldb);
				hsqldb.performPeriodicMaintenance(10 * 1000L);
			}
		}
	}

	@Test
	public void testMultipleDefragsOnDiskWithDifferentData() throws DataException, TimeoutException {
		Common.useSettingsAndDb(testSettingsFilename, false);

		for (int i=0; i<10; i++) {
			try (final HSQLDBRepository hsqldb = (HSQLDBRepository) RepositoryManager.getRepository()) {

				this.populateWithRandomData(hsqldb);
				hsqldb.performPeriodicMaintenance(10 * 1000L);
			}
		}
	}

	private void populateWithRandomData(HSQLDBRepository repository) throws DataException {
		Random random = new Random();

		System.out.println("Creating deterministic accounts...");

		// Generate some deterministic accounts
		List<Account> accounts = new ArrayList<>();
		for (int ai = 0; ai < 20; ++ai) {
			Account account = Common.generateDeterministicSeedAccount(repository, "repository-random-data", ai);
			accounts.add(account);

			AccountData accountData = new AccountData(account.getAddress());
			repository.getAccountRepository().ensureAccount(accountData);
		}
		repository.saveChanges();

		System.out.println("Creating random balances...");

		// Fill with lots of random balances
		for (int i = 0; i < 100000; ++i) {
			Account account = accounts.get(random.nextInt(accounts.size()));
			int assetId = random.nextInt(2);
			long balance = random.nextInt(100000);

			AccountBalanceData accountBalanceData = new AccountBalanceData(account.getAddress(), assetId, balance);
			repository.getAccountRepository().save(accountBalanceData);

			// Maybe mint a block to change height
			if (i > 0 && (i % 1000) == 0)
				BlockUtils.mintBlock(repository);
		}
		repository.saveChanges();
	}

	public static void hsqldbSleep(int millis) throws SQLException {
		System.out.println(String.format("HSQLDB sleep() thread ID: %s", Thread.currentThread().getId()));

		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void testSql(HSQLDBRepository hsqldb, String sql, boolean isFast) throws DataException, SQLException {
		// Execute query to prime caches
		hsqldb.prepareStatement(sql).execute();

		// Execute again for a slightly more accurate timing
		final long start = System.currentTimeMillis();
		hsqldb.prepareStatement(sql).execute();

		final long executionTime = System.currentTimeMillis() - start;
		System.out.println(String.format("%s: [%d ms] SQL: %s", (isFast ? "fast": "slow"), executionTime, sql));

		final long threshold = 3; // ms
		assertTrue( !isFast || executionTime < threshold);
	}

}
