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
import org.qortium.data.crosschain.UnsignedFeeEvent;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.FeeWaitingEvent;
import org.qortium.event.Listener;

import java.io.IOException;
import java.io.StringWriter;

@WebSocket
@SuppressWarnings("serial")
public class UnsignedFeesSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(UnsignedFeesSocket.class);

	/**
	 * Uses JettyWebSocketServletFactory for websocket mapping.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Map the current instance to handle upgrades
		factory.addMapping("/", (req, res) -> this);

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof FeeWaitingEvent))
			return;

		FeeWaitingEvent feeWaitingEvent = (FeeWaitingEvent) event;
		UnsignedFeeEvent unsignedFeeEvent = new UnsignedFeeEvent(feeWaitingEvent.isPositive(), feeWaitingEvent.getAddress());

		for (Session session : getSessions()) {
			sendUnsignedFeeEvent(session, unsignedFeeEvent);
		}
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
		}
	}

	private void sendUnsignedFeeEvent(Session session, UnsignedFeeEvent unsignedFeeEvent) {
		if (session.isOpen()) {
			StringWriter stringWriter = new StringWriter();

			try {
				marshall(stringWriter, unsignedFeeEvent);

				session.getRemote().sendString(stringWriter.toString(), WriteCallback.NOOP);
			} catch (IOException e) {
				// No output this time. WebSocketException catch removed.
			}
		}
	}
}
