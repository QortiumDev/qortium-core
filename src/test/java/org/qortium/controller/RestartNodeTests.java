package org.qortium.controller;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.test.common.LogLevelOverride;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

	@Test
	public void testEmergencyRestartCommandPinsParentAndPreservesArguments() {
		List<String> command = RestartNode.buildEmergencyRestartCommand(
				Path.of("/java"),
				List.of("-Xmx1g", "-agentlib:jdwp=transport=dt_socket"),
				"/core/qortium.jar",
				new String[]{"/runtime/settings.json"},
				1234L);

		assertTrue(command.contains("-D" + org.qortium.ApplyRestart.EMERGENCY_PARENT_PID_PROPERTY + "=1234"));
		assertTrue(command.contains(RestartNode.AGENTLIB_JVM_HOLDER_ARG + ":jdwp=transport=dt_socket"));
		assertTrue(command.contains("/core/qortium.jar"));
		assertTrue(command.contains("/runtime/settings.json"));
	}
}
