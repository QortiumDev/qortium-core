package org.qortium;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortium.api.ApiKey;
import org.qortium.api.ApiRequest;
import org.qortium.controller.AutoUpdate;
import org.qortium.settings.Settings;
import org.qortium.utils.RestartTrayAnimator;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Security;
import java.util.*;
import java.util.stream.Collectors;

import static org.qortium.controller.AutoUpdate.AGENTLIB_JVM_HOLDER_ARG;

public class ApplyUpdate {

	static {
		// This static block will be called before others if using ApplyUpdate.main()

		// Log into different files for auto-update - this has to be before LogManger.getLogger() calls
		System.setProperty("log4j2.filenameTemplate", "log-apply-update.txt");

		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static final Logger LOGGER = LogManager.getLogger(ApplyUpdate.class);
	private static final String JAR_FILENAME = AutoUpdate.JAR_FILENAME;
	private static final String NEW_JAR_FILENAME = AutoUpdate.NEW_JAR_FILENAME;
	private static final String WINDOWS_EXE_LAUNCHER = "qortium.exe";
	private static final String RUN_PID_FILENAME = "run.pid";
	private static final String JAVA_TOOL_OPTIONS_NAME = "JAVA_TOOL_OPTIONS";
	private static final String JAVA_TOOL_OPTIONS_VALUE = "";

	private static final long CHECK_INTERVAL = 30 * 1000L; // ms
	private static final int MAX_ATTEMPTS = 12;
	private static final long RESTART_API_WAIT_TIMEOUT = 5 * 60 * 1000L; // ms

	public static void main(String[] args) {
		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		// Load/check settings, which potentially sets up blockchain config, etc.
		if (args.length > 0)
			Settings.fileInstance(args[0]);
		else
			Settings.getInstance();

		LOGGER.info("Applying update...");

		try (RestartTrayAnimator trayAnimator = RestartTrayAnimator.start("Qortium Core is applying an update...")) {
			// Shutdown node using API
			if (!shutdownNode())
				return;

			// Replace JAR
			if (!replaceJar()) {
				LOGGER.error("Update JAR replacement failed - not restarting node with existing JAR");
				return;
			}

			// Restart node
			Process process = restartNode(args);
			if (process != null)
				trayAnimator.waitForNodeApi(Settings.getInstance().getApiPort(), RESTART_API_WAIT_TIMEOUT);

			LOGGER.info("Exiting...");
		}
	}

	private static boolean shutdownNode() {
		String baseUri = "http://localhost:" + Settings.getInstance().getApiPort() + "/";
		LOGGER.info(() -> String.format("Shutting down node using API via %s", baseUri));

		// The /admin/stop endpoint requires an API key, which may or may not be already generated
		boolean apiKeyNewlyGenerated = false;
		ApiKey apiKey = null;
		try {
			apiKey = new ApiKey();
			if (!apiKey.generated()) {
				apiKey.generate();
				apiKeyNewlyGenerated = true;
				LOGGER.info("Generated API key");
			}
		} catch (IOException e) {
			LOGGER.info("Error loading API key: {}", e.getMessage());
		}

		// Create request headers
		Map<String, String> headers = new HashMap<>();
		if (apiKey != null) {
			headers.put(org.qortium.api.Security.API_KEY_HEADER, apiKey.toString());
		}

		// Attempt to stop the node
		int attempt;
		for (attempt = 0; attempt < MAX_ATTEMPTS; ++attempt) {
			final int attemptForLogging = attempt;
			LOGGER.info(() -> String.format("Attempt #%d out of %d to shutdown node", attemptForLogging + 1, MAX_ATTEMPTS));
			String response = ApiRequest.perform(baseUri + "admin/stop", Collections.emptyMap(), headers);
			if (response == null) {
				// No response - consider node shut down
				if (apiKeyNewlyGenerated) {
					// API key was newly generated for this auto update, so we need to remove it
					ApplyUpdate.removeGeneratedApiKey();
				}
				return true;
			}

			LOGGER.info(() -> String.format("Response from API: %s", response));

			try {
				Thread.sleep(CHECK_INTERVAL);
			} catch (InterruptedException e) {
				// We still need to check...
				break;
			}
		}

		if (apiKeyNewlyGenerated) {
			// API key was newly generated for this auto update, so we need to remove it
			ApplyUpdate.removeGeneratedApiKey();
		}

		if (attempt == MAX_ATTEMPTS) {
			LOGGER.error("Failed to shutdown node - giving up");
			return false;
		}

		return true;
	}

	private static void removeGeneratedApiKey() {
		try {
			LOGGER.info("Removing newly generated API key...");

			// Delete the API key since it was only generated for this auto update
			ApiKey apiKey = new ApiKey();
			apiKey.delete();

		} catch (IOException e) {
			LOGGER.error("Error loading or deleting API key: {}", e.getMessage());
		}
	}

	private static boolean replaceJar() {
		return replaceJar(resolveWorkingDirectoryForUpdate());
	}

	static boolean replaceJar(Path workingDirectory) {
		// Assuming current working directory contains the JAR files
		Path realJar = workingDirectory.resolve(JAR_FILENAME);
		Path newJar = workingDirectory.resolve(NEW_JAR_FILENAME);
		Path tempJar = workingDirectory.resolve(JAR_FILENAME + ".part");

		if (!Files.exists(newJar)) {
			LOGGER.warn(() -> String.format("Replacement JAR '%s' not found?", newJar));
			return false;
		}

		for (int attempt = 0; attempt < MAX_ATTEMPTS; ++attempt) {
			final int attemptForLogging = attempt;
			LOGGER.info(() -> String.format("Attempt #%d out of %d to replace JAR", attemptForLogging + 1, MAX_ATTEMPTS));

			try {
				Files.copy(newJar, tempJar, StandardCopyOption.REPLACE_EXISTING);

				try {
					Files.move(tempJar, realJar, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(tempJar, realJar, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					Files.copy(newJar, realJar, StandardCopyOption.REPLACE_EXISTING);
					Files.deleteIfExists(tempJar);
				}

				LOGGER.info(() -> String.format("Replaced JAR '%s' with '%s'", realJar, newJar));
				return true;
			} catch (IOException e) {
				LOGGER.info(() -> String.format("Unable to replace JAR: %s", e.getMessage()));
				try {
					Files.deleteIfExists(tempJar);
				} catch (IOException ignored) {
				}

				// Try again
			}

			try {
				Thread.sleep(CHECK_INTERVAL);
			} catch (InterruptedException e) {
				LOGGER.warn("Ignoring interrupt...");
				// Doggedly retry
			}
		}

		LOGGER.error("Failed to replace JAR - giving up");
		return false;
	}

	private static Process restartNode(String[] args) {
		String javaHome = System.getProperty("java.home");
		LOGGER.debug(() -> String.format("Java home: %s", javaHome));

		Path javaBinary = Paths.get(javaHome, "bin", "java");
		LOGGER.debug(() -> String.format("Java binary: %s", javaBinary));

		Path exeLauncher = Paths.get(WINDOWS_EXE_LAUNCHER);
		LOGGER.debug(() -> String.format("Windows EXE launcher: %s", exeLauncher));

		List<String> javaCmd;
		if (Files.exists(exeLauncher)) {
			javaCmd = Arrays.asList(exeLauncher.toString());
		} else {
			javaCmd = new ArrayList<>();
			// Java runtime binary itself
			javaCmd.add(javaBinary.toString());

			// JVM arguments
			javaCmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

			// Reapply any retained, but disabled, -agentlib JVM arg
			javaCmd = javaCmd.stream()
					.map(arg -> arg.replace(AGENTLIB_JVM_HOLDER_ARG, "-agentlib"))
					.collect(Collectors.toList());

			// Call mainClass in installed JAR
			javaCmd.addAll(Arrays.asList("-jar", resolveInstalledJarPath(resolveWorkingDirectoryForUpdate()).toString()));

			// Add saved command-line args
			javaCmd.addAll(Arrays.asList(args));
		}

		try {
			LOGGER.debug(String.format("Restarting node with: %s", String.join(" ", javaCmd)));

			ProcessBuilder processBuilder = new ProcessBuilder(javaCmd);

			if (Files.exists(exeLauncher)) {
				LOGGER.debug(() -> String.format("Setting env %s to %s", JAVA_TOOL_OPTIONS_NAME, JAVA_TOOL_OPTIONS_VALUE));
				processBuilder.environment().put(JAVA_TOOL_OPTIONS_NAME, JAVA_TOOL_OPTIONS_VALUE);
			}

			// New process will inherit our stdout and stderr
			processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
			processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

			Process process = processBuilder.start();

			writePidFileForRestart(process);

			// Nothing to pipe to new process, so close output stream (process's stdin)
			process.getOutputStream().close();
			return process;
		} catch (Exception e) {
			LOGGER.error(String.format("Failed to restart node (BAD): %s", e.getMessage()));
			return null;
		}
	}

	static Path resolveInstalledJarPath(Path workingDirectory) {
		return workingDirectory.resolve(JAR_FILENAME).toAbsolutePath().normalize();
	}

	private static String getCurrentJarPath() {
		try {
			Path location = Paths.get(ApplyUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return location.toAbsolutePath().normalize().toString();
		} catch (Exception e) {
			LOGGER.warn("Failed to resolve current jar path for update; falling back to {}", JAR_FILENAME, e);
			return JAR_FILENAME;
		}
	}

	private static void writePidFileForRestart(Process process) {
		Path pidFile = null;
		try {
			Path workingDirectory = resolveWorkingDirectoryForUpdate();
			pidFile = resolvePidFileForRestart(workingDirectory);
			if (pidFile == null)
				return;

			writePidFile(pidFile, process.pid());
		} catch (IOException | InvalidPathException e) {
			LOGGER.warn("Unable to update pid file {} after auto-update restart: {}", pidFile, e.getMessage());
		}
	}

	private static Path resolveWorkingDirectoryForUpdate() {
		try {
			Path currentJar = Paths.get(getCurrentJarPath());
			Path currentDirectory = currentJar.getParent();
			if (currentDirectory != null)
				return currentDirectory;
		} catch (Exception e) {
			LOGGER.warn("Failed to resolve update working directory from jar path: {}", e.getMessage());
		}

		Path fallbackDirectory = Paths.get("").toAbsolutePath().normalize();
		LOGGER.warn("Falling back to process working directory for update operations: {}", fallbackDirectory);
		return fallbackDirectory;
	}

	static Path resolvePidFileForRestart(Path workingDirectory) {
		String pidFile = System.getProperty(AutoUpdate.PID_FILE_PROPERTY);
		if (pidFile != null && !pidFile.isBlank())
			return Paths.get(pidFile);

		Path fallbackPidFile = workingDirectory.resolve(RUN_PID_FILENAME);
		if (Files.exists(fallbackPidFile))
			return fallbackPidFile;

		return null;
	}

	static void writePidFile(Path pidFile, long pid) throws IOException {
		Path parent = pidFile.getParent();
		if (parent != null)
			Files.createDirectories(parent);

		Files.writeString(pidFile, Long.toString(pid) + System.lineSeparator(), StandardCharsets.UTF_8);
	}

}
