package org.qortium.test.settings;

import org.bitcoinj.base.Base58;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.qortium.settings.Settings;

import java.nio.file.Files;
import java.security.Security;
import java.util.List;

import static org.junit.Assert.*;

public class QdnRetainedSignaturesSettingsTests {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    @After public void restoreDefaults() { Settings.fileInstance("src/test/resources/test-settings-v2.json"); }

    private void load(String extra) throws Exception {
        var file = temporaryFolder.newFile().toPath();
        Files.writeString(file, "{\"storagePolicy\":\"FOLLOWED\"" + extra + "}");
        Settings.fileInstance(file.toString());
    }
    @Test public void defaultsRetainNothing() throws Exception {
        load("");
        assertTrue(Settings.getInstance().getQdnRetainedSignatures().isEmpty());
    }
    @Test public void explicitListIsImmutable() throws Exception {
        String sig = Base58.encode(new byte[64]);
        load(",\"qdnRetainedSignatures\":[\"" + sig + "\"]");
        assertEquals(List.of(sig), Settings.getInstance().getQdnRetainedSignatures());
        assertThrows(UnsupportedOperationException.class, () -> Settings.getInstance().getQdnRetainedSignatures().clear());
    }
    @Test public void invalidEntriesAreRejected() throws Exception {
        for (String value : List.of("null", "[null]", "[\"\"]", "[\"not-base58!\"]", "[\" " + Base58.encode(new byte[64]) + "\"]", "[\"" + Base58.encode(new byte[63]) + "\"]")) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> load(",\"qdnRetainedSignatures\":" + value));
            assertTrue(error.getMessage(), error.getMessage().contains("qdnRetainedSignatures"));
        }
    }
    @Test public void duplicateEntriesAreRejected() throws Exception {
        String sig = Base58.encode(new byte[64]);
        assertThrows(RuntimeException.class, () -> load(",\"qdnRetainedSignatures\":[\"" + sig + "\",\"" + sig + "\"]"));
    }
    @Test public void emptyListClearsRetention() throws Exception {
        load(",\"qdnRetainedSignatures\":[]");
        assertTrue(Settings.getInstance().getQdnRetainedSignatures().isEmpty());
    }
}
