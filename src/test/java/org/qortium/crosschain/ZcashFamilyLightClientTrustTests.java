package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.Service.LightdInfo;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ZcashFamilyLightClientTrustTests {

	@Test
	public void testMaliciousFirstHeightCannotPoisonHonestCorroboration() {
		ZcashFamilyLightClient.ServerHeightTracker tracker = new ZcashFamilyLightClient.ServerHeightTracker();
		ChainableServer malicious = server("malicious.example");
		ChainableServer honestOne = server("honest-one.example");
		ChainableServer honestTwo = server("honest-two.example");

		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
				tracker.assess(malicious, 50_000_000L));
		assertNull(tracker.getTrustedReferenceHeight());

		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
				tracker.assess(honestOne, 2_000_000L));
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_TRUSTED,
				tracker.assess(honestTwo, 2_000_004L));
		assertEquals(Long.valueOf(2_000_004L), tracker.getTrustedReferenceHeight());

		assertEquals(ZcashFamilyLightClient.HeightAssessment.IMPLAUSIBLY_AHEAD,
				tracker.assess(malicious, 50_000_000L));
	}

	@Test
	public void testStaleServerRejectedAfterReferenceCorroboration() {
		ZcashFamilyLightClient.ServerHeightTracker tracker = new ZcashFamilyLightClient.ServerHeightTracker();
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
				tracker.assess(server("reference-one.example"), 2_100_000L));
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_TRUSTED,
				tracker.assess(server("reference-two.example"), 2_100_010L));

		assertEquals(ZcashFamilyLightClient.HeightAssessment.STALE,
				tracker.assess(server("stale.example"), 1_900_000L));
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_TRUSTED,
				tracker.assess(server("near-tip.example"), 2_100_020L));
	}

	@Test
	public void testOneServerCannotCorroborateItself() {
		ZcashFamilyLightClient.ServerHeightTracker tracker = new ZcashFamilyLightClient.ServerHeightTracker();
		ChainableServer server = server("single.example");
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
				tracker.assess(server, 2_000_000L));
		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
				tracker.assess(server, 2_000_001L));
		assertNull(tracker.getTrustedReferenceHeight());
	}

	@Test
	public void testTrustedHeightCannotRatchetFromOneServer() {
		ZcashFamilyLightClient.ServerHeightTracker tracker = new ZcashFamilyLightClient.ServerHeightTracker();
		tracker.assess(server("reference-one.example"), 2_000_000L);
		tracker.assess(server("reference-two.example"), 2_000_005L);
		assertEquals(Long.valueOf(2_000_005L), tracker.getTrustedReferenceHeight());

		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_TRUSTED,
				tracker.assess(server("advance-one.example"), 2_050_000L));
		assertEquals(Long.valueOf(2_000_005L), tracker.getTrustedReferenceHeight());

		assertEquals(ZcashFamilyLightClient.HeightAssessment.ACCEPTED_TRUSTED,
				tracker.assess(server("advance-two.example"), 2_050_010L));
		assertEquals(Long.valueOf(2_050_010L), tracker.getTrustedReferenceHeight());
	}

	@Test
	public void testChainIdentityMustMatchConfiguredNetwork() {
		assertTrue(ZcashFamilyLightClient.matchesExpectedChainName("main", "main"));
		assertTrue(ZcashFamilyLightClient.matchesExpectedChainName("main", "MAIN"));
		assertTrue(ZcashFamilyLightClient.matchesExpectedChainName(null, null));
		assertFalse(ZcashFamilyLightClient.matchesExpectedChainName("main", "test"));
		assertFalse(ZcashFamilyLightClient.matchesExpectedChainName("main", ""));
		assertFalse(ZcashFamilyLightClient.matchesExpectedChainName("main", null));
		assertTrue(ZcashFamilyLightClient.matchesExpectedChainName("regtest", "regtest"));
		assertTrue(ZcashFamilyLightClient.requiresBirthdayFloor("main"));
		assertFalse(ZcashFamilyLightClient.requiresBirthdayFloor("test"));
		assertFalse(ZcashFamilyLightClient.requiresBirthdayFloor("regtest"));
	}

	@Test
	public void testFreshRegtestProbeDoesNotUseMainnetBirthdayFloor() throws Exception {
		FakeManagedChannel channel = new FakeManagedChannel();
		LightdInfo info = LightdInfo.newBuilder().setChainName("regtest").setBlockHeight(1).build();
		ProbeLightClient client = new ProbeLightClient(channel, info, "regtest", 2_000_000);

		Optional<ChainableServerConnection> connection = client.setCurrentServer(server("regtest.example"), "test");
		assertTrue(connection.isPresent());
		assertTrue(connection.get().isSuccess());
		assertFalse(channel.isShutdown());
	}

	@Test
	public void testFailedProbePathsCloseTemporaryChannel() throws Exception {
		assertFailedProbeClosed(null);
		assertFailedProbeClosed(LightdInfo.newBuilder().setChainName("main").setBlockHeight(0).build());
		assertFailedProbeClosed(new IllegalStateException("probe failed"));
	}

	private static void assertFailedProbeClosed(Object outcome) throws Exception {
		FakeManagedChannel channel = new FakeManagedChannel();
		ProbeLightClient client = new ProbeLightClient(channel, outcome, "main", 1);
		Optional<ChainableServerConnection> connection = client.setCurrentServer(server("probe.example"), "test");

		assertTrue(connection.isPresent());
		assertFalse(connection.get().isSuccess());
		assertTrue(channel.isShutdown());
		assertTrue(channel.isTerminated());
		assertNull(client.getCurrentServer());
	}

	private static ChainableServer server(String hostname) {
		return new ZcashFamilyLightClient.Server(hostname, ChainableServer.ConnectionType.SSL, 443);
	}

	private static final class ProbeLightClient extends ZcashFamilyLightClient {
		private final ManagedChannel channel;
		private final Object outcome;

		private ProbeLightClient(ManagedChannel channel, Object outcome, String expectedChainName, int birthday) {
			super(new ZcashFamilyWalletConfig("Test", "TEST", "Test", "signature", "encryption", "zs",
					() -> birthday, () -> null), "test", expectedChainName, Collections.emptyList(), ports(), () -> birthday);
			this.channel = channel;
			this.outcome = outcome;
		}

		@Override
		protected ManagedChannel buildProbeChannel(ChainableServer server) {
			return this.channel;
		}

		@Override
		protected LightdInfo fetchLightdInfo(ManagedChannel probeChannel) {
			if (this.outcome instanceof RuntimeException runtimeException)
				throw runtimeException;
			return (LightdInfo) this.outcome;
		}

		private static Map<ChainableServer.ConnectionType, Integer> ports() {
			Map<ChainableServer.ConnectionType, Integer> ports = new EnumMap<>(ChainableServer.ConnectionType.class);
			ports.put(ChainableServer.ConnectionType.SSL, 443);
			return ports;
		}
	}

	private static final class FakeManagedChannel extends ManagedChannel {
		private boolean shutdown;
		private boolean terminated;

		@Override
		public ManagedChannel shutdown() {
			this.shutdown = true;
			this.terminated = true;
			return this;
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown;
		}

		@Override
		public boolean isTerminated() {
			return this.terminated;
		}

		@Override
		public ManagedChannel shutdownNow() {
			this.shutdown = true;
			this.terminated = true;
			return this;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return this.terminated;
		}

		@Override
		public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
				MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String authority() {
			return "test";
		}
	}
}
