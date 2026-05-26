package org.qortium.repository;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.settings.Settings;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BootstrapConfigurationTests {

	private Object originalSettingsInstance;

	@Before
	public void captureOriginalSettingsInstance() throws IllegalAccessException {
		this.originalSettingsInstance = FieldUtils.readStaticField(Settings.class, "instance", true);
	}

	@After
	public void restoreOriginalSettingsInstance() throws IllegalAccessException {
		FieldUtils.writeStaticField(Settings.class, "instance", this.originalSettingsInstance, true);
	}

	private Settings newSettingsInstance() throws ReflectiveOperationException {
		Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@Test
	public void testDefaultBootstrapSettingsAreDisabledAndEmpty() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertFalse(settings.getBootstrap());
		assertFalse(settings.hasBootstrapHostsConfigured());
		assertArrayEquals(new String[0], settings.getBootstrapHosts());
	}

	@Test
	public void testBootstrapHostsAreTrimmedAndFiltered() throws ReflectiveOperationException, IllegalAccessException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "bootstrapHosts", new String[] {null, " ", "\t", " https://bootstrap.example "}, true);

		assertArrayEquals(new String[] {"https://bootstrap.example"}, settings.getBootstrapHosts());
		assertTrue(settings.hasBootstrapHostsConfigured());
	}

	@Test
	public void testBootstrapHostsRequireAtLeastOneConfiguredValue() throws ReflectiveOperationException, IllegalAccessException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "bootstrapHosts", new String[] {"", "   ", null}, true);
		FieldUtils.writeStaticField(Settings.class, "instance", settings, true);

		assertFalse(settings.hasBootstrapHostsConfigured());
		assertArrayEquals(new String[0], settings.getBootstrapHosts());

		try {
			Bootstrap.ensureBootstrapHostsConfigured();
			fail("Expected bootstrap host validation to fail without configured hosts");
		} catch (DataException e) {
			assertEquals(Bootstrap.MISSING_BOOTSTRAP_HOSTS_MESSAGE, e.getMessage());
		}
	}
}
