package org.qortium.api.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee8.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee8.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.ee8.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee8.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.data.arbitrary.DataMonitorInfo;
import org.qortium.event.DataMonitorEvent;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.Listener;

import java.io.IOException;
import java.io.StringWriter;

@WebSocket
@SuppressWarnings("serial")
public class DataMonitorSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(DataMonitorSocket.class);

	/**
	 * Uses JettyWebSocketServletFactory for websocket mapping.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		LOGGER.info("configure");

		// Register this instance to handle websocket upgrades on the servlet path
		factory.addMapping("/", (req, res) -> this);

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof DataMonitorEvent))
			return;

		DataMonitorEvent dataMonitorEvent = (DataMonitorEvent) event;

		for (Session session : getSessions())
			sendDataEventSummary(session, buildInfo(dataMonitorEvent));
	}

	private DataMonitorInfo buildInfo(DataMonitorEvent dataMonitorEvent) {
		return new DataMonitorInfo(
			dataMonitorEvent.getTimestamp(),
			dataMonitorEvent.getIdentifier(),
			dataMonitorEvent.getName(),
			dataMonitorEvent.getService(),
			dataMonitorEvent.getDescription(),
			dataMonitorEvent.getTransactionTimestamp(),
			dataMonitorEvent.getLatestPutTimestamp()
		);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* We ignore errors to silence log spam */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (java.util.Objects.equals(message, "ping") && session.isOpen()) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
			return;
		}
		LOGGER.info("onWebSocketMessage: message = " + message);
	}

	private void sendDataEventSummary(Session session, DataMonitorInfo dataMonitorInfo) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, dataMonitorInfo);

			// Use sendString with a WriteCallback for asynchronous delivery.
			if (session.isOpen()) {
				session.getRemote().sendString(stringWriter.toString(), WriteCallback.NOOP);
			}
		} catch (IOException e) {
			// No output this time. WebSocketException is no longer explicitly required here.
		}
	}
}
