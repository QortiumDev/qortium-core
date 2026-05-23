package org.qortal.controller;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;
import org.qortal.test.common.LogLevelOverride;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RestartNodeTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		RestartNode.releaseRestartApply();
	}

	@After
	public void afterTest() throws DataException {
		RestartNode.releaseRestartApply();
		Common.useDefaultSettings();
	}

	@Test
	public void testDirectRestartIsRejectedWhenApplyInProgress() {
		assertTrue(RestartNode.tryAcquireRestartApply());

		assertFalse(RestartNode.attemptToRestart());
		assertTrue(RestartNode.isRestartApplyInProgress());
	}

	@Test
	public void testScheduledRestartIsRejectedWhenApplyInProgress() {
		assertTrue(RestartNode.tryAcquireRestartApply());

		assertFalse(RestartNode.scheduleRestart());
		assertTrue(RestartNode.isRestartApplyInProgress());
	}

	@Test
	public void testFailedRestartLaunchReleasesApplyGuard() throws Exception {
		String originalJavaHome = System.getProperty("java.home");
		Path missingJavaHome = Files.createTempDirectory("missing-java-home");

		try {
			System.setProperty("java.home", missingJavaHome.toString());

			try (LogLevelOverride ignored = LogLevelOverride.setLevel(RestartNode.class, Level.FATAL)) {
				RestartNode.attemptToRestart();
			}
			assertFalse(RestartNode.isRestartApplyInProgress());

		} finally {
			if (originalJavaHome == null)
				System.clearProperty("java.home");
			else
				System.setProperty("java.home", originalJavaHome);

			Files.deleteIfExists(missingJavaHome);
		}
	}
}
