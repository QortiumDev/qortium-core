package org.qortium.network.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
import org.qortium.utils.DaemonThreadFactory;
import org.qortium.utils.ExecuteProduceConsume.Task;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class PeerConnectTask implements Task {
    private static final Logger LOGGER = LogManager.getLogger(PeerConnectTask.class);
    private static final ExecutorService DEFAULT_CONNECTION_EXECUTOR =
            Executors.newCachedThreadPool(new DaemonThreadFactory(8));

    private final Peer peer;
    private final String name;
    private final Runnable rejectionCleanup;
    private final ExecutorService connectionExecutor;
    private final AtomicBoolean rejectionHandled = new AtomicBoolean(false);

    public PeerConnectTask(Peer peer, Runnable rejectionCleanup) {
        this(peer, rejectionCleanup, DEFAULT_CONNECTION_EXECUTOR);
    }

    PeerConnectTask(Peer peer, Runnable rejectionCleanup, ExecutorService connectionExecutor) {
        this.peer = peer;
        this.name = "PeerConnectTask::" + peer;
        this.rejectionCleanup = Objects.requireNonNull(rejectionCleanup);
        this.connectionExecutor = Objects.requireNonNull(connectionExecutor);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void perform() throws InterruptedException {
        // Submit connection task to a dedicated thread pool for non-blocking I/O
        try {
            connectionExecutor.submit(() -> {
                try {
                    connectPeerAsync(peer);
                } catch (InterruptedException e) {
                    LOGGER.error("Connection attempt interrupted for peer {}", peer, e);
                    Thread.currentThread().interrupt();  // Reset interrupt flag
                }
            });
        } catch (RejectedExecutionException e) {
            this.onRejected();
            throw e;
        }
    }

    @Override
    public void onRejected() {
        if (this.rejectionHandled.compareAndSet(false, true))
            this.rejectionCleanup.run();
    }

    private void connectPeerAsync(Peer peer) throws InterruptedException {
        // Perform peer connection in a separate thread to avoid blocking main task execution
        try {
            switch (peer.getPeerType()) {
                case Peer.NETWORK:
                    Network.getInstance().connectPeer(peer);
                    LOGGER.trace("Called connectPeer {} on NETWORK", peer);
                    break;
                case Peer.NETWORKDATA:
                    NetworkData.getInstance().connectPeer(peer);
                    LOGGER.trace("Called connectPeer {} on QDN", peer);
                    break;
            }

        } catch (Exception e) {
            LOGGER.error("Error connecting to peer {}", peer, e);
        }
    }
}
