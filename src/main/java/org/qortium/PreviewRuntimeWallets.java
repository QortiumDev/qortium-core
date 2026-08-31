package org.qortium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * Keeps managed Previewnet wallet state under the persistent runtime directory.
 *
 * <p>Older launchers left {@code walletsPath} at its relative {@code wallets}
 * default, so Core wrote wallet registries beneath the replaceable install
 * directory. This helper recognizes only that exact legacy/default path (or an
 * already managed runtime path), copies and verifies the complete legacy tree,
 * atomically switches the settings file, and then removes redundant legacy
 * files. Any other configured path is operator-owned and remains untouched.</p>
 */
public final class PreviewRuntimeWallets {

	private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
			.enable(SerializationFeature.INDENT_OUTPUT);
	private static final TypeReference<LinkedHashMap<String, Object>> JSON_OBJECT_TYPE =
			new TypeReference<LinkedHashMap<String, Object>>() {};
	private static final LinkOption[] NO_FOLLOW_LINKS = { LinkOption.NOFOLLOW_LINKS };

	public record PreparationResult(Path walletsPath, boolean customPath, boolean migrated,
			boolean legacyCleanupComplete) {
	}

	private PreviewRuntimeWallets() {
	}

	public static void main(String[] args) {
		if (args.length != 3) {
			System.err.println("usage: PreviewRuntimeWallets <preview-install-dir> <runtime-dir> <settings-file>");
			System.exit(1);
		}

		try {
			PreparationResult result = prepare(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
			if (result.customPath()) {
				System.out.println("Preserved custom walletsPath: " + result.walletsPath());
			} else if (result.migrated()) {
				System.out.println("Migrated Previewnet wallet state to: " + result.walletsPath());
			} else {
				System.out.println("Previewnet wallet state path: " + result.walletsPath());
			}
			if (!result.legacyCleanupComplete())
				System.out.println("Legacy wallet copies remain in the install directory and can be removed after verification.");
		} catch (IOException | RuntimeException e) {
			System.err.println("Cannot prepare Previewnet wallet storage: " + e.getMessage());
			System.exit(2);
		}
	}

	static PreparationResult prepare(Path previewInstallDirectory, Path runtimeDirectory, Path settingsPath)
			throws IOException {
		Path installDirectory = previewInstallDirectory.toAbsolutePath().normalize();
		Path runtime = runtimeDirectory.toAbsolutePath().normalize();
		Path settings = settingsPath.toAbsolutePath().normalize();
		Path legacyWallets = installDirectory.resolve("wallets").normalize();
		Path runtimeWallets = runtime.resolve("wallets").normalize();
		LinkedHashMap<String, Object> settingsJson = readJsonObject(settings);

		Object configuredValue = settingsJson.get("walletsPath");
		if (settingsJson.containsKey("walletsPath") && !(configuredValue instanceof String))
			throw new IOException("walletsPath must be a non-empty string");

		if (configuredValue instanceof String configuredPath) {
			if (configuredPath.isBlank())
				throw new IOException("walletsPath must be a non-empty string");

			Path resolvedConfiguredPath;
			try {
				Path configured = Path.of(configuredPath);
				resolvedConfiguredPath = (configured.isAbsolute() ? configured : installDirectory.resolve(configured))
						.toAbsolutePath().normalize();
			} catch (RuntimeException e) {
				throw new IOException("walletsPath is invalid: " + e.getMessage(), e);
			}

			if (!resolvedConfiguredPath.equals(legacyWallets) && !resolvedConfiguredPath.equals(runtimeWallets))
				return new PreparationResult(resolvedConfiguredPath, true, false, true);
		}

		if (legacyWallets.equals(runtimeWallets)) {
			settingsJson.put("walletsPath", runtimeWallets.toString());
			writeJsonObject(settings, settingsJson);
			return new PreparationResult(runtimeWallets, false, false, true);
		}

		boolean legacyExists = existsNoFollow(legacyWallets);
		if (legacyExists) {
			if (Files.isSymbolicLink(legacyWallets) || !Files.isDirectory(legacyWallets, NO_FOLLOW_LINKS))
				throw new IOException("legacy wallets path is not a regular directory: " + legacyWallets);
			if (existsNoFollow(runtimeWallets)
					&& (Files.isSymbolicLink(runtimeWallets) || !Files.isDirectory(runtimeWallets, NO_FOLLOW_LINKS)))
				throw new IOException("runtime wallets path conflicts with the legacy migration: " + runtimeWallets);

			preflightTree(legacyWallets, runtimeWallets);
			copyAndVerifyTree(legacyWallets, runtimeWallets);
		}

		// Switch only after the complete target tree exists and every copied byte was verified.
		settingsJson.put("walletsPath", runtimeWallets.toString());
		writeJsonObject(settings, settingsJson);

		boolean cleanupComplete = !legacyExists || deleteTreeBestEffort(legacyWallets);
		return new PreparationResult(runtimeWallets, false, legacyExists, cleanupComplete);
	}

	private static void preflightTree(Path sourceRoot, Path targetRoot) throws IOException {
		for (Path source : pathsUnder(sourceRoot, false)) {
			Path relative = sourceRoot.relativize(source);
			Path target = targetRoot.resolve(relative);
			if (Files.isSymbolicLink(source))
				throw new IOException("wallet migration does not follow symbolic links: " + source);

			if (Files.isDirectory(source, NO_FOLLOW_LINKS)) {
				if (existsNoFollow(target)
						&& (Files.isSymbolicLink(target) || !Files.isDirectory(target, NO_FOLLOW_LINKS)))
					throw new IOException("wallet migration directory conflict: " + target);
				continue;
			}

			if (!Files.isRegularFile(source, NO_FOLLOW_LINKS))
				throw new IOException("unsupported wallet migration entry: " + source);
			if (!existsNoFollow(target))
				continue;
			if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NO_FOLLOW_LINKS)
					|| Files.mismatch(source, target) != -1L)
				throw new IOException("wallet migration file conflict: " + target);
		}
	}

	private static void copyAndVerifyTree(Path sourceRoot, Path targetRoot) throws IOException {
		for (Path source : pathsUnder(sourceRoot, false)) {
			Path target = targetRoot.resolve(sourceRoot.relativize(source));
			if (Files.isDirectory(source, NO_FOLLOW_LINKS)) {
				Files.createDirectories(target);
				continue;
			}
			if (!existsNoFollow(target))
				copyFileAtomically(source, target);
			if (Files.mismatch(source, target) != -1L)
				throw new IOException("wallet migration verification failed: " + target);
		}
	}

	private static void copyFileAtomically(Path source, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = Files.createTempFile(target.getParent(), ".wallet-migration-", ".tmp");
		try {
			copyWithAttributesWhenSupported(source, temporary);
			if (Files.mismatch(source, temporary) != -1L)
				throw new IOException("wallet migration copy verification failed: " + target);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
			temporary = null;
		} finally {
			if (temporary != null)
				Files.deleteIfExists(temporary);
		}
	}

	private static void copyWithAttributesWhenSupported(Path source, Path target) throws IOException {
		try {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		} catch (UnsupportedOperationException e) {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static boolean deleteTreeBestEffort(Path root) throws IOException {
		boolean complete = true;
		for (Path path : pathsUnder(root, true)) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				complete = false;
			}
		}
		return complete && !existsNoFollow(root);
	}

	private static List<Path> pathsUnder(Path root, boolean deepestFirst) throws IOException {
		List<Path> paths;
		try (Stream<Path> stream = Files.walk(root)) {
			paths = new ArrayList<>(stream.toList());
		}
		Comparator<Path> comparator = Comparator.comparingInt(Path::getNameCount)
				.thenComparing(Path::toString);
		paths.sort(deepestFirst ? comparator.reversed() : comparator);
		return paths;
	}

	private static boolean existsNoFollow(Path path) {
		return Files.exists(path, NO_FOLLOW_LINKS);
	}

	private static LinkedHashMap<String, Object> readJsonObject(Path path) throws IOException {
		try {
			LinkedHashMap<String, Object> parsed = JSON_MAPPER.readValue(Files.readAllBytes(path), JSON_OBJECT_TYPE);
			if (parsed == null)
				throw new IOException("settings file does not contain a JSON object: " + path);
			return parsed;
		} catch (IOException e) {
			throw new IOException("cannot parse settings file '" + path + "': " + e.getMessage(), e);
		}
	}

	private static void writeJsonObject(Path path, LinkedHashMap<String, Object> jsonObject) throws IOException {
		Path temporary = Files.createTempFile(path.getParent(), "settings-wallets-", ".tmp");
		try {
			String json = JSON_MAPPER.writeValueAsString(jsonObject) + System.lineSeparator();
			Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
			temporary = null;
		} finally {
			if (temporary != null)
				Files.deleteIfExists(temporary);
		}
	}
}
