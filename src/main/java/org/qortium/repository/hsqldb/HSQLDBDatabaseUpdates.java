package org.qortium.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.controller.Controller;
import org.qortium.utils.StartupStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class HSQLDBDatabaseUpdates {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBDatabaseUpdates.class);

	public static final int CURRENT_SCHEMA_VERSION = 5;

	private static final String BASELINE_SCHEMA_RESOURCE = "/repository/hsqldb-baseline.sql";

	/**
	 * Initialize a fresh Qortium database schema.
	 *
	 * @return true if database was non-existent/empty, false otherwise
	 * @throws SQLException
	 */
	public static boolean updateDatabase(Connection connection) throws SQLException {
		int databaseVersion = fetchDatabaseVersion(connection);

		if (databaseVersion == CURRENT_SCHEMA_VERSION) {
			// Local-only (non-consensus) tables live outside the schema version counter so an
			// existing node picks them up on restart without a repository reset.
			ensureLocalTables(connection);
			ensureArbitraryTransactionCreatedWhen(connection);
			connection.commit();

			updateStartupStatus();
			return false;
		}

		if (databaseVersion == 1) {
			upgradeFromVersion1(connection);
			databaseVersion = 2;
		}

		if (databaseVersion == 2) {
			upgradeFromVersion2(connection);
			databaseVersion = 3;
		}

		if (databaseVersion == 3) {
			upgradeFromVersion3(connection);
			databaseVersion = 4;
		}
		if (databaseVersion == 4) {
			upgradeFromVersion4(connection);
			ensureLocalTables(connection);
			ensureArbitraryTransactionCreatedWhen(connection);
			connection.commit();

			updateStartupStatus();
			return false;
		}

		if (databaseVersion != 0)
			throw new SQLException(String.format(
					"Unsupported HSQLDB repository schema version %d. Qortium starts fresh repositories at schema version %d and no longer upgrades inherited upstream database versions. Reset the repository path or bootstrap from a fresh Qortium database.",
					databaseVersion, CURRENT_SCHEMA_VERSION));

		StartupStatus.update("Initializing Qortium database, please wait...");

		executeBaselineSchema(connection);
		ensureLocalTables(connection);
		connection.commit();

		LOGGER.info("Initialized Qortium HSQLDB repository schema version {}", CURRENT_SCHEMA_VERSION);

		updateStartupStatus();

		return true;
	}

	/**
	 * Create local-only (non-consensus) tables if they do not already exist.
	 * <p>
	 * These hold node-local derived data that is NOT part of chain consensus, so they are
	 * created idempotently on every startup and are intentionally decoupled from
	 * {@link #CURRENT_SCHEMA_VERSION} — a node can be upgraded to populate them without a
	 * coordinated network update or repository reset.
	 *
	 * @throws SQLException
	 */
	private static void ensureLocalTables(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			// Per-block online-accounts index. The block's own ONLINE_ACCOUNTS column stores
			// positional indices into the (mutable) sorted self-share set, so they cannot be
			// resolved historically once minting accounts are added/removed. This table records
			// the absolute reward-share public keys that were online for each block (captured at
			// block-processing time), concatenated as fixed 32-byte keys, so they stay resolvable.
			stmt.execute("CREATE TABLE IF NOT EXISTS PUBLIC.BLOCKONLINEACCOUNTS ("
					+ "HEIGHT INTEGER PRIMARY KEY, "
					+ "ONLINE_REWARD_SHARES VARBINARY(1048576) NOT NULL)");

			// One-row local progress marker for checkpoint-backed archive replay. This lets a node commit
			// replayed blocks in restartable segments while keeping normal sync/minting blocked until the
			// checkpoint-spanning range has validated and the row is cleared.
			stmt.execute("CREATE TABLE IF NOT EXISTS PUBLIC.ARCHIVEREPLAYSTATE ("
					+ "ID INTEGER PRIMARY KEY, "
					+ "START_HEIGHT INTEGER NOT NULL, "
					+ "CHECKPOINT_HEIGHT INTEGER NOT NULL, "
					+ "CHECKPOINT_SIGNATURE VARBINARY(128) NOT NULL, "
					+ "TARGET_HEIGHT INTEGER NOT NULL, "
					+ "LAST_REPLAYED_HEIGHT INTEGER NOT NULL, "
					+ "UPDATED_WHEN PUBLIC.EPOCHMILLIS NOT NULL)");
		}
	}

	private static void upgradeFromVersion1(Connection connection) throws SQLException {
		StartupStatus.update("Upgrading Qortium database to schema v2, please wait...");

		try (Statement stmt = connection.createStatement()) {
			stmt.execute("ALTER TABLE PUBLIC.CREATEPOLLTRANSACTIONS ALTER COLUMN POLL_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.CREATEPOLLTRANSACTIONOPTIONS ALTER COLUMN OPTION_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.POLLS ALTER COLUMN POLL_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.POLLS ALTER COLUMN REDUCED_POLL_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.POLLOPTIONS ALTER COLUMN OPTION_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONS ALTER COLUMN NEW_POLL_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONS ALTER COLUMN PREVIOUS_POLL_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONOPTIONS ALTER COLUMN OPTION_NAME SET DATA TYPE VARCHAR(400)");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONPREVIOUSOPTIONS ALTER COLUMN OPTION_NAME SET DATA TYPE VARCHAR(400)");

			stmt.execute("ALTER TABLE PUBLIC.CREATEPOLLTRANSACTIONOPTIONS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.VOTEONPOLLTRANSACTIONS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.VOTEONPOLLTRANSACTIONS ALTER COLUMN PREVIOUS_OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.POLLOPTIONS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.POLLVOTES ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.POLLFROZENRESULTS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.POLLFROZENVOTEDETAILS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONOPTIONS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONPREVIOUSOPTIONS ALTER COLUMN OPTION_INDEX SET DATA TYPE SMALLINT");

			stmt.execute("ALTER TABLE PUBLIC.CREATEPOLLTRANSACTIONS ADD COLUMN START_WHEN PUBLIC.EPOCHMILLIS");
			stmt.execute("ALTER TABLE PUBLIC.POLLS ADD COLUMN START_WHEN PUBLIC.EPOCHMILLIS");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONS ADD COLUMN NEW_START_WHEN PUBLIC.EPOCHMILLIS");
			stmt.execute("ALTER TABLE PUBLIC.UPDATEPOLLTRANSACTIONS ADD COLUMN PREVIOUS_START_WHEN PUBLIC.EPOCHMILLIS");

			stmt.execute("CREATE TABLE PUBLIC.VOTEONPOLLTRANSACTIONOPTIONS("
					+ "SIGNATURE PUBLIC.SIGNATURE, "
					+ "OPTION_INDEX SMALLINT NOT NULL, "
					+ "PRIMARY KEY(SIGNATURE,OPTION_INDEX), "
					+ "FOREIGN KEY(SIGNATURE) REFERENCES PUBLIC.VOTEONPOLLTRANSACTIONS(SIGNATURE) ON DELETE CASCADE)");
			stmt.execute("CREATE TABLE PUBLIC.VOTEONPOLLTRANSACTIONPREVIOUSOPTIONS("
					+ "SIGNATURE PUBLIC.SIGNATURE, "
					+ "OPTION_INDEX SMALLINT NOT NULL, "
					+ "PRIMARY KEY(SIGNATURE,OPTION_INDEX), "
					+ "FOREIGN KEY(SIGNATURE) REFERENCES PUBLIC.VOTEONPOLLTRANSACTIONS(SIGNATURE) ON DELETE CASCADE)");
			stmt.execute("INSERT INTO PUBLIC.VOTEONPOLLTRANSACTIONOPTIONS(SIGNATURE, OPTION_INDEX) "
					+ "SELECT SIGNATURE, OPTION_INDEX FROM PUBLIC.VOTEONPOLLTRANSACTIONS WHERE OPTION_INDEX <> 0");
			stmt.execute("INSERT INTO PUBLIC.VOTEONPOLLTRANSACTIONPREVIOUSOPTIONS(SIGNATURE, OPTION_INDEX) "
					+ "SELECT SIGNATURE, PREVIOUS_OPTION_INDEX FROM PUBLIC.VOTEONPOLLTRANSACTIONS "
					+ "WHERE PREVIOUS_OPTION_INDEX IS NOT NULL AND PREVIOUS_OPTION_INDEX <> 0");

			stmt.execute("ALTER TABLE PUBLIC.POLLVOTES DROP PRIMARY KEY");
			stmt.execute("ALTER TABLE PUBLIC.POLLVOTES ADD PRIMARY KEY(POLL_ID,VOTER,OPTION_INDEX)");
			stmt.execute("ALTER TABLE PUBLIC.POLLFROZENVOTEDETAILS DROP PRIMARY KEY");
			stmt.execute("ALTER TABLE PUBLIC.POLLFROZENVOTEDETAILS ADD PRIMARY KEY(POLL_ID,VOTER,OPTION_INDEX)");

			stmt.execute("UPDATE PUBLIC.DATABASEINFO SET VERSION = 2");
		}

		LOGGER.info("Upgraded Qortium HSQLDB repository schema from version 1 to version 2");
	}

	private static void upgradeFromVersion2(Connection connection) throws SQLException {
		StartupStatus.update("Upgrading Qortium database to schema v3, please wait...");

		try (Statement stmt = connection.createStatement()) {
			stmt.execute("ALTER TABLE PUBLIC.ATSTATES ADD COLUMN MAP_ROOT PUBLIC.ATSTATEHASH");
			stmt.execute("CREATE TABLE PUBLIC.ATMAPENTRIES("
					+ "AT_ADDRESS PUBLIC.ACCOUNTADDRESS NOT NULL, "
					+ "KEY1 BIGINT NOT NULL, "
					+ "KEY2 BIGINT NOT NULL, "
					+ "\"VALUE\" BIGINT NOT NULL CHECK (\"VALUE\" <> 0), "
					+ "PRIMARY KEY(AT_ADDRESS,KEY1,KEY2), "
					+ "FOREIGN KEY(AT_ADDRESS) REFERENCES PUBLIC.ATS(AT_ADDRESS) ON DELETE CASCADE)");
			stmt.execute("CREATE TABLE PUBLIC.ATMAPENTRYCHANGES("
					+ "HEIGHT INTEGER NOT NULL, "
					+ "SEQUENCE INTEGER NOT NULL, "
					+ "AT_ADDRESS PUBLIC.ACCOUNTADDRESS NOT NULL, "
					+ "KEY1 BIGINT NOT NULL, "
					+ "KEY2 BIGINT NOT NULL, "
					+ "PREVIOUS_VALUE BIGINT, "
					+ "NEW_VALUE BIGINT, "
					+ "CHECK (PREVIOUS_VALUE IS NULL OR PREVIOUS_VALUE <> 0), "
					+ "CHECK (NEW_VALUE IS NULL OR NEW_VALUE <> 0), "
					+ "PRIMARY KEY(HEIGHT,SEQUENCE), "
					+ "FOREIGN KEY(AT_ADDRESS) REFERENCES PUBLIC.ATS(AT_ADDRESS) ON DELETE CASCADE)");
			stmt.execute("CREATE INDEX PUBLIC.ATMAPENTRYCHANGESADDRESSKEYINDEX "
					+ "ON PUBLIC.ATMAPENTRYCHANGES(AT_ADDRESS,KEY1,KEY2,HEIGHT)");
			stmt.execute("UPDATE PUBLIC.DATABASEINFO SET VERSION = 3");
		}

		LOGGER.info("Upgraded Qortium HSQLDB repository schema from version 2 to version 3");
	}

	private static void upgradeFromVersion3(Connection connection) throws SQLException {
		StartupStatus.update("Upgrading Qortium database to schema v4, please wait...");
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("ALTER TABLE PUBLIC.\"GROUPS\" ADD COLUMN AVATAR_SERVICE SMALLINT");
			stmt.execute("ALTER TABLE PUBLIC.\"GROUPS\" ADD COLUMN AVATAR_NAME PUBLIC.REGISTEREDNAME");
			stmt.execute("ALTER TABLE PUBLIC.\"GROUPS\" ADD COLUMN AVATAR_IDENTIFIER VARCHAR(64)");
			stmt.execute("CREATE TABLE PUBLIC.SETGROUPAVATARTRANSACTIONS("
					+ "SIGNATURE PUBLIC.SIGNATURE PRIMARY KEY, OWNER PUBLIC.ACCOUNTPUBLICKEY NOT NULL, GROUP_ID PUBLIC.GROUPID NOT NULL, "
					+ "AVATAR_SERVICE SMALLINT, AVATAR_NAME PUBLIC.REGISTEREDNAME, AVATAR_IDENTIFIER VARCHAR(64), GROUP_REFERENCE PUBLIC.SIGNATURE, "
					+ "FOREIGN KEY(SIGNATURE) REFERENCES PUBLIC.TRANSACTIONS(SIGNATURE) ON DELETE CASCADE)");
			stmt.execute("UPDATE PUBLIC.DATABASEINFO SET VERSION = 4");
		}
		LOGGER.info("Upgraded Qortium HSQLDB repository schema from version 3 to version 4");
	}

	private static void upgradeFromVersion4(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("CREATE TABLE PUBLIC.ACCOUNTAVATARS(ACCOUNT PUBLIC.ACCOUNTADDRESS PRIMARY KEY, AVATAR_SERVICE SMALLINT NOT NULL, AVATAR_NAME PUBLIC.REGISTEREDNAME NOT NULL, AVATAR_IDENTIFIER VARCHAR(64) NOT NULL, FOREIGN KEY(ACCOUNT) REFERENCES PUBLIC.ACCOUNTS(ACCOUNT) ON DELETE CASCADE)");
			stmt.execute("CREATE TABLE PUBLIC.SETACCOUNTAVATARTRANSACTIONS(SIGNATURE PUBLIC.SIGNATURE PRIMARY KEY, OWNER PUBLIC.ACCOUNTPUBLICKEY NOT NULL, AVATAR_SERVICE SMALLINT, AVATAR_NAME PUBLIC.REGISTEREDNAME, AVATAR_IDENTIFIER VARCHAR(64), PREVIOUS_AVATAR_SERVICE SMALLINT, PREVIOUS_AVATAR_NAME PUBLIC.REGISTEREDNAME, PREVIOUS_AVATAR_IDENTIFIER VARCHAR(64), FOREIGN KEY(SIGNATURE) REFERENCES PUBLIC.TRANSACTIONS(SIGNATURE) ON DELETE CASCADE)");
			stmt.execute("UPDATE PUBLIC.DATABASEINFO SET VERSION = 5");
		}
	}

	/**
	 * Fetch current version of database schema.
	 *
	 * @return database version, or 0 if no schema yet
	 * @throws SQLException
	 */
	public static int fetchDatabaseVersion(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			if (stmt.execute("SELECT version FROM DatabaseInfo"))
				try (ResultSet resultSet = stmt.getResultSet()) {
					if (resultSet.next())
						return resultSet.getInt(1);
				}
		} catch (SQLException e) {
			// empty database
		}

		return 0;
	}

	private static void executeBaselineSchema(Connection connection) throws SQLException {
		try (InputStream inputStream = HSQLDBDatabaseUpdates.class.getResourceAsStream(BASELINE_SCHEMA_RESOURCE)) {
			if (inputStream == null)
				throw new SQLException("Missing HSQLDB baseline schema resource: " + BASELINE_SCHEMA_RESOURCE);

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
					Statement stmt = connection.createStatement()) {
				String line;
				while ((line = reader.readLine()) != null) {
					String sql = line.trim();
					if (sql.isEmpty() || sql.startsWith("--"))
						continue;

					stmt.execute(sql);
				}
			}
		} catch (IOException e) {
			throw new SQLException("Unable to read HSQLDB baseline schema resource: " + BASELINE_SCHEMA_RESOURCE, e);
		}
	}

	private static void updateStartupStatus() {
		String text = String.format("Starting Qortium Core v%s...", Controller.getInstance().getVersionStringWithoutPrefix());
		StartupStatus.update(text);
	}


	private static void ensureArbitraryTransactionCreatedWhen(Connection connection) throws SQLException {
		if (!tableExists(connection, "ARBITRARYTRANSACTIONS"))
			return;

		if (!columnExists(connection, "ARBITRARYTRANSACTIONS", "CREATED_WHEN")) {
			LOGGER.info("Denormalizing created_when into ArbitraryTransactions - please wait...");
			try (Statement stmt = connection.createStatement()) {
				stmt.execute("ALTER TABLE PUBLIC.ARBITRARYTRANSACTIONS ADD CREATED_WHEN PUBLIC.EPOCHMILLIS");
				stmt.execute("UPDATE PUBLIC.ARBITRARYTRANSACTIONS SET CREATED_WHEN = "
						+ "(SELECT CREATED_WHEN FROM PUBLIC.TRANSACTIONS WHERE SIGNATURE = PUBLIC.ARBITRARYTRANSACTIONS.SIGNATURE)");
				stmt.execute("ALTER TABLE PUBLIC.ARBITRARYTRANSACTIONS ALTER COLUMN CREATED_WHEN SET NOT NULL");
			}
		}

		if (!indexExists(connection, "ARBITRARYTRANSACTIONS", "ARBITRARYNAMECREATEDINDEX")) {
			try (Statement stmt = connection.createStatement()) {
				stmt.execute("CREATE INDEX PUBLIC.ARBITRARYNAMECREATEDINDEX ON PUBLIC.ARBITRARYTRANSACTIONS (NAME, CREATED_WHEN DESC)");
			}
		}

		connection.commit();
	}

	private static boolean tableExists(Connection connection, String tableName) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getTables(null, "PUBLIC", tableName, null)) {
			return resultSet.next();
		}
	}

	private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getColumns(null, "PUBLIC", tableName, columnName)) {
			return resultSet.next();
		}
	}

	private static boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
		try (ResultSet resultSet = connection.getMetaData().getIndexInfo(null, "PUBLIC", tableName, false, false)) {
			while (resultSet.next()) {
				String candidate = resultSet.getString("INDEX_NAME");
				if (indexName.equalsIgnoreCase(candidate))
					return true;
			}
		}

		return false;
	}
}
