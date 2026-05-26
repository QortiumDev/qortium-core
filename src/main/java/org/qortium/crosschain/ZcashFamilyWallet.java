package org.qortium.crosschain;

import com.rust.litewalletjni.LiteWalletJni;
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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

public class ZcashFamilyWallet {

	private static final Logger LOGGER = LogManager.getLogger(ZcashFamilyWallet.class);

	private static final String COIN_PARAMS_FILENAME = "coinparams.json";
	private static final String SAPLING_OUTPUT_FILENAME = "saplingoutput_base64";
	private static final String SAPLING_SPEND_FILENAME = "saplingspend_base64";

	protected final ZcashFamilyWalletConfig config;
	private final byte[] entropyBytes;
	private final boolean isNullSeedWallet;
	private String seedPhrase;
	private boolean ready = false;

	private String params;
	private String saplingOutput64;
	private String saplingSpend64;

	public ZcashFamilyWallet(ZcashFamilyWalletConfig config, byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		this.config = config;
		this.entropyBytes = entropyBytes;
		this.isNullSeedWallet = isNullSeedWallet;

		Path libDirectory = config.getRustLibOuterDirectory();
		if (!Files.exists(Paths.get(libDirectory.toString(), COIN_PARAMS_FILENAME)))
			return;

		this.params = Files.readString(Paths.get(libDirectory.toString(), COIN_PARAMS_FILENAME));
		this.saplingOutput64 = Files.readString(Paths.get(libDirectory.toString(), SAPLING_OUTPUT_FILENAME));
		this.saplingSpend64 = Files.readString(Paths.get(libDirectory.toString(), SAPLING_SPEND_FILENAME));

		this.ready = this.initialize();
	}

	private boolean initialize() {
		try {
			LiteWalletJni.initlogging();

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

			String inputSeedResponse = LiteWalletJni.getseedphrasefromentropyb64(entropy64);
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

				String outputSeedResponse = LiteWalletJni.initfromseed(serverUri, this.params, inputSeedPhrase,
						Integer.toString(birthday), this.saplingOutput64, this.saplingSpend64);
				JSONObject outputSeedJson = new JSONObject(outputSeedResponse);
				String outputSeedPhrase = outputSeedJson.optString("seed", null);

				if (inputSeedPhrase == null || !Objects.equals(inputSeedPhrase, outputSeedPhrase)) {
					LOGGER.info("Unable to initialize {} wallet: seed phrases do not match, or are null", this.config.getDisplayName());
					return false;
				}

				this.seedPhrase = outputSeedPhrase;
			} else {
				String response = LiteWalletJni.initfromb64(serverUri, params, wallet, saplingOutput64, saplingSpend64);
				if (response != null && !response.contains("\"initalized\":true")) {
					LOGGER.info("Unable to initialize {} wallet at {}: {}", this.config.getDisplayName(), serverUri, response);
					return false;
				}
				this.seedPhrase = inputSeedPhrase;
			}

			Integer ourHeight = this.getHeight();
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

	private void encrypt() {
		if (this.isEncrypted())
			return;

		String encryptionKey = this.getEncryptionKey();
		if (encryptionKey != null)
			this.doEncrypt(encryptionKey);
	}

	private void decrypt() {
		if (!this.isEncrypted())
			return;

		String encryptionKey = this.getEncryptionKey();
		if (encryptionKey != null)
			this.doDecrypt(encryptionKey);
	}

	public void unlock() {
		if (!this.isEncrypted())
			return;

		String encryptionKey = this.getEncryptionKey();
		if (encryptionKey != null)
			this.doUnlock(encryptionKey);
	}

	public boolean save() throws IOException {
		if (!isInitialized()) {
			LOGGER.info("Error: can't save wallet because no wallet is initialized");
			return false;
		}

		if (this.isNullSeedWallet())
			return false;

		this.encrypt();

		String wallet64 = LiteWalletJni.save();
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
		Files.createDirectories(walletPath.getParent());
		Files.write(walletPath, wallet, StandardOpenOption.CREATE);

		LOGGER.debug("Saved {} wallet", this.config.getDisplayName());
		return true;
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

	private String getEntropyHash58() {
		if (this.entropyBytes == null)
			return null;

		byte[] entropyHash = Crypto.digest(this.entropyBytes);
		return Base58.encode(entropyHash);
	}

	public String getSeedPhrase() {
		return this.seedPhrase;
	}

	private String getEncryptionKey() {
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

	private Path getCurrentWalletPath() {
		String entropyHash58 = this.getEntropyHash58();
		String filename = String.format("wallet-%s.dat", entropyHash58);
		return this.config.getWalletPath(filename);
	}

	public boolean isInitialized() {
		return this.entropyBytes != null && this.ready;
	}

	public boolean isSynchronized() {
		Integer height = this.getHeight();
		Integer chainTip = this.getChainTip();

		if (height == null || chainTip == null)
			return false;

		return height >= chainTip - 2;
	}

	public Integer getHeight() {
		String response = LiteWalletJni.execute("height", "");
		JSONObject json = new JSONObject(response);
		return json.has("height") ? json.getInt("height") : null;
	}

	public Integer getChainTip() {
		String response = LiteWalletJni.execute("info", "");
		JSONObject json = new JSONObject(response);
		return json.has("latest_block_height") ? json.getInt("latest_block_height") : null;
	}

	public boolean isNullSeedWallet() {
		return this.isNullSeedWallet;
	}

	public Boolean isEncrypted() {
		String response = LiteWalletJni.execute("encryptionstatus", "");
		JSONObject json = new JSONObject(response);
		return json.has("encrypted") ? json.getBoolean("encrypted") : null;
	}

	public boolean doEncrypt(String key) {
		String response = LiteWalletJni.execute("encrypt", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public boolean doDecrypt(String key) {
		String response = LiteWalletJni.execute("decrypt", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public boolean doUnlock(String key) {
		String response = LiteWalletJni.execute("unlock", key);
		JSONObject json = new JSONObject(response);
		String result = json.getString("result");
		return json.has("result") && Objects.equals(result, "success");
	}

	public String getWalletAddress() {
		String response = LiteWalletJni.execute("balance", "");
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
		String response = LiteWalletJni.execute("export", "");
		JSONArray addressesJson = new JSONArray(response);
		if (!addressesJson.isEmpty()) {
			JSONObject addressJson = addressesJson.getJSONObject(0);
			if (addressJson.has("private_key"))
				return addressJson.getString("private_key");
		}
		return null;
	}

	public String getWalletSeed(String entropy58) {
		byte[] myEntropyBytes = Base58.decode(entropy58);
		String myEntropy64 = Base64.toBase64String(myEntropyBytes);
		String mySeedResponse = LiteWalletJni.getseedphrasefromentropyb64(myEntropy64);
		JSONObject mySeedJson = new JSONObject(mySeedResponse);
		return mySeedJson.has("seedPhrase") ? mySeedJson.getString("seedPhrase") : null;
	}
}
