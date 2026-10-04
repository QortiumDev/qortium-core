package org.qortium.crosschain.monero;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MoneroDaemonProbeTests {
    static String info(String extra) { return "{\"result\":{\"status\":\"OK\",\"mainnet\":true,\"synchronized\":true,\"offline\":false,\"height\":100"+extra+"}}"; }
    @Test public void healthRejectsWrongNetworkUnsyncedBehindAndOversizedReplies() throws Exception {
        var body = new java.util.concurrent.atomic.AtomicReference<>(info(""));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/json_rpc",exchange->{byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            MoneroDaemonProbe.check(url,100,false);
            assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,101,false));
            body.set(info("").replace("\"mainnet\":true","\"mainnet\":false"));assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            body.set(info("").replace("\"synchronized\":true","\"synchronized\":false"));assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            body.set(info("").replace("\"offline\":false","\"offline\":true"));assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,true));
            body.set(info(",\"nettype\":\"fakechain\"").replace("\"mainnet\":true","\"mainnet\":false").replace("\"offline\":false","\"offline\":true"));
            MoneroDaemonProbe.check(url,0,true);
            assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            body.set(info("").replace("\"mainnet\":true","\"mainnet\":\"true\""));
            assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            body.set(" ".repeat(65537)+info(""));assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
        } finally {server.stop(0);}
    }
    @Test public void redirectsNeverContactTheirTargetAndTimeoutIncludesBody() throws Exception {
        var target=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/target",e->{target.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        server.createContext("/json_rpc",e->{e.getResponseHeaders().add("Location","/target");e.sendResponseHeaders(302,-1);e.close();});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));assertEquals(0,target.get());
            server.removeContext("/json_rpc");server.createContext("/json_rpc",e->{e.sendResponseHeaders(200,0);try(var out=e.getResponseBody()){out.write('{');out.flush();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}}});
            long start=System.nanoTime();assertThrows(Exception.class,()->MoneroDaemonProbe.check(url,0,false));
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<8);
        } finally {release.countDown();server.stop(0);}
    }
    @Test public void adapterConfigurationCannotInjectCredentialsPathsOrUnsafeTransport() {
        for(String url:new String[]{"http://public.test","https://user:pass@public.test","https://public.test/path","https://public.test?url=http://evil.test","https://public.test#fragment"})
            assertThrows(IllegalArgumentException.class,()->new MoneroDaemonPool(java.util.List.of(url),false));
    }
}
