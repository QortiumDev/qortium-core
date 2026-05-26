package org.qortium.api.websocket;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.eclipse.jetty.ee8.websocket.api.WriteCallback;
import org.eclipse.jetty.ee8.websocket.api.annotations.*;
import org.eclipse.jetty.ee8.websocket.server.JettyWebSocketServletFactory;
import org.qortium.controller.ChatNotifier;
import org.qortium.crypto.Crypto;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.qortium.data.chat.ChatMessage.Encoding;

@WebSocket
@SuppressWarnings("serial")
public class ActiveChatsWebSocket extends ApiWebSocket {

	/**
	 * Jetty websocket configure implementation.
	 * This maps the current servlet instance to the websocket upgrade path.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		factory.addMapping("/", (req, res) -> this);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		super.onWebSocketConnect(session);

		Map<String, String> pathParams = getPathParams(session, "/{address}");

		String address = pathParams.get("address");
		if (address == null || !Crypto.isValidAddress(address)) {
			session.close(4001, "invalid address");
			return;
		}

		AtomicReference<String> previousOutput = new AtomicReference<>(null);

		ChatNotifier.Listener listener = chatTransactionData -> onNotify(session, chatTransactionData, address, previousOutput);
		ChatNotifier.getInstance().register(session, listener);

		this.onNotify(session, null, address, previousOutput);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		ChatNotifier.getInstance().deregister(session);
		// Parent class cleanup
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping")) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
		}
	}

	private void onNotify(Session session, ChatTransactionData chatTransactionData, String ourAddress, AtomicReference<String> previousOutput) {
		// If CHAT has a recipient (i.e. direct message, not group-based) and we're neither sender nor recipient, then it's of no interest
		if (chatTransactionData != null) {
			String recipient = chatTransactionData.getRecipient();

			if (recipient != null && (!recipient.equals(ourAddress) && !chatTransactionData.getSender().equals(ourAddress)))
				return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			Boolean hasChatReference = getHasChatReference(session);

			ActiveChats activeChats = repository.getChatStoreRepository().getActiveChats(ourAddress, getTargetEncoding(session), hasChatReference);

			StringWriter stringWriter = new StringWriter();

			marshall(stringWriter, activeChats);

			// Only output if something has changed
			String output = stringWriter.toString();
			if (output.equals(previousOutput.get()))
				return;

			previousOutput.set(output);

			// Ensure session is still open before sending
			if (session.isOpen()) {
				session.getRemote().sendString(output, WriteCallback.NOOP);
			}
		} catch (DataException | IOException e) {
			// No output this time
		}
	}

	private Encoding getTargetEncoding(Session session) {
		// Default to Base58 if not specified, for backwards support
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		List<String> encodingList = queryParams.get("encoding");
		String encoding = (encodingList != null && encodingList.size() == 1) ? encodingList.get(0) : "BASE58";
		try {
			return Encoding.valueOf(encoding);
		} catch (IllegalArgumentException e) {
			return Encoding.BASE58;
		}
	}

	private Boolean getHasChatReference(Session session) {
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		List<String> hasChatReferenceList = queryParams.get("haschatreference");

		// Return null if not specified
		if (hasChatReferenceList != null && hasChatReferenceList.size() == 1) {
			String value = hasChatReferenceList.get(0).toLowerCase();
			if (value.equals("true")) {
				return true;
			} else if (value.equals("false")) {
				return false;
			}
		}
		return null; // Ignored if not present
	}
}
