package org.qortium.crypto;

import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ElectrumX;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class ElectrumSSLSocketFactory {

	private static final String[] SECURE_TLS_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };
	private static final SSLSocketFactory DEFAULT_SOCKET_FACTORY = (SSLSocketFactory) SSLSocketFactory.getDefault();

	public static SSLSocketFactory getSocketFactory(ChainableServer server) throws IOException {
		String fingerprint = getPinnedCertificateSha256Fingerprint(server);
		if (fingerprint == null)
			return DEFAULT_SOCKET_FACTORY;

		return createPinnedSocketFactory(fingerprint);
	}

	public static void configureSocket(SSLSocket socket) {
		Set<String> supportedProtocols = Set.of(socket.getSupportedProtocols());
		List<String> enabledProtocols = new ArrayList<>();

		for (String protocol : SECURE_TLS_PROTOCOLS)
			if (supportedProtocols.contains(protocol))
				enabledProtocols.add(protocol);

		if (!enabledProtocols.isEmpty())
			socket.setEnabledProtocols(enabledProtocols.toArray(String[]::new));
	}

	public static String normalizeSha256Fingerprint(String fingerprint) {
		if (fingerprint == null)
			return null;

		String normalised = fingerprint.trim()
				.replace(":", "")
				.replace(" ", "")
				.toLowerCase(Locale.ROOT);

		return normalised.isEmpty() ? null : normalised;
	}

	static boolean certificateMatchesFingerprint(X509Certificate certificate, String expectedFingerprint) throws CertificateEncodingException {
		String normalisedExpected = normalizeSha256Fingerprint(expectedFingerprint);
		if (normalisedExpected == null)
			return false;

		return sha256Hex(certificate.getEncoded()).equals(normalisedExpected);
	}

	private static String getPinnedCertificateSha256Fingerprint(ChainableServer server) {
		if (server instanceof ElectrumX.Server)
			return normalizeSha256Fingerprint(((ElectrumX.Server) server).getCertificateSha256Fingerprint());

		return null;
	}

	private static SSLSocketFactory createPinnedSocketFactory(String fingerprint) throws IOException {
		try {
			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, new TrustManager[] { new PinnedCertificateTrustManager(fingerprint) }, new SecureRandom());
			return sslContext.getSocketFactory();
		} catch (Exception e) {
			throw new IOException("Unable to create pinned Electrum TLS socket factory", e);
		}
	}

	private static String sha256Hex(byte[] data) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte value : digest)
				hex.append(String.format("%02x", value));

			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private static class PinnedCertificateTrustManager implements X509TrustManager {
		private final String fingerprint;

		private PinnedCertificateTrustManager(String fingerprint) {
			this.fingerprint = normalizeSha256Fingerprint(fingerprint);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		@Override
		public void checkClientTrusted(X509Certificate[] certs, String authType) {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
			if (certs == null || certs.length == 0)
				throw new CertificateException("Electrum TLS server did not provide a certificate");

			if (!certificateMatchesFingerprint(certs[0], this.fingerprint))
				throw new CertificateException("Electrum TLS certificate fingerprint mismatch");
		}
	}
}
