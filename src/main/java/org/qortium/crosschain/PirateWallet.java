package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortium.crypto.Crypto;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Random;

public class PirateWallet extends ZcashFamilyWallet {
	private static final Logger LOGGER = LogManager.getLogger(PirateWallet.class);

	private final boolean unifiedWallet;
	private final PirateUnifiedWalletStorage unifiedStorage;

	public PirateWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		this(PirateChain.WALLET_CONFIG, entropyBytes, isNullSeedWallet, true);
	}

	PirateWallet(ZcashFamilyWalletConfig config, byte[] entropyBytes, boolean isNullSeedWallet,
			boolean initializeImmediately) throws IOException {
		super(config, entropyBytes, isNullSeedWallet, !config.isUnifiedWalletEnabled(), false);
		this.unifiedWallet = config.isUnifiedWalletEnabled();
		this.unifiedStorage = isNullSeedWallet
				? (this.unifiedWallet ? PirateUnifiedWalletStorage.transientWallet(config, this.getCurrentWalletPath()) : null)
				: PirateUnifiedWalletStorage.persistent(config, this.getEntropyHash58(), this.getCurrentWalletPath());

		if (initializeImmediately)
			this.setReady(this.initializeWallet());
	}

	@Override
	protected boolean initialize(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet) {
			boolean initialized = super.initialize(nativeAdapter);
			if (initialized && !this.isNullSeedWallet() && !this.captureLegacyIdentity(nativeAdapter))
				LOGGER.warn("Unable to record legacy Pirate wallet identity for a future Unified migration");
			return initialized;
		}

		try {
			Bitcoiny blockchain = this.config.getBlockchain();
			if (blockchain == null || blockchain.getBlockchainProvider() == null)
				return false;

			BitcoinyBlockchainProvider provider = blockchain.getBlockchainProvider();
			ChainableServer server = provider.getCurrentServer();
			if (server == null)
				return false;

			String scheme = server.getConnectionType() == ChainableServer.ConnectionType.SSL ? "https" : "http";
			String serverUri = String.format("%s://%s:%d/", scheme, server.getHostName(), server.getPort());
			Integer currentHeight = null;
			if (this.isNullSeedWallet()) {
				try {
					currentHeight = provider.getCurrentHeight();
				} catch (ForeignBlockchainException e) {
					// A transient wallet may fall back to the conservative birthday.
				}
			}
			int birthday = chooseUnifiedBirthday(this.config.getDefaultBirthday(), this.isNullSeedWallet(), currentHeight);

			return this.initializeUnified(nativeAdapter, serverUri, birthday);
		} catch (UnsatisfiedLinkError e) {
			return false;
		}
	}

	boolean initializeUnified(ZcashFamilyNativeAdapter nativeAdapter, String serverUri, int birthday) {
		String identityHash = null;
		boolean nativeRegistryExisted = false;
		boolean configureAttempted = false;
		boolean storageConfigured = false;
		try {
			nativeAdapter.initLogging();
			byte[] entropyBytes = this.getEntropyBytes();
			if (entropyBytes == null)
				throw new IOException("Unified wallet entropy is missing");

			PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
			nativeRegistryExisted = this.unifiedStorage.hasNativeRegistry();
			identityHash = snapshot.getIdentityHash();

			if (snapshot.getState() == PirateUnifiedWalletStorage.State.UNIFIED_READY && !nativeRegistryExisted)
				throw new IOException("Unified wallet registry is missing from a ready namespace");

			if (identityHash == null && this.unifiedStorage.hasLegacyWallet())
				throw new IOException("Legacy wallet identity must be captured before enabling Unified storage");

			if (!this.unifiedStorage.isTransientWallet()
					&& snapshot.getState() != PirateUnifiedWalletStorage.State.UNIFIED_READY)
				this.unifiedStorage.write(PirateUnifiedWalletStorage.State.MIGRATING,
						snapshot.isSyncValidated(), identityHash);

			Files.createDirectories(this.unifiedStorage.getStorageDirectory());
			String encryptionKey = this.getEncryptionKey();
			if (encryptionKey == null)
				throw new IOException("Unified storage encryption key is missing");
			configureAttempted = true;
			String configureResponse = nativeAdapter.configureStorage(
					this.unifiedStorage.getStorageDirectory().toString(), encryptionKey);
			if (configureResponse == null || configureResponse.isBlank())
				throw new IOException("Unified storage returned no initialization response");
			JSONObject configureJson = new JSONObject(configureResponse);
			if (!configureJson.optBoolean("initialized", false))
				throw new IOException("Unified storage was not initialized");
			if (!this.unifiedStorage.hasNativeRegistry())
				throw new IOException("Unified storage created no persistent registry");
			storageConfigured = true;

			this.enableDebugLogging(nativeAdapter);

			String entropy64 = Base64.toBase64String(entropyBytes);
			String inputSeedResponse = nativeAdapter.getSeedPhraseFromEntropyB64(entropy64);
			if (inputSeedResponse == null || inputSeedResponse.isBlank())
				throw new IOException("Unified seed derivation returned no response");
			JSONObject inputSeedJson = new JSONObject(inputSeedResponse);
			String inputSeedPhrase = inputSeedJson.optString("seedPhrase", null);
			if (inputSeedPhrase == null)
				throw new IOException("Unified seed derivation returned no seed phrase");

			String initResponse = nativeAdapter.initFromSeed(serverUri, "", inputSeedPhrase,
					Integer.toString(birthday), "", "");
			String outputSeedPhrase = parseSeedPhrase(initResponse);
			if (!Objects.equals(inputSeedPhrase, outputSeedPhrase))
				throw new IOException("Unified wallet seed identity did not match");
			this.setSeedPhrase(outputSeedPhrase);

			Integer height = this.getHeight(nativeAdapter);
			if (height == null || height <= 0 || (!this.isNullSeedWallet() && height < birthday))
				throw new IOException("Unified wallet initialized below its recovery birthday");

			String activeAddress = this.getWalletAddress(nativeAdapter);
			if (activeAddress == null)
				throw new IOException("Unified wallet returned no active address");
			// Unified export follows the active pool after Ironwood. The balance contract
			// keeps the unchanged legacy Sapling receive address first for migration identity.
			String currentIdentityHash = hashIdentity(super.getWalletAddress(nativeAdapter));
			if (identityHash != null && !Objects.equals(identityHash, currentIdentityHash))
				throw new IOException("Unified wallet address identity did not match the legacy wallet");
			if (currentIdentityHash == null)
				throw new IOException("Unified wallet returned no legacy-compatible receive address");
			identityHash = currentIdentityHash;

			if (!this.unifiedStorage.isTransientWallet()) {
				boolean reopenedAfterValidatedSync = snapshot.isSyncValidated() && nativeRegistryExisted;
				PirateUnifiedWalletStorage.State nextState = snapshot.getState() == PirateUnifiedWalletStorage.State.UNIFIED_READY
						|| reopenedAfterValidatedSync
						? PirateUnifiedWalletStorage.State.UNIFIED_READY
						: PirateUnifiedWalletStorage.State.MIGRATING;
				this.unifiedStorage.write(nextState, snapshot.isSyncValidated(), identityHash);
			}

			return true;
		} catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
			if (configureAttempted && !storageConfigured) {
				try {
					if (this.unifiedStorage.isTransientWallet())
						this.unifiedStorage.cleanupTransientStorage();
					else
						this.unifiedStorage.archiveRejectedNamespace();
				} catch (IOException archiveException) {
					// Preserve the original failed namespace if it cannot be archived safely.
				}
			}
			this.failRecoverably(identityHash);
			LOGGER.info("Unable to initialize Pirate Unified wallet: {}", e.getMessage());
			return false;
		}
	}

	boolean captureLegacyIdentity(ZcashFamilyNativeAdapter nativeAdapter) {
		if (this.unifiedStorage == null || this.isNullSeedWallet())
			return false;

		try {
			String identityHash = hashIdentity(super.getWalletAddress(nativeAdapter));
			if (identityHash == null)
				return false;

			PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
			if (snapshot.getIdentityHash() != null && !Objects.equals(snapshot.getIdentityHash(), identityHash)) {
				this.unifiedStorage.write(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE,
						false, snapshot.getIdentityHash());
				return false;
			}

			PirateUnifiedWalletStorage.State state = snapshot.getState() == PirateUnifiedWalletStorage.State.LEGACY
					? PirateUnifiedWalletStorage.State.LEGACY : snapshot.getState();
			this.unifiedStorage.write(state, snapshot.isSyncValidated(), identityHash);
			return true;
		} catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
			return false;
		}
	}

	private void enableDebugLogging(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.config.isUnifiedDebugLoggingEnabled())
			return;

		JSONObject request = new JSONObject().put("method", "set_debug_logging_enabled").put("enabled", true);
		nativeAdapter.invokeJson(request.toString(), false);
	}

	private void failRecoverably(String identityHash) {
		if (this.unifiedStorage == null || this.unifiedStorage.isTransientWallet())
			return;

		try {
			this.unifiedStorage.write(PirateUnifiedWalletStorage.State.FAILED_RECOVERABLE, false, identityHash);
		} catch (IOException e) {
			// The legacy wallet remains untouched even if recovery metadata cannot be updated.
		}
	}

	@Override
	public void recordValidatedSync(ZcashFamilyNativeAdapter nativeAdapter) throws IOException {
		if (!this.unifiedWallet || this.unifiedStorage.isTransientWallet())
			return;

		Integer height = this.getHeight(nativeAdapter);
		Integer chainTip = this.getChainTip(nativeAdapter);
		if (height == null || chainTip == null || height < chainTip - 2)
			throw new IOException("Unified wallet has not reached the validated chain tip");

		PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
		String activeAddress = this.getWalletAddress(nativeAdapter);
		String identityHash = hashIdentity(super.getWalletAddress(nativeAdapter));
		if (activeAddress == null || identityHash == null || (snapshot.getIdentityHash() != null
				&& !Objects.equals(snapshot.getIdentityHash(), identityHash))) {
			this.failRecoverably(snapshot.getIdentityHash());
			throw new IOException("Unified wallet identity changed during synchronization");
		}

		PirateUnifiedWalletStorage.State state = snapshot.getState() == PirateUnifiedWalletStorage.State.UNIFIED_READY
				? PirateUnifiedWalletStorage.State.UNIFIED_READY
				: PirateUnifiedWalletStorage.State.MIGRATING;
		this.unifiedStorage.write(state, true, identityHash);
	}

	@Override
	public boolean prepareForSwitch(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet)
			return true;
		return this.stopNativeSync(nativeAdapter);
	}

	private boolean stopNativeSync(ZcashFamilyNativeAdapter nativeAdapter) {
		try {
			if (!this.isNativeSyncInProgress(nativeAdapter))
				return true;

			JSONObject activeWallet = new JSONObject(nativeAdapter.invokeJson("{\"method\":\"get_active_wallet\"}", false));
			if (!activeWallet.optBoolean("ok", false) || activeWallet.isNull("result"))
				return false;

			String walletId = activeWallet.optString("result", null);
			if (walletId == null || walletId.isBlank())
				return false;

			JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync").put("wallet_id", walletId);
			JSONObject cancelResponse = new JSONObject(nativeAdapter.invokeJson(cancelRequest.toString(), false));
			if (!cancelResponse.optBoolean("ok", false))
				return false;

			// The Unified cancel call owns the wallet lifecycle lock and acknowledges only
			// after its native task has stopped. Its cached progress can remain incomplete.
			return true;
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			return false;
		}
	}

	@Override
	public void cleanupAfterSwitch() {
		if (this.unifiedStorage == null || !this.unifiedStorage.isTransientWallet())
			return;

		try {
			this.unifiedStorage.cleanupTransientStorage();
		} catch (IOException e) {
			LOGGER.warn("Unable to remove released Pirate transient wallet storage");
		}
	}

	@Override
	public boolean prepareForShutdown(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet || this.unifiedStorage == null || !this.unifiedStorage.isTransientWallet())
			return true;
		if (!this.stopNativeSync(nativeAdapter))
			return false;

		try {
			Path releaseDirectory = this.unifiedStorage.createReleaseDirectory();
			String encryptionKey = this.getEncryptionKey();
			if (encryptionKey == null)
				return false;
			String response = nativeAdapter.configureStorage(releaseDirectory.toString(), encryptionKey);
			if (response == null || !new JSONObject(response).optBoolean("initialized", false))
				return false;
			this.unifiedStorage.cleanupTransientStorage();
			return true;
		} catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
			return false;
		}
	}

	@Override
	public boolean usesPersistentNativeStorage() {
		return this.usesPersistentUnifiedStorage();
	}

	@Override
	public boolean isNativeSyncInProgress(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet)
			return false;

		try {
			JSONObject syncStatus = new JSONObject(nativeAdapter.execute("syncStatus", ""));
			return syncStatus.optBoolean("in_progress", false) || syncStatus.optBoolean("syncing", false);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			return true;
		}
	}

	@Override
	public boolean save() throws IOException {
		if (this.unifiedWallet)
			return this.isInitialized() && !this.isNullSeedWallet();
		return super.save();
	}

	@Override
	public String load() throws IOException {
		return this.unifiedWallet ? null : super.load();
	}

	@Override
	protected String getWalletAddress(ZcashFamilyNativeAdapter nativeAdapter) {
		return this.unifiedWallet
				? getUnifiedExportAddress(nativeAdapter.execute("export", ""))
				: super.getWalletAddress(nativeAdapter);
	}

	boolean usesPersistentUnifiedStorage() {
		return this.unifiedWallet && !this.unifiedStorage.isTransientWallet();
	}

	PirateUnifiedWalletStorage getUnifiedStorage() {
		return this.unifiedStorage;
	}

	static String getUnifiedExportAddress(String response) {
		if (response == null || response.isBlank())
			return null;

		try {
			JSONArray entries = new JSONArray(response);
			if (entries.isEmpty())
				return null;
			return entries.getJSONObject(0).optString("address", null);
		} catch (JSONException e) {
			return null;
		}
	}

	static String hashIdentity(String address) {
		if (address == null || address.isBlank())
			return null;
		return Base58.encode(Crypto.digest(address.getBytes(StandardCharsets.UTF_8)));
	}

	private static String parseSeedPhrase(String response) {
		if (response == null || response.isBlank())
			return null;
		String trimmed = response.trim();
		if (!trimmed.startsWith("{"))
			return trimmed.startsWith("Error:") ? null : trimmed;
		try {
			return new JSONObject(trimmed).optString("seed", null);
		} catch (JSONException e) {
			return null;
		}
	}

	static int chooseUnifiedBirthday(int configuredBirthday, boolean nullSeedWallet, Integer currentHeight) {
		if (nullSeedWallet && currentHeight != null && currentHeight > 0)
			return currentHeight;
		return configuredBirthday;
	}

	public PirateLightClient.Server getRandomServer() {
		PirateChain.PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();
		Collection<PirateLightClient.Server> servers = pirateChainNet.getServers();
		PirateLightClient.Server[] serversArray = servers.toArray(new PirateLightClient.Server[0]);

		return serversArray[new Random().nextInt(serversArray.length)];
	}
}
