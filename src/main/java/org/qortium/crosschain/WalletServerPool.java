package org.qortium.crosschain;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.function.LongSupplier;

/** Key-free read-provider selection. Adapters validate endpoints; callers serialize native connection changes. */
public final class WalletServerPool {
    public record Server(String id, String endpoint) { }
    public record Health(String id, String endpoint, String state, int failures, Long retryAt) { }
    public record Status(String selectedId, List<Health> servers, Long retryAt) { }
    private final List<Server> servers;
    private final int[] failures;
    private final long[] retryNanos;
    private final boolean[] healthy;
    private final LongSupplier monotonic, wallClock;
    private final long baseNanos, maxNanos;
    private int selected;

    public WalletServerPool(List<String> endpoints) {
        this(endpoints, Duration.ofSeconds(10), Duration.ofMinutes(5), System::nanoTime, System::currentTimeMillis);
    }
    public WalletServerPool(List<String> endpoints, Duration base, Duration max, LongSupplier monotonic, LongSupplier wallClock) {
        if (endpoints == null || endpoints.isEmpty() || endpoints.size() > 8 || endpoints.stream().anyMatch(java.util.Objects::isNull)
                || endpoints.stream().distinct().count() != endpoints.size() || base.isNegative() || base.isZero() || max.compareTo(base) < 0)
            throw new IllegalArgumentException("Invalid wallet server pool");
        var configured = new ArrayList<Server>();
        for (int i = 0; i < endpoints.size(); i++) configured.add(new Server("server-" + (i + 1), endpoints.get(i)));
        this.servers = List.copyOf(configured);
        failures = new int[servers.size()]; retryNanos = new long[servers.size()]; healthy = new boolean[servers.size()];
        this.monotonic = monotonic; this.wallClock = wallClock; baseNanos = base.toNanos(); maxNanos = max.toNanos();
    }
    /** Sticky success; failed providers are skipped until their own cooldown expires. */
    public synchronized Server candidate() {
        long now = monotonic.getAsLong();
        for (int offset = 0; offset < servers.size(); offset++) {
            int index = (selected + offset) % servers.size();
            if (failures[index] == 0 || now - retryNanos[index] >= 0) {
                selected = index; return servers.get(index);
            }
        }
        return null;
    }
    public synchronized void succeeded(Server server) {
        int index = index(server); failures[index] = 0; retryNanos[index] = 0; healthy[index] = true;
    }
    public synchronized void failed(Server server) {
        int index = index(server);
        failures[index] = Math.min(failures[index] + 1, 30); healthy[index] = false;
        long delay = baseNanos;
        for (int i = 1; i < failures[index] && delay < maxNanos; i++) delay = delay > maxNanos / 2 ? maxNanos : Math.min(maxNanos, delay * 2);
        retryNanos[index] = monotonic.getAsLong() + delay;
    }
    private int index(Server server) {
        int index = servers.indexOf(server);
        if (index < 0) throw new IllegalArgumentException("Unknown configured server");
        return index;
    }
    public synchronized long retryDelayMillis() {
        long now = monotonic.getAsLong(), remaining = Long.MAX_VALUE;
        for (int i = 0; i < servers.size(); i++) {
            if (failures[i] == 0 || now - retryNanos[i] >= 0) return 0;
            remaining = Math.min(remaining, retryNanos[i] - now);
        }
        return Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
    }
    public synchronized Status status() {
        long now = monotonic.getAsLong(), wall = wallClock.getAsLong();
        var result = new ArrayList<Health>();
        for (int i = 0; i < servers.size(); i++) {
            var server = servers.get(i);
            long remaining = failures[i] == 0 ? 0 : Math.max(0, retryNanos[i] - now);
            result.add(new Health(server.id(), server.endpoint(), healthy[i] ? "HEALTHY" : failures[i] == 0 ? "UNTESTED" : "UNAVAILABLE",
                    failures[i], remaining == 0 ? null : wall + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
        long delay = retryDelayMillis();
        return new Status(servers.get(selected).id(), List.copyOf(result), delay == 0 ? null : wall + delay);
    }
}
