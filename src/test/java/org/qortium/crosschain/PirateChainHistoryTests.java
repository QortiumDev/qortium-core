package org.qortium.crosschain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PirateChainHistoryTests {
    private JSONObject row() {
        return new JSONObject().put("txid", "abcd").put("datetime", 1_700_000_000L)
                .put("has_outgoing", true).put("metadata_complete", true)
                .put("outgoing_value", "100").put("fee", "7")
                .put("incoming_metadata", new JSONArray()).put("outgoing_metadata", new JSONArray());
    }

    private SimpleTransaction parse(JSONObject row) {
        return PirateChainHistory.parse(new JSONArray().put(row), "mine", true).get(0);
    }

    @Test public void unknownPendingAndKnownRowsSurviveTogether() {
        JSONObject unknown = row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)
                .put("fee", JSONObject.NULL).put("unconfirmed", true);
        List<SimpleTransaction> rows = PirateChainHistory.parse(new JSONArray().put(unknown).put(row()), "mine", true);
        assertEquals(2, rows.size());
        assertNull(rows.get(0).getTotalAmount());
        assertNull(rows.get(0).getFeeAmount());
        assertTrue(rows.get(0).getPending());
        assertFalse(rows.get(0).getMetadataComplete());
        assertEquals(Long.valueOf(-100), rows.get(1).getTotalAmount());
    }

    @Test public void estimatesNeverBecomeExactAmounts() {
        SimpleTransaction tx = parse(row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)
                .put("outgoing_value_estimate", "99").put("fee", JSONObject.NULL).put("fee_estimate", "10"));
        assertNull(tx.getTotalAmount());
        assertEquals(Long.valueOf(-99), tx.getTotalAmountEstimate());
        assertNull(tx.getFeeAmount());
        assertEquals(Long.valueOf(10), tx.getFeeAmountEstimate());
    }

    @Test public void partialRecipientsDoNotDefineTheTotal() {
        SimpleTransaction tx = parse(row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)
                .put("outgoing_metadata", new JSONArray().put(new JSONObject().put("value", 40).put("address", "recipient"))));
        assertNull(tx.getTotalAmount());
        assertEquals(40, tx.getOutputs().get(0).getAmount());
    }

    @Test public void feeIsChargedOnceForMultipleRecipientsAndKnownZero() {
        JSONObject tx = row().put("outgoing_metadata", new JSONArray()
                .put(new JSONObject().put("value", 40).put("address", "first"))
                .put(new JSONObject().put("value", 60).put("address", "second")));
        assertEquals(Long.valueOf(7), parse(tx).getFeeAmount());
        assertEquals(Long.valueOf(7), PirateChainHistory.parse(new JSONArray().put(tx), "mine", false).get(0).getFeeAmount());
        assertEquals(Long.valueOf(0), parse(row().put("outgoing_value", "0")).getTotalAmount());
    }

    @Test public void malformedOrOverflowingAmountsAreRejected() {
        for (String value : List.of("1.5", "-1", "9223372036854775808"))
            assertThrows(RuntimeException.class, () -> parse(row().put("outgoing_value", value)));
        assertThrows(RuntimeException.class, () -> parse(row().put("datetime", Long.MAX_VALUE)));
    }

    @Test public void incomingSelfReceiptOffsetsOutgoingButSenderFeeIsNotChargedOnReceives() {
        JSONObject incoming = new JSONObject().put("value", 100).put("address", "mine");
        assertEquals(Long.valueOf(0), parse(row().put("incoming_metadata", new JSONArray().put(incoming))).getTotalAmount());
        SimpleTransaction tx = parse(row().put("has_outgoing", false).put("outgoing_value", "0")
                .put("incoming_metadata", new JSONArray().put(incoming)));
        assertEquals(Long.valueOf(100), tx.getTotalAmount());
        assertEquals(Long.valueOf(0), tx.getFeeAmount());
    }

    private ZcashFamilyNativeAdapter adapter(String response, AtomicInteger legacy) {
        return (ZcashFamilyNativeAdapter) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ZcashFamilyNativeAdapter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("invokeJson")) {
                        JSONObject request = new JSONObject((String) args[0]);
                        assertEquals("qortal_list_transactions_partial", request.getString("method"));
                        assertEquals("active-wallet", request.getString("wallet_id"));
                        return response;
                    }
                    if (method.getName().equals("execute")) {
                        assertEquals("list", args[0]);
                        legacy.incrementAndGet();
                        return "[]";
                    }
                    throw new AssertionError("Unexpected native call: " + method);
                });
    }

    @Test public void productionReadUsesJsonBridgeAndPreservesPartialRow() throws Exception {
        AtomicInteger legacy = new AtomicInteger();
        String response = new JSONObject().put("ok", true).put("result", new JSONObject().put("transactions",
                new JSONArray().put(row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)))).toString();
        List<SimpleTransaction> result = PirateChainHistory.read(adapter(response, legacy), true, "active-wallet", "mine");
        assertEquals(1, result.size());
        assertNull(result.get(0).getTotalAmount());
        assertEquals(0, legacy.get());
    }

    @Test public void onlyUnsupportedMethodAllowsLegacyFallback() throws Exception {
        AtomicInteger legacy = new AtomicInteger();
        String unsupported = new JSONObject().put("ok", false).put("error",
                "Invalid request JSON: unknown variant `qortal_list_transactions_partial`, expected ...").toString();
        assertTrue(PirateChainHistory.read(adapter(unsupported, legacy), true, "active-wallet", "mine").isEmpty());
        assertEquals(1, legacy.get());
        for (String response : List.of("{}", "not json", "{\"ok\":false,\"error\":\"wallet failed\"}"))
            assertThrows(ForeignBlockchainException.class, () -> PirateChainHistory.read(adapter(response, legacy), true, "active-wallet", "mine"));
        assertEquals(1, legacy.get());
    }
    @Test public void apiSerializationPreservesUnknownAndEstimatedAmounts() throws Exception {
        JSONObject unknown = row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)
                .put("fee", JSONObject.NULL).put("unconfirmed", true);
        JSONObject estimated = row().put("metadata_complete", false).put("outgoing_value", JSONObject.NULL)
                .put("outgoing_value_estimate", "100000000").put("fee", JSONObject.NULL).put("fee_estimate", "10000");
        List<SimpleTransaction> rows = PirateChainHistory.parse(new JSONArray().put(unknown).put(estimated).put(row()), "mine", true);
        JSONArray wireRows = new JSONArray();
        org.eclipse.persistence.jaxb.rs.MOXyJsonProvider provider = new org.eclipse.persistence.jaxb.rs.MOXyJsonProvider();
        provider.setIncludeRoot(false);
        for (SimpleTransaction tx : rows) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            provider.writeTo(tx, SimpleTransaction.class, SimpleTransaction.class, new java.lang.annotation.Annotation[0],
                    javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE, new javax.ws.rs.core.MultivaluedHashMap<>(), bytes);
            wireRows.put(new JSONObject(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertTrue(wireRows.getJSONObject(0).isNull("totalAmount"));
        assertTrue(wireRows.getJSONObject(0).isNull("feeAmount"));
        assertFalse(wireRows.getJSONObject(0).getBoolean("metadataComplete"));
        assertTrue(wireRows.getJSONObject(0).getBoolean("pending"));
        assertEquals(-100000000L, wireRows.getJSONObject(1).getLong("totalAmountEstimate"));
        assertEquals(10000L, wireRows.getJSONObject(1).getLong("feeAmountEstimate"));
        assertEquals(-100L, wireRows.getJSONObject(2).getLong("totalAmount"));
        // Optional artifact for integration tests of embedding clients.
        String output = System.getProperty("arrr.history.output");
        if (output != null) java.nio.file.Files.writeString(java.nio.file.Path.of(output), wireRows.toString());
    }

}
