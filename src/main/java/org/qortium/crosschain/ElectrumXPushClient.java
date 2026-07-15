package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.qortium.crypto.ElectrumSSLSocketFactory;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One persistent ElectrumX connection dedicated to server-push subscriptions.
 * Existing synchronous {@link ElectrumX} connections deliberately remain unchanged.
 */
public final class ElectrumXPushClient implements AutoCloseable {

	private static final Logger LOGGER = LogManager.getLogger(ElectrumXPushClient.class);
	private static final int CONNECT_TIMEOUT_MS = 5_000;
	private static final int REQUEST_TIMEOUT_MS = 15_000;
	private static final long PING_INTERVAL_MS = 30_000L;
	private static final int SOCKET_IDLE_TIMEOUT_MS = 50_000;
	private static final long INITIAL_RECONNECT_DELAY_MS = 1_000L;
	private static final long MAX_RECONNECT_DELAY_MS = 30_000L;
	/** Accommodates a capped 4 MiB raw transaction encoded as hex plus JSON framing. */
	static final int MAX_MESSAGE_LINE_CHARS = 9 * 1024 * 1024;
	static final int MAX_JSON_NESTING_DEPTH = 64;
	static final int MAX_JSON_STRUCTURAL_TOKENS = 20_000;

	public interface Listener {
		void onConnected();

		void onNotification(String method, List<?> params);

		void onDisconnected();
	}

	private final String name;
	private final Supplier<? extends Collection<ElectrumX.Server>> serverSupplier;
	private final Listener listener;
	private final AtomicLong nextId = new AtomicLong(1L);
	private final Map<Long, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
	private final Object writeLock = new Object();
	private final ScheduledExecutorService heartbeatExecutor;

	private volatile boolean stopping;
	private volatile boolean connected;
	private volatile boolean ready;
	private volatile boolean lastDisconnectWasReady;
	private volatile Socket socket;
	private volatile BufferedWriter writer;
	private volatile Thread connectionThread;
	private int nextServerIndex;
	private boolean heartbeatStarted;

	public ElectrumXPushClient(String name,
			Supplier<? extends Collection<ElectrumX.Server>> serverSupplier, Listener listener) {
		this.name = name;
		this.serverSupplier = serverSupplier;
		this.listener = listener;
		this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable,
					"electrum-push-ping-" + this.name.toLowerCase(Locale.ROOT));
			thread.setDaemon(true);
			return thread;
		});
	}

	public synchronized void start() {
		if (this.connectionThread != null)
			return;

		this.stopping = false;
		this.connectionThread = new Thread(this::runConnectionLoop,
				"electrum-push-" + this.name.toLowerCase(Locale.ROOT));
		this.connectionThread.setDaemon(true);
		this.connectionThread.start();

		if (!this.heartbeatStarted) {
			this.heartbeatStarted = true;
			this.heartbeatExecutor.scheduleWithFixedDelay(
					this::pingIfReady, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}
	}

	public boolean isConnected() {
		return this.connected;
	}

	/** Marks a negotiated, chain-verified connection so later reconnect backoff can restart at its initial delay. */
	public void markReady() {
		if (this.connected)
			this.ready = true;
	}

	/** Closes the active socket so the connection loop fails over and reconnects. */
	public void reconnect() {
		Socket activeSocket = this.socket;
		if (activeSocket != null)
			try {
				activeSocket.close();
			} catch (IOException ignored) {
			}
	}

	@SuppressWarnings("unchecked")
	public Object request(String method, Object... params) throws IOException {
		BufferedWriter activeWriter = this.writer;
		if (!this.connected || activeWriter == null)
			throw new IOException("ElectrumX push connection is not connected");

		long id = this.nextId.getAndIncrement();
		CompletableFuture<Object> future = new CompletableFuture<>();
		this.pendingRequests.put(id, future);

		JSONObject request = new JSONObject();
		request.put("jsonrpc", "2.0");
		request.put("id", id);
		request.put("method", method);
		List<Object> requestParams = new ArrayList<>();
		if (params != null)
			Collections.addAll(requestParams, params);
		request.put("params", requestParams);

		try {
			synchronized (this.writeLock) {
				if (!this.connected || this.writer != activeWriter)
					throw new IOException("ElectrumX push connection changed before request write");
				activeWriter.write(request.toJSONString());
				activeWriter.newLine();
				activeWriter.flush();
			}

			return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted waiting for ElectrumX response", e);
		} catch (TimeoutException e) {
			throw new IOException("Timed out waiting for ElectrumX response to " + method, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException)
				throw (IOException) cause;
			throw new IOException("ElectrumX request failed: " + cause.getMessage(), cause);
		} finally {
			this.pendingRequests.remove(id);
		}
	}

	private void runConnectionLoop() {
		long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

		while (!this.stopping) {
			ElectrumX.Server server = null;
			try {
				server = selectServer();
				if (server == null)
					throw new IOException("No configured ElectrumX servers are available");

				Socket connectedSocket = connect(server);
				Reader reader = new InputStreamReader(
						connectedSocket.getInputStream(), StandardCharsets.UTF_8);
				BufferedWriter connectedWriter = new BufferedWriter(new OutputStreamWriter(
						connectedSocket.getOutputStream(), StandardCharsets.UTF_8));

				this.socket = connectedSocket;
				this.writer = connectedWriter;
				this.connected = true;
				this.ready = false;
				this.listener.onConnected();

				String line;
				while (!this.stopping && (line = readBoundedLine(reader, MAX_MESSAGE_LINE_CHARS)) != null)
						handleLine(line);

				if (!this.stopping)
					throw new IOException("ElectrumX push server closed the connection");
			} catch (Exception e) {
				if (!this.stopping)
					LOGGER.debug("{} push connection to {} failed: {}", this.name,
							server == null ? "configured servers" : server, e.getMessage());
			} finally {
				disconnect();
			}

			if (!this.stopping) {
				if (this.lastDisconnectWasReady)
					reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
				try {
					Thread.sleep(reconnectDelay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				if (!this.lastDisconnectWasReady)
					reconnectDelay = Math.min(MAX_RECONNECT_DELAY_MS, reconnectDelay * 2L);
			}
		}
	}

	private void pingIfReady() {
		if (this.stopping || !this.connected || !this.ready)
			return;

		try {
			request("server.ping");
		} catch (IOException e) {
			if (!this.stopping)
				LOGGER.debug("{} push heartbeat failed: {}", this.name, e.getMessage());
			reconnect();
		}
	}

	private ElectrumX.Server selectServer() {
		Collection<ElectrumX.Server> supplied = this.serverSupplier.get();
		List<ElectrumX.Server> servers = ElectrumServerList.filterAllowedServers(supplied);
		if (servers.isEmpty())
			return null;

		int index = Math.floorMod(this.nextServerIndex++, servers.size());
		return servers.get(index);
	}

	private static Socket connect(ElectrumX.Server server) throws IOException {
		if (!ElectrumServerList.isAllowedByTransportPolicy(server))
			throw new IOException("Plaintext Electrum TCP servers are disabled");

		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress(server.getHostName(), server.getPort()), CONNECT_TIMEOUT_MS);
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(REQUEST_TIMEOUT_MS);

			if (server.getConnectionType() == ChainableServer.ConnectionType.SSL) {
				SSLSocketFactory factory = ElectrumSSLSocketFactory.getSocketFactory(server);
				SSLSocket sslSocket = (SSLSocket) factory.createSocket(
						socket, server.getHostName(), server.getPort(), true);
				ElectrumSSLSocketFactory.configureSocket(sslSocket);
				sslSocket.setTcpNoDelay(true);
				sslSocket.setSoTimeout(REQUEST_TIMEOUT_MS);
				sslSocket.startHandshake();
				sslSocket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);
				return sslSocket;
			}

			socket.setSoTimeout(SOCKET_IDLE_TIMEOUT_MS);
			return socket;
		} catch (IOException e) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
	}

	void handleLine(String line) throws Exception {
		validateJsonStructure(line);
		Object parsed = new JSONParser().parse(line);
		if (!(parsed instanceof JSONObject))
			throw new IOException("ElectrumX push server returned a non-object message");

		handleMessage((JSONObject) parsed);
	}

	/** Applies depth/element bounds before the JSON parser allocates a message tree. */
	static void validateJsonStructure(String json) throws IOException {
		int depth = 0;
		int structuralTokens = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int index = 0; index < json.length(); index++) {
			char character = json.charAt(index);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (character == '\\') {
					escaped = true;
				} else if (character == '"') {
					inString = false;
				}
				continue;
			}

			if (character == '"') {
				inString = true;
			} else if (character == '{' || character == '[') {
				depth++;
				structuralTokens++;
				if (depth > MAX_JSON_NESTING_DEPTH)
					throw new IOException("ElectrumX push JSON exceeds nesting depth " + MAX_JSON_NESTING_DEPTH);
			} else if (character == '}' || character == ']') {
				depth--;
				if (depth < 0)
					throw new IOException("ElectrumX push JSON has invalid nesting");
			} else if (character == ',') {
				structuralTokens++;
			}

			if (structuralTokens > MAX_JSON_STRUCTURAL_TOKENS)
				throw new IOException("ElectrumX push JSON exceeds structural token limit "
						+ MAX_JSON_STRUCTURAL_TOKENS);
		}
	}

	/** Reads one newline-delimited message without ever retaining more than {@code maxChars}. */
	static String readBoundedLine(Reader reader, int maxChars) throws IOException {
		if (maxChars < 1)
			throw new IllegalArgumentException("Maximum line length must be positive");

		StringBuilder line = new StringBuilder(Math.min(maxChars, 1024));
		while (true) {
			int value = reader.read();
			if (value < 0)
				return line.length() == 0 ? null : line.toString();
			if (value == '\n') {
				int length = line.length();
				if (length > 0 && line.charAt(length - 1) == '\r')
					line.setLength(length - 1);
				return line.toString();
			}

			if (line.length() >= maxChars)
				throw new IOException("ElectrumX push message exceeds " + maxChars + " characters");
			line.append((char) value);
		}
	}

	void handleMessage(JSONObject message) {
		Object idValue = message.get("id");
		if (idValue instanceof Number) {
			CompletableFuture<Object> future = this.pendingRequests.remove(((Number) idValue).longValue());
			if (future == null)
				return;

			Object error = message.get("error");
			if (error != null)
				future.completeExceptionally(new IOException("ElectrumX RPC error: " + error));
			else
				future.complete(message.get("result"));
			return;
		}

		Object method = message.get("method");
		Object params = message.get("params");
		if (method instanceof String && params instanceof List<?>)
			this.listener.onNotification((String) method, (List<?>) params);
	}

	private void disconnect() {
		boolean wasConnected = this.connected;
		this.lastDisconnectWasReady = this.ready;
		this.ready = false;
		this.connected = false;
		this.writer = null;

		Socket activeSocket = this.socket;
		this.socket = null;
		if (activeSocket != null) {
			try {
				activeSocket.close();
			} catch (IOException ignored) {
			}
		}

		IOException disconnected = new IOException("ElectrumX push connection disconnected");
		for (CompletableFuture<Object> future : this.pendingRequests.values())
			future.completeExceptionally(disconnected);
		this.pendingRequests.clear();

		if (wasConnected) {
			try {
				this.listener.onDisconnected();
			} catch (Exception e) {
				LOGGER.debug("{} push disconnect listener failed: {}", this.name, e.getMessage());
			}
		}
	}

	@Override
	public synchronized void close() {
		this.stopping = true;
		this.heartbeatExecutor.shutdownNow();
		disconnect();

		Thread thread = this.connectionThread;
		this.connectionThread = null;
		if (thread != null)
			thread.interrupt();
	}
}
