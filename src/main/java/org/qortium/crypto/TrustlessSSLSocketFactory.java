package org.qortium.crypto;

import javax.net.ssl.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.security.cert.X509Certificate;

public abstract class TrustlessSSLSocketFactory {

	private static final String[] SECURE_TLS_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

	/**
	 * Creates a SSLSocketFactory that ignore certificate chain validation because ElectrumX servers use mostly
	 * self signed certificates.
	 */
	private static final TrustManager[] TRUSTLESS_MANAGER = new TrustManager[] {
		new X509TrustManager() {
			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(X509Certificate[] certs, String authType) {
			}
			public void checkServerTrusted(X509Certificate[] certs, String authType) {
			}
		}
	};

	/**
	 * Install the all-trusting trust manager.
	 */
	private static final SSLContext sc;
	static {
		try {
			sc = SSLContext.getInstance("TLS");
			sc.init(null, TRUSTLESS_MANAGER, new java.security.SecureRandom());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static SSLSocketFactory getSocketFactory() {
		return sc.getSocketFactory();
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
}
