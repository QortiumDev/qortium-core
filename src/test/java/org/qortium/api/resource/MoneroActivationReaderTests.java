package org.qortium.api.resource;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MoneroActivationReaderTests {
    private static final String VALID = "{\"coinSeed\":\"" + "01".repeat(32) + "\",\"derivationVersion\":1,\"restoreHeight\":0}";
    private MoneroActivationReader.Activation read(String input) throws IOException {
        return MoneroActivationReader.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void validBodyAndOwnedBufferWipe() throws Exception {
        var activation = read(VALID);
        assertEquals(32, activation.coinSeed().length);
        assertEquals(0, activation.restoreHeight());
        activation.close(); assertArrayEquals(new byte[32], activation.coinSeed());
    }
    @Test public void rejectsDuplicateUnknownTrailingCoercedAndMissingFields() {
        for (String bad : new String[]{
                VALID + "{}", VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":0,\"restoreHeight\":1"),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":\"0\""),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":0.0"),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":-1"),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":500000001"),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":9223372036854775808"),
                VALID.replace(",\"restoreHeight\":0", ""), VALID.replace("Version\":1", "Version\":2"),
                VALID.replace("\"restoreHeight\":0", "\"restoreHeight\":0,\"path\":\"/tmp/a\""),
                "[]", "null", "{}", " ".repeat(2049) + VALID, VALID.substring(0, VALID.length()-1)
        }) assertThrows(IOException.class, () -> read(bad));
    }
}
