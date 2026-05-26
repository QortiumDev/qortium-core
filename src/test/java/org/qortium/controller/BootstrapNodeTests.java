package org.qortium.controller;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapNodeTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		BootstrapNode.releaseBootstrapApply();
	}

	@After
	public void afterTest() throws DataException {
		BootstrapNode.releaseBootstrapApply();
		Common.useDefaultSettings();
	}

	@Test
	public void testDirectBootstrapIsRejectedWhenApplyInProgress() {
		assertTrue(BootstrapNode.tryAcquireBootstrapApply());

		assertFalse(BootstrapNode.attemptToBootstrap());
		assertTrue(BootstrapNode.isBootstrapApplyInProgress());
	}

	@Test
	public void testScheduledBootstrapIsRejectedWhenApplyInProgress() {
		assertTrue(BootstrapNode.tryAcquireBootstrapApply());

		assertFalse(BootstrapNode.scheduleBootstrap());
		assertTrue(BootstrapNode.isBootstrapApplyInProgress());
	}

	@Test
	public void testMissingBootstrapHostsReleaseApplyGuard() {
		assertFalse(Settings.getInstance().hasBootstrapHostsConfigured());

		assertFalse(BootstrapNode.attemptToBootstrap());
		assertFalse(BootstrapNode.isBootstrapApplyInProgress());
	}
}
