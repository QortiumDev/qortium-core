package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.AtResource;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.data.at.ATMapEntryData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.DeployAtTransaction;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AtResourceTests extends ApiCommon {

	private AtResource atResource;

	@Before
	public void buildResource() {
		this.atResource = (AtResource) ApiCommon.buildResource(AtResource.class);
	}

	@Test
	public void testGetMapValueReturnsStoredValueAndZeroForMissingKey() throws DataException {
		String atAddress;
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			DeployAtTransaction deploy = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), 1_00000000L);
			atAddress = deploy.getATAccount().getAddress();
			repository.getATRepository().saveATMapChanges(repository.getBlockRepository().getBlockchainHeight() + 1,
					List.of(new ATMapChangeData(atAddress, -11L, 22L, null, 33L)));
			repository.saveChanges();
		}

		ATMapEntryData stored = this.atResource.getMapValue(atAddress, -11L, 22L);
		assertEquals(atAddress, stored.getATAddress());
		assertEquals(-11L, stored.getKey1());
		assertEquals(22L, stored.getKey2());
		assertEquals(33L, stored.getValue());

		ATMapEntryData missing = this.atResource.getMapValue(atAddress, -11L, 23L);
		assertEquals(0L, missing.getValue());
	}

	@Test
	public void testGetMapValueRejectsNonAtAddress() {
		ApiCommon.assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.atResource.getMapValue(this.aliceAddress, 1L, 2L), null);
	}

	@Test
	public void testGetMapValueRequiresBothKeys() throws DataException {
		String atAddress;
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			atAddress = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), 1_00000000L)
					.getATAccount().getAddress();
			repository.saveChanges();
		}

		ApiCommon.assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.atResource.getMapValue(atAddress, null, 2L), null);
	}
}
