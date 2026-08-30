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
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

			ZcashFamilyWalletController.WalletSyncStatus matching =
					controller.getSyncStatusDetails(Base58.encode(entropyA));
			assertEquals(ZcashFamilyWalletController.WalletSyncState.READY, matching.getState());

			ZcashFamilyWalletController.WalletSyncStatus different =
					controller.getSyncStatusDetails(Base58.encode(entropyB));
			assertEquals(ZcashFamilyWalletController.WalletSyncState.LOADING, different.getState());
			assertEquals("Wallet status unavailable while another native operation is running", different.getMessage());

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

			ZcashFamilyWalletController.WalletSyncStatus status =
					controller.getSyncStatusDetails(Base58.encode(entropyB));
			assertEquals(ZcashFamilyWalletController.WalletSyncState.LOADING, status.getState());
			assertEquals("Wallet status unavailable while another native operation is running", status.getMessage());

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

	private static class FailedShutdownWallet extends TestWallet {
		private FailedShutdownWallet(byte[] entropyBytes) throws IOException {
			super(entropyBytes);
		}

		@Override
		public boolean prepareForShutdown(ZcashFamilyNativeAdapter nativeAdapter) {
			return false;
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
