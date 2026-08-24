package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.DecoderException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

public class ZcashFamilyWallet {

	private static final Logger LOGGER = LogManager.getLogger(ZcashFamilyWallet.class);
	private static final ZcashFamilyNativeCoordinator NATIVE_COORDINATOR = ZcashFamilyNativeCoordinator.getInstance();

	private static final String COIN_PARAMS_FILENAME = "coinparams.json";
	private static final String SAPLING_OUTPUT_FILENAME = "saplingoutput_base64";
	private static final String SAPLING_SPEND_FILENAME = "saplingspend_base64";

	protected final ZcashFamilyWalletConfig config;
	private final byte[] entropyBytes;
	private final boolean isNullSeedWallet;
	private String seedPhrase;
	private boolean ready = false;
	private boolean initializationAvailable = false;

	private String params;
	private String saplingOutput64;
	private String saplingSpend64;

	public ZcashFamilyWallet(ZcashFamilyWalletConfig config, byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		this(config, entropyBytes, isNullSeedWallet, true, true);
	}

	protected ZcashFamilyWallet(ZcashFamilyWalletConfig config, byte[] entropyBytes, boolean isNullSeedWallet,
			boolean loadLegacyParameters, boolean initializeImmediately) throws IOException {
		this.config = config;
		this.entropyBytes = entropyBytes;
		this.isNullSeedWallet = isNullSeedWallet;

		if (loadLegacyParameters && !this.loadLegacyParameters(config.getLegacyRustLibOuterDirectory()))
			return;

		if (!loadLegacyParameters) {
			this.params = "";
			this.saplingOutput64 = "";
			this.saplingSpend64 = "";
		}
		this.initializationAvailable = true;

		if (initializeImmediately)
			this.ready = this.initializeWallet();
	}

	private boolean loadLegacyParameters(Path libDirectory) throws IOException {
		Path coinParamsPath = libDirectory.resolve(COIN_PARAMS_FILENAME);
		if (!Files.exists(coinParamsPath))
			return false;

		this.params = Files.readString(coinParamsPath);
		this.saplingOutput64 = Files.readString(libDirectory.resolve(SAPLING_OUTPUT_FILENAME));
		this.saplingSpend64 = Files.readString(libDirectory.resolve(SAPLING_SPEND_FILENAME));
		return true;
	}

	protected final boolean initializeWallet() {
		if (!this.initializationAvailable)
			return false;

		try {
			return NATIVE_COORDINATOR.execute("initialize wallet", this::initialize);
		} catch (ZcashFamilyNativeCoordinator.NativeWalletException e) {
			LOGGER.info("Unable to initialize {} wallet: {}", this.config.getDisplayName(), e.getMessage());
			return false;
		}
	}

	protected boolean initialize(ZcashFamilyNativeAdapter nativeAdapter) {
		try {
			nativeAdapter.initLogging();

			if (this.entropyBytes == null)
				return false;

			Bitcoiny blockchain = this.config.getBlockchain();
			if (blockchain == null || blockchain.getBlockchainProvider() == null)
				return false;

			ChainableServer server = blockchain.getBlockchainProvider().getCurrentServer();
			if (server == null)
				return false;

			String scheme = server.getConnectionType() == ChainableServer.ConnectionType.SSL ? "https" : "http";
			String serverUri = String.format("%s://%s:%d/", scheme, server.getHostName(), server.getPort());

			String entropy64 = Base64.toBase64String(this.entropyBytes);

			String inputSeedResponse = nativeAdapter.getSeedPhraseFromEntropyB64(entropy64);
			JSONObject inputSeedJson = new JSONObject(inputSeedResponse);
			String inputSeedPhrase = inputSeedJson.optString("seedPhrase", null);

			String wallet = this.load();
			if (wallet == null) {
				int birthday = this.config.getDefaultBirthday();
				if (this.isNullSeedWallet) {
					try {
						birthday = blockchain.getBlockchainProvider().getCurrentHeight();
					} catch (ForeignBlockchainException e) {
						// Use the configured default birthday.
					}
				}

				String outputSeedResponse = nativeAdapter.initFromSeed(serverUri, this.params, inputSeedPhrase,
						Integer.toString(birthday), this.saplingOutput64, this.saplingSpend64);
				JSONObject outputSeedJson = new JSONObject(outputSeedResponse);
				String outputSeedPhrase = outputSeedJson.optString("seed", null);

				if (inputSeedPhrase == null || !Objects.equals(inputSeedPhrase, outputSeedPhrase)) {
					LOGGER.info("Unable to initialize {} wallet: seed phrases do not match, or are null", this.config.getDisplayName());
					return false;
				}

				this.seedPhrase = outputSeedPhrase;
			} else {
				String response = nativeAdapter.initFromB64(serverUri, params, wallet, saplingOutput64, saplingSpend64);
				if (response != null && !response.contains("\"initalized\":true")) {
					LOGGER.info("Unable to initialize {} wallet at {}", this.config.getDisplayName(), serverUri);
					return false;
				}
				this.seedPhrase = inputSeedPhrase;
			}

			Integer ourHeight = this.getHeight(nativeAdapter);
			return ourHeight != null && ourHeight > 0;
		} catch (IOException | JSONException | UnsatisfiedLinkError e) {
			LOGGER.info("Unable to initialize {} wallet: {}", this.config.getDisplayName(), e.getMessage());
		}

		return false;
	}

	public boolean isReady() {
		return this.ready;
	}

	public void setReady(boolean ready) {
		this.ready = ready;
	}

	public boolean entropyBytesEqual(byte[] testEntropyBytes) {
		return Arrays.equals(testEntropyBytes, this.entropyBytes);
	}

	public boolean matchesWallet(byte[] testEntropyBytes, boolean testNullSeedWallet) {
		return this.isNullSeedWallet == testNullSeedWallet && this.entropyBytesEqual(testEntropyBytes);
	}

	private void encrypt(ZcashFamilyNativeAdapter nativeAdapter) {
		if (this.isEncrypted(nativeAdapter))
			return;

		String encryptionKey = this.getEncryptionKey();
		if (encryptionKey != null)
			this.doEncrypt(nativeAdapter, encryptionKey);
	}

	private void decrypt(ZcashFamilyNativeAdapter nativeAdapter) {
		if (!this.isEncrypted(nativeAdapter))
			return;

		String encryptionKey = this.getEncryptionKey();
		if (encryptionKey != null)
			this.doDecrypt(nativeAdapter, encryptionKey);
	}

	public void unlock() {
		NATIVE_COORDINATOR.execute("unlock wallet", nativeAdapter -> {
			if (!this.isEncrypted(nativeAdapter)) {
				return null;
			}

			String encryptionKey = this.getEncryptionKey();
			if (encryptionKey != null)
				this.doUnlock(nativeAdapter, encryptionKey);
			return null;
		});
	}

	public boolean save() throws IOException {
		return NATIVE_COORDINATOR.execute("save wallet", this::save);
	}

	/** Native-family hook used before replacing the process-global wallet context. */
	public boolean prepareForSwitch(ZcashFamilyNativeAdapter nativeAdapter) {
		return true;
	}

	/** Native-family hook run after a replacement wallet has selected its storage. */
	public void cleanupAfterSwitch() {
	}

	/** Native-family hook run while the native lane is still available during controller shutdown. */
	public boolean prepareForShutdown(ZcashFamilyNativeAdapter nativeAdapter) {
		return true;
	}

	/** Native-family hook used after a sync has reached a validated chain tip. */
	public void recordValidatedSync(ZcashFamilyNativeAdapter nativeAdapter) throws IOException {
	}

	public boolean usesPersistentNativeStorage() {
		return false;
	}

	public boolean isNativeSyncInProgress(ZcashFamilyNativeAdapter nativeAdapter) {
		return false;
	}

	protected boolean save(ZcashFamilyNativeAdapter nativeAdapter) throws IOException {
		if (!isInitialized()) {
			LOGGER.info("Error: can't save wallet because no wallet is initialized");
			return false;
		}

		if (this.isNullSeedWallet())
			return false;

		this.encrypt(nativeAdapter);

		String wallet64 = nativeAdapter.save();
		byte[] wallet;
		try {
			wallet = Base64.decode(wallet64);
		} catch (DecoderException e) {
			LOGGER.info("Unable to decode wallet");
			return false;
		}
		if (wallet == null) {
			LOGGER.info("Unable to save wallet");
			return false;
		}

		Path walletPath = this.getCurrentWalletPath();
		writeWalletAtomically(walletPath, wallet);

		LOGGER.debug("Saved {} wallet", this.config.getDisplayName());
		return true;
	}

	static void writeWalletAtomically(Path walletPath, byte[] wallet) throws IOException {
		Files.createDirectories(walletPath.getParent());
		Path temporaryPath = Files.createTempFile(walletPath.getParent(), ".wallet-", ".tmp");
		try {
			Files.write(temporaryPath, wallet, StandardOpenOption.TRUNCATE_EXISTING);
			try {
				Files.move(temporaryPath, walletPath, StandardCopyOption.ATOMIC_MOVE,
						StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporaryPath, walletPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}

	public String load() throws IOException {
		if (this.isNullSeedWallet())
			return null;

		Path walletPath = this.getCurrentWalletPath();
		if (!Files.exists(walletPath))
			return null;

		byte[] wallet = Files.readAllBytes(walletPath);
		if (wallet == null)
			return null;

		return Base64.toBase64String(wallet);
	}

	protected String getEntropyHash58() {
		if (this.entropyBytes == null)
			return null;

		byte[] entropyHash = Crypto.digest(this.entropyBytes);
		return Base58.encode(entropyHash);
	}

	public String getSeedPhrase() {
		return this.seedPhrase;
	}

	protected void setSeedPhrase(String seedPhrase) {
		this.seedPhrase = seedPhrase;
	}

	protected byte[] getEntropyBytes() {
		return this.entropyBytes;
	}

	protected String getEncryptionKey() {
		if (this.entropyBytes == null)
			return null;

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			outputStream.write(this.config.getWalletEncryptionPrefix().getBytes(StandardCharsets.UTF_8));
			outputStream.write(this.entropyBytes);
		} catch (IOException e) {
			return null;
		}

		byte[] encryptionKeyHash = Crypto.digest(outputStream.toByteArray());
		return Base58.encode(encryptionKeyHash);
	}

	protected Path getCurrentWalletPath() {
		String entropyHash58 = this.getEntropyHash58();
		String filename = String.format("wallet-%s.dat", entropyHash58);
		return this.config.getWalletPath(filename);
	}

	public boolean isInitialized() {
		return this.entropyBytes != null && this.ready;
	}

	public boolean isSynchronized() {
		return NATIVE_COORDINATOR.execute("check wallet synchronization", nativeAdapter -> {
			Integer height = this.getHeight(nativeAdapter);
			Integer chainTip = this.getChainTip(nativeAdapter);

			if (height == null || chainTip == null)
				return false;

			return height >= chainTip - 2;
		});
	}

	public Integer getHeight() {
		return NATIVE_COORDINATOR.execute("get wallet height", this::getHeight);
	}

	protected Integer getHeight(ZcashFamilyNativeAdapter nativeAdapter) {
		String response = nativeAdapter.execute("height", "");
		JSONObject json = new JSONObject(response);
		return json.has("height") ? json.getInt("height") : null;
	}

	public Integer getChainTip() {
		return NATIVE_COORDINATOR.execute("get wallet chain tip", this::getChainTip);
	}

	protected Integer getChainTip(ZcashFamilyNativeAdapter nativeAdapter) {
		String response = nativeAdapter.execute("info", "");
		JSONObject json = new JSONObject(response);
		return json.has("latest_block_height") ? json.getInt("latest_block_height") : null;
	}

	public boolean isNullSeedWallet() {
		return this.isNullSeedWallet;
	}

	public Boolean isEncrypted() {
		return NATIVE_COORDINATOR.execute("get wallet encryption status", this::isEncrypted);
	}

	private Boolean isEncrypted(ZcashFamilyNativeAdapter nativeAdapter) {
		String response = nativeAdapter.execute("encryptionstatus", "");
		JSONObject json = new JSONObject(response);
		return json.has("encrypted") ? json.getBoolean("encrypted") : null;
	}

	public boolean doEncrypt(String key) {
		return NATIVE_COORDINATOR.execute("encrypt wallet", nativeAdapter -> this.doEncrypt(nativeAdapter, key));
	}

	private boolean doEncrypt(ZcashFamilyNativeAdapter nativeAdapter, String key) {
		String response = nativeAdapter.execute("encrypt", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public boolean doDecrypt(String key) {
		return NATIVE_COORDINATOR.execute("decrypt wallet", nativeAdapter -> this.doDecrypt(nativeAdapter, key));
	}

	private boolean doDecrypt(ZcashFamilyNativeAdapter nativeAdapter, String key) {
		String response = nativeAdapter.execute("decrypt", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public boolean doUnlock(String key) {
		return NATIVE_COORDINATOR.execute("unlock wallet", nativeAdapter -> this.doUnlock(nativeAdapter, key));
	}

	private boolean doUnlock(ZcashFamilyNativeAdapter nativeAdapter, String key) {
		String response = nativeAdapter.execute("unlock", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public String getWalletAddress() {
		return NATIVE_COORDINATOR.execute("get wallet address", this::getWalletAddress);
	}

	protected String getWalletAddress(ZcashFamilyNativeAdapter nativeAdapter) {
		String response = nativeAdapter.execute("balance", "");
		JSONObject json = new JSONObject(response);

		if (json.has("z_addresses")) {
			JSONArray zAddresses = json.getJSONArray("z_addresses");
			if (zAddresses != null && !zAddresses.isEmpty()) {
				JSONObject firstAddress = zAddresses.getJSONObject(0);
				if (firstAddress.has("address"))
					return firstAddress.getString("address");
			}
		}

		return null;
	}

	public String getPrivateKey() {
		return NATIVE_COORDINATOR.execute("export wallet key", this::getPrivateKey);
	}

	private String getPrivateKey(ZcashFamilyNativeAdapter nativeAdapter) {
		String response = nativeAdapter.execute("export", "");
		JSONArray addressesJson = new JSONArray(response);
		if (!addressesJson.isEmpty()) {
			JSONObject addressJson = addressesJson.getJSONObject(0);
			if (addressJson.has("private_key"))
				return addressJson.getString("private_key");
		}
		return null;
	}

	public String getWalletSeed(String entropy58) {
		return NATIVE_COORDINATOR.execute("derive wallet seed", nativeAdapter -> {
			byte[] myEntropyBytes = Base58.decode(entropy58);
			String myEntropy64 = Base64.toBase64String(myEntropyBytes);
			String mySeedResponse = nativeAdapter.getSeedPhraseFromEntropyB64(myEntropy64);
			JSONObject mySeedJson = new JSONObject(mySeedResponse);
			return mySeedJson.has("seedPhrase") ? mySeedJson.getString("seedPhrase") : null;
		});
	}
}
