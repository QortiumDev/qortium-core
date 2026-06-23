package org.qortium.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InitialDataPeersTests extends Common {

	private Settings newSettingsInstance() throws ReflectiveOperationException {
		Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@Test
	public void testDefaultInitialDataPeersAreClearnetSeeds() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertTrue(settings.hasInitialDataPeersConfigured());
		assertArrayEquals(new String[] {
				"185.207.104.78:24894",
				"146.103.42.59:24894"
		}, settings.getInitialDataPeers());
	}

	@Test
	public void testInitialDataPeersAreTrimmedAndDeduplicated() throws ReflectiveOperationException, IllegalAccessException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "initialDataPeers", new String[] {
				null,
				" ",
				"185.207.104.78:24894",
				" 185.207.104.78:24894 ",
				"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv.b32.i2p:24894"
		}, true);

		assertTrue(settings.hasInitialDataPeersConfigured());
		assertArrayEquals(new String[] {
				"185.207.104.78:24894",
				"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv.b32.i2p:24894"
		}, settings.getInitialDataPeers());
	}

	@Test
	public void testEmptyInitialDataPeersAreNotConfigured() throws ReflectiveOperationException, IllegalAccessException {
		Settings settings = newSettingsInstance();
		FieldUtils.writeField(settings, "initialDataPeers", new String[0], true);

		assertFalse(settings.hasInitialDataPeersConfigured());
		assertArrayEquals(new String[0], settings.getInitialDataPeers());
	}
}
