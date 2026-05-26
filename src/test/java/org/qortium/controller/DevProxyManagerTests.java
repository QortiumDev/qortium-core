package org.qortium.controller;

import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class DevProxyManagerTests {

	private DevProxyManager devProxyManager;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		this.devProxyManager = DevProxyManager.getInstance();
		this.devProxyManager.stop();
		this.devProxyManager.setSourceHostAndPort("127.0.0.1:5173");
	}

	@Test
	public void testAcceptsLoopbackTargets() throws DataException {
		this.devProxyManager.setSourceHostAndPort("localhost:5173");
		assertEquals("localhost:5173", this.devProxyManager.getSourceHostAndPort());

		this.devProxyManager.setSourceHostAndPort("127.0.0.1:5173");
		assertEquals("127.0.0.1:5173", this.devProxyManager.getSourceHostAndPort());

		this.devProxyManager.setSourceHostAndPort("[::1]:5173");
		assertEquals("[::1]:5173", this.devProxyManager.getSourceHostAndPort());
	}

	@Test
	public void testRejectsRemoteTargets() {
		assertInvalidTarget("attacker.com:80");
		assertInvalidTarget("192.168.1.10:5173");
		assertInvalidTarget("10.0.0.1:5173");
		assertInvalidTarget("[2001:db8::1]:5173");
	}

	@Test
	public void testRejectsQortiumPorts() {
		assertInvalidTarget("127.0.0.1:" + Settings.getInstance().getApiPort());
		assertInvalidTarget("127.0.0.1:" + Settings.getInstance().getDevProxyPort());
	}

	@Test
	public void testRejectsMalformedTargets() {
		assertInvalidTarget("");
		assertInvalidTarget("localhost");
		assertInvalidTarget("localhost:notaport");
		assertInvalidTarget("localhost:0");
		assertInvalidTarget("localhost:65536");
		assertInvalidTarget("http://127.0.0.1:5173");
		assertInvalidTarget("127.0.0.1:5173/path");
		assertInvalidTarget("127.0.0.1:5173?path=/");
		assertInvalidTarget("127.0.0.1:5173#fragment");
		assertInvalidTarget("user@127.0.0.1:5173");
		assertInvalidTarget("::1:5173");
	}

	private void assertInvalidTarget(String sourceHostAndPort) {
		String previousSourceHostAndPort = this.devProxyManager.getSourceHostAndPort();

		try {
			this.devProxyManager.setSourceHostAndPort(sourceHostAndPort);
			fail("Expected developer proxy target to be rejected: " + sourceHostAndPort);
		} catch (DataException e) {
			assertEquals(previousSourceHostAndPort, this.devProxyManager.getSourceHostAndPort());
		}
	}

}
