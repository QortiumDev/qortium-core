package org.qortium.test.settings;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.Test;
import org.qortium.settings.Settings;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ListenerPortSettingsTests {

	@Test
	public void testMainnetAndTestnetEffectiveDefaultsAreDistinct() throws Exception {
		Settings mainnet = load("{}");
		assertListenerPorts(mainnet, 14891, 14892, 14894);

		Settings testnet = load("{\"isTestNet\":true}");
		assertListenerPorts(testnet, 24891, 24892, 24894);
	}

	@Test
	public void testDistinctExplicitListenerPortsAreValid() throws Exception {
		Settings settings = load("{\"apiPort\":30000,\"listenPort\":30001,\"listenDataPort\":30002}");
		assertListenerPorts(settings, 30000, 30001, 30002);
	}

	@Test
	public void testEffectiveDefaultCollisionsAreRejected() throws Exception {
		assertInvalid("{\"listenPort\":14891}", "apiPort and listenPort", "14891");
		assertInvalid("{\"listenDataPort\":14891}", "apiPort and listenDataPort", "14891");
		assertInvalid("{\"listenDataPort\":14892}", "listenPort and listenDataPort", "14892");
		assertInvalid("{\"isTestNet\":true,\"listenDataPort\":24892}", "listenPort and listenDataPort", "24892");
	}

	@Test
	public void testExplicitListenerPortCollisionsAreRejected() throws Exception {
		assertInvalid("{\"apiPort\":30000,\"listenPort\":30000}", "apiPort and listenPort", "30000");
		assertInvalid("{\"apiPort\":30000,\"listenDataPort\":30000}", "apiPort and listenDataPort", "30000");
		assertInvalid("{\"listenPort\":30000,\"listenDataPort\":30000}", "listenPort and listenDataPort", "30000");
	}

	@Test
	public void testInvalidExplicitApiPortIsRejected() throws Exception {
		assertInvalid("{\"apiPort\":0}", "apiPort must be between 1 and 65535");
		assertInvalid("{\"apiPort\":65536}", "apiPort must be between 1 and 65535");
	}

	private static void assertListenerPorts(Settings settings, int apiPort, int listenPort, int qdnListenPort) {
		assertEquals(apiPort, settings.getApiPort());
		assertEquals(listenPort, settings.getListenPort());
		assertEquals(qdnListenPort, settings.getQDNListenPort());
	}

	private static void assertInvalid(String json, String... expectedMessageParts) throws Exception {
		try {
			load(json);
			fail("Expected settings validation failure");
		} catch (RuntimeException e) {
			for (String expectedMessagePart : expectedMessageParts)
				assertTrue("Expected message containing: " + expectedMessagePart + ", got: " + e.getMessage(),
						e.getMessage().contains(expectedMessagePart));
		}
	}

	private static Settings load(String json) throws Exception {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {Settings.class}, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		Settings settings = unmarshaller.unmarshal(new StreamSource(new StringReader(json)), Settings.class).getValue();

		Method validate = Settings.class.getDeclaredMethod("validate");
		validate.setAccessible(true);
		try {
			validate.invoke(settings);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException)
				throw (RuntimeException) e.getCause();
			throw e;
		}
		return settings;
	}
}
