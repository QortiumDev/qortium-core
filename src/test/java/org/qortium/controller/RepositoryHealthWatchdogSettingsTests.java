package org.qortium.controller;

import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepositoryHealthWatchdogSettingsTests extends Common {

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testRepositoryWatchdogDefaultsOnWithFiveMinuteRecovery() throws Exception {
		loadSettings("{}");
		assertTrue(Settings.getInstance().isRepositoryHealthWatchdogEnabled());
		assertEquals(30_000L, Settings.getInstance().getRepositoryHealthCheckInterval());
		assertEquals(300_000L, Settings.getInstance().getRepositoryHealthRestartTimeout());
	}

	@Test
	public void testRepositoryWatchdogCanBeDisabledAndTuned() throws Exception {
		loadSettings("{\"repositoryHealthWatchdogEnabled\":false,"
				+ "\"repositoryHealthCheckInterval\":2000,"
				+ "\"repositoryHealthRestartTimeout\":10000}");
		assertFalse(Settings.getInstance().isRepositoryHealthWatchdogEnabled());
		assertEquals(2_000L, Settings.getInstance().getRepositoryHealthCheckInterval());
		assertEquals(10_000L, Settings.getInstance().getRepositoryHealthRestartTimeout());
	}

	private static void loadSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("repository-health-watchdog-settings-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.writeString(settingsPath, json);
		Settings.fileInstance(settingsPath.toString());
	}
}
