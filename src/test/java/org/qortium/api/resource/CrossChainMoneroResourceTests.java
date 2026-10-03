package org.qortium.api.resource;

import org.junit.*;
import org.qortium.api.ApiException;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.settings.Settings;
import org.apache.commons.lang3.reflect.FieldUtils;
import java.io.InputStream;
import static org.junit.Assert.*;

public class CrossChainMoneroResourceTests {
    @Before public void setup() throws Exception { Common.useDefaultSettings(); ApiCommon.installTestApiKey(); }
    @After public void cleanup() { ApiCommon.clearTestApiKey(); }
    private CrossChainMoneroResource resource(String host, String key) {
        return (CrossChainMoneroResource) ApiCommon.buildResource(CrossChainMoneroResource.class, ApiCommon.buildRequest(host, key));
    }
    @Test public void authenticationAndLoopbackCheckedBeforeReadingSecrets() {
        InputStream mustNotRead = new InputStream() { @Override public int read() { throw new AssertionError("Unauthorized body read"); } };
        assertThrows(ApiException.class, () -> resource("127.0.0.1", null).activate(null, mustNotRead));
        assertThrows(ApiException.class, () -> resource("192.0.2.1", ApiCommon.TEST_API_KEY).activate(ApiCommon.TEST_API_KEY, mustNotRead));
        assertThrows(ApiException.class, () -> resource(null, ApiCommon.TEST_API_KEY).activate(ApiCommon.TEST_API_KEY, mustNotRead));
    }
    @Test public void disabledNodeDoesNotReadSeedAndCapabilitiesNeverAdvertiseSend() throws Exception {
        assertFalse(Settings.getInstance().isMoneroWalletEnabled());
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        var capabilities = resource.capabilities(ApiCommon.TEST_API_KEY);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String)capabilities.getEntity());
        assertFalse(json.get("enabled").asBoolean()); assertFalse(json.get("send").asBoolean());
        assertEquals(12, json.get("decimals").asInt());
        assertEquals("no-store", capabilities.getHeaderString("Cache-Control"));
        InputStream mustNotRead = new InputStream() { @Override public int read() { throw new AssertionError("Disabled body read"); } };
        assertEquals(503, resource.activate(ApiCommon.TEST_API_KEY, mustNotRead).getStatus());
        assertNull(FieldUtils.readStaticField(org.qortium.crosschain.monero.MoneroWalletRuntime.class, "instance", true));
    }
}
