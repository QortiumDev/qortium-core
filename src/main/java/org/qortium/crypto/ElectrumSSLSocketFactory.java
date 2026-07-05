package org.qortium.crypto;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ElectrumCertificateStore;
import org.qortium.crosschain.ElectrumX;
import org.qortium.settings.Settings;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
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

	/**
	 * How ElectrumX SSL server certificates are trusted.
	 * <p>
	 * A server with an explicit pinned {@code certificateSha256Fingerprint} is always pinned to that
	 * fingerprint regardless of mode. This mode only governs SSL servers that have no explicit pin.
	 */
	public enum TrustMode {
		/** Require a publicly trusted certificate chain (JVM default trust store). */
		STRICT,
		/** Only connect to servers that carry an explicit pinned fingerprint; reject everything else. */
		PINNED_ONLY,
		/** Trust on first use: record the leaf certificate on the first connection and pin to it thereafter. */
		TOFU
	}

	private static final Logger LOGGER = LogManager.getLogger(ElectrumSSLSocketFactory.class);

	private static final String[] SECURE_TLS_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };
	private static final SSLSocketFactory DEFAULT_SOCKET_FACTORY = (SSLSocketFactory) SSLSocketFactory.getDefault();
	private static final int TOFU_PROBE_TIMEOUT_MS = 5000;

	public static SSLSocketFactory getSocketFactory(ChainableServer server) throws IOException {
		String fingerprint = getPinnedCertificateSha256Fingerprint(server);
		if (fingerprint != null)
			return createPinnedSocketFactory(fingerprint);

		switch (resolveTrustMode()) {
			case PINNED_ONLY:
				throw new IOException("Electrum TLS trust mode PINNED_ONLY rejects unpinned server " + describe(server));

			case TOFU:
				return getTrustOnFirstUseSocketFactory(server);

			case STRICT:
			default:
				return DEFAULT_SOCKET_FACTORY;
		}
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

	/**
	 * Connect to a server only long enough to record its leaf TLS certificate SHA-256 fingerprint.
	 * <p>
	 * The certificate is never trusted: the capturing trust manager records the leaf fingerprint and then
	 * deliberately aborts the handshake. This is intended for offline server-list generation/pinning and for
	 * the first-use step of {@link TrustMode#TOFU}, not for trusting otherwise-unverified servers at runtime.
	 *
	 * @return the lower-case hex SHA-256 fingerprint of the server's leaf certificate, or {@code null} if none was presented
	 */
	public static String probeCertificateSha256Fingerprint(String host, int port, int timeoutMs) throws IOException {
		CapturingTrustManager capturing = new CapturingTrustManager();
		SSLSocketFactory factory = createCapturingSocketFactory(capturing);

		Socket baseSocket = new Socket();
		try {
			baseSocket.connect(new InetSocketAddress(host, port), timeoutMs);
			baseSocket.setSoTimeout(timeoutMs);

			SSLSocket sslSocket = null;
			try {
				sslSocket = (SSLSocket) factory.createSocket(baseSocket, host, port, true);
				configureSocket(sslSocket);
				sslSocket.startHandshake();
			} catch (IOException e) {
				// Expected: the capturing trust manager aborts the handshake once it has recorded the leaf certificate.
			} finally {
				closeQuietly(sslSocket);
			}
		} finally {
			closeQuietly(baseSocket);
		}

		return capturing.getCapturedFingerprint();
	}

	static boolean certificateMatchesFingerprint(X509Certificate certificate, String expectedFingerprint) throws CertificateEncodingException {
		String normalisedExpected = normalizeSha256Fingerprint(expectedFingerprint);
		if (normalisedExpected == null)
			return false;

		return sha256Hex(certificate.getEncoded()).equals(normalisedExpected);
	}

	private static TrustMode resolveTrustMode() {
		Settings settings = Settings.getLoadedInstance();
		// When no settings are loaded (tooling/tests) fall back to strict validation rather than reaching the network.
		return settings == null ? TrustMode.STRICT : settings.getElectrumTlsTrustMode();
	}

	private static SSLSocketFactory getTrustOnFirstUseSocketFactory(ChainableServer server) throws IOException {
		ElectrumCertificateStore store = ElectrumCertificateStore.getDefault();
		if (store == null)
			// No place to persist a first-use decision; fall back to strict validation rather than trusting blindly.
			return DEFAULT_SOCKET_FACTORY;

		String host = server.getHostName();
		int port = server.getPort();

		String knownFingerprint = store.getFingerprint(host, port);
		if (knownFingerprint != null)
			return createPinnedSocketFactory(knownFingerprint);

		String capturedFingerprint = probeCertificateSha256Fingerprint(host, port, TOFU_PROBE_TIMEOUT_MS);
		if (capturedFingerprint == null)
			throw new IOException("Unable to capture Electrum TLS certificate for " + describe(server));

		store.recordFingerprint(host, port, capturedFingerprint);
		LOGGER.info("Trusting Electrum TLS certificate for {} on first use", describe(server));
		return createPinnedSocketFactory(capturedFingerprint);
	}

	private static String getPinnedCertificateSha256Fingerprint(ChainableServer server) {
		if (server instanceof ElectrumX.Server)
			return normalizeSha256Fingerprint(((ElectrumX.Server) server).getCertificateSha256Fingerprint());

		return null;
	}

	private static SSLSocketFactory createPinnedSocketFactory(String fingerprint) throws IOException {
		try {
			// TLSv1.3 context also enables TLSv1.2, but not legacy TLS/SSL versions
			SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
			sslContext.init(null, new TrustManager[] { new PinnedCertificateTrustManager(fingerprint) }, new SecureRandom());
			return sslContext.getSocketFactory();
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to create pinned Electrum TLS socket factory", e);
		}
	}

	private static SSLSocketFactory createCapturingSocketFactory(CapturingTrustManager capturing) throws IOException {
		try {
			// TLSv1.3 context also enables TLSv1.2, but not legacy TLS/SSL versions
			SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
			sslContext.init(null, new TrustManager[] { capturing }, new SecureRandom());
			return sslContext.getSocketFactory();
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to create capturing Electrum TLS socket factory", e);
		}
	}

	private static String describe(ChainableServer server) {
		return server.getHostName() + ":" + server.getPort();
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null)
			try {
				socket.close();
			} catch (IOException e) {
				// Best effort.
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

	/**
	 * Records the server's leaf certificate fingerprint and then aborts the handshake.
	 * <p>
	 * This trust manager never trusts a certificate: both {@code checkClientTrusted} and
	 * {@code checkServerTrusted} always throw, so it cannot be used to bypass validation. Its only purpose is
	 * to let {@link #probeCertificateSha256Fingerprint} observe the presented leaf certificate.
	 */
	private static final class CapturingTrustManager implements X509TrustManager {
		private volatile String capturedFingerprint;

		private String getCapturedFingerprint() {
			return this.capturedFingerprint;
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		@Override
		public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
			throw new CertificateException("Electrum TLS client authentication is not supported");
		}

		@Override
		public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
			if (certs != null && certs.length > 0)
				this.capturedFingerprint = sha256Hex(certs[0].getEncoded());

			// Never trust the certificate: we only needed to record it, so abort the handshake.
			throw new CertificateException("Electrum TLS certificate captured for fingerprint pinning");
		}
	}
}
