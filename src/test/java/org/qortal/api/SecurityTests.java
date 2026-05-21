package org.qortal.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

public class SecurityTests {

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
		FieldUtils.writeField(Settings.getInstance(), "localAuthBypassEnabled", true, true);
	}

	@After
	public void afterTest() throws DataException {
		ApiCommon.clearTestApiKey();
		Common.useDefaultSettings();
	}

	@Test
	public void testLoopbackIpv4WithoutApiKeyRejectedWhenBypassEnabled() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", null)));
	}

	@Test
	public void testLoopbackIpv6WithoutApiKeyRejectedWhenBypassEnabled() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("::1", null)));
	}

	@Test
	public void testLoopbackWithApiKeyAllowedWhenBypassEnabled() {
		ApiCommon.assertNoApiError(
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", ApiCommon.TEST_API_KEY)));
	}

	@Test
	public void testNonLoopbackWithApiKeyAllowedWhenBypassEnabled() {
		ApiCommon.assertNoApiError(
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("192.0.2.10", ApiCommon.TEST_API_KEY)));
	}

}
