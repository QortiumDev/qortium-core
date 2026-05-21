package org.qortal.api.restricted.resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.repository.DataException;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapResourceTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
		BootstrapResource.releaseBootstrapValidation();
	}

	@After
	public void afterTest() throws DataException {
		BootstrapResource.releaseBootstrapValidation();
		ApiCommon.clearTestApiKey();
		Common.useDefaultSettings();
	}

	@Test
	public void testConcurrentValidationIsRejected() {
		assertTrue(BootstrapResource.tryAcquireBootstrapValidation());

		BootstrapResource resource = buildBootstrapResource(ApiCommon.TEST_API_KEY);

		ApiCommon.assertApiError(ApiError.OPERATION_IN_PROGRESS, () -> resource.validateBootstrap(null));
		assertTrue(BootstrapResource.isBootstrapValidationInProgress());
	}

	@Test
	public void testUnauthorizedValidationDoesNotAcquireGuard() {
		BootstrapResource resource = buildBootstrapResource(null);

		ApiCommon.assertApiError(ApiError.UNAUTHORIZED, () -> resource.validateBootstrap(null));
		assertFalse(BootstrapResource.isBootstrapValidationInProgress());
	}

	@Test
	public void testBootstrapValidationGuardCanBeReleased() {
		assertFalse(BootstrapResource.isBootstrapValidationInProgress());
		assertTrue(BootstrapResource.tryAcquireBootstrapValidation());
		assertTrue(BootstrapResource.isBootstrapValidationInProgress());

		BootstrapResource.releaseBootstrapValidation();

		assertFalse(BootstrapResource.isBootstrapValidationInProgress());
		assertTrue(BootstrapResource.tryAcquireBootstrapValidation());
	}

	private static BootstrapResource buildBootstrapResource(String apiKey) {
		BootstrapResource resource = new BootstrapResource();
		resource.request = ApiCommon.buildRequest("127.0.0.1", apiKey);
		return resource;
	}
}
