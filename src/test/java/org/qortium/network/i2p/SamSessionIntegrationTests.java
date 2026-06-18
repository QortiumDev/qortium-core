package org.qortium.network.i2p;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class SamSessionIntegrationTests {

	private static final String RUN_LIVE_I2P_TESTS_PROPERTY = "qortium.runLiveI2PTests";
	private static final String SAM_HOST_PROPERTY = "qortium.i2pSamHost";
	private static final String SAM_PORT_PROPERTY = "qortium.i2pSamPort";
	private static final String DEFAULT_SAM_HOST = "127.0.0.1";
	private static final int DEFAULT_SAM_PORT = 7656;
	private static final int STREAM_TIMEOUT_MS = 30_000;
	private static final int LIVE_TEST_TIMEOUT_MS = 300_000;
	private static final byte[] PAYLOAD = "qortium-i2p-sam-roundtrip".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] REPLY = "qortium-i2p-sam-reply".getBytes(StandardCharsets.US_ASCII);
	private static final String INVALID_B32 = "0000000000000000000000000000000000000000000000000000.b32.i2p";

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test(timeout = LIVE_TEST_TIMEOUT_MS)
	public void testRoundTripViaLiveI2pd() throws Exception {
		assumeLiveI2PTestsEnabled();

		SamSession receiver = newSession("receiver");
		SamSession sender = newSession("sender");
		ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
		ServerSocketChannel listener = null;
		SocketChannel outbound = null;
		Future<InboundResult> inboundResult = null;

		try {
			receiver.start();
			sender.start();

			assertNotNull(receiver.getLocalB32());
			assertNotNull(sender.getLocalB32());
			assertNotEquals(receiver.getLocalB32(), sender.getLocalB32());

			listener = ServerSocketChannel.open();
			listener.socket().setSoTimeout(STREAM_TIMEOUT_MS);
			listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);

			int forwardPort = ((InetSocketAddress) listener.getLocalAddress()).getPort();
			receiver.startForward(forwardPort);

			ServerSocketChannel inboundListener = listener;
			inboundResult = acceptExecutor.submit(() -> receiveAndReply(receiver, inboundListener));

			outbound = sender.connect(receiver.getLocalB32());
			assertNotNull("SAM STREAM CONNECT should return a connected stream", outbound);
			outbound.socket().setSoTimeout(STREAM_TIMEOUT_MS);
			outbound.socket().getOutputStream().write(PAYLOAD);
			outbound.socket().getOutputStream().flush();

			assertArrayEquals(REPLY, readExact(outbound.socket().getInputStream(), REPLY.length));

			InboundResult result = inboundResult.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			assertEquals(sender.getLocalB32(), result.remoteB32);
			assertArrayEquals(PAYLOAD, result.payload);
		} finally {
			closeQuietly(outbound);
			if (inboundResult != null && !inboundResult.isDone())
				inboundResult.cancel(true);
			closeQuietly(listener);
			acceptExecutor.shutdownNow();
			receiver.close();
			sender.close();
		}
	}

	@Test(timeout = LIVE_TEST_TIMEOUT_MS)
	public void testInvalidB32DestinationFails() throws Exception {
		assumeLiveI2PTestsEnabled();

		SamSession session = newSession("invalid");
		SocketChannel failedChannel = null;

		try {
			session.start();
			boolean failed = false;
			try {
				failedChannel = session.connect(INVALID_B32);
				failed = failedChannel == null;
			} catch (IOException e) {
				// Some routers reject malformed destinations by closing the command stream.
				failed = true;
			}
			assertTrue("SAM should reject an invalid .b32.i2p destination", failed);
		} finally {
			closeQuietly(failedChannel);
			session.close();
		}
	}

	private SamSession newSession(String role) throws IOException {
		String sessionId = "qortiumTest" + role + UUID.randomUUID().toString().replace("-", "");
		Path keyFile = this.temporaryFolder.newFolder(role).toPath().resolve("sam.keys");
		String samHost = System.getProperty(SAM_HOST_PROPERTY, DEFAULT_SAM_HOST);
		int samPort = Integer.getInteger(SAM_PORT_PROPERTY, DEFAULT_SAM_PORT);
		return new SamSession(samHost, samPort, sessionId, keyFile);
	}

	private static void assumeLiveI2PTestsEnabled() {
		assumeTrue("Set -D" + RUN_LIVE_I2P_TESTS_PROPERTY + "=true to run live i2pd SAM integration tests",
				Boolean.getBoolean(RUN_LIVE_I2P_TESTS_PROPERTY));
	}

	private static InboundResult receiveAndReply(SamSession receiver, ServerSocketChannel listener) throws IOException {
		try (SocketChannel inbound = listener.accept()) {
			inbound.socket().setSoTimeout(STREAM_TIMEOUT_MS);
			String remoteB32 = receiver.readForwardedDestination(inbound);
			byte[] payload = readExact(inbound.socket().getInputStream(), PAYLOAD.length);
			inbound.socket().getOutputStream().write(REPLY);
			inbound.socket().getOutputStream().flush();
			return new InboundResult(remoteB32, payload);
		}
	}

	private static byte[] readExact(InputStream inputStream, int length) throws IOException {
		byte[] buffer = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = inputStream.read(buffer, offset, length - offset);
			if (read < 0)
				throw new IOException("stream closed after " + offset + " of " + length + " bytes");
			offset += read;
		}
		return buffer;
	}

	private static void closeQuietly(SocketChannel channel) {
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private static void closeQuietly(ServerSocketChannel channel) {
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	private static class InboundResult {
		private final String remoteB32;
		private final byte[] payload;

		private InboundResult(String remoteB32, byte[] payload) {
			this.remoteB32 = remoteB32;
			this.payload = payload;
		}
	}
}
