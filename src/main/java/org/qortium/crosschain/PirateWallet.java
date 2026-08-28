package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;
import org.qortium.crypto.Crypto;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class PirateWallet extends ZcashFamilyWallet {
	private static final Logger LOGGER = LogManager.getLogger(PirateWallet.class);
	private static final String UNIFIED_NATIVE_CHAIN = "main";

	enum EndpointSelectionOutcome {
		APPLIED,
		RETRYABLE_FAILURE,
		ENDPOINT_REJECTED
	}

	private record NativeServerProbe(EndpointSelectionOutcome outcome, Long height) {
		private static NativeServerProbe applied(long height) {
			return new NativeServerProbe(EndpointSelectionOutcome.APPLIED, height);
		}

		private static NativeServerProbe retryableFailure() {
			return new NativeServerProbe(EndpointSelectionOutcome.RETRYABLE_FAILURE, null);
		}

		private static NativeServerProbe endpointRejected() {
			return new NativeServerProbe(EndpointSelectionOutcome.ENDPOINT_REJECTED, null);
		}
	}

	private final boolean unifiedWallet;
	private final PirateUnifiedWalletStorage unifiedStorage;
	private ZcashFamilyLightClient.ValidatedServerSelection appliedServerSelection;
	private volatile boolean freshSynchronizationRequired;
	private long synchronizationAcceptedGeneration = -1L;
	private String synchronizationAcceptedWalletId;
	private volatile ZcashFamilyWallet.RecoveryProgress lastObservedRecoveryProgress =
			ZcashFamilyWallet.RecoveryProgress.NONE;
	private volatile boolean recoveryCompletedInLifetime;
	/**
	 * How long the native side must continuously report idle, while the replay gate is
	 * still set, before the driver reissues its rescan. Measured from the first idle
	 * observation rather than from the issue, so a replay that runs for hours is never
	 * disturbed and only a genuinely stalled one is retried.
	 */
	static final long RECOVERY_IDLE_GRACE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
	private boolean recoveryRescanIssued;
	/** Nanotime of the first idle observation since the last issue; 0 when not idle. */
	private long recoveryIdleSinceNanos;
	/** Test seam: overrides the idle grace; null means the production constant. */
	private volatile Long recoveryIdleGraceOverrideNanos;

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
			if (!(provider instanceof ZcashFamilyLightClient lightClient))
				return false;
			return lightClient.withValidatedServerSelectionLease(() -> {
				if (!this.selectPersistedServer(lightClient))
					return false;
				ChainableServer server = provider.getCurrentServer();
				ZcashFamilyLightClient.ValidatedServerSelection selection = lightClient.getValidatedServerSelection();
				if (server == null || selection == null || !server.equals(selection.getServer()))
					return false;

				String serverUri = selection.getEndpointUri();
				Integer currentHeight = null;
				if (this.isNullSeedWallet()) {
					try {
						currentHeight = provider.getCurrentHeight();
					} catch (ForeignBlockchainException e) {
						// A transient wallet may fall back to the conservative birthday.
					}
				}
				int birthday = chooseUnifiedBirthday(
						this.config.getDefaultBirthday(), this.isNullSeedWallet(), currentHeight);

				return this.initializeUnified(nativeAdapter, serverUri, birthday);
			});
		} catch (Exception | UnsatisfiedLinkError e) {
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
	public boolean prepareForSynchronization(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet)
			return true;

		Bitcoiny blockchain = this.config.getBlockchain();
		if (blockchain == null || !(blockchain.getBlockchainProvider() instanceof ZcashFamilyLightClient lightClient))
			return false;
		return this.prepareForSynchronization(nativeAdapter, lightClient);
	}

	boolean prepareForSynchronization(ZcashFamilyNativeAdapter nativeAdapter, ZcashFamilyLightClient lightClient) {
		int remainingCandidates = Math.max(1, lightClient.getServers().size() + 1);
		Set<ChainableServer> rejectedServers = new HashSet<>();
		while (remainingCandidates-- > 0) {
			ZcashFamilyLightClient.ValidatedServerSelection selection = lightClient.getValidatedServerSelection();
			if (selection == null) {
				try {
					selection = lightClient.selectAnyValidatedServer();
				} catch (ForeignBlockchainException e) {
					return false;
				}
				if (selection == null)
					return false;
			}
			EndpointSelectionOutcome outcome = this.applyValidatedServerSelection(nativeAdapter, selection);
			if (outcome == EndpointSelectionOutcome.APPLIED)
				return true;
			if (outcome == EndpointSelectionOutcome.RETRYABLE_FAILURE) {
				LOGGER.debug("Retaining Pirate endpoint {} after retryable native reconciliation failure",
						selection.getEndpointUri());
				return false;
			}
			rejectedServers.add(selection.getServer());

			try {
				if (lightClient.selectAnotherAfterNativeFailure(selection, rejectedServers,
						this.getClass().getSimpleName(), "Native Pirate-service validation failed") == null)
					return false;
			} catch (ForeignBlockchainException e) {
				return false;
			}
		}

		return false;
	}

	@Override
	public <T> T withValidatedServerSelectionLease(ZcashFamilyNativeAdapter nativeAdapter,
			ZcashFamilyNativeCoordinator.NativeOperation<T> operation) throws Exception {
		if (!this.unifiedWallet)
			return super.withValidatedServerSelectionLease(nativeAdapter, operation);

		Bitcoiny blockchain = this.config.getBlockchain();
		if (blockchain == null || !(blockchain.getBlockchainProvider() instanceof ZcashFamilyLightClient lightClient))
			throw new ForeignBlockchainException("Pirate Chain lightwalletd endpoint is unavailable");

		return lightClient.withValidatedServerSelectionLease(() -> operation.execute(nativeAdapter));
	}

	EndpointSelectionOutcome applyValidatedServerSelection(ZcashFamilyNativeAdapter nativeAdapter,
			ZcashFamilyLightClient.ValidatedServerSelection selection) {
		if (!this.unifiedWallet || selection == null)
			return !this.unifiedWallet ? EndpointSelectionOutcome.APPLIED
					: EndpointSelectionOutcome.RETRYABLE_FAILURE;

		if (this.appliedServerSelection != null
				&& this.appliedServerSelection.getGeneration() == selection.getGeneration()
				&& Objects.equals(this.appliedServerSelection.getEndpointUri(), selection.getEndpointUri())) {
			this.observeFreshSynchronization(nativeAdapter);
			return EndpointSelectionOutcome.APPLIED;
		}

		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (walletId == null)
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;
			NativeServerProbe probe = this.probeNativeServer(nativeAdapter, selection, true);
			if (probe.outcome() != EndpointSelectionOutcome.APPLIED)
				return probe.outcome();
			if (!this.isSelectionCurrent(selection) || !this.cancelNativeSync(nativeAdapter, walletId)
					|| !this.isSelectionCurrent(selection))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;

			JSONObject setRequest = new JSONObject().put("method", "set_lightd_endpoint")
					.put("wallet_id", walletId)
					.put("url", selection.getEndpointUri())
					.put("tls_pin_opt", JSONObject.NULL);
			JSONObject setResponse = new JSONObject(nativeAdapter.invokeJson(setRequest.toString(), false));
			if (!isAcknowledged(setResponse))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;

			JSONObject getRequest = new JSONObject().put("method", "get_lightd_endpoint")
					.put("wallet_id", walletId);
			JSONObject getResponse = new JSONObject(nativeAdapter.invokeJson(getRequest.toString(), false));
			String appliedUri = getResponse.optBoolean("ok", false) && !getResponse.isNull("result")
					? getResponse.optString("result", null) : null;
			if (!Objects.equals(normalizeServerUri(selection.getEndpointUri()), normalizeServerUri(appliedUri)))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;

			JSONObject consensusRequest = new JSONObject().put("method", "validate_consensus_branch")
					.put("wallet_id", walletId);
			JSONObject consensusResponse = new JSONObject(
					nativeAdapter.invokeJson(consensusRequest.toString(), false));
			JSONObject consensusResult = consensusResponse.optBoolean("ok", false)
					? consensusResponse.optJSONObject("result") : null;
			if (consensusResult == null || !consensusResult.has("is_valid"))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;
			if (!consensusResult.optBoolean("is_valid", false))
				return EndpointSelectionOutcome.ENDPOINT_REJECTED;
			if (!Objects.equals(walletId, this.getActiveWalletId(nativeAdapter))
					|| !this.isSelectionCurrent(selection))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;

			if (!this.persistSelectedServer(selection.getEndpointUri()))
				return EndpointSelectionOutcome.RETRYABLE_FAILURE;
			this.appliedServerSelection = selection;
			this.freshSynchronizationRequired = true;
			this.synchronizationAcceptedGeneration = -1L;
			this.synchronizationAcceptedWalletId = null;
			return EndpointSelectionOutcome.APPLIED;
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			return EndpointSelectionOutcome.RETRYABLE_FAILURE;
		}
	}

	private NativeServerProbe probeNativeServer(ZcashFamilyNativeAdapter nativeAdapter,
			ZcashFamilyLightClient.ValidatedServerSelection selection, boolean requireJavaHeightAgreement) {
		JSONObject request = new JSONObject().put("method", "test_node")
				.put("url", selection.getEndpointUri())
				.put("tls_pin", JSONObject.NULL);
		JSONObject response = new JSONObject(nativeAdapter.invokeJson(request.toString(), false));
		if (!response.optBoolean("ok", false) || response.isNull("result"))
			return NativeServerProbe.retryableFailure();
		JSONObject result = response.optJSONObject("result");
		if (result == null || !result.optBoolean("success", false))
			return NativeServerProbe.retryableFailure();
		if (result.isNull("latest_block_height"))
			return NativeServerProbe.endpointRejected();

		long nativeHeight = result.optLong("latest_block_height", -1L);
		if (nativeHeight <= 0 || (requireJavaHeightAgreement && Math.abs(nativeHeight - selection.getHeight())
				> ZcashFamilyLightClient.SERVER_HEIGHT_AGREEMENT_TOLERANCE))
			return NativeServerProbe.endpointRejected();
		if (!ZcashFamilyLightClient.matchesExpectedChainName(UNIFIED_NATIVE_CHAIN,
				result.optString("chain_name", null)))
			return NativeServerProbe.endpointRejected();

		boolean expectedTls = selection.getEndpointUri().startsWith("https://");
		return result.has("tls_enabled") && result.optBoolean("tls_enabled") == expectedTls
				? NativeServerProbe.applied(nativeHeight) : NativeServerProbe.endpointRejected();
	}

	private boolean isSelectionCurrent(ZcashFamilyLightClient.ValidatedServerSelection selection) {
		Bitcoiny blockchain = this.config.getBlockchain();
		if (blockchain == null)
			return true;
		if (!(blockchain.getBlockchainProvider() instanceof ZcashFamilyLightClient lightClient))
			return false;
		ZcashFamilyLightClient.ValidatedServerSelection current = lightClient.getValidatedServerSelection();
		return current != null && current.getGeneration() == selection.getGeneration()
				&& Objects.equals(current.getEndpointUri(), selection.getEndpointUri());
	}

	private boolean cancelNativeSync(ZcashFamilyNativeAdapter nativeAdapter, String walletId) {
		JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync").put("wallet_id", walletId);
		JSONObject cancelResponse = new JSONObject(nativeAdapter.invokeJson(cancelRequest.toString(), false));
		return isAcknowledged(cancelResponse);
	}

	private boolean selectPersistedServer(ZcashFamilyLightClient lightClient) {
		if (this.unifiedStorage == null || this.unifiedStorage.isTransientWallet())
			return true;

		String selectedServerUri = this.unifiedStorage.read().getSelectedServerUri();
		if (selectedServerUri == null)
			return true;

		try {
			URI uri = new URI(selectedServerUri);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			int port = uri.getPort();
			String path = uri.getPath();
			if (host == null || port <= 0 || uri.getUserInfo() != null || uri.getQuery() != null
					|| uri.getFragment() != null || !(path == null || path.isEmpty() || "/".equals(path))
					|| !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
				return false;

			ChainableServer.ConnectionType connectionType = "https".equalsIgnoreCase(scheme)
					? ChainableServer.ConnectionType.SSL : ChainableServer.ConnectionType.TCP;
			ChainableServer storedServer = lightClient.getServer(host, connectionType, port);
			ChainableServer selectedServer = lightClient.getServers().stream()
					.filter(storedServer::equals)
					.findFirst()
					.orElse(null);
			if (selectedServer == null)
				return true;
			if (!selectedServer.equals(lightClient.getCurrentServer()))
				lightClient.setCurrentServer(selectedServer, this.getClass().getSimpleName());
			return lightClient.getValidatedServerSelection() != null;
		} catch (ForeignBlockchainException | URISyntaxException | RuntimeException e) {
			return false;
		}
	}

	private boolean persistSelectedServer(String serverUri) {
		if (this.unifiedStorage == null || this.unifiedStorage.isTransientWallet())
			return true;
		try {
			PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
			if (snapshot.isCorrupt())
				return false;
			this.unifiedStorage.write(snapshot.getState(), snapshot.isSyncValidated(), snapshot.getIdentityHash(),
					normalizeServerUri(serverUri));
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static String getResultString(JSONObject response) {
		return response.optBoolean("ok", false) && !response.isNull("result")
				? response.optString("result", null) : null;
	}

	private String getActiveWalletId(ZcashFamilyNativeAdapter nativeAdapter) {
		JSONObject response = new JSONObject(nativeAdapter.invokeJson("{\"method\":\"get_active_wallet\"}", false));
		String walletId = getResultString(response);
		return walletId == null || walletId.isBlank() ? null : walletId;
	}

	private static boolean isAcknowledged(JSONObject response) {
		JSONObject result = response.optJSONObject("result");
		return response.optBoolean("ok", false) && result != null && result.optBoolean("acknowledged", false);
	}

	/**
	 * Reads this wallet's balances.
	 * <p>
	 * Unified wallets use the typed `get_balance` request rather than the legacy
	 * `balance` command. The legacy command additionally builds a per-address
	 * breakdown that Core discards, and building it walks the wallet's own key
	 * looking for every address holding a balance. An address belonging to another
	 * key group - which is exactly what a verified spending-key import creates -
	 * cannot be found that way, so that walk runs to its 4096-address limit on
	 * every call and exceeds the native lane's timeout after a recovery. The typed
	 * request returns precisely the two amounts Core consumes.
	 */
	PirateChainBalance getWalletBalances(ZcashFamilyNativeAdapter nativeAdapter)
			throws ForeignBlockchainException {
		if (!this.unifiedWallet)
			return PirateChain.parseWalletBalances(nativeAdapter.execute("balance", ""));

		final String walletId;
		try {
			walletId = this.getActiveWalletId(nativeAdapter);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			throw new ForeignBlockchainException("Unable to determine balance");
		}
		if (walletId == null)
			throw new ForeignBlockchainException("Unable to determine balance");

		final String responseText;
		try {
			responseText = nativeAdapter.invokeJson(buildBalancePayload(walletId).toString(), false);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			throw new ForeignBlockchainException("Unable to determine balance");
		}
		return parseTypedBalance(responseText);
	}

	static JSONObject buildBalancePayload(String walletId) {
		return new JSONObject().put("method", "get_balance").put("wallet_id", walletId);
	}

	static PirateChainBalance parseTypedBalance(String responseText) throws ForeignBlockchainException {
		final JSONObject response;
		try {
			response = new JSONObject(responseText == null ? "" : responseText);
		} catch (JSONException e) {
			throw new ForeignBlockchainException("Unable to determine balance");
		}

		if (!(response.opt("ok") instanceof Boolean ok) || !ok)
			throw new ForeignBlockchainException("Unable to determine balance");
		JSONObject result = response.optJSONObject("result");
		if (result == null)
			throw new ForeignBlockchainException("Unable to determine balance");

		return new PirateChainBalance(requireAmount(result, "total"), requireAmount(result, "spendable"));
	}

	/**
	 * Unified amounts serialize as decimal strings so large values survive JSON
	 * consumers with limited integer precision; plain integers are accepted too,
	 * exactly as the upstream decoder accepts either form. Anything else, and any
	 * negative or non-integral value, fails closed rather than being coerced.
	 */
	private static long requireAmount(JSONObject result, String key) throws ForeignBlockchainException {
		Object value = result.opt(key);
		final long amount;
		if (value instanceof String text) {
			try {
				amount = Long.parseLong(text.trim());
			} catch (NumberFormatException e) {
				throw new ForeignBlockchainException("Unable to determine balance");
			}
		} else if (value instanceof Integer || value instanceof Long) {
			amount = ((Number) value).longValue();
		} else if (value instanceof java.math.BigInteger bigInteger) {
			try {
				amount = bigInteger.longValueExact();
			} catch (ArithmeticException e) {
				throw new ForeignBlockchainException("Unable to determine balance");
			}
		} else {
			throw new ForeignBlockchainException("Unable to determine balance");
		}

		if (amount < 0)
			throw new ForeignBlockchainException("Unable to determine balance");
		return amount;
	}

	/**
	 * Imports one externally derived spending key through the upstream verified request.
	 * <p>
	 * The native response is authoritative for every ownership, network, and birthday rule.
	 * The spending key exists only in the request payload; it is never logged, and error
	 * messages are redacted defensively before they can reach a caller.
	 */
	PirateChainVerifiedRecoveryResult importVerifiedSpendingKey(ZcashFamilyNativeAdapter nativeAdapter,
			PirateChainVerifiedRecoveryRequest recoveryRequest) throws ForeignBlockchainException {
		if (!this.unifiedWallet)
			throw new ForeignBlockchainException("Verified recovery requires the Unified Pirate wallet");

		final String walletId;
		try {
			walletId = this.getActiveWalletId(nativeAdapter);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			throw new ForeignBlockchainException("Unified wallet identity is unavailable");
		}
		if (walletId == null)
			throw new ForeignBlockchainException("Unified wallet identity is unavailable");

		JSONObject importRequest = buildVerifiedImportPayload(walletId, recoveryRequest);
		final String responseText;
		try {
			responseText = nativeAdapter.invokeJson(importRequest.toString(), false);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			throw new ForeignBlockchainException("Verified key import failed");
		}
		PirateChainVerifiedRecoveryResult result =
				parseVerifiedImportResult(responseText, recoveryRequest.spendingKey);
		// The durable driver record must reflect the native verdict before the caller is
		// answered, so a restarted Core knows to drive (or stop driving) the replay.
		this.reconcileRecoveryRecord(result.requiredRescanFromHeight);
		return result;
	}

	static JSONObject buildVerifiedImportPayload(String walletId, PirateChainVerifiedRecoveryRequest recoveryRequest) {
		JSONObject payload = new JSONObject()
				.put("method", "import_spending_key_verified")
				.put("wallet_id", walletId)
				.put("pool", recoveryRequest.pool)
				.put("spending_key", recoveryRequest.spendingKey)
				.put("expected_address", recoveryRequest.expectedAddress)
				.put("address_index", recoveryRequest.addressIndex.intValue())
				.put("birthday_height", recoveryRequest.birthdayHeight.intValue());
		if (recoveryRequest.label != null)
			payload.put("label", recoveryRequest.label);
		return payload;
	}

	static PirateChainVerifiedRecoveryResult parseVerifiedImportResult(String responseText, String spendingKey)
			throws ForeignBlockchainException {
		final JSONObject response;
		try {
			response = new JSONObject(responseText == null ? "" : responseText);
		} catch (JSONException e) {
			throw new ForeignBlockchainException("Verified key import failed");
		}

		// The envelope's ok flag must be an actual JSON boolean; coerced strings fail closed.
		if (!(response.opt("ok") instanceof Boolean ok) || !ok)
			throw new ForeignBlockchainException(
					sanitizeVerifiedImportError(response.optString("error", null), spendingKey));

		JSONObject result = response.optJSONObject("result");
		if (result == null || !result.has("required_rescan_from_height"))
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");

		Long requiredRescanFromHeight = result.isNull("required_rescan_from_height")
				? null : requireIntegral(result, "required_rescan_from_height", 0L, 0xFFFFFFFFL);
		return new PirateChainVerifiedRecoveryResult(
				requireIntegral(result, "key_id", Long.MIN_VALUE, Long.MAX_VALUE),
				requireString(result, "pool"),
				requireString(result, "address"),
				(int) requireIntegral(result, "address_index", 0L, 4096L),
				// The model stores this as a signed int, so the accepted range must stop at
				// Integer.MAX_VALUE or a u32-range height would wrap negative through the cast.
				(int) requireIntegral(result, "birthday_height", 1L, Integer.MAX_VALUE),
				requireBoolean(result, "already_imported"),
				requireBoolean(result, "rescan_required"),
				requiredRescanFromHeight);
	}

	/** Exact JSON type checks: org.json's coercing getters would accept strings and decimals. */
	private static long requireIntegral(JSONObject result, String key, long min, long max)
			throws ForeignBlockchainException {
		Object value = result.opt(key);
		if (!(value instanceof Integer) && !(value instanceof Long) && !(value instanceof java.math.BigInteger))
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");
		long longValue;
		try {
			longValue = value instanceof java.math.BigInteger bigInteger
					? bigInteger.longValueExact() : ((Number) value).longValue();
		} catch (ArithmeticException e) {
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");
		}
		if (longValue < min || longValue > max)
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");
		return longValue;
	}

	private static boolean requireBoolean(JSONObject result, String key) throws ForeignBlockchainException {
		Object value = result.opt(key);
		if (!(value instanceof Boolean booleanValue))
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");
		return booleanValue;
	}

	private static String requireString(JSONObject result, String key) throws ForeignBlockchainException {
		Object value = result.opt(key);
		if (!(value instanceof String stringValue) || stringValue.isBlank())
			throw new ForeignBlockchainException("Verified key import returned an incomplete result");
		return stringValue;
	}

	static String sanitizeVerifiedImportError(String error, String spendingKey) {
		if (error == null || error.isBlank())
			return "Verified key import failed";
		if (spendingKey != null && !spendingKey.isBlank()
				&& error.toLowerCase(java.util.Locale.ROOT).contains(spendingKey.toLowerCase(java.util.Locale.ROOT)))
			return "Verified key import failed";
		return error;
	}

	/**
	 * Persists or clears the durable recovery driver record so it always reflects the
	 * native import verdict. Called after a successful verified import; failing to
	 * persist fails the whole call because the exact import retry is idempotent.
	 */
	private void reconcileRecoveryRecord(Long requiredRescanFromHeight) throws ForeignBlockchainException {
		if (!this.usesPersistentUnifiedStorage())
			return;

		PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
		if (Objects.equals(snapshot.getRecoveryRescanFromHeight(), requiredRescanFromHeight))
			return;

		try {
			this.unifiedStorage.write(snapshot.getState(), snapshot.isSyncValidated(), snapshot.getIdentityHash(),
					snapshot.getSelectedServerUri(), requiredRescanFromHeight);
		} catch (IOException e) {
			throw new ForeignBlockchainException("The verified import was applied natively but its recovery "
					+ "record could not be stored; retry the exact same request");
		}
		if (requiredRescanFromHeight != null) {
			this.lastObservedRecoveryProgress = ZcashFamilyWallet.RecoveryProgress.PENDING;
			// A newly owed replay starts its own issue/idle cycle: inheriting the previous
			// recovery's state would make this one wait a grace period before its first
			// rescan.
			this.recoveryRescanIssued = false;
			this.recoveryIdleSinceNanos = 0L;
		}
	}

	/** Native spendability authority; unknown means a malformed response and never clears state. */
	record SpendabilityStatus(boolean known, boolean spendable, boolean rescanRequired, boolean repairQueued) {
		static SpendabilityStatus unknown() {
			return new SpendabilityStatus(false, false, true, true);
		}

		boolean isTerminalSafe() {
			return this.known && this.spendable && !this.rescanRequired && !this.repairQueued;
		}
	}

	static JSONObject buildSpendabilityPayload(String walletId) {
		return new JSONObject().put("method", "get_spendability_status").put("wallet_id", walletId);
	}

	static JSONObject buildRecoveryRescanPayload(String walletId, long fromHeight) {
		return new JSONObject().put("method", "rescan").put("wallet_id", walletId).put("from_height", fromHeight);
	}

	static SpendabilityStatus parseSpendabilityStatus(String responseText) {
		try {
			JSONObject response = new JSONObject(responseText == null ? "" : responseText);
			if (!(response.opt("ok") instanceof Boolean ok) || !ok)
				return SpendabilityStatus.unknown();
			JSONObject result = response.optJSONObject("result");
			if (result == null
					|| !(result.opt("spendable") instanceof Boolean spendable)
					|| !(result.opt("rescan_required") instanceof Boolean rescanRequired)
					|| !(result.opt("repair_queued") instanceof Boolean repairQueued))
				return SpendabilityStatus.unknown();
			return new SpendabilityStatus(true, spendable, rescanRequired, repairQueued);
		} catch (JSONException e) {
			return SpendabilityStatus.unknown();
		}
	}

	boolean hasPendingRecovery() {
		return this.usesPersistentUnifiedStorage()
				&& this.unifiedStorage.read().getRecoveryRescanFromHeight() != null;
	}

	/**
	 * Cheap recovery marker for status reporting: no native calls, storage read only.
	 * Returns null when recovery has never been involved in this wallet's lifetime.
	 */
	@Override
	public ZcashFamilyWallet.RecoveryProgress peekRecoveryProgress() {
		if (!this.usesPersistentUnifiedStorage())
			return null;
		if (this.unifiedStorage.read().getRecoveryRescanFromHeight() != null) {
			ZcashFamilyWallet.RecoveryProgress observed = this.lastObservedRecoveryProgress;
			return observed == ZcashFamilyWallet.RecoveryProgress.RECOVERING
					? observed : ZcashFamilyWallet.RecoveryProgress.PENDING;
		}
		return this.recoveryCompletedInLifetime ? ZcashFamilyWallet.RecoveryProgress.RECOVERED : null;
	}

	@Override
	public ZcashFamilyWallet.RecoveryProgress progressRecovery(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.usesPersistentUnifiedStorage())
			return ZcashFamilyWallet.RecoveryProgress.NONE;

		PirateUnifiedWalletStorage.Snapshot snapshot = this.unifiedStorage.read();
		Long recoveryFloor = snapshot.getRecoveryRescanFromHeight();
		if (recoveryFloor == null) {
			this.lastObservedRecoveryProgress = ZcashFamilyWallet.RecoveryProgress.NONE;
			return this.recoveryCompletedInLifetime
					? ZcashFamilyWallet.RecoveryProgress.RECOVERED : ZcashFamilyWallet.RecoveryProgress.NONE;
		}

		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (walletId == null)
				return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.PENDING);

			SpendabilityStatus spendability = parseSpendabilityStatus(
					nativeAdapter.invokeJson(buildSpendabilityPayload(walletId).toString(), false));
			if (spendability.isTerminalSafe()) {
				// The native authority says the replay completed — but the rescan SESSION
				// stays alive as a live-follow sync, and balance/transaction reads are
				// suppressed (block) while a rescan session exists. End it with a clean
				// cancel (which preserves the persisted heights) BEFORE clearing the
				// driver record; if the cancel is not acknowledged, keep the record and
				// retry the whole completion on the next pass.
				if (!this.cancelNativeSync(nativeAdapter, walletId))
					return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.RECOVERING);
				this.unifiedStorage.write(snapshot.getState(), snapshot.isSyncValidated(),
						snapshot.getIdentityHash(), snapshot.getSelectedServerUri(), null);
				this.recoveryCompletedInLifetime = true;
				this.lastObservedRecoveryProgress = ZcashFamilyWallet.RecoveryProgress.RECOVERED;
				return ZcashFamilyWallet.RecoveryProgress.RECOVERED;
			}

			// Reissuing rescan is NOT idempotent: it cancels and restarts active work.
			// While the native task is running, only observe, and treat that as proof the
			// replay is alive so any earlier idle observation is discarded.
			if (this.isNativeSyncInProgress(nativeAdapter)) {
				this.recoveryIdleSinceNanos = 0L;
				return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.RECOVERING);
			}

			// An acknowledged rescan starts, and finishes its gate bookkeeping,
			// asynchronously: the native side reports idle both before the replay task
			// starts and between the scan ending and complete_required_rescan running.
			// Reissuing inside those windows cancels and TRUNCATES the replay that was
			// about to satisfy the gate. So after an issue, require the idle-while-gated
			// state to persist for a grace period - timed from when idleness began, not
			// from the issue - before retrying. A replay that keeps reporting work resets
			// the timer and is never disturbed, however long it runs.
			if (this.recoveryRescanIssued) {
				long now = System.nanoTime();
				if (this.recoveryIdleSinceNanos == 0L) {
					this.recoveryIdleSinceNanos = now;
					return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.RECOVERING);
				}
				long grace = this.recoveryIdleGraceOverrideNanos != null
						? this.recoveryIdleGraceOverrideNanos : RECOVERY_IDLE_GRACE_NANOS;
				if (now - this.recoveryIdleSinceNanos < grace)
					return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.RECOVERING);
			}

			// First issue, restart re-issue, or a debounced retry after a genuinely dead
			// attempt. A later from_height is clamped down to the durable native floor, so a
			// controller-paced retry is always safe here.
			JSONObject rescanResponse = new JSONObject(nativeAdapter.invokeJson(
					buildRecoveryRescanPayload(walletId, recoveryFloor).toString(), false));
			if (isAcknowledged(rescanResponse)) {
				this.recoveryRescanIssued = true;
				this.recoveryIdleSinceNanos = 0L;
				return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.RECOVERING);
			}
			return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.PENDING);
		} catch (IOException | UnsatisfiedLinkError | RuntimeException e) {
			// Retryable: the durable record survives and the next controller pass retries.
			return this.observeRecovery(ZcashFamilyWallet.RecoveryProgress.PENDING);
		}
	}

	private ZcashFamilyWallet.RecoveryProgress observeRecovery(ZcashFamilyWallet.RecoveryProgress progress) {
		this.lastObservedRecoveryProgress = progress;
		return progress;
	}

	/** Test seam: shrink the idle grace so unit tests can exercise the retry path. */
	void setRecoveryIdleGraceForTesting(Long graceNanos) {
		this.recoveryIdleGraceOverrideNanos = graceNanos;
	}

	@Override
	public void recordSynchronizationAccepted(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet || this.appliedServerSelection == null)
			return;
		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (walletId == null)
				return;
			this.synchronizationAcceptedGeneration = this.appliedServerSelection.getGeneration();
			this.synchronizationAcceptedWalletId = walletId;
			this.observeFreshSynchronization(nativeAdapter);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			// Keep the fresh-sync requirement until an accepted sync can be bound to this selection.
		}
	}

	private void observeFreshSynchronization(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.freshSynchronizationRequired || this.appliedServerSelection == null)
			return;
		if (this.synchronizationAcceptedGeneration != this.appliedServerSelection.getGeneration()
				|| this.synchronizationAcceptedWalletId == null)
			return;
		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (!Objects.equals(this.synchronizationAcceptedWalletId, walletId))
				return;
			JSONObject request = new JSONObject().put("method", "sync_status").put("wallet_id", walletId);
			JSONObject response = new JSONObject(nativeAdapter.invokeJson(request.toString(), false));
			JSONObject result = response.optBoolean("ok", false) ? response.optJSONObject("result") : null;
			long targetHeight = result == null ? 0L : result.optLong("target_height", 0L);
			if (targetHeight > 0 && Math.abs(targetHeight - this.appliedServerSelection.getHeight())
					<= ZcashFamilyLightClient.SERVER_HEIGHT_AGREEMENT_TOLERANCE)
				this.freshSynchronizationRequired = false;
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			// Keep the fresh-sync requirement until the wallet-bound target can be observed.
		}
	}

	@Override
	public boolean isSynchronized() {
		if (!this.unifiedWallet)
			return super.isSynchronized();
		// A pending verified-import recovery replay means balances and histories are not
		// final yet, so the wallet must not present itself as synchronized: this blocks
		// balance/history/send (and further imports) until the replay completes.
		return !this.freshSynchronizationRequired && !this.hasPendingRecovery()
				&& this.appliedServerSelection != null
				&& this.isSelectionCurrent(this.appliedServerSelection) && super.isSynchronized();
	}

	boolean requiresFreshSynchronization() {
		return this.freshSynchronizationRequired;
	}

	@Override
	protected Integer getChainTip(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet)
			return super.getChainTip(nativeAdapter);

		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (walletId != null) {
				JSONObject request = new JSONObject().put("method", "sync_status").put("wallet_id", walletId);
				JSONObject response = new JSONObject(nativeAdapter.invokeJson(request.toString(), false));
				JSONObject result = response.optBoolean("ok", false) ? response.optJSONObject("result") : null;
				long targetHeight = result == null ? 0L : result.optLong("target_height", 0L);
				if (targetHeight > 0 && targetHeight <= Integer.MAX_VALUE)
					return (int) targetHeight;
			}
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			// Fall through to an exact typed probe instead of the JNI adapter's stale URI cache.
		}

		if (this.appliedServerSelection == null)
			return null;
		NativeServerProbe probe = this.probeNativeServer(nativeAdapter, this.appliedServerSelection, false);
		Long nativeHeight = probe.height();
		return probe.outcome() == EndpointSelectionOutcome.APPLIED && nativeHeight != null
				&& nativeHeight <= Integer.MAX_VALUE ? nativeHeight.intValue() : null;
	}

	static String normalizeServerUri(String serverUri) {
		if (serverUri == null || serverUri.isBlank())
			return null;
		try {
			URI uri = new URI(serverUri.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			int port = uri.getPort();
			if (scheme == null || host == null || port <= 0)
				return null;
			return new URI(scheme.toLowerCase(), null, host.toLowerCase(), port, null, null, null).toString();
		} catch (URISyntaxException e) {
			return null;
		}
	}

	@Override
	public boolean prepareForSwitch(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.unifiedWallet)
			return true;
		return this.stopNativeSync(nativeAdapter);
	}

	private boolean stopNativeSync(ZcashFamilyNativeAdapter nativeAdapter) {
		try {
			String walletId = this.getActiveWalletId(nativeAdapter);
			if (walletId == null)
				return false;

			// The Unified cancel call owns the wallet lifecycle lock and acknowledges only
			// after its native task has stopped. Its cached progress can remain incomplete.
			return this.cancelNativeSync(nativeAdapter, walletId);
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
	protected int synchronizationLagTolerance() {
		return this.usesPersistentUnifiedStorage() ? 0 : super.synchronizationLagTolerance();
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
