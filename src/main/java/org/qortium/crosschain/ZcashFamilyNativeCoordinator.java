package org.qortium.crosschain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes access to LiteWalletJni's single process-global wallet context. */
public final class ZcashFamilyNativeCoordinator implements AutoCloseable {

	public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
	public static final Duration SYNC_TIMEOUT = Duration.ofMinutes(30);
	public static final Duration STATUS_TIMEOUT = Duration.ofSeconds(5);

	private static final ZcashFamilyNativeCoordinator INSTANCE =
			new ZcashFamilyNativeCoordinator(new LiteWalletJniAdapter(), "Zcash Family Native Wallet");

	private final ZcashFamilyNativeAdapter adapter;
	private final ZcashFamilyNativeAdapter guardedAdapter;
	private final ThreadPoolExecutor executor;
	private final AtomicBoolean degraded = new AtomicBoolean(false);
	private volatile Thread workerThread;
	private volatile String degradedReason;

	@FunctionalInterface
	public interface NativeOperation<T> {
		T execute(ZcashFamilyNativeAdapter adapter) throws Exception;
	}

	public static ZcashFamilyNativeCoordinator getInstance() {
		return INSTANCE;
	}

	ZcashFamilyNativeCoordinator(ZcashFamilyNativeAdapter adapter, String threadName) {
		this.adapter = Objects.requireNonNull(adapter);
		this.guardedAdapter = new GuardedNativeAdapter();
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(1), runnable -> {
			Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			this.workerThread = thread;
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	public <T> T execute(String operationName, NativeOperation<T> operation) {
		return this.execute(operationName, DEFAULT_TIMEOUT, operation);
	}

	public <T> T execute(String operationName, Duration timeout, NativeOperation<T> operation) {
		Objects.requireNonNull(operationName);
		Objects.requireNonNull(timeout);
		Objects.requireNonNull(operation);

		if (this.degraded.get())
			throw new NativeWalletException("Native wallet lane is degraded: " + this.degradedReason);

		if (Thread.currentThread() == this.workerThread)
			return executeDirect(operationName, operation);

		NativeTask<T> task = new NativeTask<>(operationName, operation);
		final Future<T> future;
		try {
			future = this.executor.submit(task::run);
		} catch (RuntimeException e) {
			throw new NativeWalletException("Native wallet lane is busy while starting " + operationName, e);
		}

		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			boolean started = task.started.get();
			if (started)
				degrade(operationName + " timed out");
			future.cancel(true);
			if (!started && task.started.get()) {
				started = true;
				degrade(operationName + " timed out while starting");
			}
			if (started)
				throw new NativeWalletException("Native wallet operation timed out; lane is now degraded: " + operationName, e);
			throw new NativeWalletException("Timed out waiting to start native wallet operation: " + operationName, e);
		} catch (InterruptedException e) {
			boolean started = task.started.get();
			if (started)
				degrade(operationName + " was interrupted after starting");
			future.cancel(true);
			if (!started && task.started.get()) {
				started = true;
				degrade(operationName + " was interrupted while starting");
			}
			Thread.currentThread().interrupt();
			String state = started ? "; lane is now degraded" : "";
			throw new NativeWalletException("Interrupted while waiting for native wallet operation: " + operationName + state, e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof NativeWalletException nativeWalletException)
				throw nativeWalletException;
			throw new NativeWalletException("Native wallet operation failed: " + operationName, cause);
		}
	}

	public boolean isDegraded() {
		return this.degraded.get();
	}

	public String getDegradedReason() {
		return this.degradedReason;
	}

	public boolean isBusy() {
		return this.executor.getActiveCount() > 0 || !this.executor.getQueue().isEmpty();
	}

	public int getQueueDepth() {
		return this.executor.getQueue().size();
	}

	public int getWorkerCount() {
		return this.executor.getPoolSize();
	}

	private <T> T executeDirect(String operationName, NativeOperation<T> operation) {
		try {
			return operation.execute(this.guardedAdapter);
		} catch (NativeWalletException e) {
			throw e;
		} catch (Exception e) {
			throw new NativeWalletException("Native wallet operation failed: " + operationName, e);
		}
	}

	private void ensureHealthy() {
		if (this.degraded.get())
			throw new NativeWalletException("Native wallet lane is degraded: " + this.degradedReason);
	}

	private void degrade(String reason) {
		this.degradedReason = reason;
		this.degraded.set(true);
		this.executor.shutdownNow();
	}

	@Override
	public void close() {
		this.executor.shutdownNow();
	}

	private final class NativeTask<T> {
		private final String operationName;
		private final NativeOperation<T> operation;
		private final AtomicBoolean started = new AtomicBoolean(false);

		private NativeTask(String operationName, NativeOperation<T> operation) {
			this.operationName = operationName;
			this.operation = operation;
		}

		private T run() {
			this.started.set(true);
			return executeDirect(this.operationName, this.operation);
		}
	}

	private final class GuardedNativeAdapter implements ZcashFamilyNativeAdapter {
		@Override
		public boolean isLoaded() {
			ensureHealthy();
			return adapter.isLoaded();
		}

		@Override
		public void loadLibrary(java.nio.file.Path path) {
			ensureHealthy();
			adapter.loadLibrary(path);
		}

		@Override
		public void initLogging() {
			ensureHealthy();
			adapter.initLogging();
		}

		@Override
		public String getSeedPhraseFromEntropyB64(String entropy64) {
			ensureHealthy();
			return adapter.getSeedPhraseFromEntropyB64(entropy64);
		}

		@Override
		public String getSeedPhraseFromEntropy(String entropy) {
			ensureHealthy();
			return adapter.getSeedPhraseFromEntropy(entropy);
		}

		@Override
		public String configureStorage(String baseDirectory, String passphrase) {
			ensureHealthy();
			return adapter.configureStorage(baseDirectory, passphrase);
		}

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			ensureHealthy();
			return adapter.invokeJson(requestJson, pretty);
		}

		@Override
		public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) {
			ensureHealthy();
			return adapter.initFromSeed(serverUri, params, seedPhrase, birthday, saplingOutput64, saplingSpend64);
		}

		@Override
		public String initFromB64(String serverUri, String params, String wallet64,
				String saplingOutput64, String saplingSpend64) {
			ensureHealthy();
			return adapter.initFromB64(serverUri, params, wallet64, saplingOutput64, saplingSpend64);
		}

		@Override
		public String save() {
			ensureHealthy();
			return adapter.save();
		}

		@Override
		public String execute(String command, String arguments) {
			ensureHealthy();
			return adapter.execute(command, arguments);
		}
	}

	public static class NativeWalletException extends RuntimeException {
		public NativeWalletException(String message) {
			super(message);
		}

		public NativeWalletException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
