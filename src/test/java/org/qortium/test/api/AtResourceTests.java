package org.qortium.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.model.AtMapValueResponse;
import org.qortium.api.resource.AtResource;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.DeployAtTransaction;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

		AtMapValueResponse stored = this.atResource.getMapValue(atAddress, -11L, 22L);
		assertEquals(atAddress, stored.atAddress);
		assertEquals(-11L, stored.key1);
		assertEquals(22L, stored.key2);
		assertEquals(33L, stored.value);

		AtMapValueResponse missing = this.atResource.getMapValue(atAddress, -11L, 23L);
		assertEquals(0L, missing.value);
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

	/**
	 * {@code /at/{ataddress}} used to accept any path segment, so an invented sub-path such as
	 * {@code /at/search} answered 204 and looked exactly like a genuine "no such AT". That made
	 * "/at/search returns 204" read as proof that no ATs existed, which it never was.
	 */
	@Test
	public void testGetByAddressRejectsNonAtAddress() {
		ApiCommon.assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.atResource.getByAddress("search"), null);
		ApiCommon.assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.atResource.getByAddress(this.aliceAddress), null);
	}

	@Test
	public void testGetByAddressReturnsNothingForValidAbsentAtAddress() {
		assertNull("a well-formed AT address with no AT must still answer empty, not an error",
				this.atResource.getByAddress(Crypto.toATAddress(new byte[32])));
	}

	@Test
	public void testGetDataByAddressRejectsNonAtAddress() {
		ApiCommon.assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.atResource.getDataByAddress("search"), null);
	}

	@Test
	public void testGetDataByAddressReturnsNothingForValidAbsentAtAddress() {
		// This dereferenced a null AT state and answered a bare HTTP 500 before.
		assertNull(this.atResource.getDataByAddress(Crypto.toATAddress(new byte[32])));
	}

	/**
	 * Guards the serialized name of the AT address. {@link ATData} is bound with
	 * {@link javax.xml.bind.annotation.XmlAccessType#FIELD}, so the JSON key is the Java field
	 * name: renaming the field silently renames the public API. It was {@code ATAddress} here and
	 * {@code aTAddress} on {@code DeployAtTransactionData} while every other Core response already
	 * used {@code atAddress}.
	 */
	@Test
	public void testAtDataJsonUsesCamelCaseAtAddress() throws JAXBException {
		ATData atData = new ATData(Crypto.toATAddress(new byte[32]), new byte[32], 1L, 2, 3L,
				new byte[] {1}, new byte[32], false, null, false, false, false, null, null);

		JAXBContext context = JAXBContextFactory.createContext(new Class[] {ATData.class}, null);
		Marshaller marshaller = context.createMarshaller();
		marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

		StringWriter writer = new StringWriter();
		marshaller.marshal(atData, writer);
		String json = writer.toString();

		assertTrue("ATData JSON must expose atAddress: " + json, json.contains("\"atAddress\""));
		assertFalse("ATData JSON must not expose the legacy ATAddress key: " + json,
				json.contains("\"ATAddress\""));
	}
}
