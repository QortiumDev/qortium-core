package org.qortium.controller;

import org.junit.Assume;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PirateUnifiedWalletBundleTests {

	private static final String HOST_LIBRARY = "librust-linux-x86_64.so";

	@Test
	public void testValidCrossPlatformBundle() throws Exception {
		Path bundle = createBundle();

		PirateUnifiedWalletBundle.validate(bundle, HOST_LIBRARY);
		assertEquals(5, PirateUnifiedWalletBundle.nativeLibraries().size());
		assertEquals(7, PirateUnifiedWalletBundle.bundleFiles().size());
	}

	@Test
	public void testEveryPayloadMutationIsRejected() throws Exception {
		for (String filename : PirateUnifiedWalletBundle.bundleFiles()) {
			Path bundle = createBundle();
			Files.writeString(bundle.resolve(filename), "tampered", StandardCharsets.UTF_8);

			DataException exception = assertThrows(DataException.class,
					() -> PirateUnifiedWalletBundle.validate(bundle, HOST_LIBRARY));
			assertTrue(exception.getMessage().contains("mismatch"));
		}
	}

	@Test
	public void testEveryMissingPayloadIsRejected() throws Exception {
		for (String filename : PirateUnifiedWalletBundle.bundleFiles()) {
			Path bundle = createBundle();
			Files.delete(bundle.resolve(filename));

			assertThrows(DataException.class, () -> PirateUnifiedWalletBundle.validate(bundle, HOST_LIBRARY));
		}
	}

	@Test
	public void testExtraFileAndDirectoryAreRejected() throws Exception {
		Path extraFileBundle = createBundle();
		Files.writeString(extraFileBundle.resolve("unexpected.txt"), "unexpected");
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validate(extraFileBundle, HOST_LIBRARY));

		Path directoryBundle = createBundle();
		Files.createDirectory(directoryBundle.resolve("unexpected-directory"));
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validate(directoryBundle, HOST_LIBRARY));
	}

	@Test
	public void testDuplicateAndMalformedManifestEntriesAreRejected() throws Exception {
		Path duplicateBundle = createBundle();
		String manifest = Files.readString(duplicateBundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME));
		String fileLine = manifest.lines().filter(line -> line.endsWith("  " + HOST_LIBRARY)).findFirst().orElseThrow();
		Files.writeString(duplicateBundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME),
				manifest + fileLine + "\n");
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validate(duplicateBundle, HOST_LIBRARY));

		Path traversalBundle = createBundle();
		manifest = Files.readString(traversalBundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME));
		Files.writeString(traversalBundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME),
				manifest.replace("  " + HOST_LIBRARY, "  ../" + HOST_LIBRARY));
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validate(traversalBundle, HOST_LIBRARY));
	}

	@Test
	public void testWrongOfficialProvenanceIsRejected() throws Exception {
		Path bundle = createBundle();
		Path manifest = bundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME);
		Files.writeString(manifest, Files.readString(manifest).replace("release-tag: v1.1.8-qortium.3", "release-tag: v1.1.7"));

		assertThrows(DataException.class, () -> PirateUnifiedWalletBundle.validate(bundle, HOST_LIBRARY));
	}

	@Test
	public void testSymlinkedPayloadIsRejected() throws Exception {
		Path bundle = createBundle();
		Path payload = bundle.resolve(HOST_LIBRARY);
		Path target = Files.createTempFile("pirate-unified-symlink-target", ".so");
		Files.writeString(target, Files.readString(payload));
		Files.delete(payload);
		try {
			Files.createSymbolicLink(payload, target);
		} catch (UnsupportedOperationException | IOException | SecurityException e) {
			Assume.assumeNoException("Symbolic links are unavailable on this filesystem", e);
		}

		assertThrows(DataException.class, () -> PirateUnifiedWalletBundle.validate(bundle, HOST_LIBRARY));
	}

	@Test
	public void testUnsupportedRequestedLibraryIsRejected() throws Exception {
		Path bundle = createBundle();
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validate(bundle, "librust-freebsd-x86_64.so"));
	}

	@Test
	public void testCacheManifestMustMatchTrustedSource() throws Exception {
		Path trustedSource = createBundle();
		Path cacheParent = Files.createTempDirectory("pirate-unified-cache-parent");
		Path cache = cacheParent.resolve("cache");
		PirateUnifiedWalletBundle.install(trustedSource, cache, HOST_LIBRARY);

		PirateUnifiedWalletBundle.validateAgainstTrustedSource(cache, trustedSource, HOST_LIBRARY);
		Files.writeString(cache.resolve(HOST_LIBRARY), "replacement library", StandardCharsets.UTF_8);
		rewriteManifest(cache);
		PirateUnifiedWalletBundle.validate(cache, HOST_LIBRARY);
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validateAgainstTrustedSource(cache, trustedSource, HOST_LIBRARY));
	}

	@Test
	public void testInstallPublishesCompleteBundleAndRefusesOverwrite() throws Exception {
		Path trustedSource = createBundle();
		Path parent = Files.createTempDirectory("pirate-unified-install-parent");
		Path target = parent.resolve("cache");

		PirateUnifiedWalletBundle.install(trustedSource, target, HOST_LIBRARY);
		PirateUnifiedWalletBundle.validateAgainstTrustedSource(target, trustedSource, HOST_LIBRARY);
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.install(trustedSource, target, HOST_LIBRARY));
		try (var children = Files.list(parent)) {
			assertEquals(1, children.count());
		}
	}

	@Test
	public void testStagedPayloadsMustMatchReleaseArchive() throws Exception {
		Path bundle = createBundle();
		Path artifact = createArtifact(bundle);
		long artifactSize = Files.size(artifact);
		String artifactSha256 = sha256(artifact);

		PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, artifactSize, artifactSha256);
		Files.writeString(bundle.resolve(HOST_LIBRARY), "replacement library", StandardCharsets.UTF_8);
		rewriteManifest(bundle);
		assertThrows(DataException.class,
				() -> PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, artifactSize, artifactSha256));
	}

	static Path createBundle() throws Exception {
		Path bundle = Files.createTempDirectory("pirate-unified-bundle");
		Map<String, PirateUnifiedWalletBundle.FileRecord> records = new HashMap<>();
		for (String filename : PirateUnifiedWalletBundle.bundleFiles()) {
			Path file = bundle.resolve(filename);
			Files.writeString(file, "fixture bytes for " + filename, StandardCharsets.UTF_8);
			records.put(filename, new PirateUnifiedWalletBundle.FileRecord(Files.size(file),
					HexFormat.of().formatHex(Crypto.digestFileStream(file.toFile()))));
		}
		Files.writeString(bundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME),
				PirateUnifiedWalletBundle.createManifest(records), StandardCharsets.UTF_8);
		return bundle;
	}

	private static void rewriteManifest(Path bundle) throws Exception {
		Map<String, PirateUnifiedWalletBundle.FileRecord> records = new HashMap<>();
		for (String filename : PirateUnifiedWalletBundle.bundleFiles()) {
			Path file = bundle.resolve(filename);
			records.put(filename, new PirateUnifiedWalletBundle.FileRecord(Files.size(file), sha256(file)));
		}
		Files.writeString(bundle.resolve(PirateUnifiedWalletBundle.MANIFEST_FILENAME),
				PirateUnifiedWalletBundle.createManifest(records), StandardCharsets.UTF_8);
	}

	private static Path createArtifact(Path bundle) throws Exception {
		Path artifact = Files.createTempFile("pirate-unified-artifact", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(artifact))) {
			for (String filename : PirateUnifiedWalletBundle.bundleFiles()) {
				output.putNextEntry(new ZipEntry(filename));
				Files.copy(bundle.resolve(filename), output);
				output.closeEntry();
			}
			output.putNextEntry(new ZipEntry("LiteWalletJni.java"));
			output.write("official source fixture".getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return artifact;
	}

	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(Crypto.digestFileStream(file.toFile()));
	}
}
