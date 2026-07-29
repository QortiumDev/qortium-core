package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.qortium.crypto.ElectrumSSLSocketFactory;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent trust-on-first-use store of ElectrumX TLS leaf-certificate SHA-256 fingerprints.
 * <p>
 * Used when the {@link ElectrumSSLSocketFactory.TrustMode#TOFU} Electrum TLS trust mode is active: the first
 * connection to a server records its leaf-certificate fingerprint here, and later connections are pinned to it,
 * so a changed certificate is detected and rejected rather than silently re-trusted.
 */
public final class ElectrumCertificateStore {

	private static final Logger LOGGER = LogManager.getLogger(ElectrumCertificateStore.class);
	private static final String STORE_FILENAME = "electrum-tls-fingerprints.json";
	private static final String STORE_DIRNAME = "crosschain";

	private static ElectrumCertificateStore defaultInstance;

	private final Path storePath;
	private final Map<String, String> fingerprints = new LinkedHashMap<>();

	private ElectrumCertificateStore(Path storePath) {
		this.storePath = storePath;
		load();
	}

	/**
	 * Returns the shared store rooted next to the node's lists directory, or {@code null} when no
	 * settings are loaded (e.g. offline tooling) and there is therefore nowhere to persist first-use
	 * decisions.
	 */
	public static synchronized ElectrumCertificateStore getDefault() {
		if (defaultInstance != null)
			return defaultInstance;

		Settings settings = Settings.getLoadedInstance();
		if (settings == null)
			return null;

		// Deliberately not inside the lists directory itself: ResourceListManager treats every
		// ".json" file found there as a JSON-array resource list, and this store's JSON-object shape
		// made it fail that parse, which broke every blocked/followed lookup node-wide until the file
		// was found and removed by hand. Migrate any copy left behind at that old location.
		Path listsPath = Paths.get(settings.getListsPath());
		Path storePath = listsPath.resolveSibling(STORE_DIRNAME).resolve(STORE_FILENAME);
		migrateLegacyStore(listsPath.resolve(STORE_FILENAME), storePath);

		defaultInstance = new ElectrumCertificateStore(storePath);
		return defaultInstance;
	}

	/**
	 * Moves a store file left behind at the old, colliding location to its new home. Best-effort:
	 * on failure the legacy file is left intact because it is persistent TOFU pinning state. The
	 * ResourceListManager hardening safely skips the legacy JSON-object file until a later startup
	 * can migrate it.
	 */
	static void migrateLegacyStore(Path legacyPath, Path newPath) {
		if (Objects.equals(legacyPath, newPath) || !Files.exists(legacyPath) || Files.exists(newPath))
			return;

		try {
			if (newPath.getParent() != null)
				Files.createDirectories(newPath.getParent());
			Files.move(legacyPath, newPath);
			LOGGER.info("Migrated Electrum TLS fingerprint store out of the lists directory to {}", newPath);
		} catch (IOException e) {
			LOGGER.warn("Unable to migrate Electrum TLS fingerprint store to {}; leaving the legacy pinning store intact at {}",
					newPath, legacyPath, e);
		}
	}

	/** Test-only factory backed by an explicit path. */
	static ElectrumCertificateStore forPath(Path storePath) {
		return new ElectrumCertificateStore(storePath);
	}

	public synchronized String getFingerprint(String host, int port) {
		return this.fingerprints.get(key(host, port));
	}

	public synchronized void recordFingerprint(String host, int port, String fingerprint) {
		String normalised = ElectrumSSLSocketFactory.normalizeSha256Fingerprint(fingerprint);
		if (normalised == null)
			return;

		String previous = this.fingerprints.put(key(host, port), normalised);
		if (!normalised.equals(previous)) {
			LOGGER.info("Recorded Electrum TLS fingerprint for {}:{}", host, port);
			save();
		}
	}

	private void load() {
		if (!Files.exists(this.storePath))
			return;

		try (Reader reader = Files.newBufferedReader(this.storePath, StandardCharsets.UTF_8)) {
			Object parsed = new JSONParser().parse(reader);
			if (!(parsed instanceof JSONObject))
				return;

			Object entries = ((JSONObject) parsed).get("fingerprints");
			if (!(entries instanceof JSONObject))
				return;

			for (Object entryObject : ((JSONObject) entries).entrySet()) {
				Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
				if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String))
					continue;

				String normalised = ElectrumSSLSocketFactory.normalizeSha256Fingerprint((String) entry.getValue());
				if (normalised != null)
					this.fingerprints.put(((String) entry.getKey()).toLowerCase(Locale.ROOT), normalised);
			}
		} catch (Exception e) {
			LOGGER.warn("Unable to load Electrum TLS fingerprint store from {}", this.storePath, e);
		}
	}

	@SuppressWarnings("unchecked")
	private void save() {
		try {
			if (this.storePath.getParent() != null)
				Files.createDirectories(this.storePath.getParent());

			JSONObject entries = new JSONObject();
			entries.putAll(this.fingerprints);

			JSONObject root = new JSONObject();
			root.put("fingerprints", entries);

			Files.write(this.storePath, root.toJSONString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			LOGGER.warn("Unable to persist Electrum TLS fingerprint store to {}", this.storePath, e);
		}
	}

	private static String key(String host, int port) {
		return (host == null ? "" : host.trim().toLowerCase(Locale.ROOT)) + ":" + port;
	}
}
