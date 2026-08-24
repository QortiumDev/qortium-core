package org.qortium.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.crosschain.PirateChain;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;
import org.qortium.crosschain.ZcashFamilyWallet;
import org.qortium.crosschain.ZcashFamilyWalletConfig;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;

import java.io.IOException;
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

		controller.shutdown();
		controller.join(2_000L);
		assertFalse(controller.isAlive());
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
		assertFalse(controller.startController());
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

	private static class TestController extends ZcashFamilyWalletController<ZcashFamilyWallet> {
		private TestController() {
			super(new ZcashFamilyWalletConfig("Test", "TEST", "Test", "signature",
					"TestEncryption", "zs", () -> 1, () -> null));
		}

		@Override
		protected ZcashFamilyWallet createWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
			return null;
		}
	}
}
