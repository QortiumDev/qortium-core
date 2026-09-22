package org.qortium.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONObject;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;
import org.qortium.crosschain.ZcashFamilyWallet;
import org.qortium.crosschain.ZcashFamilyWalletConfig;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class ZcashFamilyWalletControllerLifecycleTests {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		PirateChainWalletController.resetForTesting();
	}

	@After
	public void afterTest() {
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		PirateChainWalletController.resetForTesting();
	}

	@Test
	public void testControllerStartsOnceAndTerminatesCleanly() throws Exception {
		TestController controller = new TestController();
		assertEquals(ZcashFamilyWalletController.LifecycleState.NEW, controller.getLifecycleState());

		assertTrue(controller.startController());
		assertTrue(controller.startController());
		assertEquals(ZcashFamilyWalletController.LifecycleState.RUNNING, controller.getLifecycleState());

		assertTrue(controller.shutdown());
		controller.join(2_000L);
		assertFalse(controller.isAlive());
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
		assertFalse(controller.startController());
	}

    @Test
    public void testQueuedContentionDoesNotStopController() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        TestController controller = new TestController() {
            @Override protected boolean isLibraryLoaded() {
                if (attempts.incrementAndGet() == 1)
                    throw new ZcashFamilyNativeCoordinator.NativeQueueContentionException("queued timeout", null);
                return true;
            }
        };
        setControllerField(controller, "shouldLoadWallet", true);
        controller.startController();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (attempts.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue("The loop retried after queued contention", attempts.get() >= 2);
        assertEquals(ZcashFamilyWalletController.LifecycleState.RUNNING, controller.getLifecycleState());
        assertFalse(controller.requiresCoreRestart());
        assertTrue(controller.shutdown());
    }

	@Test
	public void testFailedWalletShutdownFailsClosedUntilCoreRestart() throws Exception {
		TestController controller = new TestController();
		setControllerField(controller, "currentWallet", new FailedShutdownWallet(filledEntropy(7)));
		assertTrue(controller.startController());

		assertFalse(controller.shutdown());
		controller.join(2_000L);
		assertFalse(controller.isAlive());
		assertEquals(ZcashFamilyWalletController.LifecycleState.DEGRADED, controller.getLifecycleState());
		assertTrue(controller.requiresCoreRestart());
	}

	@Test
	public void testNormalShutdownWakesControllerWithoutThreadInterrupt() throws Exception {
		InterruptTrackingController controller = new InterruptTrackingController();
		assertTrue(controller.startController());

		assertTrue(controller.shutdown());
		controller.join(2_000L);
		assertFalse(controller.wasInterrupted());
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
	}

	@Test
	public void testPendingShutdownInterruptIsConsumedBeforeCleanup() {
		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		assertFalse(coordinator.isDegraded());

		TestController controller = new TestController();
		Thread testThread = Thread.currentThread();
		String originalName = testThread.getName();
		int originalPriority = testThread.getPriority();
		testThread.interrupt();
		try {
			// Calling run directly with a pending interrupt deterministically models shutdown
			// winning the race before the controller can enter an interruptible wait.
			controller.run();
			assertFalse(testThread.isInterrupted());
			assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
			assertFalse(coordinator.isDegraded());
		} finally {
			Thread.interrupted();
			testThread.setName(originalName);
			testThread.setPriority(originalPriority);
		}
	}

	@Test
	public void testOnlyRunningControllersAcceptWalletOperations() {
		assertFalse(ZcashFamilyWalletController.acceptsWalletOperations(ZcashFamilyWalletController.LifecycleState.NEW));
		assertTrue(ZcashFamilyWalletController.acceptsWalletOperations(ZcashFamilyWalletController.LifecycleState.RUNNING));
		assertFalse(ZcashFamilyWalletController.acceptsWalletOperations(ZcashFamilyWalletController.LifecycleState.STOPPING));
		assertFalse(ZcashFamilyWalletController.acceptsWalletOperations(ZcashFamilyWalletController.LifecycleState.TERMINATED));
		assertFalse(ZcashFamilyWalletController.acceptsWalletOperations(ZcashFamilyWalletController.LifecycleState.DEGRADED));
	}

	@Test
	public void testNewAndTerminatedControllersRejectWalletOperations() {
		TestController controller = new TestController();
		String entropy58 = Base58.encode(new byte[32]);

		assertThrows(ForeignBlockchainException.class,
				() -> controller.withEntropyWallet(entropy58, false, (wallet, nativeAdapter) -> null));
		controller.shutdown();
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
		assertThrows(ForeignBlockchainException.class,
				() -> controller.withEntropyWallet(entropy58, false, (wallet, nativeAdapter) -> null));
	}

	@Test
	public void testPirateSingletonDisabledAndRecreatedAfterTermination() throws Exception {
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
		assertNull(PirateChainWalletController.getInstance());

		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		PirateChainWalletController first = PirateChainWalletController.getInstance();
		assertTrue(first.startController());
		assertSame(first, PirateChainWalletController.getInstance());

		first.shutdown();
		first.join(2_000L);
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, first.getLifecycleState());

		PirateChainWalletController second = PirateChainWalletController.getInstance();
		assertNotSame(first, second);
		assertEquals(ZcashFamilyWalletController.LifecycleState.NEW, second.getLifecycleState());
		assertTrue(second.startController());
	}

	@Test
	public void testStatusUsesCacheWhileNativeLaneIsBusy() throws Exception {
		TestController controller = new TestController();
		assertTrue(controller.startController());

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<String> operation = caller.submit(() -> coordinator.execute("long status test operation", nativeAdapter -> {
				operationEntered.countDown();
				releaseOperation.await();
				return "done";
			}));
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

			for (int i = 0; i < 100; ++i)
				assertEquals("Not initialized yet", controller.getSyncStatus());

			assertEquals(0, coordinator.getQueueDepth());
			assertEquals(1, coordinator.getWorkerCount());
			releaseOperation.countDown();
			assertEquals("done", operation.get(2, TimeUnit.SECONDS));
		} finally {
			releaseOperation.countDown();
			caller.shutdownNow();
			controller.shutdown();
		}
	}

	@Test
	public void testStatusParserNormalizesLegacyAndUnifiedProgress() {
		ZcashFamilyWalletController.WalletSyncStatus legacy =
				ZcashFamilyWalletController.interpretNativeSyncStatus(new JSONObject()
						.put("syncing", "true")
						.put("synced_blocks", 12)
						.put("total_blocks", 30), false);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, legacy.getState());
		assertEquals("Sync in progress (12 / 30)", legacy.getMessage());
		assertEquals(Long.valueOf(12), legacy.getSyncedBlocks());
		assertEquals(Long.valueOf(30), legacy.getTotalBlocks());

		ZcashFamilyWalletController.WalletSyncStatus unified =
				ZcashFamilyWalletController.interpretNativeSyncStatus(new JSONObject()
						.put("in_progress", true), false);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, unified.getState());
		assertEquals("Sync in progress", unified.getMessage());
		assertNull(unified.getSyncedBlocks());

		ZcashFamilyWalletController.WalletSyncStatus ready =
				ZcashFamilyWalletController.interpretNativeSyncStatus(new JSONObject(), true);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, ready.getState());
		assertEquals("Synchronized", ready.getMessage());
	}

	@Test
	public void testWalletSyncStatusReadyRequiresFreshEvidence() {
		ZcashFamilyWalletController.WalletSyncStatus ready =
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized");
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, ready.getState());
		assertFalse(ready.isStale());

		// A pending/active recovery downgrades an otherwise-READY status: it is not fresh, successful
		// sync evidence.
		ZcashFamilyWalletController.WalletSyncStatus pendingRecovery = ready.withRecoveryMarker("PENDING");
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, pendingRecovery.getState());
		assertEquals("PENDING", pendingRecovery.getRecoveryState());

		// A completed recovery does not block readiness.
		ZcashFamilyWalletController.WalletSyncStatus recoveredReady = ready.withRecoveryMarker("RECOVERED");
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, recoveredReady.getState());

		// A cached/stale snapshot cannot be reported READY either.
		ZcashFamilyWalletController.WalletSyncStatus stale = ready.asStale();
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, stale.getState());
		assertTrue(stale.isStale());
		assertSame("asStale() is idempotent", stale, stale.asStale());

		// Missing absolute native fields (heights) also block readiness, independent of the relative
		// in-flight synced/total-block counters.
		ZcashFamilyWalletController.WalletSyncStatus missingHeights =
				ready.withSnapshot(null, 200L, "100000000", "90000000", "identityHash58");
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, missingHeights.getState());
		assertNull(missingHeights.getScannedHeight());
		assertEquals(Long.valueOf(200), missingHeights.getTipHeight());

		// Known heights preserve READY and carry the balances/identity through untouched.
		ZcashFamilyWalletController.WalletSyncStatus knownHeights =
				ready.withSnapshot(200L, 200L, "100000000", "90000000", "identityHash58");
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, knownHeights.getState());
		assertEquals(Long.valueOf(200), knownHeights.getScannedHeight());
		assertEquals(Long.valueOf(200), knownHeights.getTipHeight());
		assertEquals("100000000", knownHeights.getTotalBalanceAtomic());
		assertEquals("90000000", knownHeights.getVerifiedBalanceAtomic());
		assertEquals("identityHash58", knownHeights.getWalletIdentityHash());
		assertTrue("observedAt must be stamped", knownHeights.getObservedAt() > 0);

		// syncedBlocks/totalBlocks (relative counters) are untouched by snapshot enrichment.
		ZcashFamilyWalletController.WalletSyncStatus synchronizing =
				ZcashFamilyWalletController.WalletSyncStatus.synchronizing("Sync in progress (1 / 2)", 1L, 2L)
						.withSnapshot(100L, 200L, null, null, "identityHash58");
		assertEquals(Long.valueOf(1), synchronizing.getSyncedBlocks());
		assertEquals(Long.valueOf(2), synchronizing.getTotalBlocks());
		assertNull("unknown balances stay null, never a reinterpreted counter",
				synchronizing.getTotalBalanceAtomic());

		ZcashFamilyWalletController.WalletSyncStatus withError =
				ready.withLastError("NATIVE_LANE_DEGRADED", "Native wallet lane is degraded");
		assertEquals("NATIVE_LANE_DEGRADED", withError.getLastErrorCode());
		assertEquals("Native wallet lane is degraded", withError.getLastErrorMessage());
		assertNull(ready.getLastErrorCode());
	}

	@Test
	public void testPersistentSyncAcceptanceDoesNotReportReadyBeforeValidatedTip() {
		ZcashFamilyWalletController.WalletSyncStatus pending =
				ZcashFamilyWalletController.statusAfterSyncAttempt(true, true, true, false);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, pending.getState());

		ZcashFamilyWalletController.WalletSyncStatus validated =
				ZcashFamilyWalletController.statusAfterSyncAttempt(true, true, true, true);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, validated.getState());

		ZcashFamilyWalletController.WalletSyncStatus rejected =
				ZcashFamilyWalletController.statusAfterSyncAttempt(true, true, false, false);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.LOADING, rejected.getState());

		ZcashFamilyWalletController.WalletSyncStatus legacy =
				ZcashFamilyWalletController.statusAfterSyncAttempt(true, false, false, false);
		assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, legacy.getState());
	}

	@Test
	public void testPersistentIncompleteSyncIsReissuedAfterControllerRestart() throws Exception {
		TestController controller = new TestController();
		PersistentIncompleteWallet wallet = new PersistentIncompleteWallet(filledEntropy(9));
		RecordingNativeAdapter nativeAdapter = new RecordingNativeAdapter();

		assertTrue(wallet.isNativeSyncInProgress(nativeAdapter));
		assertTrue(controller.synchronizeCurrentWallet(wallet, nativeAdapter));
		assertEquals(1, nativeAdapter.syncRequests);
	}

	@Test
	public void testLoadedNativeWalletInitializationRearmsBackgroundSyncAfterControllerRestart() throws Exception {
		SyncRequestingController controller = new SyncRequestingController();
		RecordingNativeAdapter nativeAdapter = new RecordingNativeAdapter();
		String entropy58 = Base58.encode(filledEntropy(10));

		Method initialize = ZcashFamilyWalletController.class.getDeclaredMethod("initWithEntropy58",
				String.class, boolean.class, boolean.class, ZcashFamilyNativeAdapter.class);
		initialize.setAccessible(true);
		assertTrue((Boolean) initialize.invoke(controller, entropy58, false, false, nativeAdapter));

		Field shouldLoadWallet = ZcashFamilyWalletController.class.getDeclaredField("shouldLoadWallet");
		shouldLoadWallet.setAccessible(true);
		assertTrue("An already-loaded native lane must still re-arm the replacement controller's sync loop",
				shouldLoadWallet.getBoolean(controller));
	}

    @Test
    public void testOwnershipBlocksIdleBackgroundSelectionAndAllowsExplicitSwitch() throws Exception {
        OwnedController controller = new OwnedController();
        String a = Base58.encode(filledEntropy(21));
        String b = Base58.encode(filledEntropy(22));
        Method initialize = ZcashFamilyWalletController.class.getDeclaredMethod("initWithEntropy58",
                String.class, boolean.class, boolean.class, ZcashFamilyNativeAdapter.class);
        initialize.setAccessible(true);
        RecordingNativeAdapter adapter = new RecordingNativeAdapter();
        assertFalse((Boolean) initialize.invoke(controller, a, false, false, adapter));
        controller.explicit = true;
        assertTrue((Boolean) initialize.invoke(controller, a, false, false, adapter));
        controller.explicit = false;
        String revision = controller.owner.snapshot().revision();
        assertFalse((Boolean) initialize.invoke(controller, b, false, false, adapter));
        assertEquals(revision, controller.owner.snapshot().revision());
        assertThrows(ForeignBlockchainException.WalletBusyException.class, () -> controller.getSyncStatusDetails(b));
        assertThrows(ForeignBlockchainException.WalletBusyException.class,
                () -> controller.withEntropyWallet(b, false, (wallet, nativeAdapter) -> null));
        assertTrue((Boolean) initialize.invoke(controller, a, false, false, adapter));
        controller.explicit = true;
        assertTrue((Boolean) initialize.invoke(controller, b, false, false, adapter));
        controller.explicit = false;
        assertNotEquals(revision, controller.owner.snapshot().revision());
        assertFalse((Boolean) initialize.invoke(controller, a, false, false, adapter));
        assertThrows(ForeignBlockchainException.class, () -> controller.owner.requireRevision(revision));
    }

    private static class OwnedController extends SyncRequestingController {
        final ArrrWalletOwnership owner = new ArrrWalletOwnership();
        boolean explicit;
        @Override protected boolean explicitWalletSelection() { return explicit; }
        @Override protected void requireWalletOwner(String entropy, boolean nullSeed) throws ForeignBlockchainException {
            owner.requireOwner(Base58.encode(org.qortium.crypto.Crypto.digest(Base58.decode(entropy))));
        }
        @Override protected ZcashFamilyWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
            return new TestWallet(entropyBytes) { @Override public boolean save() { return true; } };
        }
        @Override protected void walletSelected(ZcashFamilyWallet wallet) {
            owner.selected(wallet.getWalletIdentityHash(), null);
        }
    }

	@Test
	public void testNotReadyWalletInitializationReportsFailure() throws Exception {
		NotReadyController controller = new NotReadyController();
		Method initialize = ZcashFamilyWalletController.class.getDeclaredMethod("initWithEntropy58",
				String.class, boolean.class, boolean.class, ZcashFamilyNativeAdapter.class);
		initialize.setAccessible(true);

		assertFalse((Boolean) initialize.invoke(controller, Base58.encode(filledEntropy(11)),
				false, false, new RecordingNativeAdapter()));
	}

	@Test
	public void testWalletSelectionUsesInitializationTimeoutButSteadyStatusStaysBounded() throws Exception {
		TestController controller = new TestController();
		byte[] entropyA = filledEntropy(1);
		byte[] entropyB = filledEntropy(2);

		assertEquals(ZcashFamilyNativeCoordinator.STATUS_TIMEOUT, controller.statusTimeoutFor(null));
		assertEquals(ZcashFamilyNativeCoordinator.DEFAULT_TIMEOUT,
				controller.statusTimeoutFor(Base58.encode(entropyA)));

		setControllerField(controller, "currentWallet", new TestWallet(entropyA));
		assertEquals(ZcashFamilyNativeCoordinator.STATUS_TIMEOUT,
				controller.statusTimeoutFor(Base58.encode(entropyA)));
		assertEquals(ZcashFamilyNativeCoordinator.DEFAULT_TIMEOUT,
				controller.statusTimeoutFor(Base58.encode(entropyB)));
	}

	@Test
	public void testBusyStatusNeverReturnsAnotherWalletsCachedReadyState() throws Exception {
		TestController controller = new TestController();
		assertTrue(controller.startController());
		byte[] entropyA = filledEntropy(1);
		byte[] entropyB = filledEntropy(2);
		TestWallet walletA = new TestWallet(entropyA);
		setControllerField(controller, "currentWallet", walletA);
		controller.cacheCurrentWalletStatus(
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<String> operation = caller.submit(() -> coordinator.execute("busy wallet identity test", nativeAdapter -> {
				operationEntered.countDown();
				releaseOperation.await();
				return "done";
			}));
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

			// A cached status for the SAME wallet is served, but flagged stale: it was produced while
			// the native lane was busy with other work, so a would-be READY is not fresh, successful
			// sync evidence and must not be reported as current.
			ZcashFamilyWalletController.WalletSyncStatus matching =
					controller.getSyncStatusDetails(Base58.encode(entropyA));
			assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, matching.getState());
			assertTrue(matching.isStale());

			// A different wallet's entropy must never receive wallet A's cached data — the controller
			// reports a structured busy signal instead.
			ForeignBlockchainException.WalletBusyException busy = assertThrows(
					ForeignBlockchainException.WalletBusyException.class,
					() -> controller.getSyncStatusDetails(Base58.encode(entropyB)));
			assertEquals("ARRR_WALLET_BUSY", busy.getMessage());

			releaseOperation.countDown();
			assertEquals("done", operation.get(2, TimeUnit.SECONDS));
		} finally {
			releaseOperation.countDown();
			caller.shutdownNow();
			controller.shutdown();
		}
	}

	@Test
	public void testBusyStatusRejectsCacheFromReplacedWallet() throws Exception {
		TestController controller = new TestController();
		assertTrue(controller.startController());
		byte[] entropyB = filledEntropy(2);
		TestWallet walletA = new TestWallet(filledEntropy(1));
		TestWallet walletB = new TestWallet(entropyB);
		setControllerField(controller, "currentWallet", walletA);
		controller.cacheCurrentWalletStatus(
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));
		setControllerField(controller, "currentWallet", walletB);

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<String> operation = caller.submit(() -> coordinator.execute("stale status cache test", nativeAdapter -> {
				operationEntered.countDown();
				releaseOperation.await();
				return "done";
			}));
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

			ForeignBlockchainException.WalletBusyException busy = assertThrows(
					ForeignBlockchainException.WalletBusyException.class,
					() -> controller.getSyncStatusDetails(Base58.encode(entropyB)));
			assertEquals("ARRR_WALLET_BUSY", busy.getMessage());

			releaseOperation.countDown();
			assertEquals("done", operation.get(2, TimeUnit.SECONDS));
		} finally {
			releaseOperation.countDown();
			caller.shutdownNow();
			controller.shutdown();
		}
	}

	@Test
	public void testSwitchBlockedByPriorNativeWorkReportsWalletBusy() throws Exception {
		TestController controller = new TestController();
		byte[] entropyA = filledEntropy(1);
		byte[] entropyB = filledEntropy(2);
		BusySwitchWallet walletA = new BusySwitchWallet(entropyA);
		setControllerField(controller, "currentWallet", walletA);

		Method initialize = ZcashFamilyWalletController.class.getDeclaredMethod("initWithEntropy58",
				String.class, boolean.class, boolean.class, ZcashFamilyNativeAdapter.class);
		initialize.setAccessible(true);

		assertFalse((Boolean) initialize.invoke(controller, Base58.encode(entropyB), false, false,
				new RecordingNativeAdapter()));

		Field initializationFailure = ZcashFamilyWalletController.class.getDeclaredField("initializationFailure");
		initializationFailure.setAccessible(true);
		assertEquals("ARRR_WALLET_BUSY", initializationFailure.get(controller));

		// The wallet still bound to A's entropy must never be swapped out for a half-initialized B.
		Field currentWallet = ZcashFamilyWalletController.class.getDeclaredField("currentWallet");
		currentWallet.setAccessible(true);
		assertSame(walletA, currentWallet.get(controller));
	}

	/**
	 * All four ARRR reads (walletaddress/walletbalance/wallettransactions/syncstatus) must report
	 * the structured busy signal - never queue behind wallet A's native work and never fall back to
	 * a generic failure - when requested for a different wallet B. walletaddress/walletbalance/
	 * wallettransactions are trivial {@link PirateChain} wrappers over the single shared
	 * {@link #withEntropyWallet} implementation exercised here directly (three separate call sites,
	 * zero additional busy-handling logic of their own); syncstatus is exercised through {@link
	 * #getSyncStatusDetails(String)}. Uses only public entry points plus the pre-existing
	 * setControllerField() field-reflection helper for test setup - no reflective private-method
	 * invocation.
	 */
	@Test
	public void testFourReadsAllReportWalletBusyForACrossWalletRequest() throws Exception {
		TestController controller = new TestController();
		assertTrue(controller.startController());
		byte[] entropyA = filledEntropy(1);
		byte[] entropyB = filledEntropy(2);
		String entropyB58 = Base58.encode(entropyB);
		TestWallet walletA = new TestWallet(entropyA);
		setControllerField(controller, "currentWallet", walletA);
		controller.cacheCurrentWalletStatus(ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<String> operation = caller.submit(() -> coordinator.execute("wallet A holds native work", nativeAdapter -> {
				operationEntered.countDown();
				releaseOperation.await();
				return "done";
			}));
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

			// walletaddress / walletbalance / wallettransactions share this one implementation.
			for (String label : new String[] {"walletaddress", "walletbalance", "wallettransactions"}) {
				long start = System.nanoTime();
				ForeignBlockchainException.WalletBusyException busy = assertThrows(
						ForeignBlockchainException.WalletBusyException.class,
						() -> controller.withEntropyWallet(entropyB58, false, (wallet, nativeAdapter) -> "unreachable"));
				assertEquals(label, "ARRR_WALLET_BUSY", busy.getMessage());
				// The preflight must fail fast, never queue behind A's still-running native work.
				assertTrue(label, Duration.ofNanos(System.nanoTime() - start).toMillis() < 1_000);
			}

			// syncstatus.
			ForeignBlockchainException.WalletBusyException syncStatusBusy = assertThrows(
					ForeignBlockchainException.WalletBusyException.class,
					() -> controller.getSyncStatusDetails(entropyB58));
			assertEquals("ARRR_WALLET_BUSY", syncStatusBusy.getMessage());

			// Wallet A itself is unaffected: same-wallet requests are ordinary in-flight operations,
			// not cross-wallet contention, so syncstatus for A still serves its (stale, cached) status.
			ZcashFamilyWalletController.WalletSyncStatus statusForA =
					controller.getSyncStatusDetails(Base58.encode(entropyA));
			assertTrue(statusForA.isStale());

			releaseOperation.countDown();
			assertEquals("done", operation.get(2, TimeUnit.SECONDS));
		} finally {
			releaseOperation.countDown();
			caller.shutdownNow();
			controller.shutdown();
		}
	}

	@Test
	public void testFailedWalletSelectionNeverReturnsAnotherWalletsCachedReadyState() throws Exception {
		TestController controller = new TestController();
		assertTrue(controller.startController());
		byte[] entropyA = filledEntropy(1);
		byte[] entropyB = filledEntropy(2);
		TestWallet walletA = new TestWallet(entropyA);
		setControllerField(controller, "currentWallet", walletA);
		controller.cacheCurrentWalletStatus(
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));

		try {
			ZcashFamilyWalletController.WalletSyncStatus status =
					controller.getSyncStatusDetails(Base58.encode(entropyB));
			assertEquals(ZcashFamilyWalletController.WalletSyncState.LOADING, status.getState());
			assertEquals("Test wallet isn't initialized yet", status.getMessage());
		} finally {
			controller.shutdown();
		}
	}

	@Test
	public void testWalletIdentityHashIsDeterministicAcrossInstancesAndRestart() throws Exception {
		byte[] entropy = filledEntropy(7);

		// Two independent wallet instances built from the same entropy - simulating the wallet
		// object being recreated after a Core restart - must report the same identity hash, since
		// it is a pure function of the entropy (matching the Base58(SHA-256(entropy)) directory
		// naming Core already uses on disk).
		TestWallet beforeRestart = new TestWallet(entropy);
		TestWallet afterRestart = new TestWallet(entropy);
		assertEquals(beforeRestart.getWalletIdentityHash(), afterRestart.getWalletIdentityHash());
		assertNotEquals(beforeRestart.getWalletIdentityHash(), new TestWallet(filledEntropy(8)).getWalletIdentityHash());
	}

	/**
	 * Controller-level enrichment coverage that goes through the public {@link
	 * #getSyncStatusDetails(String)} entry point rather than reflectively invoking any private
	 * method: the snapshot fields (heights/balances/identity) a cached status carries survive the
	 * round trip through the busy-cache-serving path with a matching identity, get correctly
	 * downgraded out of READY once served stale, and a wallet rebuilt from the same entropy after a
	 * simulated restart reports the identical identity the live snapshot carried.
	 */
	@Test
	public void testSyncStatusServesEnrichedSnapshotWithMatchingIdentityAcrossRestart() throws Exception {
		byte[] entropy = filledEntropy(9);
		String entropy58 = Base58.encode(entropy);

		TestController controller = new TestController();
		assertTrue(controller.startController());
		TestWallet wallet = new TestWallet(entropy);
		setControllerField(controller, "currentWallet", wallet);
		// Primes the cache the way a real fresh read (getSyncStatus()/enrichWithSnapshot()) would,
		// without requiring a loaded native library, which this sandbox does not have.
		controller.cacheCurrentWalletStatus(ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized")
				.withSnapshot(500L, 500L, "100000000", "90000000", wallet.getWalletIdentityHash()));

		ZcashFamilyNativeCoordinator coordinator = ZcashFamilyNativeCoordinator.getInstance();
		CountDownLatch operationEntered = new CountDownLatch(1);
		CountDownLatch releaseOperation = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		try {
			Future<String> operation = caller.submit(() -> coordinator.execute("enrichment snapshot test", nativeAdapter -> {
				operationEntered.countDown();
				releaseOperation.await();
				return "done";
			}));
			assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

			ZcashFamilyWalletController.WalletSyncStatus status = controller.getSyncStatusDetails(entropy58);
			assertEquals(wallet.getWalletIdentityHash(), status.getWalletIdentityHash());
			assertEquals(Long.valueOf(500), status.getScannedHeight());
			assertEquals(Long.valueOf(500), status.getTipHeight());
			assertEquals("100000000", status.getTotalBalanceAtomic());
			assertEquals("90000000", status.getVerifiedBalanceAtomic());
			// Served from cache while another native operation is in flight: stale, and READY must
			// be downgraded accordingly rather than reported as current.
			assertTrue(status.isStale());
			assertEquals(ZcashFamilyWalletController.WalletSyncState.SYNCHRONIZING, status.getState());

			releaseOperation.countDown();
			assertEquals("done", operation.get(2, TimeUnit.SECONDS));
		} finally {
			releaseOperation.countDown();
			caller.shutdownNow();
			controller.shutdown();
		}

		// "Restart": a brand-new wallet instance built from the same entropy (no shared Java object
		// identity with the pre-restart one) must report the identical identity hash the live
		// snapshot above carried.
		TestWallet restartedWallet = new TestWallet(entropy);
		assertEquals(wallet.getWalletIdentityHash(), restartedWallet.getWalletIdentityHash());
	}

	private static byte[] filledEntropy(int value) {
		byte[] entropy = new byte[32];
		Arrays.fill(entropy, (byte) value);
		return entropy;
	}

	private static void setControllerField(TestController controller, String fieldName, Object value)
			throws ReflectiveOperationException {
		Field field = ZcashFamilyWalletController.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(controller, value);
	}

	private static class TestController extends ZcashFamilyWalletController<ZcashFamilyWallet> {
		private static final ZcashFamilyWalletConfig TEST_CONFIG = new ZcashFamilyWalletConfig(
				"Test", "TEST", "Test", "signature", "TestEncryption", "zs", () -> 1, () -> null);

		private TestController() {
			super(TEST_CONFIG);
		}

		@Override
		protected ZcashFamilyWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
			return null;
		}
	}

	private static class SyncRequestingController extends TestController {
		@Override
		protected ZcashFamilyWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
			return new TestWallet(entropyBytes);
		}
	}

	private static class NotReadyController extends TestController {
		@Override
		protected ZcashFamilyWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
			return new NotReadyWallet(entropyBytes);
		}
	}

	private static class TestWallet extends ZcashFamilyWallet {
		private TestWallet(byte[] entropyBytes) throws IOException {
			super(TestController.TEST_CONFIG, entropyBytes, false, false, false);
			this.setReady(true);
		}

		@Override
		public boolean save() {
			return false;
		}
	}

	private static class NotReadyWallet extends ZcashFamilyWallet {
		private NotReadyWallet(byte[] entropyBytes) throws IOException {
			super(TestController.TEST_CONFIG, entropyBytes, false, false, false);
		}
	}

	private static class BusySwitchWallet extends TestWallet {
		private BusySwitchWallet(byte[] entropyBytes) throws IOException {
			super(entropyBytes);
		}

		@Override
		public boolean prepareForSwitch(ZcashFamilyNativeAdapter nativeAdapter) {
			return false;
		}
	}

	private static class FailedShutdownWallet extends TestWallet {
		private FailedShutdownWallet(byte[] entropyBytes) throws IOException {
			super(entropyBytes);
		}

		@Override
		public boolean prepareForShutdown(ZcashFamilyNativeAdapter nativeAdapter) {
			return false;
		}
	}

	private static class PersistentIncompleteWallet extends TestWallet {
		private PersistentIncompleteWallet(byte[] entropyBytes) throws IOException {
			super(entropyBytes);
		}

		@Override
		public boolean usesPersistentNativeStorage() {
			return true;
		}

		@Override
		public boolean isSynchronized() {
			return false;
		}

		@Override
		public boolean isNativeSyncInProgress(ZcashFamilyNativeAdapter nativeAdapter) {
			// Models v1.1.9 restoring an incomplete persisted sync state without a live
			// process-local task after Core restarts.
			return true;
		}
	}

	private static class RecordingNativeAdapter implements ZcashFamilyNativeAdapter {
		private int syncRequests;

		@Override
		public boolean isLoaded() {
			return true;
		}

		@Override
		public void loadLibrary(java.nio.file.Path path) {
		}

		@Override
		public void initLogging() {
		}

		@Override
		public String getSeedPhraseFromEntropyB64(String entropy64) {
			return null;
		}

		@Override
		public String getSeedPhraseFromEntropy(String entropy) {
			return null;
		}

		@Override
		public String configureStorage(String baseDirectory, String passphrase) {
			return null;
		}

		@Override
		public String invokeJson(String requestJson, boolean pretty) {
			return null;
		}

		@Override
		public String initFromSeed(String serverUri, String params, String seedPhrase, String birthday,
				String saplingOutput64, String saplingSpend64) {
			return null;
		}

		@Override
		public String initFromB64(String serverUri, String params, String wallet64,
				String saplingOutput64, String saplingSpend64) {
			return null;
		}

		@Override
		public String save() {
			return null;
		}

		@Override
		public String execute(String command, String arguments) {
			if ("sync".equalsIgnoreCase(command)) {
				++this.syncRequests;
				return "{\"result\":\"success\"}";
			}
			return "{}";
		}
	}

	private static class InterruptTrackingController extends TestController {
		private volatile boolean interrupted;

		@Override
		public void interrupt() {
			this.interrupted = true;
			super.interrupt();
		}

		private boolean wasInterrupted() {
			return this.interrupted;
		}
	}
}
