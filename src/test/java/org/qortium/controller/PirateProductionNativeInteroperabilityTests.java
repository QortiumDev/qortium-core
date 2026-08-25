package org.qortium.controller;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service.BlockID;
import cash.z.wallet.sdk.rpc.Service.BlockRange;
import org.json.JSONObject;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ChainableServerConnection;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.PirateLightClient;
import org.qortium.crosschain.ZcashFamilyLightClient;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;
import org.qortium.settings.Settings;

import java.nio.file.Path;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Explicitly opt-in, wallet-free native interoperability checks against the configured Pirate mainnet endpoints.
 * The test loads only the pinned artifact and invokes read-only node probes; it never creates or opens a wallet.
 */
public class PirateProductionNativeInteroperabilityTests {
	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 1);
	}

	private static final String RUN_PROPERTY = "qortium.runPirateProductionNativeInteroperabilityTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final int MINIMUM_COMPATIBLE_SERVERS = 2;
	private static final long MAX_HEIGHT_SPREAD = 100L;
	private static final int LIVE_RPC_TIMEOUT_SECONDS = 10;

	@BeforeClass
	public static void loadSettings() {
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
	}

	@Test
	public void testPinnedNativeServiceAdmitsConfiguredProductionEndpoints() throws Exception {
		assumeTrue("Production Pirate native interoperability is opt-in", Boolean.getBoolean(RUN_PROPERTY));
		String artifactPath = System.getProperty(ARTIFACT_PATH_PROPERTY);
		assumeTrue("Set -D" + ARTIFACT_PATH_PROPERTY + "=/absolute/path/to/the/pinned archive",
				artifactPath != null && !artifactPath.isBlank() && Path.of(artifactPath).isAbsolute());
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/the staged bundle",
				bundlePath != null && !bundlePath.isBlank() && Path.of(bundlePath).isAbsolute());
		String libraryFilename = ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);

		Path artifact = Path.of(artifactPath).toAbsolutePath().normalize();
		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		PirateUnifiedWalletBundle.FileRecord trustedRecord =
				PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, libraryFilename);
		Path library = bundle.resolve(libraryFilename);

		List<PirateLightClient.Server> servers = new ArrayList<>(PirateChain.PirateChainNet.MAIN.getServers());
		servers.sort(Comparator.comparing(ChainableServer::getHostName));
		assertTrue("At least two configured mainnet endpoints are required", servers.size() >= 2);
		Set<String> configuredEndpoints = new HashSet<>();
		for (PirateLightClient.Server server : servers) {
			assertEquals("Production native admission requires TLS", ChainableServer.ConnectionType.SSL,
					server.getConnectionType());
			assertTrue("Duplicate configured endpoint " + server,
					configuredEndpoints.add(endpointIdentity(server)));
		}

		List<Long> nativeReadyHeights = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		ZcashFamilyNativeCoordinator.getInstance().execute("Pirate production native admission", adapter -> {
			PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
			adapter.loadLibrary(library);
			assertTrue("Pirate Unified JNI library did not load", adapter.isLoaded());
			JSONObject setTunnelRequest = new JSONObject().put("method", "set_tunnel").put("mode", "Direct");
			JSONObject setTunnelResponse = new JSONObject(adapter.invokeJson(setTunnelRequest.toString(), false));
			assertTrue("Native direct-transport selection failed", setTunnelResponse.optBoolean("ok", false));
			JSONObject setTunnelResult = setTunnelResponse.optJSONObject("result");
			assertNotNull("Native direct-transport selection omitted its result", setTunnelResult);
			assertTrue("Native direct-transport selection was not acknowledged",
					setTunnelResult.optBoolean("acknowledged", false));

			for (PirateLightClient.Server server : servers) {
				try (ProductionProbeLightClient javaClient = new ProductionProbeLightClient()) {
					Optional<ChainableServerConnection> connection = javaClient.setCurrentServer(server,
							PirateProductionNativeInteroperabilityTests.class.getSimpleName());
					assertTrue("No Java connection result", connection.isPresent());
					assertTrue("Java endpoint admission failed", connection.get().isSuccess());
					ZcashFamilyLightClient.ValidatedServerSelection selection =
							javaClient.getValidatedServerSelection();
					assertNotNull("Java endpoint selection was not validated", selection);
					assertEquals("Explicit Java endpoint selection drifted", server, selection.getServer());
					assertTrue("Java endpoint returned a non-positive height", selection.getHeight() > 1L);
					List<CompactBlock> blocks = javaClient.getCompactBlocksBounded(selection.getHeight() - 1L, 2);
					assertEquals("Java endpoint did not return the requested compact range", 2, blocks.size());
					CompactBlock first = blocks.get(0);
					CompactBlock second = blocks.get(1);
					assertEquals(selection.getHeight() - 1L, first.getHeight());
					assertEquals(selection.getHeight(), second.getHeight());
					assertTrue("First Java compact block hash is missing", first.getHash().size() > 0);
					assertTrue("Second Java compact block hash is missing", second.getHash().size() > 0);
					assertArrayEquals("Java compact history is not hash-linked",
							first.getHash().toByteArray(), second.getPrevHash().toByteArray());

					NativeProbe probe = probeNativeEndpoint(adapter, selection.getEndpointUri());
					assertTrue("Native node probe failed", probe.success());
					assertEquals("Native endpoint reported the wrong chain", "main", probe.chainName());
					assertTrue("Native endpoint did not use TLS", probe.tlsEnabled());
					assertTrue("Native endpoint did not use direct transport",
							"direct".equalsIgnoreCase(probe.transportMode()));
					assertTrue("Native endpoint returned a non-positive height", probe.height() > 0L);
					assertTrue("Java/native endpoint heights disagreed",
							Math.abs(probe.height() - selection.getHeight()) <= MAX_HEIGHT_SPREAD);

					nativeReadyHeights.add(probe.height());
					System.out.printf("PIRATE_PRODUCTION_NATIVE_ENDPOINT host=%s status=READY java_height=%d native_height=%d%n",
							server.getHostName(), selection.getHeight(), probe.height());
				} catch (Exception | AssertionError e) {
					failures.add(server.getHostName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
					System.out.printf("PIRATE_PRODUCTION_NATIVE_ENDPOINT host=%s status=UNAVAILABLE%n",
							server.getHostName());
				}
			}
			return null;
		});

		int compatibleClusterSize = largestCompatibleCluster(nativeReadyHeights);
		System.out.printf("PIRATE_PRODUCTION_NATIVE_PASS attempts=%d ready=%d cluster=%d%n",
				servers.size(), nativeReadyHeights.size(), compatibleClusterSize);
		assertTrue("Fewer than " + MINIMUM_COMPATIBLE_SERVERS
				+ " configured endpoints passed both Java and pinned-native admission; failures: " + failures,
				nativeReadyHeights.size() >= MINIMUM_COMPATIBLE_SERVERS);
		assertTrue("Fewer than " + MINIMUM_COMPATIBLE_SERVERS
				+ " native-ready endpoints reported compatible heights",
				compatibleClusterSize >= MINIMUM_COMPATIBLE_SERVERS);
	}

	private static NativeProbe probeNativeEndpoint(org.qortium.crosschain.ZcashFamilyNativeAdapter adapter,
			String endpointUri) {
		JSONObject request = new JSONObject().put("method", "test_node")
				.put("url", endpointUri).put("tls_pin", JSONObject.NULL);
		JSONObject envelope = new JSONObject(adapter.invokeJson(request.toString(), false));
		assertTrue("Native node probe envelope failed", envelope.optBoolean("ok", false));
		JSONObject result = envelope.optJSONObject("result");
		assertNotNull("Native node probe omitted its result", result);
		assertFalse("Native node probe omitted its success field", result.isNull("success"));
		assertFalse("Native node probe omitted its height", result.isNull("latest_block_height"));
		assertFalse("Native node probe omitted its chain", result.isNull("chain_name"));
		assertFalse("Native node probe omitted its TLS state", result.isNull("tls_enabled"));
		assertFalse("Native node probe omitted its transport", result.isNull("transport_mode"));
		return new NativeProbe(result.optBoolean("success", false), result.optLong("latest_block_height", -1L),
				result.optString("chain_name", null), result.optBoolean("tls_enabled", false),
				result.optString("transport_mode", null));
	}

	private static int largestCompatibleCluster(List<Long> heights) {
		List<Long> sortedHeights = new ArrayList<>(heights);
		Collections.sort(sortedHeights);
		int largest = 0;
		int left = 0;
		for (int right = 0; right < sortedHeights.size(); right++) {
			while (sortedHeights.get(right) - sortedHeights.get(left) > MAX_HEIGHT_SPREAD)
				left++;
			largest = Math.max(largest, right - left + 1);
		}
		return largest;
	}

	private static String endpointIdentity(ChainableServer server) {
		return server.getConnectionType() + ":" + server.getHostName().toLowerCase(Locale.ROOT)
				+ ":" + server.getPort();
	}

	private static Map<ChainableServer.ConnectionType, Integer> defaultPorts() {
		Map<ChainableServer.ConnectionType, Integer> ports =
				new EnumMap<>(ChainableServer.ConnectionType.class);
		ports.put(ChainableServer.ConnectionType.TCP, 9067);
		ports.put(ChainableServer.ConnectionType.SSL, 443);
		return ports;
	}

	private record NativeProbe(boolean success, long height, String chainName, boolean tlsEnabled,
			String transportMode) {
	}

	private static final class ProductionProbeLightClient extends PirateLightClient {
		private ProductionProbeLightClient() {
			super("PirateChain-production-native-acceptance", "main", Collections.emptyList(), defaultPorts());
		}

		private List<CompactBlock> getCompactBlocksBounded(long startHeight, int count)
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
