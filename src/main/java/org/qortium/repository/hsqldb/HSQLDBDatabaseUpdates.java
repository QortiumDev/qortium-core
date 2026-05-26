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

	public static final int CURRENT_SCHEMA_VERSION = 1;

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
			updateStartupStatus();
			return false;
		}

		if (databaseVersion != 0)
			throw new SQLException(String.format(
					"Unsupported HSQLDB repository schema version %d. Qortium starts fresh repositories at schema version %d and no longer upgrades inherited upstream database versions. Reset the repository path or bootstrap from a fresh Qortium database.",
					databaseVersion, CURRENT_SCHEMA_VERSION));

		StartupStatus.update("Initializing Qortium database, please wait...");

		executeBaselineSchema(connection);
		connection.commit();

		LOGGER.info("Initialized Qortium HSQLDB repository schema version {}", CURRENT_SCHEMA_VERSION);

		updateStartupStatus();

		return true;
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

}
