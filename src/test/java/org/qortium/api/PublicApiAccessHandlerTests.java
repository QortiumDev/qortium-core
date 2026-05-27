package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublicApiAccessHandlerTests extends Common {

	private Settings settings;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.settings = Settings.getInstance();
	}

	@Test
	public void testDefaultOnlyAllowsApiWhitelist() {
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/status", this.settings));
	}

	@Test
	public void testPublicReadOnlyPathsAreAllowedWhenConfigured() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/status", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"2001:db8::1", "GET", "/admin/status", this.settings));
	}

	@Test
	public void testPublicRequestsCannotUseOtherReadEndpoints() throws Exception {
		enablePublicApi();

		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers", this.settings));
	}

	@Test
	public void testPublicRequestsCannotUseNonGetMethods() throws Exception {
		enablePublicApi();

		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/admin/status", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "DELETE", "/peers/known", this.settings));
	}

	@Test
	public void testFullApiWhitelistTakesPrecedenceOverPublicPaths() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "DELETE", "/peers/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings", this.settings));
	}

	@Test
	public void testDisabledFullApiWhitelistAllowsAllRequests() throws Exception {
		FieldUtils.writeField(this.settings, "apiWhitelistEnabled", false, true);

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"2001:db8::1", "POST", "/peers", this.settings));
	}

	private void enablePublicApi() throws Exception {
		FieldUtils.writeField(this.settings, "publicApiWhitelistEnabled", true, true);
		FieldUtils.writeField(this.settings, "publicApiWhitelist", new String[] {"0.0.0.0/0", "::/0"}, true);
		FieldUtils.writeField(this.settings, "publicApiPaths", new String[] {
				"GET /admin/status",
				"GET /peers/known"
		}, true);
	}

}
