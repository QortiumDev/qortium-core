package org.qortium.network.task;

import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PeerConnectTaskTests {

    @Test
    public void testRejectedConnectionExecutorRunsCleanupOnce() throws Exception {
        ExecutorService rejectingExecutor = Executors.newSingleThreadExecutor();
        rejectingExecutor.shutdownNow();
        AtomicInteger cleanupCount = new AtomicInteger();
        Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
        PeerConnectTask task = new PeerConnectTask(peer, cleanupCount::incrementAndGet, rejectingExecutor);

        try {
            task.perform();
            fail("Expected connection executor to reject the task");
        } catch (RejectedExecutionException expected) {
            // Expected: cleanup occurs before the rejection is propagated.
        }
        task.onRejected();

        assertEquals("Rejected connection task cleanup must be idempotent", 1, cleanupCount.get());
    }
}
