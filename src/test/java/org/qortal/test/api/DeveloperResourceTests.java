package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.resource.DeveloperResource;
import org.qortal.controller.DevProxyManager;
import org.qortal.repository.DataException;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

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

}
