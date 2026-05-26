package org.qortium.api.websocket;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.*;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.controller.Controller;
import org.qortium.controller.tradebot.TradeBot;
import org.qortium.data.network.TradePresenceData;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.Listener;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

@WebSocket
@SuppressWarnings("serial")
public class TradePresenceWebSocket extends ApiWebSocket implements Listener {

	/** Map key is public key in base58, map value is trade presence */
	private static final Map<String, TradePresenceData> currentEntries = Collections.synchronizedMap(new HashMap<>());

	/**
	 * Uses JettyWebSocketServletFactory for websocket mapping.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Map the current instance to handle upgrades
		factory.addMapping("/", (req, res) -> this);

		populateCurrentInfo();

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		// XXX - Suggest we change this to something like Synchronizer.NewChainTipEvent?
		// We use NewBlockEvent as a proxy for 1-minute timer
		if (!(event instanceof TradeBot.TradePresenceEvent) && !(event instanceof Controller.NewBlockEvent))
			return;

		removeOldEntries();

		if (event instanceof Controller.NewBlockEvent)
			// We only wanted a chance to cull old entries
			return;

		TradePresenceData tradePresence = ((TradeBot.TradePresenceEvent) event).getTradePresenceData();

		boolean somethingChanged = mergePresence(tradePresence);

		if (!somethingChanged)
			// nothing changed
			return;

		List<TradePresenceData> tradePresences = Collections.singletonList(tradePresence);

		// Notify sessions
		for (Session session : getSessions()) {
			sendTradePresences(session, tradePresences);
		}
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		final boolean excludeInitialData = queryParams.containsKey("excludeInitialData");

		List<TradePresenceData> tradePresences;

		if (excludeInitialData) {
			tradePresences = new ArrayList<>();
		} else {
			synchronized (currentEntries) {
				tradePresences = List.copyOf(currentEntries.values());
			}
		}

		if (!sendTradePresences(session, tradePresences)) {
			session.close(4002, "websocket issue");
			return;
		}
		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		// clean up
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

	private boolean sendTradePresences(Session session, List<TradePresenceData> tradePresences) {
		if (session.isOpen()) {
			try {
				StringWriter stringWriter = new StringWriter();
				marshall(stringWriter, tradePresences);
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

	private static void populateCurrentInfo() {
		TradeBot.getInstance().getAllTradePresences()
				.forEach(TradePresenceWebSocket::mergePresence);
	}

	/** Merge trade presence into cache of current entries, returns true if cache was updated. */
	private static boolean mergePresence(TradePresenceData tradePresence) {
		// Put/replace for this publickey making sure we keep newest timestamp
		String pubKey58 = Base58.encode(tradePresence.getPublicKey());
		TradePresenceData newEntry = currentEntries.compute(pubKey58, (k, v) ->
				v == null || v.getTimestamp() < tradePresence.getTimestamp() ? tradePresence : v);

		return newEntry == tradePresence;
	}

	private static void removeOldEntries() {
		long now = NTP.getTime();
		currentEntries.values().removeIf(v -> v.getTimestamp() < now);
	}
}
