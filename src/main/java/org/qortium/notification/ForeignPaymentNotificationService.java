package org.qortium.notification;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee8.websocket.api.Session;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyDeterministicKey;
import org.qortium.crosschain.BitcoinyDeterministicKeyChain;
import org.qortium.crosschain.BitcoinyNetwork;
import org.qortium.crosschain.BitcoinyScript;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ElectrumMethods;
import org.qortium.crosschain.ElectrumProtocolVersion;
import org.qortium.crosschain.ElectrumRequest;
import org.qortium.crosschain.ElectrumServerList;
import org.qortium.crosschain.ElectrumX;
import org.qortium.crosschain.ElectrumXPushClient;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Session-scoped foreign-payment watches fed by independent ElectrumX push connections. */
final class ForeignPaymentNotificationService {

	static final String EVENT = "FOREIGN_PAYMENT_RECEIVED";
	static final Set<String> FILTER_KEYS = Set.of("coin", "xpub");

	private static final Logger LOGGER = LogManager.getLogger(ForeignPaymentNotificationService.class);
	private static final String HEADERS_SUBSCRIBE = "blockchain.headers.subscribe";
	private static final String CLIENT_NAME = "Qortium-Notifications";
	// The negotiated range is shared with ElectrumX so the push connections and the pooled connections
	// cannot drift apart, and the subscription methods are picked per connection from what it negotiated:
	// protocol 1.7 serves blockchain.scriptpubkey.* in place of blockchain.scripthash.*.
	private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
			ElectrumX.MIN_PROTOCOL_VERSION.toString(), ElectrumX.MAX_PROTOCOL_VERSION.toString());
	private static final int INITIAL_LOOKAHEAD_INCREMENT = 3;
	private static final int WORK_QUEUE_CAPACITY = 256;
	private static final int MAX_INPUTS_PER_TRANSACTION = 500;
	private static final int TRANSACTION_CACHE_SIZE = 1_000;
	private static final long OVERLOAD_WARNING_INTERVAL_MS = 60_000L;
	static final int MAX_XPUB_LENGTH = 128;
	static final int MAX_RULES_PER_SESSION = 20;
	static final int MAX_ACTIVE_WATCH_RULES = 64;
	static final int MAX_DERIVED_ADDRESSES_PER_RULE = 1_024;
	static final int MAX_HISTORY_ENTRIES_PER_SCRIPTHASH = 1_000;
	static final int MAX_SEEN_TRANSACTION_HASHES_PER_RULE = 4_096;
	static final int MAX_RAW_TRANSACTION_BYTES = 4 * 1024 * 1024;

	private static final ForeignPaymentNotificationService INSTANCE = new ForeignPaymentNotificationService();

	private final Map<Session, IdentityHashMap<NotificationSubscription, Registration>> registrationsBySession =
			new IdentityHashMap<>();
	private final Map<String, CoinWatcher> watchersByCoin = new LinkedHashMap<>();

	private ForeignPaymentNotificationService() {
	}

	static ForeignPaymentNotificationService getInstance() {
		return INSTANCE;
	}

	static String validateSubscription(NotificationSubscription rule) {
		Map<String, Object> filters = rule.getFilters();
		if (filters == null)
			return EVENT + " subscriptions require coin and xpub filters";

		Object coinValue = filters.get("coin");
		Object xpubValue = filters.get("xpub");
		if (coinValue == null)
			return EVENT + " subscriptions require a non-blank coin string";
		if (!(coinValue instanceof String))
			return EVENT + " coin filter must be a string";
		if (((String) coinValue).trim().isEmpty())
			return EVENT + " subscriptions require a non-blank coin string";
		if (xpubValue == null)
			return EVENT + " subscriptions require a non-blank xpub string";
		if (!(xpubValue instanceof String))
			return EVENT + " xpub filter must be a string";
		String rawXpub = (String) xpubValue;
		if (rawXpub.length() > MAX_XPUB_LENGTH)
			return EVENT + " xpub filter exceeds the " + MAX_XPUB_LENGTH + " character limit";
		String xpub = rawXpub.trim();
		if (xpub.isEmpty())
			return EVENT + " subscriptions require a non-blank xpub string";

		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromRegisteredBitcoinyString((String) coinValue);
		if (entry == null)
			return "Unsupported ElectrumX coin for " + EVENT + ": " + coinValue;

		try {
			BitcoinyNetwork network = Settings.getInstance().getBitcoinyNetwork(entry.getCurrencyCode());
			BitcoinyDeterministicKey key = BitcoinyDeterministicKey.fromBase58(
					network.getParams(), xpub);
			if (key.hasPrivateKey())
				return EVENT + " requires a public extended key (xpub), not a private extended key";
		} catch (RuntimeException e) {
			return "Invalid xpub for " + entry.getCurrencyCode();
		}

		return null;
	}

	synchronized String validateSessionUpdate(Session session, List<NotificationSubscription> subscriptions) {
		int sessionRuleCount = activeRuleCount(subscriptions);
		IdentityHashMap<NotificationSubscription, Registration> existing = this.registrationsBySession.get(session);
		int existingSessionRuleCount = existing == null ? 0 : existing.size();
		int resultingGlobalRuleCount = activeRuleCount() - existingSessionRuleCount + sessionRuleCount;
		return validateRuleCounts(sessionRuleCount, resultingGlobalRuleCount);
	}

	synchronized String updateSession(Session session, List<NotificationSubscription> subscriptions) {
		String validationError = validateSessionUpdate(session, subscriptions);
		if (validationError != null)
			return validationError;

		IdentityHashMap<NotificationSubscription, Registration> previous = this.registrationsBySession.get(session);
		if (previous == null)
			previous = new IdentityHashMap<>();

		IdentityHashMap<NotificationSubscription, Registration> updated = new IdentityHashMap<>();
		if (subscriptions != null) {
			for (NotificationSubscription subscription : subscriptions) {
				if (!EVENT.equals(subscription.getEvent()) || subscription.isExpired())
					continue;

				Registration registration = previous.remove(subscription);
				if (registration == null)
					registration = addRegistration(session, subscription);
				if (registration != null)
					updated.put(subscription, registration);
			}
		}

		for (Registration registration : previous.values())
			registration.watcher.remove(registration.rule);

		if (updated.isEmpty())
			this.registrationsBySession.remove(session);
		else
			this.registrationsBySession.put(session, updated);

		removeEmptyWatchers();
		return null;
	}

	private static int activeRuleCount(List<NotificationSubscription> subscriptions) {
		if (subscriptions == null)
			return 0;

		int count = 0;
		for (NotificationSubscription subscription : subscriptions)
			if (EVENT.equals(subscription.getEvent()) && !subscription.isExpired())
				count++;
		return count;
	}

	private int activeRuleCount() {
		int count = 0;
		for (IdentityHashMap<NotificationSubscription, Registration> registrations : this.registrationsBySession.values())
			count += registrations.size();
		return count;
	}

	static String validateRuleCounts(int sessionRuleCount, int globalRuleCount) {
		if (sessionRuleCount > MAX_RULES_PER_SESSION)
			return EVENT + " allows at most " + MAX_RULES_PER_SESSION + " active rules per websocket session";
		if (globalRuleCount > MAX_ACTIVE_WATCH_RULES)
			return "Core allows at most " + MAX_ACTIVE_WATCH_RULES + " active " + EVENT
					+ " watch rules across websocket sessions";
		return null;
	}

	synchronized void removeSession(Session session) {
		IdentityHashMap<NotificationSubscription, Registration> registrations =
				this.registrationsBySession.remove(session);
		if (registrations != null)
			for (Registration registration : registrations.values())
				registration.watcher.remove(registration.rule);

		removeEmptyWatchers();
	}

	synchronized void removeExpiredSubscriptions() {
		Iterator<Map.Entry<Session, IdentityHashMap<NotificationSubscription, Registration>>> sessions =
				this.registrationsBySession.entrySet().iterator();
		while (sessions.hasNext()) {
			IdentityHashMap<NotificationSubscription, Registration> registrations = sessions.next().getValue();
			Iterator<Map.Entry<NotificationSubscription, Registration>> iterator = registrations.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<NotificationSubscription, Registration> entry = iterator.next();
				if (!entry.getKey().isExpired())
					continue;

				entry.getValue().watcher.remove(entry.getValue().rule);
				iterator.remove();
			}
			if (registrations.isEmpty())
				sessions.remove();
		}

		removeEmptyWatchers();
	}

	private Registration addRegistration(Session session, NotificationSubscription subscription) {
		try {
			String coin = coinCode(subscription);
			ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromRegisteredBitcoinyString(coin);
			if (entry == null)
				return null;

			CoinWatcher watcher = this.watchersByCoin.computeIfAbsent(
					entry.getCurrencyCode(), ignored -> new CoinWatcher(entry));
			WatchRule rule = watcher.add(session, subscription);
			return new Registration(watcher, rule);
		} catch (Exception e) {
			LOGGER.debug("Unable to start foreign-payment notification watch: {}", e.getMessage());
			return null;
		}
	}

	private void removeEmptyWatchers() {
		this.watchersByCoin.entrySet().removeIf(entry -> {
			CoinWatcher watcher = entry.getValue();
			if (!watcher.isEmpty())
				return false;

			watcher.close();
			return true;
		});
	}

	private static String coinCode(NotificationSubscription subscription) {
		Object coin = subscription.getFilters().get("coin");
		return coin instanceof String ? ((String) coin).trim().toUpperCase(Locale.ROOT) : "";
	}

	/**
	 * Reject a <code>server.version</code> reply that did not settle on a protocol this service can speak.
	 * Versions are compared component by component through {@link ElectrumProtocolVersion}, the same way the
	 * pooled ElectrumX connections compare them, so a 1.4.x server is accepted by both and 1.10 by neither.
	 */
	static void validateNegotiatedVersion(Object response) throws IOException {
		parseNegotiatedVersion(response);
	}

	/**
	 * Validate a <code>server.version</code> reply and return what the server settled on, so the caller can
	 * pick the matching method family for the life of that connection.
	 */
	static ElectrumProtocolVersion parseNegotiatedVersion(Object response) throws IOException {
		if (!(response instanceof List<?>))
			throw new IOException("ElectrumX server did not negotiate a protocol version");

		List<?> values = (List<?>) response;
		if (values.size() < 2 || !(values.get(0) instanceof String) || !(values.get(1) instanceof String))
			throw new IOException("ElectrumX server returned an invalid version response");

		ElectrumProtocolVersion negotiated = ElectrumProtocolVersion.parse((String) values.get(1))
				.orElseThrow(() -> new IOException("ElectrumX server returned an invalid protocol version"));

		if (!negotiated.isWithin(ElectrumX.MIN_PROTOCOL_VERSION, ElectrumX.MAX_PROTOCOL_VERSION))
			throw new IOException("ElectrumX server negotiated an unsupported protocol version");

		return negotiated;
	}

	/**
	 * Which protocol family the push connection negotiated, and which connection it negotiated it on.
	 * <p>
	 * The push client reconnects on its own and the watcher's work is queued, so a task created against a
	 * 1.4 server can run after the client has moved to a 1.7 server. Pairing the version with the epoch it
	 * was negotiated on makes that detectable: work whose epoch no longer matches is refused rather than
	 * being sent with the wrong method family, and no protocol-dependent request can be sent at all before
	 * negotiation has completed.
	 */
	static final class NegotiatedFamily {
		private volatile ElectrumProtocolVersion version;
		private volatile long epoch = -1L;

		void negotiated(long epoch, ElectrumProtocolVersion version) {
			this.version = version;
			this.epoch = epoch;
		}

		/** Called when the connection drops, so nothing can keep using the family it had negotiated. */
		void cleared() {
			this.version = null;
			this.epoch = -1L;
		}

		ElectrumProtocolVersion getVersion() {
			return this.version;
		}

		/**
		 * @param epoch the connection epoch the caller's work was created for
		 * @param currentEpoch the connection epoch the client is on now
		 * @return the method family to use
		 * @throws IOException when negotiation has not completed, or the connection has changed underneath
		 */
		ElectrumMethods require(long epoch, long currentEpoch) throws IOException {
			ElectrumProtocolVersion negotiatedVersion = this.version;
			if (negotiatedVersion == null || this.epoch != epoch)
				throw new IOException("ElectrumX push connection has not negotiated a protocol version");

			if (epoch != currentEpoch)
				throw new IOException("ElectrumX push connection changed before the request was sent");

			return ElectrumMethods.forVersion(negotiatedVersion);
		}
	}

	/** Protocol 1.7's standard code for a history the server refuses to serve because it is too large. */
	static final int HISTORY_TOO_LARGE_ERROR_CODE = 10001;

	/**
	 * @return true when this failure is one busy address rather than a broken connection. Reconnecting
	 * cannot fix it, and doing so would cycle the whole per-coin watcher over one address.
	 */
	static boolean isHistoryTooLarge(Throwable failure) {
		if (failure instanceof ElectrumXPushClient.RpcException)
			return ((ElectrumXPushClient.RpcException) failure).getCode() == HISTORY_TOO_LARGE_ERROR_CODE;

		String message = failure == null ? null : failure.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("history too large");
	}

	private static final class Registration {
		private final CoinWatcher watcher;
		private final WatchRule rule;

		private Registration(CoinWatcher watcher, WatchRule rule) {
			this.watcher = watcher;
			this.rule = rule;
		}
	}

	private static final class CoinWatcher implements AutoCloseable {
		private final String coin;
		private final BitcoinyNetwork network;
		private final Bitcoiny bitcoiny;
		private final int coinDecimalPlaces;
		private final ThreadPoolExecutor worker;
		private final ElectrumXPushClient client;
		private final Map<String, AddressWatch> watchesByScripthash = new LinkedHashMap<>();
		/** Protocol family negotiated on the current push connection, tied to that connection's epoch. */
		private final NegotiatedFamily negotiatedFamily = new NegotiatedFamily();
		private final Map<String, BitcoinyTransaction> transactionCache =
				new LinkedHashMap<String, BitcoinyTransaction>(TRANSACTION_CACHE_SIZE + 1, 0.75F, true) {
					@Override
					protected boolean removeEldestEntry(Map.Entry<String, BitcoinyTransaction> eldest) {
						return size() > TRANSACTION_CACHE_SIZE;
					}
				};
		private final Set<WatchRule> rules = Collections.newSetFromMap(new ConcurrentHashMap<>());
		private final AtomicLong lastOverloadWarning = new AtomicLong();
		private volatile boolean closed;
		private int currentHeight;
		private long connectionGeneration;

		private CoinWatcher(ForeignBlockchainRegistry.Entry entry) {
			this.coin = entry.getCurrencyCode();
			this.network = Settings.getInstance().getBitcoinyNetwork(this.coin);
			this.bitcoiny = Objects.requireNonNull(entry.getBitcoinyNotificationInstance());
			this.coinDecimalPlaces = entry.getBitcoinySpec().getConfig().getDecimalPlaces();
			this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY), new ForeignPaymentThreadFactory(this.coin));
			this.client = new ElectrumXPushClient(this.coin, this::configuredServers,
					new ElectrumXPushClient.Listener() {
						@Override
						public void onConnected() {
							long epoch = CoinWatcher.this.client.currentEpoch();
							execute(() -> resubscribeAll(epoch));
						}

						@Override
						public void onNotification(String method, List<?> params) {
							long epoch = CoinWatcher.this.client.currentEpoch();
							execute(() -> handlePushNotification(epoch, method, params));
						}

						@Override
						public void onDisconnected() {
							// Nothing may keep using the family this connection negotiated.
							CoinWatcher.this.negotiatedFamily.cleared();
						}
					});
			this.client.start();
		}

		private Collection<ElectrumX.Server> configuredServers() {
			return ElectrumServerList.getServers(this.coin, this.network.name(), this.network.getServers());
		}

		private WatchRule add(Session session, NotificationSubscription subscription) {
			String xpub = ((String) subscription.getFilters().get("xpub")).trim();
			if (xpub.length() > MAX_XPUB_LENGTH)
				throw new IllegalArgumentException("Foreign-payment xpub exceeds the configured length limit");
			WatchRule rule = new WatchRule(session, subscription,
					BitcoinyDeterministicKeyChain.fromBase58(this.network.getParams(), xpub));
			this.rules.add(rule);
			execute(() -> initializeRule(rule));
			return rule;
		}

		private void remove(WatchRule rule) {
			rule.active = false;
			this.rules.remove(rule);
			execute(() -> detachRule(rule));
		}

		private boolean isEmpty() {
			return this.rules.isEmpty();
		}

		private void initializeRule(WatchRule rule) {
			if (this.closed || !rule.active)
				return;

			addBatch(rule, rule.keyChain.getInitialLeafKeys(INITIAL_LOOKAHEAD_INCREMENT));
			// Only subscribe once this connection has negotiated: before that there is no method family to
			// choose, and the rule is picked up by the next resubscribeAll anyway.
			if (this.client.isConnected() && this.negotiatedFamily.getVersion() != null)
				subscribeAndAdvance(this.client.currentEpoch(), rule);
		}

		private void addBatch(WatchRule rule, List<BitcoinyDeterministicKey> keys) {
			if (keys.size() > MAX_DERIVED_ADDRESSES_PER_RULE - rule.processedLeafKeyCount)
				throw new IllegalStateException("Foreign-payment derivation batch exceeds the per-rule ceiling");

			List<AddressWatch> batch = new ArrayList<>(keys.size());
			for (BitcoinyDeterministicKey key : keys) {
				String address = this.bitcoiny.pkhToAddress(key.getPublicKeyHash());
				String scriptPubKey = HashCode.fromBytes(BitcoinyScript.p2pkhScript(key.getPublicKeyHash())).toString();
				byte[] script = BitcoinyScript.p2pkhScript(key.getPublicKeyHash());
				String scripthash = ElectrumMethods.subscriptionKey(script);
				AddressWatch watch = this.watchesByScripthash.computeIfAbsent(
						scripthash, ignored -> new AddressWatch(scripthash, script));
				watch.rules.add(rule);
				rule.watches.add(watch);
				rule.addresses.add(address);
				rule.addressByScriptPubKey.put(scriptPubKey, address);
				batch.add(watch);
			}

			rule.processedLeafKeyCount += keys.size();
			rule.batches.add(batch);
		}


		private boolean subscribeAndAdvance(long epoch, WatchRule rule) {
			if (rule.historyTooLarge)
				// Already retired: retrying only earns the same refusal.
				return true;

			try {
				for (List<AddressWatch> batch : rule.batches)
					for (AddressWatch watch : batch)
						ensureSubscribed(epoch, watch);

				advanceDiscovery(epoch, rule);
				return true;
			} catch (Exception e) {
				// One address the server refuses to serve is not a reason to cycle the whole coin's
				// connection; retire that watch and carry on.
				if (isHistoryTooLarge(e)) {
					retireOversizedWatches(rule);
					return true;
				}

				LOGGER.debug("{} foreign-payment watch initialization failed: {}", this.coin, e.getMessage());
				this.client.reconnect();
				return false;
			}
		}

		/** Mark the rule's watches that the server refuses to serve, logging once per rule. */
		private void retireOversizedWatches(WatchRule rule) {
			if (rule.historyTooLarge)
				return;

			rule.historyTooLarge = true;
			LOGGER.info("{} foreign-payment watch retired: ElectrumX refuses to serve this address's history"
					+ " (error {}); no further notifications will be sent for it", this.coin, HISTORY_TOO_LARGE_ERROR_CODE);
		}

		private void advanceDiscovery(long epoch, WatchRule rule) throws IOException {
			while (rule.active && rule.processedBatchCount < rule.batches.size()) {
				List<AddressWatch> batch = rule.batches.get(rule.processedBatchCount);
				if (batch.stream().anyMatch(watch -> !watch.initialized))
					return;

				rule.processedBatchCount++;
				if (batch.stream().anyMatch(watch -> watch.checkpoint != null))
					rule.unusedCounter = 0;
				else {
					rule.unusedCounter += INITIAL_LOOKAHEAD_INCREMENT;
					if (rule.unusedCounter >= Settings.getInstance().getGapLimit()) {
						rule.terminalBatch = batch;
						return;
					}
				}

				if (!addNextBatch(rule))
					return;
				for (AddressWatch watch : rule.batches.get(rule.batches.size() - 1))
					ensureSubscribed(epoch, watch);
			}
		}

		private boolean addNextBatch(WatchRule rule) {
			int pairCount = nextDerivationPairCount(rule.processedLeafKeyCount);
			if (pairCount <= 0) {
				if (!rule.derivationLimitLogged) {
					rule.derivationLimitLogged = true;
					LOGGER.info("{} foreign-payment watch reached the per-rule ceiling of {} derived addresses; "
							+ "continuing with the existing watched set", this.coin, MAX_DERIVED_ADDRESSES_PER_RULE);
				}
				return false;
			}

			addBatch(rule, rule.keyChain.getMoreLeafKeys(rule.processedLeafKeyCount, pairCount));
			return true;
		}

		private void ensureSubscribed(long epoch, AddressWatch watch) throws IOException {
			if (watch.subscribedGeneration == this.connectionGeneration)
				return;

			ElectrumRequest subscribeRequest = methods(epoch).subscribe(watch.script);
			Object result = this.client.request(epoch, subscribeRequest.getMethod(), subscribeRequest.getParams());
			watch.subscribedGeneration = this.connectionGeneration;
			String checkpoint = parseCheckpoint(result);
			if (!watch.initialized) {
				watch.initialized = true;
				watch.checkpoint = checkpoint;
				baseline(epoch, watch);
				return;
			}

			if (!Objects.equals(watch.checkpoint, checkpoint) || !watch.baselineComplete || watch.retryPending)
				processStatusChange(epoch, watch, checkpoint);
		}

		private static String parseCheckpoint(Object value) throws IOException {
			if (value == null)
				return null;
			if (value instanceof String && isHexHash((String) value))
				return (String) value;
			throw new IOException("Unexpected ElectrumX scripthash status response");
		}

		private void baseline(long epoch, AddressWatch watch) throws IOException {
			if (watch.checkpoint != null)
				watch.history.baseline(fetchHistory(epoch, watch));
			watch.baselineComplete = true;
		}

		private void resubscribeAll(long epoch) {
			if (this.closed)
				return;

			try {
				this.connectionGeneration++;
				// Every call below is bound to this epoch, so a resubscribe left over from a previous
				// connection cannot write anything to the current one.
				this.negotiatedFamily.negotiated(epoch, parseNegotiatedVersion(this.client.request(
						epoch, "server.version", CLIENT_NAME, SUPPORTED_PROTOCOL_VERSIONS)));
				Object features = this.client.request(epoch, "server.features");
				if (!(features instanceof Map<?, ?>))
					throw new IOException("ElectrumX server did not return features");
				Object genesisHash = ((Map<?, ?>) features).get("genesis_hash");
				if (!Objects.equals(this.network.getGenesisHash(), genesisHash))
					throw new IOException("ElectrumX server reported the wrong genesis hash");
				updateHeight(this.client.request(epoch, HEADERS_SUBSCRIBE));
				boolean subscriptionsReady = true;
				for (WatchRule rule : new ArrayList<>(this.rules))
					subscriptionsReady &= subscribeAndAdvance(epoch, rule);
				if (subscriptionsReady)
					this.client.markReady();
			} catch (Exception e) {
				LOGGER.debug("{} foreign-payment resubscribe failed: {}", this.coin, e.getMessage());
				this.client.reconnect();
			}
		}

		private void handlePushNotification(long epoch, String method, List<?> params) {
			try {
				if (HEADERS_SUBSCRIBE.equals(method)) {
					if (!params.isEmpty())
						updateHeight(params.get(0));
					return;
				}

				// 1.7 renamed the notification to blockchain.scriptpubkey.subscribe but kept its payload: the
				// first parameter is still the scripthash, so watches stay keyed by scripthash either way.
				if (!ElectrumMethods.isSubscriptionNotification(method) || params.size() < 2 || !(params.get(0) instanceof String))
					return;

				AddressWatch watch = this.watchesByScripthash.get(params.get(0));
				if (watch == null)
					return;

				try {
					processStatusChange(epoch, watch, parseCheckpoint(params.get(1)));
				} catch (Exception e) {
					// A history the server refuses to serve is a property of that address, not of the
					// connection: reconnecting would cycle the whole coin's watcher over one busy address.
					if (!isHistoryTooLarge(e))
						throw e;

					for (WatchRule rule : new ArrayList<>(watch.rules))
						retireOversizedWatches(rule);
				}
			} catch (Exception e) {
				LOGGER.debug("{} foreign-payment push handling failed: {}", this.coin, e.getMessage());
				this.client.reconnect();
			}
		}

		private void updateHeight(Object headerValue) {
			if (!(headerValue instanceof Map<?, ?>))
				return;
			Object height = ((Map<?, ?>) headerValue).get("height");
			if (height instanceof Number)
				this.currentHeight = ((Number) height).intValue();
		}

		private void processStatusChange(long epoch, AddressWatch watch, String checkpoint) throws IOException {
			if (!watch.baselineComplete) {
				watch.checkpoint = checkpoint;
				baseline(epoch, watch);
				return;
			}

			if (Objects.equals(watch.checkpoint, checkpoint) && !watch.retryPending)
				return;

			String previousCheckpoint = watch.checkpoint;
			List<ForeignPaymentHistoryDelta.Entry> added = watch.history.candidates(fetchHistory(epoch, watch));
			watch.checkpoint = checkpoint;
			watch.retryPending = false;
			if (checkpoint != null) {
				for (ForeignPaymentHistoryDelta.Entry entry : added) {
					if (handleNewTransaction(watch, entry, checkpoint))
						watch.history.markSeen(entry.txHash);
					else
						watch.retryPending = true;
				}
			}

			if (watch.retryPending)
				this.client.reconnect();

			if (previousCheckpoint == null && checkpoint != null)
				for (WatchRule rule : new ArrayList<>(watch.rules))
					if (rule.terminalBatch != null && rule.terminalBatch.contains(watch)) {
						rule.terminalBatch = null;
						rule.unusedCounter = 0;
						if (addNextBatch(rule))
							subscribeAndAdvance(epoch, rule);
					}
		}

		/** @throws IOException when this epoch's connection has not negotiated, or is no longer current */
		private ElectrumMethods methods(long epoch) throws IOException {
			return this.negotiatedFamily.require(epoch, this.client.currentEpoch());
		}

		private List<ForeignPaymentHistoryDelta.Entry> fetchHistory(long epoch, AddressWatch watch) throws IOException {
			ElectrumRequest historyRequest = methods(epoch).getHistory(watch.script);
			return parseHistory(this.client.request(epoch, historyRequest.getMethod(), historyRequest.getParams()));
		}

		private boolean handleNewTransaction(AddressWatch watch, ForeignPaymentHistoryDelta.Entry historyEntry,
				String checkpoint) {
			BitcoinyTransaction transaction;
			try {
				transaction = fetchTransaction(historyEntry.txHash);
			} catch (Exception e) {
				LOGGER.debug("{} unable to fetch new foreign transaction {}: {}",
						this.coin, historyEntry.txHash, e.getMessage());
				return false;
			}

			boolean allClassified = true;
			for (WatchRule rule : new ArrayList<>(watch.rules)) {
				if (!rule.active || rule.subscription.isExpired()
						|| rule.seenTransactionHashes.contains(historyEntry.txHash))
					continue;

				try {
					ForeignIncomingPaymentDetector.Result incomingPayment =
							inspectIncomingPayment(transaction, rule);
					rememberTransactionHash(rule.seenTransactionHashes, historyEntry.txHash,
							MAX_SEEN_TRANSACTION_HASHES_PER_RULE);
					if (incomingPayment == null)
						continue;

					Map<String, Object> data = new LinkedHashMap<>();
					data.put("coin", this.coin);
					data.put("txHash", historyEntry.txHash);
					data.put("address", incomingPayment.address);
					data.put("amount", formatAmount(incomingPayment.amount));
					data.put("direction", "incoming");
					data.put("confirmations", confirmations(historyEntry.height));
					data.put("checkpoint", checkpoint);

					NotificationManager.getInstance().dispatchTargetedEventAsync(
							rule.session, rule.subscription, new NotificationEvent(EVENT, data));
				} catch (Exception e) {
					allClassified = false;
					LOGGER.debug("{} unable to inspect new foreign transaction {}: {}",
							this.coin, historyEntry.txHash, e.getMessage());
				}
			}
			return allClassified;
		}

		private String formatAmount(long amount) {
			return BigDecimal.valueOf(amount, this.coinDecimalPlaces).setScale(8).toPlainString();
		}

		private ForeignIncomingPaymentDetector.Result inspectIncomingPayment(
				BitcoinyTransaction transaction, WatchRule rule) throws Exception {
			return ForeignIncomingPaymentDetector.detect(transaction, rule.addressByScriptPubKey,
					this::fetchTransaction, MAX_INPUTS_PER_TRANSACTION);
		}

		private BitcoinyTransaction fetchTransaction(String txHash) throws Exception {
			BitcoinyTransaction cached = this.transactionCache.get(txHash);
			if (cached != null)
				return cached;

			Object result = this.client.request("blockchain.transaction.get", txHash, false);
			BitcoinyTransaction transaction = this.bitcoiny.deserializeRawTransaction(
					txHash, decodeRawTransaction(result));
			this.transactionCache.put(txHash, transaction);
			return transaction;
		}

		private int confirmations(int transactionHeight) {
			if (transactionHeight <= 0 || this.currentHeight < transactionHeight)
				return 0;
			return this.currentHeight - transactionHeight + 1;
		}

		private void detachRule(WatchRule rule) {
			boolean removedServerWatch = false;
			for (AddressWatch watch : rule.watches) {
				watch.rules.remove(rule);
				if (watch.rules.isEmpty()) {
					this.watchesByScripthash.remove(watch.scripthash);
					removedServerWatch = true;
				}
			}
			rule.watches.clear();
			rule.addresses.clear();
			rule.addressByScriptPubKey.clear();
			rule.batches.clear();
			rule.seenTransactionHashes.clear();
			if (removedServerWatch && !this.rules.isEmpty())
				this.client.reconnect();
		}

		private void execute(Runnable task) {
			if (this.closed)
				return;
			try {
				this.worker.execute(() -> {
					if (this.closed)
						return;
					try {
						task.run();
					} catch (Exception e) {
						LOGGER.debug("{} foreign-payment worker task failed: {}", this.coin, e.getMessage());
					}
				});
			} catch (RejectedExecutionException e) {
				if (this.closed)
					return;
				long now = System.currentTimeMillis();
				long previous = this.lastOverloadWarning.get();
				if (now - previous >= OVERLOAD_WARNING_INTERVAL_MS
						&& this.lastOverloadWarning.compareAndSet(previous, now))
					LOGGER.warn("{} foreign-payment notification worker overloaded; dropping push work", this.coin);
			}
		}

		@Override
		public void close() {
			if (this.closed)
				return;
			this.closed = true;
			this.client.close();
			this.worker.getQueue().clear();
			try {
				this.worker.execute(this::clearWorkerState);
			} catch (RejectedExecutionException e) {
				LOGGER.debug("{} foreign-payment worker was already closed", this.coin);
			}
			this.worker.shutdown();
		}

		private void clearWorkerState() {
			this.watchesByScripthash.clear();
			this.transactionCache.clear();
			this.rules.clear();
		}
	}

	private static final class WatchRule {
		private final Session session;
		private final NotificationSubscription subscription;
		private final BitcoinyDeterministicKeyChain keyChain;
		private final Set<AddressWatch> watches = new LinkedHashSet<>();
		private final Set<String> addresses = new LinkedHashSet<>();
		private final Map<String, String> addressByScriptPubKey = new LinkedHashMap<>();
		private final Set<String> seenTransactionHashes = new LinkedHashSet<>();
		private final List<List<AddressWatch>> batches = new ArrayList<>();
		private volatile boolean active = true;
		/** Set when the server refuses to serve this rule's history, so it is retired instead of retried. */
		private boolean historyTooLarge;
		private int processedLeafKeyCount;
		private int processedBatchCount;
		private int unusedCounter;
		private List<AddressWatch> terminalBatch;
		private boolean derivationLimitLogged;

		private WatchRule(Session session, NotificationSubscription subscription,
				BitcoinyDeterministicKeyChain keyChain) {
			this.session = session;
			this.subscription = subscription;
			this.keyChain = keyChain;
		}
	}

	private static final class AddressWatch {
		private final String scripthash;
		/** The output script itself, needed as the parameter for the 1.7 blockchain.scriptpubkey.* family. */
		private final byte[] script;
		private final Set<WatchRule> rules = new LinkedHashSet<>();
		private final ForeignPaymentHistoryDelta history =
				new ForeignPaymentHistoryDelta(MAX_HISTORY_ENTRIES_PER_SCRIPTHASH);
		private boolean initialized;
		private boolean baselineComplete;
		private String checkpoint;
		private boolean retryPending;
		private long subscribedGeneration = -1L;

		private AddressWatch(String scripthash, byte[] script) {
			this.scripthash = scripthash;
			this.script = script;
		}
	}

	static int nextDerivationPairCount(int processedLeafKeyCount) {
		int remainingAddresses = MAX_DERIVED_ADDRESSES_PER_RULE - processedLeafKeyCount;
		if (remainingAddresses < 2)
			return 0;
		return Math.min(INITIAL_LOOKAHEAD_INCREMENT, remainingAddresses / 2);
	}

	static List<ForeignPaymentHistoryDelta.Entry> parseHistory(Object result) throws IOException {
		// 1.7 wraps the same entries in {"history": [...]}; accept either shape.
		Object unwrapped = result instanceof Map<?, ?> ? ((Map<?, ?>) result).get("history") : result;
		if (!(unwrapped instanceof List<?>))
			throw new IOException("Unexpected ElectrumX scripthash history response");
		List<?> history = (List<?>) unwrapped;
		if (history.size() > MAX_HISTORY_ENTRIES_PER_SCRIPTHASH)
			throw new IOException("ElectrumX scripthash history exceeds "
					+ MAX_HISTORY_ENTRIES_PER_SCRIPTHASH + " entries");

		List<ForeignPaymentHistoryDelta.Entry> entries = new ArrayList<>(history.size());
		for (Object value : history) {
			if (!(value instanceof Map<?, ?>))
				throw new IOException("ElectrumX scripthash history contains a non-object entry");
			Map<?, ?> item = (Map<?, ?>) value;
			Object txHash = item.get("tx_hash");
			Object height = item.get("height");
			if (!(txHash instanceof String) || !isHexHash((String) txHash) || !(height instanceof Number))
				throw new IOException("ElectrumX scripthash history contains an invalid entry");
			long numericHeight = ((Number) height).longValue();
			if (numericHeight < Integer.MIN_VALUE || numericHeight > Integer.MAX_VALUE)
				throw new IOException("ElectrumX scripthash history contains an invalid height");
			entries.add(new ForeignPaymentHistoryDelta.Entry((String) txHash, (int) numericHeight));
		}
		return entries;
	}

	static byte[] decodeRawTransaction(Object result) throws IOException {
		if (!(result instanceof String))
			throw new IOException("Unexpected raw transaction response from ElectrumX");

		String hex = (String) result;
		if (hex.length() > MAX_RAW_TRANSACTION_BYTES * 2)
			throw new IOException("ElectrumX raw transaction exceeds " + MAX_RAW_TRANSACTION_BYTES + " bytes");
		if (hex.isEmpty() || (hex.length() & 1) != 0)
			throw new IOException("ElectrumX returned invalid raw transaction hex");

		try {
			return HashCode.fromString(hex).asBytes();
		} catch (IllegalArgumentException e) {
			throw new IOException("ElectrumX returned invalid raw transaction hex", e);
		}
	}

	private static boolean isHexHash(String value) {
		if (value == null || value.length() != 64)
			return false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!((character >= '0' && character <= '9')
					|| (character >= 'a' && character <= 'f')
					|| (character >= 'A' && character <= 'F')))
				return false;
		}
		return true;
	}

	private static void rememberTransactionHash(Set<String> hashes, String transactionHash, int maximumSize) {
		if (!hashes.add(transactionHash))
			return;
		while (hashes.size() > maximumSize) {
			Iterator<String> iterator = hashes.iterator();
			iterator.next();
			iterator.remove();
		}
	}

	private static final class ForeignPaymentThreadFactory implements ThreadFactory {
		private final String coin;
		private final AtomicInteger threadNumber = new AtomicInteger(1);

		private ForeignPaymentThreadFactory(String coin) {
			this.coin = coin;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable,
					"foreign-payment-" + this.coin.toLowerCase(Locale.ROOT) + "-" + this.threadNumber.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}
}
