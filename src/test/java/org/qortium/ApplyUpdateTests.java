package org.qortium;

import org.junit.After;
import org.junit.Test;
import org.qortium.controller.AutoUpdate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApplyUpdateTests {

	@After
	public void afterTest() {
		System.clearProperty(AutoUpdate.PID_FILE_PROPERTY);
	}

	@Test
	public void testWritePidFileCreatesParentAndWritesPid() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-pid-test");
		Path pidFile = tempDir.resolve("nested").resolve("run.pid");

		ApplyUpdate.writePidFile(pidFile, 12345L);

		assertEquals("12345" + System.lineSeparator(), Files.readString(pidFile, StandardCharsets.UTF_8));
	}

	@Test
	public void testResolvePidFilePrefersProperty() throws Exception {
		System.setProperty(AutoUpdate.PID_FILE_PROPERTY, "/tmp/qortium-run.pid");
		Path tempDir = Files.createTempDirectory("qortium-pid-test");
		Files.writeString(tempDir.resolve("run.pid"), "old", StandardCharsets.UTF_8);

		assertEquals(Paths.get("/tmp/qortium-run.pid"), ApplyUpdate.resolvePidFileForRestart(tempDir));
	}

	@Test
	public void testResolvePidFileUsesExistingRunPidFallback() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-pid-test");
		Path pidFile = tempDir.resolve("run.pid");
		Files.writeString(pidFile, "old", StandardCharsets.UTF_8);

		assertEquals(pidFile, ApplyUpdate.resolvePidFileForRestart(tempDir));
	}

	@Test
	public void testResolvePidFileSkipsMissingFallback() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-pid-test");

		assertNull(ApplyUpdate.resolvePidFileForRestart(tempDir));
	}

	@Test
	public void testReplaceJarCopiesReplacementJar() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-apply-update-test");
		Path realJar = tempDir.resolve(AutoUpdate.JAR_FILENAME);
		Path newJar = tempDir.resolve(AutoUpdate.NEW_JAR_FILENAME);

		Files.writeString(realJar, "old jar", StandardCharsets.UTF_8);
		Files.writeString(newJar, "new jar", StandardCharsets.UTF_8);

		assertTrue(ApplyUpdate.replaceJar(tempDir));
		assertEquals("new jar", Files.readString(realJar, StandardCharsets.UTF_8));
	}

	@Test
	public void testReplaceJarFailsWhenReplacementJarIsMissing() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-apply-update-test");
		Path realJar = tempDir.resolve(AutoUpdate.JAR_FILENAME);

		Files.writeString(realJar, "old jar", StandardCharsets.UTF_8);

		assertFalse(ApplyUpdate.replaceJar(tempDir));
		assertEquals("old jar", Files.readString(realJar, StandardCharsets.UTF_8));
	}

	@Test
	public void testResolveInstalledJarPathUsesInstalledJarName() throws Exception {
		Path tempDir = Files.createTempDirectory("qortium-apply-update-test");

		assertEquals(tempDir.resolve(AutoUpdate.JAR_FILENAME).toAbsolutePath().normalize(), ApplyUpdate.resolveInstalledJarPath(tempDir));
	}
}
