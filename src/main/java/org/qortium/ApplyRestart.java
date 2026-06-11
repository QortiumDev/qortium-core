package org.qortium;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortium.api.ApiKey;
import org.qortium.api.ApiRequest;
import org.qortium.controller.RestartNode;
import org.qortium.settings.Settings;
import org.qortium.utils.RestartTrayAnimator;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.qortium.controller.RestartNode.AGENTLIB_JVM_HOLDER_ARG;

public class ApplyRestart {

	static {
		// This static block will be called before others if using ApplyRestart.main()

		// Log into different files for restart node - this has to be before LogManger.getLogger() calls
		System.setProperty("log4j2.filenameTemplate", "log-apply-restart.txt");

		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static final Logger LOGGER = LogManager.getLogger(ApplyRestart.class);
	private static final String JAR_FILENAME = RestartNode.JAR_FILENAME;
	private static final String WINDOWS_EXE_LAUNCHER = "qortium.exe";
	private static final String JAVA_TOOL_OPTIONS_NAME = "JAVA_TOOL_OPTIONS";
	private static final String JAVA_TOOL_OPTIONS_VALUE = "";

	private static final long CHECK_INTERVAL = 1_000L; // ms
	private static final int MAX_ATTEMPTS = 300;
	private static final long REPOSITORY_LOCK_WAIT_TIMEOUT = 2 * 60 * 1000L; // ms
	private static final long RESTART_API_WAIT_TIMEOUT = 5 * 60 * 1000L; // ms

	public static void main(String[] args) {
		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		// Load/check settings, which potentially sets up blockchain config, etc.
		if (args.length > 0)
			Settings.fileInstance(args[0]);
		else
			Settings.getInstance();

		LOGGER.info("Applying restart this can take up to 5 minutes...");

		try (RestartTrayAnimator trayAnimator = RestartTrayAnimator.start("Qortium Core is restarting...")) {
			// Shutdown node using API
			if (!shutdownNode())
				return;

			waitForRepositoryLockToClear();
			deleteLock();

			Process process = restartNode(args);
			if (process != null)
				trayAnimator.waitForNodeApi(Settings.getInstance().getApiPort(), RESTART_API_WAIT_TIMEOUT);

			LOGGER.info("Restarting...");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.error("Unable to restart", e);
		}
	}

	private static boolean shutdownNode() {
		String baseUri = "http://localhost:" + Settings.getInstance().getApiPort() + "/";
		LOGGER.debug(() -> String.format("Shutting down node using API via %s", baseUri));

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
					// API key was newly generated for restarting node, so we need to remove it
					ApplyRestart.removeGeneratedApiKey();
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
			// API key was newly generated for restarting node, so we need to remove it
			ApplyRestart.removeGeneratedApiKey();
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

			// Delete the API key since it was only generated for restarting node
			ApiKey apiKey = new ApiKey();
			apiKey.delete();

		} catch (IOException e) {
			LOGGER.error("Error loading or deleting API key: {}", e.getMessage());
		}
	}

	private static void waitForRepositoryLockToClear() throws InterruptedException {
		Path repositoryLock = Paths.get(Settings.getInstance().getRepositoryPath()).resolve("blockchain.lck");
		long deadline = System.currentTimeMillis() + REPOSITORY_LOCK_WAIT_TIMEOUT;

		while (Files.exists(repositoryLock) && System.currentTimeMillis() < deadline)
			Thread.sleep(CHECK_INTERVAL);
	}

	private static void deleteLock() {
		// Get the repository path from settings
		String repositoryPath = Settings.getInstance().getRepositoryPath();
		LOGGER.debug(String.format("Repository path is: %s", repositoryPath));

		try {
			Path root = Paths.get(repositoryPath);
			File lockFile = new File(root.resolve("blockchain.lck").toUri());
			LOGGER.debug("Lockfile is: {}", lockFile);
			FileUtils.forceDelete(FileUtils.getFile(lockFile));
		} catch (IOException e) {
			LOGGER.debug("Error deleting blockchain lock file: {}", e.getMessage());
		}
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
			javaCmd = List.of(exeLauncher.toString());
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

			// Call mainClass in JAR
			javaCmd.addAll(Arrays.asList("-jar", getCurrentJarPath()));

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

			// Nothing to pipe to new process, so close output stream (process's stdin)
			process.getOutputStream().close();
			return process;
		} catch (Exception e) {
			LOGGER.error(String.format("Failed to restart node (BAD): %s", e.getMessage()));
			return null;
		}
	}

	private static String getCurrentJarPath() {
		try {
			Path location = Paths.get(ApplyRestart.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return location.toAbsolutePath().normalize().toString();
		} catch (Exception e) {
			LOGGER.warn("Failed to resolve current jar path for restart; falling back to {}", JAR_FILENAME, e);
			return JAR_FILENAME;
		}
	}
}
