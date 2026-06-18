package org.qortium.network.i2p;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/**
 * Abstraction over an I2P transport, so the rest of the networking code does not depend on
 * <em>how</em> I2P streams are obtained. The initial implementation ({@link SamSession}) speaks
 * SAM v3 to an external i2pd router; a future implementation could embed a Java I2P router
 * ({@code net.i2p}) behind the same interface — see {@code docs/design/i2p-fallback-transport.md}.
 *
 * <p>Each provider instance corresponds to exactly ONE I2P destination, i.e. one logical network:
 * the chain network ({@code Network}) and the QDN data network ({@code NetworkData}) each get their
 * own provider/destination.
 *
 * <p>Key insight that keeps the integration small: a SAM {@code STREAM CONNECT}/{@code STREAM
 * FORWARD} yields ordinary TCP {@link SocketChannel}s to the local SAM bridge that transparently
 * carry data to/from the remote {@code .b32.i2p} peer — so the rest of Qortium's selector /
 * handshake / message machinery is reused unchanged. Only connection establishment and addressing
 * are new.
 */
public interface I2PStreamProvider {

    /** Bring up the I2P session (loads or generates+persists the destination keys). Idempotent. */
    void start() throws IOException;

    /**
     * @return our own destination as a {@code "<52-char-base32>.b32.i2p"} address, or {@code null}
     *         if the session is not up. This is what we advertise to peers (see the I2P handshake
     *         capabilities).
     */
    String getLocalB32();

    /** @return true if the I2P session is currently established — i.e. we are inbound-reachable over I2P. */
    boolean isSessionUp();

    /**
     * Open an outbound stream to a remote I2P destination.
     *
     * @param remoteB32 a {@code "<base32>.b32.i2p"} address (the SAM bridge resolves it via the netDB)
     * @return a connected, <em>blocking</em> {@link SocketChannel} carrying the stream, or {@code null}
     *         on failure. The caller configures non-blocking mode / selector registration, exactly as
     *         for a TCP {@code SocketChannel}.
     */
    SocketChannel connect(String remoteB32) throws IOException;

    /**
     * Ask the router to forward all inbound I2P streams for our destination to a local (loopback) TCP
     * port. The caller listens on that port with a normal {@code ServerSocketChannel} and accepts
     * inbound peers there. With {@code SILENT=false}, each forwarded connection is prefixed by the
     * remote peer's destination on its first line — parse it with {@link #readForwardedDestination}
     * before handing the channel to the peer machinery.
     *
     * @param localPort the loopback port that inbound I2P streams should be forwarded to
     */
    void startForward(int localPort) throws IOException;

    /**
     * Read the leading destination line that SAM ({@code SILENT=false}) writes on a forwarded inbound
     * connection, returning the remote peer's {@code .b32.i2p} address. Must be called once, before any
     * peer-protocol bytes are read from the channel.
     *
     * @param inbound a freshly-accepted forwarded connection
     * @return the remote peer's {@code .b32.i2p} address
     */
    String readForwardedDestination(SocketChannel inbound) throws IOException;

    /** Stop the session and release the destination. */
    void close();
}
