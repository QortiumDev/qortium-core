package org.qortium.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.Test;
import org.qortium.settings.Settings;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowsInstallerSettingsTests {

	private static final Path WINDOWS_SETTINGS =
			Path.of("WindowsInstaller/Install Files/AppData/settings.json");

	@Test
	public void testDeveloperReferenceSettingsAreLoopbackRestricted() throws Exception {
		Settings settings = load(WINDOWS_SETTINGS);

		assertEquals("127.0.0.1", settings.getBindAddress());
		assertArrayEquals(new String[] {"127.0.0.1", "::1"}, settings.getApiWhitelist());
		assertTrue(settings.isApiRestricted());
		assertFalse(settings.isApiKeyRemoteAccessEnabled());
		assertTrue(settings.isApiDocumentationEnabled());

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings", settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"::1", "GET", "/admin/settings", settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings", settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings",
				"node-api-key", "node-api-key", settings));
	}

	@Test
	public void testDeveloperReferenceDoesNotSelectPreviewnet() throws Exception {
		Settings settings = load(WINDOWS_SETTINGS);

		assertFalse(settings.isTestNet());
		assertEquals(14891, settings.getApiPort());
		assertEquals(14892, settings.getListenPort());
		assertEquals(14894, settings.getQDNListenPort());
		assertFalse(settings.hasInitialPeersConfigured());
		assertFalse(settings.hasInitialDataPeersConfigured());
	}

	private static Settings load(Path path) throws Exception {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {Settings.class}, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		Settings settings = unmarshaller.unmarshal(
				new StreamSource(Files.newBufferedReader(path)), Settings.class).getValue();
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
