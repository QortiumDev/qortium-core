package org.qortium.crosschain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Converts native history without treating an unknown amount as zero. */
final class PirateChainHistory {
    private static final String PARTIAL_METHOD = "qortal_list_transactions_partial";

    private PirateChainHistory() { }

    static List<SimpleTransaction> read(ZcashFamilyNativeAdapter adapter, boolean unified,
            String walletId, String address) throws ForeignBlockchainException {
        try {
            if (unified) {
                if (walletId == null || walletId.isBlank())
                    throw new IllegalArgumentException("No active ARRR wallet");
                JSONObject request = new JSONObject().put("method", PARTIAL_METHOD).put("wallet_id", walletId);
                JSONObject envelope = new JSONObject(adapter.invokeJson(request.toString(), false));
                if (envelope.getBoolean("ok"))
                    return parse(envelope.getJSONObject("result").getJSONArray("transactions"), address, true);
                // Only an explicitly unsupported method permits compatibility fallback.
                // Timeout, malformed data, or a wallet error must not trigger a second native read.
                if (!envelope.optString("error").startsWith("Invalid request JSON: unknown variant `" + PARTIAL_METHOD + "`"))
                    throw new IllegalArgumentException("Native partial history is unavailable");
            }
            return parse(new JSONArray(adapter.execute("list", "")), address, false);
        } catch (RuntimeException e) {
            throw new ForeignBlockchainException("Unable to read ARRR transaction history");
        }
    }

    static List<SimpleTransaction> parse(JSONArray rows, String address, boolean partial) {
        List<SimpleTransaction> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String hash = row.getString("txid");
            if (hash.isBlank()) throw new IllegalArgumentException("Missing transaction id");
            List<SimpleTransaction.Input> inputs = new ArrayList<>();
            List<SimpleTransaction.Output> outputs = new ArrayList<>();
            long incoming = 0;
            long outgoing = 0;
            boolean incomingKnown = true;
            String memo = null;
            JSONArray received = row.optJSONArray("incoming_metadata");
            if (received != null) for (int j = 0; j < received.length(); j++) {
                JSONObject item = received.getJSONObject(j);
                long value = requiredAmount(item, "value");
                incoming = Math.addExact(incoming, value);
                String recipient = item.optString("address", "[UNKNOWN]");
                incomingKnown &= !recipient.equals("[UNKNOWN]");
                inputs.add(new SimpleTransaction.Input("[PRIVATE]", value, false));
                outputs.add(new SimpleTransaction.Output(recipient, value, recipient.equals(address)));
                if (!item.isNull("memo")) memo = item.getString("memo");
            }
            JSONArray sent = row.optJSONArray("outgoing_metadata");
            if (sent != null) for (int j = 0; j < sent.length(); j++) {
                JSONObject item = sent.getJSONObject(j);
                long value = requiredAmount(item, "value");
                outgoing = Math.addExact(outgoing, value);
                String recipient = item.optString("address", "[UNKNOWN]");
                inputs.add(new SimpleTransaction.Input(address, value, true));
                outputs.add(new SimpleTransaction.Output(recipient, value, recipient.equals(address)));
                if (!item.isNull("memo")) memo = item.getString("memo");
            }
            boolean hasOutgoing = partial ? row.getBoolean("has_outgoing") : sent != null && !sent.isEmpty();
            Long amount;
            Long fee;
            Long amountEstimate = null;
            Long feeEstimate = null;
            boolean complete = true;
            if (partial) {
                complete = row.getBoolean("metadata_complete") && incomingKnown;
                Long total = optionalAmount(row, "outgoing_value");
                amount = complete && total != null ? Math.subtractExact(incoming, total) : null;
                if (amount == null && incomingKnown) {
                    Long estimate = optionalAmount(row, "outgoing_value_estimate");
                    if (estimate != null) amountEstimate = Math.subtractExact(incoming, estimate);
                }
                fee = hasOutgoing ? optionalAmount(row, "fee") : Long.valueOf(0);
                if (hasOutgoing && fee == null) feeEstimate = optionalAmount(row, "fee_estimate");
                complete &= amount != null;
            } else {
                amount = Math.subtractExact(incoming, outgoing);
                // The fee belongs to the transaction, not each recovered recipient.
                fee = hasOutgoing ? optionalAmount(row, "fee") : Long.valueOf(0);
                if (hasOutgoing && fee == null) feeEstimate = PirateChain.MAINNET_FEE;
            }
            long timestamp = Math.multiplyExact(requiredAmount(row, "datetime"), 1000L);
            result.add(SimpleTransaction.partial(hash, timestamp, amount, fee, amountEstimate, feeEstimate,
                    complete, row.optBoolean("unconfirmed", false), inputs, outputs, memo));
        }
        return result;
    }

    private static long requiredAmount(JSONObject row, String field) {
        Long value = optionalAmount(row, field);
        if (value == null) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static Long optionalAmount(JSONObject row, String field) {
        if (!row.has(field) || row.isNull(field)) return null;
        String value = row.get(field).toString();
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid " + field);
        return new BigInteger(value).longValueExact();
    }
}
