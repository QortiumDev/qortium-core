package org.qortium.crosschain;

import org.junit.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ZcashFamilyNativeCoordinatorTests {

	@Test
	public void testNativeOperationsAreSerialized() throws Exception {
		CountingAdapter adapter = new CountingAdapter();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		adapter.handler = command -> {
			order.add(command + "-start");
			if ("first".equals(command)) {
				firstEntered.countDown();
				releaseFirst.await();
			}
			order.add(command + "-end");
			return command;
		};

		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			ExecutorService callers = Executors.newFixedThreadPool(2);
			try {
				Future<String> first = callers.submit(() -> coordinator.execute("first", nativeAdapter ->
						nativeAdapter.execute("first", "")));
				assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
				Future<String> second = callers.submit(() -> coordinator.execute("second", nativeAdapter ->
						nativeAdapter.execute("second", "")));

				releaseFirst.countDown();
				assertEquals("first", first.get(2, TimeUnit.SECONDS));
				assertEquals("second", second.get(2, TimeUnit.SECONDS));
				assertEquals(1, adapter.maximumInFlight.get());
				assertEquals(List.of("first-start", "first-end", "second-start", "second-end"), order);
			} finally {
				callers.shutdownNow();
			}
		}
	}

	@Test
	public void testCompositeWalletOperationsCannotInterleave() throws Exception {
		CountingAdapter adapter = new CountingAdapter();
		CountDownLatch walletASelected = new CountDownLatch(1);
		CountDownLatch releaseWalletA = new CountDownLatch(1);
		AtomicReference<String> selectedWallet = new AtomicReference<>();

		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			ExecutorService callers = Executors.newFixedThreadPool(2);
			try {
				Future<String> walletA = callers.submit(() -> coordinator.execute("wallet A", nativeAdapter -> {
					selectedWallet.set("A");
					walletASelected.countDown();
					releaseWalletA.await();
					return selectedWallet.get();
				}));
				assertTrue(walletASelected.await(2, TimeUnit.SECONDS));

				Future<String> walletB = callers.submit(() -> coordinator.execute("wallet B", nativeAdapter -> {
					selectedWallet.set("B");
					return selectedWallet.get();
				}));
				releaseWalletA.countDown();

				assertEquals("A", walletA.get(2, TimeUnit.SECONDS));
				assertEquals("B", walletB.get(2, TimeUnit.SECONDS));
			} finally {
				callers.shutdownNow();
			}
		}
	}

	@Test
	public void testQueuedTimeoutDoesNotDegradeLane() throws Exception {
		CountingAdapter adapter = new CountingAdapter();
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			ExecutorService caller = Executors.newSingleThreadExecutor();
			try {
				Future<String> first = caller.submit(() -> coordinator.execute("running", nativeAdapter -> {
					running.countDown();
					release.await();
					return "done";
				}));
				assertTrue(running.await(2, TimeUnit.SECONDS));

				assertThrows(ZcashFamilyNativeCoordinator.NativeWalletException.class,
						() -> coordinator.execute("queued", Duration.ofMillis(50), nativeAdapter -> "never"));
				assertFalse(coordinator.isDegraded());

				release.countDown();
				assertEquals("done", first.get(2, TimeUnit.SECONDS));
				assertEquals("healthy", coordinator.execute("later", nativeAdapter -> "healthy"));
			} finally {
				caller.shutdownNow();
			}
		}
	}

	@Test
	public void testRunningTimeoutPermanentlyDegradesLane() throws Exception {
		CountingAdapter adapter = new CountingAdapter();
		CountDownLatch nativeEntered = new CountDownLatch(1);
		CountDownLatch releaseNative = new CountDownLatch(1);
		adapter.handler = command -> {
			nativeEntered.countDown();
			boolean released = false;
			while (!released) {
				try {
					released = releaseNative.await(20, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					// Model a native call that ignores Java interruption.
				}
			}
			return command;
		};

		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			assertThrows(ZcashFamilyNativeCoordinator.NativeWalletException.class,
					() -> coordinator.execute("hung", Duration.ofMillis(80), nativeAdapter -> {
						nativeAdapter.execute("first", "");
						return nativeAdapter.execute("must-not-run", "");
					}));
			assertTrue(nativeEntered.await(2, TimeUnit.SECONDS));
			assertTrue(coordinator.isDegraded());
			releaseNative.countDown();
			assertThrows(ZcashFamilyNativeCoordinator.NativeWalletException.class,
					() -> coordinator.execute("later", nativeAdapter -> "unsafe"));
			assertEquals(1, adapter.callCount.get());
		}
	}

	@Test
	public void testNestedOperationIsReentrant() {
		CountingAdapter adapter = new CountingAdapter();
		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			String result = coordinator.execute("outer", nativeAdapter ->
					coordinator.execute("inner", innerAdapter -> innerAdapter.execute("nested", "")));
			assertEquals("nested", result);
			assertEquals(1, adapter.callCount.get());
		}
	}

	@Test
	public void testRunningCallerInterruptionPermanentlyDegradesLane() throws Exception {
		CountingAdapter adapter = new CountingAdapter();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			Thread caller = new Thread(() -> {
				try {
					coordinator.execute("interruptible caller", nativeAdapter -> {
						operationEntered.countDown();
						boolean released = false;
						while (!released) {
							try {
								released = releaseOperation.await(20, TimeUnit.MILLISECONDS);
							} catch (InterruptedException e) {
								// Model native code that has not confirmed cancellation.
							}
						}
						return null;
					});
				} catch (Throwable e) {
					failure.set(e);
				}
			}, "Interrupted Native Caller Test");
			caller.start();
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));
			caller.interrupt();
			caller.join(2_000L);

			assertFalse(caller.isAlive());
			assertTrue(failure.get() instanceof ZcashFamilyNativeCoordinator.NativeWalletException);
			assertTrue(coordinator.isDegraded());
			releaseOperation.countDown();
			assertThrows(ZcashFamilyNativeCoordinator.NativeWalletException.class,
					() -> coordinator.execute("later", nativeAdapter -> "unsafe"));
		}
	}

	@Test
	public void testUnifiedSurfaceUsesGuardedNativeAdapter() {
		CountingAdapter adapter = new CountingAdapter();
		try (ZcashFamilyNativeCoordinator coordinator = coordinator(adapter)) {
			coordinator.execute("Unified surface", nativeAdapter -> {
				assertEquals("entropy", nativeAdapter.getSeedPhraseFromEntropy("entropy"));
				assertEquals("wallets/PirateChain", nativeAdapter.configureStorage("wallets/PirateChain", "passphrase"));
				assertEquals("{\"method\":\"info\"}", nativeAdapter.invokeJson("{\"method\":\"info\"}", false));
				return null;
			});

			assertEquals(List.of("entropy", "configure", "invoke"), adapter.unifiedCalls);
		}
	}

	private static ZcashFamilyNativeCoordinator coordinator(ZcashFamilyNativeAdapter adapter) {
		return new ZcashFamilyNativeCoordinator(adapter, "Native Coordinator Test");
	}

	@FunctionalInterface
	private interface ExecuteHandler {
		String execute(String command) throws Exception;
	}

	private static class CountingAdapter implements ZcashFamilyNativeAdapter {
		private final AtomicInteger callCount = new AtomicInteger();
		private final AtomicInteger inFlight = new AtomicInteger();
		private final AtomicInteger maximumInFlight = new AtomicInteger();
		private final List<String> unifiedCalls = new ArrayList<>();
		private ExecuteHandler handler = command -> command;

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void loadLibrary(Path path) {
		}

		@Override
		public void initLogging() {
		}

		@Override
		public String getSeedPhraseFromEntropyB64(String entropy64) {
			return entropy64;
		}

		@Override
		public String getSeedPhraseFromEntropy(String entropy) {
			this.unifiedCalls.add("entropy");
			return entropy;
		}

		@Override
		public String configureStorage(String baseDirectory, String passphrase) {
			this.unifiedCalls.add("configure");
			return baseDirectory;
		}

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			this.unifiedCalls.add("invoke");
			return requestJson;
		}

		@Override
		public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) {
			return seedPhrase;
		}

		@Override
		public String initFromB64(String serverUri, String params, String wallet64,
				String saplingOutput64, String saplingSpend64) {
			return wallet64;
		}

		@Override
		public String save() {
			return "wallet";
		}

		@Override
		public String execute(String command, String arguments) {
			this.callCount.incrementAndGet();
			int current = this.inFlight.incrementAndGet();
			this.maximumInFlight.accumulateAndGet(current, Math::max);
			try {
				return this.handler.execute(command);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				this.inFlight.decrementAndGet();
			}
		}
	}
}
