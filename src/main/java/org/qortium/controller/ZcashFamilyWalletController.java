package org.qortium.controller;

import com.rust.litewalletjni.LiteWalletJni;
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

	protected final ZcashFamilyWalletConfig config;
	private long lastSaveTime = 0L;
	private boolean running;
	private W currentWallet = null;
	private boolean shouldLoadWallet = false;
	private String loadStatus = null;

	protected ZcashFamilyWalletController(ZcashFamilyWalletConfig config) {
		this.config = config;
		this.running = Settings.getInstance().isWalletEnabled(config.getCurrencyCode());
	}

	protected abstract W createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException;

	@Override
	public void run() {
		Thread.currentThread().setName(this.config.getDisplayName() + " Wallet Controller");
		Thread.currentThread().setPriority(MIN_PRIORITY);

		try {
			while (running && !Controller.isStopping()) {
				Thread.sleep(1000);

				if (!shouldLoadWallet)
					continue;

				if (!LiteWalletJni.isLoaded()) {
					this.loadLibrary();

					if (!LiteWalletJni.isLoaded()) {
						Thread.sleep(5 * 1000);
						continue;
					}
				}

				this.loadStatus = null;

				if (this.currentWallet == null || this.currentWallet.isNullSeedWallet())
					continue;

				LOGGER.debug("Syncing {} wallet...", this.config.getDisplayName());
				String response = LiteWalletJni.execute("sync", "");
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

				Thread.sleep(30000);

				Long now = NTP.getTime();
				if (now != null && now - SAVE_INTERVAL >= this.lastSaveTime)
					this.saveCurrentWallet();
			}
		} catch (InterruptedException e) {
			// Fall-through to exit.
		}
	}

	public void shutdown() {
		try {
			this.saveCurrentWallet();
			this.running = false;
			this.interrupt();
		} catch (Exception e) {
			// Best-effort shutdown.
		}
	}

	private void loadLibrary() throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String libFileName = resolveRustLibFilename();
			if (libFileName == null) {
				String osName = System.getProperty("os.name");
				String osArchitecture = System.getProperty("os.arch");
				this.loadStatus = String.format("Unsupported architecture (%s %s)", osName, osArchitecture);
				return;
			}

			Path libDirectory = this.config.getRustLibOuterDirectory();
			Path libPath = Paths.get(libDirectory.toString(), libFileName);
			if (Files.exists(libPath)) {
				LiteWalletJni.loadLibrary(libPath);
				return;
			}

			String qdnWalletSignature = this.config.getQdnWalletSignature();
			ArbitraryTransactionData transactionData = getPinnedTransactionData(repository, qdnWalletSignature);

			List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();
			if (handshakedPeers.size() < Settings.getInstance().getMinBlockchainPeers()) {
				this.loadStatus = "Searching for peers...";
				return;
			}

			Path resourcePath;
			try {
				resourcePath = resolvePinnedQdnWalletPath(qdnWalletSignature, transactionData);
			} catch (MissingDataException e) {
				LOGGER.info("Missing data when loading configured {} wallet library", this.config.getDisplayName());
				this.loadStatus = String.format("Downloading configured %s wallet library from QDN...",
						this.config.getDisplayName());
				return;
			}

			try {
				validatePinnedResourcePath(resourcePath, libFileName);
			} catch (DataException e) {
				LOGGER.error("Invalid configured {} wallet library: {}", this.config.getDisplayName(), e.getMessage());
				this.loadStatus = String.format("Configured %s wallet library could not be read from QDN",
						this.config.getDisplayName());
				return;
			}

			Files.createDirectories(libDirectory);
			FileUtils.copyDirectory(resourcePath.toFile(), libDirectory.toFile());

			LiteWalletJni.loadLibrary(libPath);
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

	public boolean initWithEntropy58(String entropy58) {
		return this.initWithEntropy58(entropy58, false);
	}

	public boolean initNullSeedWallet() {
		return this.initWithEntropy58(Base58.encode(new byte[32]), true);
	}

	private boolean initWithEntropy58(String entropy58, boolean isNullSeedWallet) {
		if (!LiteWalletJni.isLoaded()) {
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
		if (this.currentWallet == null)
			return;

		try {
			if (this.currentWallet.save()) {
				Long now = NTP.getTime();
				if (now != null)
					this.lastSaveTime = now;
			}
		} catch (IOException e) {
			LOGGER.info("Unable to save wallet");
		}
	}

	public W getCurrentWallet() {
		return this.currentWallet;
	}

	private void closeCurrentWallet() {
		this.saveCurrentWallet();
		this.currentWallet = null;
	}

	public void ensureInitialized() throws ForeignBlockchainException {
		if (!LiteWalletJni.isLoaded() || this.currentWallet == null || !this.currentWallet.isInitialized())
			throw new ForeignBlockchainException(this.config.getDisplayName() + " wallet isn't initialized yet");
	}

	public void ensureNotNullSeed() throws ForeignBlockchainException {
		if (this.currentWallet == null || this.currentWallet.isNullSeedWallet())
			throw new ForeignBlockchainException("Invalid wallet");
	}

	public void ensureSynchronized() throws ForeignBlockchainException {
		if (this.currentWallet == null || !this.currentWallet.isSynchronized())
			throw new ForeignBlockchainException("Wallet isn't synchronized yet");

		String response = LiteWalletJni.execute("syncStatus", "");
		JSONObject json = new JSONObject(response);
		if (json.has("syncing")) {
			boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
			if (isSyncing) {
				long syncedBlocks = json.getLong("synced_blocks");
				long totalBlocks = json.getLong("total_blocks");
				throw new ForeignBlockchainException(String.format("Sync in progress (%d / %d). Please try again later.",
						syncedBlocks, totalBlocks));
			}
		}
	}

	public String getSyncStatus() {
		if (this.currentWallet == null || !this.currentWallet.isInitialized()) {
			if (this.loadStatus != null)
				return this.loadStatus;

			return "Not initialized yet";
		}

		String syncStatusResponse = LiteWalletJni.execute("syncStatus", "");
		JSONObject json = new JSONObject(syncStatusResponse);
		if (json.has("syncing")) {
			boolean isSyncing = Boolean.valueOf(json.getString("syncing"));
			if (isSyncing) {
				long syncedBlocks = json.getLong("synced_blocks");
				long totalBlocks = json.getLong("total_blocks");
				return String.format("Sync in progress (%d / %d)", syncedBlocks, totalBlocks);
			}
		}

		return this.currentWallet.isSynchronized() ? "Synchronized" : "Initializing wallet...";
	}
}
