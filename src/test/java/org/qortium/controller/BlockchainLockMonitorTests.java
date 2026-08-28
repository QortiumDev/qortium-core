package org.qortium.controller;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BlockchainLockMonitorTests {

	@Test
	public void testWarnsWhileHeldAndStopsAfterClose() {
		AtomicLong nowNanos = new AtomicLong(1_000L);
		List<BlockchainLockMonitor.WarningSnapshot> warnings = new ArrayList<>();
		BlockchainLockMonitor monitor = new BlockchainLockMonitor(null, 30_000L, nowNanos::get, warnings::add);
		ReentrantLock blockchainLock = new ReentrantLock();
		blockchainLock.lock();

		BlockchainLockMonitor.Watch watch = monitor.watch("test operation", blockchainLock);
		try {
			nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(29_999L));
			watch.checkNow();
			assertEquals(0, warnings.size());

			nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1L));
			watch.checkNow();
			assertEquals(1, warnings.size());

			BlockchainLockMonitor.WarningSnapshot warning = warnings.get(0);
			assertEquals("test operation", warning.operation);
			assertEquals(30_000L, warning.elapsedMillis);
			assertEquals(0, warning.queueLength);
			assertEquals(Thread.currentThread().getName(), warning.ownerName);
			assertEquals(Thread.currentThread().getId(), warning.ownerId);
			assertSame(Thread.currentThread().getState(), warning.ownerState);
			assertTrue(warning.stackTrace.length > 0);

			watch.checkNow();
			assertEquals("warnings must be rate limited", 1, warnings.size());

			nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30_000L));
			watch.checkNow();
			assertEquals(2, warnings.size());
			assertEquals(60_000L, watch.elapsedMillis());

			watch.close();
			nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30_000L));
			watch.checkNow();
			assertEquals("closed watches must not report", 2, warnings.size());
		} finally {
			watch.close();
			blockchainLock.unlock();
		}
	}

	@Test
	public void testConcurrentClosePreventsStaleWarning() {
		List<BlockchainLockMonitor.WarningSnapshot> warnings = new ArrayList<>();
		AtomicInteger timeReads = new AtomicInteger();
		AtomicReference<BlockchainLockMonitor.Watch> watchReference = new AtomicReference<>();
		BlockchainLockMonitor monitor = new BlockchainLockMonitor(null, 30_000L, () -> {
			if (timeReads.getAndIncrement() > 0)
				watchReference.get().close();

			return TimeUnit.MILLISECONDS.toNanos(30_000L * timeReads.get());
		}, warnings::add);
		ReentrantLock blockchainLock = new ReentrantLock();
		blockchainLock.lock();

		BlockchainLockMonitor.Watch watch = monitor.watch("closing operation", blockchainLock);
		watchReference.set(watch);
		try {
			watch.checkNow();
			assertEquals("a closed watch must not publish an obsolete snapshot", 0, warnings.size());
		} finally {
			watch.close();
			blockchainLock.unlock();
		}
	}
}
