package org.qortium.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.transform.stream.StreamSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.exceptions.XMLMarshalException;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.qortium.block.BlockChain;
import org.qortium.controller.PirateChainWalletController;
import org.qortium.controller.arbitrary.ArbitraryDataStorageManager.StoragePolicy;
import org.qortium.crosschain.BitcoinyChainSpec;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyNetwork;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.PirateChain.PirateChainNet;
import org.qortium.network.message.MessageType;
import org.qortium.utils.EnumUtils;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
@SuppressWarnings("FieldCanBeLocal")
public class Settings {

	public enum AutoUpdateMode {
		OFF,
		CHECK_ONLY,
		NOTIFY,
		INSTALL
	}

	// New in v5.1.0 - Dedicated Ports for Data Flows; NetworkData Class
	private static final int MAINNET_QDN_LISTEN_PORT = 14894;
	private static final int TESTNET_QDN_LISTEN_PORT = 24894;

	private static final int MAINNET_LISTEN_PORT = 14892;
	private static final int TESTNET_LISTEN_PORT = 24892;

	private static final int MAINNET_API_PORT = 14891;
	private static final int TESTNET_API_PORT = 24891;

	private static final int MAINNET_DOMAIN_MAP_PORT = 80;
	private static final int TESTNET_DOMAIN_MAP_PORT = 8080;

	private static final int MAINNET_GATEWAY_PORT = 80;
	private static final int TESTNET_GATEWAY_PORT = 8080;

	private static final int MAINNET_DEV_PROXY_PORT = 14893;
	private static final int TESTNET_DEV_PROXY_PORT = 24893;

	private static final Logger LOGGER = LogManager.getLogger(Settings.class);
	private static final String SETTINGS_FILENAME = "settings.json";
	private static final ObjectMapper SETTINGS_JSON_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
	private static final TypeReference<LinkedHashMap<String, Object>> SETTINGS_MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};
	private static final TypeReference<LinkedHashMap<String, LinkedHashMap<String, BitcoinyServerSettings>>> BITCOINY_SERVERS_MAP_TYPE = new TypeReference<LinkedHashMap<String, LinkedHashMap<String, BitcoinyServerSettings>>>() {};
	@XmlTransient
	private static final Map<String, WritableSetting> WRITABLE_SETTINGS = buildWritableSettings();

	// Properties
	private static Settings instance;
	@XmlTransient
	private static Path activeSettingsPath;

	// Settings, and other config files
	private String userPath;

	// General
	private String localeLang = Locale.getDefault().getLanguage();

	// Common to all networking (API/P2P)
	private String bindAddress = "::"; // Use IPv6 wildcard to listen on all local addresses
    private final String bindAddressFallback = "0.0.0.0"; // Some systems are unable to bind using IPv6

	// API-related
	private boolean apiEnabled = true;
	private Integer apiPort;
	private boolean apiWhitelistEnabled = true;
	private String[] apiWhitelist = new String[] {
		"::1", "127.0.0.1"
	};
	private boolean publicApiWhitelistEnabled = false;
	private String[] publicApiWhitelist = new String[0];
	private String[] publicApiPaths = new String[0];

	/** Storage location for API key file (Nov 2021 onwards) */
	private String apiKeyPath = System.getProperty("user.dir");
	private String ourExternalIpAddress = null;
	private Boolean apiRestricted;
	private boolean apiLoggingEnabled = false;
	private boolean apiDocumentationEnabled = false;
	// Both of these need to be set for API to use SSL
	private String sslKeystorePathname = "QortiumKeyStore.jks";
	private String sslKeystorePassword = "default";

	// Domain mapping
	private Integer domainMapPort;
	private boolean domainMapEnabled = false;
	private boolean domainMapLoggingEnabled = false;
	private List<DomainMap> domainMap = null;

	// Gateway
	private Integer gatewayPort;
	private boolean gatewayEnabled = false;
	private boolean gatewayLoggingEnabled = false;
	private boolean gatewayLoopbackEnabled = false;

	// Developer Proxy
	private Integer devProxyPort;
	private boolean devProxyEnabled = false;
	private boolean devProxyLoggingEnabled = false;
	private boolean devProxyUnsafeEvalEnabled = false;

	// Specific to this node
	private boolean wipeUnconfirmedOnStart = false;
	/** Maximum number of unconfirmed transactions allowed per account */
	private int maxUnconfirmedPerAccount = 25;
	/** Max milliseconds into future for accepting new, unconfirmed transactions */
	private int maxTransactionTimestampFuture = 30 * 60 * 1000; // milliseconds

	/** Maximum number of CHAT transactions allowed per account in recent timeframe */
	private int maxRecentChatMessagesPerAccount = 250;
	/** Maximum age of a CHAT transaction to be considered 'recent' */
	private long recentChatMessagesMaxAge = 60 * 60 * 1000L; // milliseconds
	/** How long transient chat messages remain available from the dedicated chat store */
	private long chatMessageRetentionPeriod = 24 * 60 * 60 * 1000L; // milliseconds

	/** Whether and how this node checks, reports, or installs approved auto-updates. */
	private AutoUpdateMode autoUpdateMode = null;
	/** Whether we check, restart node without connected peers */
	private boolean autoRestartEnabled = false;
	/** How long between repository backups (ms), or 0 if disabled. */
	private long repositoryBackupInterval = 0; // ms
	/** Whether to show a notification when we backup repository. */
	private boolean showBackupNotification = false;
	/** Minimum time between repository maintenance attempts (ms) */
	private long repositoryMaintenanceMinInterval = 3 * 24 * 60 * 60 * 1000L; // 3 days (ms) default
	/** Maximum time between repository maintenance attempts (ms) (0 if disabled). */
	private long repositoryMaintenanceMaxInterval = 14 * 24 * 60 * 60 * 1000L; // 14 days (ms) default
	/** Whether to show a notification when we run scheduled maintenance. */
	private boolean showMaintenanceNotification = false;
	/** How long between repository checkpoints (ms). */
	private long repositoryCheckpointInterval = 60 * 60 * 1000L; // 1 hour (ms) default
	/** Whether to show a notification when we perform repository 'checkpoint'. */
	private boolean showCheckpointNotification = false;
	/* How many blocks to cache locally. Defaulted to 10, which covers a typical Synchronizer request + a few spare - increased to 100 */
	private int blockCacheSize = 100;

	/** Maximum number of transactions for the block minter to include in a block */
	private int maxTransactionsPerBlock = 100;

	/** How long to keep old, full, AT state data (ms). */
	private long atStatesMaxLifetime = 5 * 24 * 60 * 60 * 1000L; // milliseconds
	/** How often to attempt AT state trimming (ms). */
	private long atStatesTrimInterval = 5678L; // milliseconds
	/** Block height range to scan for trimmable AT states.<br>
	 * This has a significant effect on execution time. */
	private int atStatesTrimBatchSize = 100; // blocks
	/** Max number of AT states to trim in one go. */
	private int atStatesTrimLimit = 4000; // records

	/** How often to attempt online accounts signatures trimming (ms). */
	private long onlineSignaturesTrimInterval = 9876L; // milliseconds
	/** Block height range to scan for trimmable online accounts signatures.<br>
	 * This has a significant effect on execution time. */
	private int onlineSignaturesTrimBatchSize = 100; // blocks

	/** Lite nodes don't sync blocks, and instead request "derived data" from peers */
	private boolean lite = false;

	/** Whether we should prune old data to reduce database size
	 * This prevents the node from being able to serve older blocks - No longer used */
	private boolean topOnly = false;
	/** The amount of recent blocks we should keep when pruning */
	private int pruneBlockLimit = 6000;

	/** How often to attempt AT state pruning (ms). */
	private long atStatesPruneInterval = 3219L; // milliseconds
	/** Block height range to scan for prunable AT states.<br>
	 * This has a significant effect on execution time. */
	private int atStatesPruneBatchSize = 25; // blocks

	/** How often to attempt block pruning (ms). */
	private long blockPruneInterval = 3219L; // milliseconds
	/** Block height range to scan for prunable blocks.<br>
	 * This has a significant effect on execution time. */
	private int blockPruneBatchSize = 10000; // blocks

	/** Whether we should archive old data to reduce the database size */
	private boolean archiveEnabled = true;
	/** How often to attempt archiving (ms). */
	private long archiveInterval = 7171L; // milliseconds
	/** Serialization version to use when building an archive */
	private int defaultArchiveVersion = 1;

	/** Whether to automatically bootstrap instead of syncing from genesis */
	private boolean bootstrap = false;

	/** Registered names integrity check */
	private boolean namesIntegrityCheckEnabled = false;

	// Peer-to-peer related
	private boolean isTestNet = false;
	/** Single node testnet mode */
	private boolean singleNodeTestnet = false;
	/** Port number for inbound peer-to-peer connections. */
	private Integer listenPort;
	private Integer listenDataPort;
	/** Whether to attempt to open the listen port via UPnP */
	private boolean uPnPEnabled = true;
	/** Minimum number of peers to allow block minting / synchronization. */
	private int minBlockchainPeers = 3;
	/** Target number of outbound connections to peers we should make. */
	private int minOutboundPeers = 16;
	/** Maximum number of peer connections we allow. */
	private int maxPeers = 32;
	/** Number of slots to reserve for QDN data transfers */
	private int maxDataPeers = 64;
	/** Maximum number of threads for network engine. */
	private int maxNetworkThreadPoolSize = 512;
	/** Maximum number of threads for network proof-of-work compute, used during handshaking. */
	private int networkPoWComputePoolSize = 4;
	/** Maximum number of retry attempts if a peer fails to respond with the requested data */
	private int maxRetries = 3;

	/** The number of seconds of no activity before recovery mode begins */
	public long recoveryModeTimeout = 9999999999999L;

	/** Minimum peer version number required in order to sync with them */
	private String minPeerVersion = "1.0.0";

	/** Whether to allow connections with peers below minPeerVersion
	 * If true, we won't sync with them but they can still sync with us, and will show in the peers list
	 * If false, sync will be blocked both ways, and they will not appear in the peers list */
	private boolean allowConnectionsWithOlderPeerVersions = false;

	/** Minimum time (in seconds) that we should attempt to remain connected to a peer for */
	private int minPeerConnectionTime = 2 * 60 * 60; // seconds = 2hrs
	/** Maximum time (in seconds) that we should attempt to remain connected to a peer for */
	private int maxPeerConnectionTime = 6 * 60 * 60; // seconds = 6hrs
	/** Maximum time (in seconds) that a peer should remain connected when requesting QDN data */
	private int maxDataPeerConnectionTime = 30 * 60; // seconds

	/** Whether to sync multiple blocks at once in normal operation */
	private boolean fastSyncEnabled = true;
	/** Whether to sync multiple blocks at once when the peer has a different chain */
	private boolean fastSyncEnabledWhenResolvingFork = true;
	/** Maximum number of blocks to request at once */
	private int maxBlocksPerRequest = 100;
	/** Maximum number of blocks this node will serve in a single response */
	private int maxBlocksPerResponse = 200;

	// Which blockchains this node is running
	@XmlJavaTypeAdapter(WalletsMapXmlAdapter.class)
	private Map<String, Boolean> wallets = new HashMap<>();
	private String blockchainConfig = null; // use default from resources
	@XmlJavaTypeAdapter(StringMapXmlAdapter.class)
	private Map<String, String> bitcoinyNetworks = defaultBitcoinyNetworks();
	@XmlJavaTypeAdapter(BitcoinyServersMapXmlAdapter.class)
	private Map<String, Map<String, BitcoinyServerSettings>> bitcoinyServers = new LinkedHashMap<>();
	private PirateChainNet pirateChainNet = PirateChainNet.MAIN;
	// Also crosschain-related:
	/** Whether to show SysTray pop-up notifications when trade-bot entries change state */
	private boolean tradebotSystrayEnabled = false;

	/** Maximum buy attempts for each trade offer before it is considered failed, and hidden from the list */
	private int maxTradeOfferAttempts = 3;

	/** Wallets path - used for storing encrypted wallet caches for coins that require them */
	private String walletsPath = "wallets";

	private int arrrDefaultBirthday = 2000000;

	// Repository related
	/** Queries that take longer than this are logged. (milliseconds) */
	private Long slowQueryThreshold = null;
	/** Repository storage path. */
	private String repositoryPath = "db";
	/** Repository connection pool size. Needs to be a bit bigger than maxNetworkThreadPoolSize */
	private int repositoryConnectionPoolSize = 1920;
	private String[] initialPeers = new String[0];
	private List<String> fixedNetwork;

	// Export/import
	private String exportPath = "qortium-backup";

	// Bootstrap
	private String bootstrapFilenamePrefix = "";

	// Bootstrap sources
	private String[] bootstrapHosts = new String[0];

	// Lists
	private String listsPath = "lists";

	/** Array of NTP server hostnames. */
	private String[] ntpServers = new String[] {
		"pool.ntp.org",
		"0.pool.ntp.org",
		"1.pool.ntp.org",
		"2.pool.ntp.org",
		"3.pool.ntp.org",
		"asia.pool.ntp.org",
		"0.asia.pool.ntp.org",
		"1.asia.pool.ntp.org",
		"2.asia.pool.ntp.org",
		"3.asia.pool.ntp.org",
		"europe.pool.ntp.org",
		"0.europe.pool.ntp.org",
		"1.europe.pool.ntp.org",
		"2.europe.pool.ntp.org",
		"3.europe.pool.ntp.org",
		"north-america.pool.ntp.org",
		"0.north-america.pool.ntp.org",
		"1.north-america.pool.ntp.org",
		"2.north-america.pool.ntp.org",
		"3.north-america.pool.ntp.org",
		"oceania.pool.ntp.org",
		"0.oceania.pool.ntp.org",
		"1.oceania.pool.ntp.org",
		"2.oceania.pool.ntp.org",
		"3.oceania.pool.ntp.org",
		"south-america.pool.ntp.org",
		"0.south-america.pool.ntp.org",
		"1.south-america.pool.ntp.org",
		"2.south-america.pool.ntp.org",
		"3.south-america.pool.ntp.org"
	};
	/** Additional offset added to values returned by NTP.getTime() */
	private Long testNtpOffset = null;

	/* Foreign chains */

	/** The number of consecutive empty addresses required before treating a wallet's transaction set as complete */
	private int gapLimit = 3;

	/** How many wallet keys to generate when using bitcoinj as the blockchain interface (e.g. when sending coins) */
	private int bitcoinjLookaheadSize = 50;

	/** How many units of data to be kept in a blockchain cache before the cache should be reduced or cleared. */
	private int blockchainCacheLimit = 1000;

	// Data storage (QDN)

	/** Data storage enabled/disabled*/
	private boolean qdnEnabled = true;
	/** Data storage path. */
	private String dataPath = "data";
	/** Data storage path (for temporary data). Defaults to {dataPath}/_temp */
	private String tempDataPath = null;

	/** Storage policy to indicate which data should be hosted */
	private String storagePolicy = "FOLLOWED_OR_VIEWED";

	/** Whether to allow data outside of the storage policy to be relayed between other peers */
	private boolean relayModeEnabled = true;

	/** Whether to remember which data was originally uploaded using this node.
	 * This prevents auto deletion of own files when storage limits are reached. */
	private boolean originalCopyIndicatorFileEnabled = true;

	/** Whether to make connections directly with peers that have the required data */
	private boolean directDataRetrievalEnabled = true;

	/** Expiry time (ms) for (unencrypted) built/cached data */
	private Long builtDataExpiryInterval = 7 * 24 * 60 * 60 * 1000L; // 7 days

	/** Whether to validate every layer when building arbitrary data, or just the final layer */
	private boolean validateAllDataLayers = false;

	/** Whether to allow public (decryptable) data to be stored */
	private boolean publicDataEnabled = true;
	/** Whether to allow private (non-decryptable) data to be stored */
	private boolean privateDataEnabled = true;

	/** Maximum total size of hosted data, in bytes. Unlimited if null */
	private Long maxStorageCapacity = null;

	/** Whether to serve QDN data without authentication */
	private boolean qdnAuthBypassEnabled = false;

	/** Limit threads per message type */
	private Set<ThreadLimit> maxThreadsPerMessageType = new HashSet<>();

	/** The number of threads per message type at which a warning should be logged.
	 * Exclude from settings.json to disable this warning. */
	private Integer threadCountPerMessageTypeWarningThreshold = null;

	/**
	 * DB Cache Enabled?
	 */
	private boolean dbCacheEnabled = true;

	/**
	 * DB Cache Thread Priority
	 *
	 * If DB Cache is disabled, then this is ignored. If value is lower then 1, than 1 is used. If value is higher
	 * than 10,, then 10 is used.
	 */
	private int dbCacheThreadPriority = 1;

	/**
	 * DB Cache Frequency
	 *
	 * The number of seconds in between DB cache updates. If DB Cache is disabled, then this is ignored.
	 */
	private int dbCacheFrequency = 1800;

	/**
	 * Network Thread Priority
	 *
	 * The Network Thread Priority
	 *
	 * The thread priority (1 is lowest, 10 is highest) of the threads used for network peer connections. This is the
	 * main thread connecting to a peer in the network.
	 */
    private int networkThreadPriority = 7;

	/**
	 * The Handshake Thread Priority
	 *
	 * The thread priority (1 i slowest, 10 is highest) of the threads used for peer handshake messaging. This is a
	 * secondary thread to exchange status messaging to a peer in the network.
	 */
	private int handshakeThreadPriority = 7;

	/**
	 * Pruning Thread Priority
	 *
	 * The thread priority (1 is lowest, 10 is highest) of the threads used for database pruning and trimming.
	 */
	private int pruningThreadPriority = 2;

	/**
	 * Sychronizer Thread Priority
	 *
	 * The thread priority (1 is lowest, 10 is highest) of the threads used for synchronizing with the others peers.
	 */
	private int synchronizerThreadPriority = 10;

	/**
	 * Archiving Pause
	 *
	 * In milliseconds
	 *
	 * The pause in between archiving blocks to allow other processes to execute.
	 */
	private long archivingPause = 3000;

	/**
	 * Enable Balance Recorder?
	 *
	 * True for balance recording, otherwise false.
	 */
	private boolean balanceRecorderEnabled = false;

	/**
	 * Balance Recorder Priority
	 *
	 * The thread priority (1 is lowest, 10 is highest) of the balance recorder thread, if enabled.
	 */
	private int balanceRecorderPriority = 1;

	/**
	 * Balance Recorder Frequency
	 *
	 * How often the balances will be recorded, if enabled, measured in minutes.
	 */
	private int balanceRecorderFrequency = 20;

	/**
	 * Balance Recorder Capacity
	 *
	 * The number of balance recorder ranges will be held in memory.
	 */
	private int balanceRecorderCapacity = 1000;

	/**
	 * Minimum Balance Recording
	 *
	 * The minimum recorded balance change in atomic native asset units (1/100000000 native asset)
	 */
    private long minimumBalanceRecording = 100000000;

	/**
	 * Top Balance Logging Limit
	 *
	 * When logging the number limit of top balance changes to show in the logs for any given block range.
	 */
	private long topBalanceLoggingLimit = 100;

	/**
	 * Balance Recorder Rollback Allowance
	 *
	 * If the balance recorder is enabled, it must protect its prior balances by this number of blocks in case of
	 * a blockchain rollback and reorganization.
	 */
	private int balanceRecorderRollbackAllowance = 100;

	/**
	 * Is Reward Recording Only
	 *
	 * Set true to only retain the recordings that cover reward distributions, otherwise set false.
	 */
    private boolean rewardRecordingOnly = true;

	/**
	 * Is The Connection Monitored?
	 *
	 * Is the database connection pooled monitored?
	 */
    private boolean connectionPoolMonitorEnabled = false;

	/**
	 * Buiild Arbitrary Resources Batch Size
	 *
	 * The number resources to batch per iteration when rebuilding.
	 */
	private int buildArbitraryResourcesBatchSize = 200;

	/**
	 * Arbitrary Indexing Priority
	 *
	 * The thread priority when indexing arbitrary resources.
	 */
    private int arbitraryIndexingPriority = 1;

	/**
	 * Arbitrary Indexing Frequency (In Minutes)
	 *
	 * The frequency at which the arbitrary indices are cached.
	 */
	private int arbitraryIndexingFrequency = 10;

    private boolean rebuildArbitraryResourceCacheTaskEnabled = false;

	/**
	 * Rebuild Arbitrary Resource Cache Task Delay (In Minutes)
	 *
	 * Waiting period before the first rebuild task is started.
	 */
	private int rebuildArbitraryResourceCacheTaskDelay = 300;

	/**
	 * Rebuild Arbitrary Resource Cache Task Period (In Hours)
	 *
	 * The frequency the arbitrary resource cache is rebuilt.
	 */
	private int rebuildArbitraryResourceCacheTaskPeriod = 24;

	/**
	 * Electrum Thread Count
	 *
	 * The number of threads ready to access Electrum servers for the supported foreign coins.
	 */
    private int electrumThreadCount = 12;

	/** Whether Electrum-compatible BTC-like chains may connect to plaintext TCP servers. */
	private boolean allowPlaintextElectrumServers = false;

	/**
	 * Host Monitor Enabled
	 *
	 * The Host Monitor is a thread that runs in the background. It crawls through the QDN data directory to monitor
	 * what is in there. If set to false, then it will not run.
	 */
    private  boolean hostMonitorEnabled = false;

    // Domain mapping
	public static class ThreadLimit {
		private String messageType;
		private Integer limit;

		private ThreadLimit() { // makes JAXB happy; will never be invoked
		}

		private ThreadLimit(String messageType, Integer limit) {
			this.messageType = messageType;
			this.limit = limit;
		}

		public String getMessageType() {
			return messageType;
		}

		public void setMessageType(String messageType) {
			this.messageType = messageType;
		}

		public Integer getLimit() {
			return limit;
		}

		public void setLimit(Integer limit) {
			this.limit = limit;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof ThreadLimit))
				return false;

			return this.messageType.equals(((ThreadLimit) other).getMessageType());
		}

		@Override
		public int hashCode() {
			return Objects.hash(messageType);
		}
	}


	// Domain mapping
	public static class DomainMap {
		private String domain;
		private String name;

		private DomainMap() { // makes JAXB happy; will never be invoked
		}

		public String getDomain() {
			return domain;
		}

		public void setDomain(String domain) {
			this.domain = domain;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class BitcoinyServerSettings {
		private boolean replaceDefaults;
		private List<BitcoinyServer> servers = new ArrayList<>();
		private List<BitcoinyServer> disabledServers = new ArrayList<>();

		public BitcoinyServerSettings() {
		}

		public BitcoinyServerSettings(BitcoinyServerSettings other) {
			if (other == null)
				return;

			this.replaceDefaults = other.replaceDefaults;
			this.servers = copyServers(other.servers);
			this.disabledServers = copyServers(other.disabledServers);
		}

		public boolean isReplaceDefaults() {
			return this.replaceDefaults;
		}

		public void setReplaceDefaults(boolean replaceDefaults) {
			this.replaceDefaults = replaceDefaults;
		}

		public List<BitcoinyServer> getServers() {
			return copyServers(this.servers);
		}

		public void setServers(List<BitcoinyServer> servers) {
			this.servers = copyServers(servers);
		}

		public List<BitcoinyServer> getDisabledServers() {
			return copyServers(this.disabledServers);
		}

		public void setDisabledServers(List<BitcoinyServer> disabledServers) {
			this.disabledServers = copyServers(disabledServers);
		}

		public boolean addServer(BitcoinyServer server) {
			if (this.servers.contains(server))
				return false;

			return this.servers.add(server);
		}

		public boolean removeServer(BitcoinyServer server) {
			return this.servers.remove(server);
		}

		public boolean addDisabledServer(BitcoinyServer server) {
			if (this.disabledServers.contains(server))
				return false;

			return this.disabledServers.add(server);
		}

		public boolean removeDisabledServer(BitcoinyServer server) {
			return this.disabledServers.remove(server);
		}

		private static List<BitcoinyServer> copyServers(List<BitcoinyServer> servers) {
			List<BitcoinyServer> copy = new ArrayList<>();
			if (servers == null)
				return copy;

			for (BitcoinyServer server : servers)
				copy.add(new BitcoinyServer(server));

			return copy;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class BitcoinyServer {
		private String hostName;
		private int port;
		private String connectionType;

		public BitcoinyServer() {
		}

		public BitcoinyServer(String hostName, int port, String connectionType) {
			this.hostName = hostName;
			this.port = port;
			this.connectionType = connectionType;
		}

		public BitcoinyServer(BitcoinyServer other) {
			if (other == null)
				return;

			this.hostName = other.hostName;
			this.port = other.port;
			this.connectionType = other.connectionType;
		}

		public static BitcoinyServer from(ChainableServer server) {
			return normaliseBitcoinyServer(new BitcoinyServer(
					server.getHostName(),
					server.getPort(),
					server.getConnectionType().name()));
		}

		public String getHostName() {
			return this.hostName;
		}

		public void setHostName(String hostName) {
			this.hostName = hostName;
		}

		public int getPort() {
			return this.port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getConnectionType() {
			return this.connectionType;
		}

		public void setConnectionType(String connectionType) {
			this.connectionType = connectionType;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other)
				return true;

			if (!(other instanceof BitcoinyServer))
				return false;

			BitcoinyServer otherServer = (BitcoinyServer) other;
			return this.port == otherServer.port
					&& Objects.equals(this.hostName, otherServer.hostName)
					&& Objects.equals(this.connectionType, otherServer.connectionType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.hostName, this.port, this.connectionType);
		}
	}

	// Constructors

	private Settings() {
	}

	// Other methods

	private enum WritableSettingType {
		BOOLEAN,
		AUTO_UPDATE_MODE,
		STRING_ARRAY,
		STRING_MAP,
		BOOLEAN_MAP,
		BITCOINY_SERVERS,
		PIRATE_CHAIN_NET,
		STORAGE_POLICY
	}

	@XmlTransient
	private static class WritableSetting {
		private final WritableSettingType type;
		private final boolean restartRequired;

		private WritableSetting(WritableSettingType type, boolean restartRequired) {
			this.type = type;
			this.restartRequired = restartRequired;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class SettingsUpdateResult {
		public boolean saved;
		public String settingsPath;
		public List<String> updated = new ArrayList<>();
		public List<String> removed = new ArrayList<>();
		public List<String> applied = new ArrayList<>();
		public List<String> restartRequired = new ArrayList<>();

		public SettingsUpdateResult() {
		}
	}

	private static Map<String, WritableSetting> buildWritableSettings() {
		Map<String, WritableSetting> settings = new LinkedHashMap<>();

		settings.put("wallets", new WritableSetting(WritableSettingType.BOOLEAN_MAP, true));
		settings.put("bitcoinyNetworks", new WritableSetting(WritableSettingType.STRING_MAP, true));
		settings.put("bitcoinyServers", new WritableSetting(WritableSettingType.BITCOINY_SERVERS, true));
		settings.put("allowPlaintextElectrumServers", new WritableSetting(WritableSettingType.BOOLEAN, true));
		settings.put("pirateChainNet", new WritableSetting(WritableSettingType.PIRATE_CHAIN_NET, true));
		settings.put("autoUpdateMode", new WritableSetting(WritableSettingType.AUTO_UPDATE_MODE, true));
		settings.put("autoRestartEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("bootstrapHosts", new WritableSetting(WritableSettingType.STRING_ARRAY, false));
		settings.put("qdnEnabled", new WritableSetting(WritableSettingType.BOOLEAN, true));
		settings.put("storagePolicy", new WritableSetting(WritableSettingType.STORAGE_POLICY, false));
		settings.put("relayModeEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("publicDataEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("privateDataEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("apiDocumentationEnabled", new WritableSetting(WritableSettingType.BOOLEAN, true));
		settings.put("apiLoggingEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("devProxyEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("devProxyUnsafeEvalEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));
		settings.put("tradebotSystrayEnabled", new WritableSetting(WritableSettingType.BOOLEAN, false));

		return Collections.unmodifiableMap(settings);
	}

	public static synchronized Settings getInstance() {
		if (instance == null)
			fileInstance(SETTINGS_FILENAME);

		return instance;
	}

	public static synchronized Settings getLoadedInstance() {
		return instance;
	}

	/**
	 * Parse settings from given file.
	 * <p>
	 * Throws <tt>RuntimeException</tt> with <tt>UnmarshalException</tt> as cause if settings file could not be parsed.
	 * <p>
	 * We use <tt>RuntimeException</tt> because it can be caught first caller of {@link #getInstance()} above,
	 * but it's not necessary to surround later {@link #getInstance()} calls
	 * with <tt>try-catch</tt> as they should be read-only.
	 *
	 * @param filename
	 * @throws RuntimeException with UnmarshalException as cause if settings file could not be parsed
	 * @throws RuntimeException with FileNotFoundException as cause if settings file could not be found/opened
	 * @throws RuntimeException with JAXBException as cause if some unexpected JAXB-related error occurred
	 * @throws RuntimeException with IOException as cause if some unexpected I/O-related error occurred
	 */
	public static synchronized void fileInstance(String filename) {
		Settings settings = null;
		String path = "";
		Path settingsPath = null;

		do {
			settingsPath = resolveSettingsPath(path, filename);
			LOGGER.info(String.format("Using settings file: %s", settingsPath));

			settings = unmarshalSettings(settingsPath);

			if (settings.userPath != null) {
				// Adjust filename and go round again
				path = settings.userPath;

				// Add trailing directory separator if needed
				if (!path.endsWith(File.separator))
					path += File.separator;
			}
		} while (settings.userPath != null);

		settings = prepareLoadedSettings(settings, path);

		// Successfully read settings now in effect
		instance = settings;
		activeSettingsPath = settingsPath.toAbsolutePath().normalize();

		// Now read blockchain config
		BlockChain.fileInstance(settings.getUserPath(), settings.getBlockchainConfig());
	}

	private static Unmarshaller createUnmarshaller() {
		try {
			// Create JAXB context aware of Settings
			JAXBContext jc = JAXBContextFactory.createContext(new Class[] {
				Settings.class
			}, null);

			// Create unmarshaller
			Unmarshaller unmarshaller = jc.createUnmarshaller();

			// Set the unmarshaller media type to JSON
			unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");

			// Tell unmarshaller that there's no JSON root element in the JSON input
			unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

			return unmarshaller;
		} catch (JAXBException e) {
			String message = "Failed to setup unmarshaller to process settings file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}
	}

	private static Path resolveSettingsPath(String path, String filename) {
		Path filenamePath = Paths.get(filename);
		if (path == null || path.isEmpty())
			return filenamePath;

		return Paths.get(path).resolve(filenamePath.getFileName());
	}

	private static Settings unmarshalSettings(Path settingsPath) {
		Unmarshaller unmarshaller = createUnmarshaller();

		// Create the StreamSource by creating Reader to the JSON input
		try (Reader settingsReader = new FileReader(settingsPath.toFile())) {
			StreamSource json = new StreamSource(settingsReader);

			// Attempt to unmarshal JSON stream to Settings
			return unmarshaller.unmarshal(json, Settings.class).getValue();
		} catch (FileNotFoundException e) {
			String message = "Settings file not found: " + settingsPath;
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (UnmarshalException e) {
			Throwable linkedException = e.getLinkedException();
			if (linkedException instanceof XMLMarshalException) {
				String message = ((XMLMarshalException) linkedException).getInternalException().getLocalizedMessage();
				LOGGER.error(message);
				throw new RuntimeException(message, e);
			}

			String message = "Failed to parse settings file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (JAXBException e) {
			String message = "Unexpected JAXB issue while processing settings file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		} catch (IOException e) {
			String message = "Unexpected I/O issue while processing settings file";
			LOGGER.error(message, e);
			throw new RuntimeException(message, e);
		}
	}

	private static Settings prepareLoadedSettings(Settings settings, String userPath) {
		// Set some additional defaults if needed
		settings.setAdditionalDefaults();

		// Validate settings
		settings.validate();

		// Minor fix-up
		settings.userPath = userPath;

		return settings;
	}

	public static synchronized SettingsUpdateResult updateAndSave(String patchJson) throws IOException {
		Settings settings = getInstance();
		if (activeSettingsPath == null)
			throw new IllegalStateException("Active settings file is unknown");

		LinkedHashMap<String, Object> patch = parseSettingsPatch(patchJson);
		SettingsUpdateResult result = new SettingsUpdateResult();
		result.settingsPath = activeSettingsPath.toString();

		if (patch.isEmpty())
			return result;

		LinkedHashMap<String, Object> mergedSettings = readSettingsMap(activeSettingsPath);
		LinkedHashSet<String> updated = new LinkedHashSet<>();
		LinkedHashSet<String> removed = new LinkedHashSet<>();
		LinkedHashSet<String> restartRequired = new LinkedHashSet<>();

		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			String settingName = entry.getKey();
			WritableSetting writableSetting = WRITABLE_SETTINGS.get(settingName);
			if (writableSetting == null)
				throw new IllegalArgumentException("Setting is not writable: " + settingName);

			Object value = entry.getValue();
			if (value == null) {
				mergedSettings.remove(settingName);
				removed.add(settingName);
			} else {
				value = validateWritableSettingValue(settingName, writableSetting, value);
				mergedSettings.put(settingName, value);
				updated.add(settingName);
			}

			if (writableSetting.restartRequired)
				restartRequired.add(settingName);
		}

		Path tempSettingsPath = writeTempSettings(activeSettingsPath, mergedSettings);
		try {
			Settings validatedSettings;
			try {
				validatedSettings = prepareLoadedSettings(unmarshalSettings(tempSettingsPath), settings.userPath);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("Invalid settings patch: " + e.getMessage(), e);
			}

			replaceSettingsFile(tempSettingsPath, activeSettingsPath);
			tempSettingsPath = null;

			instance = validatedSettings;

			result.saved = true;
			result.updated = new ArrayList<>(updated);
			result.removed = new ArrayList<>(removed);
			result.restartRequired = new ArrayList<>(restartRequired);

			LinkedHashSet<String> applied = new LinkedHashSet<>();
			applied.addAll(updated);
			applied.addAll(removed);
			applied.removeAll(restartRequired);
			result.applied = new ArrayList<>(applied);

			return result;
		} finally {
			if (tempSettingsPath != null)
				Files.deleteIfExists(tempSettingsPath);
		}
	}

	public static synchronized SettingsUpdateResult updateBitcoinyServersAndSave(Map<String, Map<String, BitcoinyServerSettings>> bitcoinyServers) throws IOException {
		LinkedHashMap<String, Object> patch = new LinkedHashMap<>();
		patch.put("bitcoinyServers", normaliseBitcoinyServersMap(bitcoinyServers));
		return updateAndSave(SETTINGS_JSON_MAPPER.writeValueAsString(patch));
	}

	public static Path getActiveSettingsPath() {
		return activeSettingsPath;
	}

	private static LinkedHashMap<String, Object> parseSettingsPatch(String patchJson) {
		if (patchJson == null || patchJson.trim().isEmpty())
			throw new IllegalArgumentException("Settings patch is empty");

		try {
			return SETTINGS_JSON_MAPPER.readValue(patchJson, SETTINGS_MAP_TYPE);
		} catch (IOException e) {
			throw new IllegalArgumentException("Settings patch must be a JSON object", e);
		}
	}

	private static LinkedHashMap<String, Object> readSettingsMap(Path settingsPath) throws IOException {
		byte[] settingsBytes = Files.readAllBytes(settingsPath);
		if (settingsBytes.length == 0)
			return new LinkedHashMap<>();

		return SETTINGS_JSON_MAPPER.readValue(settingsBytes, SETTINGS_MAP_TYPE);
	}

	private static Object validateWritableSettingValue(String settingName, WritableSetting writableSetting, Object value) {
		switch (writableSetting.type) {
			case BOOLEAN:
				if (!(value instanceof Boolean))
					throw new IllegalArgumentException("Setting must be a boolean: " + settingName);
				return value;

			case AUTO_UPDATE_MODE:
				if (!(value instanceof String))
					throw new IllegalArgumentException("Setting must be an auto-update mode name: " + settingName);
				return AutoUpdateMode.valueOf(((String) value).trim().toUpperCase(Locale.ROOT)).name();

			case STRING_ARRAY:
				validateStringArraySetting(settingName, value);
				return value;

			case STRING_MAP:
				validateMapSetting(settingName, value, String.class);
				return value;

			case BOOLEAN_MAP:
				validateMapSetting(settingName, value, Boolean.class);
				return value;

			case BITCOINY_SERVERS:
				return validateBitcoinyServersSetting(settingName, value);

			case PIRATE_CHAIN_NET:
				if (!(value instanceof String))
					throw new IllegalArgumentException("Setting must be a PirateChain network name: " + settingName);
				PirateChainNet.valueOf((String) value);
				return value;

			case STORAGE_POLICY:
				if (!(value instanceof String))
					throw new IllegalArgumentException("Setting must be a storage policy name: " + settingName);
				StoragePolicy.valueOf((String) value);
				return value;

			default:
				throw new IllegalArgumentException("Unsupported writable setting type: " + writableSetting.type);
		}
	}

	private static void validateStringArraySetting(String settingName, Object value) {
		if (!(value instanceof List<?>))
			throw new IllegalArgumentException("Setting must be a string array: " + settingName);

		for (Object entry : (List<?>) value) {
			if (!(entry instanceof String))
				throw new IllegalArgumentException("Setting array must contain strings only: " + settingName);
		}
	}

	private static void validateMapSetting(String settingName, Object value, Class<?> valueClass) {
		if (!(value instanceof Map<?, ?>))
			throw new IllegalArgumentException("Setting must be an object: " + settingName);

		for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
			if (!(entry.getKey() instanceof String) || !valueClass.isInstance(entry.getValue()))
				throw new IllegalArgumentException(String.format("Setting object must contain string keys and %s values only: %s",
						valueClass.getSimpleName(), settingName));
		}
	}

	private static Object validateBitcoinyServersSetting(String settingName, Object value) {
		if (!(value instanceof Map<?, ?>))
			throw new IllegalArgumentException("Setting must be an object: " + settingName);

		try {
			LinkedHashMap<String, LinkedHashMap<String, BitcoinyServerSettings>> parsed = SETTINGS_JSON_MAPPER.convertValue(value, BITCOINY_SERVERS_MAP_TYPE);
			Map<String, Map<String, BitcoinyServerSettings>> normalised = normaliseBitcoinyServersMap(parsed);
			return SETTINGS_JSON_MAPPER.convertValue(normalised, Object.class);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid bitcoinyServers setting: " + e.getMessage(), e);
		}
	}

	private static Path writeTempSettings(Path settingsPath, LinkedHashMap<String, Object> settings) throws IOException {
		Path absoluteSettingsPath = settingsPath.toAbsolutePath().normalize();
		Path settingsDirectory = absoluteSettingsPath.getParent();
		if (settingsDirectory == null)
			settingsDirectory = Paths.get("").toAbsolutePath();

		Path tempSettingsPath = Files.createTempFile(settingsDirectory, "settings-", ".tmp");
		String json = SETTINGS_JSON_MAPPER.writeValueAsString(settings) + System.lineSeparator();
		Files.write(tempSettingsPath, json.getBytes(StandardCharsets.UTF_8));
		return tempSettingsPath;
	}

	private static void replaceSettingsFile(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void throwValidationError(String message) {
		throw new RuntimeException(message, new UnmarshalException(message));
	}

	private void validate() {
		normaliseBitcoinyNetworks();
		normaliseBitcoinyServers();
		// Validation goes here
		if (this.minBlockchainPeers < 1 && !singleNodeTestnet)
			throwValidationError("minBlockchainPeers must be at least 1");

		if (this.topOnly)
			throwValidationError("topOnly mode is no longer supported");

		try {
			StoragePolicy.valueOf(this.storagePolicy);
		} catch (IllegalArgumentException ex) {
			String possibleValues = EnumUtils.getNames(StoragePolicy.class, ", ");
			throwValidationError(String.format("storagePolicy must be one of: %s", possibleValues));
		}
	}

	private static Map<String, String> defaultBitcoinyNetworks() {
		Map<String, String> networks = new LinkedHashMap<>();
		for (String currencyCode : BitcoinyChainSpecs.currencyCodes())
			networks.put(currencyCode, BitcoinyChainSpecs.MAIN);

		return networks;
	}

	private void normaliseBitcoinyNetworks() {
		Map<String, String> normalisedNetworks = defaultBitcoinyNetworks();
		if (this.bitcoinyNetworks != null) {
			for (Map.Entry<String, String> entry : this.bitcoinyNetworks.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null)
					continue;

				String currencyCode = entry.getKey().trim().toUpperCase(Locale.ROOT);
				String networkName = entry.getValue().trim().toUpperCase(Locale.ROOT);

				BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(currencyCode);
				if (spec == null)
					throwValidationError("Unsupported Bitcoiny network coin: " + entry.getKey());

				if (spec.getNetwork(networkName) == null)
					throwValidationError(String.format("Unsupported %s Bitcoiny network: %s", currencyCode, entry.getValue()));

				normalisedNetworks.put(currencyCode, networkName);
			}
		}

		this.bitcoinyNetworks = normalisedNetworks;
	}

	private void normaliseBitcoinyServers() {
		this.bitcoinyServers = normaliseBitcoinyServersMap(this.bitcoinyServers);
	}

	private static Map<String, Map<String, BitcoinyServerSettings>> normaliseBitcoinyServersMap(Map<String, ? extends Map<String, BitcoinyServerSettings>> serverSettings) {
		Map<String, Map<String, BitcoinyServerSettings>> normalisedServers = new LinkedHashMap<>();
		if (serverSettings == null)
			return normalisedServers;

		for (Map.Entry<String, ? extends Map<String, BitcoinyServerSettings>> coinEntry : serverSettings.entrySet()) {
			if (coinEntry.getKey() == null)
				continue;

			String currencyCode = coinEntry.getKey().trim().toUpperCase(Locale.ROOT);
			BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(currencyCode);
			if (spec == null)
				throwValidationError("Unsupported Bitcoiny server coin: " + coinEntry.getKey());

			Map<String, BitcoinyServerSettings> normalisedNetworkServers = new LinkedHashMap<>();
			Map<String, BitcoinyServerSettings> networkSettings = coinEntry.getValue();
			if (networkSettings != null) {
				for (Map.Entry<String, BitcoinyServerSettings> networkEntry : networkSettings.entrySet()) {
					if (networkEntry.getKey() == null)
						continue;

					String networkName = networkEntry.getKey().trim().toUpperCase(Locale.ROOT);
					if (spec.getNetwork(networkName) == null)
						throwValidationError(String.format("Unsupported %s Bitcoiny server network: %s", currencyCode, networkEntry.getKey()));

					normalisedNetworkServers.put(networkName, normaliseBitcoinyServerSettings(networkEntry.getValue()));
				}
			}

			if (!normalisedNetworkServers.isEmpty())
				normalisedServers.put(currencyCode, normalisedNetworkServers);
		}

		return normalisedServers;
	}

	private static BitcoinyServerSettings normaliseBitcoinyServerSettings(BitcoinyServerSettings settings) {
		BitcoinyServerSettings normalisedSettings = new BitcoinyServerSettings();
		if (settings == null)
			return normalisedSettings;

		normalisedSettings.setReplaceDefaults(settings.isReplaceDefaults());
		normalisedSettings.setServers(normaliseBitcoinyServerList(settings.getServers()));
		normalisedSettings.setDisabledServers(normaliseBitcoinyServerList(settings.getDisabledServers()));
		return normalisedSettings;
	}

	private static List<BitcoinyServer> normaliseBitcoinyServerList(List<BitcoinyServer> servers) {
		LinkedHashSet<BitcoinyServer> normalisedServers = new LinkedHashSet<>();
		if (servers != null) {
			for (BitcoinyServer server : servers)
				normalisedServers.add(normaliseBitcoinyServer(server));
		}

		return new ArrayList<>(normalisedServers);
	}

	private static BitcoinyServer normaliseBitcoinyServer(BitcoinyServer server) {
		if (server == null)
			throwValidationError("Bitcoiny server entry must not be null");

		String hostName = server.getHostName() == null ? null : server.getHostName().trim().toLowerCase(Locale.ROOT);
		if (hostName == null || hostName.isEmpty())
			throwValidationError("Bitcoiny server hostName is required");
		if (hostName.endsWith(".onion"))
			throwValidationError("Bitcoiny server hostName must not be an onion address: " + hostName);

		int port = server.getPort();
		if (port <= 0 || port > 65535)
			throwValidationError("Bitcoiny server port must be between 1 and 65535: " + port);

		String connectionType = server.getConnectionType() == null ? null : server.getConnectionType().trim().toUpperCase(Locale.ROOT);
		if (!"SSL".equals(connectionType) && !"TCP".equals(connectionType))
			throwValidationError("Bitcoiny server connectionType must be SSL or TCP");

		return new BitcoinyServer(hostName, port, connectionType);
	}

	private void setAdditionalDefaults() {
		// Populate defaults for maxThreadsPerMessageType. If any are specified in settings.json, they will take priority.
		maxThreadsPerMessageType.add(new ThreadLimit("ARBITRARY_DATA_FILE", 5));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_ARBITRARY_DATA_FILE", 15));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_ARBITRARY_DATA_FILES", 5));   // New in v5.10
		maxThreadsPerMessageType.add(new ThreadLimit("ARBITRARY_DATA", 5));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_ARBITRARY_DATA", 5));
		maxThreadsPerMessageType.add(new ThreadLimit("ARBITRARY_DATA_FILE_LIST", 50));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_ARBITRARY_DATA_FILE_LIST", 50));
		maxThreadsPerMessageType.add(new ThreadLimit("ARBITRARY_SIGNATURES", 5));
		maxThreadsPerMessageType.add(new ThreadLimit("ARBITRARY_METADATA", 5));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_ARBITRARY_METADATA", 100));
		maxThreadsPerMessageType.add(new ThreadLimit("GET_TRANSACTION", 50));
		maxThreadsPerMessageType.add(new ThreadLimit("TRANSACTION_SIGNATURES", 50));
		maxThreadsPerMessageType.add(new ThreadLimit("TRADE_PRESENCES", 50));
	}

	// Getters / setters

	public String getUserPath() {
		return this.userPath;
	}

	public String getLocaleLang() {
		return this.localeLang;
	}

	public boolean isApiEnabled() {
		return this.apiEnabled;
	}

	public int getApiPort() {
		if (this.apiPort != null)
			return this.apiPort;

		return this.isTestNet ? TESTNET_API_PORT : MAINNET_API_PORT;
	}

	public String[] getApiWhitelist() {
		if (!this.apiWhitelistEnabled) {
			// Allow all connections if the whitelist is disabled
			return new String[] {"0.0.0.0/0", "::/0"};
		}
		return this.apiWhitelist;
	}

	public boolean isPublicApiWhitelistEnabled() {
		return this.publicApiWhitelistEnabled;
	}

	public String[] getPublicApiWhitelist() {
		return this.publicApiWhitelist != null ? this.publicApiWhitelist : new String[0];
	}

	public String[] getPublicApiPaths() {
		return this.publicApiPaths != null ? this.publicApiPaths : new String[0];
	}

	public boolean isApiRestricted() {
		// Explicitly set value takes precedence
		if (this.apiRestricted != null)
			return this.apiRestricted;

		// Not set in config file, so restrict if not testnet
		return !BlockChain.getInstance().isTestChain();
	}

	public String getOurExternalIpAddress() {
		return this.ourExternalIpAddress;
	}

	public String getApiKeyPath() {
		return this.apiKeyPath;
	}

	public boolean isApiLoggingEnabled() {
		return this.apiLoggingEnabled;
	}

	public boolean isApiDocumentationEnabled() {
		return this.apiDocumentationEnabled;
	}

	public String getSslKeystorePathname() {
		return this.sslKeystorePathname;
	}

	public String getSslKeystorePassword() {
		return this.sslKeystorePassword;
	}

	public int getDomainMapPort() {
		if (this.domainMapPort != null)
			return this.domainMapPort;

		return this.isTestNet ? TESTNET_DOMAIN_MAP_PORT : MAINNET_DOMAIN_MAP_PORT;
	}

	public boolean isDomainMapEnabled() {
		return this.domainMapEnabled;
	}

	public boolean isDomainMapLoggingEnabled() {
		return this.domainMapLoggingEnabled;
	}

	public Map<String, String> getSimpleDomainMap() {
		HashMap<String, String> map = new HashMap<>();
		for (DomainMap dMap : this.domainMap) {
			map.put(dMap.getDomain(), dMap.getName());

			// If the domain doesn't include a subdomain then add a www. alternative
			if (dMap.getDomain().chars().filter(c -> c == '.').count() == 1) {
				map.put("www.".concat(dMap.getDomain()), dMap.getName());
			}
		}
		return map;
	}


	public int getGatewayPort() {
		if (this.gatewayPort != null)
			return this.gatewayPort;

		return this.isTestNet ? TESTNET_GATEWAY_PORT : MAINNET_GATEWAY_PORT;
	}

	public boolean isGatewayEnabled() {
		return this.gatewayEnabled;
	}

	public boolean isGatewayLoggingEnabled() {
		return this.gatewayLoggingEnabled;
	}

	public boolean isGatewayLoopbackEnabled() {
		return this.gatewayLoopbackEnabled;
	}


	public int getDevProxyPort() {
		if (this.devProxyPort != null)
			return this.devProxyPort;

		return this.isTestNet ? TESTNET_DEV_PROXY_PORT : MAINNET_DEV_PROXY_PORT;
	}

	public boolean isDevProxyEnabled() {
		return this.devProxyEnabled;
	}

	public boolean isDevProxyLoggingEnabled() {
		return this.devProxyLoggingEnabled;
	}

	public boolean isDevProxyUnsafeEvalEnabled() {
		return this.devProxyUnsafeEvalEnabled;
	}

	public boolean getWipeUnconfirmedOnStart() {
		return this.wipeUnconfirmedOnStart;
	}

	public int getMaxUnconfirmedPerAccount() {
		return this.maxUnconfirmedPerAccount;
	}

	public int getMaxTransactionTimestampFuture() {
		return this.maxTransactionTimestampFuture;
	}

	public int getMaxRecentChatMessagesPerAccount() {
		return this.maxRecentChatMessagesPerAccount;
	}

	public long getRecentChatMessagesMaxAge() {
		return recentChatMessagesMaxAge;
	}

	public long getChatMessageRetentionPeriod() {
		return this.chatMessageRetentionPeriod;
	}

	public int getBlockCacheSize() {
		return this.blockCacheSize;
	}

	public int getMaxTransactionsPerBlock() {
		return this.maxTransactionsPerBlock;
	}

	public boolean isTestNet() {
		return this.isTestNet;
	}

	public boolean isSingleNodeTestnet() {
		return this.singleNodeTestnet;
	}

	public int getListenPort() {
		if (this.listenPort != null)
			return this.listenPort;

		return this.isTestNet ? TESTNET_LISTEN_PORT : MAINNET_LISTEN_PORT;
	}

	public int getQDNListenPort() {
		if (this.listenDataPort != null)
			return this.listenDataPort;
		return this.isTestNet ? TESTNET_QDN_LISTEN_PORT : MAINNET_QDN_LISTEN_PORT;
	}

	public int getDefaultListenPort() {
		return this.isTestNet ? TESTNET_LISTEN_PORT : MAINNET_LISTEN_PORT;
	}

	public int getDefaultQDNListenPort() {
		return this.isTestNet ? TESTNET_QDN_LISTEN_PORT : MAINNET_QDN_LISTEN_PORT;
	}

	public String getBindAddress() {
		return this.bindAddress;
	}

	public String getBindAddressFallback() {
		return this.bindAddressFallback;
	}

	public boolean isUPnPEnabled() {
		return this.uPnPEnabled;
	}

	public int getMinBlockchainPeers() {
		if (singleNodeTestnet)
			return 0;

		return this.minBlockchainPeers;
	}

	public int getMinOutboundPeers() {
		return this.minOutboundPeers;
	}

	public int getMaxPeers() {
		return this.maxPeers;
	}

	public int getMaxDataPeers() {
		return this.maxDataPeers;
	}

	public int getMaxNetworkThreadPoolSize() {
		return this.maxNetworkThreadPoolSize;
	}

	public int getNetworkPoWComputePoolSize() {
		return this.networkPoWComputePoolSize;
	}

	public int getMaxRetries() { return this.maxRetries; }

	public long getRecoveryModeTimeout() {
		return recoveryModeTimeout;
	}

	public String getMinPeerVersion() { return this.minPeerVersion; }

	public boolean getAllowConnectionsWithOlderPeerVersions() { return this.allowConnectionsWithOlderPeerVersions; }

	public int getMinPeerConnectionTime() { return this.minPeerConnectionTime; }

	public int getMaxPeerConnectionTime() { return this.maxPeerConnectionTime; }

	public int getMaxDataPeerConnectionTime() {
		return this.maxDataPeerConnectionTime;
	}

	public boolean isWalletEnabled(String coinKey) {
		ForeignBlockchainRegistry.Entry foreignBlockchain = ForeignBlockchainRegistry.fromString(coinKey);
		if (foreignBlockchain != null)
			coinKey = foreignBlockchain.getCurrencyCode();

		if (this.wallets == null || !this.wallets.containsKey(coinKey))
			return true;

		return this.wallets.get(coinKey);
	}

	public boolean enableWallet(String coinKey) {
		ForeignBlockchainRegistry.Entry foreignBlockchain = ForeignBlockchainRegistry.fromString(coinKey);
		if (foreignBlockchain == null) {
			LOGGER.warn("Unknown coinKey: " + coinKey);
			return false;
		}

		this.wallets.put(foreignBlockchain.getCurrencyCode(), true);	// Next call to wallet.getInstance() will create it if needed
		return true;		
	}

	public boolean disableWallet(String coinKey) {
		ForeignBlockchainRegistry.Entry foreignBlockchain = ForeignBlockchainRegistry.fromString(coinKey);
		if (foreignBlockchain == null) {
			LOGGER.warn("Unknown coinKey: " + coinKey);
			return false;
		}

		if (ForeignBlockchainRegistry.PIRATECHAIN_NAME.equals(foreignBlockchain.name())) {
			PirateChainWalletController pirateWalletController = PirateChainWalletController.getInstance();
			if (pirateWalletController != null)
				pirateWalletController.shutdown();
		}

		this.wallets.put(foreignBlockchain.getCurrencyCode(), false);
		return true;		
	}

	public String getBlockchainConfig() {
		return this.blockchainConfig;
	}

	public BitcoinyNetwork getBitcoinyNetwork(String currencyCode) {
		BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(currencyCode);
		if (spec == null)
			throw new IllegalArgumentException("Unsupported Bitcoiny coin: " + currencyCode);

		BitcoinyNetwork network = spec.getNetwork(getBitcoinyNetworkName(spec.getCurrencyCode()));
		if (network == null)
			throw new IllegalArgumentException(String.format("Unsupported %s Bitcoiny network: %s", spec.getCurrencyCode(), getBitcoinyNetworkName(spec.getCurrencyCode())));

		return network;
	}

	public String getBitcoinyNetworkName(String currencyCode) {
		String normalisedCurrencyCode = currencyCode.toUpperCase(Locale.ROOT);
		if (this.bitcoinyNetworks == null)
			return BitcoinyChainSpecs.MAIN;

		return this.bitcoinyNetworks.getOrDefault(normalisedCurrencyCode, BitcoinyChainSpecs.MAIN);
	}

	public Map<String, Map<String, BitcoinyServerSettings>> getBitcoinyServers() {
		return copyBitcoinyServers(this.bitcoinyServers);
	}

	public BitcoinyServerSettings getBitcoinyServerSettings(String currencyCode, String networkName) {
		if (currencyCode == null || networkName == null || this.bitcoinyServers == null)
			return null;

		Map<String, BitcoinyServerSettings> networkSettings = this.bitcoinyServers.get(currencyCode.trim().toUpperCase(Locale.ROOT));
		if (networkSettings == null)
			return null;

		BitcoinyServerSettings settings = networkSettings.get(networkName.trim().toUpperCase(Locale.ROOT));
		return settings == null ? null : new BitcoinyServerSettings(settings);
	}

	private static Map<String, Map<String, BitcoinyServerSettings>> copyBitcoinyServers(Map<String, Map<String, BitcoinyServerSettings>> bitcoinyServers) {
		Map<String, Map<String, BitcoinyServerSettings>> copy = new LinkedHashMap<>();
		if (bitcoinyServers == null)
			return copy;

		for (Map.Entry<String, Map<String, BitcoinyServerSettings>> coinEntry : bitcoinyServers.entrySet()) {
			Map<String, BitcoinyServerSettings> networkCopy = new LinkedHashMap<>();
			if (coinEntry.getValue() != null) {
				for (Map.Entry<String, BitcoinyServerSettings> networkEntry : coinEntry.getValue().entrySet())
					networkCopy.put(networkEntry.getKey(), new BitcoinyServerSettings(networkEntry.getValue()));
			}

			copy.put(coinEntry.getKey(), networkCopy);
		}

		return copy;
	}

	public PirateChainNet getPirateChainNet() {
		return this.pirateChainNet;
	}

	public int getMaxTradeOfferAttempts() {
		return this.maxTradeOfferAttempts;
	}

	public String getWalletsPath() {
		return this.walletsPath;
	}

	public int getArrrDefaultBirthday() {
		return this.arrrDefaultBirthday;
	}

	public boolean isTradebotSystrayEnabled() {
		return this.tradebotSystrayEnabled;
	}

	public Long getSlowQueryThreshold() {
		return this.slowQueryThreshold;
	}

	public String getRepositoryPath() {
		return this.repositoryPath;
	}

	public int getRepositoryConnectionPoolSize() {
		return this.repositoryConnectionPoolSize;
	}

	public String getExportPath() {
		return this.exportPath;
	}

	public String getBootstrapFilenamePrefix() {
		return this.bootstrapFilenamePrefix;
	}

	public boolean isFastSyncEnabled() {
		return this.fastSyncEnabled;
	}

	public boolean isFastSyncEnabledWhenResolvingFork() {
		return this.fastSyncEnabledWhenResolvingFork;
	}

	public int getMaxBlocksPerRequest() { return this.maxBlocksPerRequest; }

	public int getMaxBlocksPerResponse() { return this.maxBlocksPerResponse; }

	public AutoUpdateMode getAutoUpdateMode() {
		if (this.autoUpdateMode != null)
			return this.autoUpdateMode;

		return AutoUpdateMode.OFF;
	}

	public boolean isAutoRestartEnabled() {
		return this.autoRestartEnabled;
	}

	public String[] getBootstrapHosts() {
		if (this.bootstrapHosts == null || this.bootstrapHosts.length == 0)
			return new String[0];

		List<String> configuredHosts = new ArrayList<>(this.bootstrapHosts.length);
		for (String bootstrapHost : this.bootstrapHosts) {
			if (bootstrapHost == null)
				continue;

			String trimmedHost = bootstrapHost.trim();
			if (!trimmedHost.isEmpty())
				configuredHosts.add(trimmedHost);
		}

		return configuredHosts.toArray(new String[0]);
	}

	public boolean hasBootstrapHostsConfigured() {
		return this.getBootstrapHosts().length > 0;
	}

	public String getListsPath() {
		return this.listsPath;
	}

	public String[] getNtpServers() {
		return this.ntpServers;
	}

	public Long getTestNtpOffset() {
		return this.testNtpOffset;
	}

	public long getRepositoryBackupInterval() {
		return this.repositoryBackupInterval;
	}

	public boolean getShowBackupNotification() {
		return this.showBackupNotification;
	}

	public long getRepositoryMaintenanceMinInterval() {
		return this.repositoryMaintenanceMinInterval;
	}

	public long getRepositoryMaintenanceMaxInterval() {
		return this.repositoryMaintenanceMaxInterval;
	}

	public boolean getShowMaintenanceNotification() {
		return this.showMaintenanceNotification;
	}

	public long getRepositoryCheckpointInterval() {
		return this.repositoryCheckpointInterval;
	}

	public boolean getShowCheckpointNotification() {
		return this.showCheckpointNotification;
	}

	public String[] getInitialPeers() {
		if (this.initialPeers == null || this.initialPeers.length == 0)
			return new String[0];

		Set<String> configuredPeers = new LinkedHashSet<>(this.initialPeers.length);
		for (String initialPeer : this.initialPeers) {
			if (initialPeer == null)
				continue;

			String trimmedPeer = initialPeer.trim();
			if (!trimmedPeer.isEmpty())
				configuredPeers.add(trimmedPeer);
		}

		return configuredPeers.toArray(new String[0]);
	}

	public boolean hasInitialPeersConfigured() {
		return this.getInitialPeers().length > 0;
	}

	public List<String> getFixedNetwork() {
		return fixedNetwork;
	}

	public long getAtStatesMaxLifetime() {
		return this.atStatesMaxLifetime;
	}

	public long getAtStatesTrimInterval() {
		return this.atStatesTrimInterval;
	}

	public int getAtStatesTrimBatchSize() {
		return this.atStatesTrimBatchSize;
	}

	public int getAtStatesTrimLimit() {
		return this.atStatesTrimLimit;
	}

	public long getOnlineSignaturesTrimInterval() {
		return this.onlineSignaturesTrimInterval;
	}

	public int getOnlineSignaturesTrimBatchSize() {
		return this.onlineSignaturesTrimBatchSize;
	}

	public boolean isLite() {
		return this.lite;
	}

	public boolean isTopOnly() {
		return this.topOnly;
	}

	public int getPruneBlockLimit() {
		// Never prune more than twice the block reward batch size, as the data is needed when processing/orphaning
		int minPruneBlockLimit = BlockChain.getInstance().getBlockRewardBatchSize() * 2;
		return Math.max(this.pruneBlockLimit, minPruneBlockLimit);
	}

	public long getAtStatesPruneInterval() {
		return this.atStatesPruneInterval;
	}

	public int getAtStatesPruneBatchSize() {
		return this.atStatesPruneBatchSize;
	}

	public long getBlockPruneInterval() {
		return this.blockPruneInterval;
	}

	public int getBlockPruneBatchSize() {
		return this.blockPruneBatchSize;
	}

	public boolean isNamesIntegrityCheckEnabled() {
		return this.namesIntegrityCheckEnabled;
	}


	public boolean isArchiveEnabled() {
		if (this.topOnly) {
			return false;
		}
		return this.archiveEnabled;
	}

	public long getArchiveInterval() {
		return this.archiveInterval;
	}

	public int getDefaultArchiveVersion() {
		return this.defaultArchiveVersion;
	}


	public boolean getBootstrap() {
		return this.bootstrap;
	}


	public int getGapLimit() {
		return this.gapLimit;
	}

	public int getBitcoinjLookaheadSize() {
		return bitcoinjLookaheadSize;
	}

	public int getBlockchainCacheLimit() {
		return blockchainCacheLimit;
	}

	public boolean isQdnEnabled() {
		return this.qdnEnabled;
	}

	public String getDataPath() {
		return this.dataPath;
	}

	public String getTempDataPath() {
		if (this.tempDataPath != null) {
			return this.tempDataPath;
		}
		// Default the temp path to a "_temp" folder inside the data directory
		return Paths.get(this.getDataPath(), "_temp").toString();
	}

	public StoragePolicy getStoragePolicy() {
		return StoragePolicy.valueOf(this.storagePolicy);
	}

	public boolean isRelayModeEnabled() {
		return this.relayModeEnabled;
	}

	public boolean isDirectDataRetrievalEnabled() {
		return this.directDataRetrievalEnabled;
	}

	public boolean isOriginalCopyIndicatorFileEnabled() {
		return this.originalCopyIndicatorFileEnabled;
	}

	public Long getBuiltDataExpiryInterval() {
		return this.builtDataExpiryInterval;
	}

	public boolean shouldValidateAllDataLayers() {
		return this.validateAllDataLayers;
	}

	public boolean isPublicDataEnabled() {
		return this.publicDataEnabled;
	}

	public boolean isPrivateDataEnabled() {
		return this.privateDataEnabled;
	}

	public Long getMaxStorageCapacity() {
		return this.maxStorageCapacity;
	}

	public boolean isQDNAuthBypassEnabled() {
		if (this.gatewayEnabled) {
			// We must always bypass QDN authentication in gateway mode, in order for it to function properly
			return true;
		}
		return this.qdnAuthBypassEnabled;
	}

	public Integer getMaxThreadsForMessageType(MessageType messageType) {
		if (maxThreadsPerMessageType != null) {
			for (ThreadLimit threadLimit : maxThreadsPerMessageType) {
				if (threadLimit.getMessageType().equals(messageType.name())) {
					return threadLimit.getLimit();
				}
			}
		}
		// No entry, so assume unlimited
		return null;
	}

	public Integer getThreadCountPerMessageTypeWarningThreshold() {
		return this.threadCountPerMessageTypeWarningThreshold;
	}

	public boolean isDbCacheEnabled() {
		return dbCacheEnabled;
	}

	public int getDbCacheThreadPriority() {
		return dbCacheThreadPriority;
	}

	public int getDbCacheFrequency() {
		return dbCacheFrequency;
	}

	public int getNetworkThreadPriority() {
		return networkThreadPriority;
	}

	public int getHandshakeThreadPriority() {
		return handshakeThreadPriority;
	}

	public int getPruningThreadPriority() {
		return pruningThreadPriority;
	}

	public int getSynchronizerThreadPriority() {
		return synchronizerThreadPriority;
	}

	public long getArchivingPause() {
		return archivingPause;
	}

	public int getBalanceRecorderPriority() {
		return balanceRecorderPriority;
	}

	public int getBalanceRecorderFrequency() {
		return balanceRecorderFrequency;
	}

	public int getBalanceRecorderCapacity() {
		return balanceRecorderCapacity;
	}

	public boolean isBalanceRecorderEnabled() {
		return balanceRecorderEnabled;
	}

	public long getMinimumBalanceRecording() {
		return minimumBalanceRecording;
	}

	public long getTopBalanceLoggingLimit() {
		return topBalanceLoggingLimit;
	}

	public int getBalanceRecorderRollbackAllowance() {
		return balanceRecorderRollbackAllowance;
	}

	public boolean isRewardRecordingOnly() {
		return rewardRecordingOnly;
	}

	public boolean isConnectionPoolMonitorEnabled() {
		return connectionPoolMonitorEnabled;
	}

	public int getBuildArbitraryResourcesBatchSize() {
		return buildArbitraryResourcesBatchSize;
	}

	public int getArbitraryIndexingPriority() {
		return arbitraryIndexingPriority;
	}

	public int getArbitraryIndexingFrequency() {
		return arbitraryIndexingFrequency;
	}

	public boolean isRebuildArbitraryResourceCacheTaskEnabled() {
		return rebuildArbitraryResourceCacheTaskEnabled;
	}

	public int getRebuildArbitraryResourceCacheTaskDelay() {
		return rebuildArbitraryResourceCacheTaskDelay;
	}

	public int getRebuildArbitraryResourceCacheTaskPeriod() {
		return rebuildArbitraryResourceCacheTaskPeriod;
	}

	public int getElectrumThreadCount() {
		return electrumThreadCount;
	}

	public boolean isPlaintextElectrumServersAllowed() {
		return this.allowPlaintextElectrumServers;
	}

	public boolean isHostMonitorEnabled() {
		return hostMonitorEnabled;
	}
}
