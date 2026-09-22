package org.qortium.controller;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.*;
import org.qortium.crosschain.*;
import org.qortium.crypto.Crypto;
import org.qortium.test.common.Common;
import org.qortium.utils.Base58;
import static org.junit.Assert.*;


public class PirateWalletSessionTests {
    private FakeController controller;
    private static String entropy(int value) { byte[] bytes = new byte[32]; Arrays.fill(bytes,(byte)value); return Base58.encode(bytes); }
    private static String identity(String entropy) { return Base58.encode(Crypto.digest(Base58.decode(entropy))); }
    @Before public void before() throws Exception {
        Common.useDefaultSettings();
        PirateChainWalletController.resetForTesting();
        controller = new FakeController();
        Field field = PirateChainWalletController.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null,controller);
    }
    @After public void after() { PirateChainWalletController.resetForTesting(); }

    @Test public void passiveStatusAndAddressAreAccountBoundAcrossStopStart() throws Exception {
        String a=entropy(31),b=entropy(32);
        var empty=PirateChainWalletController.walletSession(a);
        assertEquals("NONE",empty.relation); assertNull(empty.address); assertEquals(0,controller.created);
        var selected=PirateChainWalletController.activateWallet(a,empty.revision);
        assertEquals("SELF",selected.relation); assertNotNull(selected.address);
        assertEquals("OTHER",PirateChainWalletController.walletSession(b).relation);
        assertNull(PirateChainWalletController.walletSession(b).address);
        assertThrows(ForeignBlockchainException.WalletBusyException.class,()->controller.getSyncStatusDetails(b));
        assertThrows(ForeignBlockchainException.WalletBusyException.class,()->controller.withEntropyWallet(b,false,(w,n)->null));
        assertEquals(1,controller.created);
        PirateChainWalletController.stopWallet();
        assertEquals("SELF",PirateChainWalletController.walletSession(a).relation);
        assertEquals(selected.address,PirateChainWalletController.walletSession(a).address);
        assertNotEquals(selected.revision,PirateChainWalletController.walletSession(a).revision);
        PirateChainWalletController.startInstance();
        assertEquals("OTHER",PirateChainWalletController.walletSession(b).relation);
    }

    @Test public void concurrentApprovalsHaveOneWinner() throws Exception {
        String revision=PirateChainWalletController.walletSession(entropy(1)).revision;
        ExecutorService threads=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            java.util.List<Future<Boolean>> futures=new java.util.ArrayList<>();
            for (int i=1;i<=2;i++) { final int id=i; futures.add(threads.submit(()->{
                start.await();
                try { PirateChainWalletController.activateWallet(entropy(id),revision); return true; }
                catch(ForeignBlockchainException e) { assertTrue(e.getMessage().contains("ARRR_SESSION_CHANGED")); return false; }
            })); }
            start.countDown(); int wins=0; for(var result:futures) if(result.get(3,TimeUnit.SECONDS)) wins++;
            assertEquals(1,wins);assertEquals(1,controller.created);
        } finally { threads.shutdownNow(); }
    }

    @Test public void transientNullWalletDoesNotTakeCustodyOwnership() throws Exception {
        String a=entropy(7); var active=PirateChainWalletController.activateWallet(a,PirateChainWalletController.walletSession(a).revision);
        Method init=ZcashFamilyWalletController.class.getDeclaredMethod("initWithEntropy58",String.class,boolean.class,boolean.class,ZcashFamilyNativeAdapter.class);
        init.setAccessible(true);
        assertTrue((Boolean)init.invoke(controller,entropy(0),true,false,controller.adapter));
        assertEquals("SELF",PirateChainWalletController.walletSession(a).relation);
        assertEquals(active.revision,PirateChainWalletController.walletSession(a).revision);
        assertTrue((Boolean)init.invoke(controller,a,false,false,controller.adapter));
        assertEquals(active.revision,PirateChainWalletController.walletSession(a).revision);
    }

    private static class FakeController extends PirateChainWalletController {
        int created;
        final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("arrr-session-test-");
        final ZcashFamilyNativeAdapter adapter=(ZcashFamilyNativeAdapter) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{ZcashFamilyNativeAdapter.class},
                (proxy,method,args)->method.getName().equals("isLoaded") ? true : null);
        FakeController() throws Exception {
            Field field=ZcashFamilyWalletController.class.getDeclaredField("lifecycleState");field.setAccessible(true);field.set(this,LifecycleState.RUNNING);
        }
        @Override public synchronized boolean startController(){return true;}
        @Override public boolean shutdown(){return true;}
        @Override protected <T>T executeChecked(String name,ZcashFamilyNativeCoordinator.NativeOperation<T> operation)throws ForeignBlockchainException {
            try{return operation.execute(adapter);}catch(ForeignBlockchainException e){throw e;}catch(Exception e){throw new ForeignBlockchainException(e.getMessage());}
        }
        @Override protected PirateWallet createWallet(byte[] bytes,boolean nullSeed,boolean tip)throws java.io.IOException {
            created++;
            return new PirateSessionTestWallet(bytes,nullSeed,root);
        }
    }
}
