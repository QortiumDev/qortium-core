package org.qortium.crosschain;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/** Necessary selected-key-group funds/repair gates; never claims exact native note selectability. */
final class PirateChainSendPreflight {
    static void check(ZcashFamilyNativeAdapter adapter, String input, long amount, long fee)
            throws ForeignBlockchainException {
        try {
            String wallet = string(invoke(adapter, new JSONObject().put("method", "get_active_wallet")));
            JSONArray addresses = array(invoke(adapter, request("list_address_balances", wallet).put("key_id", JSONObject.NULL)));
            Long key = null;
            for (Object item : addresses) {
                JSONObject row = object(item);
                if (input.equals(string(row.get("address")))) {
                    if (key != null) throw new IllegalArgumentException();
                    key = integer(row.get("key_id"));
                }
            }
            if (key == null) throw new IllegalArgumentException();
            JSONArray groups = array(invoke(adapter, request("list_key_groups", wallet)));
            int matches = 0;
            for (Object item : groups) {
                JSONObject group = object(item);
                if (integer(group.get("id")) == key) {
                    if (!Boolean.TRUE.equals(group.get("spendable"))) throw new IllegalArgumentException();
                    matches++;
                }
            }
            if (matches != 1) throw new IllegalArgumentException();
            JSONArray funds = array(invoke(adapter, request("list_address_balances", wallet).put("key_id", key)));
            Set<String> seenAddresses = new HashSet<>(); Set<Long> seenIds = new HashSet<>(); long spendable = 0;
            for (Object item : funds) {
                JSONObject row = object(item);
                if (integer(row.get("key_id")) != key || !seenAddresses.add(string(row.get("address")))
                        || !seenIds.add(integer(row.get("address_id")))) throw new IllegalArgumentException();
                spendable = Math.addExact(spendable, amount(row.get("spendable")));
            }
            if (spendable < Math.addExact(amount, fee))
                throw new ForeignBlockchainException.InsufficientFundsException(PirateChain.INSUFFICIENT_VERIFIED_FUNDS_REASON);
            JSONObject status = object(invoke(adapter, request("get_spendability_status", wallet)));
            long anchor = integer(status.get("anchor_height"));
            if (!Boolean.TRUE.equals(status.get("spendable")) || !Boolean.FALSE.equals(status.get("rescan_required"))
                    || !Boolean.FALSE.equals(status.get("repair_queued")) || !"OK".equals(status.get("reason_code"))
                    || anchor <= 0 || integer(status.get("validated_anchor_height")) < anchor)
                throw new IllegalArgumentException();
            if (!wallet.equals(string(invoke(adapter, new JSONObject().put("method", "get_active_wallet"))))
                    || !input.equals(string(invoke(adapter, request("current_receive_address", wallet)))))
                throw new IllegalArgumentException();
        } catch (ForeignBlockchainException.InsufficientFundsException insufficient) { throw insufficient; }
        catch (Exception | LinkageError uncertain) {
            throw new ForeignBlockchainException.WalletNotReadyException("ARRR_SELECTED_FUNDS_UNAVAILABLE");
        }
    }
    private static JSONObject request(String method, String wallet) { return new JSONObject().put("method", method).put("wallet_id", wallet); }
    private static Object invoke(ZcashFamilyNativeAdapter adapter, JSONObject request) {
        JSONObject reply = new JSONObject(adapter.invokeJson(request.toString(), false));
        if (!Boolean.TRUE.equals(reply.opt("ok")) || !reply.has("result") || (reply.has("error") && !reply.isNull("error")))
            throw new IllegalArgumentException();
        return reply.get("result");
    }
    private static JSONObject object(Object value) { if (value instanceof JSONObject object) return object; throw new IllegalArgumentException(); }
    private static JSONArray array(Object value) { if (value instanceof JSONArray array) return array; throw new IllegalArgumentException(); }
    private static String string(Object value) { if (value instanceof String text && !text.isBlank()) return text; throw new IllegalArgumentException(); }
    private static long integer(Object value) {
        if (value instanceof Integer || value instanceof Long) return ((Number)value).longValue();
        if (value instanceof BigInteger integer) return integer.longValueExact();
        throw new IllegalArgumentException();
    }
    private static long amount(Object value) {
        if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
        return Long.parseLong(text);
    }
}
