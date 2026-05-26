package org.qortium.api.websocket;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.*;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.api.model.NodeStatus;
import org.qortium.controller.Controller;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.event.Listener;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

@WebSocket
@SuppressWarnings("serial")
public class AdminStatusWebSocket extends ApiWebSocket implements Listener {

	private static final AtomicReference<String> previousOutput = new AtomicReference<>(null);

	/**
	 * Use JettyWebSocketServletFactory for manual websocket mapping.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Register this instance to handle websocket upgrades on the servlet path
		factory.addMapping("/", (req, res) -> this);

		try {
			previousOutput.set(buildStatusString());
		} catch (IOException e) {
			// Fail silently or log; status will update on next event
			return;
		}

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof Controller.StatusChangeEvent))
			return;

		String newOutput;
		try {
			newOutput = buildStatusString();
		} catch (IOException e) {
			return;
		}

		// Update atomic reference and check if content actually changed
		String oldOutput = previousOutput.getAndSet(newOutput);
		if (newOutput.equals(oldOutput))
			return;

		for (Session session : getSessions())
			this.sendStatus(session, newOutput);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		// Initial status push
		this.sendStatus(session, previousOutput.get());

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

	private static String buildStatusString() throws IOException {
		NodeStatus nodeStatus = new NodeStatus();
		StringWriter stringWriter = new StringWriter();
		marshall(stringWriter, nodeStatus);
		return stringWriter.toString();
	}

	private void sendStatus(Session session, String status) {
		if (session.isOpen() && status != null) {
			// Using NOOP as we don't need to track the success of this specific push
			session.getRemote().sendString(status, WriteCallback.NOOP);
		}
	}
}
