package org.qortal.controller;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.ApplyUpdate;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.globalization.Translator;
import org.qortal.gui.SysTray;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.Base58;
import org.qortal.utils.Groups;

import java.awt.TrayIcon.MessageType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/* NOTE: It is CRITICAL that we use OpenJDK and not Java SE because our uber jar repacks BouncyCastle which, in turn, unsigns BC causing it to be rejected as a security provider by Java SE. */

public class AutoUpdate extends Thread {

	public static final String JAR_FILENAME = "qortium.jar";
	public static final String NEW_JAR_FILENAME = "new-" + JAR_FILENAME;
	public static final String AGENTLIB_JVM_HOLDER_ARG = "-DQORTIUM_agentlib=";

	private static final Logger LOGGER = LogManager.getLogger(AutoUpdate.class);
	private static final long CHECK_INTERVAL = 20 * 60 * 1000L; // ms

	private static final int UPDATE_SERVICE = 1;

	/** This byte value used to hide contents from deep-inspection firewalls in case they block updates. */
	public static final byte XOR_VALUE = (byte) 0x5a;

	private static AutoUpdate instance;

	private volatile boolean isStopping = false;

	private AutoUpdate() {
	}

	public static AutoUpdate getInstance() {
		if (instance == null)
			instance = new AutoUpdate();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Auto-update");

		if (!Settings.getInstance().isQdnEnabled()) {
			LOGGER.warn("Auto-update is enabled but QDN is disabled. Skipping auto-update service.");
			return;
		}

		long buildTimestamp = Controller.getInstance().getBuildTimestamp() * 1000L;
		boolean attemptedUpdate = false;

		while (!isStopping) {
			try {
				Thread.sleep(CHECK_INTERVAL);
			} catch (InterruptedException e) {
				return;
			}

			// Try to clean up any leftover downloads (but if have attempted update then don't delete new JAR)
			if (!attemptedUpdate)
				try {
					Path newJar = Paths.get(NEW_JAR_FILENAME);
					Files.deleteIfExists(newJar);
				} catch (IOException de) {
					// Whatever
				}

			// Look for "update" tx which is arbitrary tx in a configured dev group with service 1 and timestamp later than buildTimestamp
			try (final Repository repository = RepositoryManager.getRepository()) {
				int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
				List<Integer> devGroupIds = Groups.getGroupIdsAtHeight(BlockChain.getInstance().getDevGroupIds(), blockchainHeight);
				if (devGroupIds.isEmpty()) {
					LOGGER.warn(String.format("Auto-update is enabled but no devGroupIds are configured at height %d. Skipping update lookup.", blockchainHeight));
					continue;
				}

				byte[] signature = repository.getTransactionRepository().getLatestAutoUpdateTransaction(TransactionType.ARBITRARY, devGroupIds, UPDATE_SERVICE);
				if (signature == null)
					continue;

				TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
				if (!(transactionData instanceof ArbitraryTransactionData))
					continue;

				ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
				if (!arbitraryTransaction.isDataLocal())
					continue; // We can't access data

				byte[] data = arbitraryTransaction.fetchData();
				AutoUpdateManifest manifest;
				try {
					manifest = AutoUpdateManifest.parse(data);
				} catch (IllegalArgumentException e) {
					LOGGER.debug("Ignoring invalid auto-update manifest {}: {}", HashCode.fromBytes(signature), e.getMessage());
					continue;
				}

				if (!manifest.isQdnManifest()) {
					LOGGER.warn("Ignoring legacy HTTP auto-update manifest {} because this build only supports QDN auto-update transport",
							HashCode.fromBytes(signature));
					continue;
				}

				if (manifest.getTimestamp() <= buildTimestamp)
					continue; // update is the same, or older, than current code

				LOGGER.info("Update's git commit hash: {}", manifest.getCommitHashHex());

				if (attemptUpdate(manifest)) {
					// Consider ourselves updated so don't re-re-re-download
					buildTimestamp = System.currentTimeMillis();
					attemptedUpdate = true;
				}
			} catch (DataException e) {
				LOGGER.warn("Repository issue to find updates", e);
				// Keep going I guess...
			}
		}

		LOGGER.info("Stopping auto-update service");
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

	private static boolean attemptUpdate(AutoUpdateManifest manifest) {
		if (manifest.getBinarySignature() != null) {
			LOGGER.info("Fetching update from pinned QDN {} transaction {} path {}",
					manifest.getQdnService(), manifest.getBinarySignature58(), manifest.getQdnPath());
		} else {
			LOGGER.info("Fetching update from QDN {}/{}/{} path {}",
					manifest.getQdnService(), manifest.getQdnName(), manifest.getQdnIdentifier(), manifest.getQdnPath());
		}

		Path newJar = Paths.get(NEW_JAR_FILENAME);

		try {
			Path updatePath = resolveQdnUpdatePath(manifest);
			try (InputStream in = Files.newInputStream(updatePath)) {
				if (!writeVerifiedUpdate(in, manifest.getUpdateHash(), newJar, updatePath.toString()))
					return false;
			}
		} catch (DataException | IOException | MissingDataException e) {
			LOGGER.warn("Failed to fetch update from QDN: {}", e.getMessage());
			return false;
		}

		return applyUpdate(newJar);
	}

	static Path resolveQdnUpdatePath(AutoUpdateManifest manifest) throws DataException, IOException, MissingDataException {
		byte[] expectedSignature = manifest.getBinarySignature();
		if (expectedSignature != null)
			return resolvePinnedQdnUpdatePath(manifest, expectedSignature);

		ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(
				manifest.getQdnName(), ResourceIdType.NAME, manifest.getQdnService(), manifest.getQdnIdentifier());
		return loadQdnUpdatePath(arbitraryDataReader, manifest);
	}

	private static Path resolvePinnedQdnUpdatePath(AutoUpdateManifest manifest, byte[] binarySignature)
			throws DataException, IOException, MissingDataException {
		ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(
				Base58.encode(binarySignature), ResourceIdType.TRANSACTION_DATA, manifest.getQdnService(), null);
		arbitraryDataReader.setTransactionData(getPinnedQdnBinaryTransaction(manifest, binarySignature));
		return loadQdnUpdatePath(arbitraryDataReader, manifest);
	}

	private static ArbitraryTransactionData getPinnedQdnBinaryTransaction(AutoUpdateManifest manifest, byte[] binarySignature) throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(binarySignature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				throw new DataException("Pinned QDN update transaction signature does not identify an ARBITRARY transaction");

			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;
			if (arbitraryTransactionData.getService() != manifest.getQdnService())
				throw new DataException("Pinned QDN update transaction service does not match manifest");

			return arbitraryTransactionData;
		}
	}

	private static Path loadQdnUpdatePath(ArbitraryDataReader arbitraryDataReader, AutoUpdateManifest manifest)
			throws DataException, IOException, MissingDataException {
		arbitraryDataReader.loadSynchronously(false);

		Path outputPath = arbitraryDataReader.getFilePath();
		if (outputPath == null)
			throw new DataException("QDN update resource did not produce an output path");

		Path updatePath = outputPath.resolve(manifest.getQdnPath()).normalize();
		if (!updatePath.startsWith(outputPath.normalize()))
			throw new DataException("QDN update path escapes resource directory");

		if (!Files.isRegularFile(updatePath))
			updatePath = findSingleFileFallback(outputPath, updatePath);

		return updatePath;
	}

	private static Path findSingleFileFallback(Path outputPath, Path requestedPath) throws IOException {
		try (java.util.stream.Stream<Path> stream = Files.list(outputPath)) {
			List<Path> files = stream
					.filter(Files::isRegularFile)
					.filter(path -> !".qdn".equals(path.getFileName().toString()))
					.collect(Collectors.toList());

			if (files.size() == 1)
				return files.get(0);
		}

		throw new IOException(String.format("QDN update file not found: %s", requestedPath));
	}

	static boolean writeVerifiedUpdate(InputStream in, byte[] downloadHash, Path newJar, String sourceDescription) {
		MessageDigest sha256;
		try {
			sha256 = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			LOGGER.error("SHA-256 digest is unavailable", e);
			return false;
		}

		LOGGER.debug("Saving update from {} into {}", sourceDescription, newJar);

		try (OutputStream out = Files.newOutputStream(newJar)) {
			byte[] buffer = new byte[1024 * 1024];
			do {
				int nread = in.read(buffer);
				if (nread == -1)
					break;

				// Hash is based on XORed version
				sha256.update(buffer, 0, nread);

				// ReXOR before writing
				for (int i = 0; i < nread; ++i)
					buffer[i] ^= XOR_VALUE;

				out.write(buffer, 0, nread);
			} while (true);
			out.flush();

			byte[] hash = sha256.digest();
			if (!Arrays.equals(downloadHash, hash)) {
				LOGGER.warn("Downloaded update hash {} doesn't match {}", HashCode.fromBytes(hash), HashCode.fromBytes(downloadHash));
				deleteUpdateFile(newJar, "download");
				return false;
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to save update from {} into {}: {}", sourceDescription, newJar, e.getMessage());
			deleteUpdateFile(newJar, "partial download");
			return false;
		}

		return true;
	}

	private static boolean applyUpdate(Path newJar) {
		// Give repository a chance to backup in case things go badly wrong (if enabled)
		if (Settings.getInstance().getRepositoryBackupInterval() > 0) {
			try {
				// Timeout if the database isn't ready for backing up after 60 seconds
				long timeout = 60 * 1000L;
				RepositoryManager.backup(true, "backup", timeout);

			} catch (TimeoutException e) {
				LOGGER.info("Attempt to backup repository failed due to timeout: {}", e.getMessage());
				// Continue with the auto update anyway...
			}
		}

		// Call ApplyUpdate to end this process (unlocking current JAR so it can be replaced)
		String javaHome = System.getProperty("java.home");
		Path javaBinary = Paths.get(javaHome, "bin", "java");
		Path javaSpawnHelper = Paths.get(javaHome, "lib", "jspawnhelper");
		Path newJarAbsolute = newJar.toAbsolutePath();
		String[] savedArgs = Controller.getInstance().getSavedArgs();

		SysTray.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "AUTO_UPDATE"),
				Translator.INSTANCE.translate("SysTray", "APPLYING_UPDATE_AND_RESTARTING"),
				MessageType.INFO);

		List<String> javaCandidates = buildJavaCandidates(javaBinary);
		List<String> runtimeInputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
		Exception lastException = null;
		int attempt = 0;

		// Try full JVM args first, then retry with a minimal command line.
		for (boolean includeJvmArgs : new boolean[]{true, false}) {
				for (String javaCandidate : javaCandidates) {
					attempt++;
					List<String> javaCmd = buildApplyUpdateCommand(javaCandidate, includeJvmArgs, runtimeInputArgs, savedArgs, newJarAbsolute);
					LOGGER.info("Applying update attempt {} (jvmArgs={}, java={}): {}", attempt, includeJvmArgs, javaCandidate, String.join(" ", javaCmd));

				try {
					startApplyUpdateProcess(javaCmd);
					return true; // applying update OK
				} catch (Exception e) {
					lastException = e;
					LOGGER.error("Failed to apply update attempt {} (jvmArgs={}, java={}): {}", attempt, includeJvmArgs, javaCandidate, e.getMessage(), e);
				}
			}
		}

		logLaunchDiagnostics(javaHome, javaBinary, javaSpawnHelper, newJarAbsolute, javaCandidates, attempt, lastException);

		try {
			Files.deleteIfExists(newJar);
		} catch (IOException de) {
			LOGGER.warn(String.format("Failed to delete update download: %s", de.getMessage()));
		}

		return false; // failed - allow retry
	}

	private static void deleteUpdateFile(Path newJar, String description) {
		try {
			Files.deleteIfExists(newJar);
		} catch (IOException de) {
			LOGGER.warn("Failed to delete {}: {}", description, de.getMessage());
		}
	}

	static List<String> buildJavaCandidates(Path javaBinary) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		candidates.add(javaBinary.toString());

		try {
			candidates.add(javaBinary.toRealPath().toString());
		} catch (IOException e) {
			// ignore
		}

		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!osName.contains("win")) {
			candidates.add("/usr/bin/java");
		}

		candidates.add("java");
		return new ArrayList<>(candidates);
	}

	static List<String> sanitizeJvmArguments(List<String> inputArgs) {
		List<String> javaCmd = new ArrayList<>(inputArgs);

		// Disable, but retain, any -agentlib JVM arg as sub-process might fail if it tries to reuse same port
		javaCmd = javaCmd.stream()
				.map(arg -> arg.replace("-agentlib", AGENTLIB_JVM_HOLDER_ARG))
				.collect(Collectors.toList());

		// Remove JNI options as they won't be supported by command-line 'java'
		// These are typically added by the AdvancedInstaller Java launcher EXE
		javaCmd.removeAll(Arrays.asList("abort", "exit", "vfprintf"));

		return javaCmd;
	}

	static List<String> buildApplyUpdateCommand(String javaExecutable, boolean includeJvmArgs,
											 List<String> runtimeInputArgs, String[] savedArgs, Path newJarAbsolute) {
		List<String> javaCmd = new ArrayList<>();
		javaCmd.add(javaExecutable);

		if (includeJvmArgs) {
			javaCmd.addAll(sanitizeJvmArguments(runtimeInputArgs));
		}

		// Call ApplyUpdate using new JAR
		javaCmd.addAll(Arrays.asList("-cp", newJarAbsolute.toString(), ApplyUpdate.class.getCanonicalName()));

		// Add command-line args saved from start-up
		if (savedArgs != null)
			javaCmd.addAll(Arrays.asList(savedArgs));

		return javaCmd;
	}

	private static void startApplyUpdateProcess(List<String> javaCmd) throws IOException {
		ProcessBuilder processBuilder = new ProcessBuilder(javaCmd);

		// New process will inherit our stdout and stderr
		processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

		Process process = processBuilder.start();

		// Nothing to pipe to new process, so close output stream (process's stdin)
		process.getOutputStream().close();
	}

	private static void logLaunchDiagnostics(String javaHome, Path javaBinary, Path javaSpawnHelper, Path newJarAbsolute,
											 List<String> javaCandidates, int attempts, Exception lastException) {
		LOGGER.error("Failed to apply update after {} attempts. java.home={}, cwd={}, newJar={}, candidates={}",
				attempts, javaHome, Paths.get(".").toAbsolutePath().normalize(), newJarAbsolute, javaCandidates);

		logPathDiagnostics("javaBinary", javaBinary);
		logPathDiagnostics("jspawnhelper", javaSpawnHelper);
		logPathDiagnostics("newJar", newJarAbsolute);

		if (lastException != null) {
			LOGGER.error("Last apply update failure summary: {}", lastException.getMessage(), lastException);
		}
	}

	private static void logPathDiagnostics(String label, Path path) {
		try {
			LOGGER.error("{} -> path={}, exists={}, readable={}, executable={}, size={}",
					label,
					path,
					Files.exists(path),
					Files.isReadable(path),
					Files.isExecutable(path),
					Files.exists(path) ? Files.size(path) : -1L);
		} catch (IOException e) {
			LOGGER.error("{} diagnostics failed for {}: {}", label, path, e.getMessage(), e);
		}
	}
}
