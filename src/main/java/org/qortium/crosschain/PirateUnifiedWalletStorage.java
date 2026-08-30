package org.qortium.crosschain;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Crash-safe Qortium metadata for one persistent Pirate Unified wallet namespace. */
final class PirateUnifiedWalletStorage {

	static final String NATIVE_REGISTRY_FILENAME = "wallet_registry.db";
	static final String STATE_FILENAME = "qortium-wallet-state.json";
	private static final int STATE_VERSION = 1;
	private static final AtomicBoolean STALE_TRANSIENT_STORAGE_CLEANED = new AtomicBoolean(false);

	enum State {
		LEGACY,
		MIGRATING,
		UNIFIED_READY,
		FAILED_RECOVERABLE
	}

	static final class Snapshot {
		private final State state;
		private final boolean syncValidated;
		private final String identityHash;
		private final String selectedServerUri;
		private final Long recoveryRescanFromHeight;
		private final PirateWallet.InitializationMode initializationMode;
		private final Integer initializationBirthdayHeight;
		private final boolean corrupt;

		private Snapshot(State state, boolean syncValidated, String identityHash, String selectedServerUri,
				Long recoveryRescanFromHeight, PirateWallet.InitializationMode initializationMode,
				Integer initializationBirthdayHeight, boolean corrupt) {
			this.state = state;
			this.syncValidated = syncValidated;
			this.identityHash = identityHash;
			this.selectedServerUri = selectedServerUri;
			this.recoveryRescanFromHeight = recoveryRescanFromHeight;
			this.initializationMode = initializationMode;
			this.initializationBirthdayHeight = initializationBirthdayHeight;
			this.corrupt = corrupt;
		}

		State getState() {
			return this.state;
		}

		boolean isSyncValidated() {
			return this.syncValidated;
		}

		String getIdentityHash() {
			return this.identityHash;
		}

		String getSelectedServerUri() {
			return this.selectedServerUri;
		}

		/**
		 * Durable driver intent: a verified-import rescan is still owed from this height.
		 * Null when no recovery replay is pending. The native wallet's own gate is
		 * authoritative; this record only tells a restarted controller to drive the
		 * native rescan again, because upstream never resumes it on its own.
		 */
		Long getRecoveryRescanFromHeight() {
			return this.recoveryRescanFromHeight;
		}

		PirateWallet.InitializationMode getInitializationMode() {
			return this.initializationMode;
		}

		Integer getInitializationBirthdayHeight() {
			return this.initializationBirthdayHeight;
		}

		boolean isCorrupt() {
			return this.corrupt;
		}
	}

	private final Path storageDirectory;
	private final Path legacyWalletPath;
	private final boolean transientWallet;

	static PirateUnifiedWalletStorage persistent(ZcashFamilyWalletConfig config, String entropyHash58,
			Path legacyWalletPath) {
		if (entropyHash58 == null || entropyHash58.isBlank())
			throw new IllegalArgumentException("Missing entropy namespace");

		return new PirateUnifiedWalletStorage(config.getUnifiedWalletsDirectory().resolve(entropyHash58),
				legacyWalletPath, false);
	}

	static PirateUnifiedWalletStorage transientWallet(ZcashFamilyWalletConfig config, Path legacyWalletPath)
			throws IOException {
		Path transientRoot = config.getUnifiedWalletsDirectory().resolve("transient");
		Files.createDirectories(transientRoot);
		if (STALE_TRANSIENT_STORAGE_CLEANED.compareAndSet(false, true))
			cleanupChildren(transientRoot);
		Path storageDirectory = Files.createTempDirectory(transientRoot, "session-");
		return new PirateUnifiedWalletStorage(storageDirectory, legacyWalletPath, true);
	}

	private PirateUnifiedWalletStorage(Path storageDirectory, Path legacyWalletPath, boolean transientWallet) {
		this.storageDirectory = storageDirectory;
		this.legacyWalletPath = legacyWalletPath;
		this.transientWallet = transientWallet;
	}

	Path getStorageDirectory() {
		return this.storageDirectory;
	}

	Path getLegacyWalletPath() {
		return this.legacyWalletPath;
	}

	boolean isTransientWallet() {
		return this.transientWallet;
	}

	boolean hasNativeRegistry() {
		return Files.isRegularFile(this.storageDirectory.resolve(NATIVE_REGISTRY_FILENAME));
	}

	boolean hasStateFile() {
		return Files.isRegularFile(this.storageDirectory.resolve(STATE_FILENAME));
	}

	boolean hasLegacyWallet() {
		return !this.transientWallet && Files.isRegularFile(this.legacyWalletPath);
	}

	void cleanupTransientStorage() throws IOException {
		if (this.transientWallet)
			deleteTree(this.storageDirectory);
	}

	Path archiveRejectedNamespace() throws IOException {
		if (this.transientWallet || !Files.isDirectory(this.storageDirectory))
			return null;

		Path archivePath = this.storageDirectory.resolveSibling(
				this.storageDirectory.getFileName() + ".failed-" + UUID.randomUUID());
		try {
			Files.move(this.storageDirectory, archivePath, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(this.storageDirectory, archivePath);
		}
		return archivePath;
	}

	private static void cleanupChildren(Path transientRoot) throws IOException {
		try (var children = Files.list(transientRoot)) {
			for (Path child : children.filter(path -> path.getFileName().toString().startsWith("session-")
						|| path.getFileName().toString().startsWith("release-"))
					.toList())
				deleteTree(child);
		}
	}

	Path createReleaseDirectory() throws IOException {
		Path transientRoot = this.storageDirectory.getParent();
		Files.createDirectories(transientRoot);
		return Files.createTempDirectory(transientRoot, "release-");
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root))
			return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	Snapshot read() {
		if (this.transientWallet)
			return new Snapshot(State.LEGACY, false, null, null, null, null, null, false);

		Path statePath = this.storageDirectory.resolve(STATE_FILENAME);
		if (!Files.isRegularFile(statePath))
			return new Snapshot(State.LEGACY, false, null, null, null, null, null, false);

		try {
			JSONObject json = new JSONObject(Files.readString(statePath, StandardCharsets.UTF_8));
			if (json.getInt("version") != STATE_VERSION)
				throw new JSONException("Unsupported state version");

			State state = State.valueOf(json.getString("state"));
			String identityHash = json.optString("identityHash", null);
			if (identityHash != null && identityHash.isBlank())
				identityHash = null;
			String selectedServerUri = json.optString("selectedServerUri", null);
			if (selectedServerUri != null && selectedServerUri.isBlank())
				selectedServerUri = null;
			boolean syncValidated = json.optBoolean("syncValidated", false);
			if ((syncValidated && identityHash == null)
					|| (state == State.UNIFIED_READY && !syncValidated)
					|| (state == State.LEGACY && syncValidated))
				throw new JSONException("Invalid wallet migration state");

			Long recoveryRescanFromHeight = null;
			if (json.has("recoveryRescanFromHeight") && !json.isNull("recoveryRescanFromHeight")) {
				recoveryRescanFromHeight = json.getLong("recoveryRescanFromHeight");
				if (recoveryRescanFromHeight < 1)
					throw new JSONException("Invalid recovery rescan height");
			}

			PirateWallet.InitializationMode initializationMode = null;
			Integer initializationBirthdayHeight = null;
			if (json.has("initializationMode") || json.has("initializationBirthdayHeight")) {
				initializationMode = PirateWallet.InitializationMode.valueOf(json.getString("initializationMode"));
				initializationBirthdayHeight = json.getInt("initializationBirthdayHeight");
				if (initializationMode != PirateWallet.InitializationMode.NEW_AT_CURRENT_TIP
						|| initializationBirthdayHeight < 1)
					throw new JSONException("Invalid wallet initialization intent");
			}

			return new Snapshot(state, syncValidated, identityHash, selectedServerUri,
					recoveryRescanFromHeight, initializationMode, initializationBirthdayHeight, false);
		} catch (IOException | JSONException | IllegalArgumentException e) {
			return new Snapshot(State.FAILED_RECOVERABLE, false, null, null, null, null, null, true);
		}
	}

	void write(State state, boolean syncValidated, String identityHash) throws IOException {
		Snapshot current = this.read();
		this.write(state, syncValidated, identityHash, current.getSelectedServerUri(),
				current.getRecoveryRescanFromHeight());
	}

	void write(State state, boolean syncValidated, String identityHash, String selectedServerUri) throws IOException {
		this.write(state, syncValidated, identityHash, selectedServerUri,
				this.read().getRecoveryRescanFromHeight());
	}

	void write(State state, boolean syncValidated, String identityHash, String selectedServerUri,
			Long recoveryRescanFromHeight) throws IOException {
		Snapshot current = this.read();
		this.write(state, syncValidated, identityHash, selectedServerUri, recoveryRescanFromHeight,
				current.getInitializationMode(), current.getInitializationBirthdayHeight());
	}

	void write(State state, boolean syncValidated, String identityHash, String selectedServerUri,
			Long recoveryRescanFromHeight, PirateWallet.InitializationMode initializationMode,
			Integer initializationBirthdayHeight) throws IOException {
		if (this.transientWallet)
			return;
		if ((initializationMode == null) != (initializationBirthdayHeight == null)
				|| (initializationMode != null && (initializationMode != PirateWallet.InitializationMode.NEW_AT_CURRENT_TIP
				|| initializationBirthdayHeight < 1)))
			throw new IOException("Invalid wallet initialization intent");

		Files.createDirectories(this.storageDirectory);
		Snapshot current = this.read();
		Path statePath = this.storageDirectory.resolve(STATE_FILENAME);
		if (current.isCorrupt())
			Files.copy(statePath, this.storageDirectory.resolve(STATE_FILENAME + ".corrupt-" + UUID.randomUUID()),
					StandardCopyOption.COPY_ATTRIBUTES);

		JSONObject json = new JSONObject();
		json.put("version", STATE_VERSION);
		json.put("state", state.name());
		json.put("syncValidated", syncValidated);
		json.put("legacyCachePresent", this.hasLegacyWallet());
		if (identityHash != null)
			json.put("identityHash", identityHash);
		if (selectedServerUri != null)
			json.put("selectedServerUri", selectedServerUri);
		if (recoveryRescanFromHeight != null) {
			if (recoveryRescanFromHeight < 1)
				throw new IOException("Invalid recovery rescan height");
			json.put("recoveryRescanFromHeight", recoveryRescanFromHeight.longValue());
		}
		if (initializationMode != null) {
			json.put("initializationMode", initializationMode.name());
			json.put("initializationBirthdayHeight", initializationBirthdayHeight.intValue());
		}

		Path temporaryPath = Files.createTempFile(this.storageDirectory, ".qortium-wallet-state-", ".tmp");
		try {
			Files.writeString(temporaryPath, json.toString(2), StandardCharsets.UTF_8,
					StandardOpenOption.TRUNCATE_EXISTING);
			try (FileChannel fileChannel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
				fileChannel.force(true);
			}
			try {
				Files.move(temporaryPath, statePath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporaryPath, statePath, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}
}
