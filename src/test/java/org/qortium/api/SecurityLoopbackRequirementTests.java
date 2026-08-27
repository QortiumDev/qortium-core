package org.qortium.api;

import org.junit.Test;
import org.qortium.test.common.ApiCommon;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SecurityLoopbackRequirementTests {

	@Test
	public void testLoopbackAddressesAreAccepted() {
		Security.requireLoopbackRequest(ApiCommon.buildRequest("127.0.0.1", null));
		Security.requireLoopbackRequest(ApiCommon.buildRequest("127.0.53.1", null));
		Security.requireLoopbackRequest(ApiCommon.buildRequest("::1", null));
		Security.requireLoopbackRequest(ApiCommon.buildRequest("0:0:0:0:0:0:0:1", null));
	}

	@Test
	public void testNonLoopbackAddressesAreRejected() {
		for (String remoteAddr : new String[] { "203.0.113.5", "10.0.0.8", "192.168.1.20", "2001:db8::7" }) {
			HttpServletRequest request = ApiCommon.buildRequest(remoteAddr, null);
			ApiException exception = assertThrows(ApiException.class,
					() -> Security.requireLoopbackRequest(request));
			assertEquals(403, exception.getResponse().getStatus());
		}
	}

	@Test
	public void testMissingRemoteAddressFailsClosed() {
		// InetAddress.getByName(null) and getByName("") resolve to loopback, so these
		// must be rejected before resolution rather than silently passing the gate.
		for (String remoteAddr : new String[] { null, "", "   " }) {
			HttpServletRequest request = ApiCommon.buildRequest(remoteAddr, null);
			ApiException exception = assertThrows(ApiException.class,
					() -> Security.requireLoopbackRequest(request));
			assertEquals(403, exception.getResponse().getStatus());
		}
	}
}
