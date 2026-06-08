package org.qortium.crypto;

import org.junit.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElectrumSSLSocketFactoryTests {

	@Test
	public void testNormalizeSha256Fingerprint() {
		String fingerprint = "AA:BB CC";

		assertEquals("aabbcc", ElectrumSSLSocketFactory.normalizeSha256Fingerprint(fingerprint));
	}

	@Test
	public void testCertificateMatchesFingerprint() throws Exception {
		byte[] encoded = "certificate bytes".getBytes(StandardCharsets.UTF_8);
		X509Certificate certificate = new EncodedCertificate(encoded);
		String fingerprint = sha256Hex(encoded);

		assertTrue(ElectrumSSLSocketFactory.certificateMatchesFingerprint(certificate, fingerprint));
		assertTrue(ElectrumSSLSocketFactory.certificateMatchesFingerprint(certificate, colonSeparated(fingerprint).toUpperCase()));
		assertFalse(ElectrumSSLSocketFactory.certificateMatchesFingerprint(certificate,
				"0000000000000000000000000000000000000000000000000000000000000000"));
	}

	private static String sha256Hex(byte[] data) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte value : digest)
			hex.append(String.format("%02x", value));

		return hex.toString();
	}

	private static String colonSeparated(String fingerprint) {
		StringBuilder formatted = new StringBuilder();
		for (int i = 0; i < fingerprint.length(); i += 2) {
			if (i > 0)
				formatted.append(':');

			formatted.append(fingerprint, i, i + 2);
		}

		return formatted.toString();
	}

	private static class EncodedCertificate extends X509Certificate {
		private final byte[] encoded;

		private EncodedCertificate(byte[] encoded) {
			this.encoded = encoded;
		}

		@Override
		public byte[] getEncoded() throws CertificateEncodingException {
			return this.encoded;
		}

		@Override
		public void verify(PublicKey key) {
		}

		@Override
		public void verify(PublicKey key, String sigProvider) {
		}

		@Override
		public String toString() {
			return "EncodedCertificate";
		}

		@Override
		public PublicKey getPublicKey() {
			return null;
		}

		@Override
		public void checkValidity() {
		}

		@Override
		public void checkValidity(Date date) {
		}

		@Override
		public int getVersion() {
			return 3;
		}

		@Override
		public BigInteger getSerialNumber() {
			return BigInteger.ONE;
		}

		@Override
		public Principal getIssuerDN() {
			return new X500Principal("CN=issuer");
		}

		@Override
		public Principal getSubjectDN() {
			return new X500Principal("CN=subject");
		}

		@Override
		public Date getNotBefore() {
			return new Date(0);
		}

		@Override
		public Date getNotAfter() {
			return new Date(Long.MAX_VALUE);
		}

		@Override
		public byte[] getTBSCertificate() {
			return new byte[0];
		}

		@Override
		public byte[] getSignature() {
			return new byte[0];
		}

		@Override
		public String getSigAlgName() {
			return "NONE";
		}

		@Override
		public String getSigAlgOID() {
			return "0.0";
		}

		@Override
		public byte[] getSigAlgParams() {
			return new byte[0];
		}

		@Override
		public boolean[] getIssuerUniqueID() {
			return null;
		}

		@Override
		public boolean[] getSubjectUniqueID() {
			return null;
		}

		@Override
		public boolean[] getKeyUsage() {
			return null;
		}

		@Override
		public int getBasicConstraints() {
			return -1;
		}

		@Override
		public Set<String> getCriticalExtensionOIDs() {
			return null;
		}

		@Override
		public Set<String> getNonCriticalExtensionOIDs() {
			return null;
		}

		@Override
		public byte[] getExtensionValue(String oid) {
			return null;
		}

		@Override
		public boolean hasUnsupportedCriticalExtension() {
			return false;
		}
	}
}
