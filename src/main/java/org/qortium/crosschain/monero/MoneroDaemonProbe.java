package org.qortium.crosschain.monero;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Public metadata only. No credentials/redirects; standard TLS, bounded entire response and request deadline. */
final class MoneroDaemonProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static void check(String endpoint, long minimumHeight, boolean regtest) throws Exception {
        MoneroJniWallet.validateDaemon(endpoint);
        var request = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/$", "") + "/json_rpc"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":\"health\",\"method\":\"get_info\"}")).build();
        var pending = HTTP.sendAsync(request, ignored -> new LimitedBody());
        HttpResponse<byte[]> response;
        try { response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) {
            pending.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() != 200) throw new IllegalStateException("XMR daemon unavailable");
        var body = JSON.readTree(response.body());
        var info = body == null ? null : body.get("result");
        if (body == null || body.hasNonNull("error") || info == null || !info.isObject()
                || !"OK".equals(info.path("status").asText())
                || (regtest ? !"fakechain".equals(info.path("nettype").asText()) || !info.path("offline").isBoolean() || !info.path("offline").booleanValue()
                        : !info.path("mainnet").isBoolean() || !info.path("mainnet").booleanValue()
                            || !info.path("offline").isBoolean() || info.path("offline").booleanValue()
                            || !info.path("synchronized").isBoolean() || !info.path("synchronized").booleanValue())
                || !info.path("height").isIntegralNumber() || !info.path("height").canConvertToLong()
                || info.path("height").asLong() <= 0 || info.path("height").asLong() > 500_000_000L
                || info.path("height").asLong() < minimumHeight)
            throw new IllegalStateException("XMR daemon unavailable");
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("XMR metadata too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
