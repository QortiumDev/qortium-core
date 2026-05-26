package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.LiteNode;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HandshakeTests {

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testNonLiteNodeAdvertisesLiteDataCapability() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "lite", false, true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities();

		assertEquals(LiteNode.LITE_DATA_CAPABILITY_VERSION, capabilities.get(LiteNode.LITE_DATA_CAPABILITY));
	}

	@Test
	public void testLiteNodeDoesNotAdvertiseLiteDataCapability() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "lite", true, true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities();

		assertFalse(capabilities.containsKey(LiteNode.LITE_DATA_CAPABILITY));
	}

}
