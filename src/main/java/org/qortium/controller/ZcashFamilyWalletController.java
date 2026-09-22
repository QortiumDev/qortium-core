package org.qortium.controller;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.ArbitraryDataReader;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ZcashFamilyWallet;
import org.qortium.crypto.Crypto;
import org.qortium.crosschain.ZcashFamilyWalletConfig;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public abstract class ZcashFamilyWalletController<W extends ZcashFamilyWallet> extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ZcashFamilyWalletController.class);
	private static final long SAVE_INTERVAL = 60 * 60 * 1000L;
	private static final long SHUTDOWN_JOIN_MILLIS = 5_000L;
	private static final ZcashFamilyNativeCoordinator NATIVE_COORDINATOR = ZcashFamilyNativeCoordinator.getInstance();

	/**
	 * Stable, machine-readable reason reported (via {@link ForeignBlockchainException.WalletBusyException})
	 * when the controller is bound to a different wallet and cannot be safely stopped or switched right
	 * now. Every wallet-bound read must fail this way rather than ever serving another wallet's data.
	 */
	public static final String WALLET_BUSY_REASON = "ARRR_WALLET_BUSY";

	public enum LifecycleState {
		NEW,
		RUNNING,
		STOPPING,
		TERMINATED,
		DEGRADED
	}

	public enum WalletSyncState {
		DISABLED,
		LOADING,
		SYNCHRONIZING,
		DEGRADED,
		READY
	}

	/** Best-effort atomic-unit balance strings for a coin's active wallet; null when unavailable. */
	public record WalletBalanceSnapshot(String totalAtomic, String verifiedAtomic) {
	}

	/** Stable status shared by the legacy text and opt-in structured API responses. */
	public static final class WalletSyncStatus {
		private final WalletSyncState state;
		private final String message;
		private final Long syncedBlocks;
		private final Long totalBlocks;
		private final boolean restartRequired;
		private final String recoveryState;
		private final Long scannedHeight;
		private final Long tipHeight;
		private final String totalBalanceAtomic;
		private final String verifiedBalanceAtomic;
		private final long observedAt;
		private final boolean stale;
		private final String walletIdentityHash;
		private final String lastErrorCode;
		private final String lastErrorMessage;

		private WalletSyncStatus(WalletSyncState state, String message, Long syncedBlocks, Long totalBlocks,
				boolean restartRequired, String recoveryState, Long scannedHeight, Long tipHeight,
				String totalBalanceAtomic, String verifiedBalanceAtomic, long observedAt, boolean stale,
				String walletIdentityHash, String lastErrorCode, String lastErrorMessage) {
			this.state = state;
			this.message = message;
			this.syncedBlocks = syncedBlocks;
			this.totalBlocks = totalBlocks;
			this.restartRequired = restartRequired;
			this.recoveryState = recoveryState;
			this.scannedHeight = scannedHeight;
			this.tipHeight = tipHeight;
			this.totalBalanceAtomic = totalBalanceAtomic;
			this.verifiedBalanceAtomic = verifiedBalanceAtomic;
			this.observedAt = observedAt;
			this.stale = stale;
			this.walletIdentityHash = walletIdentityHash;
			this.lastErrorCode = lastErrorCode;
			this.lastErrorMessage = lastErrorMessage;
		}

		private static WalletSyncStatus base(WalletSyncState state, String message, Long syncedBlocks,
				Long totalBlocks, boolean restartRequired, String recoveryState) {
			return new WalletSyncStatus(state, message, syncedBlocks, totalBlocks, restartRequired, recoveryState,
					null, null, null, null, System.currentTimeMillis(), false, null, null, null);
		}

		public static WalletSyncStatus disabled(String message) {
			return base(WalletSyncState.DISABLED, message, null, null, false, null);
		}

		public static WalletSyncStatus loading(String message) {
			return base(WalletSyncState.LOADING, message, null, null, false, null);
		}

		public static WalletSyncStatus synchronizing(String message, Long syncedBlocks, Long totalBlocks) {
			return base(WalletSyncState.SYNCHRONIZING, message, syncedBlocks, totalBlocks, false, null);
		}

		/** A verified-import recovery replay owns the wallet: reported as synchronizing plus a marker. */
		public static WalletSyncStatus recovering(String message, String recoveryState) {
			return base(WalletSyncState.SYNCHRONIZING, message, null, null, false, recoveryState);
		}

		public static WalletSyncStatus degraded(String message) {
			return base(WalletSyncState.DEGRADED, message, null, null, true, null);
		}

		public static WalletSyncStatus ready(String message) {
			return base(WalletSyncState.READY, message, null, null, false, null);
		}

		/**
		 * Same status with the given recovery marker (null clears it). A durable recovery replay that
		 * has not completed (anything but RECOVERED) can never coexist with a READY state: readiness
		 * requires fresh, successful synchronization evidence, and a pending/active recovery is neither.
		 */
		public WalletSyncStatus withRecoveryMarker(String recoveryState) {
			if (Objects.equals(this.recoveryState, recoveryState))
				return this;
			WalletSyncState effectiveState = this.state;
			if (effectiveState == WalletSyncState.READY && recoveryState != null
					&& !ZcashFamilyWallet.RecoveryProgress.RECOVERED.name().equals(recoveryState))
				effectiveState = WalletSyncState.SYNCHRONIZING;
			return new WalletSyncStatus(effectiveState, this.message, this.syncedBlocks, this.totalBlocks,
					this.restartRequired, recoveryState, this.scannedHeight, this.tipHeight,
					this.totalBalanceAtomic, this.verifiedBalanceAtomic, this.observedAt, this.stale,
					this.walletIdentityHash, this.lastErrorCode, this.lastErrorMessage);
		}

		/**
		 * Enriches this status with the wallet-bound snapshot fields: absolute chain heights (never the
		 * relative in-flight counters carried by syncedBlocks/totalBlocks), best-effort atomic-unit
		 * balances, and the identity hash of the wallet the snapshot actually belongs to. A READY state
		 * requires the absolute heights to be known; when they are not, READY is not fresh, successful
		 * sync evidence and is downgraded.
		 */
		public WalletSyncStatus withSnapshot(Long scannedHeight, Long tipHeight, String totalBalanceAtomic,
				String verifiedBalanceAtomic, String walletIdentityHash) {
			WalletSyncState effectiveState = this.state == WalletSyncState.READY
					&& (scannedHeight == null || tipHeight == null)
					? WalletSyncState.SYNCHRONIZING : this.state;
			return new WalletSyncStatus(effectiveState, this.message, this.syncedBlocks, this.totalBlocks,
					this.restartRequired, this.recoveryState, scannedHeight, tipHeight, totalBalanceAtomic,
					verifiedBalanceAtomic, this.observedAt, this.stale, walletIdentityHash,
					this.lastErrorCode, this.lastErrorMessage);
		}

		/** Sanitized last-error detail (no paths, no entropy, no stack trace) for a degraded/failed lane. */
		public WalletSyncStatus withLastError(String code, String message) {
			return new WalletSyncStatus(this.state, this.message, this.syncedBlocks, this.totalBlocks,
					this.restartRequired, this.recoveryState, this.scannedHeight, this.tipHeight,
					this.totalBalanceAtomic, this.verifiedBalanceAtomic, this.observedAt, this.stale,
					this.walletIdentityHash, code, message);
		}

		/**
		 * Marks this snapshot as served from cache rather than freshly observed (produced while the
		 * native lane was busy with other work, or kept after a failed refresh attempt). A stale READY
		 * is not fresh, successful sync evidence and is downgraded so structured consumers cannot treat
		 * it as a current guarantee.
		 */
		public WalletSyncStatus asStale() {
			if (this.stale)
				return this;
			WalletSyncState effectiveState = this.state == WalletSyncState.READY
					? WalletSyncState.SYNCHRONIZING : this.state;
			return new WalletSyncStatus(effectiveState, this.message, this.syncedBlocks, this.totalBlocks,
					this.restartRequired, this.recoveryState, this.scannedHeight, this.tipHeight,
					this.totalBalanceAtomic, this.verifiedBalanceAtomic, this.observedAt, true,
					this.walletIdentityHash, this.lastErrorCode, this.lastErrorMessage);
		}

		public WalletSyncState getState() {
			return this.state;
		}

		public String getMessage() {
			return this.message;
		}

		public Long getSyncedBlocks() {
			return this.syncedBlocks;
		}

		public Long getTotalBlocks() {
			return this.totalBlocks;
		}

		public boolean isRestartRequired() {
			return this.restartRequired;
		}

		/** RecoveryProgress name (PENDING/RECOVERING/RECOVERED), or null when recovery is not involved. */
		public String getRecoveryState() {
			return this.recoveryState;
		}

		/** Absolute wallet-scanned chain height, or null when unknown. Never a relative counter. */
		public Long getScannedHeight() {
			return this.scannedHeight;
		}

		/** Absolute chain tip height, or null when unknown. Never a relative counter. */
		public Long getTipHeight() {
			return this.tipHeight;
		}

		/** Total balance in 8-decimal atomic units, or null when unknown. */
		public String getTotalBalanceAtomic() {
			return this.totalBalanceAtomic;
		}

		/** Verified/spendable balance in 8-decimal atomic units, or null when unknown. */
		public String getVerifiedBalanceAtomic() {
			return this.verifiedBalanceAtomic;
		}

		/** Epoch-millisecond timestamp this snapshot was originally observed. */
		public long getObservedAt() {
			return this.observedAt;
		}

		/** Whether this snapshot is a cached reuse rather than a fresh, current observation. */
		public boolean isStale() {
			return this.stale;
		}

		/** Base58(SHA-256(entropy)) identity of the wallet this snapshot actually belongs to. */
		public String getWalletIdentityHash() {
			return this.walletIdentityHash;
		}

		public String getLastErrorCode() {
			return this.lastErrorCode;
		}

		public String getLastErrorMessage() {
			return this.lastErrorMessage;
		}
	}

	private static final class CachedWalletSyncStatus {
		private final WalletSyncStatus status;
		private final ZcashFamilyWallet wallet;

		private CachedWalletSyncStatus(WalletSyncStatus status, ZcashFamilyWallet wallet) {
			this.status = status;
			this.wallet = wallet;
		}
	}

	protected final ZcashFamilyWalletConfig config;
	private long lastSaveTime = 0L;
	private volatile boolean running;
	private volatile W currentWallet = null;
	private volatile boolean shouldLoadWallet = false;
	private volatile String loadStatus = null;
	private volatile String initializationFailure = null;
	private volatile CachedWalletSyncStatus cachedStatus = new CachedWalletSyncStatus(
			WalletSyncStatus.loading("Not initialized yet"), null);
	private volatile LifecycleState lifecycleState = LifecycleState.NEW;
	private volatile boolean shutdownPrepared = true;

	protected ZcashFamilyWalletController(ZcashFamilyWalletConfig config) {
		this.config = config;
	}

	protected abstract W createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException;

	/** Coin-specific opt-in hook for a fresh wallet whose birthday must be chosen from the current tip. */
	protected W createWallet(byte[] entropyBytes, boolean isNullSeedWallet, boolean initializeAtCurrentTip)
			throws IOException {
		if (initializeAtCurrentTip)
			throw new IOException(this.config.getDisplayName() + " does not support current-tip initialization");
		return this.createWallet(entropyBytes, isNullSeedWallet);
	}

	/** Whether an initialized wallet satisfies an exact current-tip initialization retry. */
	protected boolean isCurrentTipInitializedWallet(W wallet) {
		return false;
	}

	/** Optional safe, coin-specific detail for a failed wallet initialization. */
	protected String getWalletInitializationFailure(W wallet) {
		return null;
	}

	/**
	 * Coin-specific hook: best-effort atomic-unit balances for the currently active wallet, for
	 * sync-status snapshot enrichment. Returns null when the coin has no balance-in-status support.
	 * Implementations must never throw: a failure to read balances must be caught internally and
	 * reported as a snapshot whose fields are null, so a status read never fails just because a
	 * secondary balance read did. Runs on the native lane; the coordinator is reentrant for the
	 * thread that is already executing on it, so implementations may safely call the wallet's own
	 * public accessors.
	 */
	protected WalletBalanceSnapshot currentWalletBalanceSnapshot(W wallet) {
		return null;
	}

	@FunctionalInterface
	public interface WalletOperation<W extends ZcashFamilyWallet, T> {
		T execute(W wallet, ZcashFamilyNativeAdapter nativeAdapter) throws Exception;
	}

	@Override
	public synchronized void start() {
		if (this.lifecycleState != LifecycleState.NEW)
			return;

		this.running = true;
		this.lifecycleState = LifecycleState.RUNNING;
		super.start();
	}

	public synchronized boolean startController() {
		this.start();
		return this.lifecycleState == LifecycleState.RUNNING;
	}

	@Override
	public void run() {
		Thread.currentThread().setName(this.config.getDisplayName() + " Wallet Controller");
		Thread.currentThread().setPriority(MIN_PRIORITY);

		try {
			while (running && !Controller.isStopping()) {
                try {
				if (!this.waitWhileRunning(1000))
					break;

				if (!shouldLoadWallet)
					continue;

				if (!isLibraryLoaded()) {
					this.loadLibrary();

					if (!isLibraryLoaded()) {
						if (!this.waitWhileRunning(5 * 1000))
							break;
						continue;
					}
				}

				this.loadStatus = null;

				boolean syncAttempted = NATIVE_COORDINATOR.execute("synchronize " + this.config.getCurrencyCode() + " wallet",
						ZcashFamilyNativeCoordinator.SYNC_TIMEOUT, nativeAdapter -> {
					W wallet = this.currentWallet;
					if (wallet == null || wallet.isNullSeedWallet())
						return false;
					return wallet.withValidatedServerSelectionLease(nativeAdapter,
							leasedAdapter -> this.synchronizeCurrentWallet(wallet, leasedAdapter));
				});
				if (!syncAttempted)
					continue;

				if (!this.waitWhileRunning(30000))
					break;

				Long now = NTP.getTime();
				if (now != null && now - SAVE_INTERVAL >= this.lastSaveTime)
					this.saveCurrentWallet();
                } catch (ZcashFamilyNativeCoordinator.NativeQueueContentionException e) {
                    if (NATIVE_COORDINATOR.isDegraded()) throw e;
                    // A queued operation never touched native state. Do not turn normal
                    // contention into controller shutdown/restart-required recovery.
                    if (!this.waitWhileRunning(1000)) break;
                }
			}
		} catch (InterruptedException e) {
			// Fall-through to exit.
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			String status = this.config.getDisplayName() + " native wallet is unavailable until Core restart";
			this.cacheStatus(WalletSyncStatus.degraded(status));
			this.lifecycleState = LifecycleState.DEGRADED;
			this.loadStatus = status;
			LOGGER.error("{} wallet controller stopped because the native lane is unavailable: {}",
					this.config.getDisplayName(), e.getMessage());
		} finally {
			// Consume any external interrupt so cleanup does not present it to the native
			// coordinator as an interrupted native operation. Normal shutdown uses notifyAll().
			Thread.interrupted();
			this.running = false;
			this.shutdownPrepared = this.prepareCurrentWalletForShutdown();
			this.saveCurrentWallet();
			if (NATIVE_COORDINATOR.isDegraded() || !this.shutdownPrepared) {
				String status = NATIVE_COORDINATOR.isDegraded()
						? this.config.getDisplayName() + " native wallet is unavailable until Core restart"
						: this.config.getDisplayName() + " wallet did not stop cleanly; Core restart required";
				this.cacheStatus(WalletSyncStatus.degraded(status));
				this.loadStatus = status;
				this.lifecycleState = LifecycleState.DEGRADED;
			} else {
				this.cacheStatus(WalletSyncStatus.loading(
						this.config.getDisplayName() + " wallet controller is stopped"));
				this.lifecycleState = LifecycleState.TERMINATED;
			}
		}
	}

	private synchronized boolean waitWhileRunning(long delayMillis) throws InterruptedException {
		if (!this.running)
			return false;
		this.wait(delayMillis);
		return this.running;
	}

	boolean synchronizeCurrentWallet(W wallet, ZcashFamilyNativeAdapter nativeAdapter) throws IOException {
		if (!wallet.prepareForSynchronization(nativeAdapter)) {
			this.cacheCurrentWalletStatus(WalletSyncStatus.loading(
					"Waiting for a validated " + this.config.getDisplayName() + " lightwalletd endpoint..."));
			return true;
		}

		if (wallet.usesPersistentNativeStorage()) {
			// A pending verified-import recovery must be driven through the dedicated native
			// rescan path BEFORE any normal sync handling: a plain sync never clears the
			// native replay gate, and isSynchronized() reports false while the durable
			// recovery record exists.
			ZcashFamilyWallet.RecoveryProgress recovery = wallet.progressRecovery(nativeAdapter);
			if (recovery == ZcashFamilyWallet.RecoveryProgress.PENDING
					|| recovery == ZcashFamilyWallet.RecoveryProgress.RECOVERING) {
				this.cacheCurrentWalletStatus(WalletSyncStatus.recovering(
						recovery == ZcashFamilyWallet.RecoveryProgress.RECOVERING
								? "Recovering imported keys..."
								: "Recovery rescan pending...",
						recovery.name()));
				return true;
			}

			if (wallet.isSynchronized()) {
				wallet.setReady(true);
				wallet.recordValidatedSync(nativeAdapter);
				this.cacheCurrentWalletStatus(WalletSyncStatus.ready("Synchronized")
						.withRecoveryMarker(recovery == ZcashFamilyWallet.RecoveryProgress.RECOVERED
								? recovery.name() : null));
				return true;
			}
			// A persisted incomplete native sync can still report in_progress after a Core
			// restart even though the process-local native task no longer exists. Always
			// reissue the idempotent sync request while the wallet is short of the tip; the
			// Unified service keeps a live task unchanged and resumes a restored one.
		}

		this.cacheCurrentWalletStatus(WalletSyncStatus.synchronizing("Synchronizing wallet...", null, null));
		LOGGER.debug("Syncing {} wallet...", this.config.getDisplayName());
		String response = nativeAdapter.execute("sync", "");
		LOGGER.debug("{} wallet sync returned a response", this.config.getDisplayName());

		boolean syncAccepted = false;
		boolean synchronizedAtTip = false;
		try {
			JSONObject json = new JSONObject(response);
			if (json.has("result")) {
				String result = json.getString("result");
				if (Objects.equals(result, "success")) {
					syncAccepted = true;
					wallet.recordSynchronizationAccepted(nativeAdapter);
					wallet.setReady(true);
					synchronizedAtTip = wallet.isSynchronized();
					if (synchronizedAtTip)
						wallet.recordValidatedSync(nativeAdapter);
				}
			}
		} catch (JSONException e) {
			LOGGER.info("Unable to interpret JSON", e);
		}
		this.cacheCurrentWalletStatus(statusAfterSyncAttempt(wallet.isReady(),
				wallet.usesPersistentNativeStorage(), syncAccepted, synchronizedAtTip));
		return true;
	}

	public boolean shutdown() {
		synchronized (this) {
			if (this.lifecycleState == LifecycleState.TERMINATED)
				return this.shutdownPrepared;
			if (this.lifecycleState == LifecycleState.DEGRADED)
				return false;

			if (this.lifecycleState == LifecycleState.NEW) {
				this.running = false;
				this.lifecycleState = LifecycleState.TERMINATED;
				this.cacheStatus(WalletSyncStatus.loading(
						this.config.getDisplayName() + " wallet controller is stopped"));
				return true;
			}

			this.cacheStatus(WalletSyncStatus.loading(
					"Stopping " + this.config.getDisplayName() + " wallet controller..."));
			this.lifecycleState = LifecycleState.STOPPING;
			this.running = false;
			this.notifyAll();
		}

		if (Thread.currentThread() != this) {
			try {
				this.join(SHUTDOWN_JOIN_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		return this.lifecycleState == LifecycleState.TERMINATED && this.shutdownPrepared;
	}

	public LifecycleState getLifecycleState() {
		return this.lifecycleState;
	}

	public boolean requiresCoreRestart() {
		return this.lifecycleState == LifecycleState.DEGRADED || NATIVE_COORDINATOR.isDegraded();
	}

	static boolean acceptsWalletOperations(LifecycleState state) {
		return state == LifecycleState.RUNNING;
	}

	private void loadLibrary() throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String libFileName = resolveRustLibFilename();
			boolean unifiedWalletEnabled = this.config.isUnifiedWalletEnabled();
			if (libFileName == null) {
				String osName = System.getProperty("os.name");
				String osArchitecture = System.getProperty("os.arch");
				setLoadStatus(String.format("Unsupported architecture (%s %s)", osName, osArchitecture));
				return;
			}

			Path libDirectory = this.config.getRustLibOuterDirectory();
			Path libPath = Paths.get(libDirectory.toString(), libFileName);
			String qdnWalletSignature = this.config.getActiveQdnWalletSignature();
			ArbitraryTransactionData transactionData = null;
			Path authenticatedUnifiedSource = null;
			if (unifiedWalletEnabled) {
				transactionData = getPinnedTransactionData(repository, qdnWalletSignature);
				try {
					authenticatedUnifiedSource = resolvePinnedQdnWalletPath(qdnWalletSignature, transactionData, true);
				} catch (MissingDataException e) {
					setMissingWalletLibraryStatus();
					return;
				}
				try {
					validatePinnedResourcePath(authenticatedUnifiedSource, libFileName, true);
				} catch (DataException e) {
					LOGGER.error("Invalid configured {} wallet library: {} (configured QDN signature: {})",
							this.config.getDisplayName(), e.getMessage(), qdnWalletSignature);
					setLoadStatus(String.format("Configured %s wallet library could not be read from QDN",
							this.config.getDisplayName()));
					return;
				}
			}

			if (Files.exists(libPath)) {
				try {
					loadValidatedNativeLibrary(libDirectory, libFileName, authenticatedUnifiedSource);
				} catch (DataException e) {
					LOGGER.error("Invalid cached {} wallet library: {} (configured QDN signature: {})",
							this.config.getDisplayName(), e.getMessage(), qdnWalletSignature);
					setLoadStatus(String.format("Cached %s wallet library failed integrity validation",
							this.config.getDisplayName()));
					return;
				}
				return;
			}

			if (transactionData == null)
				transactionData = getPinnedTransactionData(repository, qdnWalletSignature);
			Path resourcePath = authenticatedUnifiedSource;
			if (!unifiedWalletEnabled) {
				List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
				if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					setLoadStatus("Searching for peers...");
					return;
				}

				try {
					resourcePath = resolvePinnedQdnWalletPath(qdnWalletSignature, transactionData);
				} catch (MissingDataException e) {
					setMissingWalletLibraryStatus();
					return;
				}
			}

			try {
				validatePinnedResourcePath(resourcePath, libFileName, unifiedWalletEnabled);
			} catch (DataException e) {
				LOGGER.error("Invalid configured {} wallet library: {} (configured QDN signature: {})",
						this.config.getDisplayName(), e.getMessage(), qdnWalletSignature);
				setLoadStatus(String.format("Configured %s wallet library could not be read from QDN",
						this.config.getDisplayName()));
				return;
			}

			try {
				if (unifiedWalletEnabled) {
					PirateUnifiedWalletBundle.install(resourcePath, libDirectory, libFileName);
				} else {
					Files.createDirectories(libDirectory);
					FileUtils.copyDirectory(resourcePath.toFile(), libDirectory.toFile());
				}
				loadValidatedNativeLibrary(libDirectory, libFileName, authenticatedUnifiedSource);
			} catch (DataException e) {
				LOGGER.error("Invalid installed {} wallet library: {} (configured QDN signature: {})",
						this.config.getDisplayName(), e.getMessage(), qdnWalletSignature);
				setLoadStatus(String.format("Installed %s wallet library failed integrity validation",
						this.config.getDisplayName()));
				return;
			}
		} catch (DataException e) {
			LOGGER.error("Repository issue when loading {} wallet library", this.config.getDisplayName(), e);
		} catch (IOException e) {
			LOGGER.error("Error when loading {} wallet library", this.config.getDisplayName(), e);
		}
	}

	private void setMissingWalletLibraryStatus() {
		LOGGER.info("Missing data when loading configured {} wallet library", this.config.getDisplayName());
		List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
		if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers())
			setLoadStatus("Searching for peers...");
		else
			setLoadStatus(String.format("Downloading configured %s wallet library from QDN...",
					this.config.getDisplayName()));
	}

	static ArbitraryTransactionData getPinnedTransactionData(Repository repository, String signature58) throws DataException {
		if (signature58 == null || signature58.isBlank())
			throw new DataException("Configured wallet QDN transaction signature is missing");

		final byte[] signature;
		try {
			signature = Base58.decode(signature58);
		} catch (RuntimeException e) {
			throw new DataException("Configured wallet QDN transaction signature is invalid");
		}

		if (signature == null || signature.length != Transformer.SIGNATURE_LENGTH)
			throw new DataException("Configured wallet QDN transaction signature is invalid");

		TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
		if (!(transactionData instanceof ArbitraryTransactionData arbitraryTransactionData))
			throw new DataException("Configured wallet QDN signature does not identify an ARBITRARY transaction");

		if (arbitraryTransactionData.getService() != Service.ARBITRARY_DATA)
			throw new DataException("Configured wallet QDN transaction is not an ARBITRARY_DATA publication");

		return arbitraryTransactionData;
	}

	static Path resolvePinnedQdnWalletPath(String signature58, ArbitraryTransactionData transactionData)
			throws DataException, IOException, MissingDataException {
		return resolvePinnedQdnWalletPath(signature58, transactionData, false);
	}

	static Path resolvePinnedQdnWalletPath(String signature58, ArbitraryTransactionData transactionData,
			boolean overwrite)
			throws DataException, IOException, MissingDataException {
		return resolvePinnedQdnWalletPath(signature58, transactionData, overwrite, true);
	}

	static Path resolvePinnedQdnWalletPath(String signature58, ArbitraryTransactionData transactionData,
			boolean overwrite, boolean canRequestMissingFiles)
			throws DataException, IOException, MissingDataException {
		if (transactionData == null)
			throw new DataException("Configured wallet QDN transaction is missing");
		if (transactionData.getService() != Service.ARBITRARY_DATA)
			throw new DataException("Configured wallet QDN transaction is not an ARBITRARY_DATA publication");

		ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(signature58,
				ArbitraryDataFile.ResourceIdType.TRANSACTION_DATA, transactionData.getService(), transactionData.getIdentifier());
		arbitraryDataReader.setTransactionData(transactionData);
		arbitraryDataReader.setCanRequestMissingFiles(canRequestMissingFiles);
		arbitraryDataReader.loadSynchronously(overwrite);
		return arbitraryDataReader.getFilePath();
	}

	static void validatePinnedResourcePath(Path resourcePath, String libFileName) throws DataException {
		validatePinnedResourcePath(resourcePath, libFileName, false);
	}

	static void validatePinnedResourcePath(Path resourcePath, String libFileName, boolean unifiedWalletEnabled)
			throws DataException {
		if (resourcePath == null || !Files.isDirectory(resourcePath))
			throw new DataException("wallet library resource is not a directory");
		if (libFileName == null || !Files.isRegularFile(resourcePath.resolve(libFileName)))
			throw new DataException("wallet library resource is missing the expected platform library");
		if (unifiedWalletEnabled)
			PirateUnifiedWalletBundle.validate(resourcePath, libFileName);
	}

	public static String resolveRustLibFilename() {
		return resolveRustLibFilename(System.getProperty("os.name"), System.getProperty("os.arch"));
	}

	static String resolveRustLibFilename(String osName, String osArchitecture) {
		if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64"))
			return "librust-macos-x86_64.dylib";
		else if (osName.equals("Mac OS X") && (osArchitecture.equals("aarch64") || osArchitecture.equals("arm64")))
			return "librust-macos-aarch64.dylib";
		else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("aarch64"))
			return "librust-linux-aarch64.so";
		else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("amd64"))
			return "librust-linux-x86_64.so";
		else if (osName.contains("Windows") && osArchitecture.equals("amd64"))
			return "librust-windows-x86_64.dll";

		return null;
	}

	/** Coin-specific ownership gate; runs on the native lane before any switch/cancel. */
	protected void requireWalletOwner(String entropy58, boolean isNullSeedWallet) throws ForeignBlockchainException { }
	protected void walletSelected(W wallet) { }
    protected void walletSelectionPending(String entropy58) throws ForeignBlockchainException { }
	private boolean selectingExplicitly; // Native-lane-only; never a request-thread bypass.
	protected boolean explicitWalletSelection() { return selectingExplicitly; }

	protected final void activateEntropyWallet(String entropy58, Runnable checkRevision) throws ForeignBlockchainException {
		if (!isValidEntropy(entropy58)) throw new ForeignBlockchainException("Invalid entropy bytes");
		executeChecked("activate wallet", nativeAdapter -> {
			if (!acceptsWalletOperations(this.lifecycleState)) throw new ForeignBlockchainException("Wallet controller isn't running");
			checkRevision.run();
			this.selectingExplicitly = true;
			try {
				if (!this.initWithEntropy58(entropy58, false, nativeAdapter)) {
                    if (!nativeAdapter.isLoaded()) {
                        // Activation chooses an owner even while the library loads;
                        // its subsequent authorized reads may initialize only that wallet.
                        walletSelectionPending(entropy58);
                        return null;
                    }
					throw new ForeignBlockchainException(this.initializationFailure == null ? "Wallet activation not completed" : this.initializationFailure);
                }
				return null;
			} finally { this.selectingExplicitly = false; }
		});
	}

	private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet,
			ZcashFamilyNativeAdapter nativeAdapter) {
		return this.initWithEntropy58(entropy58, isNullSeedWallet, false, nativeAdapter);
	}

	private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet,
			boolean initializeAtCurrentTip, ZcashFamilyNativeAdapter nativeAdapter) {
		this.initializationFailure = null;
		if (!explicitWalletSelection()) {
			try { requireWalletOwner(entropy58, isNullSeedWallet); }
			catch (ForeignBlockchainException e) { this.initializationFailure = WALLET_BUSY_REASON; return false; }
		}

		if (!nativeAdapter.isLoaded()) {
			shouldLoadWallet = true;
			return false;
		}

		byte[] entropyBytes = Base58.decode(entropy58);
		if (entropyBytes == null || entropyBytes.length != 32) {
			LOGGER.info("Invalid entropy bytes");
			this.initializationFailure = "Invalid entropy bytes";
			return false;
		}

		W previousWallet = null;
		if (this.currentWallet != null) {
			if (this.currentWallet.matchesWallet(entropyBytes, isNullSeedWallet)) {
				if (initializeAtCurrentTip && !this.isCurrentTipInitializedWallet(this.currentWallet)) {
					this.initializationFailure = "Known-new initialization requires an unused wallet namespace";
					return false;
				}
				return true;
			}

			if (!this.currentWallet.prepareForSwitch(nativeAdapter)) {
				LOGGER.info("Unable to switch {} wallet because prior native work has not terminated",
						this.config.getDisplayName());
				this.initializationFailure = WALLET_BUSY_REASON;
				return false;
			}
			if (explicitWalletSelection() && !this.currentWallet.isNullSeedWallet()) {
				try {
					if (!this.currentWallet.save()) { this.initializationFailure = "Prior wallet save was not confirmed"; return false; }
				} catch (IOException | RuntimeException e) { this.initializationFailure = "Prior wallet save failed"; return false; }
			}
			previousWallet = this.currentWallet;
			if (!explicitWalletSelection()) this.saveCurrentWallet();
			this.currentWallet = null;
		}

		try {
			this.currentWallet = this.createWallet(entropyBytes, isNullSeedWallet, initializeAtCurrentTip);
			if (!this.currentWallet.isReady()) {
				this.initializationFailure = this.getWalletInitializationFailure(this.currentWallet);
				this.currentWallet = null;
			} else {
				// The native library can outlive a stopped controller. In that case wallet
				// initialization succeeds without passing through the unloaded branch above,
				// so explicitly re-arm the new controller's background sync loop.
				this.shouldLoadWallet = true;
				walletSelected(this.currentWallet);
				if (previousWallet != null)
					previousWallet.cleanupAfterSwitch();
			}
			return this.currentWallet != null;
		} catch (IOException e) {
			this.initializationFailure = e.getMessage();
			LOGGER.info("Unable to initialize wallet: {}", e.getMessage());
		}

		return false;
	}

	/**
	 * Initializes one explicitly known-new entropy wallet at a coin-specific validated current tip.
	 * The coin implementation owns durable intent and exact-retry semantics.
	 */
	protected final W initializeWalletAtCurrentTip(String entropy58, Runnable checkRevision) throws ForeignBlockchainException {
		if (!acceptsWalletOperations(this.lifecycleState))
			throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet controller isn't running");
		if (!isValidEntropy(entropy58))
			throw new ForeignBlockchainException("Invalid entropy bytes");

		return executeChecked("initialize known-new " + this.config.getCurrencyCode() + " wallet", nativeAdapter -> {
            checkRevision.run();
			if (!acceptsWalletOperations(this.lifecycleState))
				throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet controller isn't running");
			boolean initialized;
			this.selectingExplicitly = true;
			try { initialized = this.initWithEntropy58(entropy58, false, true, nativeAdapter); }
			finally { this.selectingExplicitly = false; }
			if (!initialized) {
				if (WALLET_BUSY_REASON.equals(this.initializationFailure))
					throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
				throw new ForeignBlockchainException(this.initializationFailure == null
						? this.config.getDisplayName() + " wallet could not be initialized as known-new"
						: this.initializationFailure);
			}
			return this.currentWallet;
		});
	}

	private void saveCurrentWallet() {
		try {
			NATIVE_COORDINATOR.execute("save " + this.config.getCurrencyCode() + " wallet", nativeAdapter -> {
				if (this.currentWallet == null)
					return null;

				if (this.currentWallet.save()) {
					Long now = NTP.getTime();
					if (now != null)
						this.lastSaveTime = now;
				}
				return null;
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			LOGGER.info("Unable to save wallet");
		}
	}

	private boolean prepareCurrentWalletForShutdown() {
		try {
			return NATIVE_COORDINATOR.execute("prepare " + this.config.getCurrencyCode() + " wallet shutdown", nativeAdapter -> {
				if (this.currentWallet != null && !this.currentWallet.prepareForShutdown(nativeAdapter))
					return false;
				return true;
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			LOGGER.warn("Unable to prepare {} wallet shutdown", this.config.getDisplayName());
			return false;
		}
	}

	public String getSyncStatus() throws ForeignBlockchainException {
		return this.getSyncStatusDetails().getMessage();
	}

	public String getSyncStatus(String entropy58) throws ForeignBlockchainException {
		return this.getSyncStatusDetails(entropy58).getMessage();
	}

	public WalletSyncStatus getSyncStatusDetails() throws ForeignBlockchainException {
		return this.getBoundedSyncStatus(null);
	}

	public WalletSyncStatus getSyncStatusDetails(String entropy58) throws ForeignBlockchainException {
		return this.getBoundedSyncStatus(entropy58);
	}

	public <T> T withEntropyWallet(String entropy58, boolean requireSynchronized,
			WalletOperation<W, T> operation) throws ForeignBlockchainException {
		return withWallet(entropy58, false, requireSynchronized, true, operation);
	}

	public <T> T withNullSeedWallet(WalletOperation<W, T> operation) throws ForeignBlockchainException {
		return withWallet(Base58.encode(new byte[32]), true, false, false, operation);
	}

	private <T> T withWallet(String entropy58, boolean isNullSeedWallet, boolean requireSynchronized,
			boolean requireNotNullSeed, WalletOperation<W, T> operation) throws ForeignBlockchainException {
		// Fast, non-blocking preflight BEFORE submitting anything to the native coordinator: without
		// this, a request for a different wallet than the one currently bound would either queue
		// behind the in-flight/queued work for up to the coordinator's default timeout (two minutes)
		// or be rejected outright by a full queue, and only then surface - as a generic failure, not
		// the structured busy signal every wallet-bound read must give.
		this.requireWalletOwner(entropy58, isNullSeedWallet);
		this.assertNotCrossWalletBusy(entropy58, isNullSeedWallet);

		try {
			return NATIVE_COORDINATOR.execute("execute " + this.config.getCurrencyCode() + " wallet operation",
					nativeAdapter -> {
				if (!acceptsWalletOperations(this.lifecycleState))
					throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet controller isn't running");
				if (!this.initWithEntropy58(entropy58, isNullSeedWallet, nativeAdapter)) {
					if (WALLET_BUSY_REASON.equals(this.initializationFailure))
						throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
					throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet isn't initialized yet");
				}
				ensureInitialized(nativeAdapter);
				W wallet = this.currentWallet;
				// Defense in depth: initWithEntropy58() must already guarantee this, but a served wallet
				// must never diverge from the requester's own entropy under any future refactor.
				byte[] requestedEntropyBytes = Base58.decode(entropy58);
				if (requestedEntropyBytes == null || !wallet.matchesWallet(requestedEntropyBytes, isNullSeedWallet))
					throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
				return wallet.withValidatedServerSelectionLease(nativeAdapter, leasedAdapter -> {
					if (!wallet.prepareForSynchronization(leasedAdapter))
						throw new ForeignBlockchainException(
								this.config.getDisplayName() + " wallet endpoint is not validated yet");
					if (requireSynchronized)
						ensureSynchronized(leasedAdapter);
					if (requireNotNullSeed)
						ensureNotNullSeedInternal();
					return operation.execute(wallet, leasedAdapter);
				});
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			Throwable cause = e;
			while (cause != null) {
				if (cause instanceof ForeignBlockchainException foreignBlockchainException)
					throw foreignBlockchainException;
				cause = cause.getCause();
			}
			// No ForeignBlockchainException anywhere in the cause chain means the operation itself
			// never got to run: the coordinator either rejected the submission outright (its single
			// worker plus one-deep queue were both occupied) or the wait to be scheduled outlasted
			// the default timeout - both only happen because another wallet's native work is holding
			// the lane, so this must report the same structured busy signal, not a generic failure.
			// A lane that is now degraded (a mid-operation timeout) is a different, unrelated failure
			// mode and keeps the generic mapping below.
			if (!NATIVE_COORDINATOR.isDegraded()
					&& (e.getCause() instanceof java.util.concurrent.RejectedExecutionException
							|| e.getCause() instanceof java.util.concurrent.TimeoutException
							|| e.getCause() instanceof InterruptedException))
				throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
			throw new ForeignBlockchainException(e.getMessage());
		}
	}

	/**
	 * Fast, non-blocking cross-wallet busy check shared by every wallet-bound read
	 * (walletaddress/walletbalance/wallettransactions via {@link #withWallet}, and syncstatus via
	 * {@link #computeBoundedSyncStatus}). Only ever a fast-fail optimization: the coordinator itself
	 * remains the sole authority once an operation actually runs, this just avoids ever submitting a
	 * cross-wallet request into a lane that is currently occupied by different wallet's work.
	 */
	private void assertNotCrossWalletBusy(String entropy58, boolean isNullSeedWallet) throws ForeignBlockchainException {
		if (!NATIVE_COORDINATOR.isBusy())
			return;

		byte[] entropyBytes;
		try {
			entropyBytes = Base58.decode(entropy58);
		} catch (RuntimeException e) {
			entropyBytes = null;
		}
		W wallet = this.currentWallet;
		if (entropyBytes != null && entropyBytes.length == 32 && wallet != null
				&& wallet.matchesWallet(entropyBytes, isNullSeedWallet))
			return; // busy, but already bound to the SAME wallet: an ordinary in-flight operation

		throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
	}

	private void ensureInitialized(ZcashFamilyNativeAdapter nativeAdapter) throws ForeignBlockchainException {
		if (!nativeAdapter.isLoaded() || this.currentWallet == null || !this.currentWallet.isInitialized())
			throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet isn't initialized yet");
	}

	private void ensureNotNullSeedInternal() throws ForeignBlockchainException {
		if (this.currentWallet == null || this.currentWallet.isNullSeedWallet())
			throw new ForeignBlockchainException("Invalid wallet");
	}

	private void ensureSynchronized(ZcashFamilyNativeAdapter nativeAdapter) throws ForeignBlockchainException {
		if (this.currentWallet == null || !this.currentWallet.isSynchronized())
			throw new ForeignBlockchainException("Wallet isn't synchronized yet");

		String response = nativeAdapter.execute("syncStatus", "");
		JSONObject json = new JSONObject(response);
		if (isSyncInProgress(json)) {
			Long syncedBlocks = optionalLong(json, "synced_blocks");
			Long totalBlocks = optionalLong(json, "total_blocks");
			String progress = syncedBlocks != null && totalBlocks != null
					? String.format("Sync in progress (%d / %d). Please try again later.", syncedBlocks, totalBlocks)
					: "Sync in progress. Please try again later.";
			throw new ForeignBlockchainException(progress);
		}
	}

	private WalletSyncStatus getSyncStatus(ZcashFamilyNativeAdapter nativeAdapter) {
		if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
			if (this.loadStatus != null)
				return cacheStatus(WalletSyncStatus.loading(this.loadStatus));

			return cacheStatus(WalletSyncStatus.loading("Not initialized yet"));
		}

		String syncStatusResponse = nativeAdapter.execute("syncStatus", "");
		JSONObject json = new JSONObject(syncStatusResponse);
		WalletSyncStatus status = interpretNativeSyncStatus(json, this.currentWallet.isSynchronized());
		ZcashFamilyWallet.RecoveryProgress recoveryMarker = this.currentWallet.peekRecoveryProgress();
		if (recoveryMarker != null)
			status = status.withRecoveryMarker(recoveryMarker.name());
		status = this.enrichWithSnapshot(status);
		return cacheCurrentWalletStatus(status);
	}

	/**
	 * Best-effort absolute heights, atomic-unit balances and wallet identity for the currently active
	 * wallet. Each reading fails closed to null on its own rather than failing the whole status: a
	 * status read must never fail just because a secondary snapshot field could not be determined.
	 */
	private WalletSyncStatus enrichWithSnapshot(WalletSyncStatus status) {
		W wallet = this.currentWallet;
		if (wallet == null)
			return status;

		Long scannedHeight = safeAbsoluteHeight(wallet::getHeight);
		Long tipHeight = safeAbsoluteHeight(wallet::getChainTip);
		String walletIdentityHash = wallet.getWalletIdentityHash();
		WalletBalanceSnapshot balances = this.currentWalletBalanceSnapshot(wallet);

		return status.withSnapshot(scannedHeight, tipHeight,
				balances == null ? null : balances.totalAtomic(),
				balances == null ? null : balances.verifiedAtomic(),
				walletIdentityHash);
	}

	private static Long safeAbsoluteHeight(java.util.function.Supplier<Integer> heightSupplier) {
		try {
			Integer height = heightSupplier.get();
			return height == null ? null : Long.valueOf(height);
		} catch (RuntimeException e) {
			return null;
		}
	}

	static WalletSyncStatus interpretNativeSyncStatus(JSONObject json, boolean walletSynchronized) {
		if (isSyncInProgress(json)) {
			Long syncedBlocks = optionalLong(json, "synced_blocks");
			Long totalBlocks = optionalLong(json, "total_blocks");
			String message = syncedBlocks != null && totalBlocks != null
					? String.format("Sync in progress (%d / %d)", syncedBlocks, totalBlocks)
					: "Sync in progress";
			return WalletSyncStatus.synchronizing(message, syncedBlocks, totalBlocks);
		}

		return walletSynchronized
				? WalletSyncStatus.ready("Synchronized")
				: WalletSyncStatus.loading("Initializing wallet...");
	}

	static WalletSyncStatus statusAfterSyncAttempt(boolean walletReady, boolean persistentStorage,
			boolean syncAccepted, boolean synchronizedAtTip) {
		if (persistentStorage && !synchronizedAtTip)
			return syncAccepted
					? WalletSyncStatus.synchronizing("Synchronizing wallet...", null, null)
					: WalletSyncStatus.loading("Initializing wallet...");
		return walletReady
				? WalletSyncStatus.ready("Synchronized")
				: WalletSyncStatus.loading("Initializing wallet...");
	}

	static boolean isSyncInProgress(JSONObject json) {
		return booleanValue(json, "in_progress") || booleanValue(json, "syncing");
	}

	private static boolean booleanValue(JSONObject json, String key) {
		Object value = json.opt(key);
		return value instanceof Boolean ? (Boolean) value
				: value instanceof String && Boolean.parseBoolean((String) value);
	}

	private static Long optionalLong(JSONObject json, String key) {
		if (!json.has(key) || json.isNull(key))
			return null;
		try {
			return json.getLong(key);
		} catch (JSONException e) {
			return null;
		}
	}

	/**
	 * Window beyond which a served snapshot is considered stale purely by age, independent of
	 * whether it was served from cache because the native lane was busy. Applied uniformly to every
	 * returned status by {@link #getBoundedSyncStatus(String)} so no internal branch can accidentally
	 * skip it.
	 */
	static final long STATUS_STALE_WINDOW_MILLIS = 90_000L;

	private WalletSyncStatus getBoundedSyncStatus(String entropy58) throws ForeignBlockchainException {
		return applyStaleWindow(this.computeBoundedSyncStatus(entropy58));
	}

	/** Re-checks staleness purely by the snapshot's age, regardless of how it was produced. */
	private static WalletSyncStatus applyStaleWindow(WalletSyncStatus status) {
		if (status.isStale())
			return status;
		long age = System.currentTimeMillis() - status.getObservedAt();
		return age > STATUS_STALE_WINDOW_MILLIS ? status.asStale() : status;
	}

	private WalletSyncStatus computeBoundedSyncStatus(String entropy58) throws ForeignBlockchainException {
		if (entropy58 != null) this.requireWalletOwner(entropy58, false);
		if (this.requiresCoreRestart())
			return cacheStatus(WalletSyncStatus.degraded(
					this.config.getDisplayName() + " native wallet is unavailable until Core restart")
					.withLastError("CORE_RESTART_REQUIRED", "Native wallet is unavailable until Core restart"));

		if (this.lifecycleState == LifecycleState.NEW)
			return cacheStatus(WalletSyncStatus.loading(
					this.config.getDisplayName() + " wallet controller has not started"));
		if (this.lifecycleState == LifecycleState.STOPPING)
			return this.cachedStatus.status;
		if (this.lifecycleState == LifecycleState.TERMINATED)
			return cacheStatus(WalletSyncStatus.loading(
					this.config.getDisplayName() + " wallet controller is stopped"));
		if (entropy58 != null && !isValidEntropy(entropy58))
			return WalletSyncStatus.loading("Invalid entropy bytes");

		if (NATIVE_COORDINATOR.isBusy()) {
			CachedWalletSyncStatus cachedStatus = this.cachedStatus;
			if (entropy58 == null || this.matchesCachedWallet(cachedStatus, entropy58))
				return withPeekedRecoveryMarker(cachedStatus).asStale();
			// Never fall back to a generic "loading" placeholder here: that would blur a genuinely
			// busy/switch-blocked wallet with an ordinary unsynchronized one, and callers need the
			// stable, structured signal to retry instead of misreading this as sync progress.
			throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
		}

		try {
			Duration timeout = this.statusTimeoutFor(entropy58);
			return NATIVE_COORDINATOR.execute("get wallet synchronization status",
					timeout, nativeAdapter -> {
				if (entropy58 != null && !this.initWithEntropy58(entropy58, false, nativeAdapter)) {
					if (WALLET_BUSY_REASON.equals(this.initializationFailure))
						throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
					return WalletSyncStatus.loading(this.config.getDisplayName() + " wallet isn't initialized yet");
				}
				WalletSyncStatus fresh = this.getSyncStatus(nativeAdapter);
				// Defense in depth: initWithEntropy58() must already guarantee this, but the served
				// snapshot's identity must never diverge from the requester's own entropy under any
				// future refactor - never trust the "current" wallet without re-checking it here.
				if (entropy58 != null && !Objects.equals(fresh.getWalletIdentityHash(), expectedIdentityHash58(entropy58)))
					throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
				return fresh;
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			Throwable cause = e;
			while (cause != null) {
				if (cause instanceof ForeignBlockchainException.WalletBusyException walletBusyException)
					throw walletBusyException;
				cause = cause.getCause();
			}
			if (NATIVE_COORDINATOR.isDegraded()) {
				WalletSyncStatus status = cacheStatus(WalletSyncStatus.degraded(
						this.config.getDisplayName() + " native wallet is unavailable until Core restart")
						.withLastError("NATIVE_LANE_DEGRADED", "Native wallet lane is degraded"));
				this.lifecycleState = LifecycleState.DEGRADED;
				return status;
			}
			CachedWalletSyncStatus cachedStatus = this.cachedStatus;
			if (entropy58 == null || this.matchesCachedWallet(cachedStatus, entropy58))
				return withPeekedRecoveryMarker(cachedStatus).asStale();
			throw new ForeignBlockchainException.WalletBusyException(WALLET_BUSY_REASON);
		}
	}

	/** Base58(SHA-256(entropy)) the same way {@link ZcashFamilyWallet#getWalletIdentityHash()} does. */
	private static String expectedIdentityHash58(String entropy58) {
		try {
			byte[] entropyBytes = Base58.decode(entropy58);
			if (entropyBytes == null)
				return null;
			return Base58.encode(Crypto.digest(entropyBytes));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Decorates a cached status with the wallet's current recovery marker. The peek makes
	 * no native call (an atomic metadata-file read only), so a cached status returned while
	 * the native lane is busy still reflects a recovery record written just before.
	 */
	private static WalletSyncStatus withPeekedRecoveryMarker(CachedWalletSyncStatus cachedStatus) {
		ZcashFamilyWallet wallet = cachedStatus.wallet;
		if (wallet == null)
			return cachedStatus.status;
		ZcashFamilyWallet.RecoveryProgress peeked = wallet.peekRecoveryProgress();
		return peeked != null ? cachedStatus.status.withRecoveryMarker(peeked.name()) : cachedStatus.status;
	}

	Duration statusTimeoutFor(String entropy58) {
		if (entropy58 != null && !this.matchesWallet(this.currentWallet, entropy58))
			return ZcashFamilyNativeCoordinator.DEFAULT_TIMEOUT;
		return ZcashFamilyNativeCoordinator.STATUS_TIMEOUT;
	}

	private boolean matchesCachedWallet(CachedWalletSyncStatus cachedStatus, String entropy58) {
		return this.matchesWallet(cachedStatus.wallet, entropy58);
	}

	private boolean matchesWallet(ZcashFamilyWallet wallet, String entropy58) {
		if (wallet == null || entropy58 == null)
			return false;
		try {
			byte[] entropyBytes = Base58.decode(entropy58);
			return entropyBytes != null && entropyBytes.length == 32
					&& wallet.matchesWallet(entropyBytes, false);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static boolean isValidEntropy(String entropy58) {
		try {
			byte[] entropyBytes = Base58.decode(entropy58);
			return entropyBytes != null && entropyBytes.length == 32;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private WalletSyncStatus cacheStatus(WalletSyncStatus status) {
		this.cachedStatus = new CachedWalletSyncStatus(status, null);
		return status;
	}

	WalletSyncStatus cacheCurrentWalletStatus(WalletSyncStatus status) {
		this.cachedStatus = new CachedWalletSyncStatus(status, this.currentWallet);
		return status;
	}

	private void setLoadStatus(String status) {
		this.loadStatus = status;
		this.cacheStatus(WalletSyncStatus.loading(status));
	}

	protected boolean isLibraryLoaded() {
		return NATIVE_COORDINATOR.execute("check native wallet library", ZcashFamilyNativeAdapter::isLoaded);
	}

	private void loadValidatedNativeLibrary(Path libDirectory, String libFileName, Path authenticatedUnifiedSource)
			throws DataException {
		final PirateUnifiedWalletBundle.FileRecord trustedRecord;
		if (authenticatedUnifiedSource != null) {
			trustedRecord = PirateUnifiedWalletBundle.validateAgainstTrustedSource(libDirectory,
					authenticatedUnifiedSource, libFileName);
		} else {
			validatePinnedResourcePath(libDirectory, libFileName, false);
			trustedRecord = null;
		}

		try {
			NATIVE_COORDINATOR.execute("load native wallet library", nativeAdapter -> {
				Path library = libDirectory.resolve(libFileName);
				if (trustedRecord != null)
					PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
				nativeAdapter.loadLibrary(library);
				return null;
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			Throwable cause = e;
			while (cause != null) {
				if (cause instanceof DataException dataException)
					throw dataException;
				cause = cause.getCause();
			}
			throw e;
		}
	}

	protected <T> T executeChecked(String operationName,
			ZcashFamilyNativeCoordinator.NativeOperation<T> operation) throws ForeignBlockchainException {
		try {
			return NATIVE_COORDINATOR.execute(operationName, operation);
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			Throwable cause = e;
			while (cause != null) {
				if (cause instanceof ForeignBlockchainException foreignBlockchainException)
					throw foreignBlockchainException;
				cause = cause.getCause();
			}
			throw new ForeignBlockchainException(e.getMessage());
		}
	}
}
