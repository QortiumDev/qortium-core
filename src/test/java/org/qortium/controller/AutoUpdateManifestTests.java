package org.qortium.controller;

import org.junit.Test;
import org.qortium.arbitrary.misc.Service;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import static org.junit.Assert.*;

public class AutoUpdateManifestTests {

	@Test
	public void testQdnManifestRoundTripWithPinnedSignature() {
		byte[] commitHash = sequentialBytes(20, 1);
		byte[] updateHash = sequentialBytes(32, 21);
		byte[] binarySignature = sequentialBytes(64, 53);

		AutoUpdateManifest manifest = AutoUpdateManifest.qdnV1(1_700_000_000_000L, commitHash, updateHash, binarySignature);
		AutoUpdateManifest parsed = AutoUpdateManifest.parse(manifest.toBytes());

		assertEquals(1_700_000_000_000L, parsed.getTimestamp());
		assertArrayEquals(commitHash, parsed.getCommitHash());
		assertArrayEquals(updateHash, parsed.getUpdateHash());
		assertArrayEquals(binarySignature, parsed.getBinarySignature());
		assertEquals(Base58.encode(binarySignature), parsed.getBinarySignature58());
		assertEquals("qortium", parsed.getQdnName());
		assertEquals(Service.AUTO_UPDATE_BINARY, parsed.getQdnService());
		assertEquals("0102030405060708090a0b0c0d0e0f1011121314", parsed.getQdnIdentifier());
		assertEquals("qortium.update", parsed.getQdnPath());
	}

	@Test
	public void testQdnManifestRoundTripWithoutPinnedSignature() {
		byte[] commitHash = sequentialBytes(20, 1);
		byte[] updateHash = sequentialBytes(32, 21);

		AutoUpdateManifest parsed = AutoUpdateManifest.parse(AutoUpdateManifest.qdnV1(1L, commitHash, updateHash, null).toBytes());

		assertNull(parsed.getBinarySignature());
		assertNull(parsed.getBinarySignature58());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testLegacyManifestIsRejected() {
		int legacyLength = Transformer.TIMESTAMP_LENGTH + AutoUpdateManifest.GIT_COMMIT_HASH_LENGTH + Transformer.SHA256_LENGTH;
		AutoUpdateManifest.parse(new byte[legacyLength]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMalformedManifestIsRejected() {
		AutoUpdateManifest.parse(new byte[12]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidPinnedSignatureLengthIsRejected() {
		byte[] commitHash = sequentialBytes(20, 1);
		byte[] updateHash = sequentialBytes(32, 21);
		byte[] manifest = AutoUpdateManifest.qdnV1(1L, commitHash, updateHash, null).toBytes();
		manifest[AutoUpdateManifest.QDN_V1_BASE_LENGTH - 1] = 1;

		AutoUpdateManifest.parse(manifest);
	}

	private static byte[] sequentialBytes(int length, int start) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (start + i);

		return bytes;
	}
}
