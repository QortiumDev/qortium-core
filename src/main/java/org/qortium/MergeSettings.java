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
 * was generated, so only added and changed keys are preserved.
 */
public class MergeSettings {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

	public static class MergeResult {
		public boolean createdFromTemplate = false;
		public List<String> preserved = new ArrayList<>();
		public List<String> removed = new ArrayList<>();
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

		System.exit(0);
	}

	public static MergeResult merge(Path templatePath, Path snapshotPath, Path settingsPath) throws IOException {
		LinkedHashMap<String, Object> template = readJsonObject(templatePath);
		MergeResult result = new MergeResult();

		LinkedHashMap<String, Object> merged;
		if (!Files.exists(settingsPath)) {
			merged = template;
			result.createdFromTemplate = true;
		} else {
			LinkedHashMap<String, Object> settings = readJsonObject(settingsPath);
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

		writeJsonObject(settingsPath, merged);
		Files.copy(templatePath, snapshotPath, StandardCopyOption.REPLACE_EXISTING);

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

	private static boolean isLocalSettingChanged(String key, Object baseValue, Object settingValue, boolean haveSnapshot) {
		if (Objects.equals(baseValue, settingValue))
			return false;

		if (!haveSnapshot && "publicApiPaths".equals(key) && baseValue instanceof List<?> && settingValue instanceof List<?>)
			return !((List<?>) baseValue).containsAll((List<?>) settingValue);

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
