package org.qortium.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

public class SecurityTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
	}

	@After
	public void afterTest() throws DataException {
		ApiCommon.clearTestApiKey();
		Common.useDefaultSettings();
	}

	@Test
	public void testLoopbackIpv4WithoutApiKeyRejected() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", null)));
	}

	@Test
	public void testLoopbackIpv6WithoutApiKeyRejected() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("::1", null)));
	}

	@Test
	public void testLoopbackWithApiKeyAllowed() {
		ApiCommon.assertNoApiError(
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", ApiCommon.TEST_API_KEY)));
	}

	@Test
	public void testLoopbackWithQueryApiKeyRejected() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", null, ApiCommon.TEST_API_KEY)));
	}

	@Test
	public void testLoopbackWithInvalidApiKeyRejected() {
		ApiCommon.assertApiError(ApiError.UNAUTHORIZED,
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("127.0.0.1", "wrong-api-key")));
	}

	@Test
	public void testNonLoopbackWithApiKeyAllowed() {
		ApiCommon.assertNoApiError(
				() -> Security.checkApiCallAllowed(ApiCommon.buildRequest("192.0.2.10", ApiCommon.TEST_API_KEY)));
	}

}
