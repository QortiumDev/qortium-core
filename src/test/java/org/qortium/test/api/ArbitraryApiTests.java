package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.api.resource.ArbitraryResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.arbitrary.misc.Service;
import org.qortium.test.common.ApiCommon;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ArbitraryApiTests extends ApiCommon {

	private ArbitraryResource arbitraryResource;

	@Before
	public void buildResource() {
		this.arbitraryResource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
	}

	@Test
	public void testSearch() {
		Integer[] startingBlocks = new Integer[] { null, 0, 1, 999999999 };
		Integer[] blockLimits = new Integer[] { null, 0, 1, 999999999 };
		Integer[] txGroupIds = new Integer[] { null, 0, 1, 999999999 };
		Service[] services = new Service[] { Service.WEBSITE, Service.GIT_REPOSITORY, Service.BLOG_COMMENT };
		String[] names = new String[] { null, "Test" };
		String[] addresses = new String[] { null, this.aliceAddress };
		ConfirmationStatus[] confirmationStatuses = new ConfirmationStatus[] { ConfirmationStatus.UNCONFIRMED, ConfirmationStatus.CONFIRMED, ConfirmationStatus.BOTH };

		for (Integer startBlock : startingBlocks)
			for (Integer blockLimit : blockLimits)
				for (Integer txGroupId : txGroupIds)
					for (Service service : services)
						for (String name : names)
							for (String address : addresses)
								for (ConfirmationStatus confirmationStatus : confirmationStatuses) {
									if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null))
										continue;

									assertNotNull(this.arbitraryResource.searchTransactions(startBlock, blockLimit, txGroupId, service, name, address, confirmationStatus, 20, null, null));
									assertNotNull(this.arbitraryResource.searchTransactions(startBlock, blockLimit, txGroupId, service, name, address, confirmationStatus, 1, 1, true));
								}

		assertNotNull(this.arbitraryResource.searchTransactions(null, null, null, Service.APP, null, this.aliceAddress, null, 10, null, true));
	}

	@Test
	public void testAttachmentContentDispositionSanitizesHeaderValue() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("buildAttachmentContentDisposition", String.class);
		method.setAccessible(true);

		String header = (String) method.invoke(null, "bad\r\nname<script>.txt");

		assertTrue(header.startsWith("attachment"));
		assertFalse(header.contains("\r"));
		assertFalse(header.contains("\n"));
		assertFalse(header.contains("<"));
		assertFalse(header.contains(">"));
		assertTrue(header.contains(".txt"));
	}

	@Test
	public void testAttachmentContentDispositionUsesFallbackExtension() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("sanitizeAttachmentFilename", String.class);
		method.setAccessible(true);

		assertEquals("download.bin", method.invoke(null, ""));
		assertEquals("unsafename.bin", method.invoke(null, "unsafe/name"));
	}

}
