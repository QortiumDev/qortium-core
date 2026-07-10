package org.qortium.notification;

import java.util.List;
import java.util.Map;

/**
 * A normalized event produced by a hook point (metadata save, transaction, etc.)
 * and consumed by the NotificationManager dispatcher.
 *
 * <p>An optional {@code dedupKey} can be supplied. When present, the
 * NotificationManager will suppress duplicate dispatches of the same
 * {@code type + dedupKey} within {@code DEDUP_WINDOW_MS} milliseconds.
 * Use the transaction signature (Base58) as the dedup key for tx-based events.
 */
public class NotificationEvent {

    private final String type;
    private final Map<String, ?> data;
    private final String dedupKey;
    private final List<String> involvedAddresses;

    public NotificationEvent(String type, Map<String, ?> data) {
        this(type, data, null, null);
    }

    public NotificationEvent(String type, Map<String, ?> data, String dedupKey) {
        this(type, data, dedupKey, null);
    }

    public NotificationEvent(String type, Map<String, ?> data, String dedupKey, List<String> involvedAddresses) {
        this.type = type;
        this.data = data;
        this.dedupKey = dedupKey;
        this.involvedAddresses = involvedAddresses;
    }

    public String getType() {
        return type;
    }

    public Map<String, ?> getData() {
        return data;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public List<String> getInvolvedAddresses() {
        return involvedAddresses;
    }
}
