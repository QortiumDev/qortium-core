package org.qortium.api.restricted.resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.repository.DataException;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapResourceTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
		BootstrapResource.releaseBootstrapOperation();
	}

	@After
	public void afterTest() throws DataException {
		BootstrapResource.releaseBootstrapOperation();
		ApiCommon.clearTestApiKey();
		Common.useDefaultSettings();
	}

	@Test
	public void testConcurrentValidationIsRejected() {
		assertTrue(BootstrapResource.tryAcquireBootstrapOperation());

		BootstrapResource resource = buildBootstrapResource(ApiCommon.TEST_API_KEY);

		ApiCommon.assertApiError(ApiError.OPERATION_IN_PROGRESS, () -> resource.validateBootstrap(null));
		assertTrue(BootstrapResource.isBootstrapOperationInProgress());
	}

	@Test
	public void testConcurrentCreationIsRejected() {
		assertTrue(BootstrapResource.tryAcquireBootstrapOperation());

		BootstrapResource resource = buildBootstrapResource(ApiCommon.TEST_API_KEY);

		ApiCommon.assertApiError(ApiError.OPERATION_IN_PROGRESS, () -> resource.createBootstrap(null));
		assertTrue(BootstrapResource.isBootstrapOperationInProgress());
	}

	@Test
	public void testUnauthorizedValidationDoesNotAcquireOperationGuard() {
		BootstrapResource resource = buildBootstrapResource(null);

		ApiCommon.assertApiError(ApiError.UNAUTHORIZED, () -> resource.validateBootstrap(null));
		assertFalse(BootstrapResource.isBootstrapOperationInProgress());
	}

	@Test
	public void testUnauthorizedCreationDoesNotAcquireOperationGuard() {
		BootstrapResource resource = buildBootstrapResource(null);

		ApiCommon.assertApiError(ApiError.UNAUTHORIZED, () -> resource.createBootstrap(null));
		assertFalse(BootstrapResource.isBootstrapOperationInProgress());
	}

	@Test
	public void testBootstrapOperationGuardCanBeReleased() {
		assertFalse(BootstrapResource.isBootstrapOperationInProgress());
		assertTrue(BootstrapResource.tryAcquireBootstrapOperation());
		assertTrue(BootstrapResource.isBootstrapOperationInProgress());

		BootstrapResource.releaseBootstrapOperation();

		assertFalse(BootstrapResource.isBootstrapOperationInProgress());
		assertTrue(BootstrapResource.tryAcquireBootstrapOperation());
	}

	private static BootstrapResource buildBootstrapResource(String apiKey) {
		BootstrapResource resource = new BootstrapResource();
		resource.request = ApiCommon.buildRequest("127.0.0.1", apiKey);
		return resource;
	}
}
