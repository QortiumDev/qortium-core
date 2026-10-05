package org.qortium.crosschain;

import org.junit.Test;
import static org.junit.Assert.*;

public class WalletScanHistoryTests {
    @Test public void denseCallbacksAreBoundedAndSnapshotsImmutable() {
        var tracker = new WalletScanHistory.Tracker();
        for (int i = 0; i <= 200_000; i++) tracker.record("A", i * 10L, i, 500_000);
        var history = tracker.snapshot();
        assertTrue(history.samples().size() <= 92);
        assertEquals(200_000, history.samples().get(history.samples().size() - 1).blocks());
        assertThrows(UnsupportedOperationException.class, () -> history.samples().clear());
        tracker.clear(); assertNull(tracker.snapshot()); assertFalse(history.samples().isEmpty());
    }
    @Test public void identityRangeClockAndGapBoundariesReset() {
        var tracker = new WalletScanHistory.Tracker();
        tracker.record("A", 1000, 10, 100); tracker.record("A", 121000, 20, 100);
        assertEquals(2, tracker.snapshot().samples().size());
        tracker.record("A", 121000, 20, 100); assertEquals(2, tracker.snapshot().samples().size());
        tracker.record("B", 140000, 30, 100); assertEquals("B", tracker.snapshot().identity()); assertEquals(1, tracker.snapshot().samples().size());
        tracker.record("B", 141000, 1, 100); assertEquals(1, tracker.snapshot().samples().size());
        tracker.record("B", 140000, 2, 100); assertEquals(1, tracker.snapshot().samples().size());
        tracker.record("B", 1040001, 3, 100); assertEquals(1, tracker.snapshot().samples().size());
        tracker.record("B", 1100000, 3, 50); assertEquals(1, tracker.snapshot().samples().size());
        tracker.record("B", 1200000, 60, 50); assertEquals(1, tracker.snapshot().samples().size());
    }
    @Test public void sharedHistoryHasTheSameJsonShapeThroughJacksonAndMoxy() throws Exception {
        var tracker = new WalletScanHistory.Tracker(); tracker.record("synthetic", 1000, 10, 100);
        var status = new org.qortium.api.model.crosschain.PirateChainSyncStatus(); status.scanHistory = tracker.snapshot();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var expected = json.readTree(json.writeValueAsString(status)).get("scanHistory");
        var context = org.eclipse.persistence.jaxb.JAXBContextFactory.createContext(new Class<?>[]{status.getClass()}, java.util.Map.of());
        var marshaller = context.createMarshaller();
        marshaller.setProperty(org.eclipse.persistence.jaxb.MarshallerProperties.MEDIA_TYPE, "application/json");
        marshaller.setProperty(org.eclipse.persistence.jaxb.MarshallerProperties.JSON_INCLUDE_ROOT, false);
        var out = new java.io.StringWriter(); marshaller.marshal(status, out);
        assertEquals(expected, json.readTree(out.toString()).get("scanHistory"));
        assertEquals(10, expected.get("samples").get(0).get("blocks").asInt());
    }
}
