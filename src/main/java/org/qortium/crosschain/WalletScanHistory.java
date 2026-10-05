package org.qortium.crosschain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Bounded backend observations for display estimates only; never wallet authority or readiness. */
@XmlAccessorType(XmlAccessType.FIELD)
public final class WalletScanHistory {
    public final String identity;
    public final List<Sample> samples;
    public WalletScanHistory() { this(null, List.of()); } // JAXB serialization
    public WalletScanHistory(String identity, List<Sample> samples) { this.identity = identity; this.samples = List.copyOf(samples); }
    public String identity() { return identity; }
    public List<Sample> samples() { return samples; }
    @XmlAccessorType(XmlAccessType.FIELD)
    public static final class Sample {
        public final long at, blocks, total;
        public Sample() { this(0, 0, 0); } // JAXB serialization
        public Sample(long at, long blocks, long total) { this.at = at; this.blocks = blocks; this.total = total; }
        public long at() { return at; }
        public long blocks() { return blocks; }
        public long total() { return total; }
    }

    /** Called only by the existing native observation producer, never a cached status getter. */
    public static final class Tracker {
        private static final long WINDOW = 30 * 60_000L, MAX_GAP = 15 * 60_000L, INTERVAL = 20_000L;
        private String identity;
        private final ArrayList<Sample> anchors = new ArrayList<>();
        private Sample tail;
        public void clear() { identity = null; anchors.clear(); tail = null; }
        public void record(String owner, long at, long blocks, long total) {
            if (owner == null || at < 0 || blocks < 0 || total <= 0 || blocks > total || total > 500_000_000L) return;
            if (!Objects.equals(identity, owner) || (tail != null &&
                    (at < tail.at() || at - tail.at() > MAX_GAP || blocks < tail.blocks() || total < tail.total()))) {
                clear();
            }
            identity = owner;
            if (tail != null && at == tail.at()) return;
            Sample next = new Sample(at, blocks, total);
            anchors.removeIf(sample -> at - sample.at() > WINDOW);
            if (anchors.isEmpty() || at - anchors.get(anchors.size() - 1).at() >= INTERVAL) anchors.add(next);
            tail = next;
        }
        public WalletScanHistory snapshot() {
            if (tail == null) return null;
            ArrayList<Sample> result = new ArrayList<>(anchors);
            if (result.isEmpty() || result.get(result.size() - 1).at() != tail.at()) result.add(tail);
            return new WalletScanHistory(identity, result);
        }
    }
}
