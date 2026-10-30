package org.qortium.crosschain.monero;

import org.junit.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.Assert.*;

/** Ordinary CI must catch dependency upgrades whose native bytes were not reviewed. */
public class MoneroNativeArtifactTests {
    @Test public void bundledNativeResourceMatchesReviewedPinWithoutLoadingIt() throws Exception {
        try (var input = MoneroNativeLoader.class.getResourceAsStream("/linux-x86_64/libmonero-java.so")) {
            assertNotNull("The dependency must contain the evaluated Linux x86_64 native resource", input);
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            assertEquals("Review the native artifact and execute isolated JNI acceptance before updating its pin",
                    MoneroNativeLoader.SHA256, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
