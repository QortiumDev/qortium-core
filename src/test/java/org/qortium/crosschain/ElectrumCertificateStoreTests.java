package org.qortium.crosschain;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ElectrumCertificateStoreTests {

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
		Path storePath = temporaryFolder.newFolder().toPath().resolve("electrum-tls-fingerprints.json");

		ElectrumCertificateStore store = ElectrumCertificateStore.forPath(storePath);
		store.recordFingerprint("electrum.example", 50002,
				"1111111111111111111111111111111111111111111111111111111111111111");

		// A fresh instance backed by the same file must see the recorded fingerprint.
		ElectrumCertificateStore reloaded = ElectrumCertificateStore.forPath(storePath);
		assertEquals("1111111111111111111111111111111111111111111111111111111111111111",
				reloaded.getFingerprint("electrum.example", 50002));
	}

	@Test
	public void testInvalidFingerprintIgnored() throws Exception {
		Path storePath = temporaryFolder.newFolder().toPath().resolve("electrum-tls-fingerprints.json");
		ElectrumCertificateStore store = ElectrumCertificateStore.forPath(storePath);

		store.recordFingerprint("electrum.example", 50002, "   ");

		assertNull(store.getFingerprint("electrum.example", 50002));
	}
}
