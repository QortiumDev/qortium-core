package org.qortium.api.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.*;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.api.CrossChainTradeFilters;
import org.qortium.api.model.CrossChainOfferSummary;
import org.qortium.controller.Synchronizer;
import org.qortium.controller.tradebot.TradeBot;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.AcctRegistry;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.data.at.ATStateData;
import org.qortium.data.block.BlockData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.Listener;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.utils.ByteArray;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@WebSocket
@SuppressWarnings("serial")
public class TradeOffersWebSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(TradeOffersWebSocket.class);

	private static class CachedOfferInfo {
		public final Map<String, AcctMode> previousAtModes = new HashMap<>();
		// OFFERING
		public final Map<String, CrossChainOfferSummary> currentSummaries = new HashMap<>();
		// REDEEMED/REFUNDED/CANCELLED
		public final Map<String, CrossChainOfferSummary> historicSummaries = new HashMap<>();
	}
	// Manual synchronization
	private static final Map<String, CachedOfferInfo> cachedInfoByBlockchain = new HashMap<>();

	private static final Predicate<CrossChainOfferSummary> isHistoric = offerSummary
			-> offerSummary.getMode() == AcctMode.REDEEMED
			|| offerSummary.getMode() == AcctMode.REFUNDED
			|| offerSummary.getMode() == AcctMode.CANCELLED;

	private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionBlockchain = Collections.synchronizedMap(new HashMap<>());
	private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionOfferedForeignBlockchain = Collections.synchronizedMap(new HashMap<>());
	private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionRequestedForeignBlockchain = Collections.synchronizedMap(new HashMap<>());
	private static final Map<Session, Long> sessionLocalAssetId = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Uses JettyWebSocketServletFactory for websocket mapping.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Register this instance to handle websocket upgrades
		factory.addMapping("/", (req, res) -> this);

		try (final Repository repository = RepositoryManager.getRepository()) {
			populateCurrentSummaries(repository);
			populateHistoricSummaries(repository);
		} catch (DataException e) {
			// How to fail properly?
			return;
		}

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Synchronizer.NewChainTipEvent))
			return;

		BlockData blockData = ((Synchronizer.NewChainTipEvent) event).getNewChainTip();

		// Process any new info

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Find any new/changed trade ATs since this block
			final Boolean isFinished = null;
			final Integer dataByteOffset = null;
			final Long expectedValue = null;
			final Integer minimumFinalHeight = blockData.getHeight();

			for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
				Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = AcctRegistry.getFilteredAcctMap(blockchain);
				List<CrossChainOfferSummary> crossChainOfferSummaries = new ArrayList<>();

				synchronized (cachedInfoByBlockchain) {
					CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

					for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
						byte[] codeHash = acctInfo.getKey().value;
						ACCT acct = acctInfo.getValue().get();

						List<ATStateData> atStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
								isFinished, dataByteOffset, expectedValue, minimumFinalHeight,
								null, null, null);

						crossChainOfferSummaries.addAll(produceSummaries(repository, acct, atStates, blockData.getTimestamp(), blockchain));
					}

					// OFFERING split-fill summaries can change amounts/slots without changing mode.
					crossChainOfferSummaries.removeIf(offerSummary ->
							offerSummary.getMode() != AcctMode.OFFERING
									&& cachedInfo.previousAtModes.get(offerSummary.getAtAddress()) == offerSummary.getMode());

					// Skip to next blockchain if nothing has changed (for this blockchain)
					if (crossChainOfferSummaries.isEmpty())
						continue;

					// Update
					for (CrossChainOfferSummary offerSummary : crossChainOfferSummaries) {
						String offerAtAddress = offerSummary.getAtAddress();
						cachedInfo.previousAtModes.put(offerAtAddress, offerSummary.getMode());
						LOGGER.trace("Block height: {}, AT: {}, mode: {}", blockData.getHeight(), offerAtAddress, offerSummary.getMode().name());

						switch (offerSummary.getMode()) {
							case OFFERING:
								cachedInfo.currentSummaries.put(offerAtAddress, offerSummary);
								cachedInfo.historicSummaries.remove(offerAtAddress);
								break;
							case REDEEMED:
							case REFUNDED:
							case CANCELLED:
								cachedInfo.currentSummaries.remove(offerAtAddress);
								cachedInfo.historicSummaries.put(offerAtAddress, offerSummary);
								break;
							case TRADING:
								cachedInfo.currentSummaries.remove(offerAtAddress);
								cachedInfo.historicSummaries.remove(offerAtAddress);
								break;
						}
					}

					// Remove any historic offers that are over 24 hours old
					final long tooOldTimestamp = NTP.getTime() - 24 * 60 * 60 * 1000L;
					cachedInfo.historicSummaries.values().removeIf(historicSummary -> historicSummary.getTimestamp() < tooOldTimestamp);
				}

				// Notify sessions
				for (Session session : getSessions()) {
					// Only send if this session has this/no preferred blockchain
					ForeignBlockchainRegistry.Entry preferredBlockchain = sessionBlockchain.get(session);
					if (preferredBlockchain == null || preferredBlockchain.name().equals(blockchain.name())) {
						List<CrossChainOfferSummary> filteredSummaries = filterForSession(session, crossChainOfferSummaries);
						if (preferredBlockchain == null)
							filteredSummaries.removeIf(offerSummary -> isDuplicateForeignForeignCacheSummary(offerSummary, blockchain));
						if (!filteredSummaries.isEmpty())
							sendOfferSummaries(session, filteredSummaries);
					}
				}
			}
		} catch (DataException e) {
			// No output this time
		}
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		final boolean includeHistoric = queryParams.containsKey("includeHistoric");
		final boolean excludeInitialData = queryParams.containsKey("excludeInitialData");

		List<String> foreignBlockchains = queryParams.get("foreignBlockchain");
		final String foreignBlockchain = (foreignBlockchains == null || foreignBlockchains.isEmpty()) ? null : foreignBlockchains.get(0);

		ForeignBlockchainRegistry.Entry foreignBlockchainEntry = null;
		if (foreignBlockchain != null) {
			foreignBlockchainEntry = ForeignBlockchainRegistry.fromString(foreignBlockchain);
			if (foreignBlockchainEntry == null) {
				session.close(4003, "unknown blockchain: " + foreignBlockchain);
				return;
			}
		}

		ForeignBlockchainRegistry.Entry offeredForeignBlockchainEntry = resolveOptionalBlockchain(queryParams,
				"offeredForeignBlockchain", session, 4005);
		if (isSupplied(queryParams, "offeredForeignBlockchain") && offeredForeignBlockchainEntry == null)
			return;

		ForeignBlockchainRegistry.Entry requestedForeignBlockchainEntry = resolveOptionalBlockchain(queryParams,
				"requestedForeignBlockchain", session, 4006);
		if (isSupplied(queryParams, "requestedForeignBlockchain") && requestedForeignBlockchainEntry == null)
			return;

		// Save session's preferred blockchain filters, if given
		if (foreignBlockchainEntry != null)
			sessionBlockchain.put(session, foreignBlockchainEntry);
		if (offeredForeignBlockchainEntry != null)
			sessionOfferedForeignBlockchain.put(session, offeredForeignBlockchainEntry);
		if (requestedForeignBlockchainEntry != null)
			sessionRequestedForeignBlockchain.put(session, requestedForeignBlockchainEntry);

		List<String> localAssetIds = queryParams.get("localAssetId");
		if (localAssetIds != null && !localAssetIds.isEmpty()) {
			try {
				sessionLocalAssetId.put(session, Long.parseLong(localAssetIds.get(0)));
			} catch (NumberFormatException e) {
				session.close(4004, "invalid localAssetId: " + localAssetIds.get(0));
				return;
			}
		}

		List<CrossChainOfferSummary> crossChainOfferSummaries = new ArrayList<>();

		// We might need to exclude the initial data from the response
		if (!excludeInitialData) {
			synchronized (cachedInfoByBlockchain) {
				Collection<CachedOfferInfo> cachedInfos;
				if (foreignBlockchainEntry == null)
					// No preferred blockchain, so iterate through all of them
					cachedInfos = cachedInfoByBlockchain.values();
				else
					cachedInfos = Collections.singleton(cachedInfoByBlockchain.computeIfAbsent(foreignBlockchainEntry.name(), k -> new CachedOfferInfo()));

				for (CachedOfferInfo cachedInfo : cachedInfos) {
					crossChainOfferSummaries.addAll(cachedInfo.currentSummaries.values());
					if (includeHistoric)
						crossChainOfferSummaries.addAll(cachedInfo.historicSummaries.values());
				}
			}
		}

		if (!sendOfferSummaries(session, filterForSession(session, crossChainOfferSummaries))) {
			session.close(4002, "websocket issue");
			return;
		}

		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		// clean up
		sessionBlockchain.remove(session);
		sessionOfferedForeignBlockchain.remove(session);
		sessionRequestedForeignBlockchain.remove(session);
		sessionLocalAssetId.remove(session);
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping") && session.isOpen()) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
		}
	}

	private boolean sendOfferSummaries(Session session, List<CrossChainOfferSummary> crossChainOfferSummaries) {
		if (session.isOpen()) {
			try {
				StringWriter stringWriter = new StringWriter();
				marshall(stringWriter, crossChainOfferSummaries);
				String output = stringWriter.toString();

				// Use Jetty's async send pattern.
				session.getRemote().sendString(output, WriteCallback.NOOP);
				return true;
			} catch (IOException e) {
				return false;
			}
		}
		return false;
	}

	private static List<CrossChainOfferSummary> filterForSession(Session session, List<CrossChainOfferSummary> crossChainOfferSummaries) {
		ForeignBlockchainRegistry.Entry foreignBlockchain = sessionBlockchain.get(session);
		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = sessionOfferedForeignBlockchain.get(session);
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = sessionRequestedForeignBlockchain.get(session);
		Long localAssetId = sessionLocalAssetId.get(session);

		Map<String, CrossChainOfferSummary> filteredSummariesByAtAddress = new LinkedHashMap<>();
		for (CrossChainOfferSummary offerSummary : crossChainOfferSummaries) {
			if (CrossChainTradeFilters.matchesOfferSummary(offerSummary, foreignBlockchain,
					offeredForeignBlockchain, requestedForeignBlockchain, localAssetId)) {
				filteredSummariesByAtAddress.putIfAbsent(offerSummary.getAtAddress(), offerSummary);
			}
		}

		return new ArrayList<>(filteredSummariesByAtAddress.values());
	}

	private static void populateCurrentSummaries(Repository repository) throws DataException {
		Boolean isFinished = Boolean.FALSE;
		Long expectedValue = (long) AcctMode.OFFERING.value;

		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = AcctRegistry.getFilteredAcctMap(blockchain);
			CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();
				Integer dataByteOffset = acct.getModeByteOffset();

				List<ATStateData> initialAtStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
						isFinished, dataByteOffset, expectedValue, null, null, null, null);

				if (initialAtStates == null)
					throw new DataException("Couldn't fetch current trades from repository");

				// Save initial AT modes
				cachedInfo.previousAtModes.putAll(initialAtStates.stream().collect(Collectors.toMap(ATStateData::getATAddress, atState -> AcctMode.OFFERING)));

				// Convert to offer summaries
					cachedInfo.currentSummaries.putAll(produceSummaries(repository, acct, initialAtStates, null, blockchain).stream()
							.collect(Collectors.toMap(CrossChainOfferSummary::getAtAddress, offerSummary -> offerSummary)));
			}
		}
	}

	private static void populateHistoricSummaries(Repository repository) throws DataException {
		// We want REDEEMED/REFUNDED/CANCELLED trades over the last 24 hours
		long timestamp = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
		int minimumFinalHeight = repository.getBlockRepository().getHeightFromTimestamp(timestamp);

		if (minimumFinalHeight == 0)
			throw new DataException("Couldn't fetch block timestamp from repository");

		Boolean isFinished = Boolean.TRUE;
		++minimumFinalHeight; // because height is just *before* timestamp

		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
			Map<ByteArray, Supplier<ACCT>> acctsByCodeHash = AcctRegistry.getFilteredAcctMap(blockchain);
			CachedOfferInfo cachedInfo = cachedInfoByBlockchain.computeIfAbsent(blockchain.name(), k -> new CachedOfferInfo());

			for (Map.Entry<ByteArray, Supplier<ACCT>> acctInfo : acctsByCodeHash.entrySet()) {
				byte[] codeHash = acctInfo.getKey().value;
				ACCT acct = acctInfo.getValue().get();

				List<ATStateData> historicAtStates = repository.getATRepository().getMatchingFinalATStates(codeHash, null, null,
						isFinished, null, null, minimumFinalHeight, null, null, null);

				if (historicAtStates == null)
					throw new DataException("Couldn't fetch historic trades from repository");

				for (ATStateData historicAtState : historicAtStates) {
					CrossChainOfferSummary historicOfferSummary = produceSummary(repository, acct, historicAtState, null, null);
					if (historicOfferSummary == null)
						continue;
					if (!matchesBlockchain(historicOfferSummary, blockchain))
						continue;
					if (!isHistoric.test(historicOfferSummary))
						continue;

					// Add summary to initial burst
					cachedInfo.historicSummaries.put(historicOfferSummary.getAtAddress(), historicOfferSummary);

					// Save initial AT mode
					cachedInfo.previousAtModes.put(historicOfferSummary.getAtAddress(), historicOfferSummary.getMode());
				}
			}
		}
	}

	private static CrossChainOfferSummary produceSummary(Repository repository, ACCT acct, ATStateData atState, CrossChainTradeData crossChainTradeData, Long timestamp) throws DataException {
		if (crossChainTradeData == null) {
			crossChainTradeData = acct.populateTradeData(repository, atState);
		}
		if (crossChainTradeData == null)
			return null;

		long atStateTimestamp;
		if (crossChainTradeData.mode == AcctMode.OFFERING)
			// We want when trade was created, not when it was last updated
			atStateTimestamp = crossChainTradeData.creationTimestamp;
		else
			atStateTimestamp = timestamp != null ? timestamp : repository.getBlockRepository().getTimestampFromHeight(atState.getHeight());

		return new CrossChainOfferSummary(crossChainTradeData, atStateTimestamp);
	}

	private static List<CrossChainOfferSummary> produceSummaries(Repository repository, ACCT acct, List<ATStateData> atStates, Long timestamp, ForeignBlockchainRegistry.Entry blockchain) throws DataException {
		List<CrossChainOfferSummary> offerSummaries = new ArrayList<>();
		List<CrossChainTradeData> crossChainTrades = new ArrayList<>();
		List<ATStateData> atStatesByTrade = new ArrayList<>();

		for (ATStateData atState : atStates) {
			CrossChainTradeData crossChainTradeData = acct.populateTradeData(repository, atState);
			if (crossChainTradeData == null)
				continue;
			if (!matchesBlockchain(crossChainTradeData, blockchain))
				continue;

			crossChainTrades.add(crossChainTradeData);
			atStatesByTrade.add(atState);
		}

		// Batch failed-trade filtering to avoid re-running unconfirmed MESSAGE queries per trade.
		Set<String> nonFailedTradeAddresses = TradeBot.getInstance().removeFailedTrades(repository, crossChainTrades).stream()
				.map(crossChainTradeData -> crossChainTradeData.atAddress)
				.collect(Collectors.toSet());

		for (int i = 0; i < crossChainTrades.size(); ++i) {
			CrossChainTradeData crossChainTradeData = crossChainTrades.get(i);
			if (!nonFailedTradeAddresses.contains(crossChainTradeData.atAddress))
				continue;

			offerSummaries.add(produceSummary(repository, acct, atStatesByTrade.get(i), crossChainTradeData, timestamp));
		}

		return offerSummaries;
	}

	private static boolean matchesBlockchain(CrossChainTradeData crossChainTradeData, ForeignBlockchainRegistry.Entry blockchain) {
		return CrossChainTradeFilters.matchesForeignBlockchain(crossChainTradeData, blockchain);
	}

	private static boolean matchesBlockchain(CrossChainOfferSummary offerSummary, ForeignBlockchainRegistry.Entry blockchain) {
		return CrossChainTradeFilters.matchesForeignBlockchain(offerSummary, blockchain);
	}

	private static boolean isDuplicateForeignForeignCacheSummary(CrossChainOfferSummary offerSummary,
			ForeignBlockchainRegistry.Entry blockchain) {
		return offerSummary.getTradeDirection() == TradeDirection.SELL_FOREIGN_FOR_FOREIGN
				&& offerSummary.getOfferedForeignBlockchain() != null
				&& !blockchain.name().equals(offerSummary.getOfferedForeignBlockchain());
	}

	private static ForeignBlockchainRegistry.Entry resolveOptionalBlockchain(Map<String, List<String>> queryParams, String parameterName,
			Session session, int closeStatusCode) {
		List<String> blockchains = queryParams.get(parameterName);
		String blockchain = blockchains == null || blockchains.isEmpty() ? null : blockchains.get(0);
		if (blockchain == null)
			return null;

		ForeignBlockchainRegistry.Entry blockchainEntry = ForeignBlockchainRegistry.fromString(blockchain);
		if (blockchainEntry == null) {
			session.close(closeStatusCode, "unknown blockchain: " + blockchain);
			return null;
		}

		return blockchainEntry;
	}

	private static boolean isSupplied(Map<String, List<String>> queryParams, String parameterName) {
		List<String> values = queryParams.get(parameterName);
		return values != null && !values.isEmpty();
	}
}
