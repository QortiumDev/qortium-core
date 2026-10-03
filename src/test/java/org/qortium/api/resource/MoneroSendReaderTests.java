package org.qortium.api.resource;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

public class MoneroSendReaderTests {
    private final String id = UUID.randomUUID().toString();
    private String prepare(String amount) {
        return "{\"operationId\":\"" + id + "\",\"address\":\"" + "4".repeat(95) + "\",\"amountAtomic\":" + amount + "}";
    }
    private MoneroSendReader.Body read(String body, MoneroSendReader.Kind kind) throws IOException {
        return MoneroSendReader.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), kind);
    }
    private void rejected(String body, MoneroSendReader.Kind kind) {
        try { read(body, kind); fail("Body accepted"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
    @Test public void exactUnsignedAtomicStringsAndStrictObjectFields() throws Exception {
        assertEquals("18446744073709551615", read(prepare("\"18446744073709551615\""), MoneroSendReader.Kind.PREPARE).amountAtomic());
        for (String amount : new String[]{"1", "1.0", "null", "true", "[]", "{}", "\"0\"", "\"01\"", "\"-1\"", "\"1e3\"", "\"18446744073709551616\""})
            rejected(prepare(amount), MoneroSendReader.Kind.PREPARE);
        String valid = prepare("\"9007199254740993\"");
        for (String body : new String[]{"", "null", "[]", "{}", valid + "{}", valid.replace("}", ",\"unknown\":\"x\"}"),
                valid.replace("}", ",\"operationId\":\"" + id + "\"}"), valid.substring(0, valid.length() - 1), " ".repeat(2049) + valid})
            rejected(body, MoneroSendReader.Kind.PREPARE);
    }
    @Test public void CommitAndOperationSchemasCannotSmuggleRecipientsOrMetadata() throws Exception {
        String op = "{\"operationId\":\"" + id + "\"}";
        assertEquals(id, read(op, MoneroSendReader.Kind.OPERATION).operationId());
        rejected(op, MoneroSendReader.Kind.COMMIT);
        String commit = op.replace("}", ",\"quoteDigest\":\"" + "a".repeat(64) + "\"}");
        assertEquals("a".repeat(64), read(commit, MoneroSendReader.Kind.COMMIT).quoteDigest());
        rejected(commit, MoneroSendReader.Kind.OPERATION);
        rejected(commit.replace("a".repeat(64), "A".repeat(64)), MoneroSendReader.Kind.COMMIT);
        rejected(commit.replace("}", ",\"metadata\":\"aa\"}"), MoneroSendReader.Kind.COMMIT);
        rejected(op.replace(id, "1-1-1-1-1"), MoneroSendReader.Kind.OPERATION);
        assertThrows(IOException.class, () -> MoneroSendReader.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), MoneroSendReader.Kind.OPERATION));
    }
    @Test public void OversizedStreamIsBoundedWithoutReadingRemainder() {
        InputStream endless = new InputStream() {
            int count;
            public int read() { if (++count > 2049) throw new AssertionError("Read beyond bound"); return ' '; }
        };
        assertThrows(IOException.class, () -> MoneroSendReader.read(endless, MoneroSendReader.Kind.PREPARE));
    }
}
