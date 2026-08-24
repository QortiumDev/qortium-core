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
import java.util.List;
import java.util.Objects;

public abstract class ZcashFamilyWalletController<W extends ZcashFamilyWallet> extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ZcashFamilyWalletController.class);
	private static final long SAVE_INTERVAL = 60 * 60 * 1000L;
	private static final long SHUTDOWN_JOIN_MILLIS = 5_000L;
	private static final ZcashFamilyNativeCoordinator NATIVE_COORDINATOR = ZcashFamilyNativeCoordinator.getInstance();

	public enum LifecycleState {
		NEW,
		RUNNING,
		STOPPING,
		TERMINATED,
		DEGRADED
	}

	protected final ZcashFamilyWalletConfig config;
	private long lastSaveTime = 0L;
	private volatile boolean running;
	private W currentWallet = null;
	private volatile boolean shouldLoadWallet = false;
	private volatile String loadStatus = null;
	private volatile String cachedStatus = "Not initialized yet";
	private volatile LifecycleState lifecycleState = LifecycleState.NEW;

	protected ZcashFamilyWalletController(ZcashFamilyWalletConfig config) {
		this.config = config;
	}

	protected abstract W createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException;

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
				Thread.sleep(1000);

				if (!shouldLoadWallet)
					continue;

				if (!isLibraryLoaded()) {
					this.loadLibrary();

					if (!isLibraryLoaded()) {
						Thread.sleep(5 * 1000);
						continue;
					}
				}

				this.loadStatus = null;

				boolean syncAttempted = NATIVE_COORDINATOR.execute("synchronize " + this.config.getCurrencyCode() + " wallet",
						ZcashFamilyNativeCoordinator.SYNC_TIMEOUT, nativeAdapter -> {
					if (this.currentWallet == null || this.currentWallet.isNullSeedWallet())
						return false;

					this.cachedStatus = "Synchronizing wallet...";
					LOGGER.debug("Syncing {} wallet...", this.config.getDisplayName());
					String response = nativeAdapter.execute("sync", "");
					LOGGER.debug("sync response: {}", response);

					try {
						JSONObject json = new JSONObject(response);
						if (json.has("result")) {
							String result = json.getString("result");
							if (Objects.equals(result, "success"))
								this.currentWallet.setReady(true);
						}
					} catch (JSONException e) {
						LOGGER.info("Unable to interpret JSON", e);
					}
					this.cachedStatus = this.currentWallet.isReady() ? "Synchronized" : "Initializing wallet...";
					return true;
				});
				if (!syncAttempted)
					continue;

				Thread.sleep(30000);

				Long now = NTP.getTime();
				if (now != null && now - SAVE_INTERVAL >= this.lastSaveTime)
					this.saveCurrentWallet();
			}
		} catch (InterruptedException e) {
			// Fall-through to exit.
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			this.lifecycleState = LifecycleState.DEGRADED;
			this.cachedStatus = this.config.getDisplayName() + " native wallet is unavailable until Core restart";
			this.loadStatus = this.cachedStatus;
			LOGGER.error("{} wallet controller stopped because the native lane is unavailable: {}",
					this.config.getDisplayName(), e.getMessage());
		} finally {
			this.running = false;
			if (NATIVE_COORDINATOR.isDegraded()) {
				this.lifecycleState = LifecycleState.DEGRADED;
				this.cachedStatus = this.config.getDisplayName() + " native wallet is unavailable until Core restart";
			} else {
				this.saveCurrentWallet();
				this.lifecycleState = LifecycleState.TERMINATED;
				this.cachedStatus = this.config.getDisplayName() + " wallet controller is stopped";
			}
		}
	}

	public synchronized void shutdown() {
		if (this.lifecycleState == LifecycleState.TERMINATED || this.lifecycleState == LifecycleState.DEGRADED)
			return;

		if (this.lifecycleState == LifecycleState.NEW) {
			this.running = false;
			this.lifecycleState = LifecycleState.TERMINATED;
			this.cachedStatus = this.config.getDisplayName() + " wallet controller is stopped";
			return;
		}

		this.lifecycleState = LifecycleState.STOPPING;
		this.cachedStatus = "Stopping " + this.config.getDisplayName() + " wallet controller...";
		this.running = false;
		this.interrupt();

		if (Thread.currentThread() != this) {
			try {
				this.join(SHUTDOWN_JOIN_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
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
			if (libFileName == null) {
				String osName = System.getProperty("os.name");
				String osArchitecture = System.getProperty("os.arch");
				setLoadStatus(String.format("Unsupported architecture (%s %s)", osName, osArchitecture));
				return;
			}

			Path libDirectory = this.config.getRustLibOuterDirectory();
			Path libPath = Paths.get(libDirectory.toString(), libFileName);
			if (Files.exists(libPath)) {
				loadNativeLibrary(libPath);
				return;
			}

			String qdnWalletSignature = this.config.getQdnWalletSignature();
			ArbitraryTransactionData transactionData = getPinnedTransactionData(repository, qdnWalletSignature);

			List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
			if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers()) {
				setLoadStatus("Searching for peers...");
				return;
			}

			Path resourcePath;
			try {
				resourcePath = resolvePinnedQdnWalletPath(qdnWalletSignature, transactionData);
			} catch (MissingDataException e) {
				LOGGER.info("Missing data when loading configured {} wallet library", this.config.getDisplayName());
				setLoadStatus(String.format("Downloading configured %s wallet library from QDN...",
						this.config.getDisplayName()));
				return;
			}

			try {
				validatePinnedResourcePath(resourcePath, libFileName);
			} catch (DataException e) {
				LOGGER.error("Invalid configured {} wallet library: {}", this.config.getDisplayName(), e.getMessage());
				setLoadStatus(String.format("Configured %s wallet library could not be read from QDN",
						this.config.getDisplayName()));
				return;
			}

			Files.createDirectories(libDirectory);
			FileUtils.copyDirectory(resourcePath.toFile(), libDirectory.toFile());

			loadNativeLibrary(libPath);
		} catch (DataException e) {
			LOGGER.error("Repository issue when loading {} wallet library", this.config.getDisplayName(), e);
		} catch (IOException e) {
			LOGGER.error("Error when loading {} wallet library", this.config.getDisplayName(), e);
		}
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
		if (transactionData == null)
			throw new DataException("Configured wallet QDN transaction is missing");
		if (transactionData.getService() != Service.ARBITRARY_DATA)
			throw new DataException("Configured wallet QDN transaction is not an ARBITRARY_DATA publication");

		ArbitraryDataReader arbitraryDataReader = new ArbitraryDataReader(signature58,
				ArbitraryDataFile.ResourceIdType.TRANSACTION_DATA, transactionData.getService(), transactionData.getIdentifier());
		arbitraryDataReader.setTransactionData(transactionData);
		arbitraryDataReader.loadSynchronously(false);
		return arbitraryDataReader.getFilePath();
	}

	static void validatePinnedResourcePath(Path resourcePath, String libFileName) throws DataException {
		if (resourcePath == null || !Files.isDirectory(resourcePath))
			throw new DataException("wallet library resource is not a directory");
		if (libFileName == null || !Files.isRegularFile(resourcePath.resolve(libFileName)))
			throw new DataException("wallet library resource is missing the expected platform library");
	}

	public static String resolveRustLibFilename() {
		return resolveRustLibFilename(System.getProperty("os.name"), System.getProperty("os.arch"));
	}

	static String resolveRustLibFilename(String osName, String osArchitecture) {
		if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64"))
			return "librust-macos-x86_64.dylib";
		else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("aarch64"))
			return "librust-linux-aarch64.so";
		else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("amd64"))
			return "librust-linux-x86_64.so";
		else if (osName.contains("Windows") && osArchitecture.equals("amd64"))
			return "librust-windows-x86_64.dll";

		return null;
	}

	private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet,
			ZcashFamilyNativeAdapter nativeAdapter) {
		if (!nativeAdapter.isLoaded()) {
			shouldLoadWallet = true;
			return false;
		}

		byte[] entropyBytes = Base58.decode(entropy58);
		if (entropyBytes == null || entropyBytes.length != 32) {
			LOGGER.info("Invalid entropy bytes");
			return false;
		}

		if (this.currentWallet != null) {
			if (this.currentWallet.entropyBytesEqual(entropyBytes))
				return true;

			this.closeCurrentWallet();
		}

		try {
			this.currentWallet = this.createWallet(entropyBytes, isNullSeedWallet);
			if (!this.currentWallet.isReady())
				this.currentWallet = null;
			return true;
		} catch (IOException e) {
			LOGGER.info("Unable to initialize wallet: {}", e.getMessage());
		}

		return false;
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

	private void closeCurrentWallet() {
		this.saveCurrentWallet();
		this.currentWallet = null;
	}

	public String getSyncStatus() {
		return this.getBoundedSyncStatus(null);
	}

	public String getSyncStatus(String entropy58) {
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
		return executeChecked("execute " + this.config.getCurrencyCode() + " wallet operation", nativeAdapter -> {
			if (!acceptsWalletOperations(this.lifecycleState))
				throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet controller isn't running");
			if (!this.initWithEntropy58(entropy58, isNullSeedWallet, nativeAdapter))
				throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet isn't initialized yet");
			ensureInitialized(nativeAdapter);
			if (requireSynchronized)
				ensureSynchronized(nativeAdapter);
			if (requireNotNullSeed)
				ensureNotNullSeedInternal();
			return operation.execute(this.currentWallet, nativeAdapter);
		});
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
		if (json.has("syncing") && Boolean.parseBoolean(json.getString("syncing"))) {
			long syncedBlocks = json.getLong("synced_blocks");
			long totalBlocks = json.getLong("total_blocks");
			throw new ForeignBlockchainException(String.format("Sync in progress (%d / %d). Please try again later.",
					syncedBlocks, totalBlocks));
		}
	}

	private String getSyncStatus(ZcashFamilyNativeAdapter nativeAdapter) {
		if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
			if (this.loadStatus != null)
				return cacheStatus(this.loadStatus);

			return cacheStatus("Not initialized yet");
		}

		String syncStatusResponse = nativeAdapter.execute("syncStatus", "");
		JSONObject json = new JSONObject(syncStatusResponse);
		if (json.has("syncing") && Boolean.parseBoolean(json.getString("syncing"))) {
			long syncedBlocks = json.getLong("synced_blocks");
			long totalBlocks = json.getLong("total_blocks");
			return cacheStatus(String.format("Sync in progress (%d / %d)", syncedBlocks, totalBlocks));
		}

		return cacheStatus(this.currentWallet.isSynchronized() ? "Synchronized" : "Initializing wallet...");
	}

	private String getBoundedSyncStatus(String entropy58) {
		if (this.requiresCoreRestart())
			return cacheStatus(this.config.getDisplayName() + " native wallet is unavailable until Core restart");

		if (this.lifecycleState == LifecycleState.NEW)
			return cacheStatus(this.config.getDisplayName() + " wallet controller has not started");
		if (this.lifecycleState == LifecycleState.STOPPING)
			return this.cachedStatus;
		if (this.lifecycleState == LifecycleState.TERMINATED)
			return cacheStatus(this.config.getDisplayName() + " wallet controller is stopped");

		if (NATIVE_COORDINATOR.isBusy())
			return this.cachedStatus;

		try {
			return NATIVE_COORDINATOR.execute("get wallet synchronization status",
					ZcashFamilyNativeCoordinator.STATUS_TIMEOUT, nativeAdapter -> {
				if (entropy58 != null)
					this.initWithEntropy58(entropy58, false, nativeAdapter);
				return this.getSyncStatus(nativeAdapter);
			});
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			if (NATIVE_COORDINATOR.isDegraded()) {
				this.lifecycleState = LifecycleState.DEGRADED;
				return cacheStatus(this.config.getDisplayName() + " native wallet is unavailable until Core restart");
			}
			return this.cachedStatus;
		}
	}

	private String cacheStatus(String status) {
		this.cachedStatus = status;
		return status;
	}

	private void setLoadStatus(String status) {
		this.loadStatus = status;
		this.cachedStatus = status;
	}

	private boolean isLibraryLoaded() {
		return NATIVE_COORDINATOR.execute("check native wallet library", ZcashFamilyNativeAdapter::isLoaded);
	}

	private void loadNativeLibrary(Path libPath) {
		NATIVE_COORDINATOR.execute("load native wallet library", nativeAdapter -> {
			nativeAdapter.loadLibrary(libPath);
			return null;
		});
	}

	private <T> T executeChecked(String operationName,
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
