package org.qortium.api.resource;

import com.fasterxml.jackson.core.*;
import org.qortium.crosschain.monero.MoneroSendAccess;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Authorized, bounded strict JSON only; diagnostics must never reach API responses or logs. */
final class MoneroSendReader {
    enum Kind { PREPARE, COMMIT, OPERATION }
    record Body(String operationId, String address, String amountAtomic, String quoteDigest) {
        @Override public String toString() { return "XMR send body [redacted]"; }
    }
    static Body read(InputStream input, Kind kind) throws IOException {
        if (input == null) throw new IOException("Invalid XMR send request");
        byte[] bytes = input.readNBytes(2049);
        try {
            if (bytes.length > 2048) throw new IOException("Invalid XMR send request");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Set<String> fields = switch (kind) {
                case PREPARE -> Set.of("operationId", "address", "amountAtomic");
                case COMMIT -> Set.of("operationId", "quoteDigest");
                case OPERATION -> Set.of("operationId");
            };
            Map<String, String> values = new HashMap<>();
            try (JsonParser parser = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(text)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid XMR send request");
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Invalid XMR send request");
                    String name = parser.currentName();
                    if (!fields.contains(name) || parser.nextToken() != JsonToken.VALUE_STRING)
                        throw new IOException("Invalid XMR send request");
                    values.put(name, parser.getText());
                }
                if (parser.nextToken() != null || !values.keySet().equals(fields)) throw new IOException("Invalid XMR send request");
            }
            String id = values.get("operationId"), address = values.get("address"), amount = values.get("amountAtomic"), digest = values.get("quoteDigest");
            MoneroSendAccess.validateId(id);
            if (kind == Kind.PREPARE) MoneroSendAccess.validateRequest(id, address, amount);
            if (kind == Kind.COMMIT) MoneroSendAccess.validateDigest(digest);
            return new Body(id, address, amount, digest);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
