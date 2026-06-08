package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.resource.DeveloperResource;
import org.qortium.controller.DevProxyManager;
import org.qortium.repository.DataException;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

public class DeveloperResourceTests extends ApiCommon {

	private DeveloperResource developerResource;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		DevProxyManager.getInstance().stop();

		this.developerResource = (DeveloperResource) ApiCommon.buildResource(DeveloperResource.class);
	}

	@Test
	public void testStartProxyRequiresApiKey() {
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.developerResource.startProxy(null, "127.0.0.1:5173"));
	}

	@Test
	public void testStopProxyRequiresApiKey() {
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.developerResource.stopProxy(null));
	}

	@Test
	public void testStartProxyRejectsDisabledSetting() {
		ApiCommon.installTestApiKey();
		try {
			DeveloperResource authenticatedDeveloperResource = (DeveloperResource) ApiCommon.buildResource(DeveloperResource.class, ApiCommon.TEST_API_KEY);

			assertApiError(ApiError.INVALID_CRITERIA,
					() -> authenticatedDeveloperResource.startProxy(ApiCommon.TEST_API_KEY, "127.0.0.1:5173"));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

}
