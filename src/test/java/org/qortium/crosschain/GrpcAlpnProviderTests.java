package org.qortium.crosschain;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.Security;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;

/**
 * Reproduces the ARRR lightwalletd gRPC TLS regression first seen 2026-09-20: on Java 21 with
 * BouncyCastleJsseProvider registered ahead of the JDK's own SunJSSE (every real node does this
 * at startup - see {@code Controller#main}:
 * {@code Security.insertProviderAt(new BouncyCastleProvider(), 0);
 * Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);} - before any wallet/blockchain
 * code runs), building a TLS gRPC channel the exact same way {@link ZcashFamilyLightClient}
 * does used to throw.
 *
 * <p>This test builds the probe channel exactly like {@code ZcashFamilyLightClient#buildProbeChannel}:
 * {@code ManagedChannelBuilder.forAddress(host, port).useTransportSecurity().build()}. grpc-netty
 * builds the TLS {@code SslContext} eagerly inside {@code build()} (not lazily on first RPC), so
 * this reproduces the failure without needing a live server.
 *
 * <p>Root cause (bisected 2026-09-21): NOT the grpc.version 1.83.1&rarr;1.84.0 bump (#313) or the
 * netty.version 4.2.17.Final&rarr;4.2.18.Final bump (#320) - both grpc-netty's {@code GrpcSslContexts}/
 * {@code JettyTlsUtil} sources and netty's behaviour are unaffected by either. Bisecting each
 * dependency independently (with this same BCJSSE-first provider setup) showed the failure
 * persists even at the full pre-regression baseline (grpc 1.83.1 + netty 4.2.17.Final) and
 * disappears only when bouncycastle.version is held at 1.85. The real trigger is the
 * bouncycastle.version 1.85&rarr;1.86 bump (#319, merged in the same window but not by either PR
 * named above): bctls-jdk18on 1.86's Multi-Release JAR dropped the {@code META-INF/versions/9}
 * override of {@code ProvSSLContextSpi} that 1.85 shipped. On Java 9+, the JVM still resolves the
 * versioned {@code SSLEngineUtil} (whose {@code create(ContextData)} returns {@code ProvSSLEngine}),
 * but falls back to the un-versioned {@code ProvSSLContextSpi} (compiled against the base
 * {@code SSLEngineUtil} overload returning plain {@code SSLEngine}) - a descriptor mismatch that
 * throws {@code NoSuchMethodError} the moment BCJSSE is asked for a JDK-provider SSLEngine
 * ({@code SSLContext#createSSLEngine}), which grpc-netty's {@code JettyTlsUtil.isJava9AlpnAvailable()}
 * does as its generic ALPN probe. That failure is cached process-wide (a static holder), so
 * every provider grpc-netty subsequently checks - including the JDK's own working SunJSSE further
 * down the provider list - is wrongly rejected too, and {@code GrpcSslContexts.defaultSslProvider()}
 * throws "Could not find TLS ALPN provider; no working netty-tcnative, Conscrypt, or Jetty
 * NPN/ALPN available" - exactly the owner's reported symptom.
 */
public class GrpcAlpnProviderTests {

	@BeforeClass
	public static void registerNodeStartupProviders() {
		// Mirrors org.qortium.controller.Controller#main exactly, so this test sees the same
		// Security provider ordering as the running node.
		if (Security.getProvider("BC") == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 0);
		if (Security.getProvider("BCJSSE") == null)
			Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	@Test
	public void tlsProbeChannelBuildsOnJava21() throws Exception {
		// Same call as ZcashFamilyLightClient#buildProbeChannel for an SSL server: a plain
		// ManagedChannelBuilder with useTransportSecurity(), resolved via ServiceLoader to
		// grpc-netty(-shaded)'s NettyChannelProvider. The port need not be reachable - the ALPN
		// SslContext is built synchronously inside build(), before any socket connects.
		ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", 1)
				.useTransportSecurity()
				.build();
		try {
			assertNotNull(channel);
		} finally {
			channel.shutdownNow();
			channel.awaitTermination(5, TimeUnit.SECONDS);
		}
	}
}
