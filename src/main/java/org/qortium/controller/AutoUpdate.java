package org.qortium.controller;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.ApplyUpdate;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.block.BlockChain;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.crypto.Crypto;
import org.qortium.globalization.Translator;
import org.qortium.gui.NodeTrayFactory;
import org.qortium.gui.TrayMessageType;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.settings.Settings.AutoUpdateMode;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Base58;
import org.qortium.utils.Groups;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/* NOTE: It is CRITICAL that we use OpenJDK and not Java SE because our uber jar repacks BouncyCastle which, in turn, unsigns BC causing it to be rejected as a security provider by Java SE. */

public class AutoUpdate extends Thread {

	public static final String JAR_FILENAME = "qortium.jar";
	public static final String NEW_JAR_FILENAME = "new-" + JAR_FILENAME;
	public static final String AGENTLIB_JVM_HOLDER_ARG = "-DQORTIUM_agentlib=";

	private static final Logger LOGGER = LogManager.getLogger(AutoUpdate.class);
	private static final long CHECK_INTERVAL = 20 * 60 * 1000L; // ms

	private static final int UPDATE_SERVICE = 1;
	private static final long MANUAL_UPDATE_DELAY = 1000L;

	public static final String STATUS_QDN_DISABLED = "QDN_DISABLED";
	public static final String STATUS_NO_DEV_GROUPS = "NO_DEV_GROUPS";
	public static final String STATUS_NO_APPROVED_UPDATE = "NO_APPROVED_UPDATE";
	public static final String STATUS_UNSUPPORTED_TRANSACTION = "UNSUPPORTED_TRANSACTION";
	public static final String STATUS_MANIFEST_NOT_LOCAL = "MANIFEST_NOT_LOCAL";
	public static final String STATUS_INVALID_MANIFEST = "INVALID_MANIFEST";
	public static final String STATUS_UNPINNED_MANIFEST = "UNPINNED_MANIFEST";
	public static final String STATUS_INVALID_BINARY_TRANSACTION = "INVALID_BINARY_TRANSACTION";
	public static final String STATUS_NOT_NEWER = "NOT_NEWER";
	public static final String STATUS_UPDATE_AVAILABLE = "UPDATE_AVAILABLE";
	public static final String STATUS_INSTALL_IN_PROGRESS = "INSTALL_IN_PROGRESS";
	public static final String STATUS_INSTALL_STARTED = "INSTALL_STARTED";

	/** This byte value used to hide contents from deep-inspection firewalls in case they block updates. */
	public static final byte XOR_VALUE = (byte) 0x5a;

	private static AutoUpdate instance;
	private static final AtomicBoolean updateInstallInProgress = new AtomicBoolean(false);

	private volatile boolean isStopping = false;
	private String lastNotifiedManifestSignature = null;

	private AutoUpdate() {
	}

	public static AutoUpdate getInstance() {
		if (instance == null)
			instance = new AutoUpdate();

		return instance;
	}

	public static AutoUpdate getLoadedInstance() {
		return instance;
	}

	public static boolean shouldStartBackgroundService() {
		return Settings.getInstance().getAutoUpdateMode() != AutoUpdateMode.OFF;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Auto-update");

		if (!Settings.getInstance().isQdnEnabled()) {
			LOGGER.warn("Auto-update mode is {} but QDN is disabled. Skipping auto-update service.",
					Settings.getInstance().getAutoUpdateMode());
			return;
		}

		long buildTimestamp = getCurrentBuildTimestamp();
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

			try {
				UpdateLookup lookup = lookupLatestUpdate(buildTimestamp);
				logSkippedLookup(lookup.status);
				if (!lookup.status.updateAvailable)
					continue;

				LOGGER.info("Update's git commit hash: {}", lookup.status.commitHash);

				AutoUpdateMode mode = Settings.getInstance().getAutoUpdateMode();
				if (mode == AutoUpdateMode.OFF) {
					LOGGER.info("Auto-update mode changed to OFF. Stopping auto-update service.");
					return;
				}

				if (mode == AutoUpdateMode.CHECK_ONLY) {
					LOGGER.info("Approved update {} is available but auto-update mode is CHECK_ONLY", lookup.status.manifestSignature);
				} else if (mode == AutoUpdateMode.NOTIFY) {
					notifyUpdateAvailable(lookup.status);
				} else if (mode == AutoUpdateMode.INSTALL) {
					if (attemptUpdate(lookup.manifest)) {
						// Consider ourselves updated so don't re-re-re-download
						buildTimestamp = System.currentTimeMillis();
						attemptedUpdate = true;
					}
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

	private void notifyUpdateAvailable(UpdateCheckResult status) {
		if (status.manifestSignature == null)
			return;

		if (status.manifestSignature.equals(this.lastNotifiedManifestSignature))
			return;

		this.lastNotifiedManifestSignature = status.manifestSignature;
		NodeTrayFactory.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "AUTO_UPDATE"),
				String.format("Approved update available: %s", status.commitHash),
				TrayMessageType.INFO);
	}

	public static UpdateCheckResult checkLatestUpdate() throws DataException {
		return lookupLatestUpdate(getCurrentBuildTimestamp()).status;
	}

	public static UpdateCheckResult requestManualUpdate() throws DataException {
		UpdateLookup lookup = lookupLatestUpdate(getCurrentBuildTimestamp());
		if (!lookup.status.updateAvailable)
			return lookup.status;

		if (!tryAcquireUpdateInstall()) {
			lookup.status.installing = true;
			lookup.status.status = STATUS_INSTALL_IN_PROGRESS;
			lookup.status.message = "An update install is already in progress";
			return lookup.status;
		}

		lookup.status.installing = true;
		lookup.status.installStarted = true;
		lookup.status.status = STATUS_INSTALL_STARTED;
		lookup.status.message = "Update install has been scheduled";

		AutoUpdateManifest manifest = lookup.manifest;
		new Thread(() -> {
			try {
				Thread.sleep(MANUAL_UPDATE_DELAY);
				if (!attemptUpdateAlreadyMarkedInProgress(manifest))
					LOGGER.warn("Manual auto-update attempt failed");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.warn("Manual auto-update attempt was interrupted before it started");
				releaseUpdateInstall();
			}
		}, "Manual auto-update").start();

		return lookup.status;
	}

	private static long getCurrentBuildTimestamp() {
		return Controller.getInstance().getBuildTimestamp() * 1000L;
	}

	private static UpdateLookup lookupLatestUpdate(long buildTimestamp) throws DataException {
		UpdateCheckResult status = new UpdateCheckResult();
		AutoUpdateMode autoUpdateMode = Settings.getInstance().getAutoUpdateMode();
		status.currentBuildTimestamp = buildTimestamp;
		status.autoUpdateMode = autoUpdateMode.name();
		status.qdnEnabled = Settings.getInstance().isQdnEnabled();
		status.installing = isUpdateInstallInProgress();

		if (!status.qdnEnabled)
			return UpdateLookup.withoutManifest(status, STATUS_QDN_DISABLED,
					"QDN is disabled, so QDN auto-updates cannot be checked");

		try (final Repository repository = RepositoryManager.getRepository()) {
			int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
			status.blockchainHeight = blockchainHeight;
			List<Integer> devGroupIds = Groups.getGroupIdsAtHeight(BlockChain.getInstance().getDevGroupIds(), blockchainHeight);
			status.devGroupIds = devGroupIds;
			populateDevGroupSummaries(status, repository, devGroupIds);
			if (devGroupIds.isEmpty())
				return UpdateLookup.withoutManifest(status, STATUS_NO_DEV_GROUPS,
						String.format("No development group IDs are configured at height %d", blockchainHeight));

			byte[] signature = repository.getTransactionRepository().getLatestAutoUpdateTransaction(TransactionType.ARBITRARY, devGroupIds, UPDATE_SERVICE);
			if (signature == null)
				return UpdateLookup.withoutManifest(status, STATUS_NO_APPROVED_UPDATE,
						"No approved auto-update manifest was found");

			status.manifestSignature = Base58.encode(signature);
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			populateManifestTransactionStatus(status, transactionData);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return UpdateLookup.withoutManifest(status, STATUS_UNSUPPORTED_TRANSACTION,
						"Latest auto-update transaction is not an ARBITRARY transaction");

			ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
			if (!arbitraryTransaction.isDataLocal())
				return UpdateLookup.withoutManifest(status, STATUS_MANIFEST_NOT_LOCAL,
						"Latest auto-update manifest data is not available locally");

			byte[] data = arbitraryTransaction.fetchData();
			AutoUpdateManifest manifest;
			try {
				manifest = AutoUpdateManifest.parse(data);
			} catch (IllegalArgumentException e) {
				return UpdateLookup.withoutManifest(status, STATUS_INVALID_MANIFEST,
						String.format("Invalid auto-update manifest: %s", e.getMessage()));
			}

			populateManifestStatus(status, manifest);

			if (manifest.getBinarySignature() == null)
				return UpdateLookup.withoutManifest(status, STATUS_UNPINNED_MANIFEST,
						"Approved auto-update manifest does not pin a QDN binary transaction");

			try {
				populateBinaryTransactionStatus(status, getPinnedQdnBinaryTransaction(repository, manifest, manifest.getBinarySignature()));
			} catch (DataException e) {
				return UpdateLookup.withoutManifest(status, STATUS_INVALID_BINARY_TRANSACTION, e.getMessage());
			}

			if (manifest.getTimestamp() <= buildTimestamp)
				return UpdateLookup.withoutManifest(status, STATUS_NOT_NEWER,
						"Latest approved auto-update manifest is not newer than this build");

			status.updateAvailable = true;
			status.status = STATUS_UPDATE_AVAILABLE;
			status.message = "A newer approved auto-update is available";
			return new UpdateLookup(status, manifest);
		}
	}

	private static void populateManifestStatus(UpdateCheckResult status, AutoUpdateManifest manifest) {
		status.updateTimestamp = manifest.getTimestamp();
		status.commitHash = manifest.getCommitHashHex();
		status.binarySignature = manifest.getBinarySignature58();
		status.qdnService = manifest.getQdnService().name();
		status.qdnName = manifest.getQdnName();
		status.qdnIdentifier = manifest.getQdnIdentifier();
		status.qdnPath = manifest.getQdnPath();
	}

	private static void populateManifestTransactionStatus(UpdateCheckResult status, TransactionData transactionData) {
		if (transactionData == null)
			return;

		status.manifestCreatorAddress = addressFromPublicKey(transactionData.getCreatorPublicKey());
		status.manifestTxGroupId = transactionData.getTxGroupId();
		status.manifestApprovalStatus = transactionData.getApprovalStatus() == null ? null : transactionData.getApprovalStatus().name();
		status.manifestBlockHeight = transactionData.getBlockHeight();
		status.manifestApprovalHeight = transactionData.getApprovalHeight();
	}

	private static void populateBinaryTransactionStatus(UpdateCheckResult status, ArbitraryTransactionData transactionData) {
		if (transactionData == null)
			return;

		status.binaryCreatorAddress = addressFromPublicKey(transactionData.getCreatorPublicKey());
		status.binaryService = transactionData.getService().name();
		status.binaryName = transactionData.getName();
		status.binaryIdentifier = transactionData.getIdentifier();
		status.binaryMethod = transactionData.getMethod() == null ? null : transactionData.getMethod().name();
		status.binaryBlockHeight = transactionData.getBlockHeight();
	}

	private static void populateDevGroupSummaries(UpdateCheckResult status, Repository repository, List<Integer> devGroupIds) throws DataException {
		List<DevGroupSummary> devGroups = new ArrayList<>();
		for (Integer groupId : devGroupIds) {
			if (groupId == null)
				continue;

			GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
			if (groupData == null)
				continue;

			DevGroupSummary summary = new DevGroupSummary();
			summary.groupId = groupId;
			summary.groupName = groupData.getGroupName();
			summary.owner = groupData.getOwner();
			summary.approvalThreshold = groupData.getApprovalThreshold() == null ? null : groupData.getApprovalThreshold().name();
			summary.adminCount = repository.getGroupRepository().countGroupAdmins(groupId);
			summary.usableAdminCount = repository.getGroupRepository().countUsableGroupAdmins(groupId);
			summary.memberCount = repository.getGroupRepository().countGroupMembers(groupId);
			devGroups.add(summary);
		}

		status.devGroups = devGroups;
	}

	private static String addressFromPublicKey(byte[] publicKey) {
		return publicKey == null ? null : Crypto.toAddress(publicKey);
	}

	private static void logSkippedLookup(UpdateCheckResult status) {
		if (STATUS_NO_DEV_GROUPS.equals(status.status)) {
			LOGGER.warn("Auto-update is enabled but {}. Skipping update lookup.", status.message);
		} else if (STATUS_INVALID_MANIFEST.equals(status.status)) {
			LOGGER.debug("Ignoring invalid auto-update manifest {}: {}", status.manifestSignature, status.message);
		} else if (STATUS_UNPINNED_MANIFEST.equals(status.status) || STATUS_INVALID_BINARY_TRANSACTION.equals(status.status)) {
			LOGGER.warn("Ignoring approved auto-update manifest {}: {}", status.manifestSignature, status.message);
		}
	}

	private static boolean attemptUpdate(AutoUpdateManifest manifest) {
		if (!tryAcquireUpdateInstall()) {
			LOGGER.warn("Skipping auto-update because another update install is already in progress");
			return false;
		}

		return attemptUpdateAlreadyMarkedInProgress(manifest);
	}

	private static boolean attemptUpdateAlreadyMarkedInProgress(AutoUpdateManifest manifest) {
		boolean applyProcessStarted = false;
		try {
			applyProcessStarted = attemptUpdateInternal(manifest);
			return applyProcessStarted;
		} finally {
			finishUpdateInstallAttempt(applyProcessStarted);
		}
	}

	static boolean tryAcquireUpdateInstall() {
		return updateInstallInProgress.compareAndSet(false, true);
	}

	static void releaseUpdateInstall() {
		updateInstallInProgress.set(false);
	}

	static boolean isUpdateInstallInProgress() {
		return updateInstallInProgress.get();
	}

	static void finishUpdateInstallAttempt(boolean applyProcessStarted) {
		if (!applyProcessStarted) {
			releaseUpdateInstall();
		}
	}

	private static boolean attemptUpdateInternal(AutoUpdateManifest manifest) {
		LOGGER.info("Fetching update from pinned QDN {} transaction {} path {}",
				manifest.getQdnService(), manifest.getBinarySignature58(), manifest.getQdnPath());

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

	private static class UpdateLookup {
		private final UpdateCheckResult status;
		private final AutoUpdateManifest manifest;

		private UpdateLookup(UpdateCheckResult status, AutoUpdateManifest manifest) {
			this.status = status;
			this.manifest = manifest;
		}

		private static UpdateLookup withoutManifest(UpdateCheckResult status, String statusCode, String message) {
			status.status = statusCode;
			status.message = message;
			status.updateAvailable = false;
			return new UpdateLookup(status, null);
		}
	}

	public static class UpdateCheckResult {
		public String autoUpdateMode;
		public boolean qdnEnabled;
		public boolean updateAvailable;
		public boolean installing;
		public boolean installStarted;
		public String status;
		public String message;
		public long currentBuildTimestamp;
		public Integer blockchainHeight;
		public List<Integer> devGroupIds;
		public List<DevGroupSummary> devGroups;
		public Long updateTimestamp;
		public String commitHash;
		public String manifestSignature;
		public String manifestCreatorAddress;
		public Integer manifestTxGroupId;
		public String manifestApprovalStatus;
		public Integer manifestBlockHeight;
		public Integer manifestApprovalHeight;
		public String binarySignature;
		public String binaryCreatorAddress;
		public String binaryService;
		public String binaryName;
		public String binaryIdentifier;
		public String binaryMethod;
		public Integer binaryBlockHeight;
		public String qdnService;
		public String qdnName;
		public String qdnIdentifier;
		public String qdnPath;
	}

	public static class DevGroupSummary {
		public Integer groupId;
		public String groupName;
		public String owner;
		public String approvalThreshold;
		public Integer adminCount;
		public Integer usableAdminCount;
		public Integer memberCount;
	}

	static Path resolveQdnUpdatePath(AutoUpdateManifest manifest) throws DataException, IOException, MissingDataException {
		byte[] expectedSignature = manifest.getBinarySignature();
		if (expectedSignature == null)
			throw new DataException("Auto-update manifest does not pin a QDN binary transaction");

		return resolvePinnedQdnUpdatePath(manifest, expectedSignature);
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
			return getPinnedQdnBinaryTransaction(repository, manifest, binarySignature);
		}
	}

	private static ArbitraryTransactionData getPinnedQdnBinaryTransaction(Repository repository, AutoUpdateManifest manifest, byte[] binarySignature) throws DataException {
		if (binarySignature == null)
			throw new DataException("Pinned QDN update transaction signature is missing");

		TransactionData transactionData = repository.getTransactionRepository().fromSignature(binarySignature);
		if (!(transactionData instanceof ArbitraryTransactionData))
			throw new DataException("Pinned QDN update transaction signature does not identify an ARBITRARY transaction");

		ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;
		if (arbitraryTransactionData.getService() != manifest.getQdnService())
			throw new DataException("Pinned QDN update transaction service does not match manifest");

		return arbitraryTransactionData;
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

	static boolean writeVerifiedUpdate(InputStream in, byte[] expectedJarHash, Path newJar, String sourceDescription) {
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

				// Decode the QDN transport bytes before hashing and writing the executable JAR.
				for (int i = 0; i < nread; ++i)
					buffer[i] ^= XOR_VALUE;

				sha256.update(buffer, 0, nread);
				out.write(buffer, 0, nread);
			} while (true);
			out.flush();

			byte[] hash = sha256.digest();
			if (!Arrays.equals(expectedJarHash, hash)) {
				LOGGER.warn("Decoded update JAR hash {} doesn't match {}", HashCode.fromBytes(hash), HashCode.fromBytes(expectedJarHash));
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

		NodeTrayFactory.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "AUTO_UPDATE"),
				Translator.INSTANCE.translate("SysTray", "APPLYING_UPDATE_AND_RESTARTING"),
				TrayMessageType.INFO);

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
