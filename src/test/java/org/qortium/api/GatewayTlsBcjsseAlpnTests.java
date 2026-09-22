package org.qortium.api;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.security.Security;

import static org.junit.Assert.assertNotNull;

/**
 * Reproduces the gateway-service startup regression first seen 2026-09-20/21: on the owner's
 * node, {@code Controller.main} died with an uncaught {@code NoSuchMethodError} before it ever
 * reached {@code Gui.notifyRunning()}, leaving the node half-initialised (API up, gateway down,
 * splash stuck):
 *
 * <pre>
 * Exception in thread "main" java.lang.NoSuchMethodError:
 *     'javax.net.ssl.SSLEngine org.bouncycastle.jsse.provider.SSLEngineUtil.create(org.bouncycastle.jsse.provider.ContextData)'
 *     at org.bouncycastle.jsse.provider.ProvSSLContextSpi.engineCreateSSLEngine(Unknown Source)
 *     at javax.net.ssl.SSLContext.createSSLEngine(SSLContext.java:373)
 *     at org.eclipse.jetty.util.ssl.SslContextFactory.checkConfiguration(SslContextFactory.java)
 *     at org.qortium.api.GatewayService.start(GatewayService.java:167)
 *     at org.qortium.controller.Controller.main(Controller.java:712)
 * </pre>
 *
 * <p>{@link GatewayService#start()} builds its TLS {@code SSLContext} explicitly from BCJSSE
 * ({@code SSLContext.getInstance("TLS", "BCJSSE")}), then hands it to Jetty's
 * {@code SslContextFactory.Server} via {@code setSslContext(...)}. Jetty's own
 * {@code SslContextFactory.checkConfiguration()} sanity-checks that context by calling
 * {@code sslContext.createSSLEngine()} - exactly what this test does directly, without needing a
 * real keystore, since the failure has nothing to do with key material: it is a Multi-Release-JAR
 * defect in bctls-jdk18on 1.86 (see the {@code bouncycastle.version} comment in {@code pom.xml}
 * and {@link org.qortium.crosschain.GrpcAlpnProviderTests}, which reproduces the same underlying
 * bug via grpc-netty's JDK ALPN probe instead of Jetty's gateway TLS).
 *
 * <p>Every real node registers BouncyCastleProvider/BouncyCastleJsseProvider ahead of the JDK's
 * own SunJSSE before any of this runs ({@code Controller#main}:
 * {@code Security.insertProviderAt(new BouncyCastleProvider(), 0);
 * Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);}), so this test mirrors that
 * exactly.
 *
 * <p>Verified by flipping {@code bouncycastle.version} in {@code pom.xml} and rerunning this
 * test alone (2026-09-21): fails with the {@code NoSuchMethodError} above at 1.86, passes at
 * 1.85 - the version now pinned in {@code pom.xml}.
 */
public class GatewayTlsBcjsseAlpnTests {

	@BeforeClass
	public static void registerNodeStartupProviders() {
		// Mirrors org.qortium.controller.Controller#main exactly.
		if (Security.getProvider("BC") == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 0);
		if (Security.getProvider("BCJSSE") == null)
			Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	@Test
	public void bcjsseSslEngineCreationSucceedsOnJava21() throws Exception {
		// Same provider name GatewayService.start() requests explicitly, and the same no-arg
		// createSSLEngine() call Jetty's SslContextFactory.checkConfiguration() makes.
		SSLContext sslContext = SSLContext.getInstance("TLS", "BCJSSE");
		sslContext.init(null, null, null);

		SSLEngine engine = sslContext.createSSLEngine();

		assertNotNull(engine);
	}
}
