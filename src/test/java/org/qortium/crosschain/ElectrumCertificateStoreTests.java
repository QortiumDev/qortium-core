package org.qortium.crosschain;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ElectrumCertificateStoreTests {

	private static final String STORE_FILENAME = "electrum-tls-fingerprints.json";
	private static final String FINGERPRINT =
			"1111111111111111111111111111111111111111111111111111111111111111";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void testRecordAndRetrieve() throws Exception {
		Path storePath = temporaryFolder.newFolder().toPath().resolve("electrum-tls-fingerprints.json");
		ElectrumCertificateStore store = ElectrumCertificateStore.forPath(storePath);

		assertNull(store.getFingerprint("electrum.example", 50002));

		store.recordFingerprint("electrum.example", 50002, "AA:BB:CC");

		// Fingerprints are normalised (colons stripped, lower-cased).
		assertEquals("aabbcc", store.getFingerprint("electrum.example", 50002));
		// Host lookups are case-insensitive.
		assertEquals("aabbcc", store.getFingerprint("ELECTRUM.EXAMPLE", 50002));
		// The port is part of the identity.
		assertNull(store.getFingerprint("electrum.example", 50001));
	}

	@Test
	public void testPersistsAcrossInstances() throws Exception {
		Path storePath = temporaryFolder.newFolder().toPath().resolve(STORE_FILENAME);

		ElectrumCertificateStore store = ElectrumCertificateStore.forPath(storePath);
		store.recordFingerprint("electrum.example", 50002, FINGERPRINT);

		// A fresh instance backed by the same file must see the recorded fingerprint.
		ElectrumCertificateStore reloaded = ElectrumCertificateStore.forPath(storePath);
		assertEquals(FINGERPRINT, reloaded.getFingerprint("electrum.example", 50002));
	}

	@Test
	public void testInvalidFingerprintIgnored() throws Exception {
		Path storePath = temporaryFolder.newFolder().toPath().resolve("electrum-tls-fingerprints.json");
		ElectrumCertificateStore store = ElectrumCertificateStore.forPath(storePath);

		store.recordFingerprint("electrum.example", 50002, "   ");

		assertNull(store.getFingerprint("electrum.example", 50002));
	}

	@Test
	public void testMigratesLegacyStoreWithoutLosingPins() throws Exception {
		Path rootPath = temporaryFolder.newFolder().toPath();
		Path legacyPath = rootPath.resolve("lists").resolve(STORE_FILENAME);
		Path newPath = rootPath.resolve("crosschain").resolve(STORE_FILENAME);

		ElectrumCertificateStore.forPath(legacyPath)
				.recordFingerprint("electrum.example", 50002, FINGERPRINT);

		ElectrumCertificateStore.migrateLegacyStore(legacyPath, newPath);

		assertFalse(Files.exists(legacyPath));
		assertTrue(Files.exists(newPath));
		assertEquals(FINGERPRINT,
				ElectrumCertificateStore.forPath(newPath).getFingerprint("electrum.example", 50002));
	}

	@Test
	public void testFailedMigrationPreservesLegacyPins() throws Exception {
		Path rootPath = temporaryFolder.newFolder().toPath();
		Path legacyPath = rootPath.resolve("lists").resolve(STORE_FILENAME);
		Path blockedParent = Files.createFile(rootPath.resolve("crosschain"));
		Path newPath = blockedParent.resolve(STORE_FILENAME);

		ElectrumCertificateStore.forPath(legacyPath)
				.recordFingerprint("electrum.example", 50002, FINGERPRINT);

		ElectrumCertificateStore.migrateLegacyStore(legacyPath, newPath);

		assertTrue(Files.exists(legacyPath));
		assertFalse(Files.exists(newPath));
		assertEquals(FINGERPRINT,
				ElectrumCertificateStore.forPath(legacyPath).getFingerprint("electrum.example", 50002));
	}
}
