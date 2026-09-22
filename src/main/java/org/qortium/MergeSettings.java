package org.qortium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a generated settings file from its release template while preserving
 * local changes, so settings edited by hand or through PATCH /admin/settings
 * survive restarts and release upgrades.
 *
 * The snapshot file records the template that produced the current settings
 * file and is rewritten from the template after every merge. Top-level keys are
 * compared as whole values: a key whose value differs from the snapshot (or is
 * absent from it) is treated as a local change and kept; a key the snapshot has
 * but the settings file lacks is treated as a local removal and stays removed;
 * every other key follows the current template. Without a snapshot, removals
 * cannot be told apart from keys the template gained since the settings file
 * was generated, so only added and changed keys are preserved. Narrow
 * migrations can recognize an exact former default where preserving it would
 * otherwise prevent a managed profile from following a new release default.
 *
 * Superseded-value rules are a second, more targeted migration applied after the generic
 * merge above. Where the generic merge only ever preserves an operator's local value or lets
 * an untouched key follow the template, a superseded-value rule additionally forward-migrates
 * one specific known-old value to the new template value for a specific key, without touching
 * any other local value (an operator's own third choice, a different JSON type, or the key's
 * local absence all stay untouched).
 */
public class MergeSettings {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

	/**
	 * A narrow forward migration for one settings key: when the current template's value for
	 * {@code key} equals {@code replacementValue} and the operator's local file has {@code key}
	 * set to a value that exactly matches (same JSON type, not just equal after coercion) one of
	 * {@code supersededValues}, the local value is migrated to {@code replacementValue}. Any other
	 * local value for the key -- an operator-chosen third value, a different JSON type, or the key
	 * being absent locally -- is left untouched. Requiring the template to already carry
	 * {@code replacementValue} means a rollback to an older template (which still carries an old
	 * value) can never trigger the migration.
	 */
	private static final class SupersededValueRule {
		final String key;
		final Object replacementValue;
		final List<Object> supersededValues;

		SupersededValueRule(String key, Object replacementValue, Object... supersededValues) {
			this.key = key;
			this.replacementValue = replacementValue;
			this.supersededValues = List.of(supersededValues);
		}

		boolean matches(Object localValue) {
			for (Object supersededValue : this.supersededValues)
				if (exactTypeEquals(localValue, supersededValue))
					return true;
			return false;
		}
	}

	/**
	 * Rule table for values that must forward-migrate even though the operator's settings file
	 * still names the key explicitly (so the generic key-preservation merge above would otherwise
	 * keep the old value forever). Keep this table narrow and exact-match only.
	 *
	 * Keys that must NEVER be added here, and why:
	 * - {@code initialPeers}, {@code initialDataPeers}: an operator's peer/data-peer list can be an
	 *   intentional private topology; the generic merge already advances an untouched list and
	 *   preserves a changed one as a whole value, which is the correct behavior for network topology.
	 * - Anything in previewchain.json (checkpoints, minting thresholds, reward shares/schedules):
	 *   consensus-chain configuration, not operator settings, and not read through this merger.
	 * - {@code bitcoinyServers} / Electrum server choices: the bundled Electrum server registry can
	 *   update with releases, but an operator's chosen servers, plaintext, TLS-trust, or thread-count
	 *   settings are explicit connectivity choices and must never be silently overridden.
	 * - {@code autoUpdateMode}, {@code autoRestartEnabled}: explicit operator policy; launcher
	 *   environment overrides are applied after this merge runs.
	 * - {@code rewardRecordingOnly}: an operational recording preference, not consensus reward
	 *   policy, and has no "superseded value" to migrate away from.
	 *
	 * Values below are the Pirate Unified QDN wallet bundle signatures. Source of truth:
	 * docs/cross-chain/pirate-unified-qdn-releases.json ("current".signature and
	 * "previous".signature) and the compiled-in default PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE in
	 * org.qortium.settings.Settings. Keep all three in sync when the bundle rotates again.
	 */
	private static final List<SupersededValueRule> SUPERSEDED_VALUE_RULES = List.of(
			new SupersededValueRule(
					"pirateChainWalletQdnSignature",
					"24hysb2o6HwXY6U7DmfdcZEpu4JtC5pF9WGftHhkeQPXeoNyatd8EfbUD6G2DptfhKKv9r7o865UEfXYFCCK2M6j",
					"bEd5dM3wcbYWyG9hUHQQQsrYrYQ2rnYMDPahbqACpxCojjND5hwyUwiQQZNsTqRXu5awnsSurSwHnKkVeh24q7a"
			)
	);

	public static class MergeResult {
		public boolean createdFromTemplate = false;
		public List<String> preserved = new ArrayList<>();
		public List<String> removed = new ArrayList<>();
		public List<String> migrated = new ArrayList<>();
	}

	public static void main(String[] args) {
		if (args.length != 3) {
			System.err.println("usage: MergeSettings <template-file> <template-snapshot-file> <settings-file>");
			System.exit(1);
		}

		MergeResult result = null;
		try {
			result = merge(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
		} catch (IOException | RuntimeException e) {
			System.err.println(String.format("Cannot merge settings: %s", e.getMessage()));
			System.exit(2);
		}

		if (result.createdFromTemplate)
			System.out.println(String.format("Created '%s' from template", args[2]));
		if (!result.preserved.isEmpty())
			System.out.println(String.format("Preserved local settings: %s", String.join(", ", result.preserved)));
		if (!result.removed.isEmpty())
			System.out.println(String.format("Kept local removals: %s", String.join(", ", result.removed)));
		if (!result.migrated.isEmpty())
			System.out.println(String.format("Migrated superseded settings: %s", String.join(", ", result.migrated)));

		System.exit(0);
	}

	public static MergeResult merge(Path templatePath, Path snapshotPath, Path settingsPath) throws IOException {
		LinkedHashMap<String, Object> template = readJsonObject(templatePath);
		MergeResult result = new MergeResult();

		LinkedHashMap<String, Object> merged;
		LinkedHashMap<String, Object> localSettings = null;
		if (!Files.exists(settingsPath)) {
			merged = template;
			result.createdFromTemplate = true;
		} else {
			LinkedHashMap<String, Object> settings = readJsonObject(settingsPath);
			localSettings = settings;
			boolean haveSnapshot = Files.exists(snapshotPath);
			LinkedHashMap<String, Object> base = haveSnapshot ? readJsonObject(snapshotPath) : template;

			merged = new LinkedHashMap<>(template);
			for (Map.Entry<String, Object> entry : settings.entrySet()) {
				String key = entry.getKey();
				if (!base.containsKey(key) || isLocalSettingChanged(key, base.get(key), entry.getValue(), haveSnapshot)) {
					merged.put(key, entry.getValue());
					result.preserved.add(key);
				}
			}

			if (haveSnapshot) {
				for (String key : base.keySet()) {
					if (!settings.containsKey(key) && merged.containsKey(key)) {
						merged.remove(key);
						result.removed.add(key);
					}
				}
			}
		}

		// Superseded-value migrations run after the ordinary merge above (and before the unconditional
		// security migrations below, which are unrelated keys) so they see the operator's raw local
		// value for the key, not whatever the generic preserve/removal logic already decided.
		if (localSettings != null)
			applySupersededValueRules(template, localSettings, merged, result);

		boolean retirePeerClaimOrphaning = template.containsKey("developmentPeerClaimOrphaningEnabled");
		boolean retireHostedBootstrap = template.containsKey("bootstrapHosts");

		// Security migrations keep retired behavior explicitly disabled in managed runtime settings.
		// Local unsafe overrides must never survive an upgrade, and the old names remain present only
		// where an older release needs a safe value after rollback.
		if (retirePeerClaimOrphaning) {
			merged.put("recoveryWatchdogEnabled", false);
			merged.put("developmentPeerClaimOrphaningEnabled", false);
			result.preserved.remove("recoveryWatchdogEnabled");
			result.preserved.remove("developmentPeerClaimOrphaningEnabled");
			result.removed.remove("recoveryWatchdogEnabled");
			result.removed.remove("developmentPeerClaimOrphaningEnabled");
		}
		if (retireHostedBootstrap) {
			merged.put("bootstrap", false);
			merged.put("bootstrapHosts", new ArrayList<>());
			result.preserved.remove("bootstrap");
			result.preserved.remove("bootstrapHosts");
			result.removed.remove("bootstrap");
			result.removed.remove("bootstrapHosts");
		}

		writeJsonObject(settingsPath, merged);
		if (retirePeerClaimOrphaning || retireHostedBootstrap) {
			// Deliberately omit retired keys from the snapshot while retaining safe runtime values. If a
			// complete release rollback restores an old template, its generic merge then sees those values
			// as local additions and preserves them instead of restoring an unsafe default or configured path.
			LinkedHashMap<String, Object> safeSnapshot = new LinkedHashMap<>(template);
			if (retirePeerClaimOrphaning) {
				safeSnapshot.remove("recoveryWatchdogEnabled");
				safeSnapshot.remove("developmentPeerClaimOrphaningEnabled");
			}
			if (retireHostedBootstrap) {
				safeSnapshot.remove("bootstrap");
				safeSnapshot.remove("bootstrapHosts");
			}
			writeJsonObject(snapshotPath, safeSnapshot);
		} else {
			Files.copy(templatePath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
		}

		return result;
	}

	private static LinkedHashMap<String, Object> readJsonObject(Path path) throws IOException {
		LinkedHashMap<String, Object> parsed;
		try {
			parsed = JSON_MAPPER.readValue(Files.readAllBytes(path), JSON_OBJECT_TYPE);
		} catch (IOException e) {
			throw new IOException(String.format("cannot parse '%s': %s", path, e.getMessage()), e);
		}

		if (parsed == null)
			throw new IOException(String.format("'%s' does not contain a JSON object", path));

		return parsed;
	}

	/**
	 * Applies {@link #SUPERSEDED_VALUE_RULES} to {@code merged} in place, using {@code localSettings}
	 * (the operator's settings file exactly as read from disk, before the generic merge's
	 * preserve/removal decisions) to decide whether each rule's key should forward-migrate.
	 */
	private static void applySupersededValueRules(LinkedHashMap<String, Object> template,
			LinkedHashMap<String, Object> localSettings, LinkedHashMap<String, Object> merged, MergeResult result) {
		for (SupersededValueRule rule : SUPERSEDED_VALUE_RULES) {
			if (!exactTypeEquals(template.get(rule.key), rule.replacementValue))
				continue;

			if (!localSettings.containsKey(rule.key))
				continue;

			if (!rule.matches(localSettings.get(rule.key)))
				continue;

			merged.put(rule.key, rule.replacementValue);
			result.migrated.add(rule.key);
			result.preserved.remove(rule.key);
			result.removed.remove(rule.key);
		}
	}

	/** Value equality that also requires the same JSON-parsed Java type, e.g. a string never matches a number. */
	private static boolean exactTypeEquals(Object a, Object b) {
		if (a == null || b == null)
			return a == b;

		if (!a.getClass().equals(b.getClass()))
			return false;

		return a.equals(b);
	}

	private static boolean isLocalSettingChanged(String key, Object baseValue, Object settingValue, boolean haveSnapshot) {
		if (Objects.equals(baseValue, settingValue))
			return false;

		if (!haveSnapshot && "publicApiPaths".equals(key) && baseValue instanceof List<?> && settingValue instanceof List<?>)
			return !((List<?>) baseValue).containsAll((List<?>) settingValue);

		if (!haveSnapshot && "wallets".equals(key) && baseValue instanceof Map<?, ?> && settingValue instanceof Map<?, ?>)
			return !isLegacyWalletMapWithNewArrrDefault((Map<?, ?>) baseValue, (Map<?, ?>) settingValue);

		return true;
	}

	private static boolean isLegacyWalletMapWithNewArrrDefault(Map<?, ?> templateWallets, Map<?, ?> settingWallets) {
		// Without a snapshot, ARRR false in the otherwise-exact wallet map is the only former
		// participant default we can safely recognize. Any missing key, added key, or other value
		// difference makes the map operator-owned and preserves it whole.
		if (templateWallets.size() != settingWallets.size())
			return false;

		for (Map.Entry<?, ?> entry : templateWallets.entrySet()) {
			Object wallet = entry.getKey();
			Object templateEnabled = entry.getValue();
			Object settingEnabled = settingWallets.get(wallet);

			if ("ARRR".equals(wallet) && Boolean.TRUE.equals(templateEnabled) && Boolean.FALSE.equals(settingEnabled))
				continue;

			if (!settingWallets.containsKey(wallet) || !Objects.equals(templateEnabled, settingEnabled))
				return false;
		}

		return true;
	}

	private static void writeJsonObject(Path path, LinkedHashMap<String, Object> jsonObject) throws IOException {
		Path absolutePath = path.toAbsolutePath().normalize();
		Path tempPath = Files.createTempFile(absolutePath.getParent(), "settings-", ".tmp");
		try {
			String json = JSON_MAPPER.writeValueAsString(jsonObject) + System.lineSeparator();
			Files.write(tempPath, json.getBytes(StandardCharsets.UTF_8));

			try {
				Files.move(tempPath, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tempPath, absolutePath, StandardCopyOption.REPLACE_EXISTING);
			}
			tempPath = null;
		} finally {
			if (tempPath != null)
				Files.deleteIfExists(tempPath);
		}
	}

}
