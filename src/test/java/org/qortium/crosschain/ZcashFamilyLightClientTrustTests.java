package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.Service.BlockID;
import cash.z.wallet.sdk.rpc.Service.LightdInfo;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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

	@Test
	public void testProbeRejectsUnavailableOrDivergentLatestBlock() throws Exception {
		LightdInfo info = LightdInfo.newBuilder().setChainName("main").setBlockHeight(2_100_000).build();

		FakeManagedChannel unavailableChannel = new FakeManagedChannel();
		ProbeLightClient unavailable = new ProbeLightClient(unavailableChannel, info, "main", 1,
				new IllegalStateException("cache unavailable"));
		Optional<ChainableServerConnection> unavailableConnection =
				unavailable.setCurrentServer(server("unavailable.example"), "test");
		assertTrue(unavailableConnection.isPresent());
		assertFalse(unavailableConnection.get().isSuccess());
		assertTrue(unavailableChannel.isTerminated());
		assertFalse(unavailable.getUselessServers().contains(server("unavailable.example")));

		FakeManagedChannel divergentChannel = new FakeManagedChannel();
		ProbeLightClient divergent = new ProbeLightClient(divergentChannel, info, "main", 1, 2_300_000L);
		Optional<ChainableServerConnection> divergentConnection =
				divergent.setCurrentServer(server("divergent.example"), "test");
		assertTrue(divergentConnection.isPresent());
		assertFalse(divergentConnection.get().isSuccess());
		assertTrue(divergentChannel.isTerminated());
		assertFalse(divergent.getUselessServers().contains(server("divergent.example")));
	}

	@Test
	public void testClosingClientClosesActiveChannel() throws Exception {
		FakeManagedChannel channel = new FakeManagedChannel();
		LightdInfo info = LightdInfo.newBuilder().setChainName("regtest").setBlockHeight(1).build();
		ProbeLightClient client = new ProbeLightClient(channel, info, "regtest", 1);

		Optional<ChainableServerConnection> connection = client.setCurrentServer(server("close.example"), "test");
		assertTrue(connection.isPresent());
		assertTrue(connection.get().isSuccess());
		assertFalse(channel.isShutdown());

		client.close();
		assertTrue(channel.isTerminated());
		assertNull(client.getCurrentServer());
	}

	@Test
	public void testValidatedSelectionGenerationFollowsBoundedServerRotation() throws Exception {
		SequencedProbeLightClient client = new SequencedProbeLightClient();
		ChainableServer first = server("first.example");
		ChainableServer second = server("second.example");
		client.addServer(first);
		client.addServer(second);

		assertTrue(client.setCurrentServer(first, "test").orElseThrow().isSuccess());
		ZcashFamilyLightClient.ValidatedServerSelection firstSelection = client.getValidatedServerSelection();
		assertEquals(1L, firstSelection.getGeneration());
		assertEquals("https://first.example:443", firstSelection.getEndpointUri());
		assertEquals("main", firstSelection.getExpectedChainName());
		assertEquals(2_100_000L, firstSelection.getHeight());

		ZcashFamilyLightClient.ValidatedServerSelection secondSelection =
				client.selectAnotherAfterNativeFailure(firstSelection, new HashSet<>(Collections.singleton(first)),
						"test", "native probe failed");
		assertEquals(2L, secondSelection.getGeneration());
		assertEquals("https://second.example:443", secondSelection.getEndpointUri());
		assertFalse(client.getUselessServers().contains(first));

		client.close();
		assertNull(client.getValidatedServerSelection());
	}

	@Test
	public void testStaleNativeFailureCannotRejectNewerJavaSelection() throws Exception {
		SequencedProbeLightClient client = new SequencedProbeLightClient();
		ChainableServer first = server("first.example");
		ChainableServer second = server("second.example");
		client.addServer(first);
		client.addServer(second);
		assertTrue(client.setCurrentServer(first, "test").orElseThrow().isSuccess());
		ZcashFamilyLightClient.ValidatedServerSelection stale = client.getValidatedServerSelection();
		assertTrue(client.setCurrentServer(second, "test").orElseThrow().isSuccess());
		ZcashFamilyLightClient.ValidatedServerSelection current = client.getValidatedServerSelection();

		ZcashFamilyLightClient.ValidatedServerSelection retained = client.selectAnotherAfterNativeFailure(stale,
				new HashSet<>(Collections.singleton(first)), "test", "stale failure");
		assertEquals(current.getGeneration(), retained.getGeneration());
		assertEquals(second, client.getCurrentServer());
		assertFalse(client.getUselessServers().contains(second));
	}

	@Test
	public void testTransientNativeFailureCanRetryOnlyConfiguredServer() throws Exception {
		SequencedProbeLightClient client = new SequencedProbeLightClient();
		ChainableServer onlyServer = server("only.example");
		client.addServer(onlyServer);
		assertTrue(client.setCurrentServer(onlyServer, "test").orElseThrow().isSuccess());
		ZcashFamilyLightClient.ValidatedServerSelection rejected = client.getValidatedServerSelection();

		assertNull(client.selectAnotherAfterNativeFailure(rejected,
				new HashSet<>(Collections.singleton(onlyServer)), "test", "transient native failure"));
		ZcashFamilyLightClient.ValidatedServerSelection retried = client.selectAnyValidatedServer();
		assertEquals(onlyServer, retried.getServer());
		assertTrue(retried.getGeneration() > rejected.getGeneration());
		assertFalse(client.getUselessServers().contains(onlyServer));
	}

	@Test
	public void testValidatedSelectionLeaseDefersGenerationChangeUntilNativeUseCompletes() throws Exception {
		SequencedProbeLightClient client = new SequencedProbeLightClient();
		ChainableServer first = server("first.example");
		ChainableServer second = server("second.example");
		client.addServer(first);
		client.addServer(second);
		assertTrue(client.setCurrentServer(first, "test").orElseThrow().isSuccess());
		long firstGeneration = client.getValidatedServerSelection().getGeneration();

		CountDownLatch leaseEntered = new CountDownLatch(1);
		CountDownLatch releaseLease = new CountDownLatch(1);
		CountDownLatch mutationStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> nativeUse = executor.submit(() -> client.withValidatedServerSelectionLease(() -> {
				leaseEntered.countDown();
				releaseLease.await();
				return null;
			}));
			assertTrue(leaseEntered.await(2, TimeUnit.SECONDS));

			Future<Optional<ChainableServerConnection>> mutation = executor.submit(() -> {
				mutationStarted.countDown();
				return client.setCurrentServer(second, "test");
			});
			assertTrue(mutationStarted.await(2, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));

			releaseLease.countDown();
			nativeUse.get(2, TimeUnit.SECONDS);
			assertTrue(mutation.get(2, TimeUnit.SECONDS).orElseThrow().isSuccess());
			assertTrue(client.getValidatedServerSelection().getGeneration() > firstGeneration);
			assertEquals(second, client.getCurrentServer());
		} finally {
			releaseLease.countDown();
			executor.shutdownNow();
			client.close();
		}
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
		private final Object latestOutcome;

		private ProbeLightClient(ManagedChannel channel, Object outcome, String expectedChainName, int birthday) {
			this(channel, outcome, expectedChainName, birthday,
					outcome instanceof LightdInfo ? ((LightdInfo) outcome).getBlockHeight() : null);
		}

		private ProbeLightClient(ManagedChannel channel, Object outcome, String expectedChainName, int birthday,
				Object latestOutcome) {
			super(new ZcashFamilyWalletConfig("Test", "TEST", "Test", "signature", "encryption", "zs",
					() -> birthday, () -> null), "test", expectedChainName, Collections.emptyList(), ports(), () -> birthday);
			this.channel = channel;
			this.outcome = outcome;
			this.latestOutcome = latestOutcome;
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

		@Override
		protected BlockID fetchLatestBlock(ManagedChannel probeChannel) {
			if (this.latestOutcome instanceof RuntimeException runtimeException)
				throw runtimeException;
			if (this.latestOutcome == null)
				return null;
			return BlockID.newBuilder().setHeight((Long) this.latestOutcome).build();
		}

		private static Map<ChainableServer.ConnectionType, Integer> ports() {
			Map<ChainableServer.ConnectionType, Integer> ports = new EnumMap<>(ChainableServer.ConnectionType.class);
			ports.put(ChainableServer.ConnectionType.SSL, 443);
			return ports;
		}
	}

	private static final class SequencedProbeLightClient extends ZcashFamilyLightClient {
		private SequencedProbeLightClient() {
			super(new ZcashFamilyWalletConfig("Test", "TEST", "Test", "signature", "encryption", "zs",
					() -> 1, () -> null), "test", "main", Collections.emptyList(), ProbeLightClient.ports(), () -> 1);
		}

		@Override
		protected ManagedChannel buildProbeChannel(ChainableServer server) {
			return new FakeManagedChannel();
		}

		@Override
		protected LightdInfo fetchLightdInfo(ManagedChannel probeChannel) {
			return LightdInfo.newBuilder().setChainName("main").setBlockHeight(2_100_000L).build();
		}

		@Override
		protected BlockID fetchLatestBlock(ManagedChannel probeChannel) {
			return BlockID.newBuilder().setHeight(2_100_000L).build();
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
