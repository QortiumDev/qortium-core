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

public class PeerMaintenanceSettingsTests {

	@Test
	public void testNewDataIdleSettingIsEffectiveAndDiagnosed() throws Exception {
		Settings settings = load("{\"maxDataPeerIdleTime\":1234}");

		assertEquals(1234, settings.getMaxDataPeerIdleTime());
		assertEquals("maxDataPeerIdleTime", settings.getDataPeerIdleSettingSource());
	}

	@Test
	public void testLegacyDataConnectionSettingMigratesAsIdleAlias() throws Exception {
		Settings settings = load("{\"maxDataPeerConnectionTime\":2345}");

		assertEquals(2345, settings.getMaxDataPeerIdleTime());
		assertTrue(settings.getDataPeerIdleSettingSource().contains("deprecated alias"));
	}

	@Test
	public void testConflictingDataIdleSettingsAreRejected() throws Exception {
		assertInvalid("{\"maxDataPeerIdleTime\":1200,\"maxDataPeerConnectionTime\":1800}",
				"maxDataPeerIdleTime conflicts with deprecated maxDataPeerConnectionTime");
	}

	@Test
	public void testInvalidPeerAgeAndIdleSettingsAreRejectedClearly() throws Exception {
		assertInvalid("{\"minPeerConnectionTime\":0}", "minPeerConnectionTime must be greater than 0");
		assertInvalid("{\"minPeerConnectionTime\":10,\"maxPeerConnectionTime\":10}",
				"maxPeerConnectionTime must be greater than minPeerConnectionTime");
		assertInvalid("{\"maxDataPeerIdleTime\":0}", "maxDataPeerIdleTime must be greater than 0");
	}

	private static void assertInvalid(String json, String expectedMessage) throws Exception {
		try {
			load(json);
			fail("Expected settings validation failure");
		} catch (RuntimeException e) {
			assertTrue("Expected message containing: " + expectedMessage + ", got: " + e.getMessage(),
					e.getMessage().contains(expectedMessage));
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
