package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.utils.DaemonThreadFactory;

import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Reports blockchain-lock stalls while they are still occurring.
 *
 * <p>The monitor is deliberately observational: it never interrupts the owner,
 * releases the lock, or changes consensus behavior.</p>
 */
final class BlockchainLockMonitor {

	private static final Logger LOGGER = LogManager.getLogger(BlockchainLockMonitor.class);
	static final long WARNING_INTERVAL_MILLIS = 30_000L;
	private static final int MAX_REPORTED_STACK_FRAMES = 20;

	private static final BlockchainLockMonitor INSTANCE = new BlockchainLockMonitor();

	private final ScheduledExecutorService scheduler;
	private final long warningIntervalNanos;
	private final LongSupplier nanoTime;
	private final Consumer<WarningSnapshot> warningSink;

	private BlockchainLockMonitor() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
				new DaemonThreadFactory("BlockchainLockMonitor", Thread.NORM_PRIORITY));
		scheduler.setRemoveOnCancelPolicy(true);
		this.scheduler = scheduler;
		this.warningIntervalNanos = TimeUnit.MILLISECONDS.toNanos(WARNING_INTERVAL_MILLIS);
		this.nanoTime = System::nanoTime;
		this.warningSink = BlockchainLockMonitor::logWarning;
	}

	BlockchainLockMonitor(ScheduledExecutorService scheduler, long warningIntervalMillis,
			LongSupplier nanoTime, Consumer<WarningSnapshot> warningSink) {
		if (warningIntervalMillis <= 0L)
			throw new IllegalArgumentException("warning interval must be positive");

		this.scheduler = scheduler;
		this.warningIntervalNanos = TimeUnit.MILLISECONDS.toNanos(warningIntervalMillis);
		this.nanoTime = nanoTime;
		this.warningSink = warningSink;
	}

	static BlockchainLockMonitor getInstance() {
		return INSTANCE;
	}

	Watch watch(String operation, ReentrantLock blockchainLock) {
		Watch watch = new Watch(operation, blockchainLock, Thread.currentThread(), this.nanoTime.getAsLong());

		if (this.scheduler != null) {
			long warningIntervalMillis = TimeUnit.NANOSECONDS.toMillis(this.warningIntervalNanos);
			try {
				ScheduledFuture<?> future = this.scheduler.scheduleWithFixedDelay(watch::checkSafely,
						warningIntervalMillis, warningIntervalMillis, TimeUnit.MILLISECONDS);
				watch.setFuture(future);
			} catch (RejectedExecutionException e) {
				LOGGER.warn("Unable to schedule blockchain lock stall diagnostics", e);
			}
		}

		return watch;
	}

	private static void logWarning(WarningSnapshot snapshot) {
		String stack = Arrays.stream(snapshot.stackTrace)
				.limit(MAX_REPORTED_STACK_FRAMES)
				.map(StackTraceElement::toString)
				.collect(Collectors.joining(" <- "));

		LOGGER.warn("Blockchain lock hold sampled: {} on thread {}#{} ({}) after {} ms; approximately {} thread(s) waiting; sampled owner stack: {}",
				snapshot.operation, snapshot.ownerName, snapshot.ownerId, snapshot.ownerState,
				snapshot.elapsedMillis, snapshot.queueLength, stack);
	}

	final class Watch implements AutoCloseable {
		private final String operation;
		private final ReentrantLock blockchainLock;
		private final Thread owner;
		private final long startedNanos;
		private final AtomicBoolean active = new AtomicBoolean(true);
		private final AtomicLong lastWarningElapsedNanos = new AtomicLong(Long.MIN_VALUE);
		private volatile ScheduledFuture<?> future;

		private Watch(String operation, ReentrantLock blockchainLock, Thread owner, long startedNanos) {
			this.operation = operation;
			this.blockchainLock = blockchainLock;
			this.owner = owner;
			this.startedNanos = startedNanos;
		}

		private void setFuture(ScheduledFuture<?> future) {
			this.future = future;
			if (!this.active.get())
				future.cancel(false);
		}

		private void checkSafely() {
			try {
				this.checkNow();
			} catch (RuntimeException e) {
				LOGGER.warn("Unable to report blockchain lock stall diagnostics", e);
			}
		}

		void checkNow() {
			if (!this.active.get())
				return;

			long elapsedNanos = this.elapsedNanos();
			if (elapsedNanos < BlockchainLockMonitor.this.warningIntervalNanos)
				return;

			long previousWarningNanos = this.lastWarningElapsedNanos.get();
			if (previousWarningNanos != Long.MIN_VALUE
					&& elapsedNanos - previousWarningNanos < BlockchainLockMonitor.this.warningIntervalNanos)
				return;

			if (!this.lastWarningElapsedNanos.compareAndSet(previousWarningNanos, elapsedNanos))
				return;

			if (!this.active.get())
				return;

			WarningSnapshot snapshot = new WarningSnapshot(this.operation,
					TimeUnit.NANOSECONDS.toMillis(elapsedNanos), this.blockchainLock.getQueueLength(),
					this.owner.getName(), this.owner.getId(), this.owner.getState(), this.owner.getStackTrace());
			if (!this.active.get())
				return;

			BlockchainLockMonitor.this.warningSink.accept(snapshot);
		}

		long elapsedMillis() {
			return TimeUnit.NANOSECONDS.toMillis(this.elapsedNanos());
		}

		private long elapsedNanos() {
			return BlockchainLockMonitor.this.nanoTime.getAsLong() - this.startedNanos;
		}

		@Override
		public void close() {
			if (!this.active.compareAndSet(true, false))
				return;

			ScheduledFuture<?> currentFuture = this.future;
			if (currentFuture != null)
				currentFuture.cancel(false);
		}
	}

	static final class WarningSnapshot {
		final String operation;
		final long elapsedMillis;
		final int queueLength;
		final String ownerName;
		final long ownerId;
		final Thread.State ownerState;
		final StackTraceElement[] stackTrace;

		private WarningSnapshot(String operation, long elapsedMillis, int queueLength,
				String ownerName, long ownerId, Thread.State ownerState, StackTraceElement[] stackTrace) {
			this.operation = operation;
			this.elapsedMillis = elapsedMillis;
			this.queueLength = queueLength;
			this.ownerName = ownerName;
			this.ownerId = ownerId;
			this.ownerState = ownerState;
			this.stackTrace = stackTrace;
		}
	}
}
