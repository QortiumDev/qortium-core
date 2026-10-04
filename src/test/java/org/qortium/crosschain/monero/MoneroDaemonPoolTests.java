package org.qortium.crosschain.monero;
import org.junit.Test;
import org.qortium.crosschain.WalletServerPool;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
public class MoneroDaemonPoolTests {
    @Test public void adapterSkipsFailedProbeAndStickyConnectionThenWaitsWhenAllFail() throws Exception {
        var clock=new AtomicLong(1);var calls=new ArrayList<String>();
        var daemons=new MoneroDaemonPool(new WalletServerPool(List.of("https://a.test","https://b.test"),
                Duration.ofSeconds(10),Duration.ofMinutes(5),clock::get,()->1000),false);
        daemons.connect(endpoint->{calls.add(endpoint);if(endpoint.contains("a.test"))throw new IllegalStateException("private diagnostic");});
        assertEquals(List.of("https://a.test","https://b.test"),calls);
        daemons.succeeded();daemons.connect(endpoint->{throw new AssertionError("Sticky connection must not probe again");});
        var failure=daemons.failed();assertEquals(10000,failure.delayMillis);
        assertThrows(MoneroDaemonPool.Unavailable.class,()->daemons.connect(endpoint->{throw new AssertionError("Cooldown must not connect");}));
        clock.addAndGet(Duration.ofSeconds(10).toNanos());daemons.connect(calls::add);daemons.succeeded();
        assertEquals("server-2",daemons.pool.status().selectedId());
        daemons.resetConnection();daemons.connect(calls::add);assertEquals(4,calls.size());
    }
    @Test public void settingsPreserveLegacyPrimaryAndDeduplicateExplicitFallbacks() throws Exception {
        org.qortium.test.common.Common.useDefaultSettings();var settings=org.qortium.settings.Settings.getInstance();
        var legacy=org.qortium.settings.Settings.class.getDeclaredField("moneroDaemonUri");legacy.setAccessible(true);
        var list=org.qortium.settings.Settings.class.getDeclaredField("moneroDaemonUris");list.setAccessible(true);
        try {
            legacy.set(settings,"https://a.test");list.set(settings,List.of("https://a.test","https://b.test"));
            assertEquals(List.of("https://a.test","https://b.test"),settings.getMoneroDaemonUris());
            list.set(settings,null);assertThrows(IllegalArgumentException.class,settings::getMoneroDaemonUris);
            list.set(settings,java.util.Arrays.asList((String)null));assertThrows(IllegalArgumentException.class,settings::getMoneroDaemonUris);
            list.set(settings,List.of());assertEquals(List.of("https://a.test"),settings.getMoneroDaemonUris());
            legacy.set(settings,null);assertThrows(IllegalArgumentException.class,()->MoneroJniWallet.factory(java.nio.file.Path.of("unused"),settings.getMoneroDaemonUris()));
            list.set(settings,List.of("https://1.test","https://2.test","https://3.test","https://4.test","https://5.test","https://6.test","https://7.test","https://8.test"));legacy.set(settings,"https://a.test");
            assertThrows(IllegalArgumentException.class,()->MoneroJniWallet.factory(java.nio.file.Path.of("unused"),settings.getMoneroDaemonUris()));
        } finally {legacy.set(settings,null);list.set(settings,List.of());}
    }
}
