package org.qortium.crosschain;
import org.junit.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class WalletServerPoolTests {
    @Test public void failedPrimaryYieldsToStickyBackupAndAllFailedBackOff() {
        var clock = new AtomicLong(1); var wall = new AtomicLong(1000);
        var pool = new WalletServerPool(List.of("https://primary.test", "https://backup.test"),
                Duration.ofSeconds(10), Duration.ofMinutes(5), clock::get, wall::get);
        var primary = pool.candidate(); pool.failed(primary);
        var backup = pool.candidate(); assertNotEquals(primary, backup);
        pool.succeeded(backup); assertEquals(backup, pool.candidate());
        clock.addAndGet(Duration.ofSeconds(11).toNanos()); assertEquals(backup, pool.candidate());
        pool.failed(backup); assertEquals(primary, pool.candidate()); pool.failed(primary);
        assertNull(pool.candidate()); assertEquals(10000, pool.retryDelayMillis());
        assertEquals(Long.valueOf(11000), pool.status().retryAt());
        clock.addAndGet(Duration.ofSeconds(10).toNanos()); assertEquals(backup, pool.candidate());
        pool.succeeded(backup); assertNull(pool.status().retryAt()); assertEquals(0, pool.status().servers().get(1).failures());
    }
    @Test public void failuresExponentiallyBackOffAndCapWithoutWallClockAuthority() {
        var mono = new AtomicLong(1); var wall = new AtomicLong(1000);
        var pool = new WalletServerPool(List.of("https://one.test"),Duration.ofSeconds(10),Duration.ofMinutes(5),mono::get,wall::get);
        for (int i = 0; i < 9; i++) {
            var server = pool.candidate(); assertNotNull(server); pool.failed(server);
            long expected = Math.min(300000,10000L << i);
            assertEquals(expected,pool.retryDelayMillis());
            wall.addAndGet(9999999); assertNull(pool.candidate());
            mono.addAndGet(Duration.ofMillis(expected).toNanos());
        }
        assertThrows(IllegalArgumentException.class,()->pool.failed(new WalletServerPool.Server("other","https://other.test")));
    }
    @Test public void invalidPoolsCannotAcquireProviders() {
        assertThrows(IllegalArgumentException.class,()->new WalletServerPool(List.of()));
        assertThrows(IllegalArgumentException.class,()->new WalletServerPool(List.of("a","a")));
        assertThrows(IllegalArgumentException.class,()->new WalletServerPool(java.util.Collections.nCopies(9,"a")));
    }
}
