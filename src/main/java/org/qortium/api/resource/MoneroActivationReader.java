package org.qortium.api.resource;

import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HexFormat;

/** Explicit reader runs after API authorization; rejects coercion, unknown fields and oversized bodies. */
final class MoneroActivationReader {
    record Activation(byte[] coinSeed, org.qortium.crosschain.WalletScanStart scanStart, String expectedSession) implements AutoCloseable {
        long restoreHeight() { return scanStart.height() == null ? 0 : scanStart.height(); }
        @Override public void close() { Arrays.fill(coinSeed, (byte) 0); }
    }
    static Activation read(InputStream input) throws IOException {
        if (input == null) throw new IOException("XMR activation required");
        byte[] bytes = input.readNBytes(2049);
        try {
            if (bytes.length > 2048) throw new IOException("Invalid XMR activation");
            String utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonFactory factory = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try (JsonParser parser = factory.createParser(utf8)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid XMR activation");
                String seed = null, expected = null, mode = null;
                Long height = null;
                Integer version = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Invalid XMR activation");
                    String name = parser.currentName();
                    JsonToken value = parser.nextToken();
                    switch (name) {
                        case "coinSeed" -> {
                            if (value != JsonToken.VALUE_STRING) throw new IOException("Invalid XMR seed");
                            seed = parser.getText();
                        }
                        case "scanMode" -> {
                            if (value != JsonToken.VALUE_STRING) throw new IOException("Invalid scan mode");
                            mode = parser.getText();
                        }
                        case "restoreHeight" -> {
                            if (value != JsonToken.VALUE_NUMBER_INT) throw new IOException("Explicit restore height required");
                            height = parser.getLongValue();
                        }
                        case "derivationVersion" -> {
                            if (value != JsonToken.VALUE_NUMBER_INT) throw new IOException("Derivation version required");
                            version = parser.getIntValue();
                        }
                        case "expectedSession" -> {
                            if (value != JsonToken.VALUE_STRING && value != JsonToken.VALUE_NULL) throw new IOException("Invalid XMR session");
                            expected = value == JsonToken.VALUE_NULL ? null : parser.getText();
                            if (expected != null && !expected.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                                throw new IOException("Invalid XMR session");
                        }
                        default -> throw new IOException("Unknown XMR field");
                    }
                }
                if (parser.nextToken() != null || seed == null || !seed.matches("[0-9a-f]{64}")
                        || version == null || version != 1)
                    throw new IOException("Invalid XMR activation");
                try {
                    var selected = mode == null ? org.qortium.crosschain.WalletScanStart.Mode.RESTORE_FROM_HEIGHT
                            : org.qortium.crosschain.WalletScanStart.Mode.valueOf(mode);
                    var start = new org.qortium.crosschain.WalletScanStart(selected, height);
                    return new Activation(HexFormat.of().parseHex(seed), start, expected);
                } catch (IllegalArgumentException e) { throw new IOException("Invalid scan start"); }
            }
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
