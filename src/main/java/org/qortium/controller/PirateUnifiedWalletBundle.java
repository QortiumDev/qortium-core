package org.qortium.controller;

import org.apache.commons.io.FileUtils;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates the pinned Pirate Unified cross-platform native bundle. */
final class PirateUnifiedWalletBundle {

	static final String RELEASE_TAG = "v1.1.8-qortium.3";
	static final String ARTIFACT_FILENAME = "pirate-unified-wallet-qortal-jni-artifacts-v1.1.8-qortium.3.zip";
	static final long ARTIFACT_SIZE = 362_743_273L;
	static final String ARTIFACT_SHA256 =
			"a06bb575929e38b8d6062f0220a71a0a88c25f95a8c90c324a73c1b6950ee0ca";
	static final String RELEASE_URL = "https://github.com/QortiumDev/Pirate-Unified-Light-Wallet/"
			+ "releases/download/" + RELEASE_TAG + "/" + ARTIFACT_FILENAME;
	static final String MANIFEST_FILENAME = "QORTIUM-MANIFEST.txt";

	private static final String FORMAT = "qortium-pirate-unified-bundle-v1";
	private static final long MAX_MANIFEST_SIZE = 64 * 1024L;
	private static final Pattern FILE_PATTERN = Pattern.compile(
			"^file: ([0-9]+) ([0-9a-f]{64})  ([A-Za-z0-9._-]+)$");
	private static final Set<String> NATIVE_LIBRARIES = Set.of(
			"librust-linux-x86_64.so",
			"librust-linux-aarch64.so",
			"librust-macos-x86_64.dylib",
			"librust-macos-aarch64.dylib",
			"librust-windows-x86_64.dll");
	private static final Set<String> BUNDLE_FILES = Set.of(
			"librust-linux-x86_64.so",
			"librust-linux-aarch64.so",
			"librust-macos-x86_64.dylib",
			"librust-macos-aarch64.dylib",
			"librust-windows-x86_64.dll",
			"LICENSE-qortal-jni.txt",
			"qortal-handoff.md");
	private static final Map<String, String> REQUIRED_METADATA = Map.of(
			"format", FORMAT,
			"release-tag", RELEASE_TAG,
			"release-url", RELEASE_URL,
			"artifact-filename", ARTIFACT_FILENAME,
			"artifact-size", Long.toString(ARTIFACT_SIZE),
			"artifact-sha256", ARTIFACT_SHA256,
			"bundle-kind", "cross-platform",
			"platform", "all");

	private PirateUnifiedWalletBundle() {
	}

	static Set<String> nativeLibraries() {
		return NATIVE_LIBRARIES;
	}

	static List<String> bundleFiles() {
		return BUNDLE_FILES.stream().sorted().toList();
	}

	static void validate(Path bundleDirectory, String requiredLibrary) throws DataException {
		inspect(bundleDirectory, requiredLibrary);
	}

	static FileRecord validateAgainstTrustedSource(Path bundleDirectory, Path trustedSource, String requiredLibrary)
			throws DataException {
		BundleRecord trusted = inspect(trustedSource, requiredLibrary);
		BundleRecord candidate = inspect(bundleDirectory, requiredLibrary);
		if (!candidate.manifestSha256().equals(trusted.manifestSha256()))
			throw new DataException("Unified wallet bundle manifest does not match the authenticated QDN source");
		return trusted.files().get(requiredLibrary);
	}

	static void validateSelectedLibrary(Path library, FileRecord expectedRecord) throws DataException {
		if (library == null || expectedRecord == null)
			throw new DataException("Unified wallet library verification is missing trusted input");
		validateFile(library, library.getFileName().toString(), expectedRecord);
	}

	static void install(Path trustedSource, Path targetDirectory, String requiredLibrary)
			throws DataException, IOException {
		inspect(trustedSource, requiredLibrary);
		if (targetDirectory == null || Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new DataException("Unified wallet cache target already exists");
		Path parent = targetDirectory.getParent();
		if (parent == null)
			throw new DataException("Unified wallet cache target has no parent directory");

		Files.createDirectories(parent);
		Path stagingDirectory = Files.createTempDirectory(parent,
				"." + targetDirectory.getFileName() + "-staging-");
		boolean installed = false;
		try {
			for (String filename : bundleFiles())
				Files.copy(trustedSource.resolve(filename), stagingDirectory.resolve(filename));
			Files.copy(trustedSource.resolve(MANIFEST_FILENAME), stagingDirectory.resolve(MANIFEST_FILENAME));
			validateAgainstTrustedSource(stagingDirectory, trustedSource, requiredLibrary);
			Files.move(stagingDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
			installed = true;
		} finally {
			if (!installed)
				FileUtils.deleteDirectory(stagingDirectory.toFile());
		}
	}

	static void validateArtifact(Path artifact, Path bundleDirectory) throws DataException {
		validateArtifact(artifact, bundleDirectory, NATIVE_LIBRARIES.iterator().next(),
				ARTIFACT_SIZE, ARTIFACT_SHA256);
	}

	static FileRecord validateArtifact(Path artifact, Path bundleDirectory, String requiredLibrary)
			throws DataException {
		return validateArtifact(artifact, bundleDirectory, requiredLibrary,
				ARTIFACT_SIZE, ARTIFACT_SHA256).files().get(requiredLibrary);
	}

	static void validateArtifact(Path artifact, Path bundleDirectory, long expectedSize, String expectedSha256)
			throws DataException {
		validateArtifact(artifact, bundleDirectory, NATIVE_LIBRARIES.iterator().next(), expectedSize, expectedSha256);
	}

	private static BundleRecord validateArtifact(Path artifact, Path bundleDirectory, String requiredLibrary,
			long expectedSize, String expectedSha256) throws DataException {
		try {
			if (artifact == null || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(artifact) != expectedSize || !sha256(artifact).equals(expectedSha256))
				throw new DataException("Pirate Unified release artifact does not match the pinned size and SHA-256");

			BundleRecord bundle = inspect(bundleDirectory, requiredLibrary);
			try (ZipFile zipFile = new ZipFile(artifact.toFile())) {
				for (String filename : bundleFiles()) {
					ZipEntry matchingEntry = null;
					int matchingEntries = 0;
					Enumeration<? extends ZipEntry> entries = zipFile.entries();
					while (entries.hasMoreElements()) {
						ZipEntry entry = entries.nextElement();
						if (entry.getName().equals(filename)) {
							matchingEntry = entry;
							matchingEntries++;
						}
					}
					FileRecord expected = bundle.files().get(filename);
					if (matchingEntries != 1 || matchingEntry == null || matchingEntry.isDirectory()
							|| matchingEntry.getSize() != expected.size())
						throw new DataException("Pirate Unified release artifact has an invalid entry: " + filename);
					try (InputStream input = zipFile.getInputStream(matchingEntry)) {
						if (!sha256(input).equals(expected.sha256()))
							throw new DataException("Staged Pirate Unified file differs from the pinned artifact: "
									+ filename);
					}
				}
			}
			return bundle;
		} catch (IOException e) {
			throw new DataException("Unable to validate Pirate Unified release artifact", e);
		}
	}

	private static BundleRecord inspect(Path bundleDirectory, String requiredLibrary) throws DataException {
		if (bundleDirectory == null || !Files.isDirectory(bundleDirectory, LinkOption.NOFOLLOW_LINKS))
			throw new DataException("Unified wallet bundle is not a directory");
		if (!NATIVE_LIBRARIES.contains(requiredLibrary))
			throw new DataException("Unified wallet bundle requested an unsupported platform library");

		Path manifestPath = bundleDirectory.resolve(MANIFEST_FILENAME);
		try {
			if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(manifestPath) > MAX_MANIFEST_SIZE)
				throw new DataException("Unified wallet bundle manifest is missing or too large");
			BasicFileAttributes manifestBefore = Files.readAttributes(manifestPath, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			byte[] manifestBytes = Files.readAllBytes(manifestPath);
			BasicFileAttributes manifestAfter = Files.readAttributes(manifestPath, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!sameFile(manifestBefore, manifestAfter))
				throw new DataException("Unified wallet bundle manifest changed while it was being verified");

			Map<String, String> metadata = new HashMap<>();
			Map<String, FileRecord> expectedFiles = new HashMap<>();
			for (String line : new String(manifestBytes, StandardCharsets.UTF_8).lines().toList())
				parseLine(line, metadata, expectedFiles);

			if (!metadata.equals(REQUIRED_METADATA))
				throw new DataException("Unified wallet bundle manifest provenance does not match the pinned release");
			if (!expectedFiles.keySet().equals(BUNDLE_FILES))
				throw new DataException("Unified wallet bundle manifest inventory is incomplete");

			Set<String> actualFiles = new HashSet<>();
			try (var children = Files.list(bundleDirectory)) {
				for (Path child : children.toList()) {
					if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS))
						throw new DataException("Unified wallet bundle contains a non-regular entry");
					actualFiles.add(child.getFileName().toString());
				}
			}

			Set<String> expectedInventory = new HashSet<>(BUNDLE_FILES);
			expectedInventory.add(MANIFEST_FILENAME);
			if (!actualFiles.equals(expectedInventory))
				throw new DataException("Unified wallet bundle contains an unexpected or missing file");

			for (Map.Entry<String, FileRecord> entry : expectedFiles.entrySet()) {
				validateFile(bundleDirectory.resolve(entry.getKey()), entry.getKey(), entry.getValue());
			}
			return new BundleRecord(Map.copyOf(expectedFiles),
					HexFormat.of().formatHex(Crypto.digest(manifestBytes)));
		} catch (IOException e) {
			throw new DataException("Unable to validate Unified wallet bundle", e);
		}
	}

	private static void validateFile(Path file, String filename, FileRecord expectedRecord) throws DataException {
		try {
			BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!before.isRegularFile() || before.size() != expectedRecord.size())
				throw new DataException("Unified wallet bundle file size mismatch: " + filename);
			String actualHash = sha256(file);
			BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			if (!sameFile(before, after))
				throw new DataException("Unified wallet bundle file changed while it was being verified: " + filename);
			if (!actualHash.equals(expectedRecord.sha256()))
				throw new DataException("Unified wallet bundle file hash mismatch: " + filename);
		} catch (IOException e) {
			throw new DataException("Unable to validate Unified wallet bundle file: " + filename, e);
		}
	}

	private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
		return before.isRegularFile() && after.isRegularFile()
				&& before.size() == after.size()
				&& before.lastModifiedTime().equals(after.lastModifiedTime())
				&& Objects.equals(before.fileKey(), after.fileKey());
	}

	private static String sha256(Path path) throws IOException {
		return HexFormat.of().formatHex(Crypto.digestFileStream(path.toFile()));
	}

	private static String sha256(InputStream input) throws IOException {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[256 * 1024];
			int bytesRead;
			while ((bytesRead = input.read(buffer)) != -1)
				digest.update(buffer, 0, bytesRead);
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 algorithm not available", e);
		}
	}

	private static void parseLine(String line, Map<String, String> metadata, Map<String, FileRecord> expectedFiles)
			throws DataException {
		Matcher fileMatcher = FILE_PATTERN.matcher(line);
		if (fileMatcher.matches()) {
			String filename = fileMatcher.group(3);
			final long size;
			try {
				size = Long.parseLong(fileMatcher.group(1));
			} catch (NumberFormatException e) {
				throw new DataException("Unified wallet bundle manifest has an invalid file size");
			}
			if (!BUNDLE_FILES.contains(filename)
					|| expectedFiles.putIfAbsent(filename, new FileRecord(size, fileMatcher.group(2))) != null)
				throw new DataException("Unified wallet bundle manifest has an invalid or duplicate file entry");
			return;
		}

		int separator = line.indexOf(": ");
		if (separator <= 0)
			throw new DataException("Unified wallet bundle manifest contains an invalid line");
		String key = line.substring(0, separator);
		String value = line.substring(separator + 2);
		if (!REQUIRED_METADATA.containsKey(key) || metadata.putIfAbsent(key, value) != null)
			throw new DataException("Unified wallet bundle manifest has an unknown or duplicate field");
	}

	static String createManifest(Map<String, FileRecord> files) {
		if (files == null || !files.keySet().equals(BUNDLE_FILES))
			throw new IllegalArgumentException("Unified wallet bundle manifest inventory is incomplete");

		List<String> lines = new ArrayList<>();
		lines.add("format: " + FORMAT);
		lines.add("release-tag: " + RELEASE_TAG);
		lines.add("release-url: " + RELEASE_URL);
		lines.add("artifact-filename: " + ARTIFACT_FILENAME);
		lines.add("artifact-size: " + ARTIFACT_SIZE);
		lines.add("artifact-sha256: " + ARTIFACT_SHA256);
		lines.add("bundle-kind: cross-platform");
		lines.add("platform: all");
		files.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
				.map(entry -> "file: " + entry.getValue().size() + " " + entry.getValue().sha256()
						+ "  " + entry.getKey())
				.forEach(lines::add);
		return String.join("\n", lines) + "\n";
	}

	record FileRecord(long size, String sha256) {
		FileRecord {
			if (size < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Invalid Unified wallet bundle file record");
		}
	}

	private record BundleRecord(Map<String, FileRecord> files, String manifestSha256) {
	}
}
