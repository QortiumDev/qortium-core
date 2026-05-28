package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.ApplyUpdate;
import org.qortium.settings.Settings;
import org.qortium.settings.Settings.AutoUpdateMode;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoUpdateTests {

	@Before
	public void beforeTest() {
		AutoUpdate.releaseUpdateInstall();
	}

	@After
	public void afterTest() {
		AutoUpdate.releaseUpdateInstall();
	}

	private Settings newSettingsInstance() throws ReflectiveOperationException {
		Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@Test
	public void testDefaultAutoUpdateModeIsDisabled() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertEquals(AutoUpdateMode.OFF, settings.getAutoUpdateMode());
	}

	@Test
	public void testBackgroundAutoUpdateChecksSoonAfterStartup() {
		assertEquals(30_000L, AutoUpdate.INITIAL_CHECK_DELAY);
		assertEquals(20 * 60 * 1000L, AutoUpdate.CHECK_INTERVAL);
		assertTrue(AutoUpdate.INITIAL_CHECK_DELAY < AutoUpdate.CHECK_INTERVAL);
	}

	@Test
	public void testQdnDownloadRetryIntervalsAreOperatorFriendly() {
		assertEquals(60_000L, AutoUpdate.QDN_DOWNLOAD_RETRY_INTERVAL);
		assertEquals(30_000L, AutoUpdate.MANUAL_QDN_DOWNLOAD_RETRY_INTERVAL);
		assertEquals(60_000L, AutoUpdate.QDN_DOWNLOAD_STALL_INTERVAL);
		assertTrue(AutoUpdate.MANUAL_QDN_DOWNLOAD_RETRY_INTERVAL < AutoUpdate.CHECK_INTERVAL);
		assertTrue(AutoUpdate.QDN_DOWNLOAD_STALL_INTERVAL >= AutoUpdate.MANUAL_QDN_DOWNLOAD_RETRY_INTERVAL);
	}

	@Test
	public void testAutoUpdateModeCanEnableInstallMode() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "autoUpdateMode", AutoUpdateMode.INSTALL, true);

		assertEquals(AutoUpdateMode.INSTALL, settings.getAutoUpdateMode());
	}

	@Test
	public void testAutoUpdateModeCanUseNotifyMode() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "autoUpdateMode", AutoUpdateMode.NOTIFY, true);

		assertEquals(AutoUpdateMode.NOTIFY, settings.getAutoUpdateMode());
	}

	@Test
	public void testSanitizeJvmArgumentsReplacesAgentlibAndRemovesJniArgs() {
		List<String> inputArgs = Arrays.asList(
				"-Xmx1g",
				"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
				"abort",
				"exit",
				"vfprintf",
				"-Dfoo=bar"
		);

		List<String> sanitized = AutoUpdate.sanitizeJvmArguments(inputArgs);

		assertTrue(sanitized.contains("-Xmx1g"));
		assertTrue(sanitized.contains("-Dfoo=bar"));
		assertTrue(sanitized.contains("-DQORTIUM_agentlib=:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"));
		assertFalse(sanitized.contains("abort"));
		assertFalse(sanitized.contains("exit"));
		assertFalse(sanitized.contains("vfprintf"));
	}

	@Test
	public void testBuildApplyUpdateCommandIncludesJvmArgsWhenRequested() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g", "-agentlib:test=foo", "abort");
		String[] savedArgs = new String[]{"--alpha", "beta"};
		String javaExecutable = "/tmp/java";

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				javaExecutable,
				true,
				runtimeInputArgs,
				savedArgs,
				Paths.get("/tmp/new-qortium.jar")
		);

		assertEquals(javaExecutable, command.get(0));
		assertTrue(command.contains("-Xmx2g"));
		assertTrue(command.contains("-DQORTIUM_agentlib=:test=foo"));
		assertFalse(command.contains("abort"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortium.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
		assertTrue(command.contains("--alpha"));
		assertTrue(command.contains("beta"));
	}

	@Test
	public void testBuildApplyUpdateCommandSkipsJvmArgsWhenDisabled() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g", "-agentlib:test=foo");

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				"java",
				false,
				runtimeInputArgs,
				null,
				Paths.get("/tmp/new-qortium.jar")
		);

		assertEquals("java", command.get(0));
		assertFalse(command.contains("-Xmx2g"));
		assertFalse(command.contains("-DQORTIUM_agentlib=:test=foo"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortium.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
	}

	@Test
	public void testBuildJavaCandidatesIncludesPrimaryAndFallbackJava() {
		List<String> candidates = AutoUpdate.buildJavaCandidates(Paths.get("/opt/jdk/bin/java"));

		assertEquals("/opt/jdk/bin/java", candidates.get(0));
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!osName.contains("win")) {
			assertTrue(candidates.contains("/usr/bin/java"));
		}
		assertTrue(candidates.contains("java"));
		assertEquals(candidates.size(), candidates.stream().distinct().count());
	}

	@Test
	public void testSanitizeJvmArgumentsDoesNotMutateInputList() {
		List<String> inputArgs = new ArrayList<>(Arrays.asList("-Xmx1g", "-agentlib:test=foo", "abort"));

		List<String> sanitized = AutoUpdate.sanitizeJvmArguments(inputArgs);

		assertEquals(Arrays.asList("-Xmx1g", "-agentlib:test=foo", "abort"), inputArgs);
		assertTrue(sanitized.contains("-DQORTIUM_agentlib=:test=foo"));
		assertFalse(sanitized.contains("abort"));
	}

	@Test
	public void testBuildApplyUpdateCommandHandlesNullSavedArgs() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g");

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				"java",
				true,
				runtimeInputArgs,
				null,
				Paths.get("/tmp/new-qortium.jar")
		);

		assertTrue(command.contains("-Xmx2g"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortium.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
	}

	@Test
	public void testDefaultRuntimeIdentitySettingsUseQortiumNames() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertEquals("QortiumKeyStore.jks", settings.getSslKeystorePathname());
		assertEquals("qortium-backup", settings.getExportPath());
		assertEquals("qortium.jar", AutoUpdate.JAR_FILENAME);
		assertEquals("new-qortium.jar", AutoUpdate.NEW_JAR_FILENAME);
		assertEquals("-DQORTIUM_agentlib=", AutoUpdate.AGENTLIB_JVM_HOLDER_ARG);
	}

	@Test
	public void testUpdateInstallGuardRejectsDuplicateAcquisition() {
		assertTrue(AutoUpdate.tryAcquireUpdateInstall());

		assertFalse(AutoUpdate.tryAcquireUpdateInstall());
		assertTrue(AutoUpdate.isUpdateInstallInProgress());
	}

	@Test
	public void testUpdateInstallGuardIsHeldAfterApplyProcessStarts() {
		assertTrue(AutoUpdate.tryAcquireUpdateInstall());

		AutoUpdate.finishUpdateInstallAttempt(true);

		assertTrue(AutoUpdate.isUpdateInstallInProgress());
	}

	@Test
	public void testUpdateInstallGuardIsReleasedWhenApplyProcessDoesNotStart() {
		assertTrue(AutoUpdate.tryAcquireUpdateInstall());

		AutoUpdate.finishUpdateInstallAttempt(false);

		assertFalse(AutoUpdate.isUpdateInstallInProgress());
	}
}
