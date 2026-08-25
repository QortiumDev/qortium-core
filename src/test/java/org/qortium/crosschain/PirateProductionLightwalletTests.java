package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service.BlockID;
import cash.z.wallet.sdk.rpc.Service.BlockRange;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.settings.Settings;

import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Explicitly opt-in, read-only interoperability checks against the configured
 * Pirate mainnet lightwalletd servers. These tests never initialize a wallet,
 * derive an address, query a balance, request a transaction, or broadcast data.
 */
public class PirateProductionLightwalletTests {
	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 1);
	}

	private static final String RUN_PROPERTY = "qortium.runPirateProductionLightwalletTests";
	private static final int MAX_TIP_SPREAD = 100;
	private static final int MINIMUM_COMPATIBLE_SERVERS = 2;
	private static final int LIVE_RPC_TIMEOUT_SECONDS = 10;

	@BeforeClass
	public static void loadSettings() {
		assumeTrue("Production Pirate lightwalletd acceptance is opt-in",
				Boolean.getBoolean(RUN_PROPERTY));
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
	}

	@Test
	public void testConfiguredMainnetServersExposeLinkedCompactHistory() throws Exception {
		List<PirateLightClient.Server> servers = new ArrayList<>(PirateChain.PirateChainNet.MAIN.getServers());
		servers.sort(Comparator.comparing(ChainableServer::getHostName));
		assertTrue("At least two configured mainnet endpoints are required", servers.size() >= 2);
		Set<String> configuredEndpoints = new HashSet<>();
		for (PirateLightClient.Server server : servers)
			assertTrue("Duplicate configured endpoint " + server,
					configuredEndpoints.add(endpointIdentity(server)));

		List<Integer> validatedHeights = new ArrayList<>();
		List<String> failures = new ArrayList<>();

		for (PirateLightClient.Server server : servers) {
			try (ProductionProbeLightClient client = new ProductionProbeLightClient()) {
				Optional<ChainableServerConnection> connection = client.setCurrentServer(server,
						PirateProductionLightwalletTests.class.getSimpleName());
				assertTrue("No connection result", connection.isPresent());
				assertTrue("Initial probe failed: " + connection.get().getNotes(), connection.get().isSuccess());
				assertEquals("Explicit server selection drifted", server, client.getCurrentServer());

				int height = client.getCurrentHeightBounded();
				assertTrue("Server returned a non-positive height", height > 0);

				List<CompactBlock> blocks = client.getCompactBlocksBounded(height - 1, 2);
				assertEquals("Server did not return the requested compact range", 2, blocks.size());
				CompactBlock first = blocks.get(0);
				CompactBlock second = blocks.get(1);
				assertEquals(height - 1L, first.getHeight());
				assertEquals(height, second.getHeight());
				assertTrue("First compact block hash is missing", first.getHash().size() > 0);
				assertTrue("Second compact block hash is missing", second.getHash().size() > 0);
				assertArrayEquals("Compact history is not hash-linked",
						first.getHash().toByteArray(), second.getPrevHash().toByteArray());
				validatedHeights.add(height);
				System.out.printf("PIRATE_PRODUCTION_ENDPOINT host=%s status=READY height=%d%n",
						server.getHostName(), height);
			} catch (Exception | AssertionError e) {
				String failure = server.getHostName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
				failures.add(failure);
				System.out.printf("PIRATE_PRODUCTION_ENDPOINT host=%s status=UNAVAILABLE%n",
						server.getHostName());
			}
		}

		int compatibleClusterSize = largestCompatibleCluster(validatedHeights);
		System.out.printf("PIRATE_PRODUCTION_CLUSTER size=%d%n", compatibleClusterSize);
		assertTrue("Fewer than " + MINIMUM_COMPATIBLE_SERVERS
				+ " fully validated endpoints reported mutually compatible heights; failures: " + failures,
				compatibleClusterSize >= MINIMUM_COMPATIBLE_SERVERS);
	}

	private static int largestCompatibleCluster(List<Integer> heights) {
		List<Integer> sortedHeights = new ArrayList<>(heights);
		Collections.sort(sortedHeights);
		int largest = 0;
		int left = 0;
		for (int right = 0; right < sortedHeights.size(); right++) {
			while ((long) sortedHeights.get(right) - sortedHeights.get(left) > MAX_TIP_SPREAD)
				left++;
			largest = Math.max(largest, right - left + 1);
		}
		return largest;
	}

	private static String endpointIdentity(ChainableServer server) {
		return server.getConnectionType() + ":" + server.getHostName().toLowerCase(Locale.ROOT) + ":" + server.getPort();
	}

	private static Map<ChainableServer.ConnectionType, Integer> defaultPorts() {
		Map<ChainableServer.ConnectionType, Integer> ports =
				new EnumMap<>(ChainableServer.ConnectionType.class);
		ports.put(ChainableServer.ConnectionType.TCP, 9067);
		ports.put(ChainableServer.ConnectionType.SSL, 443);
		return ports;
	}

	private static final class ProductionProbeLightClient extends PirateLightClient {
		private ProductionProbeLightClient() {
			super("PirateChain-production-acceptance", "main", Collections.emptyList(), defaultPorts());
		}

		private int getCurrentHeightBounded() throws ForeignBlockchainException {
			BlockID latestBlock = this.getCompactTxStreamerStub()
					.withDeadlineAfter(LIVE_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.getLatestBlock(null);
			assertTrue("Latest block response is missing", latestBlock != null);
			return Math.toIntExact(latestBlock.getHeight());
		}

		private List<CompactBlock> getCompactBlocksBounded(int startHeight, int count)
				throws ForeignBlockchainException {
			BlockRange range = BlockRange.newBuilder()
					.setStart(BlockID.newBuilder().setHeight(startHeight).build())
					.setEnd(BlockID.newBuilder().setHeight(startHeight + count - 1L).build())
					.build();
			CompactTxStreamerGrpc.CompactTxStreamerBlockingStub stub = this.getCompactTxStreamerStub()
					.withDeadlineAfter(LIVE_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			Iterator<CompactBlock> iterator = stub.getBlockRange(range);
			List<CompactBlock> blocks = new ArrayList<>();
			iterator.forEachRemaining(blocks::add);
			return blocks;
		}
	}
}
