package org.qortium.network;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortium.controller.arbitrary.ArbitraryDataFileManager;
import org.qortium.controller.arbitrary.ArbitraryMetadataManager;
import org.qortium.crypto.Crypto;
import org.qortium.data.network.PeerData;
import org.qortium.data.network.KnownPeerDiagnostic;
import org.qortium.data.network.KnownPeerDiagnostics;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.network.i2p.SamSession;
import org.qortium.network.message.*;
import org.qortium.network.task.*;
import org.qortium.network.upnp.PortMapperFactory;
import org.qortium.network.upnp.PortMappingResult;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.utils.Base58;
import org.qortium.utils.DaemonThreadFactory;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

// For managing arbitrary data between peers
public class NetworkData {
    private static final Logger LOGGER = LogManager.getLogger(NetworkData.class);

    // the maximum number of pending connections the operating system will allow to queue up for the server socket
    private static final int LISTEN_BACKLOG = 5;

    // How long before retrying after a connection failure, in milliseconds.
    private static final long CONNECT_FAILURE_BACKOFF = 2 * 60 * 1000L; // ms
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

    //  Maximum time allowed for handshake to complete, in milliseconds.
    private static final long HANDSHAKE_TIMEOUT = 60 * 1000L; // ms
    private static final long HANDSHAKE_CLEANUP_INTERVAL = 5 * 1000L; // ms
    private static final int I2P_FORWARD_DESTINATION_TIMEOUT = 5 * 1000; // ms
    private static final long I2P_DATA_START_RETRY_DELAY = 60 * 1000L; // ms

    private static final long NETWORK_EPC_KEEPALIVE = 5L; // seconds

    private static final long DISCONNECTION_CHECK_INTERVAL = 180 * 1000L; // milliseconds - 3min

    // ---- Data-layer gossip (PEERS) bounds ----
    // The data overlay has NO compensating age-prune for allKnownPeers (unlike the chain layer's
    // Network.prunePeers, which evicts 'old' peers). Gossiped entries also carry no lastAttempted/
    // lastConnected timestamps, so an age-based prune copied from the chain layer would never evict
    // them. To stop a hostile (or merely chatty) data peer from growing allKnownPeers without bound,
    // we (a) cap how many advertised addresses we process from a single PEERS message, and (b) cap
    // the total size of allKnownPeers, evicting the OLDEST gossiped entries first (never seeds,
    // chain-discovered peers, or currently-connected peers).

    /** Marker stored in PeerData.addedBy for entries learned via untrusted PEERS gossip. */
    static final String GOSSIP_ADDED_BY = "data-gossip";

    /**
     * Max advertised addresses accepted from a single PEERS message. A PEERS message can carry on the
     * order of a million addresses (10MB MAX_DATA_SIZE), so bound per-message work regardless of cap.
     */
    static final int MAX_GOSSIPED_PEERS_PER_MESSAGE = 1000;

    /**
     * Hard cap on the total number of entries kept in allKnownPeers. When gossip would push us over
     * this, the oldest gossiped (untrusted) entries are evicted to make room. Seeds, chain-discovered
     * peers and connected peers are never evicted by this cap.
     */
    static final int MAX_KNOWN_PEERS = 10000;

    // Generate our node keys / ID
    private final Ed25519PrivateKeyParameters edPrivateKeyParams = new Ed25519PrivateKeyParameters(new SecureRandom());
    private final Ed25519PublicKeyParameters edPublicKeyParams = edPrivateKeyParams.generatePublicKey();
    private final String ourNodeId = Crypto.toNodeAddress(edPublicKeyParams.getEncoded());
    public final static int MAX_NODEID_SIZE = 34;

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
     * Maintain a list for each subset of peers:
     * - A synchronizedList, to be modified when peers are added/removed
     */
    private final List<Peer> connectedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> handshakedPeers = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> outboundHandshakedPeers = Collections.synchronizedList(new ArrayList<>());

    //  Count threads per message type in order to enforce limits
    private final Map<MessageType, Integer> threadsPerMessageType = Collections.synchronizedMap(new HashMap<>());

    //  Keep track of total thread count, to warn when the thread pool is getting low
    private int totalThreadCount = 0;

    // * Thresholds at which to warn about the number of active threads
    private final int threadCountWarningThreshold = (int) (Settings.getInstance().getMaxNetworkThreadPoolSize() * 0.9f);
    private final Integer threadCountPerMessageTypeWarningThreshold = Settings.getInstance().getThreadCountPerMessageTypeWarningThreshold();

    private final List<PeerAddress> selfPeers = new ArrayList<>();
    private final Set<PeerAddress> connectingI2PPeers = ConcurrentHashMap.newKeySet();

    private final PeerDirectionState peerDirectionState = new PeerDirectionState();
    
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

    private static final long ADDRESS_CACHE_EXPIRY = 24 * 60 * 60 * 1000L; // 24 hours
    
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
    /** Produces Connect tasks and submits to worker pool. */
    private Thread schedulerThread;
    /** Message handling only (MessageTask, ConnectTask). Never does I/O. */
    private ExecutorService networkDataWorkerPool;
    /** Scheduler state: when to try next connect. */
    private final AtomicLong nextConnectTaskTimestamp = new AtomicLong(0L);

    /**
     * Dedicated thread pool for processing ARBITRARY_DATA_FILE messages.
     * This prevents chunk validation and disk I/O from blocking the NetworkProcessor
     * thread, which needs to quickly drain socket buffers via selector.select().
     * 
     * Pool size: 10 threads to handle multiple concurrent chunk writes without
     * blocking the network read loop.
     */
    private static final ExecutorService chunkProcessorPool = new ThreadPoolExecutor(
            5, // corePoolSize: maintain 5 threads for chunk processing
            20, // maximumPoolSize: scale up to 20 for burst traffic
            60L, TimeUnit.SECONDS, // keepAliveTime: idle threads die after 1 minute
            new LinkedBlockingQueue<>(100), // bounded queue to prevent memory bloat
            new NamedThreadFactory("ChunkProcessor", Thread.NORM_PRIORITY),
            new ThreadPoolExecutor.AbortPolicy() // reject instead of running chunk disk I/O on the caller
    );

     /** Dedicated pool for QDN force-connect so processFileHashes doesn't block on TCP connect. Shut down in shutdown(). */
    private static final ExecutorService forceConnectExecutor = Executors.newCachedThreadPool(
        new DaemonThreadFactory("QDN-force-connect", Thread.NORM_PRIORITY));
    
    private Selector channelSelector;
    private ServerSocketChannel serverChannel;
    private SelectionKey serverSelectionKey;
    private volatile I2PStreamProvider dataI2PStreamProvider;
    private volatile ServerSocketChannel i2pServerChannel;
    private final Set<SelectableChannel> channelsPendingWrite = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean i2pStartupInProgress = new AtomicBoolean(false);
    private final AtomicLong i2pStartupAttemptCounter = new AtomicLong(0L);
    private final AtomicBoolean i2pFallbackUnavailableLogged = new AtomicBoolean(false);
    /** Coalesces OP_WRITE wakeups: only the first caller per select-cycle actually wakes the selector. */
    private final java.util.concurrent.atomic.AtomicBoolean selectorWakeupPending = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Lock for atomic peer list operations to prevent race conditions.
     * Used to ensure peer additions/removals are atomic across both connectedPeers and handshakedPeers.
     */
    private final Object peerListsLock = new Object();

    private final List<String> ourExternalIpAddressHistory = new ArrayList<>();
    private String ourExternalIpAddress = null;
    private int ourExternalPort = Settings.getInstance().getQDNListenPort();
    private final InboundReachability inboundReachability = new InboundReachability();
    private volatile boolean isShuttingDown = false;

    // Constructors
    private NetworkData() {
        maxMessageSize = BlocksMessage.maxMessageSizeForMaxBlockSize(BlockChain.getInstance().getMaxBlockSize());

        minOutboundPeers = Settings.getInstance().getMinOutboundPeers();
        maxPeers = Settings.getInstance().getMaxPeers();

        int networkDataPriority = Settings.getInstance().getNetworkThreadPriority();
        if (networkDataPriority > 1)
            networkDataPriority--;  // Create QDN with a lower thread priority than the primary network

        // Worker pool: message handling only (MessageTask, ConnectTask). I/O runs on dedicated ioThread.
        this.networkDataWorkerPool = new ThreadPoolExecutor(
                10, 20,
                NETWORK_EPC_KEEPALIVE, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory("NetworkData-Worker", networkDataPriority));
    }

    public void start() throws IOException, DataException {
        LOGGER.trace("Running start()");
        // Grab QDN port from settings
        int listenPort = Settings.getInstance().getQDNListenPort();

        // Grab bind addresses from settings
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
                    LOGGER.trace("Success - Bound to interface: {}:{}", this.bindAddress,listenPort);
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
            // I2P-only (IP not in allowedTransports): do not bind/advertise a public direct QDN listener.
            this.inboundReachability.setListenSocketAvailable(false);
            LOGGER.info("Direct TCP (IP) disabled by allowedTransports - QDN data network listening over I2P only");
        }

        // Attempt to set up UPnP for QDN. All errors are ignored.
        int qdnPort = Settings.getInstance().getQDNListenPort();
        if (Settings.getInstance().isUPnPEnabled() && Settings.getInstance().isIPAllowed()) {
            PortMappingResult portMappingResult = PortMapperFactory.getInstance().openTcpPort(qdnPort, "Qortium QDN");
            if (portMappingResult.isMapped()) {
                this.inboundReachability.setPortMapped(true);
                portMappingResult.getExternalAddress()
                        .ifPresent(externalAddress -> this.ourExternalIpAddress = externalAddress.getHostAddress());
                LOGGER.info("UPnP Mapped for QDN, port: {}", qdnPort);
            } else {
                this.inboundReachability.setPortMapped(false);
                LOGGER.warn("Unable to map QDN port: {} with UPnP, port in use?", qdnPort);
            }
        }
        else {
            this.inboundReachability.setPortMapped(false);
            PortMapperFactory.getInstance().closeTcpPort(qdnPort);
        }

        // Seed the data layer's known-peers list from configured initialDataPeers before the IO/scheduler
        // loops begin, so the data layer has bootstrap peers to dial immediately.
        seedInitialDataPeers();

        this.ioThread = new Thread(this::runIOLoop, "NetworkData-IO");
        this.ioThread.setDaemon(false);
        this.ioThread.start();
        this.schedulerThread = new Thread(this::runSchedulerLoop, "NetworkData-Scheduler");
        this.schedulerThread.setDaemon(false);
        this.schedulerThread.start();

        startI2PDataFallbackAsync();
    }

    /**
     * Seed {@link #allKnownPeers} from the configured {@code initialDataPeers} list, so the data layer has
     * bootstrap peers to dial at startup before peer-exchange gossip kicks in. Only entries reachable on a
     * transport this node supports are kept: clearnet entries require {@link Settings#isIPAllowed()} and
     * I2P (.b32.i2p) entries require {@link Settings#isI2PEnabled()}. Seeds carry no nodeId.
     */
    void seedInitialDataPeers() {
        long addedWhen = NTP.getTime() != null ? NTP.getTime() : System.currentTimeMillis();

        List<PeerData> seeds = new ArrayList<>();
        for (String addr : Settings.getInstance().getInitialDataPeers()) {
            try {
                PeerAddress peerAddress = PeerAddress.fromString(addr);
                boolean reachable = peerAddress.isI2P()
                        ? Settings.getInstance().isI2PEnabled()
                        : Settings.getInstance().isIPAllowed();
                if (!reachable) {
                    LOGGER.debug("Skipping initialDataPeers '{}': transport not supported by this node", addr);
                    continue;
                }
                seeds.add(new PeerData(peerAddress, addedWhen, "INIT"));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping invalid initialDataPeers '{}': {}", addr, e.getMessage());
            }
        }

        if (!seeds.isEmpty()) {
            int added = addKnownPeersIfMissing(seeds, null);
            LOGGER.info("Seeded {} data peer(s) from initialDataPeers", added);
        }
    }

    private void startI2PDataFallbackAsync() {
        if (!this.i2pStartupInProgress.compareAndSet(false, true))
            return;

        Thread i2pStarter = new Thread(this::startI2PDataFallback, "NetworkData-I2P-Startup");
        i2pStarter.setDaemon(true);
        i2pStarter.start();
    }

    private void startI2PDataFallback() {
        try {
            while (!this.isShuttingDown && !Thread.currentThread().isInterrupted()) {
                Settings settings = Settings.getInstance();
                if (!settings.isQdnEnabled() || !settings.isI2PEnabled()) {
                    LOGGER.info("I2P data fallback disabled by settings");
                    return;
                }

                if (settings.isI2PEmbeddedRouterEnabled())
                    LOGGER.warn("Embedded I2P router is not implemented yet; using the SAM provider");

                if (startI2PDataFallbackAttempt(settings))
                    return;

                try {
                    Thread.sleep(I2P_DATA_START_RETRY_DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            this.i2pStartupInProgress.set(false);
            restartI2PDataFallbackIfSessionDown();
        }
    }

    private boolean startI2PDataFallbackAttempt(Settings settings) {
        // No session-up callback: the I2P data destination (I2P_QDN) is never advertised in a HELLO, so
        // there is nothing to re-advertise when the data SAM session comes up. The I2P data layer is
        // bootstrapped via initialDataPeers plus data-layer gossip instead.
        I2PStreamProvider provider = new SamSession(settings.getI2PSamHost(), settings.getI2PSamPort(),
                nextI2PDataSessionId(), settings.getI2PDataKeyPath(), null, this::onI2PDataSessionDown);
        ServerSocketChannel forwardServerChannel = null;

        try {
            provider.start();
            if (this.isShuttingDown)
                throw new IOException("NetworkData is shutting down");

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
                throw new IOException("NetworkData is shutting down");
            if (!provider.isSessionUp())
                throw new IOException("I2P data session went down during startup");

            this.dataI2PStreamProvider = provider;
            this.i2pServerChannel = forwardServerChannel;
            this.i2pFallbackUnavailableLogged.set(false);
            LOGGER.info("NetworkData I2P fallback up at {} (control/tunnels established; inbound reachability depends on LeaseSet publication)",
                    provider.getLocalB32());
            return true;
        } catch (IOException | RuntimeException e) {
            logI2PDataFallbackUnavailable(settings, e);
            if (forwardServerChannel != null) {
                try {
                    forwardServerChannel.close();
                } catch (IOException closeException) {
                    LOGGER.debug("Error closing I2P data forward listener: {}", closeException.getMessage());
                }
            }
            provider.close();
            this.dataI2PStreamProvider = null;
            this.i2pServerChannel = null;
            return false;
        }
    }

    private void logI2PDataFallbackUnavailable(Settings settings, Exception e) {
        long retrySeconds = TimeUnit.MILLISECONDS.toSeconds(I2P_DATA_START_RETRY_DELAY);
        if (this.i2pFallbackUnavailableLogged.compareAndSet(false, true)) {
            LOGGER.info("NetworkData I2P fallback unavailable via SAM at {}:{} ({}). Direct TCP remains active; "
                            + "install/run i2pd or remove I2P from allowedTransports to disable I2P retries. Retrying in {} seconds",
                    settings.getI2PSamHost(), settings.getI2PSamPort(), e.getMessage(), retrySeconds);
            return;
        }

        LOGGER.debug("NetworkData I2P fallback still unavailable via SAM at {}:{} ({}); retrying in {} seconds",
                settings.getI2PSamHost(), settings.getI2PSamPort(), e.getMessage(), retrySeconds);
    }

    private String nextI2PDataSessionId() {
        return "qortium-data-" + this.ourNodeId + "-" + this.i2pStartupAttemptCounter.incrementAndGet();
    }

    // Getters / setters

    private static class SingletonContainer {
        private static final NetworkData INSTANCE = new NetworkData();
    }

    public Map<MessageType, Integer> getThreadsPerMessageType() {
        return this.threadsPerMessageType;
    }

    public int getTotalThreadCount() {
        synchronized (this) {
            return this.totalThreadCount;
        }
    }

    public static NetworkData getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public String getBindAddress() {
        return this.bindAddress;
    }

    public int getMaxPeers() {
        return this.maxPeers;
    }

    public byte[] getMessageMagic() {
        return BlockChain.getInstance().getMessageMagic(Settings.getInstance().isTestNet());
    }

    public String getOurNodeId() {
        return this.ourNodeId;
    }

    public String getI2PDataDestination() {
        I2PStreamProvider provider = this.dataI2PStreamProvider;
        if (provider == null || !provider.isSessionUp())
            return null;

        return provider.getLocalB32();
    }

    private void onI2PDataSessionDown() {
        Thread t = new Thread(this::restartI2PDataFallbackIfSessionDown, "i2p-restart-data");
        t.setDaemon(true);
        t.start();
    }

    private void restartI2PDataFallbackIfSessionDown() {
        if (this.isShuttingDown || this.i2pStartupInProgress.get())
            return;

        I2PStreamProvider provider = this.dataI2PStreamProvider;
        if (provider == null || provider.isSessionUp())
            return;

        LOGGER.warn("NetworkData I2P fallback session went down; restarting fallback");
        closeI2PDataFallback();
        startI2PDataFallbackAsync();
    }

    public SocketChannel connectI2PDataPeer(String remoteB32) throws IOException {
        if (!Settings.getInstance().isI2PEnabled()) {
            LOGGER.debug("I2P data peer {} not connected because I2P is disabled", remoteB32);
            return null;
        }

        I2PStreamProvider provider = this.dataI2PStreamProvider;
        if (provider == null) {
            LOGGER.debug("I2P data peer {} not connected because the data fallback session is not ready", remoteB32);
            startI2PDataFallbackAsync();
            return null;
        }

        if (!provider.isSessionUp()) {
            LOGGER.warn("I2P data peer {} not connected because the data fallback session is down; restarting fallback", remoteB32);
            closeI2PDataFallback();
            startI2PDataFallbackAsync();
            return null;
        }

        LOGGER.info("Connecting to I2P data peer {}", remoteB32);
        try {
            SocketChannel socketChannel = provider.connect(remoteB32);
            if (socketChannel == null)
                LOGGER.warn("I2P data peer {} connect failed", remoteB32);
            else
                LOGGER.info("Connected to I2P data peer {}", remoteB32);

            return socketChannel;
        } catch (IOException e) {
            LOGGER.warn("I2P data peer {} connect failed: {}", remoteB32, e.getMessage());
            throw e;
        }
    }

    private void closeI2PDataFallback() {
        ServerSocketChannel serverChannel = this.i2pServerChannel;
        this.i2pServerChannel = null;
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                LOGGER.debug("Error closing I2P data forward listener: {}", e.getMessage());
            }
        }

        I2PStreamProvider provider = this.dataI2PStreamProvider;
        this.dataI2PStreamProvider = null;
        if (provider != null)
            provider.close();
    }

    protected byte[] getOurPublicKey() {
        return this.edPublicKeyParams.getEncoded();
    }

    /**
     * Maximum message size (bytes). Needs to be at least maximum block size + MAGIC + message type, etc.
     */
    protected int getMaxMessageSize() {
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
        int failureCount = this.peerDirectionState.recordOutboundFailure(peerAddress, nodeId);
        if (nodeId != null)
            LOGGER.debug("Recorded outbound failure #{} for nodeId {}", failureCount, nodeId.substring(0, 8));
        else
            LOGGER.debug("Recorded outbound failure #{} for IP {} (nodeId unknown)",
                    failureCount, PeerAddress.fromString(peerAddress).getHost());
    }

    /**
     * Check if outbound connections to the given peer have been failing recently.
     * Returns true if there have been at least OUTBOUND_FAILURE_THRESHOLD failures
     * within the OUTBOUND_FAILURE_WINDOW_MS time window.
     * Prefers checking by nodeId, falls back to IP if nodeId unknown.
     */
    public boolean hasRecentOutboundFailures(String nodeId, String peerIP) {
        return this.peerDirectionState.hasRecentOutboundFailures(nodeId, peerIP);
    }

    public boolean hasRecentOutboundFailureEvidence(String nodeId, String peerIP) {
        return this.peerDirectionState.hasRecentOutboundFailureEvidence(nodeId, peerIP);
    }

    boolean shouldBeOutboundTo(String theirNodeId, String peerIP) {
        return PeerDirectionPolicy.shouldBeOutbound(this.ourNodeId, theirNodeId, this.canAcceptInboundData(),
                isKnownDialableI2PPeer(peerIP),
                hasRecentOutboundFailureEvidence(theirNodeId, peerIP));
    }

    private static boolean isKnownDialableI2PPeer(String peerIP) {
        return peerIP != null && peerIP.toLowerCase(Locale.ROOT).endsWith(".b32.i2p");
    }

    /**
     * Clear outbound failure records for the given peer.
     * Called when a connection is successfully established.
     * Clears both IP-based and nodeId-based tracking.
     */
    public void clearOutboundFailures(String peerIP, String nodeId) {
        int removedCount = this.peerDirectionState.clearOutboundFailures(peerIP, nodeId);
        if (removedCount > 0)
            LOGGER.debug("Cleared outbound failures for peer IP {} / nodeId {} (was {} failures)",
                    peerIP, nodeId == null ? "unknown" : nodeId.substring(0, 8), removedCount);
    }

    /**
     * Periodically clean up stale outbound failure records to prevent memory accumulation.
     * Called from checkLongestConnection during prunePeers() (every 90 seconds).
     */
    private void cleanupStaleOutboundFailures() {
        int removed = this.peerDirectionState.cleanupStaleOutboundFailures();
        if (removed > 0)
            LOGGER.debug("Cleaned up {} stale outbound failure records", removed);
    }

    // Direction mismatch tracking

    /**
     * Record that a peer was disconnected due to direction mismatch.
     * Tracks by nodeId (survives IP/port changes from UPnP, DHCP, etc).
     * Uses exponential backoff to prevent thrash while allowing eventual retry.
     */
    public void recordDirectionMismatch(String nodeId) {
        PeerDirectionState.DirectionMismatchRecord record = this.peerDirectionState.recordDirectionMismatch(nodeId);
        LOGGER.debug("Recorded direction mismatch #{} for nodeId {} - backoff: {}ms", 
                record.count, nodeId.substring(0, 8), record.backoffDuration);
    }

    /**
     * Check if a peer nodeId has a recent direction mismatch and should be skipped for outbound.
     * Returns true if within backoff period, false otherwise.
     */
    public boolean hasRecentDirectionMismatch(String nodeId) {
        return this.peerDirectionState.hasRecentDirectionMismatch(nodeId);
    }

    /**
     * Clear direction mismatch record for the given peer nodeId.
     * Called when an inbound connection from this peer succeeds,
     * indicating they can reach us and we don't need to avoid them.
     */
    public void clearDirectionMismatch(String nodeId) {
        int removedCount = this.peerDirectionState.clearDirectionMismatch(nodeId);
        if (removedCount > 0) {
            LOGGER.debug("Cleared direction mismatch for nodeId {} (was {} mismatches)", 
                    nodeId.substring(0, 8), removedCount);
        }
    }

    /**
     * Check if a peer address is in the fixed network list.
     * Fixed peers are never skipped due to direction mismatch (prevents isolation).
     */
    private boolean isFixedPeer(PeerAddress address) {
        List<String> fixedNetwork = Settings.getInstance().getFixedNetwork();
        if (fixedNetwork == null || fixedNetwork.isEmpty())
            return false;

        return !ipNotInFixedList(address, fixedNetwork);
    }

    private boolean isInitialDataPeer(PeerAddress address) {
        for (String initialDataPeer : Settings.getInstance().getInitialDataPeers()) {
            try {
                if (address.equals(PeerAddress.fromString(initialDataPeer)))
                    return true;
            } catch (IllegalArgumentException e) {
                LOGGER.debug("Ignoring invalid initialDataPeers entry '{}': {}", initialDataPeer, e.getMessage());
            }
        }

        return false;
    }

    public boolean ipNotInFixedList(PeerAddress address, List<String> fixedNetwork) {
        for (String ipAddress : fixedNetwork) {
            String[] bits = ipAddress.split(":");
            if (bits.length >= 1 && bits.length <= 2 && address.getHost().equals(bits[0]))
                return false;
        }

        return true;
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
        int removedMismatches = this.peerDirectionState.cleanupStaleDirectionMismatches();
        int removedCache = 0;

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

    public StatsSnapshot getStatsSnapshot() {
        StatsSnapshot snapshot = new StatsSnapshot();
        if (this.networkDataWorkerPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) this.networkDataWorkerPool;
            snapshot.activeThreadCount = tpe.getActiveCount();
            snapshot.greatestActiveThreadCount = Math.max(snapshot.activeThreadCount, snapshot.greatestActiveThreadCount);
            snapshot.consumerCount = snapshot.activeThreadCount;
        }
        snapshot.spawnFailures = 0;
        return snapshot;
    }


    public List<PeerData> getAllKnownPeers() {
        synchronized (this.allKnownPeers) {
            return new ArrayList<>(this.allKnownPeers);
        }
    }

    public KnownPeerDiagnostics getKnownPeerDiagnostics(Long now) {
        final Long diagnosticNow = now != null ? now : System.currentTimeMillis();

        KnownPeerDiagnostics diagnostics = new KnownPeerDiagnostics(KnownPeerDiagnostics.Layer.DATA);
        diagnostics.knownCount = this.getAllKnownPeers().size();
        diagnostics.connectedCount = this.getImmutableConnectedPeers().size();
        diagnostics.handshakedCount = this.getImmutableHandshakedPeers().size();
        diagnostics.outboundHandshakedCount = this.getImmutableOutboundHandshakedPeers().size();
        diagnostics.i2pSessionUp = this.getI2PDataDestination() != null;
        diagnostics.allowedTransports = Settings.getInstance().getAllowedTransports().stream()
                .map(Enum::name)
                .collect(Collectors.toList());
        diagnostics.qdnFallbackCandidateCount = countQdnFallbackCandidateChainPeers();

        List<KnownPeerDiagnostic> peerDiagnostics = this.getAllKnownPeers().stream()
                .map(peerData -> buildKnownPeerDiagnostic(peerData, diagnosticNow))
                .collect(Collectors.toList());

        boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();
        boolean hasNormallyConnectablePeer = peerDiagnostics.stream().anyMatch(peer -> peer.reasons.isEmpty());
        for (KnownPeerDiagnostic peerDiagnostic : peerDiagnostics) {
            if (peerDiagnostic.reasons.isEmpty()) {
                peerDiagnostic.connectable = true;
            } else if (hasNoPeers && !hasNormallyConnectablePeer && peerDiagnostic.hasOnlyBackoffReason()) {
                peerDiagnostic.connectable = true;
                peerDiagnostic.isolationRetryCandidate = true;
            }
            diagnostics.addPeer(peerDiagnostic);
        }

        return diagnostics;
    }

    private KnownPeerDiagnostic buildKnownPeerDiagnostic(PeerData peerData, Long now) {
        KnownPeerDiagnostic diagnostic = new KnownPeerDiagnostic(peerData);
        PeerAddress peerAddress = peerData.getAddress();
        Peer connectedPeer = findConnectedPeer(peerAddress);
        Peer handshakedPeer = findHandshakedPeer(peerAddress);
        Peer displayPeer = handshakedPeer != null ? handshakedPeer : connectedPeer;
        CachedNodeIdInfo cachedInfo = addressToNodeIdCache.get(peerAddress.toString());

        if (cachedInfo != null)
            diagnostic.nodeId = cachedInfo.nodeId;
        if (displayPeer != null) {
            diagnostic.outbound = displayPeer.isOutbound();
            if (diagnostic.nodeId == null)
                diagnostic.nodeId = displayPeer.getPeersNodeId();
        }

        diagnostic.connected = connectedPeer != null;
        diagnostic.handshaked = handshakedPeer != null;

        if (isFixedPeer(peerAddress))
            diagnostic.tags.add(KnownPeerDiagnostic.Tag.FIXED_PEER);
        if (isInitialDataPeer(peerAddress))
            diagnostic.tags.add(KnownPeerDiagnostic.Tag.INITIAL_DATA_PEER);
        if ("Network-fallback".equals(peerData.getAddedBy()))
            diagnostic.tags.add(KnownPeerDiagnostic.Tag.NETWORK_FALLBACK_CANDIDATE);

        if (hasRecentConnectFailure(peerData, now)) {
            diagnostic.inBackoff = true;
            diagnostic.backoffUntil = peerData.getLastAttempted() + getConnectFailureBackoff(peerData);
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.RECENT_CONNECT_FAILURE);
        }

        synchronized (this.selfPeers) {
            if (isSelfPeer.test(peerData))
                diagnostic.reasons.add(KnownPeerDiagnostic.Reason.SELF);
        }

        if (isLocalI2PPeer.test(peerData))
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.LOCAL_I2P_ADDRESS);
        if (diagnostic.connected)
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.ALREADY_CONNECTED);
        if (isConnectingI2PPeer.test(peerData))
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.ALREADY_CONNECTING_I2P);
        if (isI2PAlternativeForConnectedPeer.test(peerData))
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.I2P_ALTERNATIVE_ALREADY_CONNECTED);

        if (!Settings.getInstance().isIPAllowed() && !peerAddress.isI2P())
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.TRANSPORT_NOT_ALLOWED);
        if (!Settings.getInstance().isI2PEnabled() && peerAddress.isI2P())
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.TRANSPORT_NOT_ALLOWED);
        if (peerAddress.isI2P() && this.getI2PDataDestination() == null)
            diagnostic.reasons.add(KnownPeerDiagnostic.Reason.I2P_SESSION_DOWN);

        if (cachedInfo != null) {
            Peer existingPeer = findConnectedPeerByNodeId(cachedInfo.nodeId);
            if (existingPeer != null && !existingPeer.getPeerData().getAddress().equals(peerAddress)
                    && !allowsPreferredReplacement(existingPeer, peerData, cachedInfo.nodeId)) {
                diagnostic.reasons.add(KnownPeerDiagnostic.Reason.ALREADY_CONNECTED_BY_NODE_ID);
            }

            if (!isFixedPeer(peerAddress) && !isInitialDataPeer(peerAddress)
                    && hasRecentDirectionMismatch(cachedInfo.nodeId)) {
                diagnostic.reasons.add(KnownPeerDiagnostic.Reason.DIRECTION_MISMATCH);
            }
        }

        return diagnostic;
    }

    private Peer findConnectedPeer(PeerAddress peerAddress) {
        return this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getPeerData().getAddress().equals(peerAddress))
                .findFirst()
                .orElse(null);
    }

    private Peer findHandshakedPeer(PeerAddress peerAddress) {
        return this.getImmutableHandshakedPeers().stream()
                .filter(peer -> peer.getPeerData().getAddress().equals(peerAddress))
                .findFirst()
                .orElse(null);
    }

    private Peer findConnectedPeerByNodeId(String nodeId) {
        if (nodeId == null)
            return null;

        return this.getImmutableConnectedPeers().stream()
                .filter(peer -> peer.getPeersNodeId() != null && peer.getPeersNodeId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    private boolean allowsPreferredReplacement(Peer existingPeer, PeerData peerData, String candidateNodeId) {
        if (!Settings.getInstance().isI2PPreferred()
                && existingPeer.getPeerData().getAddress().isI2P()
                && !peerData.getAddress().isI2P()) {
            return true;
        }

        String ourNodeId = this.getOurNodeId();
        if (ourNodeId == null)
            return false;

        boolean weShouldBeOutbound = shouldBeOutboundTo(candidateNodeId, peerData.getAddress().getHost());
        boolean outboundRecentlyFailed = hasRecentOutboundFailures(candidateNodeId, peerData.getAddress().getHost());
        return PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(existingPeer.isOutbound(),
                weShouldBeOutbound, outboundRecentlyFailed);
    }

    private long getConnectFailureBackoff(PeerData peerData) {
        return peerData.getAddress().isI2P() ? I2P_CONNECT_FAILURE_BACKOFF : CONNECT_FAILURE_BACKOFF;
    }

    private int countQdnFallbackCandidateChainPeers() {
        Network network = Network.getInstance();
        if (network == null)
            return 0;

        int count = 0;
        for (Peer networkPeer : network.getImmutableHandshakedPeers()) {
            if (!buildQdnPeerDataFromNetworkPeer(networkPeer, null, "Network-fallback").isEmpty())
                count++;
        }
        return count;
    }

    public PeerList getImmutableConnectedPeers() {
        return new PeerList(this.connectedPeers);
    }


    public void addConnectedPeer(Peer peer) {
        // ATOMIC: Synchronize for consistency with removeConnectedPeer()
        synchronized (this.connectedPeers) {
            this.connectedPeers.add(peer);
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
            }
        }
    }

    public List<PeerAddress> getSelfPeers() {
        synchronized (this.selfPeers) {
            return new ArrayList<>(this.selfPeers);
        }
    }

    public Peer getPeerByPeerData(PeerData pd) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pd);
    }

    public Peer getPeerByPeerAddress(PeerAddress pa) {
        PeerList handshakedSnapshot = this.getImmutableHandshakedPeers();
        return handshakedSnapshot.get(pa);
    }

    public boolean requestDataFromPeer(String peerAddressString, byte[] signature) {
        if (peerAddressString != null) {
            PeerAddress peerAddress = PeerAddress.fromString(peerAddressString);
            PeerData peerData; //= null;

            LOGGER.trace("Requesting data using NetworkData from {}", peerAddressString);
            // Reuse an existing PeerData instance if it's already in the known peers list
            synchronized (this.allKnownPeers) {
                peerData = this.allKnownPeers.stream()
                        .filter(knownPeerData -> knownPeerData.getAddress().equals(peerAddress))
                        .findFirst()
                        .orElse(null);
            }

            // When should we get a peer we don't know about?  We get our peers from the main NetWork
            if (peerData == null) {
                // Not a known peer, so we need to create one
                Long addedWhen =  NTP.getTime();
                String addedBy = "requestDataFromPeer";
                peerData = new PeerData(peerAddress, addedWhen, addedBy);
            }

            PeerList connectedSnapshot = this.getImmutableConnectedPeers();
            Peer connectedPeer = connectedSnapshot.get(peerAddress);

            boolean isConnected = (connectedPeer != null);
            boolean isHandshaked = this.getImmutableHandshakedPeers().contains(peerAddress);

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
                        Peer peer = new Peer(peerData, Peer.NETWORKDATA);
                        peer.setIsDataPeer(true);   // This is set when we make a connection
                        peer.addPendingSignatureRequest(signature);
                        return this.connectPeer(peer);
                        // If connection (and handshake) is successful, data will automatically be requested
                    }
                    else if (!isHandshaked) {
                        LOGGER.trace("Peer {} is connected but not handshaked. Not attempting a new connection.", peerAddress);
                        return false;
                    }

                } catch (InterruptedException e) {
                    LOGGER.warn("Interrupted when connecting to peer {}", peerAddress);
                    return false;
                }
            }
        }
        return false;
    }

    private boolean requestDataFromConnectedPeer(Peer connectedPeer, byte[] signature) {
        if (signature == null) {  // Nothing to do
            return false;
        }
        return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(connectedPeer, signature);
    }

    /**
     * Returns list of connected peers that have completed handshaking.
     */
    public PeerList getImmutableHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.handshakedPeers);
    }

    public void addHandshakedPeer(Peer peer) {
        // ATOMIC: Synchronize for consistency with removeHandshakedPeer()
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.add(peer);
        }
    
        // Also add to outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.addOutboundHandshakedPeer(peer);
        } else {
            // Only inbound connections prove we can accept inbound
            // Outbound connections only prove we can reach others, not that they can reach us
            this.inboundReachability.recordInboundHandshake();
        }
    }

    public void removeHandshakedPeer(Peer peer) {
        // CRITICAL: Use object identity (==), not equals()
        // Peer.equals() compares by address, which can fail to find the exact object
        synchronized (this.handshakedPeers) {
            this.handshakedPeers.removeIf(p -> p == peer);
        }

        // Also remove from outbound handshaked peers cache
        if (peer.isOutbound()) {
            this.removeOutboundHandshakedPeer(peer);
        }
    }

    /**
     * Returns list of peers we connected to that have completed handshaking.
     */
    public PeerList getImmutableOutboundHandshakedPeers() {
        // A new PeerList is created as a snapshot every time this is called
        return new PeerList(this.outboundHandshakedPeers);
    }

    public void addOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        // ATOMIC: Synchronize for consistency with removeOutboundHandshakedPeer()
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.add(peer);
        }
    }

    public void removeOutboundHandshakedPeer(Peer peer) {
        if (!peer.isOutbound()) {
            return;
        }
        synchronized (this.outboundHandshakedPeers) {
            this.outboundHandshakedPeers.removeIf(p -> p == peer);
        }
    }

    /**
     * Returns first peer that has completed handshaking and has matching public key.
     * Searches handshakedPeers directly as the authoritative source for completed handshakes.
     */
    public Peer getHandshakedPeerWithPublicKey(byte[] publicKey) {
        // Search handshakedPeers directly - this is the authoritative list for completed handshakes
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

        String localI2PDestination = this.getI2PDataDestination();
        return localI2PDestination != null && peerAddress.getHost().equalsIgnoreCase(localI2PDestination);
    }

    private final Predicate<PeerData> isI2PAlternativeForConnectedPeer = peerData -> {
        PeerAddress peerAddress = peerData.getAddress();
        if (!peerAddress.isI2P() || Settings.getInstance().isI2PPreferred())
            return false;

        return this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.getPeerData().getAddress().isI2P())
                .anyMatch(peer -> peerAdvertisesI2PAddress(peer, Handshake.I2P_QDN_CAPABILITY, peerAddress));
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

    private List<PeerData> buildQdnPeerDataFromNetworkPeer(Peer networkPeer, Long addedWhen, String addedBy) {
        List<PeerData> qdnPeers = new ArrayList<>(2);

        PeerAddress directAddress = getDirectQdnPeerAddress(networkPeer);
        if (directAddress != null)
            qdnPeers.add(new PeerData(directAddress, addedWhen, addedBy));

        PeerAddress i2pAddress = getI2PQdnPeerAddress(networkPeer);
        if (i2pAddress != null)
            qdnPeers.add(new PeerData(i2pAddress, addedWhen, addedBy));

        return qdnPeers;
    }

    private PeerAddress getDirectQdnPeerAddress(Peer networkPeer) {
        PeerAddress networkAddress = networkPeer.getPeerData().getAddress();
        if (networkAddress.isI2P())
            return null;

        Integer qdnPort = getQdnPortCapability(networkPeer);
        if (qdnPort == null || qdnPort <= 0)
            return null;

        try {
            return PeerAddress.fromString(networkAddress.getHost() + ":" + qdnPort);
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Peer {} advertised unusable QDN port {}: {}", networkAddress, qdnPort, e.getMessage());
            return null;
        }
    }

    private Integer getQdnPortCapability(Peer networkPeer) {
        Object qdnCapability = networkPeer.getPeerCapability("QDN");
        if (qdnCapability == null)
            return null;

        if (!(qdnCapability instanceof Number)) {
            LOGGER.warn("Peer {} has invalid QDN capability type: {}, skipping direct QDN address",
                    networkPeer.getPeerData().getAddress(), qdnCapability.getClass());
            return null;
        }

        return ((Number) qdnCapability).intValue();
    }

    private PeerAddress getI2PQdnPeerAddress(Peer networkPeer) {
        if (!Settings.getInstance().isI2PEnabled())
            return null;

        Object i2pCapability = networkPeer.getPeerCapability(Handshake.I2P_QDN_CAPABILITY);
        if (i2pCapability == null)
            return null;

        if (!(i2pCapability instanceof String)) {
            LOGGER.warn("Peer {} has invalid I2P_QDN capability type: {}, skipping I2P QDN address",
                    networkPeer.getPeerData().getAddress(), i2pCapability.getClass());
            return null;
        }

        try {
            PeerAddress i2pAddress = PeerAddress.fromString(((String) i2pCapability).trim());
            if (!i2pAddress.isI2P())
                throw new IllegalArgumentException("I2P_QDN was not an I2P address");
            if (isLocalI2PAddress(i2pAddress)) {
                LOGGER.debug("Peer {} advertised our own I2P QDN address {}, skipping",
                        networkPeer.getPeerData().getAddress(), i2pAddress);
                return null;
            }
            return i2pAddress;
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Peer {} advertised invalid I2P_QDN capability: {}",
                    networkPeer.getPeerData().getAddress(), e.getMessage());
            return null;
        }
    }

    private int addKnownPeersIfMissing(List<PeerData> candidatePeers, String nodeId) {
        int added = 0;

        synchronized (this.allKnownPeers) {
            for (PeerData candidatePeer : candidatePeers) {
                String address = candidatePeer.getAddress().toString();
                if (nodeId != null)
                    updateAddressToNodeIdCache(address, nodeId);

                boolean alreadyKnown = this.allKnownPeers.stream()
                        .anyMatch(peerData -> peerData.getAddress().equals(candidatePeer.getAddress()));
                if (alreadyKnown)
                    continue;

                this.allKnownPeers.add(candidatePeer);
                added++;
            }
        }

        return added;
    }

    private List<PeerData> preferConfiguredTransport(List<PeerData> peers) {
        if (peers.isEmpty())
            return peers;

        if (!Settings.getInstance().isIPAllowed())
            // I2P-only: never dial a direct peer (no fallback to direct addresses)
            return peers.stream().filter(peerData -> peerData.getAddress().isI2P()).collect(Collectors.toList());

        boolean preferI2P = Settings.getInstance().isI2PPreferred();
        List<PeerData> preferredPeers = peers.stream()
                .filter(peerData -> peerData.getAddress().isI2P() == preferI2P)
                .collect(Collectors.toList());

        return preferredPeers.isEmpty() ? peers : preferredPeers;
    }

    // private final Predicate<PeerData> isResolvedAsConnectedPeer = peerData -> {
    //     try {
    //         InetSocketAddress resolvedSocketAddress = peerData.getAddress().toSocketAddress();
    //         return this.getImmutableConnectedPeers().stream()
    //                 .anyMatch(peer -> peer.getResolvedAddress().equals(resolvedSocketAddress));
    //     } catch (UnknownHostException e) {
    //         // Can't resolve - no point even trying to connect
    //         return true;
    //     }
    // };

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
                            // dominant source of NetworkData-IO CPU cost with many active peers. On error or
                            // EOF, disconnect() closes the channel, cancelling the key automatically.
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
                                        LOGGER.trace("[{}] NetworkData I/O thread encountered I/O error: {}", peer.getPeerConnectionId(), e.getMessage(), e);
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
                            // event and was the dominant cost in processUpdateQueue during bulk transfers.
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
                                        LOGGER.debug("[{}] NetworkData I/O thread encountered I/O error on write: {}", peer.getPeerConnectionId(), e.getMessage(), e);
                                        peer.disconnect("I/O error");
                                    }
                                } finally {
                                    channelsPendingWrite.remove(socketChannel);
                                }
                            }
                        } else if (key.isAcceptable()) {
                            clearInterestOps(key, SelectionKey.OP_ACCEPT);
                            ServerSocketChannel readyServerChannel = (ServerSocketChannel) key.channel();
                            try {
                                if (readyServerChannel == this.i2pServerChannel)
                                    acceptI2PForwardedPeer(readyServerChannel);
                                else
                                    new ChannelAcceptTask(readyServerChannel, Peer.NETWORKDATA).perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } finally {
                                setInterestOps(readyServerChannel, SelectionKey.OP_ACCEPT);
                            }
                        }
                    } catch (CancelledKeyException e) {
                    }
                }
            }
            for (Peer peer : readPeersThisRound) {
                ExecuteProduceConsume.Task task;
                while ((task = peer.getMessageTask(Peer.NETWORKDATA)) != null) {
                    final ExecuteProduceConsume.Task t = task;
                    try {
                        networkDataWorkerPool.execute(() -> {
                            try {
                                t.perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                LOGGER.warn("NetworkData worker task threw: {}", e.getMessage(), e);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        // Worker pool is full or shutting down - log and continue
                        // Message will be lost but system remains stable
                        LOGGER.warn("[{}] NetworkData worker pool rejected message task (pool full or shutting down)", 
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
        LOGGER.debug("NetworkData I/O loop exiting");
    }

    private void acceptI2PForwardedPeer(ServerSocketChannel forwardServerChannel) {
        try {
            SocketChannel socketChannel = forwardServerChannel.accept();
            if (socketChannel == null)
                return;

            try {
                networkDataWorkerPool.execute(() -> processI2PForwardedPeer(socketChannel));
            } catch (RejectedExecutionException e) {
                LOGGER.debug("NetworkData worker pool rejected I2P forwarded peer setup");
                closeQuietly(socketChannel);
            }
        } catch (IOException e) {
            LOGGER.debug("I2P data accept failed: {}", e.getMessage());
        }
    }

    private void processI2PForwardedPeer(SocketChannel socketChannel) {
        PeerAddress peerAddress = null;

        try {
            Long now = NTP.getTime();
            if (now == null) {
                LOGGER.debug("I2P data connection discarded due to lack of NTP sync");
                socketChannel.close();
                return;
            }

            I2PStreamProvider provider = this.dataI2PStreamProvider;
            if (provider == null || !provider.isSessionUp()) {
                LOGGER.debug("I2P data connection discarded because the session is not up");
                socketChannel.close();
                return;
            }

            socketChannel.configureBlocking(true);
            socketChannel.socket().setSoTimeout(I2P_FORWARD_DESTINATION_TIMEOUT);
            peerAddress = PeerAddress.fromString(provider.readForwardedDestination(socketChannel));
            socketChannel.socket().setSoTimeout(0);
            if (!peerAddress.isI2P())
                throw new IOException("Forwarded destination was not an I2P address");

            LOGGER.debug("I2P data connection accepted from peer {}", peerAddress);

            Peer newPeer = new Peer(socketChannel, Peer.NETWORKDATA, peerAddress);
            newPeer.setIsDataPeer(true);
            this.addConnectedPeer(newPeer);
            this.onPeerReady(newPeer);
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.debug("I2P data connection failed from peer {}: {}",
                    peerAddress != null ? peerAddress : "unknown", e.getMessage());
            closeQuietly(socketChannel);
        }
    }

    private void closeQuietly(SocketChannel socketChannel) {
        if (socketChannel == null || !socketChannel.isOpen())
            return;

        try {
            socketChannel.close();
        } catch (IOException closeException) {
            LOGGER.debug("Error closing socket: {}", closeException.getMessage());
        }
    }

    private void runSchedulerLoop() {
        while (!isShuttingDown && !Thread.currentThread().isInterrupted()) {
            try {
                Long now = NTP.getTime();
                ExecuteProduceConsume.Task task = maybeProduceConnectPeerTask(now);
                if (task != null) {
                    final ExecuteProduceConsume.Task t = task;
                    try {
                        networkDataWorkerPool.execute(() -> {
                            try {
                                t.perform();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                LOGGER.warn("NetworkData scheduler task threw: {}", e.getMessage(), e);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        // Worker pool is full or shutting down - skip this task
                        LOGGER.debug("NetworkData worker pool rejected scheduler task (pool full or shutting down)");
                    }
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOGGER.debug("NetworkData scheduler loop exiting");
    }

    private ExecuteProduceConsume.Task maybeProduceConnectPeerTask(Long now) throws InterruptedException {
        cleanupStaleHandshakingPeers(now);

        if (now == null || now < nextConnectTaskTimestamp.get()) {
            return null;
        }
        if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
            disconnectI2PFallbackPeerWithDirectReplacement(now);
            return null;
        }
        boolean hasNoPeers = getImmutableHandshakedPeers().isEmpty();
        if (hasNoPeers && lastPeerWasFromBackoff) {
            nextConnectTaskTimestamp.set(now + ISOLATION_RETRY_INTERVAL);
        } else {
            nextConnectTaskTimestamp.set(now + 3000L);
        }
        Peer targetPeer = getConnectablePeer(now);
        if (targetPeer == null) {
            return null;
        }
        targetPeer.setPeerType(Peer.NETWORKDATA);
        return new PeerConnectTask(targetPeer);
    }

    /**
     * Repairs inconsistent peer state where a peer is in one list but not the other.
     * This can happen due to race conditions in duplicate connection handling during
     * handshake completion.
     * 
     * Two types of orphaned peers are detected:
     * 1. Peer in connectedPeers with COMPLETED status but not in handshakedPeers
     * 2. Peer in handshakedPeers but not in connectedPeers (invisible to API, can't sync data)
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
                            LOGGER.debug("[{}] Repairing orphaned data peer {} - in connectedPeers with COMPLETED status but not in handshakedPeers",
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
                    LOGGER.debug("[{}] Detected zombie data peer {} - in connectedPeers but not in handshakedPeers (status={}, age={}ms)",
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
        // This catches peers that are available for data transfer but invisible to management
        for (Peer peer : getImmutableHandshakedPeers()) {
            // CRITICAL: Use object identity (==), not equals()
            boolean inConnected = getImmutableConnectedPeers().stream()
                    .anyMatch(p -> p == peer);
            
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
                        // This causes the peer to be invisible to management and can prevent proper data sync
                        LOGGER.warn("[{}] Repairing orphaned data peer {} - in handshakedPeers but not in connectedPeers",
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
        // Guard against running during shutdown or not nodeId
        if (this.isShuttingDown || this.ourNodeId == null) {
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
            String peerHost = peers.get(0).getPeerData().getAddress().getHost();
            boolean weShouldBeOutbound = shouldBeOutboundTo(theirNodeId, peerHost);
            
            if (peers.size() == 1) {
                Peer peer = peers.get(0);
                if (PeerDirectionPolicy.shouldKeepSinglePeerAsFallback(peer.isOutbound(), weShouldBeOutbound)) {
                    LOGGER.trace("[NetworkData: {}] Keeping single peer {} as direction fallback (outbound={}, preferredOutbound={})",
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
                    LOGGER.warn("[NetworkData] No correct-direction peer found for nodeId {}, keeping oldest peer {}",
                            theirNodeId, correctPeer);
                }
                
                // Collect peers to disconnect (all except the correct one)
                for (Peer p : peers) {
                    if (p != correctPeer) {
                        LOGGER.debug("[NetworkData: {}] Will disconnect direction-incorrect peer {} (outbound={}, shouldBeOutbound={}, correctPeer={})",
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

        LOGGER.debug("[NetworkData: {}] Dropping I2P fallback peer {} (nodeId {}) so direct TCP peer {} can be retried",
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

        return isFixedPeer(peerData.getAddress())
                || isInitialDataPeer(peerData.getAddress())
                || !hasRecentDirectionMismatch(cachedInfo.nodeId);
    }

    private boolean isDirectPeerInConnectBackoff(PeerData peerData, Long now) {
        if (peerData.getLastAttempted() == null)
            return false;

        if (peerData.getLastConnected() == null)
            return true;

        return now != null
                && peerData.getLastConnected() < peerData.getLastAttempted()
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
        List<PeerData> peers = this.getAllKnownPeers();
            
        // Fallback: If NetworkData has no peers, try to get peers from Network
        // Only use peers that actually advertise QDN capability
        if (peers.isEmpty()) {
            try {
                Network network = Network.getInstance();
                if (network != null) {
                    // Get connected peers with capabilities, not just known addresses
                    List<Peer> connectedNetworkPeers = network.getImmutableHandshakedPeers();
                    if (!connectedNetworkPeers.isEmpty()) {
                        Long addedWhen = NTP.getTime();
                        String addedBy = "Network-fallback";
                        int peersAdded = 0;
                        
                        for (Peer networkPeer : connectedNetworkPeers) {
                            List<PeerData> qdnPeerData = buildQdnPeerDataFromNetworkPeer(networkPeer, addedWhen, addedBy);
                            peers.addAll(qdnPeerData);
                            peersAdded += qdnPeerData.size();
                        }
                        
                        // Also add to our known peers list for future use
                        if (peersAdded > 0) {
                            addKnownPeersIfMissing(peers, null);
                            
                            LOGGER.trace("NetworkData had no peers - using {} QDN-capable peer(s) from Network as fallback", peersAdded);
                        } else {
                            LOGGER.debug("NetworkData had no peers and no Network peers advertise QDN capability");
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to get peers from Network fallback: {}", e.getMessage());
            }
        }
        
        if (peers.isEmpty()) {
            return null;
        }
        
    
        
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

        // Don't consider peers that we know loop back to self
        synchronized (this.selfPeers) {
            peers.removeIf(isSelfPeer);
        }
        peers.removeIf(isLocalI2PPeer);

        // Don't consider already connected peers (simple address match)
        peers.removeIf(isConnectedPeer);
        peers.removeIf(isConnectingI2PPeer);
        peers.removeIf(isI2PAlternativeForConnectedPeer);

        // Don't consider peers we're already connected to by nodeId
        // This handles cases where we have an inbound connection on an ephemeral port
        // but allKnownPeers has the listen port (common when peer is added from Network)
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
                        LOGGER.debug("Data peer {} (nodeId {}) is connected over I2P fallback, allowing direct TCP replacement attempt",
                                peerAddress, candidateNodeId.substring(0, 8));
                        return false;
                    }

                    // Already connected to this nodeId - check whether a preferred outbound replacement is useful.
                    String ourNodeId = this.getOurNodeId();
                    if (ourNodeId != null) {
                        boolean weShouldBeOutbound = shouldBeOutboundTo(candidateNodeId, peerData.getAddress().getHost());
                        boolean outboundRecentlyFailed = hasRecentOutboundFailures(candidateNodeId, peerData.getAddress().getHost());
                        if (PeerDirectionPolicy.shouldAttemptPreferredOutboundReplacement(existingPeer.isOutbound(),
                                weShouldBeOutbound, outboundRecentlyFailed)) {
                            LOGGER.debug("Data peer {} (nodeId {}) connected in fallback direction, allowing preferred outbound attempt",
                                    peerAddress, candidateNodeId.substring(0, 8));
                            return false;
                        }
                    }

                    LOGGER.debug("Skipping peer {} (nodeId {}) - already connected",
                            peerAddress, candidateNodeId.substring(0, 8));
                    return true;
                }
            }
            return false;
        });

        // Don't attempt I2P data peers until our own data I2P session is up.
        peers.removeIf(peerData -> peerData.getAddress().isI2P() && this.getI2PDataDestination() == null);

        // Don't consider peers with recent direction mismatches
        // CRITICAL: Never skip fixed network peers (prevents isolation)
        peers.removeIf(peerData -> {
            if (isFixedPeer(peerData.getAddress()) || isInitialDataPeer(peerData.getAddress())) {
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
                LOGGER.debug("No connected data peers - retrying {} peer(s) in backoff period", peers.size());
            }
        } else {
            lastPeerWasFromBackoff = false;
        }

        // Any left?
        if (peers.isEmpty()) {
            if (hasNoPeers) {
                LOGGER.warn(formatNoConnectableDataPeersWarning(getKnownPeerDiagnostics(now)));
            }
            return null;
        }

        peers = preferConfiguredTransport(peers);

        // Pick random peer
        int peerIndex = new Random().nextInt(peers.size());

        // Pick candidate
        PeerData peerData = peers.get(peerIndex);
        Peer newPeer = new Peer(peerData, Peer.NETWORKDATA);
        newPeer.setIsDataPeer(true);
        if (peerData.getAddress().isI2P())
            this.connectingI2PPeers.add(peerData.getAddress());

        // Update connection attempt info
        peerData.setLastAttempted(now);
        return newPeer;
    }

    static String formatNoConnectableDataPeersWarning(KnownPeerDiagnostics diagnostics) {
        StringBuilder message = new StringBuilder("Isolated node: No connectable data peers found (");
        message.append("known=").append(diagnostics.knownCount);
        message.append(", connected=").append(diagnostics.connectedCount);
        message.append(", handshaked=").append(diagnostics.handshakedCount);
        message.append(", connectable=").append(diagnostics.connectableCount);
        message.append(", backoff=").append(diagnostics.backoffCount);
        message.append(", i2pSessionUp=").append(diagnostics.i2pSessionUp);
        if (diagnostics.qdnFallbackCandidateCount != null)
            message.append(", qdnFallbackCandidates=").append(diagnostics.qdnFallbackCandidateCount);

        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.RECENT_CONNECT_FAILURE,
                "recentConnectFailure");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.I2P_SESSION_DOWN,
                "i2pSessionDown");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.TRANSPORT_NOT_ALLOWED,
                "transportNotAllowed");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.ALREADY_CONNECTED,
                "alreadyConnected");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.ALREADY_CONNECTED_BY_NODE_ID,
                "alreadyConnectedByNodeId");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.ALREADY_CONNECTING_I2P,
                "alreadyConnectingI2P");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.I2P_ALTERNATIVE_ALREADY_CONNECTED,
                "i2pAlternativeAlreadyConnected");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.LOCAL_I2P_ADDRESS,
                "localI2PAddress");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.DIRECTION_MISMATCH,
                "directionMismatch");
        appendReasonCount(message, diagnostics, KnownPeerDiagnostic.Reason.SELF,
                "self");

        message.append(")");
        return message.toString();
    }

    private static void appendReasonCount(StringBuilder message, KnownPeerDiagnostics diagnostics,
                                          KnownPeerDiagnostic.Reason reason, String label) {
        Integer count = diagnostics.reasonCounts.get(reason);
        if (count != null && count > 0)
            message.append(", ").append(label).append("=").append(count);
    }

    public boolean connectPeer(Peer newPeer) throws InterruptedException {
        try {
            // Also checked before creating PeerConnectTask
            if (getImmutableOutboundHandshakedPeers().size() >= minOutboundPeers) {
                return false;
            }

            SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
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
                LOGGER.debug("Thread is interrupted");
                return false;
            }

            this.addConnectedPeer(newPeer);
            this.onPeerReady(newPeer);

            return true;
        } finally {
            removeConnectingI2PPeer(newPeer);
        }
    }

    /* Same as connectPeer except it ignores the max peer count */
    public boolean forceConnectPeer(Peer newPeer) {
        try {

            SocketChannel socketChannel = newPeer.connect(Peer.NETWORKDATA);
            if (socketChannel == null) {
                return false;
            }

            if (Thread.currentThread().isInterrupted()) {
                return false;
            }

            this.addConnectedPeer(newPeer);
            this.onPeerReady(newPeer);

            return true;
        } finally {
            removeConnectingI2PPeer(newPeer);
        }
    }

    private void removeConnectingI2PPeer(Peer peer) {
        PeerAddress peerAddress = peer.getPeerData().getAddress();
        if (peerAddress.isI2P())
            this.connectingI2PPeers.remove(peerAddress);
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

            LOGGER.debug("Disconnecting stale data handshaking peer {} at {} after {} ms",
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name(),
                    now - peer.getConnectionTimestamp());
            peer.disconnect(String.format("handshake timeout at %s", peer.getHandshakeStatus().name()));
        }
    }

      /**
     * Submit forceConnectPeer to a dedicated executor so the caller doesn't block on TCP connect.
     * Use this from processFileHashes so the first loop stays fast when there are many direct-not-connected responses.
     */
      public void forceConnectPeerAsync(Peer newPeer) {
        forceConnectExecutor.submit(() -> {
            try {
                forceConnectPeer(newPeer);
            } catch (Exception e) {
                LOGGER.debug("Force connect failed for peer {}: {}", newPeer, e.getMessage());
            }
        });
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
        List<Peer> peersToDisconnect = this.getImmutableConnectedPeers().stream()
                .filter(peer -> !peer.isSyncInProgress())
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
                        LOGGER.trace("Failed to set interest ops on channel {} - channel already closed", socketChannel);
                        return;
                    } catch (Exception e) {
                        LOGGER.trace("Failed to register channel {} for interest ops {}: {}", socketChannel, interestOps, e.getMessage());
                        return;
                    }
                    // Fall-through to allow logging
                }
            }
        }

        try {
            setInterestOps(selectionKey, interestOps);
        } catch (Exception e) {
            LOGGER.trace("Failed to set interest ops {} on selection key for channel {}: {}", interestOps, socketChannel, e.getMessage());
        }
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
                } catch (Exception e) {
                    LOGGER.trace("Failed to register peer channel {}: {}", channel, e.getMessage());
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
                LOGGER.debug("Re-enabling accepting incoming connections because the server is no longer full");
                setInterestOps(serverSelectionKey, SelectionKey.OP_ACCEPT);
            } catch (CancelledKeyException e) {
                LOGGER.error("Failed to re-enable accepting of incoming connections: {}", e.getMessage());
            }
        }

        // Notify Controller
        Controller.getInstance().onPeerDisconnect(peer);
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

            case HELLO:
                // A HELLO after completion is a capability refresh (the peer's slow I2P session just came
                // up and it is now advertising its I2P_QDN destination). Merge it instead of treating it as
                // a protocol error; only disconnect if its chain identity is incompatible.
                if (!Handshake.applyPostHandshakeHello(peer, (HelloMessage) message))
                    peer.disconnect("incompatible post-handshake HELLO");
                return;

            case CHALLENGE:
            case RESPONSE:
                LOGGER.debug("[{}] Unexpected handshaking message {} from peer {}", peer.getPeerConnectionId(),
                        message.getType().name(), peer);
                peer.disconnect("unexpected handshaking message");
                return;

            case ARBITRARY_DATA_FILE:
                ArbitraryDataFileMessage adfm = (ArbitraryDataFileMessage) message;
                ArbitraryDataFile adf = adfm.getArbitraryDataFile();

                // CRITICAL: Offload heavy processing (validation + disk I/O) to separate thread pool
                // to prevent blocking the NetworkProcessor thread, which needs to quickly return
                // to selector.select() to drain TCP buffers from all peers.
                // 
                // Without this, the NetworkProcessor thread blocks for 1200-1400ms per chunk,
                // causing other peers' data to pile up in TCP receive buffers (saw 2.3 MB backlog),
                // resulting in 50-80 second apparent "RTT" (actually just queue wait time).
                final Peer finalPeer = peer;
                try {
                    chunkProcessorPool.execute(() -> {
                        try {
                            ArbitraryDataFileManager.getInstance().receivedArbitraryDataFile(finalPeer, adf);
                        } catch (Exception e) {
                            LOGGER.error("Error processing chunk {} from peer {}", adf.getHash58(), finalPeer, e);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    LOGGER.warn("Dropping arbitrary data file chunk {} from peer {} because the chunk processor queue is full",
                            adf.getHash58(), finalPeer);
                    finalPeer.disconnect("chunk processor queue full");
                }
                break;

			case ARBITRARY_DATA_FILE_LIST:
				ArbitraryDataFileListManager.getInstance().onNetworkArbitraryDataFileListMessage(peer, message);
				break;

			case ARBITRARY_DATA_FILE_OFFER:
				ArbitraryDataFileManager.getInstance().onNetworkArbitraryDataFileOfferMessage(peer, message);
				break;

			case ARBITRARY_DATA_FILE_WANT:
				ArbitraryDataFileManager.getInstance().onNetworkArbitraryDataFileWantMessage(peer, message);
				break;

			case GET_ARBITRARY_DATA_FILE:
				ArbitraryDataFileManager.getInstance().onNetworkGetArbitraryDataFileMessage(peer, message);
				break;

            case GET_ARBITRARY_DATA_FILE_LIST:
                ArbitraryDataFileListManager.getInstance().onNetworkGetArbitraryDataFileListMessage(peer, message);
                break;

			case GET_ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkGetArbitraryMetadataMessage(peer, message);
				break;

			case ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkArbitraryMetadataMessage(peer, message);
				break;

            case GET_PEERS:
                onGetPeersMessage(peer, message);
                break;

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
            LOGGER.trace("[NetworkData: {}] Handshake status {}, message {} from peer {} isOutbound {}",
                    peer.getPeerConnectionId(),
                    handshakeStatus != null ? handshakeStatus.name() : "null",
                    (message != null ? message.getType().name() : "null"),
                    peer,
                    peer.isOutbound());
    
            // Initial outbound handshake kick-off calls into here with message == null (STARTED).
            // Don't touch message.getType() in that case; just advance state and perform the action.
            if (message == null) {
                Handshake newHandshakeStatus = handshakeStatus.onMessage(peer, null);
    
                if (newHandshakeStatus == null) {
                    peer.disconnect("handshake failure");
                    return;
                }
    
                // Ensure this peer is marked as NETWORKDATA
                peer.setPeerType(Peer.NETWORKDATA);
    
                if (peer.isOutbound()) {
                    newHandshakeStatus.action(peer);
                }
    
                peer.setHandshakeStatus(newHandshakeStatus);
    
                // Do NOT call onHandshakeCompleted() here.
                // Completion requires RESPONSE validation + our RESPONSE sent (PoW thread).
                return;
            }
    
            // HELLO can arrive out-of-order during handshake as a side-band peer info update.
            if (message.getType() == MessageType.HELLO
                    && handshakeStatus != Handshake.HELLO) {
                if (Handshake.HELLO.onMessage(peer, message) == null)
                    peer.disconnect("handshake failure");
                return;
            }
    
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

    
            // Check message type is as expected
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
    
            Handshake newHandshakeStatus = effectiveHandshakeStatus.onMessage(peer, message);
    
            if (newHandshakeStatus == null) {
                LOGGER.debug("[{}] Handshake failure with peer {} message {}",
                        peer.getPeerConnectionId(), peer, message.getType().name());
                peer.disconnect("handshake failure");
                return;
            }
    
            // Ensure this peer is marked as NETWORKDATA
            peer.setPeerType(Peer.NETWORKDATA);
    
            // Perform actions (send responses)
            if (peer.isOutbound()) {
                // Outbound: act first for the NEXT state
                newHandshakeStatus.action(peer);
            } else if (newHandshakeStatus != Handshake.RESPONDING) {
                newHandshakeStatus.action(peer);
            }
    
            // Note: RESPONSE.onMessage() always returns RESPONDING now.
            // Completion is handled by tryCompleteHandshake() which is called from:
            // - RESPONSE.onMessage() after setting handshakeResponseValidated = true (RX side)
            // - RESPONSE.action() after setting handshakeResponseSent = true (TX side)
            // Whichever thread completes second will trigger the actual completion.
            peer.setHandshakeStatus(newHandshakeStatus);
    
        } finally {
            peer.resetHandshakeMessagePending();
        }
    }
    
    protected void onHandshakeCompleted(Peer peer) {
        LOGGER.trace("[NetworkData: {}] Handshake completed with peer {} on {}", peer.getPeerConnectionId(), peer,
                peer.getPeersVersionString());

        // Clear any outbound failure records for this peer's IP since connection succeeded
        // Also update address→nodeId cache and clear direction mismatch for inbound
        try {
            PeerAddress peerAddress = peer.getPeerData().getAddress();
            if (peerAddress.isI2P() && peer.getPeersNodeId() != null) {
                String theirNodeId = peer.getPeersNodeId();
                updateAddressToNodeIdCache(peerAddress.toString(), theirNodeId);
                clearOutboundFailures(peerAddress.getHost(), theirNodeId);

                if (!peer.isOutbound())
                    clearDirectionMismatch(theirNodeId);
            } else if (peer.getResolvedAddress() != null && peer.getPeersNodeId() != null) {
                String peerIP = peer.getResolvedAddress().getAddress().getHostAddress();
                int peerPort = peer.getResolvedAddress().getPort();
                String resolvedPeerAddress = peerIP + ":" + peerPort;
                String theirNodeId = peer.getPeersNodeId();
                
                // Keep cache updated with latest address for this nodeId
                // Handles IP changes from DHCP/UPnP/VPN
                updateAddressToNodeIdCache(resolvedPeerAddress, theirNodeId);
                
                clearOutboundFailures(peerIP, theirNodeId);
                
                // Clear direction mismatch if inbound succeeds
                // (They successfully connected to us, so we don't need to avoid them)
                if (!peer.isOutbound()) {
                    clearDirectionMismatch(theirNodeId);
                }
            }
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
                    LOGGER.warn("[NetworkData: {}] Peer {} not in connectedPeers during handshake completion - adding now",
                            peer.getPeerConnectionId(), peer);
                    this.addConnectedPeer(peer);  // Safe: addConnectedPeer is reentrant
                }
            }

            // Synchronize duplicate check and add operation to prevent race condition
            synchronized (this.handshakedPeers) {
                // Check if this exact peer is already in handshakedPeers (duplicate call protection)
                // Use object identity (==), not equals() which compares by address
                if (this.handshakedPeers.stream().anyMatch(p -> p == peer)) {
                    LOGGER.debug("[NetworkData: {}] Peer {} already in handshakedPeers, skipping duplicate add",
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
                        LOGGER.trace("[NetworkData: {}] Existing peer {} is stale (socket closed or stopping), replacing with new peer {}",
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
                            boolean weShouldBeOutbound = shouldBeOutboundTo(theirNodeId,
                                    peer.getPeerData().getAddress().getHost());
                            PeerDirectionPolicy.DuplicateConnectionDecision duplicateDecision =
                                    PeerDirectionPolicy.decideDuplicate(true, existingPeer.isOutbound(), peer.isOutbound(),
                                            weShouldBeOutbound);
                            String winner = duplicateDecision == PeerDirectionPolicy.DuplicateConnectionDecision.REPLACE_EXISTING
                                    ? "new" : "existing";
                            LOGGER.debug("[NetworkData: {}] Duplicate peer decision: existing={} (outbound={}), new={} (outbound={}), weShouldBeOutbound={}, winner={}",
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

                // Add to handshaked peers cache if decision was made to add
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

        // @ToDo : Need to understand what this is, what are pending signatures?
        //   Should this be part of the other thread?
        // Process any pending signature requests, as this peer may have been connected for this purpose only
        List<byte[]> pendingSignatureRequests = new ArrayList<>(peer.getPendingSignatureRequests());
        if (!pendingSignatureRequests.isEmpty()) {
            for (byte[] signature : pendingSignatureRequests) {
                this.requestDataFromConnectedPeer(peer, signature);
                peer.removePendingSignatureRequest(signature);
            }
        }

        // FUTURE: we may want to disconnect from this peer if we've finished requesting data from it

        // Only the outbound side needs to send anything (after we've received handshake-completing response).
        // (If inbound sent anything here, it's possible it could be processed out-of-order with handshake message).
        //
        // DATA-LAYER peer exchange (gossip): mirror the chain layer's one-shot cadence
        // (Network.onHandshakeCompleted) exactly. The outbound side advertises our transport-scoped
        // known DATA peers and asks for theirs. There is no periodic/recurring GET_PEERS — one request
        // per connection, outbound only. This lets the QDN/data overlay discover data peers
        // independently of the chain layer, without ever referencing chain identity.
        if (peer.isOutbound()) {
            // Send our (transport-scoped) known data peers
            if (!peer.sendMessage(buildDataPeersMessage(peer))) {
                peer.disconnect("failed to send peers list");
                return;
            }

            // Request their known data peers
            if (!peer.sendMessage(new GetPeersMessage())) {
                peer.disconnect("failed to request peers list");
                return;
            }
        }

        LOGGER.trace("Handshake has been completed");
        // Ask Controller if they want to do anything
        Controller.getInstance().onPeerHandshakeCompleted(peer);
    }

    public boolean canAcceptInbound() {
        return this.inboundReachability.canAcceptInbound();
    }

    /**
     * Transport-aware direct-connectability for QDN data fetching.
     * <p>
     * Returns true when this node is reachable directly either over clearnet (IP) or over I2P
     * (it has a usable, session-up I2P data destination). Used so a NAT'd-but-I2P-reachable
     * publisher advertises itself as directly connectable, letting requesters dial its data
     * destination instead of funnelling to an unreachable I2P relay.
     */
    public boolean canAcceptInboundData() {
        return this.inboundReachability.canAcceptInboundData(this.getI2PDataDestination() != null);
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

    // External IP / peerAddress tracking

    public synchronized void ourPeerAddressUpdated(String peerAddress) {
        if (peerAddress == null || peerAddress.isEmpty()) {
            return;
        }

        // Validate IP address
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
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
                }
            }
        }
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
            this.allKnownPeers.clear();

            try (Repository repository = RepositoryManager.getRepository()) {
                numDeleted = repository.getNetworkRepository().deleteAllPeers();
                repository.saveChanges();
            }
        }

        for (Peer peer : this.getImmutableConnectedPeers()) {
            peer.disconnect("to be forgotten");
        }

        return numDeleted;
    }

    private void disconnectPeer(PeerAddress peerAddress) {
        // Create snapshot first (acquires and releases lock)
        PeerList peerListSnapshot = this.getImmutableConnectedPeers();
        
        // Find matching peer in snapshot (no lock held)
        Peer matchingPeer = peerListSnapshot.stream()
            .filter(peer -> peerAddress.equals(peer.getPeerData().getAddress()))
            .findFirst()
            .orElse(null);
        
        // Disconnect outside the iteration (no lock held)
        if (matchingPeer != null) {
            matchingPeer.disconnect("to be forgotten");
        }
    }

    // Network-wide calls
    public void addPeer(Peer p) {
        LOGGER.trace("Passed a newly connected peer from Network : {}", p);

        List<PeerData> qdnPeers = buildQdnPeerDataFromNetworkPeer(p, System.currentTimeMillis(), "INIT");
        if (qdnPeers.isEmpty()) {
            LOGGER.debug("Peer {} does not advertise a usable QDN or I2P_QDN capability, skipping NetworkData registration",
                    p.getPeerData().getAddress());
            return;
        }

        int added = addKnownPeersIfMissing(qdnPeers, p.getPeersNodeId());
        if (added > 0)
            LOGGER.debug("Added {} QDN peer address(es) from Network connection {}", added, p.getPeerData().getAddress());
    }

    public boolean mergePeers(String addedBy, long addedWhen, List<PeerAddress> peerAddresses) throws DataException {
        List<PeerData> newPeers;
        synchronized (this.allKnownPeers) {
            for (PeerData knownPeerData : this.allKnownPeers) {
                // Filter out duplicates, without resolving via DNS
                Predicate<PeerAddress> isKnownAddress = peerAddress -> knownPeerData.getAddress().equals(peerAddress);
                peerAddresses.removeIf(isKnownAddress);
            }

            if (peerAddresses.isEmpty()) {
                return false;
            }

            // Add leftover peer addresses to known peers list
            newPeers = peerAddresses.stream()
                    .map(peerAddress -> new PeerData(peerAddress, addedWhen, addedBy))
                    .collect(Collectors.toList());

            this.allKnownPeers.addAll(newPeers);

            return true;
        }
    }

    // =====================================================================================
    // DATA-LAYER peer exchange (gossip)
    //
    // A transport-scoped GET_PEERS/PEERS exchange that lets the QDN/data overlay discover
    // data peers INDEPENDENTLY of the chain layer. This mirrors Network.buildPeersMessage /
    // Network.onGetPeersMessage / Network.onPeersMessage exactly, but sources its addresses
    // ONLY from NetworkData.allKnownPeers (DATA destinations seeded from initialDataPeers and
    // grown by gossip). Privacy invariants:
    //   - allKnownPeers holds only DATA destinations, so chain addresses are never shared.
    //   - The transport is the privacy boundary: an I2P requester only ever learns .b32.i2p
    //     data peers; a clearnet requester only ever learns clearnet data peers. Never cross.
    //   - No chain identity (public key / nodeId) is referenced; gossiped peers are merged with
    //     a null nodeId so they never poison the address->nodeId cache.
    // =====================================================================================

    /**
     * Responder: a data peer asked for our known data peers, so reply with a transport-scoped
     * PEERS message built from our DATA destinations only.
     */
    private void onGetPeersMessage(Peer peer, Message message) {
        if (!peer.sendMessage(buildDataPeersMessage(peer))) {
            peer.disconnect("failed to send peers list");
        }
    }

    /**
     * Build a transport-scoped PEERS message from our known DATA peers.
     * Mirrors Network.buildPeersMessage: an I2P requester receives ONLY .b32.i2p data
     * destinations, a clearnet requester receives ONLY clearnet data destinations. The
     * "0.0.0.0:&lt;port&gt;" sentinel first entry is added automatically by the PeersMessage
     * constructor (and is dropped/ignored by the data consumer), so the wire format matches
     * the chain PEERS message and no new message type is required.
     */
    public Message buildDataPeersMessage(Peer peer) {
        // allKnownPeers holds ONLY data destinations, so chain addresses can never be advertised.
        List<PeerData> knownPeers = this.getAllKnownPeers();

        // The transport is the privacy boundary: never cross-advertise between transports.
        final boolean requesterIsI2P = peer.getPeerData().getAddress().isI2P();

        List<PeerAddress> peerAddresses = scopeDataPeerAddresses(knownPeers, requesterIsI2P, peer.isLocal());

        // PeersMessage constructor prepends the sentinel and drops >255-byte addresses.
        return new PeersMessage(peerAddresses);
    }

    /**
     * Transport-scope the advertised DATA peer addresses for a requester. The transport is the
     * privacy boundary, mirroring Network.buildPeersMessage:
     * <ul>
     *   <li>an I2P requester is given ONLY .b32.i2p data destinations,</li>
     *   <li>a clearnet requester is given ONLY clearnet data destinations,</li>
     *   <li>never cross between transports.</li>
     * </ul>
     * Because the input is sourced from allKnownPeers (DATA destinations only), chain addresses
     * can never appear here. Package-visible and static so the scoping invariant is unit-testable
     * without constructing a live Peer.
     */
    static List<PeerAddress> scopeDataPeerAddresses(List<PeerData> knownPeers, boolean requesterIsI2P,
                                                    boolean requesterIsLocal) {
        List<PeerAddress> peerAddresses = new ArrayList<>();

        for (PeerData peerData : knownPeers) {
            PeerAddress address = peerData.getAddress();

            if (address.isI2P()) {
                // Only hand I2P data destinations to an I2P requester.
                if (requesterIsI2P) {
                    peerAddresses.add(address);
                }
                continue;
            }

            // Clearnet data address: never hand it to an I2P requester.
            if (requesterIsI2P) {
                continue;
            }

            try {
                InetAddress resolved = InetAddress.getByName(address.getHost());

                // Don't send 'local' addresses if peer is not 'local'.
                if (!requesterIsLocal && Peer.isAddressLocal(resolved)) {
                    continue;
                }

                peerAddresses.add(address);
            } catch (UnknownHostException e) {
                // Couldn't resolve hostname to IP address so discard
            }
        }

        return peerAddresses;
    }

    /**
     * Consumer: a data peer advertised its known data peers. Drop the sentinel first entry,
     * transport-gate each advertised address by LOCAL reachability (so an I2P-only node never
     * stores clearnet data peers and vice-versa), then merge into allKnownPeers.
     * <p>
     * Unlike the chain consumer we do NOT reconstruct the sender's listen address from the
     * sentinel port: the sentinel carries the CHAIN listen port, which is meaningless for a
     * data destination, so reusing it would record a wrong port / leak chain-layer info.
     */
    private void onPeersMessage(Peer peer, Message message) {
        PeersMessage peersMessage = (PeersMessage) message;

        List<PeerAddress> peerAddresses = peersMessage.getPeerAddresses();

        PeerExchangeRecorder.getInstance().record("data", peer, peersMessage.getPeerAddresses());

        if (peerAddresses == null || peerAddresses.isEmpty()) {
            return;
        }

        // First entry is the sentinel (sender's chain listen port with empty address); discard it.
        peerAddresses.remove(0);

        // Transport-gate by LOCAL reachability so we only store data peers we could actually dial.
        final boolean i2pEnabled = Settings.getInstance().isI2PEnabled();
        final boolean ipAllowed = Settings.getInstance().isIPAllowed();

        List<PeerAddress> dialableAddresses = new ArrayList<>();
        for (PeerAddress address : peerAddresses) {
            if (address.isI2P()) {
                if (i2pEnabled) {
                    dialableAddresses.add(address);
                }
            } else {
                if (ipAllowed) {
                    dialableAddresses.add(address);
                }
            }
        }

        if (dialableAddresses.isEmpty()) {
            return;
        }

        // Bound per-message work: a single PEERS message can advertise ~1M addresses (10MB
        // MAX_DATA_SIZE), so never process more than MAX_GOSSIPED_PEERS_PER_MESSAGE from one message
        // regardless of the total cap. This stops a hostile peer from forcing a huge merge in one go.
        if (dialableAddresses.size() > MAX_GOSSIPED_PEERS_PER_MESSAGE) {
            LOGGER.debug("Capping PEERS gossip from {} to {} addresses (advertised {})",
                    peer.getPeerData().getAddress(), MAX_GOSSIPED_PEERS_PER_MESSAGE, dialableAddresses.size());
            dialableAddresses = dialableAddresses.subList(0, MAX_GOSSIPED_PEERS_PER_MESSAGE);
        }

        // Merge into allKnownPeers (inherits dedup + locking). Pass a null nodeId so gossiped,
        // unverified data destinations never poison the address->nodeId cache (no chain identity).
        // Tag with GOSSIP_ADDED_BY (not peer.toString()) so these untrusted entries are identifiable
        // and are the only ones evicted when the total-size cap is enforced.
        final Long now = NTP.getTime();
        final long addedWhen = now != null ? now : System.currentTimeMillis();

        List<PeerData> candidatePeers = new ArrayList<>(dialableAddresses.size());
        for (PeerAddress address : dialableAddresses) {
            candidatePeers.add(new PeerData(address, addedWhen, GOSSIP_ADDED_BY));
        }

        int added = addGossipedPeersIfMissing(candidatePeers);
        if (added > 0) {
            LOGGER.debug("Added {} data peer address(es) from PEERS gossip via {}", added,
                    peer.getPeerData().getAddress());
        }
    }

    /**
     * Merge untrusted gossiped data peers into allKnownPeers, enforcing the MAX_KNOWN_PEERS total
     * cap. Because the data layer has no age-based prune (and gossiped entries carry no connection
     * timestamps that such a prune could act on), this is the ONLY backstop against unbounded growth.
     * When adding would exceed the cap we first evict the OLDEST existing gossiped entries; we never
     * evict seeds, chain-discovered peers, or currently-connected peers. If no gossiped entries can
     * be evicted (the list is full of trusted entries), excess candidates are simply dropped.
     *
     * @return number of new gossiped entries actually stored
     */
    private int addGossipedPeersIfMissing(List<PeerData> candidatePeers) {
        int added = 0;

        synchronized (this.allKnownPeers) {
            for (PeerData candidatePeer : candidatePeers) {
                boolean alreadyKnown = this.allKnownPeers.stream()
                        .anyMatch(peerData -> peerData.getAddress().equals(candidatePeer.getAddress()));
                if (alreadyKnown)
                    continue;

                // Enforce the hard cap before inserting. Try to make room by evicting the oldest
                // gossiped (untrusted) entry that isn't currently connected.
                if (this.allKnownPeers.size() >= MAX_KNOWN_PEERS && !evictOldestGossipedPeer()) {
                    // Could not free a slot (no evictable gossiped entries) — drop remaining candidates.
                    LOGGER.debug("allKnownPeers at cap {} with no evictable gossiped entries; "
                            + "dropping {} further gossiped address(es)", MAX_KNOWN_PEERS,
                            candidatePeers.size() - added);
                    break;
                }

                this.allKnownPeers.add(candidatePeer);
                added++;
            }
        }

        return added;
    }

    /**
     * Evict the single oldest gossiped (GOSSIP_ADDED_BY) entry from allKnownPeers that is not
     * currently connected. Caller MUST hold the allKnownPeers monitor.
     *
     * @return true if an entry was evicted, false if none was evictable
     */
    private boolean evictOldestGossipedPeer() {
        PeerData oldest = null;
        for (PeerData peerData : this.allKnownPeers) {
            if (!GOSSIP_ADDED_BY.equals(peerData.getAddedBy()))
                continue;
            if (isConnectedPeer.test(peerData))
                continue;

            if (oldest == null || addedWhenOf(peerData) < addedWhenOf(oldest)) {
                oldest = peerData;
            }
        }

        if (oldest == null)
            return false;

        this.allKnownPeers.remove(oldest);
        return true;
    }

    private static long addedWhenOf(PeerData peerData) {
        Long addedWhen = peerData.getAddedWhen();
        return addedWhen != null ? addedWhen : Long.MAX_VALUE;
    }

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

        // Disconnect peers that are stuck during handshake
        // Get the PeerList snapshot and create a mutable copy (ArrayList) of its contents.
        // We use .stream().collect(Collectors.toList()) to create the mutable List<Peer>.
        List<Peer> handshakePeers = this.getImmutableConnectedPeers().stream()
                .collect(Collectors.toList());

        // Disregard peers that have completed handshake or only connected recently
        handshakePeers.removeIf(peer -> peer.getHandshakeStatus() == Handshake.COMPLETED
                || peer.getConnectionTimestamp() == null || peer.getConnectionTimestamp() > now - HANDSHAKE_TIMEOUT);

        for (Peer peer : handshakePeers) {
            LOGGER.trace("Disconnecting stuck peer {} at handshake status {}", 
                    peer.getPeerData().getAddress(), peer.getHandshakeStatus().name());
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
            LOGGER.trace("Disconnecting dead data peer {} (socket closed, handshake status: {})",
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
            LOGGER.warn("Found {} zombie data peer(s) in handshakedPeers list, forcing cleanup", 
                    zombieHandshakedPeers.size());
            for (Peer peer : zombieHandshakedPeers) {
                LOGGER.warn("Removing zombie handshaked data peer {} (socket closed)",
                        peer.getPeerData().getAddress());
                // Directly remove from lists as a defensive measure
                // This shouldn't normally be needed if disconnect() works properly,
                // but provides a safety net against the bug we're fixing
                this.removeHandshakedPeer(peer);
                this.removeConnectedPeer(peer);
            }
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



        // Prune 'old' peers from if we are over the count
        // getImmutableHandshakedPeers().size() works fine as PeerList has a size() method.
        int overCount = this.getImmutableHandshakedPeers().size() - Settings.getInstance().getMaxDataPeers();
        if (overCount > 0) { // Too Many peers we need to trim some out
            List<Peer> listDisconnectPeers = findOldPeers(overCount);
            for (Peer disconnectPeer : listDisconnectPeers) {
                disconnectPeer.disconnect("Over Max and Old");
            }
        }
    }

    /**
     * Returns the N peers with the lowest getLastQDNUse() values.
     *
     * @param num number of peers to return
     * @return list of peers with lowest getLastQDNUse()
     */
     List<Peer> findOldPeers(int num) {
        return this.getImmutableHandshakedPeers().stream()
                .sorted(Comparator.comparingLong(Peer::getLastQDNUse))
                .limit(num)
                .collect(Collectors.toList());
     }

    public void broadcast(Function<Peer, Message> peerMessageBuilder) {
        for (Peer peer : getImmutableHandshakedPeers()) {
            if (this.isShuttingDown)
                return;

            Message message = peerMessageBuilder.apply(peer);

            if (message == null) {
                continue;
            }

            LOGGER.trace("Broadcasting Message {} : {} to {} on NETWORKDATA", message.getType(), message.toString(), peer);

            // Use PeerSendManager for retry logic and backpressure handling
            try {
                PeerSendManager sendManager = PeerSendManagement.getInstance().getOrCreateSendManager(peer, true);
                
                
                // Calculate estimated message size for queue management
                int estimatedSize;
                try {
                    byte[] messageBytes = message.toBytes();
                    estimatedSize = messageBytes != null ? messageBytes.length : 1024;
                } catch (MessageException e) {
                    LOGGER.warn("Failed to calculate message size for broadcast, using default: {}", e.getMessage());
                    estimatedSize = 1024;
                }
                
                // Use HIGH_PRIORITY for broadcasts since they're important (file list requests, etc.)
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
                    LOGGER.trace("Failed to broadcast message to {} - socket closed", peer);
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
        closeI2PDataFallback();

        // Shutdown chunk processor pool first (stop accepting new chunk processing tasks)
        LOGGER.info("Shutting down chunk processor pool...");
        chunkProcessorPool.shutdown();
        try {
            // Wait up to 30 seconds for pending chunk processing to complete
            if (!chunkProcessorPool.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn("Chunk processor pool did not terminate gracefully, forcing shutdown");
                chunkProcessorPool.shutdownNow();
            } else {
                LOGGER.info("Chunk processor pool shutdown complete");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for chunk processor pool to terminate");
            chunkProcessorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Shutting down QDN force-connect executor...");
        forceConnectExecutor.shutdown();
        try {
            if (!forceConnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Force-connect executor did not terminate in time, forcing shutdown");
                forceConnectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for force-connect executor to terminate");
            forceConnectExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (this.ioThread != null && this.ioThread.isAlive()) {
            this.ioThread.interrupt();
            try {
                this.ioThread.join(5000);
                if (this.ioThread.isAlive())
                    LOGGER.warn("NetworkData I/O thread did not terminate in time");
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for NetworkData I/O thread");
            }
        }
        if (this.schedulerThread != null && this.schedulerThread.isAlive()) {
            this.schedulerThread.interrupt();
            try {
                this.schedulerThread.join(2000);
                if (this.schedulerThread.isAlive())
                    LOGGER.warn("NetworkData scheduler thread did not terminate in time");
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for NetworkData scheduler thread");
            }
        }
        try {
            this.networkDataWorkerPool.shutdown();
            if (!this.networkDataWorkerPool.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                this.networkDataWorkerPool.shutdownNow();
                if (!this.networkDataWorkerPool.awaitTermination(2000, TimeUnit.MILLISECONDS))
                    LOGGER.warn("NetworkData worker pool did not terminate");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for NetworkData worker pool to terminate");
            this.networkDataWorkerPool.shutdownNow();
        }

        try {  // DeMap QDN uPnP so other nodes can use it when we are done
            PortMapperFactory.getInstance().closeTcpPort(Settings.getInstance().getQDNListenPort());
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
