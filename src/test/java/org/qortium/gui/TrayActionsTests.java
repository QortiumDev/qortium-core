package org.qortium.gui;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertFalse;

public class TrayActionsTests {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testLegacyHostedBootstrapInputsDoNotExposeTrayAction() throws Exception {
		Settings settings = Settings.getInstance();
		FieldUtils.writeField(settings, "bootstrap", true, true);
		FieldUtils.writeField(settings, "bootstrapHosts", new String[] {"https://attacker.invalid"}, true);

		List<TrayMenuAction> actions = TrayActions.createMenuActions(null);

		assertFalse(actions.stream().anyMatch(action -> action.getId() == 6));
	}
}
