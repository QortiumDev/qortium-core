package org.qortium.crosschain;
import org.json.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class PirateChainSendPreflightTests {
    private Map<String,Object> replies() {
        Map<String,Object> r = new HashMap<>();
        r.put("get_active_wallet", "wallet"); r.put("current_receive_address", "input");
        r.put("list_key_groups", new JSONArray().put(new JSONObject().put("id", 7).put("spendable", true)));
        r.put("external", new JSONArray().put(row("input", 1, "0")));
        r.put("funds", new JSONArray().put(row("input", 1, "10")).put(row("internal-change", 2, "10090")));
        r.put("get_spendability_status", new JSONObject().put("spendable", true).put("rescan_required", false)
                .put("repair_queued", false).put("reason_code", "OK").put("anchor_height", 12).put("validated_anchor_height", 12));
        return r;
    }
    private JSONObject row(String address, int id, String funds) { return new JSONObject().put("address", address).put("address_id", id).put("key_id", 7).put("spendable", funds); }
    private void check(Map<String,Object> r) throws Exception {
        var adapter = (ZcashFamilyNativeAdapter)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ZcashFamilyNativeAdapter.class}, (proxy, method, args) -> {
            assertEquals("invokeJson", method.getName()); JSONObject request = new JSONObject((String)args[0]); String name = request.getString("method");
            if (name.equals("list_address_balances")) name = request.isNull("key_id") ? "external" : "funds";
            return new JSONObject().put("ok", true).put("result", r.get(name)).toString();
        });
        PirateChainSendPreflight.check(adapter, "input", 100, 10000);
    }
    @Test public void includesInternalChangeInTheSelectedGroup() throws Exception { check(replies()); }
    @Test public void rejectsMalformedOrUnspendableGroupReplies() {
        List<Consumer<Map<String,Object>>> cases = List.of(
            r -> ((JSONArray)r.get("external")).getJSONObject(0).put("key_id", JSONObject.NULL),
            r -> ((JSONArray)r.get("external")).put(row("input", 3, "1")),
            r -> ((JSONArray)r.get("list_key_groups")).getJSONObject(0).put("spendable", false),
            r -> ((JSONArray)r.get("funds")).getJSONObject(0).put("key_id", 8),
            r -> ((JSONArray)r.get("funds")).getJSONObject(0).put("spendable", 10),
            r -> ((JSONArray)r.get("funds")).put(row("input", 9, "100")),
            r -> ((JSONArray)r.get("funds")).put(row("different", 1, "100")),
            r -> ((JSONObject)r.get("get_spendability_status")).put("validated_anchor_height", 11),
            r -> ((JSONObject)r.get("get_spendability_status")).put("repair_queued", true),
            r -> r.put("current_receive_address", "another-input")
        );
        for (var mutation : cases) { var r = replies(); mutation.accept(r); assertThrows(ForeignBlockchainException.WalletNotReadyException.class, () -> check(r)); }
    }
    @Test public void rejectsInsufficientSelectedFundsDespiteOtherGroups() {
        var r = replies(); ((JSONArray)r.get("funds")).getJSONObject(1).put("spendable", "1");
        assertThrows(ForeignBlockchainException.InsufficientFundsException.class, () -> check(r));
    }
}
