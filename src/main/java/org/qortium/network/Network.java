package org.qortium.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortium.crypto.Crypto;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.PeerData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.network.message.*;
import org.qortium.network.task.*;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.network.i2p.SamSession;
import org.qortium.network.upnp.PortMapperFactory;
import org.qortium.network.upnp.PortMappingResult;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;
import org.qortium.utils.ExecuteProduceConsume;
import org.qortium.utils.ExecuteProduceConsume.StatsSnapshot;
import org.qortium.utils.NTP;
import org.qortium.utils.NamedThreadFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.channels.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// For managing peers
public class Network {
    private static final Logger LOGGER = LogManager.getLogger(Network.class);

    private static final int LISTEN_BACKLOG = 5;
    /**
     * How long before retrying after a connection failure, in milliseconds.
     */
    private static final long CONNECT_FAILURE_BACKOFF = 2 * 60 * 1000L; // ms
    /**
     * I2P stream setup is slow and stale destinations can still establish at
     * the SAM layer without completing a Core handshake. Back off longer than
     * direct TCP to avoid repeated fallback probes while direct peers are healthy.
     */
    private static final long I2P_CONNECT_FAILURE_BACKOFF = 15 * 60 * 1000L; // ms
    /**
     * After dropping a working I2P fallback peer to retry direct TCP, don't drop another
     * I2P fallback for the same node within this window. Bounds drop/reconnect thrash when
     * the cached direct address is stale or unreachable.
     */
    private static final long I2P_FALLBACK_DROP_COOLDOWN = 15 * 60 * 1000L; // ms
    /**
     * How long to wait between connection attempts when isolated (no peers) and retrying backoff peers, in milliseconds.
     * This prevents hammering peers when the node has no connections.
     */
    private static final long ISOLATION_RETRY_INTERVAL = 60 * 1000L; // ms
    /**
     * How long between informational broadcasts to all connected peers, in milliseconds.
     */
    private static final long BROADCAST_INTERVAL = 30 * 1000L; // ms
    /**
     * Maximum time since last successful connection for peer info to be propagated, in milliseconds.
     */
    private static final long RECENT_CONNECTION_THRESHOLD = 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time since last connection attempt before a peer is potentially considered "old", in milliseconds.
     */
    private static final long OLD_PEER_ATTEMPTED_PERIOD = 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time since last successful connection before a peer is potentially considered "old", in milliseconds.
     */
    private static final long OLD_PEER_CONNECTION_PERIOD = 7 * 24 * 60 * 60 * 1000L; // ms
    /**
     * Maximum time allowed for handshake to complete, in milliseconds.
     */
    private static final long HANDSHAKE_TIMEOUT = 60 * 1000L; // ms
    private static final long HANDSHAKE_CLEANUP_INTERVAL = 5 * 1000L; // ms
    private static final int I2P_FORWARD_DESTINATION_TIMEOUT = 5 * 1000; // ms
    private static final long I2P_CHAIN_START_RETRY_DELAY = 60 * 1000L; // ms

    private static final long NETWORK_EPC_KEEPALIVE = 5L; // seconds

    public static final int MAX_SIGNATURES_PER_REPLY = 500;
    public static final int MAX_BLOCK_SUMMARIES_PER_REPLY = 500;
    public static final int MAX_BLOCKS_PER_REPLY = 200;

    private static final long DISCONNECTION_CHECK_INTERVAL = 20 * 1000L; // milliseconds

    private static final int BROADCAST_CHAIN_TIP_DEPTH = 7; // Just enough to fill a SINGLE TCP packet (~1440 bytes)

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());

    private final int maxMessageSize;
    private final int minOutboundPeers;
    private final int maxPeers;

    private long nextDisconnectionCheck = 0L;
    private long nextHandshakeCleanup = 0L;

    private final List<PeerData> allKnownPeers = new ArrayList<>();
    
    /**
     * Track whether the last peer selected was from the backoff list.
     * Used to determine retry interval when isolated.
     */
    private volatile boolean lastPeerWasFromBackoff = false;

    /**
     * Maintain two lists for each subset of peers:
     * - A synchronizedList, to be modified when peers are added/removed
     * - An immutable List, which is rebuilt automatically to mirror the synchronized list, and is then served to consumers
     * This allows for thread safety without having to synchronize every time a thread requests a peer list
     */
    private final List<Peer> connectedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableConnectedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above

    private final List<Peer> handshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above

    private final List<Peer> outboundHandshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private List<Peer> immutableOutboundHandshakedPeers = Collections.emptyList(); // always rebuilt from mutable, synced list above


    /**
     * Count threads per message type in order to enforce limits
     */
    private final Map<MessageType, Integer> threadsPerMessageType = Collections.synchronizedMap(new HashMap<>());

    /**
     * Keep track of total thread count, to warn when the thread pool is getting low
     */
    private int totalThreadCount = 0;

    /**
     * Thresholds at which to warn about the number of active threads
     */
    private final int threadCountWarningThreshold = (int) (Settings.getInstance().getMaxNetworkThreadPoolSize() * 0.9f);
    private final Integer threadCountPerMessageTypeWarningThreshold = Settings.getInstance().getThreadCountPerMessageTypeWarningThreshold();

    private final List<PeerAddress> selfPeers = new ArrayList<>();
    private final Set<PeerAddress> connectingI2PPeers = ConcurrentHashMap.newKeySet();

    /**
     * Track outbound connection failures by peer IP address.
     * Used to implement reachability fallback: if outbound to a peer keeps failing,
     * we allow inbound connections from them even if deterministic tie-breaking says we should be outbound.
     */
    private final Map<String, OutboundFailureInfo> outboundFailures = new ConcurrentHashMap<>();
    
    /**
     * Track outbound connection failures by peer nodeId (preferred, handles multiple nodes per IP).
     * Falls back to IP-based tracking if nodeId is not available (first-time connection).
     */
    private final Map<String, OutboundFailureInfo> outboundFailuresByNodeId = new ConcurrentHashMap<>();
    
    /**
     * Configuration for outbound failure tracking.
     * Allow inbound fallback after this many failures within the time window.
     */
    private static final int OUTBOUND_FAILURE_THRESHOLD = 3;
    private static final long OUTBOUND_FAILURE_WINDOW_MS = 5 * 60 * 1000L; // 5 minutes

    /**
     * Tracks outbound connection failure history for a peer IP.
     */
    private static class OutboundFailureInfo {
        int failureCount = 0;
        long firstFailureTimestamp = 0;
        long lastFailureTimestamp = 0;
    }

    /**
     * Direction mismatch tracking: prevents immediate reconnect thrash when we disconnect
     * a peer for having the wrong connection direction. Tracks by nodeId (survives IP changes).
     */
    private final Map<String, DirectionMismatchInfo> directionMismatchByNodeId = new ConcurrentHashMap<>();
    
    /**
     * Cache mapping address → nodeId, learned from successful handshakes.
     * Used to look up nodeId before connecting, to check if we should skip due to direction mismatch.
     * Expires after 24 hours to prevent stale mappings.
     */
    private final Map<String, CachedNodeIdInfo> addressToNodeIdCache = new ConcurrentHashMap<>();

    /**
     * nodeId -> earliest epoch-ms at which we may again drop an I2P fallback peer for that
     * node, so a stale cached direct address cannot thrash a working I2P tunnel.
     * See {@link #I2P_FALLBACK_DROP_COOLDOWN}.
     */
    private final Map<String, Long> i2pFallbackDropCooldownUntil = new ConcurrentHashMap<>();

    /**
     * Configuration for direction mismatch tracking (main Network).
     * Exponential backoff: 2min base, up to 30min max.
     */
    private static final long DIRECTION_MISMATCH_BASE_BACKOFF = 2 * 60 * 1000L; // 2 minutes
    private static final long DIRECTION_MISMATCH_MAX_BACKOFF = 30 * 60 * 1000L; // 30 minutes
    private static final long ADDRESS_CACHE_EXPIRY = 24 * 60 * 60 * 1000L; // 24 hours
    
    /**
     * Tracks direction mismatch history for a peer nodeId.
     * Uses exponential backoff to prevent both thrash and permanent blocking.
     */
    private static class DirectionMismatchInfo {
        int count = 0;
        long firstMismatch = 0;
        long lastMismatch = 0;
        
        long getBackoffDuration() {
            // Exponential backoff: 2min, 4min, 8min, 16min, capped at 30min
            return Math.min(DIRECTION_MISMATCH_BASE_BACKOFF * (1L << (count - 1)), 
                           DIRECTION_MISMATCH_MAX_BACKOFF);
        }
    }
    
    /**
     * Cached nodeId info with timestamp for expiry.
     */
    private static class CachedNodeIdInfo {
        String nodeId;
        long lastUpdated;
        
        CachedNodeIdInfo(String nodeId, long lastUpdated) {
            this.nodeId = nodeId;
            this.lastUpdated = lastUpdated;
        }
    }

    private String bindAddress = null;

    /** Dedicated I/O: select/read/write only. Never runs message handling. */
    private Thread ioThread;
    /** Produces Ping/Connect/Broadcast tasks and submits to worker pool. */
    private Thread schedulerThread;
    /** Message handling only (MessageTask, PingTask, ConnectTask, BroadcastTask). Never does I/O. */
    private ExecutorService networkWorkerPool;
    /** Scheduler state: when to try next connect. */
    private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L);
    /** Scheduler state: when to do next broadcast. */
    private final AtomicLong nextBroadcastTimestamp = new AtomicLong(0L);

    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private SelectionKey serverSelectionKey;
    private volatile I2PStreamProvider chainI2PStreamProvider;
    private volatile ServerSocketChannel i2pServerChannel;
    private final Set<SelectableChannel> channelsPendingWrite = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean i2pStartupInProgress = new AtomicBoolean(false);
    private final AtomicLong i2pStartupAttemptCounter = new AtomicLong(0L);
    private final AtomicBoolean i2pFallbackUnavailableLogged = new AtomicBoolean(false);
    /** Coalesces OP_WRITE wakeups: only the first caller per select-cycle actually wakes the selector. */
    private final java.util.concurrent.atomic.AtomicBoolean selectorWakeupPending = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Lock mergePeersLock = new ReentrantLock();
    
    /**
     * Lock for atomic peer list operations to prevent race conditions.
     * Used to ensure peer additions/removals are atomic across both connectedPeers and handshakedPeers.
     */
    private final Object peerListsLock = new Object();
    private final InboundReachability inboundReachability = new InboundReachability();

    private List<String> ourExternalIpAddressHistory = new ArrayList<>();
    private String ourExternalIpAddress = Settings.getInstance().getOurExternalIpAddress();
 
    private int ourExternalPort = Settings.getInstance().getListenPort();

    private volatile boolean isShuttingDown = false;

    // Constructors

    private Network() {
        maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();

        minOutboundPeers = Settings.getInstance().getMinOutboundPeers();
        maxPeers = Settings.getInstance().getMaxPeers();

        // Worker pool: message handling only (MessageTask, PingTask, ConnectTask, BroadcastTask).
        // I/O (select/read/write) runs on dedicated ioThread; workers never touch sockets.
        this.networkWorkerPool = new ThreadPoolExecutor(2,
                Settings.getInstance().getMaxNetworkThreadPoolSize(),
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new NamedThreadFactory("Network-Worker", Settings.getInstance().getNetworkThreadPriority()));
    }

    public void start() throws IOException, DataException {
        // Grab P2P port from settings
        int listenPort = Settings.getInstance().getListenPort();

        // Grab P2P bind addresses from settings
        List<String> bindAddresses = new ArrayList<>();
        if (Settings.getInstance().getBindAddress() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddress());
        }
        if (Settings.getInstance().getBindAddressFallback() != null) {
            bindAddresses.add(Settings.getInstance().getBindAddressFallback());
        }

        // The channel selector is always needed (the I2P inbound forward listener also registers on it).
        channelSelector = Selector.open();

        if (Settings.getInstance().isIPAllowed()) {
            for (int i=0; i<bindAddresses.size(); i++) {
                try {
                    String testBindAddress = bindAddresses.get(i);
                    InetAddress bindAddr = InetAddress.getByName(testBindAddress);
                    InetSocketAddress endpoint = new InetSocketAddress(bindAddr, listenPort);

                    // Set up listen socket
                    serverChannel = ServerSocketChannel.open();
                    serverChannel.configureBlocking(false);
                    serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                    serverChannel.bind(endpoint, LISTEN_BACKLOG);
                    serverSelectionKey = serverChannel.register(channelSelector, SelectionKey.OP_ACCEPT);

                    this.bindAddress = testBindAddress; // Store the selected address, so that it can be used by other parts of the app
                    this.inboundReachability.setListenSocketAvailable(true);
                    break; // We don't want to bind to more than one address
                } catch (UnknownHostException | UnsupportedAddressTypeException e) {
                    LOGGER.error("Can't bind listen socket to address {}", Settings.getInstance().getBindAddress());
                    if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                        throw new IOException("Can't bind listen socket to address", e);
                    }
                } catch (IOException e) {
                    LOGGER.error("Can't create listen socket: {}", e.getMessage());
                    if (i == bindAddresses.size()-1) { // Only throw an exception if all addresses have been tried
                        throw new IOException("Can't create listen socket", e);
                    }
                }
            }
        } else {
            // I2P-only (IP not in allowedTransports): do not bind/advertise a public direct TCP listener.
            this.inboundReachability.setListenSocketAvailable(false);
            LOGGER.info("Direct TCP (IP) disabled by allowedTransports - chain network listening over I2P only");
        }

        // Load all known peers from repository
        synchronized (this.allKnownPeers) {
            List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
            if (fixedNetwork != null && !fixedNetwork.isEmpty()) {
                Long addedWhen = NTP.getTime();
                String addedBy = "fixedNetwork";
                List<PeerAddress> peerAddresses = new ArrayList<>();
                for (String address : fixedNetwork) {
                    PeerAddress peerAddress = PeerAddress.fromString(address);
                    peerAddresses.add(peerAddress);
                }
                List<PeerData> peers = peerAddresses.stream()
                        .map(peerAddress -> new PeerData(peerAddress, addedWhen, addedBy))
                        .collect(Collectors.toList());
                this.allKnownPeers.addAll(peers);
            } else {
                try (Repository repository = RepositoryManager.getRepository()) {
                    this.allKnownPeers.addAll(repository.getNetworkRepository().getAllPeers());
                }
            }

            LOGGER.debug("starting with {} known peers", this.allKnownPeers.size());
        }

        // Attempt to set up UPnP for P2P. All errors are ignored.
        int networkPort = Settings.getInstance().getListenPort();
        if (Settings.getInstance().isUPnPEnabled() && Settings.getInstance().isIPAllowed()) {
            PortMappingResult portMappingResult = PortMapperFactory.getInstance().openTcpPort(networkPort, "Qortium P2P");
            if (portMappingResult.isMapped()) {
                this.inboundReachability.setPortMapped(true);
                portMappingResult.getExternalAddress()
                        .ifPresent(externalAddress -> this.ourExternalIpAddress = externalAddress.getHostAddress());
                LOGGER.info("UPnP Mapped for P2P, port: {}", networkPort);
            } else {
                this.inboundReachability.setPortMapped(false);
                LOGGER.warn("Unable to map P2P port: {} with UPnP, port in use?", networkPort);
            }
        } else {
            this.inboundReachability.setPortMapped(false);
            PortMapperFactory.getInstance().closeTcpPort(networkPort);
        }

        // Start dedicated I/O thread (select/read/write only) and scheduler (feeds worker pool)
        this.ioThread = new Thread(this::runIOLoop, "Network-IO");
        this.ioThread.setDaemon(false);
        this.ioThread.start();
        this.schedulerThread = new Thread(this::runSchedulerLoop, "Network-Scheduler");
        this.schedulerThread.setDaemon(false);
        this.schedulerThread.start();

        startI2PChainFallbackAsync();

        // Completed Setup for Network, Time to launch NetworkData for non-priority tasks
        LOGGER.info("Starting second network (QDN) on port {}", Settings.getInstance().getQDNListenPort());
        try {
            NetworkData.getInstance().start();
        } catch (IOException | DataException e) {
            LOGGER.error("Unable to start second network for data (QDN)", e);
        }
    }

    private void startI2PChainFallbackAsync() {
        if (!this.i2pStartupInProgress.compareAndSet(false, true))
            return;

        Thread i2pStarter = new Thread(this::startI2PChainFallback, "Network-I2P-Startup");
        i2pStarter.setDaemon(true);
        i2pStarter.start();
    }

    private void startI2PChainFallback() {
        try {
            while (!this.isShuttingDown && !Thread.currentThread().isInterrupted()) {
                Settings settings = Settings.getInstance();
                if (!settings.isI2PEnabled()) {
                    LOGGER.info("I2P chain fallback disabled by settings");
                    return;
                }

                if (settings.isI2PEmbeddedRouterEnabled())
                    LOGGER.warn("Embedded I2P router is not implemented yet; using the SAM provider");

                if (startI2PChainFallbackAttempt(settings))
                    return;

                try {
                    Thread.sleep(I2P_CHAIN_START_RETRY_DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            this.i2pStartupInProgress.set(false);
        }
    }

    private boolean startI2PChainFallbackAttempt(Settings settings) {
        I2PStreamProvider provider = new SamSession(settings.getI2PSamHost(), settings.getI2PSamPort(),
                nextI2PChainSessionId(), settings.getI2PChainKeyPath(), this::onI2PChainSessionUp);
        ServerSocketChannel forwardServerChannel = null;

        try {
            provider.start();
            if (this.isShuttingDown)
                throw new IOException("Network is shutting down");

            forwardServerChannel = ServerSocketChannel.open();
            forwardServerChannel.configureBlocking(false);
            forwardServerChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            forwardServerChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), LISTEN_BACKLOG);
            synchronized (this.channelSelector) {
                forwardServerChannel.register(this.channelSelector, SelectionKey.OP_ACCEPT);
                this.channelSelector.wakeup();
            }
            int localForwardPort = ((InetSocketAddress) forwardServerChannel.getLocalAddress()).getPort();

            provider.startForward(localForwardPort);
            if (this.isShuttingDown)
                throw new IOException("Network is shutting down");

            this.chainI2PStreamProvider = provider;
            this.i2pServerChannel = forwardServerChannel;
            this.i2pFallbackUnavailableLogged.set(false);
            LOGGER.info("Network I2P fallback up at {} (control/tunnels established; inbound reachability depends on LeaseSet publication)",
                    provider.getLocalB32());
            return true;
        } catch (IOException | RuntimeException e) {
            logI2PChainFallbackUnavailable(settings, e);
            if (forwardServerChannel != null) {
                try {
                    forwardServerChannel.close();
                } catch (IOException closeException) {
                    LOGGER.debug("Error closing I2P chain forward listener: {}", closeException.getMessage());
                }
            }
            provider.close();
            this.chainI2PStreamProvider = null;
            this.i2pServerChannel = null;
            return false;
        }
    }

    private void logI2PChainFallbackUnavailable(Settings settings, Exception e) {
        long retrySeconds = TimeUnit.MILLISECONDS.toSeconds(I2P_CHAIN_START_RETRY_DELAY);
        if (this.i2pFallbackUnavailableLogged.compareAndSet(false, true)) {
            LOGGER.info("Network I2P fallback unavailable via SAM at {}:{} ({}). Direct TCP remains active; "
                            + "install/run i2pd or remove I2P from allowedTransports to disable I2P retries. Retrying in {} seconds",
                    settings.getI2PSamHost(), settings.getI2PSamPort(), e.getMessage(), retrySeconds);
            return;
        }

        LOGGER.debug("Network I2P fallback still unavailable via SAM at {}:{} ({}); retrying in {} seconds",
                settings.getI2PSamHost(), settings.getI2PSamPort(), e.getMessage(), retrySeconds);
    }

    private String nextI2PChainSessionId() {
        return "qortium-chain-" + this.ourNodeId + "-" + this.i2pStartupAttemptCounter.incrementAndGet();
    }

    // Getters / setters

    private static class SingletonContainer {
        private static final Network INSTANCE = new Network();
    }

    public Map<MessageType, Integer> getThreadsPerMessageType() {
        return this.threadsPerMessageType;
    }

    public int getTotalThreadCount() {
        synchronized (this) {
            return this.totalThreadCount;
        }
    }

    public static Network getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public int getMaxPeers() {
        return this.maxPeers;
    }

    public String getBindAddress() {
        return this.bindAddress;
    }

    public byte[] getMessageMagic() {
        return BlockChain.getInstance().getMessageMagic(Settings.getInstance().isTestNet());
    }

    public String getOurNodeId() {
        return this.ourNodeId;
    }

    public String getI2PChainDestination() {
        I2PStreamProvider provider = this.chainI2PStreamProvider;
        if (provider == null || !provider.isSessionUp())
            return null;

        return provider.getLocalB32();
    }

    /**
     * Fired by {@link SamSession} the moment our chain I2P session comes up. A NAT'd node finishes its
     * clearnet handshake to seeds in under a second, but its SAM I2P session can take 9-30s to build
     * tunnels and publish a LeaseSet; the one-shot HELLO those peers received therefore carried no
     * {@code I2P} capability, so they never learn our b32 until connection rotation hours later. Re-send
     * an updated HELLO (which now includes the {@code I2P} capability) so already-handshaked peers learn
     * our reachable destination immediately.
     *
     * <p>Runs the re-advertise on a short-lived daemon thread, not the SAM setup thread: when this fires
     * {@code chainI2PStreamProvider} has not yet been assigned (it is set after {@code provider.start()}
     * returns), so {@link #getI2PChainDestination()} would still read {@code null} if called inline.
     * Deferring also keeps the per-peer message queueing off the session-setup path.
     */
    private void onI2PChainSessionUp() {
        Thread t = new Thread(this::reAdvertiseI2PChainCapability, "i2p-readvertise-chain");
        t.setDaemon(true);
        t.start();
    }

    private void reAdvertiseI2PChainCapability() {
        if (this.isShuttingDown)
            return;
        // Capability snapshot is built per-peer below (HELLO carries the recipient's own address), and now
        // includes the I2P capability because the session is up. If the session has already gone back down
        // there is nothing useful to advertise, so skip.
        if (this.getI2PChainDestination() == null) {
            LOGGER.debug("Skipping I2P chain capability re-advertise: chain destination not available");
            return;
        }
        Long timestamp = NTP.getTime();
        if (timestamp == null) {
            LOGGER.debug("Skipping I2P chain capability re-advertise: NTP unsynchronized");
            return;
        }
        String versionString = Controller.getInstance().getVersionString();
        LOGGER.info("Re-advertising I2P chain capability to handshaked peers (destination now published)");
        broadcast(peer -> {
            // Transport-scoped: only re-advertise the chain I2P destination to I2P peers. A clearnet peer
            // can never reach our I2P destination and must never see our I2P identity.
            if (!peer.getPeerData().getAddress().isI2P())
                return null;
            // Only peers that understand a post-handshake HELLO; older peers would treat it as a protocol
            // error and disconnect. Such peers simply pick up our destination at the next handshake/rotation.
            if (peer.getHandshakeStatus() != Handshake.COMPLETED || !Handshake.supportsPostHandshakeHello(peer))
                return null;
            // I2P-scoped capabilities: this re-advertise targets I2P peers only and its whole purpose is to
            // carry our chain I2P destination (now that the SAM session is up). The no-arg overload defaults
            // to clearnet scope and would omit the I2P destination, so request I2P scope explicitly.
            Map<String, Object> capabilities = Handshake.buildHelloCapabilities(true);
            String senderPeerAddress = peer.getPeerData().getAddress().toString();
            return new HelloMessage(timestamp, versionString, senderPeerAddress, capabilities, peer.getPeerType());
        });
    }

    public SocketChannel connectI2PChainPeer(String remoteB32) throws IOException {
        if (!Settings.getInstance().isI2PEnabled()) {
            LOGGER.debug("I2P chain peer {} not connected because I2P is disabled", remoteB32);
            return null;
        }

        I2PStreamProvider provider = this.chainI2PStreamProvider;
        if (provider == null) {
            LOGGER.debug("I2P chain peer {} not connected because the chain fallback session is not ready", remoteB32);
            startI2PChainFallbackAsync();
            return null;
        }

        if (!provider.isSessionUp()) {
            LOGGER.warn("I2P chain peer {} not connected because the chain fallback session is down; restarting fallback", remoteB32);
            closeI2PChainFallback();
            startI2PChainFallbackAsync();
            return null;
        }

        LOGGER.info("Connecting to I2P chain peer {}", remoteB32);
        try {
            SocketChannel socketChannel = provider.connect(remoteB32);
            if (socketChannel == null)
                LOGGER.warn("I2P chain peer {} connect failed", remoteB32);
            else
                LOGGER.info("Connected to I2P chain peer {}", remoteB32);

            return socketChannel;
        } catch (IOException e) {
            LOGGER.warn("I2P chain peer {} connect failed: {}", remoteB32, e.getMessage());
            throw e;
        }
    }

    private void closeI2PChainFallback() {
        ServerSocketChannel serverChannel = this.i2pServerChannel;
        this.i2pServerChannel = null;
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                LOGGER.debug("Error closing I2P chain forward listener: {}", e.getMessage());
            }
        }

        I2PStreamProvider provider = this.chainI2PStreamProvider;
        this.chainI2PStreamProvider = null;
        if (provider != null)
            provider.close();
    }

    protected byte[] getOurPublicKey() {
        return this.edPublicKeyParams.getEncoded();
    }

    /**
     * Maximum message size (bytes). Needs to be at least maximum block size + MAGIC + message type, etc.
     */
    public int getMaxMessageSize() {
        return this.maxMessageSize;
    }

    // Outbound failure tracking for reachability fallback

    /**
     * Record an outbound connection failure.
     * Used to track when outbound connections to a peer are failing,
     * so we can allow inbound connections as a fallback.
     * Prefers tracking by nodeId (persistent across IP changes), falls back to IP.
     */
    public void recordOutboundFailure(String peerAddress, String nodeId) {
        // Track by nodeId if available (handles multiple nodes per IP)
        if (nodeId != null) {
            OutboundFailureInfo info = outboundFailuresByNodeId.computeIfAbsent(
                nodeId, k -> new OutboundFailureInfo()
            );
            synchronized (info) {
                if (info.firstFailureTimestamp == 0) {
                    info.firstFailureTimestamp = System.currentTimeMillis();
                }
                info.failureCount++;
                info.lastFailureTimestamp = System.currentTimeMillis();
            }
            LOGGER.debug("Recorded outbound failure #{} for nodeId {}", 
                info.failureCount, nodeId.substring(0, 8));
        } else {
            // Fallback: track by IP if nodeId unknown (first-time connection)
            String peerIP = PeerAddress.fromString(peerAddress).getHost();
            OutboundFailureInfo info = outboundFailures.computeIfAbsent(
                peerIP, k -> new OutboundFailureInfo()
            );
            synchronized (info) {
                if (info.firstFailureTimestamp == 0) {
                    info.firstFailureTimestamp = System.currentTimeMillis();
                }
                info.failureCount++;
                info.lastFailureTimestamp = System.currentTimeMillis();
            }
            LOGGER.debug("Recorded outbound failure #{} for IP {} (nodeId unknown)", 
                info.failureCount, peerIP);
        }
    }

    /**
     * Check if outbound connections to the given peer have been failing recently.
     * Returns true if there have been at least OUTBOUND_FAILURE_THRESHOLD failures
     * within the OUTBOUND_FAILURE_WINDOW_MS time window.
     * Prefers checking by nodeId, falls back to IP if nodeId unknown.
     */
    public boolean hasRecentOutboundFailures(String nodeId, String peerIP) {
        long now = System.currentTimeMillis();
        
        // Check by nodeId first (most accurate, handles multiple nodes per IP)
        if (nodeId != null) {
            OutboundFailureInfo info = outboundFailuresByNodeId.get(nodeId);
            if (info != null) {
                synchronized (info) {
                    if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
                        outboundFailuresByNodeId.remove(nodeId);
                        return false;
                    }
                    return info.failureCount >= OUTBOUND_FAILURE_THRESHOLD;
                }
            }
        }
        
        // Fallback: check by IP (for first-time connections or cache miss)
        if (peerIP != null) {
            OutboundFailureInfo info = outboundFailures.get(peerIP);
            if (info != null) {
                synchronized (info) {
                    if (now - info.lastFailureTimestamp > OUTBOUND_FAILURE_WINDOW_MS) {
                        outboundFailures.remove(peerIP);
                        return false;
                    }
                    return info.failureCount >= OUTBOUND_FAILURE_THRESHOLD;
                }
            }
        }
        
        return false;
    }

    /**
     * Clear outbound failure records for the given peer.
     * Called when a connection is successfully established.
     * Clears both IP-based and nodeId-based tracking.
     */
    public void clearOutboundFailures(String peerIP, String nodeId) {
        // Clear IP-based tracking
        OutboundFailureInfo removed = outboundFailures.remove(peerIP);
        if (removed != null) {
            LOGGER.debug("Cleared outbound failures for peer IP {} (was {} failures)", 
                peerIP, removed.failureCount);
        }
        
        // Clear nodeId-based tracking
        if (nodeId != null) {
            OutboundFailureInfo removedById = outboundFailuresByNodeId.remove(nodeId);
            if (removedById != null) {
                LOGGER.debug("Cleared outbound failures for nodeId {} (was {} failures)", 
                    nodeId.substring(0, 8), removedById.failureCount);
            }
        }
    }

    /**
     * Periodically clean up stale outbound failure records to prevent memory accumulation.
     * Called from checkLongestConnection during prunePeers() (every 90 seconds).
     */
    private void cleanupStaleOutboundFailures() {
        if (outboundFailures.isEmpty() && outboundFailuresByNodeId.isEmpty()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int removed = 0;
        
        // Clean up IP-based failures
        var iterator = outboundFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            OutboundFailureInfo info = entry.getValue();
            synchronized (info) {
                if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        
        // Clean up nodeId-based failures
        var nodeIdIterator = outboundFailuresByNodeId.entrySet().iterator();
        while (nodeIdIterator.hasNext()) {
            var entry = nodeIdIterator.next();
            OutboundFailureInfo info = entry.getValue();
            synchronized (info) {
                if ((now - info.lastFailureTimestamp) > OUTBOUND_FAILURE_WINDOW_MS) {
                    nodeIdIterator.remove();
                    removed++;
                }
            }
        }
        
        if (removed > 0) {
            LOGGER.debug("Cleaned up {} stale outbound failure records", removed);
        }
    }

    // Direction mismatch tracking

    /**
     * Record that a peer was disconnected due to direction mismatch.
     * Tracks by nodeId (survives IP/port changes from UPnP, DHCP, etc).
     * Uses exponential backoff to prevent thrash while allowing eventual retry.
     */
    public void recordDirectionMismatch(String nodeId) {
        DirectionMismatchInfo info = directionMismatchByNodeId.computeIfAbsent(
            nodeId, k -> new DirectionMismatchInfo()
        );
        
        synchronized (info) {
            if (info.firstMismatch == 0) {
                info.firstMismatch = System.currentTimeMillis();
            }
            info.count++;
            info.lastMismatch = System.currentTimeMillis();
        }
        
        LOGGER.debug("Recorded direction mismatch #{} for nodeId {} - backoff: {}ms", 
                info.count, nodeId.substring(0, 8), info.getBackoffDuration());
    }

    /**
     * Check if a peer nodeId has a recent direction mismatch and should be skipped for outbound.
     * Returns true if within backoff period, false otherwise.
     */
    public boolean hasRecentDirectionMismatch(String nodeId) {
        DirectionMismatchInfo info = directionMismatchByNodeId.get(nodeId);
        if (info == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        long backoffDuration = info.getBackoffDuration();
        
        synchronized (info) {
            if (now - info.lastMismatch > backoffDuration) {
                // Backoff expired - clear it
                directionMismatchByNodeId.remove(nodeId);
                return false;
            }
            return true;
        }
    }

    /**
     * Clear direction mismatch record for the given peer nodeId.
     * Called when an inbound connection from this peer succeeds,
     * indicating they can reach us and we don't need to avoid them.
     */
    public void clearDirectionMismatch(String nodeId) {
        DirectionMismatchInfo removed = directionMismatchByNodeId.remove(nodeId);
        if (removed != null) {
            LOGGER.debug("Cleared direction mismatch for nodeId {} (was {} mismatches)", 
                    nodeId.substring(0, 8), removed.count);
        }
    }

    /**
     * Update the address → nodeId cache with a fresh mapping.
     * Called on every successful handshake to keep cache current.
     * Helps handle IP changes from DHCP/UPnP/VPN.
     */
    private void updateAddressToNodeIdCache(String address, String nodeId) {
        addressToNodeIdCache.put(address, new CachedNodeIdInfo(nodeId, System.currentTimeMillis()));
    }

    void noteHandshakePeerAddress(Peer peer, String nodeId) {
        if (peer == null || nodeId == null || peer.getPeerData() == null || peer.getPeerData().getAddress() == null)
            return;

        PeerAddress peerAddress = peer.getPeerData().getAddress();
        updateAddressToNodeIdCache(peerAddress.toString(), nodeId);

        if (peerAddress.isI2P() || peer.getResolvedAddress() == null)
            return;

        String peerIP = peer.getResolvedAddress().getAddress().getHostAddress();
        int peerPort = peer.getResolvedAddress().getPort();
        updateAddressToNodeIdCache(peerIP + ":" + peerPort, nodeId);
    }

    /**
     * Periodically clean up stale direction mismatch records and address cache.
     * Called from prunePeers.
     */
    private void cleanupStaleDirectionMismatches() {
        long now = System.currentTimeMillis();
        int removedMismatches = 0;
        int removedCache = 0;
        
        // Clean up expired mismatch records
        var mismatchIterator = directionMismatchByNodeId.entrySet().iterator();
        while (mismatchIterator.hasNext()) {
            var entry = mismatchIterator.next();
            DirectionMismatchInfo info = entry.getValue();
            synchronized (info) {
                if (now - info.lastMismatch > info.getBackoffDuration()) {
                    mismatchIterator.remove();
                    removedMismatches++;
                }
            }
        }
        
        // Clean up old address cache entries (24 hour expiry)
        var cacheIterator = addressToNodeIdCache.entrySet().iterator();
        while (cacheIterator.hasNext()) {
            var entry = cacheIterator.next();
            if (now - entry.getValue().lastUpdated > ADDRESS_CACHE_EXPIRY) {
                cacheIterator.remove();
                removedCache++;
            }
        }
        
        if (removedMismatches > 0 || removedCache > 0) {
            LOGGER.debug("Cleaned up {} stale direction mismatch records and {} stale cache entries", 
                    removedMismatches, removedCache);
        }
    }

    /**
     * Check if a peer address is in the fixed network list.
     * Fixed peers are never skipped due to direction mismatch (prevents isolation).
     */
    private boolean isFixedPeer(PeerAddress address) {
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        if (fixedNetwork == null || fixedNetwork.isEmpty()) {
            return false;
        }
        return !ipNotInFixedList(address, fixedNetwork);
    }

    public StatsSnapshot getStatsSnapshot() {
        StatsSnapshot snapshot = new StatsSnapshot();
        if (this.networkWorkerPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) this.networkWorkerPool;
            snapshot.activeThreadCount = tpe.getActiveCount();
            snapshot.greatestActiveThreadCount = Math.max(snapshot.activeThreadCount, snapshot.greatestActiveThreadCount);
            snapshot.consumerCount = snapshot.activeThreadCount; // workers are consumers
        }
        snapshot.spawnFailures = 0; // N/A with fixed worker pool
        return snapshot;
    }

    // Peer lists

    public List<PeerData> getAllKnownPeers() {
        synchronized (this.allKnownPeers) {
            return new ArrayList<>(this.allKnownPeers);
        }
    }

    public List<Peer> getImmutableConnectedPeers() {
        return this.immutableConnectedPeers;
    }

    public List<Peer> getImmutableConnectedDataPeers() {
        return this.getImmutableConnectedPeers().stream()
                .filter(p -> p.isDataPeer())
                .collect(Collectors.toList());
    }

    public List<Peer> getImmutableConnectedNonDataPeers() {
        return this.getImmutableConnectedPeers().stream()
                .filter(p -> !p.isDataPeer())
                .collect(Collectors.toList());
    }

    public void addConnectedPeer(Peer peer) {
        // ATOMIC: Synchronize to ensure add() and List.copyOf() are atomic
        // Without this, another thread could modify the list between add() and copyOf()
        synchronized (this.connectedPeers) {
            this.connectedPeers.add(peer);
            this.immutableConnectedPeers = List.copyOf(this.connectedPeers);
        }
    }

    public void removeConnectedPeer(Peer peer) {
        // ATOMIC: Lock both lists to prevent race condition with onHandshakeCompleted
        // This ensures peer isn't added to handshakedPeers while being removed from connectedPeers
        synchronized (this.peerListsLock) {
            // Firstly remove from handshaked peers
            this.removeHandshakedPeer(peer);

            // CRITICAL: Use object identity (==), not equals()
            // Peer.equals() compares by address, which can fail to find the exact object
            synchronized (this.connectedPeers) {
                this.connectedPeers.removeIf(p -> p == peer);
                this.immutableConnectedPeers = List.copyOf(this.connectedPeers);
            }
        }
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    public boolean requestDataFromPeer(String peerAddressString, byte[] signature) {
        if (peerAddressString != null) {
            PeerAddress peerAddress = PeerAddress.fromString(peerAddressString);
            PeerData peerData = null;

            // Reuse an existing PeerData instance if it's already in the known peers list
            synchronized (this.allKnownPeers) {
                peerData = this.allKnownPeers.stream()
                        .filter(knownPeerData -> knownPeerData.getAddress().equals(peerAddress))
                        .findFirst()
                        .orElse(null);
            }

            if (peerData == null) {
                // Not a known peer, so we need to create one
                Long addedWhen = NTP.getTime();
                String addedBy = "requestDataFromPeer";
                peerData = new PeerData(peerAddress, addedWhen, addedBy);
            }



            // Check if we're already connected to and handshaked with this peer
            Peer connectedPeer = this.getImmutableConnectedPeers().stream()
                        .filter(p -> p.getPeerData().getAddress().equals(peerAddress))
                        .findFirst()
                        .orElse(null);

            boolean isConnected = (connectedPeer != null);

            boolean isHandshaked = this.getImmutableHandshakedPeers().stream()
                    .anyMatch(p -> p.getPeerData().getAddress().equals(peerAddress));

            if (isConnected && isHandshaked) {
                // Already connected
                return this.requestDataFromConnectedPeer(connectedPeer, signature);
            }
            else {
                // We need to connect to this peer before we can request data
                try {
                    if (!isConnected) {
                        // Add this signature to the list of pending requests for this peer
                        LOGGER.debug("Making connection to peer {} to request files for signature {}...", peerAddressString, Base58.encode(signature));
                        Peer peer = new Peer(peerData, Peer.NETWORK);
                        peer.setIsDataPeer(true);
                        peer.addPendingSignatureRequest(signature);
                        return this.connectPeer(peer);
                        // If connection (and handshake) is successful, data will automatically be requested
                    }
                    else if (!isHandshaked) {
                        LOGGER.debug("Peer {} is connected but not handshaked. Not attempting a new connection.", peerAddress);
                        return false;
                    }

                } catch (InterruptedException e) {
                    LOGGER.debug("Interrupted when connecting to peer {}", peerAddress);
                    return false;
                }
            }
        }
        return false;
    }

    private boolean requestDataFromConnectedPeer(Peer connectedPeer, byte[] signature) {
        if (signature == null) {
            // Nothing to do
            return false;
        }

        return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public List<Peer> getImmutableHandshakedPeers() {
        return this.immutableHandshakedPeers;
    }

    public void addHandshakedPeer(Peer peer) {
        // ATOMIC: Synchronize to ensure add() and List.copyOf() are atomic
        // Without this, another thread could modify the list between add() and copyOf()
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.add(peer);
            this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers);
        }

        // Also add to outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.addOutboundHandshakedPeer(peer);
        }
    }

    public void removeHandshakedPeer(Peer peer) {
        // CRITICAL: Use object identity (==), not equals()
        // Peer.equals() compares by address, which can fail to find the exact object
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.removeIf(p -> p == peer);
            this.immutableHandshakedPeers = List.copyOf(this.handshakedPeers);
        }

        // Also remove from outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.removeOutboundHandshakedPeer(peer);
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public List<Peer> getImmutableOutboundHandshakedPeers() {
        return this.immutableOutboundHandshakedPeers;
    }

    public void addOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        // ATOMIC: Synchronize to ensure add() and List.copyOf() are atomic
        // Without this, another thread could modify the list between add() and copyOf()
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.add(peer);
            this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers);
        }
    }

    public void removeOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        // CRITICAL: Use object identity (==), not equals()
        // Peer.equals() compares by address, which can fail to find the exact object
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.removeIf(p -> p == peer);
            this.immutableOutboundHandshakedPeers = List.copyOf(this.outboundHandshakedPeers);
        }
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     * Searches handshakedPeers directly as the authoritative source for completed handshakes.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        // Search handshakedPeers directly - this is the authoritative list for completed handshakes
        // This avoids potential race conditions where a peer might be in connectedPeers with
        // COMPLETED status but not yet in handshakedPeers (or vice versa)
        return this.getImmutableHandshakedPeers().stream()
                .filter(peer -> Arrays.equals(peer.getPeersPublicKey(), publicKey))
                .findFirst().orElse(null);
    }

    // Peer list filters

    /**
     * Must be inside <tt>synchronized (this.selfPeers) {...}</tt>
     */
    private final Predicate<PeerData> isSelfPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.selfPeers.stream().anyMatch(selfPeer -> selfPeer.equals(peerAddress));
    };

    private final Predicate<PeerData> isConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return this.getImmutableConnectedPeers().stream().anyMatch(peer -> peer.getPeerData().getAddress().equals(peerAddress));
    };

    private final Predicate<PeerData> isConnectingI2PPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        return peerAddress.isI2P() && this.connectingI2PPeers.contains(peerAddress);
    };

    private final Predicate<PeerData> isLocalI2PPeer = peerData -> isLocalI2PAddress(peerData.getAddress());

    private boolean isLocalI2PAddress(PeerAddress peerAddress) {
        if (peerAddress == null || !peerAddress.isI2P())
            return false;

        String localI2PDestination = this.getI2PChainDestination();
        return localI2PDestination != null && peerAddress.getHost().equalsIgnoreCase(localI2PDestination);
    }

    private final Predicate<PeerData> isI2PAlternativeForConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        if (!peerAddress.isI2P() || Settings.getInstance().isI2PPreferred())
            return false;

        return this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.getPeerData().getAddress().isI2P())
                .anyMatch(peer -> peerAdvertisesI2PAddress(peer, Handshake.I2P_CAPABILITY, peerAddress));
    };

    private boolean peerAdvertisesI2PAddress(Peer peer, String capabilityName, PeerAddress peerAddress) {
        Object capability = peer.getPeerCapability(capabilityName);
        if (!(capability instanceof String))
            return false;

        try {
            return PeerAddress.fromString(((String) capability).trim()).equals(peerAddress);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
        try {
            InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
            return this.getImmutableConnectedPeers().stream()
                    .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
        } catch (UnknownHostException e) {
            // Can't resolve - no point even trying to connect
            return true;
        }
    };

    // Initial setup

    public static void installInitialPeers(Repository repository) throws DataException {
        long addedWhen = System.currentTimeMillis();

        for (String address : Settings.getInstance().getInitialPeers()) {
            try {
                PeerAddress peerAddress = PeerAddress.fromString(address);
                PeerData peerData = new PeerData(peerAddress, addedWhen, "INIT");
                repository.getNetworkRepository().save(peerData);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping invalid initial peer '{}' from settings.json: {}", address, e.getMessage());
            }
        }

        repository.saveChanges();
    }

    /**
     * Dedicated I/O loop: select(), then read/write/accept for all ready channels.
     * Never runs message handling; after each read, drains peer's pending messages to worker pool.
     */
    private void runIOLoop() {
        final List<Peer> readPeersThisRound = new ArrayList<>(32);
        while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
            readPeersThisRound.clear();
            synchronized (channelSelector) {
                try {
                    channelSelector.select(50L);
                } catch (IOException e) {
                    LOGGER.warn("Channel selection threw IOException: {}", e.getMessage());
                    continue;
                }
                // Reset coalescing flag now that select() has returned, so the next queued write
                // will trigger a fresh wakeup on the following iteration.
                selectorWakeupPending.set(false);
                if (Thread.currentThread().isInterrupted())
                    break;
                Set<SelectionKey> selected = channelSelector.selectedKeys();
                Iterator<SelectionKey> it = selected.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid())
                        continue;
                    SelectableChannel socketChannel = key.channel();
                    try {
                        if (key.isReadable()) {
                            // Do NOT clear/re-arm OP_READ here. readChannel() drains all available socket
                            // data in its internal loop (exits when bytesRead == 0). After draining, the OS
                            // socket buffer is empty so epoll will not re-fire OP_READ until new data arrives.
                            // Clearing and re-arming OP_READ every cycle would add 2 epoll_ctl() system calls
                            // per readable peer per iteration (processed in processUpdateQueue), which is the
                            // dominant source of Network-IO CPU cost with many active peers. On error or EOF,
                            // disconnect() closes the channel, cancelling the key automatically.
                            // key.attachment() is O(1): the Peer was stored at registration time via registerPeerChannel().
                            Peer peer = (Peer) key.attachment();
                            if (peer != null) {
                                try {
                                    peer.readChannel();
                                    readPeersThisRound.add(peer);
                                } catch (IOException e) {
                                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                                        peer.disconnect("Connection reset");
                                    } else {
                                        LOGGER.trace("[{}] Network I/O thread encountered I/O error: {}", peer.getPeerConnectionId(), e.getMessage(), e);
                                        peer.disconnect("I/O error");
                                    }
                                }
                            }
                        } else if (key.isWritable()) {
                            // Do NOT clear OP_WRITE upfront. Only clear it when writeChannel()
                            // confirms the send queue is fully drained (needsMoreWriting == false).
                            // While data remains, OP_WRITE stays armed and the selector re-fires it
                            // next cycle — no epoll_ctl calls needed. The old pattern (always clear
                            // upfront + conditionally re-arm) issued 1–2 epoll_ctl calls per writable
                            // event and was the dominant cost in processUpdateQueue during block sync.
                            Peer peer = (Peer) key.attachment();
                            if (peer != null && channelsPendingWrite.add(socketChannel)) {
                                try {
                                    boolean needsMoreWriting = peer.writeChannel();
                                    if (!needsMoreWriting)
                                        clearInterestOps(key, SelectionKey.OP_WRITE);
                                } catch (IOException e) {
                                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("connection reset")) {
                                        peer.disconnect("Connection reset");
                                    } else {
                                        LOGGER.debug("[{}] Network I/O thread encountered I/O error on write: {}", peer.getPeerConnectionId(), e.getMessage(), e);
                                        peer.disconnect("I/O error");
                                    }
                                } finally {
                                    channelsPendingWrite.remove(socketChannel);
                                }
                            }
                        } else if (key.isAcceptable()) {
                            clearInterestOps(key, SelectionKey.OP_ACCEPT);
                            try {
                                ServerSocketChannel readyServerChannel = (ServerSocketChannel) key.channel();
                                if (readyServerChannel == this.i2pServerChannel)
                                    acceptI2PForwardedPeer(readyServerChannel);
                                else
                                    new ChannelAcceptTask(readyServerChannel, Peer.NETWORK).perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            setInterestOps(key.channel(), SelectionKey.OP_ACCEPT);
                        }
                    } catch (CancelledKeyException e) {
                        // key was cancelled between isValid() and isReadable/isWritable
                    }
                }
            }
            // Drain read peers' pending messages to worker pool (outside selector lock to avoid deadlock)
            for (Peer peer : readPeersThisRound) {
                ExecuteProduceConsume.Task task;
                while ((task = peer.getMessageTask(Peer.NETWORK)) != null) {
                    final ExecuteProduceConsume.Task t = task;
                    try {
                        networkWorkerPool.execute(() -> {
                            try {
                                t.perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                LOGGER.warn("Worker task threw: {}", e.getMessage(), e);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        // Worker pool is full or shutting down - log and continue
                        // Message will be lost but system remains stable
                        LOGGER.warn("[{}] Worker pool rejected message task (pool full or shutting down)", 
                                peer.getPeerConnectionId());
                        break; // Stop draining this peer's queue
                    }
                }
            }
            // Sleep unconditionally at the end of every cycle to cap the loop at ~1000
            // iterations/sec. Without this, OP_WRITE staying armed (level-triggered EPOLLOUT)
            // causes select() to return immediately on every iteration even during heavy sync
            // when reads are also present — yielding hundreds of thousands of iterations/sec
            // and near-100% CPU on this thread. 1 ms is well within the responsiveness budget
            // of a blockchain node (the original select(50L) idle timeout was 50× longer).
            // The selector lock is already released here, so a wakeup() queued by another
            // thread is not blocked — it will be consumed on the very next select() call.
            if (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.debug("Network I/O loop exiting");
    }

    private void acceptI2PForwardedPeer(ServerSocketChannel forwardServerChannel) {
        try {
            SocketChannel socketChannel = forwardServerChannel.accept();
            if (socketChannel == null)
                return;

            try {
                networkWorkerPool.execute(() -> processI2PForwardedPeer(socketChannel));
            } catch (RejectedExecutionException e) {
                LOGGER.debug("Network worker pool rejected I2P forwarded peer setup");
                closeQuietly(socketChannel);
            }
        } catch (IOException e) {
            LOGGER.debug("I2P chain accept failed: {}", e.getMessage());
        }
    }

    private void processI2PForwardedPeer(SocketChannel socketChannel) {
        PeerAddress peerAddress = null;

        try {
            Long now = NTP.getTime();
            if (now == null) {
                LOGGER.debug("I2P chain connection discarded due to lack of NTP sync");
                socketChannel.close();
                return;
            }

            I2PStreamProvider provider = this.chainI2PStreamProvider;
            if (provider == null || !provider.isSessionUp()) {
                LOGGER.debug("I2P chain connection discarded because the session is not up");
                socketChannel.close();
                return;
            }

            // Mirror the TCP accept cap (ChannelAcceptTask) so forwarded I2P peers can't push the
            // chain peer count past maxPeers, which would also skew the serverChannel accept re-arm.
            if (getImmutableConnectedPeers().size() >= getMaxPeers()) {
                LOGGER.debug("I2P chain connection discarded because the server is full");
                socketChannel.close();
                return;
            }

            socketChannel.configureBlocking(true);
            socketChannel.socket().setSoTimeout(I2P_FORWARD_DESTINATION_TIMEOUT);
            peerAddress = PeerAddress.fromString(provider.readForwardedDestination(socketChannel));
            socketChannel.socket().setSoTimeout(0);
            if (!peerAddress.isI2P())
                throw new IOException("Forwarded destination was not an I2P address");

            LOGGER.debug("I2P chain connection accepted from peer {}", peerAddress);

            Peer newPeer = new Peer(socketChannel, Peer.NETWORK, peerAddress);
            newPeer.setIsDataPeer(false);
            this.addConnectedPeer(newPeer);
            this.onPeerReady(newPeer);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.debug("I2P chain connection failed from peer {}: {}",
                    peerAddress != null ? peerAddress : "unknown", e.getMessage());
            closeQuietly(socketChannel);
        }
    }

    private void closeQuietly(SocketChannel socketChannel) {
        if (socketChannel == null || !socketChannel.isOpen())
            return;

        try {
            socketChannel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Scheduler loop: produce Ping/Connect/Broadcast tasks and submit to worker pool.
     * Does not perform I/O or message handling.
     */
    private void runSchedulerLoop() {
        while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
            try {
                Long now = NTP.getTime();
                ExecuteProduceConsume.Task task = producePingConnectOrBroadcastTask(now);
                if (task != null) {
                    final ExecuteProduceConsume.Task t = task;
                    try {
                        networkWorkerPool.execute(() -> {
                            try {
                                t.perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                LOGGER.warn("Scheduler task threw: {}", e.getMessage(), e);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        // Worker pool is full or shutting down - skip this task
                        LOGGER.debug("Worker pool rejected scheduler task (pool full or shutting down)");
                    }
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOGGER.debug("Network scheduler loop exiting");
    }

    /** Produces one Ping, Connect, or Broadcast task if due; otherwise null. */
    private ExecuteProduceConsume.Task producePingConnectOrBroadcastTask(Long now) {
        ExecuteProduceConsume.Task task = maybeProducePeerPingTask(now);
        if (task != null) return task;
        try {
            task = maybeProduceConnectPeerTask(now);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (task != null) return task;
        return maybeProduceBroadcastTask(now);
    }

    private ExecuteProduceConsume.Task maybeProducePeerPingTask(Long now) {
        ExecuteProduceConsume.Task task = getImmutableHandshakedPeers().stream()
                .map(peer -> peer.getPingTask(now))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (task != null)
            return task;
        return getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED)
                .map(peer -> peer.getPingTask(now))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private ExecuteProduceConsume.Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
        cleanupStaleHandshakingPeers(now);

        if (now == null || now < nextConnectTaskTimestamp.get()) {
            return null;
        }
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        boolean hasFixedNetwork = (fixedNetwork != null && !fixedNetwork.isEmpty());
        if (hasFixedNetwork) {
            int properlyConnectedFixedPeers = 0;
            for (Peer peer : getImmutableHandshakedPeers()) {
                if (peer.getPeersNodeId() == null) continue;
                String peerHost = peer.getPeerData().getAddress().getHost();
                boolean isFixed = fixedNetwork.stream().anyMatch(fixedAddr -> fixedAddr.startsWith(peerHost + ":"));
                if (!isFixed) continue;
                String ourNodeId = getOurNodeId();
                if (ourNodeId != null) {
                    boolean weShouldBeOutbound = ourNodeId.compareTo(peer.getPeersNodeId()) < 0;
                    if (peer.isOutbound() == weShouldBeOutbound)
                        properlyConnectedFixedPeers++;
                }
            }
            if (properlyConnectedFixedPeers >= fixedNetwork.size() && getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
                disconnectI2PFallbackPeerWithDirectReplacement(now);
                return null;
            }
        } else {
            if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
                disconnectI2PFallbackPeerWithDirectReplacement(now);
                return null;
            }
        }
        boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();
        if (hasNoPeers && lastPeerWasFromBackoff) {
            nextConnectTaskTimestamp.set(now + ISOLATION_RETRY_INTERVAL);
        } else {
            nextConnectTaskTimestamp.set(now + 1000L);
        }
        Peer targetPeer = getConnectablePeer(now);
        if (targetPeer == null) {
            return null;
        }
        return new PeerConnectTask(targetPeer);
    }

    private ExecuteProduceConsume.Task maybeProduceBroadcastTask(Long now) {
        if (now == null || now < nextBroadcastTimestamp.get()) {
            return null;
        }
        nextBroadcastTimestamp.set(now + BROADCAST_INTERVAL);
        return new BroadcastTask();
    }

    public boolean ipNotInFixedList(PeerAddress address, List<String> fixedNetwork) {
        for (String ipAddress : fixedNetwork) {
            String[] bits = ipAddress.split(":");
            if (bits.length >= 1 && bits.length <= 2 && address.getHost().equals(bits[0])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Repairs inconsistent peer state where a peer is in one list but not the other.
     * This can happen due to race conditions in duplicate connection handling during
     * handshake completion.
     * 
     * Two types of orphaned peers are detected:
     * 1. Peer in connectedPeers with COMPLETED status but not in handshakedPeers
     * 2. Peer in handshakedPeers but not in connectedPeers (invisible to API, can't sync)
     */
    private void repairOrphanedPeers() {
        // Collect peers to disconnect outside the lock
        List<Peer> zombiesToDisconnect = new ArrayList<>();
        
        // Check 1: connectedPeers → handshakedPeers
        for (Peer peer : getImmutableConnectedPeers()) {
            // CRITICAL: Use object identity (==), not equals()
            // Peer.equals() compares by address, which can match different Peer objects
            // This caused zombies to go undetected when a different object with same address was in handshakedPeers
            boolean inHandshaked = getImmutableHandshakedPeers().stream()
                    .anyMatch(p -> p == peer);
            
            if (!inHandshaked) {
                // Peer is orphaned - in connectedPeers but not in handshakedPeers
                
                if (peer.getHandshakeStatus() == Handshake.COMPLETED) {
                    // ATOMIC: Lock to prevent disconnect during repair (double-check pattern)
                    synchronized (this.peerListsLock) {
                        // Recheck after acquiring lock - peer might have been removed
                        // ATOMIC: Hold list locks during iteration to prevent ConcurrentModificationException
                        boolean stillInConnected;
                        synchronized (this.connectedPeers) {
                            stillInConnected = this.connectedPeers.stream().anyMatch(p -> p == peer);
                        }
                        
                        boolean stillNotInHandshaked;
                        synchronized (this.handshakedPeers) {
                            stillNotInHandshaked = !this.handshakedPeers.stream().anyMatch(p -> p == peer);
                        }
                        
                        if (stillInConnected && stillNotInHandshaked) {
                            // Normal case: handshake completed but peer missing from handshakedPeers
                            // This can happen due to race conditions in duplicate handling
                            LOGGER.debug("[{}] Repairing orphaned peer {} - in connectedPeers with COMPLETED status but not in handshakedPeers",
                                    peer.getPeerConnectionId(), peer);
                            this.addHandshakedPeer(peer);
                        }
                    }
                } else {
                    // Zombie case: peer in connectedPeers but handshake status is not COMPLETED
                    // This is an inconsistent state that should never exist - the peer was either:
                    // 1. Removed from handshakedPeers but status was corrupted
                    // 2. Never properly completed handshake but stayed in connectedPeers
                    // 3. Had its status reset by a bug
                    // Collect for disconnect outside lock to avoid holding lock during cleanup
                    LOGGER.debug("[{}] Detected zombie peer {} - in connectedPeers but not in handshakedPeers (status={}, age={}ms)",
                            peer.getPeerConnectionId(), peer, peer.getHandshakeStatus(), peer.getConnectionAge());
                    zombiesToDisconnect.add(peer);
                }
            }
        }
        
        // Disconnect zombies outside the lock
        for (Peer zombie : zombiesToDisconnect) {
            zombie.disconnect("zombie peer - inconsistent state");
        }
        
        // Check 2: handshakedPeers → connectedPeers (reverse check)
        // This catches peers that are receiving pings but invisible to the API and sync
        for (Peer peer : getImmutableHandshakedPeers()) {
            // CRITICAL: Use object identity (==), not equals()
            // ATOMIC: Hold connectedPeers lock during iteration to prevent ConcurrentModificationException
            boolean inConnected;
            synchronized (this.connectedPeers) {
                inConnected = this.connectedPeers.stream()
                        .anyMatch(p -> p == peer);
            }
            
            if (!inConnected) {
                // ATOMIC: Lock to prevent disconnect during repair (double-check pattern)
                synchronized (this.peerListsLock) {
                    // Recheck after acquiring lock - peer might have been removed
                    // ATOMIC: Hold list locks during iteration to prevent ConcurrentModificationException
                    boolean stillInHandshaked;
                    synchronized (this.handshakedPeers) {
                        stillInHandshaked = this.handshakedPeers.stream().anyMatch(p -> p == peer);
                    }
                    
                    boolean stillNotInConnected;
                    synchronized (this.connectedPeers) {
                        stillNotInConnected = !this.connectedPeers.stream().anyMatch(p -> p == peer);
                    }
                    
                    if (stillInHandshaked && stillNotInConnected) {
                        // Peer is orphaned - in handshakedPeers but not in connectedPeers
                        // This causes the peer to be invisible to the API (/peers endpoint)
                        // and can prevent proper sync operation
                        LOGGER.warn("[{}] Repairing orphaned peer {} - in handshakedPeers but not in connectedPeers",
                                peer.getPeerConnectionId(), peer);
                        this.addConnectedPeer(peer);
                    }
                }
            }
        }
    }

    /**
     * Applies the direction preference when duplicate connections exist for a nodeId.
     * Single usable fallback connections are kept until a preferred-direction
     * replacement has completed handshake.
     */
    private void enforceDirectionPreference() {
        // Guard against running during shutdown
        if (this.isShuttingDown) {
            return;
        }
        
        if (this.ourNodeId == null) {
            return;
        }
        
        // Group handshaked peers by their nodeId (reading from immutable snapshot)
        Map<String, List<Peer>> byNodeId = getImmutableHandshakedPeers().stream()
                .filter(p -> p.getPeersNodeId() != null)
                .collect(Collectors.groupingBy(Peer::getPeersNodeId));
        
        // Collect disconnection decisions before executing them
        List<Peer> peersToDisconnect = new ArrayList<>();
        List<String> disconnectReasons = new ArrayList<>();
        
        for (Map.Entry<String, List<Peer>> entry : byNodeId.entrySet()) {
            List<Peer> peers = entry.getValue();
            String theirNodeId = entry.getKey();
            boolean weShouldBeOutbound = ourNodeId.compareTo(theirNodeId) < 0;
            
            if (peers.size() == 1) {
                Peer peer = peers.get(0);
                if (!isFixedPeer(peer.getPeerData().getAddress())
                        && PeerDirectionPolicy.shouldKeepSinglePeerAsFallback(peer.isOutbound(), weShouldBeOutbound)) {
                    LOGGER.trace("[{}] Keeping single peer {} as direction fallback (outbound={}, preferredOutbound={})",
                            peer.getPeerConnectionId(), peer.getPeerData().getAddress(), peer.isOutbound(), weShouldBeOutbound);
                }
            } else if (peers.size() > 1) {
                // Multiple connections - keep the correctly-directed one
                Peer correctPeer = peers.stream()
                        .filter(p -> p.isOutbound() == weShouldBeOutbound)
                        .findFirst()
                        .orElse(null);
                
                // If no correct-direction peer exists, keep the oldest established connection
                if (correctPeer == null) {
                    correctPeer = peers.stream()
                            .min(Comparator.comparingLong(Peer::getConnectionEstablishedTime))
                            .orElse(peers.get(0));
                    LOGGER.warn("No correct-direction peer found for nodeId {}, keeping oldest peer {}",
                            theirNodeId, correctPeer);
                }
                
                // Collect peers to disconnect (all except the correct one)
                for (Peer p : peers) {
                    if (p != correctPeer) {
                        LOGGER.warn("[{}] Will disconnect direction-incorrect peer {} (outbound={}, shouldBeOutbound={}, correctPeer={})",
                                p.getPeerConnectionId(), p.getPeerData().getAddress(), 
                                p.isOutbound(), weShouldBeOutbound, correctPeer.getPeerConnectionId());
                        
                        // Record direction mismatch if WE initiated (outbound) - prevents immediate reconnect thrash
                        if (p.isOutbound()) {
                            try {
                                String peerAddress = p.getPeerData().getAddress().toString();
                                recordDirectionMismatch(theirNodeId);
                                updateAddressToNodeIdCache(peerAddress, theirNodeId);
                            } catch (Exception e) {
                                LOGGER.debug("Failed to record direction mismatch: {}", e.getMessage());
                            }
                        }
                        
                        peersToDisconnect.add(p);
                        disconnectReasons.add("direction preference duplicate");
                    }
                }
            }
        }
        
        // Execute all disconnections
        for (int i = 0; i < peersToDisconnect.size(); i++) {
            peersToDisconnect.get(i).disconnect(disconnectReasons.get(i));
        }
    }

    private boolean disconnectI2PFallbackPeerWithDirectReplacement(Long now) {
        Peer i2pFallbackPeer = findI2PFallbackPeerWithDirectReplacement(now);
        if (i2pFallbackPeer == null)
            return false;

        PeerData directPeerData = findDirectReplacementForI2PFallback(i2pFallbackPeer, now);
        if (directPeerData == null)
            return false;

        LOGGER.debug("[{}] Dropping I2P fallback peer {} (nodeId {}) so direct TCP peer {} can be retried",
                i2pFallbackPeer.getPeerConnectionId(), i2pFallbackPeer.getPeerData().getAddress(),
                i2pFallbackPeer.getPeersNodeId(), directPeerData.getAddress());
        String fallbackNodeId = i2pFallbackPeer.getPeersNodeId();
        if (now != null && fallbackNodeId != null)
            i2pFallbackDropCooldownUntil.put(fallbackNodeId, now + I2P_FALLBACK_DROP_COOLDOWN);
        i2pFallbackPeer.disconnect("direct TCP replacement available");
        return true;
    }

    private Peer findI2PFallbackPeerWithDirectReplacement(Long now) {
        if (Settings.getInstance().isI2PPreferred())
            return null;

        return getImmutableOutboundHandshakedPeers().stream()
                .filter(peer -> peer.getPeerData().getAddress().isI2P())
                .filter(peer -> peer.getPeersNodeId() != null)
                .filter(peer -> !isI2PFallbackDropOnCooldown(peer.getPeersNodeId(), now))
                .filter(peer -> findDirectReplacementForI2PFallback(peer, now) != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * @return true if we dropped an I2P fallback peer for this node too recently to drop another,
     *         per {@link #I2P_FALLBACK_DROP_COOLDOWN}. Expired entries are pruned on read.
     */
    private boolean isI2PFallbackDropOnCooldown(String nodeId, Long now) {
        if (now == null || nodeId == null)
            return false;

        Long until = i2pFallbackDropCooldownUntil.get(nodeId);
        if (until == null)
            return false;
        if (now >= until) {
            i2pFallbackDropCooldownUntil.remove(nodeId);
            return false;
        }
        return true;
    }

    private PeerData findDirectReplacementForI2PFallback(Peer i2pFallbackPeer, Long now) {
        if (i2pFallbackPeer == null || i2pFallbackPeer.getPeersNodeId() == null)
            return null;

        String fallbackNodeId = i2pFallbackPeer.getPeersNodeId();
        return getAllKnownPeers().stream()
                .filter(peerData -> isEligibleDirectReplacementForI2PFallback(peerData, fallbackNodeId, now))
                .findFirst()
                .orElse(null);
    }

    private boolean isEligibleDirectReplacementForI2PFallback(PeerData peerData, String fallbackNodeId, Long now) {
        if (peerData == null || peerData.getAddress().isI2P())
            return false;

        CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerData.getAddress().toString());
        if (cachedInfo == null || !cachedInfo.nodeId.equals(fallbackNodeId))
            return false;

        if (isDirectPeerInConnectBackoff(peerData, now))
            return false;

        synchronized (this.selfPeers) {
            if (isSelfPeer.test(peerData))
                return false;
        }

        if (isConnectedPeer.test(peerData))
            return false;

        return isFixedPeer(peerData.getAddress()) || !hasRecentDirectionMismatch(cachedInfo.nodeId);
    }

    private boolean isDirectPeerInConnectBackoff(PeerData peerData, Long now) {
        if (now == null || peerData.getLastAttempted() == null)
            return false;

        return (peerData.getLastConnected() == null || peerData.getLastConnected() < peerData.getLastAttempted())
                && peerData.getLastAttempted() > now - CONNECT_FAILURE_BACKOFF;
    }

    private boolean hasRecentConnectFailure(PeerData peerData, Long now) {
        if (now == null || peerData.getLastAttempted() == null)
            return false;

        if (peerData.getLastConnected() != null && peerData.getLastConnected() >= peerData.getLastAttempted())
            return false;

        long backoff = peerData.getAddress().isI2P() ? I2P_CONNECT_FAILURE_BACKOFF : CONNECT_FAILURE_BACKOFF;
        return peerData.getLastAttempted() > now - backoff;
    }

    private Peer getConnectablePeer(final Long now) throws InterruptedException {
        // Find an address to connect to
        List<PeerData> peers = this.getAllKnownPeers();

        try (Repository repository = RepositoryManager.tryRepository()) {
            if (repository == null) {
                LOGGER.warn("Unable to get repository connection : Network.getConnectablePeer()");
                return null;
            }
        
            LOGGER.trace("ConnectedPeers: {}, Handshaked Peers: {} ", immutableConnectedPeers.size(), immutableHandshakedPeers.size());
            
            // Check if we have any handshaked peers (inbound or outbound) - are we isolated?
            boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();
                
            // Save peers in backoff for later consideration if we're isolated
            List<PeerData> peersInBackoff = new ArrayList<>();
            if (hasNoPeers) {
                peersInBackoff = peers.stream()
                    .filter(peerData -> hasRecentConnectFailure(peerData, now))
                    .collect(Collectors.toList());
            }
            
            peers.removeIf(peerData -> hasRecentConnectFailure(peerData, now));

            // Don't consider peers that we know loop back to ourself
            synchronized (this.selfPeers) {
                peers.removeIf(isSelfPeer);
            }
            peers.removeIf(isLocalI2PPeer);

            // Don't consider already connected peers (simple address match)
            peers.removeIf(isConnectedPeer);
            peers.removeIf(isConnectingI2PPeer);
            peers.removeIf(isI2PAlternativeForConnectedPeer);

            // CRITICAL FIX: Don't consider peers we're already connected to by nodeId
            // This handles cases where we have an inbound connection on an ephemeral port
            // but allKnownPeers has the listen port (common with peer discovery/persistence)
            // Exception: allow a preferred outbound replacement attempt while keeping the current fallback.
            peers.removeIf(peerData -> {
                String peerAddress = peerData.getAddress().toString();
                CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
                
                if (cachedInfo != null) {
                    // We know this peer's nodeId - check if already connected
                    String candidateNodeId = cachedInfo.nodeId;
                    Peer existingPeer = this.getImmutableConnectedPeers().stream()
                            .filter(peer -> peer.getPeersNodeId() != null 
                                   && peer.getPeersNodeId().equals(candidateNodeId))
                            .findFirst()
                            .orElse(null);
                    
                    if (existingPeer != null) {
                        if (!Settings.getInstance().isI2PPreferred()
                                && existingPeer.getPeerData().getAddress().isI2P()
                                && !peerData.getAddress().isI2P()) {
                            LOGGER.debug("Peer {} (nodeId {}) is connected over I2P fallback, allowing direct TCP replacement attempt",
                                    peerAddress, candidateNodeId.substring(0, 8));
                            return false;
                        }

                        // Already connected to this nodeId - check whether a preferred outbound replacement is useful.
                        // If the preferred direction is outbound from us, allow a replacement
                        // attempt while preserving the current fallback connection.
                        String ourNodeId = this.getOurNodeId();
                        if (ourNodeId != null) {
                            boolean weShouldBeOutbound = PeerDirectionPolicy.shouldBeOutbound(ourNodeId, candidateNodeId);
                            boolean outboundRecentlyFailed = hasRecentOutboundFailures(candidateNodeId, peerData.getAddress().getHost());
                            if (PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(existingPeer.isOutbound(),
                                    weShouldBeOutbound, outboundRecentlyFailed)) {
                                LOGGER.debug("Peer {} (nodeId {}) connected in fallback direction, allowing preferred outbound attempt",
                                        peerAddress, candidateNodeId.substring(0, 8));
                                return false; // Don't filter out
                            }
                        }

                        LOGGER.debug("Skipping peer {} (nodeId {}) - already connected",
                                peerAddress, candidateNodeId.substring(0, 8));
                        return true;
                    }
                }
                
                return false;
            });

            // Don't consider peers with recent direction mismatches
            // CRITICAL: Never skip fixed network peers (prevents isolation)
            peers.removeIf(peerData -> {
                // Fixed peers are always allowed, even if they have direction mismatches
                if (isFixedPeer(peerData.getAddress())) {
                    return false;
                }
                
                // Try to resolve address to nodeId using cache
                String peerAddress = peerData.getAddress().toString();
                CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
                
                if (cachedInfo != null) {
                    // We know this peer's nodeId from previous handshake
                    boolean shouldSkip = hasRecentDirectionMismatch(cachedInfo.nodeId);
                    if (shouldSkip) {
                        LOGGER.debug("Skipping peer {} (nodeId {}) due to recent direction mismatch",
                                peerAddress, cachedInfo.nodeId.substring(0, 8));
                    }
                    return shouldSkip;
                }
                
                // No cached nodeId - can't determine if mismatch, allow connection
                // (First-time connection, or cache expired)
                return false;
            });

            // Don't consider already connected peers (resolved address match)
            // Disabled because this might be too slow if we end up waiting a long time for hostnames to resolve via DNS
            // Which is ok because duplicate connections to the same peer are handled during handshaking
            // peers.removeIf(isResolvedAsConnectedPeer);

            // If we have no available peers but have peers in backoff, and we're isolated, retry them
            // Being isolated is worse than retrying a peer that might still be down
            if (peers.isEmpty() && !peersInBackoff.isEmpty() && hasNoPeers) {
                // Filter out self and connected from backoff list
                synchronized (this.selfPeers) {
                    peersInBackoff.removeIf(isSelfPeer);
                }
                peersInBackoff.removeIf(isLocalI2PPeer);
                peersInBackoff.removeIf(isConnectedPeer);
                peersInBackoff.removeIf(isConnectingI2PPeer);
                peersInBackoff.removeIf(isI2PAlternativeForConnectedPeer);
                
                if (!peersInBackoff.isEmpty()) {
                    peers = peersInBackoff;
                    lastPeerWasFromBackoff = true;
                    LOGGER.debug("No connected peers - retrying {} peer(s) in backoff period", peers.size());
                }
            } else {
                lastPeerWasFromBackoff = false;
            }

            peers = selectPreferredTransportPeers(peers);

            // Any left?
            if (peers.isEmpty()) {
                if (hasNoPeers) {
                    LOGGER.debug("Isolated node: No connectable peers found!");
                }
                return null;
            }

            // Pick random peer
            int peerIndex = new Random().nextInt(peers.size());

            // Pick candidate
            PeerData peerData = peers.get(peerIndex);
            Peer newPeer = new Peer(peerData, Peer.NETWORK);
            newPeer.setIsDataPeer(false);
            if (peerData.getAddress().isI2P())
                this.connectingI2PPeers.add(peerData.getAddress());

            // Update connection attempt info
            peerData.setLastAttempted(now);
            synchronized (this.allKnownPeers) {
                repository.getNetworkRepository().save(peerData);
                repository.saveChanges();
            }

            return newPeer;
        } catch (DataException e) {
            LOGGER.error("Repository issue while finding a connectable peer", e);
            return null;
        }

        
    }

    public boolean connectPeer(Peer newPeer) throws InterruptedException {
        try {
            // Also checked before creating PeerConnectTask
            if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers)
                return false;

            SocketChannel socketChannel = newPeer.connect(Peer.NETWORK);
            if (socketChannel == null) {
                // Record outbound failure for reachability fallback
                try {
                    String peerAddress = newPeer.getPeerData().getAddress().toString();
                
                    // Try to get nodeId from cache for more accurate tracking
                    String nodeId = null;
                    CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress);
                    if (cachedInfo != null) {
                        nodeId = cachedInfo.nodeId;
                    }

                    recordOutboundFailure(peerAddress, nodeId);
                } catch (Exception e) {
                    LOGGER.debug("Failed to record outbound failure: {}", e.getMessage());
                }
                return false;
            }
                
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }

            this.addConnectedPeer(newPeer);
            this.onPeerReady(newPeer);

            return true;
        } finally {
            PeerAddress peerAddress = newPeer.getPeerData().getAddress();
            if (peerAddress.isI2P())
                this.connectingI2PPeers.remove(peerAddress);
        }
    }

    private void cleanupStaleHandshakingPeers(Long now) {
        if (now == null || now < this.nextHandshakeCleanup)
            return;

        this.nextHandshakeCleanup = now + HANDSHAKE_CLEANUP_INTERVAL;

        List<Peer> stalePeers = this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() != Handshake.COMPLETED)
                .filter(peer -> peer.getConnectionTimestamp() != null)
                .filter(peer -> peer.getConnectionTimestamp() <= now - HANDSHAKE_TIMEOUT)
                .collect(Collectors.toList());

        for (Peer peer : stalePeers) {
            if (peer.isOutbound() && peer.getPeerData().getAddress().isI2P())
                recordOutboundFailure(peer.getPeerData().getAddress().toString(), peer.getPeersNodeId());

            LOGGER.debug("Disconnecting stale handshaking peer {} at {} after {} ms",
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name(),
                    now - peer.getConnectionTimestamp());
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }
    }

    private List<PeerData> selectPreferredTransportPeers(List<PeerData> peers) {
        if (peers == null || peers.isEmpty())
            return peers;

        boolean chainI2PReady = this.getI2PChainDestination() != null;
        List<PeerData> directPeers = peers.stream()
                .filter(peerData -> !peerData.getAddress().isI2P())
                .collect(Collectors.toList());
        if (!Settings.getInstance().isIPAllowed())
            directPeers = new ArrayList<>(); // I2P-only: never dial a direct peer
        List<PeerData> i2pPeers = peers.stream()
                .filter(peerData -> peerData.getAddress().isI2P())
                .collect(Collectors.toList());

        if (!chainI2PReady)
            return directPeers;

        if (Settings.getInstance().isI2PPreferred())
            return !i2pPeers.isEmpty() ? i2pPeers : directPeers;

        return !directPeers.isEmpty() ? directPeers : i2pPeers;
    }

    public Peer getPeerFromChannel(SocketChannel socketChannel) {
        for (Peer peer : this.getImmutableConnectedPeers()) {
            if (peer.getSocketChannel() == socketChannel) {
                return peer;
            }
        }

        return null;
    }

    private void checkLongestConnection(Long now) {
        if (now == null || now < nextDisconnectionCheck) {
            return;
        }

	// Find peers that have reached their maximum connection age, and disconnect them
	// Exception: never disconnect fixed peers (prevents unnecessary churn on bootstrap nodes)
	List<Peer> peersToDisconnect = this.getImmutableConnectedPeers().stream()
			.filter(peer -> !peer.isSyncInProgress())
			.filter(peer -> !isFixedPeer(peer.getPeerData().getAddress()))
			.filter(peer -> peer.hasReachedMaxConnectionAge())
			.collect(Collectors.toList());

        if (peersToDisconnect != null && !peersToDisconnect.isEmpty()) {
            for (Peer peer : peersToDisconnect) {
                LOGGER.debug("Forcing disconnection of peer {} because connection age ({} ms) " +
                        "has reached the maximum ({} ms)", peer, peer.getConnectionAge(), peer.getMaxConnectionAge());
                peer.disconnect("Connection age too old");
            }
        }

        // Clean up stale outbound failure records
        cleanupStaleOutboundFailures();

        // Check again after a minimum fixed interval
        nextDisconnectionCheck = now + DISCONNECTION_CHECK_INTERVAL;
    }

    // SocketChannel interest-ops manipulations

    private static final String[] OP_NAMES = new String[SelectionKey.OP_ACCEPT * 2];
    static {
        for (int i = 0; i < OP_NAMES.length; i++) {
            StringJoiner joiner = new StringJoiner(",");

            if ((i & SelectionKey.OP_READ) != 0) joiner.add("OP_READ");
            if ((i & SelectionKey.OP_WRITE) != 0) joiner.add("OP_WRITE");
            if ((i & SelectionKey.OP_CONNECT) != 0) joiner.add("OP_CONNECT");
            if ((i & SelectionKey.OP_ACCEPT) != 0) joiner.add("OP_ACCEPT");

            OP_NAMES[i] = joiner.toString();
        }
    }

    public void clearInterestOps(SelectableChannel socketChannel, int interestOps) {
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);
        if (selectionKey == null)
            return;

        clearInterestOps(selectionKey, interestOps);
    }

    private void clearInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.channel().isOpen())
            return;

        // If none of the bits to clear are currently set, interestOpsAnd() would queue a
        // no-op epoll_ctl into processUpdateQueue. Skip it to avoid unnecessary syscall overhead.
        if ((selectionKey.interestOps() & interestOps) == 0)
            return;

        LOGGER.trace("Thread {} clearing {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsAnd(~interestOps);
    }

    public void setInterestOps(SelectableChannel socketChannel, int interestOps) {
        SelectionKey selectionKey = socketChannel.keyFor(channelSelector);
        if (selectionKey == null) {
            // Must synchronize on selector when registering to avoid race with select()
            synchronized (channelSelector) {
                // Re-check after acquiring lock (channel might have been registered by another thread)
                selectionKey = socketChannel.keyFor(channelSelector);
                if (selectionKey == null) {
                    try {
                        selectionKey = socketChannel.register(this.channelSelector, interestOps);
                        // Wake selector to process the new registration immediately
                        channelSelector.wakeup();
                    } catch (ClosedChannelException e) {
                        // Channel already closed so ignore
                        return;
                    }
                    // Fall-through to allow logging
                }
            }
        }

        setInterestOps(selectionKey, interestOps);
    }

    /**
     * Register a peer's SocketChannel with the selector for OP_READ, attaching the Peer
     * object to the SelectionKey so the IO loop can resolve peer → O(1) via key.attachment()
     * instead of an O(n) linear scan through connectedPeers. Must be called exactly once per
     * peer, from Peer.sharedSetup().
     */
    public void registerPeerChannel(SocketChannel channel, Peer peer) {
        synchronized (channelSelector) {
            SelectionKey key = channel.keyFor(channelSelector);
            if (key == null) {
                try {
                    channel.register(channelSelector, SelectionKey.OP_READ, peer);
                    channelSelector.wakeup();
                } catch (ClosedChannelException e) {
                    // Channel closed before we could register — nothing to do
                }
            } else {
                // Already registered (shouldn't happen in normal flow) — just attach the peer
                key.attach(peer);
            }
        }
    }

    private void setInterestOps(SelectionKey selectionKey, int interestOps) {
        if (!selectionKey.isValid() || !selectionKey.channel().isOpen())
            return;

        // If all requested bits are already set, interestOpsOr() would queue a no-op epoll_ctl
        // into processUpdateQueue. Skip both the syscall and the wakeup — the selector already
        // knows this channel is armed and will fire on it when it's ready.
        if ((selectionKey.interestOps() & interestOps) == interestOps)
            return;

        LOGGER.trace("Thread {} setting {} interest-ops on channel: {}",
                Thread.currentThread().getId(),
                OP_NAMES[interestOps],
                selectionKey.channel());

        selectionKey.interestOpsOr(interestOps);

        // Wake selector immediately for write operations so the first queued message is sent without
        // waiting for the 50ms select() timeout. Subsequent callers in the same select-cycle are
        // coalesced: compareAndSet(false→true) ensures only one actual wakeup() call per cycle,
        // eliminating redundant wakeups when multiple peers enqueue messages simultaneously.
        if (interestOps == SelectionKey.OP_WRITE) {
            if (selectorWakeupPending.compareAndSet(false, true)) {
                channelSelector.wakeup();
                LOGGER.trace("Selector woken for OP_WRITE on channel {}", selectionKey.channel());
            }
        }
    }

    // Peer / Task callbacks

    public void notifyChannelNotWriting(SelectableChannel socketChannel) {
        this.channelsPendingWrite.remove(socketChannel);
    }

    protected void wakeupChannelSelector() {
        this.channelSelector.wakeup();
    }

    /**
     * Wake up the selector immediately.
     * This is useful after re-arming OP_READ to avoid waiting for the selector timeout.
     */
    public void wakeSelector() {
        this.channelSelector.wakeup();
    }

    protected boolean verify(byte[] signature, byte[] message) {
        return Crypto.verify(this.edPublicKeyParams.getEncoded(), signature, message);
    }

    protected byte[] sign(byte[] message) {
        return Crypto.sign(this.edPrivateKeyParams, message);
    }

    protected byte[] getSharedSecret(byte[] publicKey) {
        return Crypto.getSharedSecret(this.edPrivateKeyParams.getEncoded(), publicKey);
    }

    /**
     * Called when Peer's thread has setup and is ready to process messages
     */
    public void onPeerReady(Peer peer) {
        onHandshakingMessage(peer, null, Handshake.STARTED);
    }

    public void onDisconnect(Peer peer) {
        if (peer.getConnectionEstablishedTime() > 0L) {
            LOGGER.debug("[{}] Disconnected from peer {}", peer.getPeerConnectionId(), peer);
        } else {
            LOGGER.debug("[{}] Failed to connect to peer {}", peer.getPeerConnectionId(), peer);
        }

        this.removeConnectedPeer(peer);
        this.channelsPendingWrite.remove(peer.getSocketChannel());
        
        // Clean up PeerSendManager immediately when peer disconnects
        // This prevents messages from being queued to a dead manager
        PeerSendManagement.getInstance().removeSendManager(peer);

        if (this.isShuttingDown)
            // No need to do any further processing, like re-enabling listen socket or notifying Controller
            return;

        if (getImmutableConnectedPeers().size() < maxPeers - 1
                && serverSelectionKey != null
                && serverSelectionKey.isValid()
                && (serverSelectionKey.interestOps() & SelectionKey.OP_ACCEPT) == 0) {
            try {
                LOGGER.debug("Re-enabling accepting incoming connections because the server is not longer full");
                setInterestOps(serverSelectionKey, SelectionKey.OP_ACCEPT);
            } catch (CancelledKeyException e) {
                LOGGER.error("Failed to re-enable accepting of incoming connections: {}", e.getMessage());
            }
        }

        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
    }

    public void peerMisbehaved(Peer peer) {
        PeerData peerData = peer.getPeerData();
        peerData.setLastMisbehaved(NTP.getTime());
    }

    /**
     * Called when a new message arrives for a peer. message can be null if called after connection
     */
    public void onMessage(Peer peer, Message message) {
        if (message != null) {
            LOGGER.trace("[{}] Processing {} message with ID {} from peer {}", peer.getPeerConnectionId(),
                    message.getType().name(), message.getId(), peer);
        }

        Handshake handshakeStatus = peer.getHandshakeStatus();
        if (handshakeStatus != Handshake.COMPLETED) {
            LOGGER.trace("Calling onHandShakingMessage : {} : on {}", handshakeStatus.toString(), peer.getPeerType());
            onHandshakingMessage(peer, message, handshakeStatus);
            return;
        }

        // Should be non-handshaking messages from now on

        // Limit threads per message type and discard if there are already too many
        Integer maxThreadsForMessageType = Settings.getInstance().getMaxThreadsForMessageType(message.getType());
        if (maxThreadsForMessageType != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount >= maxThreadsForMessageType) {
                LOGGER.warn("Discarding {} message from peer {} (threads for type: {} >= limit {})",
                        message.getType().name(), peer, threadCount, maxThreadsForMessageType);
                return;
            }
        }

        // Warn if necessary
        if (threadCountPerMessageTypeWarningThreshold != null) {
            Integer threadCount = threadsPerMessageType.get(message.getType());
            if (threadCount != null && threadCount > threadCountPerMessageTypeWarningThreshold) {
                LOGGER.info("Warning: high thread count for {} message type: {}", message.getType().name(), threadCount);
            }
        }

        // Add to per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value + 1);
        
        // Add to total thread count
        synchronized (this) {
            totalThreadCount++;

            if (totalThreadCount >= threadCountWarningThreshold) {
                LOGGER.info("Warning: high total thread count: {} / {}", totalThreadCount, Settings.getInstance().getMaxNetworkThreadPoolSize());
            }
        }

        // Ordered by message type value
        switch (message.getType()) {
            case GET_PEERS:
                onGetPeersMessage(peer, message);
                break;

            case PING:
                onPingMessage(peer, message);
                break;

            case HELLO:
                // A HELLO after completion is a capability refresh (the peer's slow I2P session just came
                // up and it is now advertising its I2P chain destination). Merge it instead of treating it
                // as a protocol error; only disconnect if its chain identity is incompatible.
                if (!Handshake.applyPostHandshakeHello(peer, (HelloMessage) message))
                    peer.disconnect("incompatible post-handshake HELLO");
                return;

            case CHALLENGE:
            case RESPONSE:
                LOGGER.debug("[{}] Unexpected handshaking message {} from peer {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer);
                peer.disconnect("unexpected handshaking message");
                return;

            case PEERS:
                onPeersMessage(peer, message);
                break;

            default:
                // Bump up to controller for possible action
                Controller.getInstance().onNetworkMessage(peer, message);
                break;
        }

        // Remove from per-message thread count (first initializing to 0 if not already present)
        threadsPerMessageType.computeIfAbsent(message.getType(), key -> 0);
        threadsPerMessageType.computeIfPresent(message.getType(), (key, value) -> value - 1);

        // Remove from total thread count
        synchronized (this) {
            totalThreadCount--;
        }
    }

    private void onHandshakingMessage(Peer peer, Message message, Handshake handshakeStatus) {
        try {
            LOGGER.debug("[{}] Handshake status {}, message {} from peer {} isOutbound {}",
                    peer.getPeerConnectionId(),
                    handshakeStatus != null ? handshakeStatus.name() : "null",
                    (message != null ? message.getType().name() : "null"),
                    peer,
                    peer.isOutbound());
    
            // ---- 1) Outbound kick-off: message == null ----
            // Initial outbound handshake kick-off calls into here with message == null (STARTED).
            // Don't touch message.getType() in that case; just advance state and perform the action.
            if (message == null) {
                Handshake newHandshakeStatus = handshakeStatus.onMessage(peer, null);
    
                if (newHandshakeStatus == null) {
                    peer.disconnect("handshake failure");
                    return;
                }
    
                if (peer.isOutbound()) {
                    newHandshakeStatus.action(peer);
                }
    
                peer.setHandshakeStatus(newHandshakeStatus);
                // Do NOT call onHandshakeCompleted() here; completion requires RESPONSE exchange + PoW thread flag.
                return;
            }
    
            // ---- 2) Allow HELLO side-band updates out-of-order ----
            // HELLO can arrive after we've moved past HELLO; treat it as a peer info update and keep state.
            if (message.getType() == MessageType.HELLO
                    && handshakeStatus != Handshake.HELLO) {
                if (Handshake.HELLO.onMessage(peer, message) == null)
                    peer.disconnect("handshake failure");
                return;
            }
    
            // ---- 3) Early CHALLENGE handling ----
            Handshake effectiveHandshakeStatus = handshakeStatus;
            // If peer sends CHALLENGE early while we're still in HELLO, handle it as CHALLENGE.
            if (handshakeStatus == Handshake.HELLO
                && message.getType() == MessageType.CHALLENGE) {
                effectiveHandshakeStatus = Handshake.CHALLENGE;
            }

            // If peer sends RESPONSE early (while we're still in CHALLENGE), handle it as RESPONSE.
            if (handshakeStatus == Handshake.CHALLENGE
                && message.getType() == MessageType.RESPONSE) {
                effectiveHandshakeStatus = Handshake.RESPONSE;
            }

    
            // ---- 4) Validate message type (after applying effectiveHandshakeStatus) ----
            boolean unexpectedMessage = effectiveHandshakeStatus.expectedMessageType != null
                    && message.getType() != effectiveHandshakeStatus.expectedMessageType;
    
            if (unexpectedMessage) {
                LOGGER.debug("[{}] Unexpected {} message from {}, expected {}",
                        peer.getPeerConnectionId(),
                        message.getType().name(),
                        peer,
                        effectiveHandshakeStatus.expectedMessageType);
                peer.disconnect("unexpected message");
                return;
            }
    
            // ---- 5) Advance handshake state machine ----
            Handshake newHandshakeStatus = effectiveHandshakeStatus.onMessage(peer, message);
    
            if (newHandshakeStatus == null) {
                LOGGER.debug("[{}] Handshake failure with peer {} message {}",
                        peer.getPeerConnectionId(), peer, message.getType().name());
                peer.disconnect("handshake failure");
                return;
            }
    
            // ---- 6) Perform actions (send responses) ----
            if (peer.isOutbound()) {
                // Outbound: act first for the NEXT state
                newHandshakeStatus.action(peer);
            } else if (newHandshakeStatus != Handshake.RESPONDING) {
                newHandshakeStatus.action(peer);
            }
    
        // ---- 7) Commit state ----
        // Note: RESPONSE.onMessage() always returns RESPONDING now.
        // Completion is handled by tryCompleteHandshake() which is called from:
        // - RESPONSE.onMessage() after setting handshakeResponseValidated = true (RX side)
        // - RESPONSE.action() after setting handshakeResponseSent = true (TX side)
        // Whichever thread completes second will trigger the actual completion.
        peer.setHandshakeStatus(newHandshakeStatus);
    
        } finally {
            // Always reset after processing one handshake message so further handshake messages can be processed.
            // PoW is computed asynchronously; keeping this flag set would stall the handshake.
            peer.resetHandshakeMessagePending();
        }
    }
    

    private void onGetPeersMessage(Peer peer, Message message) {
        // Send our known peers
        if (!peer.sendMessage(this.buildPeersMessage(peer))) {
            peer.disconnect("failed to send peers list");
        }
    }

    private void onPingMessage(Peer peer, Message message) {
        PingMessage pingMessage = (PingMessage) message;

        // Generate 'pong' using same ID
        PingMessage pongMessage = new PingMessage();
        pongMessage.setId(pingMessage.getId());

        if (!peer.sendMessage(pongMessage)) {
            peer.disconnect("failed to send ping reply");
        }
    }

    private void onPeersMessage(Peer peer, Message message) {
        PeersMessage peersMessage = (PeersMessage) message;

        List<PeerAddress> peerAddresses = peersMessage.getPeerAddresses();

        PeerExchangeRecorder.getInstance().record("chain", peer, peersMessage.getPeerAddresses());

        // First entry contains remote peer's listen port but empty address.
        int peerPort = peerAddresses.get(0).getPort();
        peerAddresses.remove(0);

        List<PeerAddress> recentlyConnectedPeerAddresses = Collections.emptyList();

        // If inbound peer, use listen port and socket address to recreate first entry
        if (!peer.isOutbound()) {
            String host = peer.getPeerData().getAddress().getHost();
            PeerAddress sendingPeerAddress = peer.getPeerData().getAddress().isI2P()
                    ? PeerAddress.fromString(host)
                    : PeerAddress.fromString(host + ":" + peerPort);
            LOGGER.trace("PEERS sending peer's listen address: {}", sendingPeerAddress.toString());
            peerAddresses.add(0, sendingPeerAddress);
            recentlyConnectedPeerAddresses = Collections.singletonList(sendingPeerAddress);
            
            // CRITICAL FIX: Update cache with listen address -> nodeId mapping
            // This allows getConnectablePeer() to identify that we're already connected to this peer
            // even though they connected from an ephemeral port
            if (peer.getPeersNodeId() != null) {
                String listenAddress = sendingPeerAddress.toString();
                updateAddressToNodeIdCache(listenAddress, peer.getPeersNodeId());
                LOGGER.debug("Updated cache: listen address {} -> nodeId {} (from PEERS)",
                        listenAddress, peer.getPeersNodeId().substring(0, 8));
            }
        }

        opportunisticMergePeers(peer.toString(), peerAddresses, recentlyConnectedPeerAddresses);
    }

    protected void onHandshakeCompleted(Peer peer) {
        LOGGER.debug("[{}] Handshake completed with peer {} on {}", peer.getPeerConnectionId(), peer,
                peer.getPeersVersionString());

        // Clear any outbound failure records since connection succeeded.
        // Also update address→nodeId cache and clear direction mismatch for inbound.
        try {
            updateConnectedPeerAddressCache(peer);
        } catch (Exception e) {
            LOGGER.debug("Failed to update peer tracking: {}", e.getMessage());
        }

        // ATOMIC: Lock both peer lists during add operations to prevent race condition
        // This prevents disconnect from removing peer from connectedPeers between the two add operations
        // which would leave peer orphaned in handshakedPeers only
        
        // Determine what action to take while holding the lock, then execute disconnects outside
        Peer peerToDisconnect = null;
        String disconnectReason = null;
        boolean shouldAddPeer = false;
        
        synchronized (this.peerListsLock) {
            // Ensure peer is in connectedPeers before adding to handshakedPeers
            // This can happen if the PoW thread completes handshake but the peer wasn't properly
            // added to connectedPeers during connection establishment
            // Use object identity (==), not equals() which compares by address
            // ATOMIC: Hold connectedPeers lock during iteration to prevent ConcurrentModificationException
            synchronized (this.connectedPeers) {
                if (!this.connectedPeers.stream().anyMatch(p -> p == peer)) {
                    LOGGER.warn("[{}] Peer {} not in connectedPeers during handshake completion - adding now",
                            peer.getPeerConnectionId(), peer);
                    this.addConnectedPeer(peer);  // Safe: addConnectedPeer is reentrant
                }
            }

            // Synchronize duplicate check and add operation to prevent race condition
            synchronized (this.handshakedPeers) {
                // Check if this exact peer is already in handshakedPeers (duplicate call protection)
                // Use object identity (==), not equals() which compares by address
                if (this.handshakedPeers.stream().anyMatch(p -> p == peer)) {
                    LOGGER.debug("[{}] Peer {} already in handshakedPeers, skipping duplicate add",
                            peer.getPeerConnectionId(), peer);
                    return;
                }

                // Are we already connected to this peer (by public key)?
                Peer existingPeer = getHandshakedPeerWithPublicKey(peer.getPeersPublicKey());
                // NOTE: actual object reference compare, not Peer.equals()
                if (existingPeer != null && existingPeer != peer) {
                    // First check if existing peer is actually usable (not stale/dead)
                    // This is critical for force-connected peers to replace stale connections
                    boolean existingPeerUsable = existingPeer.getSocketChannel() != null 
                        && existingPeer.getSocketChannel().isOpen()
                        && !existingPeer.isStopping();
                    
                    if (!existingPeerUsable) {
                        // Existing peer is dead/stale - always replace it with new connection
                        // This ensures force-connected peers can replace stale entries
                        LOGGER.debug("[{}] Existing peer {} is stale (socket closed or stopping), replacing with new peer {}",
                                peer.getPeerConnectionId(),
                                existingPeer.getPeerConnectionId(),
                                peer.getPeerConnectionId());
                        peerToDisconnect = existingPeer;
                        disconnectReason = "replaced stale connection";
                        shouldAddPeer = true;
                    } else {
                        // Existing peer is alive - prefer deterministic direction but keep
                        // usable fallback connections until a preferred replacement completes.
                        String ourNodeId = this.getOurNodeId();
                        String theirNodeId = peer.getPeersNodeId();

                        if (ourNodeId == null || theirNodeId == null) {
                            peerToDisconnect = peer;
                            disconnectReason = "duplicate connection - missing node id";
                        } else {
                            boolean weShouldBeOutbound = PeerDirectionPolicy.shouldBeOutbound(ourNodeId, theirNodeId);
                            PeerDirectionPolicy.DuplicateConnectionDecision duplicateDecision =
                                    PeerDirectionPolicy.decideDuplicate(true, existingPeer.isOutbound(), peer.isOutbound(),
                                            weShouldBeOutbound);
                            String winner = duplicateDecision == PeerDirectionPolicy.DuplicateConnectionDecision.REPLACE_EXISTING
                                    ? "new" : "existing";
                            LOGGER.debug("[{}] Duplicate peer decision: existing={} (outbound={}), new={} (outbound={}), weShouldBeOutbound={}, winner={}",
                                    peer.getPeerConnectionId(),
                                    existingPeer.getPeerConnectionId(), existingPeer.isOutbound(),
                                    peer.getPeerConnectionId(), peer.isOutbound(),
                                    weShouldBeOutbound, winner);

                            if (duplicateDecision == PeerDirectionPolicy.DuplicateConnectionDecision.KEEP_EXISTING) {
                                peerToDisconnect = peer;
                                disconnectReason = "duplicate connection - keeping existing";
                            } else {
                                peerToDisconnect = existingPeer;
                                disconnectReason = "replaced by connection with correct direction";
                                shouldAddPeer = true;  // Continue to add new peer
                            }
                        }
                    }
                } else {
                    // No duplicate - proceed with adding
                    shouldAddPeer = true;
                }

                // Add to this.handshakedPeers cache list if decision was made to add
                if (shouldAddPeer) {
                    this.addHandshakedPeer(peer);
                }
            }
        }
        
        // Execute disconnect outside the lock to avoid holding lock during cleanup
        if (peerToDisconnect != null) {
            peerToDisconnect.disconnect(disconnectReason);
            // If we disconnected the new peer, return early
            if (peerToDisconnect == peer) {
                return;
            }
        }

        // Make a note that we've successfully completed handshake (and when)
        peer.getPeerData().setLastConnected(NTP.getTime());

        // Push to NetworkData for ALL peers (inbound or outbound).
        // Qortium reset its version line to 1.0.0, so capability-based QDN discovery
        // must not depend on old Qortal peer-version thresholds.
        addI2PChainPeer(peer);
        NetworkData.getInstance().addPeer(peer);

        // Update repository for outbound peers only
        // Only peers we successfully connected to are worth persisting for later reconnection
        if (peer.isOutbound()) {
            try (Repository repository = RepositoryManager.getRepository()) {
                synchronized (this.allKnownPeers) {
                    repository.getNetworkRepository().save(peer.getPeerData());
                    repository.saveChanges();
                }
            } catch (DataException e) {
                LOGGER.error("[{}] Repository issue while trying to update outbound peer {}",
                        peer.getPeerConnectionId(), peer, e);
            }
        }


        // Process any pending signature requests, as this peer may have been connected for this purpose only
        List<byte[]> pendingSignatureRequests = new ArrayList<>(peer.getPendingSignatureRequests());
        if (pendingSignatureRequests != null && !pendingSignatureRequests.isEmpty()) {
            for (byte[] signature : pendingSignatureRequests) {
                this.requestDataFromConnectedPeer(peer, signature);
                peer.removePendingSignatureRequest(signature);
            }
        }

        // FUTURE: we may want to disconnect from this peer if we've finished requesting data from it

        // Start regular pings
        peer.startPings();

        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).

        if (peer.isOutbound()) {
            if (!Settings.getInstance().isLite()) {
                // Send our height / chain tip info
                Message message = this.buildHeightOrChainTipInfo();

                if (message == null) {
                    peer.disconnect("Couldn't build our chain tip info");
                    return;
                }

                if (!peer.sendMessage(message)) {
                    peer.disconnect("failed to send height / chain tip info");
                    return;
                }
            }

            // Send our peers list
            Message peersMessage = this.buildPeersMessage(peer);
            if (!peer.sendMessage(peersMessage)) {
                peer.disconnect("failed to send peers list");
            }

            // Request their peers list
            Message getPeersMessage = new GetPeersMessage();
            if (!peer.sendMessage(getPeersMessage)) {
                peer.disconnect("failed to request peers list");
            }
        }

        // Ask Controller if they want to do anything
        Controller.getInstance().onPeerHandshakeCompleted(peer);
    }

    private void updateConnectedPeerAddressCache(Peer peer) {
        if (peer == null || peer.getPeerData() == null || peer.getPeersNodeId() == null)
            return;

        PeerAddress peerAddress = peer.getPeerData().getAddress();
        String theirNodeId = peer.getPeersNodeId();

        if (peerAddress.isI2P()) {
            updateAddressToNodeIdCache(peerAddress.toString(), theirNodeId);
            clearOutboundFailures(peerAddress.getHost(), theirNodeId);
            if (!peer.isOutbound())
                clearDirectionMismatch(theirNodeId);
            return;
        }

        if (peer.getResolvedAddress() == null)
            return;

        String peerIP = peer.getResolvedAddress().getAddress().getHostAddress();
        int peerPort = peer.getResolvedAddress().getPort();
        String resolvedPeerAddress = peerIP + ":" + peerPort;

        // Keep cache updated with latest address for this nodeId.
        // Handles IP changes from DHCP/UPnP/VPN.
        updateAddressToNodeIdCache(resolvedPeerAddress, theirNodeId);

        clearOutboundFailures(peerIP, theirNodeId);

        // Clear direction mismatch if inbound succeeds.
        // They successfully connected to us, so we don't need to avoid them.
        if (!peer.isOutbound()) {
            this.inboundReachability.recordInboundHandshake();
            clearDirectionMismatch(theirNodeId);
        }
    }

    private void addI2PChainPeer(Peer peer) {
        PeerAddress i2pAddress = getI2PChainPeerAddress(peer);
        if (i2pAddress == null)
            return;

        Long addedWhen = NTP.getTime();
        if (addedWhen == null)
            return;

        synchronized (this.allKnownPeers) {
            boolean alreadyKnown = this.allKnownPeers.stream()
                    .anyMatch(peerData -> peerData.getAddress().equals(i2pAddress));
            if (!alreadyKnown) {
                this.allKnownPeers.add(new PeerData(i2pAddress, addedWhen, peer.getPeerData().getAddress().toString()));
                LOGGER.debug("Added I2P chain peer address {} from Network connection {}",
                        i2pAddress, peer.getPeerData().getAddress());
            }
        }

        if (peer.getPeersNodeId() != null)
            updateAddressToNodeIdCache(i2pAddress.toString(), peer.getPeersNodeId());
    }

    private PeerAddress getI2PChainPeerAddress(Peer peer) {
        if (!Settings.getInstance().isI2PEnabled())
            return null;

        Object i2pCapability = peer.getPeerCapability(Handshake.I2P_CAPABILITY);
        if (i2pCapability == null)
            return null;

        if (!(i2pCapability instanceof String)) {
            LOGGER.warn("Peer {} has invalid I2P capability type: {}, skipping I2P chain address",
                    peer.getPeerData().getAddress(), i2pCapability.getClass().getSimpleName());
            return null;
        }

        try {
            PeerAddress i2pAddress = PeerAddress.fromString(((String) i2pCapability).trim());
            if (!i2pAddress.isI2P())
                throw new IllegalArgumentException("I2P was not an I2P address");
            if (isLocalI2PAddress(i2pAddress)) {
                LOGGER.debug("Peer {} advertised our own I2P chain address {}, skipping",
                        peer.getPeerData().getAddress(), i2pAddress);
                return null;
            }
            return i2pAddress;
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Peer {} advertised invalid I2P capability: {}",
                    peer.getPeerData().getAddress(), i2pCapability);
            return null;
        }
    }

    // Message-building calls

    /**
     * Returns PEERS message made from peers we've connected to recently, and this node's details
     */
    public Message buildPeersMessage(Peer peer) {
        List<PeerData> knownPeers = this.getAllKnownPeers();

        // Transport-scoped peer exchange: the transport is the privacy boundary, so an I2P requester
        // must only ever learn .b32.i2p peer addresses, and a clearnet requester must only ever learn
        // clearnet addresses. Never cross-advertise between transports.
        final boolean requesterIsI2P = peer.getPeerData().getAddress().isI2P();

        // Filter out peers that we've not connected to ever or within X milliseconds
        final long connectionThreshold = NTP.getTime() - RECENT_CONNECTION_THRESHOLD;
        Predicate<PeerData> notRecentlyConnected = peerData -> {
            final Long lastAttempted = peerData.getLastAttempted();
            final Long lastConnected = peerData.getLastConnected();

            if (lastAttempted == null || lastConnected == null) {
                return true;
            }

            if (lastConnected < lastAttempted) {
                return true;
            }

            if (lastConnected < connectionThreshold) {
                return true;
            }

            return false;
        };
        knownPeers.removeIf(notRecentlyConnected);

        List<PeerAddress> peerAddresses = new ArrayList<>();

        for (PeerData peerData : knownPeers) {
            if (peerData.getAddress().isI2P()) {
                // Only hand I2P addresses to an I2P requester.
                if (requesterIsI2P) {
                    peerAddresses.add(peerData.getAddress());
                }
                continue;
            }

            // Clearnet address: never hand it to an I2P requester.
            if (requesterIsI2P) {
                continue;
            }

            try {
                InetAddress address = InetAddress.getByName(peerData.getAddress().getHost());

                // Don't send 'local' addresses if peer is not 'local'.
                // e.g. don't send localhost:9084 to example-peer.invalid
                if (!peer.isLocal() && Peer.isAddressLocal(address)) {
                    continue;
                }

                peerAddresses.add(peerData.getAddress());
            } catch (UnknownHostException e) {
                // Couldn't resolve hostname to IP address so discard
            }
        }

        // Current PEERS message supports hostnames, IPv6 and ports.
        return new PeersMessage(peerAddresses);
    }

    /** Builds the current chain-tip summaries message.
     *
     *  @return Message, or null if DataException was thrown.
     */
    public Message buildHeightOrChainTipInfo() {
        int latestHeight = Controller.getInstance().getChainHeight();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            return new BlockSummariesMessage(latestBlockSummaries);
        } catch (DataException e) {
            return null;
        }
    }

    public void broadcastOurChain() {
        int latestHeight = Controller.getInstance().getChainHeight();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<BlockSummaryData> latestBlockSummaries = repository.getBlockRepository().getBlockSummaries(latestHeight - BROADCAST_CHAIN_TIP_DEPTH, latestHeight);
            Message latestBlockSummariesMessage = new BlockSummariesMessage(latestBlockSummaries);

            Network.getInstance().broadcast(broadcastPeer -> latestBlockSummariesMessage);
        } catch (DataException e) {
            LOGGER.warn("Couldn't broadcast our chain tip info", e);
        }
    }

    public Message buildNewTransactionMessage(Peer peer, TransactionData transactionData) {
        // Send transaction signatures only so peers can decide whether to request the full transaction.
        return new TransactionSignaturesMessage(Collections.singletonList(transactionData.getSignature()));
    }

    public Message buildGetUnconfirmedTransactionsMessage(Peer peer) {
        return new GetUnconfirmedTransactionsMessage();
    }


    // External IP / peerAddress tracking

    public synchronized void ourPeerAddressUpdated(String peerAddress) {
        if (peerAddress == null || peerAddress.isEmpty()) {
            LOGGER.debug("peer address was null or empty");
            return;
        }

        // Validate IP address
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            LOGGER.debug("Does not contain IP:PORT");
            return;
        }
        String host = parts[0];
        
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress() || addr.isSiteLocalAddress()) {
                // Ignore local addresses
                return;
            }
        } catch (UnknownHostException e) {
            return;
        }

        // Keep track of the port
        try {
            this.ourExternalPort = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            LOGGER.debug("Invalid port number in peer address: {}", peerAddress);
            return;
        }

        // Add to the list
        this.ourExternalIpAddressHistory.add(host);
        LOGGER.trace("We were told our address is: {}", host);
        // In the beginning we don't have 10 connections, so assume the first client tells the truth
        if (this.ourExternalIpAddress == null) {

            this.ourExternalIpAddress = host;
            this.onExternalIpUpdate(host);
            return;
        }

        // Limit to 25 entries
        while (this.ourExternalIpAddressHistory.size() > 25) {
            this.ourExternalIpAddressHistory.remove(0);
        }

        // Now take a copy of the IP address history so it can be safely iterated
        // Without this, another thread could remove an element, resulting in an exception
        List<String> ipAddressHistory = new ArrayList<>(this.ourExternalIpAddressHistory);

        // If we've had 10 consecutive matching addresses, and they're different from
        // our stored IP address value, treat it as updated.
        int consecutiveReadingsRequired = 10;
        int size = ipAddressHistory.size();

        if (size < consecutiveReadingsRequired) {
            // Need at least 10 readings
            return;
        }

        // Count the number of consecutive IP address readings from the end of the list
        String lastReading = ipAddressHistory.get(size - 1);
        int consecutiveReadings = 1; // Start at 1 since the last element counts as the first match
        for (int i = size - 2; i >= 0; i--) {
            String reading = ipAddressHistory.get(i);
            if (Objects.equals(reading, lastReading)) {
                consecutiveReadings++;
            } else {
                // Stop when we find a different address (we want consecutive matches only)
                break;
            }
        }

        if (consecutiveReadings >= consecutiveReadingsRequired) {
            // Last 10 readings were the same - i.e. more than one peer agreed on the new IP address...
            String ip = ipAddressHistory.get(size - 1);
            if (ip != null && !Objects.equals(ip, "null")) {
                if (!Objects.equals(ip, this.ourExternalIpAddress)) {
                    // ... and the readings were different to our current recorded value, so
                    // update our external IP address value
                    this.ourExternalIpAddress = ip;
                    this.onExternalIpUpdate(ip);
                }
            }
        }
    }

    public void onExternalIpUpdate(String ipAddress) {
        LOGGER.info("External IP address updated to {}", ipAddress);
    }

    public boolean canAcceptInbound() {
        return this.inboundReachability.canAcceptInbound();
    }

    public boolean isListenSocketAvailable() {
        return this.inboundReachability.isListenSocketAvailable();
    }

    public boolean isPortMapped() {
        return this.inboundReachability.isPortMapped();
    }

    public long getLastInboundHandshakeTimestamp() {
        return this.inboundReachability.getLastInboundHandshakeTimestamp();
    }

    public String getOurExternalIpAddress() {
        return this.ourExternalIpAddress;
    }

    public String getOurExternalIpAddressAndPort() {
        String ipAddress = this.getOurExternalIpAddress();
        if (ipAddress == null) {
            return null;
        }
        return String.format("%s:%d", ipAddress, this.ourExternalPort);
    }

    // Peer-management calls

    public void noteToSelf(Peer peer) {
        LOGGER.debug("[{}] No longer considering peer address {} as it connects to self",
                peer.getPeerConnectionId(), peer);

        synchronized (this.selfPeers) {
            this.selfPeers.add(peer.getPeerData().getAddress());
        }
    }

    public boolean forgetPeer(PeerAddress peerAddress) throws DataException {
        boolean numDeleted;

        synchronized (this.allKnownPeers) {
            numDeleted = this.allKnownPeers.removeIf(peerData -> peerData.getAddress().equals(peerAddress));
        }

        disconnectPeer(peerAddress);

        return numDeleted;
    }

    public int forgetAllPeers() throws DataException {
        int numDeleted;

        synchronized (this.allKnownPeers) {
            numDeleted = this.allKnownPeers.size();
            this.allKnownPeers.clear();
        }

        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Disconnect peer
        try {
            InetSocketAddress knownAddress = peerAddress.toSocketAddress();

            List<Peer> peers = this.getImmutableConnectedPeers().stream()
                    .filter(peer -> Peer.addressEquals(knownAddress, peer.getResolvedAddress()))
                    .collect(Collectors.toList());

            for (Peer peer : peers) {
                peer.disconnect("to be forgotten");
            }
        } catch (UnknownHostException e) {
            // Unknown host isn't going to match any of our connected peers so ignore
        }
    }

    // Network-wide calls

    public void prunePeers() throws DataException {
        // Guard against running during shutdown
        if (this.isShuttingDown) {
            return;
        }
        
        final Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Repair any orphaned peers (bidirectional check between connectedPeers and handshakedPeers)
        try {
            repairOrphanedPeers();
        } catch (Exception e) {
            LOGGER.error("Error repairing orphaned peers: {}", e.getMessage(), e);
            // Continue with other pruning operations - don't let one failure stop the rest
        }
        
        // Apply direction preference to duplicate connections
        try {
            enforceDirectionPreference();
        } catch (Exception e) {
            LOGGER.error("Error enforcing direction preference: {}", e.getMessage(), e);
            // Continue with other pruning operations - don't let one failure stop the rest
        }
        
        // Clean up stale direction mismatch records and address cache
        try {
            cleanupStaleDirectionMismatches();
        } catch (Exception e) {
            LOGGER.error("Error cleaning up stale direction mismatches: {}", e.getMessage(), e);
            // Continue with other pruning operations
        }

        // Disconnect peers that have stuck writes (no progress for 60 seconds)
        final long WRITE_STUCK_TIMEOUT = 60_000L;
        List<Peer> stuckWritePeers = this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED)
                .filter(peer -> peer.hasStuckWrite(WRITE_STUCK_TIMEOUT))
                .collect(Collectors.toList());

        for (Peer peer : stuckWritePeers) {
            String stuckInfo = peer.getStuckWriteInfo();
            LOGGER.warn("Disconnecting peer {} with stuck write: {}", 
                    peer.getPeerData().getAddress(), stuckInfo);
            peer.disconnect("write stuck: " + stuckInfo);
        }

        // Disconnect peers that are stuck during handshake
        // Needs a mutable copy of the unmodifiableList
        List<Peer> handshakePeers = new ArrayList<>(this.getImmutableConnectedPeers());

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT);

        for (Peer peer : handshakePeers) {
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }

        // Clean up peers with closed sockets (zombie connections)
        // These can block new connections due to duplicate detection during handshake
        // This catches peers in any handshake state (including COMPLETED) where the socket
        // has been closed but the peer hasn't been removed from the connected list yet
        List<Peer> deadPeers = this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen())
                .collect(Collectors.toList());

        for (Peer peer : deadPeers) {
            LOGGER.debug("Disconnecting dead peer {} (socket closed, handshake status: {})",
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name());
            peer.disconnect("socket closed");
        }

        // Additional defensive cleanup: Check handshakedPeers for zombie connections
        // This catches the case where onDisconnect() might have failed to remove a peer
        // from handshakedPeers even though the socket is closed
        // NOTE: We only check for closed/null sockets, NOT isStopping() - that flag is set
        // during normal disconnect flow and would incorrectly remove all disconnecting peers
        List<Peer> zombieHandshakedPeers = this.getImmutableHandshakedPeers().stream()
                .filter(peer -> peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen())
                .collect(Collectors.toList());

        if (!zombieHandshakedPeers.isEmpty()) {
            LOGGER.warn("Found {} zombie peer(s) in handshakedPeers list, forcing cleanup", 
                    zombieHandshakedPeers.size());
            for (Peer peer : zombieHandshakedPeers) {
                LOGGER.warn("Removing zombie handshaked peer {} (socket closed)",
                        peer.getPeerData().getAddress());
                // Directly remove from lists as a defensive measure
                // This shouldn't normally be needed if disconnect() works properly,
                // but provides a safety net against the bug we're fixing
                this.removeHandshakedPeer(peer);
                this.removeConnectedPeer(peer);
            }
        }

        // Disconnect peers that have exceeded their maximum connection age
        this.checkLongestConnection(now);

        // Prune 'old' peers from repository...
        // Pruning peers isn't critical so no need to block for a repository instance.
        synchronized (this.allKnownPeers) {
            // Fetch all known peers
            List<PeerData> peers = new ArrayList<>(this.allKnownPeers);

            // 'Old' peers:
            // We attempted to connect within the last day
            // but we last managed to connect over a week ago.
            Predicate<PeerData> isNotOldPeer = peerData -> {
                if (peerData.getLastAttempted() == null
                        || peerData.getLastAttempted() < now - OLD_PEER_ATTEMPTED_PERIOD) {
                    return true;
                }

                if (peerData.getLastConnected() == null
                        || peerData.getLastConnected() > now - OLD_PEER_CONNECTION_PERIOD) {
                    return true;
                }

                return false;
            };

            // Disregard peers that are NOT 'old'
            peers.removeIf(isNotOldPeer);

            // Don't consider already connected peers (simple address match)
            peers.removeIf(isConnectedPeer);

            for (PeerData peerData : peers) {
                // Delete from known peer cache too
                this.allKnownPeers.remove(peerData);
            }
        }
    }

    public boolean mergePeers(String addedBy, long addedWhen, List<PeerAddress> peerAddresses) throws DataException {
        mergePeersLock.lock();

        try{
            return this.mergePeersUnlocked(addedBy, addedWhen, peerAddresses, Collections.emptyList());
        } finally {
            mergePeersLock.unlock();
        }
    }

    private void opportunisticMergePeers(String addedBy, List<PeerAddress> peerAddresses) {
        opportunisticMergePeers(addedBy, peerAddresses, Collections.emptyList());
    }

    private void opportunisticMergePeers(String addedBy, List<PeerAddress> peerAddresses,
                                         List<PeerAddress> recentlyConnectedPeerAddresses) {
        final Long addedWhen = NTP.getTime();
        if (addedWhen == null) {
            return;
        }

        // Serialize using lock to prevent repository deadlocks
        if (!mergePeersLock.tryLock()) {
            return;
        }

        try {
            // Merging peers isn't critical so don't block for a repository instance.

            this.mergePeersUnlocked(addedBy, addedWhen, peerAddresses, recentlyConnectedPeerAddresses);

        } catch (DataException e) {
            // Already logged by this.mergePeersUnlocked()
        } finally {
            mergePeersLock.unlock();
        }
    }

    private boolean mergePeersUnlocked(String addedBy, long addedWhen, List<PeerAddress> peerAddresses,
                                       List<PeerAddress> recentlyConnectedPeerAddresses)
            throws DataException {
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        if (fixedNetwork != null && !fixedNetwork.isEmpty()) {
            return false;
        }
        boolean updatedRecentlyConnectedPeer = false;
        List<PeerData> newPeers;
        synchronized (this.allKnownPeers) {
            for (PeerData knownPeerData : this.allKnownPeers) {
                updatedRecentlyConnectedPeer |= markRecentlyConnectedPeerData(knownPeerData,
                        addedWhen, recentlyConnectedPeerAddresses);

                // Filter out duplicates, without resolving via DNS
                Predicate<PeerAddress> isKnownAddress = peerAddress -> knownPeerData.getAddress().equals(peerAddress);
                peerAddresses.removeIf(isKnownAddress);
            }

            if (peerAddresses.isEmpty()) {
                return updatedRecentlyConnectedPeer;
            }

            // Add leftover peer addresses to known peers list
            newPeers = peerAddresses.stream()
                    .map(peerAddress -> {
                        PeerData peerData = new PeerData(peerAddress, addedWhen, addedBy);
                        markRecentlyConnectedPeerData(peerData, addedWhen, recentlyConnectedPeerAddresses);
                        return peerData;
                    })
                    .collect(Collectors.toList());

            this.allKnownPeers.addAll(newPeers);

            return true;
        }
    }

    static boolean markRecentlyConnectedPeerData(PeerData peerData, long timestamp,
                                                 List<PeerAddress> recentlyConnectedPeerAddresses) {
        if (peerData == null || recentlyConnectedPeerAddresses == null)
            return false;

        boolean isRecentlyConnected = recentlyConnectedPeerAddresses.stream()
                .anyMatch(peerAddress -> peerData.getAddress().equals(peerAddress));
        if (!isRecentlyConnected)
            return false;

        peerData.setLastAttempted(timestamp);
        peerData.setLastConnected(timestamp);
        return true;
    }

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        for (Peer peer : getImmutableHandshakedPeers()) {
            if (this.isShuttingDown)
                return;

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            // Use PeerSendManager for retry logic and backpressure handling
            try {
                PeerSendManager sendManager = PeerSendManagement.getInstance().getOrCreateSendManager(peer, false);
                
                
                // Calculate estimated message size for queue management
                int estimatedSize;
                try {
                    byte[] messageBytes = message.toBytes();
                    estimatedSize = messageBytes != null ? messageBytes.length : 1024;
                } catch (MessageException e) {
                    LOGGER.debug("Failed to calculate message size for broadcast, using default: {}", e.getMessage());
                    estimatedSize = 1024;
                }
                
                // Use HIGH_PRIORITY for broadcasts since they're important
                sendManager.queueMessageFactoryWithPriority(
                    PeerSendManager.HIGH_PRIORITY,
                    () -> message,
                    estimatedSize,
                    null  // No hash tracking for broadcast messages
                );
            } catch (MessageException e) {
                // PeerSendManager rejected the message (cooldown, etc.)
                LOGGER.debug("PeerSendManager rejected broadcast message to {}: {}", peer, e.getMessage());
                
                // Only disconnect if the socket is actually gone
                if (peer.getSocketChannel() == null || !peer.getSocketChannel().isOpen()) {
                    peer.disconnect("failed to broadcast message");
                }
            }
        }
    }

    // Shutdown

    public void shutdown() {
        this.isShuttingDown = true;
        this.inboundReachability.setListenSocketAvailable(false);
        this.inboundReachability.setPortMapped(false);

        // Close listen socket to prevent more incoming connections
        if (this.serverChannel != null && this.serverChannel.isOpen()) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
                // Not important
            }
        }
        closeI2PChainFallback();

        // Stop I/O and scheduler threads
        if (this.ioThread != null && this.ioThread.isAlive()) {
            this.ioThread.interrupt();
            try {
                this.ioThread.join(5000);
                if (this.ioThread.isAlive())
                    LOGGER.warn("Network I/O thread did not terminate in time");
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for Network I/O thread");
            }
        }
        if (this.schedulerThread != null && this.schedulerThread.isAlive()) {
            this.schedulerThread.interrupt();
            try {
                this.schedulerThread.join(2000);
                if (this.schedulerThread.isAlive())
                    LOGGER.warn("Network scheduler thread did not terminate in time");
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for Network scheduler thread");
            }
        }
        // Shutdown worker pool
        try {
            this.networkWorkerPool.shutdown();
            if (!this.networkWorkerPool.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                this.networkWorkerPool.shutdownNow();
                if (!this.networkWorkerPool.awaitTermination(2000, TimeUnit.MILLISECONDS))
                    LOGGER.warn("Network worker pool did not terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for Network worker pool to terminate");
            this.networkWorkerPool.shutdownNow();
        }

        try( Repository repository = RepositoryManager.getRepository() ){

            // reset all known peers in database
            int deletedCount = repository.getNetworkRepository().deleteAllPeers();

            LOGGER.debug("Deleted {} known peers", deletedCount);

            List<PeerData> knownPeersToProcess;
            synchronized (this.allKnownPeers) {
                knownPeersToProcess = new ArrayList<>(this.allKnownPeers);
            }

            int addedPeerCount = 0;

            // save all known peers for next start up
            for (PeerData knownPeerToProcess : knownPeersToProcess) {
                repository.getNetworkRepository().save(knownPeerToProcess);
                addedPeerCount++;
            }

            repository.saveChanges();

            LOGGER.debug("Added {} known peers", addedPeerCount);
        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
        }

        // Release uPnP if it was enabled
        try {
            PortMapperFactory.getInstance().closeTcpPort(Settings.getInstance().getListenPort());
        } catch (Exception e) {
            // do nothing
        }
        // Close all peer connections
        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.shutdown();
        }
        // Release selector and pending-write set to avoid resource leaks
        this.channelsPendingWrite.clear();
        if (this.channelSelector != null && this.channelSelector.isOpen()) {
            try {
                this.channelSelector.close();
            } catch (IOException e) {
                LOGGER.debug("Error closing channel selector: {}", e.getMessage());
            }
        }
    }

}
