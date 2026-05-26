package org.qortium.api.websocket;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.*;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.api.CrossChainTradeFilters;
import org.qortium.controller.tradebot.TradeBot;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.Listener;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

@WebSocket
@SuppressWarnings("serial")
public class TradeBotWebSocket extends ApiWebSocket implements Listener {

    /** Cache of trade-bot entry states, keyed by trade-bot entry's "trade private key" (base58) */
    private static final Map<String, Integer> PREVIOUS_STATES = new HashMap<>();

    private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionBlockchain = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionOfferedForeignBlockchain = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Session, ForeignBlockchainRegistry.Entry> sessionRequestedForeignBlockchain = Collections.synchronizedMap(new HashMap<>());

    /**
     * Uses JettyWebSocketServletFactory for websocket mapping.
     */
    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        // Map the current instance to handle upgrades
        factory.addMapping("/", (req, res) -> this);

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<TradeBotData> tradeBotEntries = repository.getCrossChainRepository().getAllTradeBotData();
            if (tradeBotEntries != null) {
                synchronized (PREVIOUS_STATES) {
                    PREVIOUS_STATES.putAll(tradeBotEntries.stream().collect(Collectors.toMap(
                            entry -> Base58.encode(entry.getTradePrivateKey()),
                            TradeBotData::getStateValue)));
                }
            }
        } catch (DataException e) {
            // No initial state cache available
        }

        EventBus.INSTANCE.addListener(this);
    }

    @Override
    public void listen(Event event) {
        if (!(event instanceof TradeBot.StateChangeEvent))
            return;

        TradeBotData tradeBotData = ((TradeBot.StateChangeEvent) event).getTradeBotData();
        String tradePrivateKey58 = Base58.encode(tradeBotData.getTradePrivateKey());

        synchronized (PREVIOUS_STATES) {
            Integer previousStateValue = PREVIOUS_STATES.get(tradePrivateKey58);
            if (previousStateValue != null && previousStateValue == tradeBotData.getStateValue())
                return;

            PREVIOUS_STATES.put(tradePrivateKey58, tradeBotData.getStateValue());
        }

        List<TradeBotData> tradeBotEntries = Collections.singletonList(tradeBotData);

        for (Session session : getSessions()) {
            if (CrossChainTradeFilters.matchesTradeBotData(tradeBotData, sessionBlockchain.get(session),
                    sessionOfferedForeignBlockchain.get(session), sessionRequestedForeignBlockchain.get(session)))
                sendEntries(session, tradeBotEntries);
        }
    }

    @OnWebSocketConnect
    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);

        Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
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

        String normalizedForeignBlockchain = foreignBlockchainEntry == null ? null : foreignBlockchainEntry.name();
        if (normalizedForeignBlockchain != null)
            sessionBlockchain.put(session, foreignBlockchainEntry);

        ForeignBlockchainRegistry.Entry offeredForeignBlockchainEntry = resolveOptionalBlockchain(queryParams,
                "offeredForeignBlockchain", session, 4005);
        if (isSupplied(queryParams, "offeredForeignBlockchain") && offeredForeignBlockchainEntry == null)
            return;

        ForeignBlockchainRegistry.Entry requestedForeignBlockchainEntry = resolveOptionalBlockchain(queryParams,
                "requestedForeignBlockchain", session, 4006);
        if (isSupplied(queryParams, "requestedForeignBlockchain") && requestedForeignBlockchainEntry == null)
            return;

        if (offeredForeignBlockchainEntry != null)
            sessionOfferedForeignBlockchain.put(session, offeredForeignBlockchainEntry);
        if (requestedForeignBlockchainEntry != null)
            sessionRequestedForeignBlockchain.put(session, requestedForeignBlockchainEntry);

        final ForeignBlockchainRegistry.Entry sessionForeignBlockchainEntry = foreignBlockchainEntry;
        final ForeignBlockchainRegistry.Entry sessionOfferedForeignBlockchainEntry = offeredForeignBlockchainEntry;
        final ForeignBlockchainRegistry.Entry sessionRequestedForeignBlockchainEntry = requestedForeignBlockchainEntry;

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<TradeBotData> tradeBotEntries = new ArrayList<>();

            if (!excludeInitialData) {
                tradeBotEntries = repository.getCrossChainRepository().getAllTradeBotData();
                tradeBotEntries = tradeBotEntries.stream()
                        .filter(tradeBotData -> CrossChainTradeFilters.matchesTradeBotData(tradeBotData,
                                sessionForeignBlockchainEntry, sessionOfferedForeignBlockchainEntry,
                                sessionRequestedForeignBlockchainEntry))
                        .collect(Collectors.toList());
            }

            if (!sendEntries(session, tradeBotEntries)) {
                session.close(4002, "websocket issue");
            }
        } catch (DataException e) {
            session.close(4001, "repository issue fetching trade-bot entries");
        }
    }

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		// clean up
		sessionBlockchain.remove(session);
        sessionOfferedForeignBlockchain.remove(session);
        sessionRequestedForeignBlockchain.remove(session);
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

    private boolean sendEntries(Session session, List<TradeBotData> tradeBotEntries) {
        if (session.isOpen()) {
            try {
                StringWriter stringWriter = new StringWriter();
                marshall(stringWriter, tradeBotEntries);
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
