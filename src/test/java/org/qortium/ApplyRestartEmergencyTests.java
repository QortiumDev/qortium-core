package org.qortium;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApplyRestartEmergencyTests {

	@Test
	public void testEmergencyParentPidParsingFailsClosed() {
		assertEquals(Long.valueOf(123L), ApplyRestart.parseEmergencyParentPid("123"));
		assertNull(ApplyRestart.parseEmergencyParentPid("0"));
		assertNull(ApplyRestart.parseEmergencyParentPid("-1"));
		assertNull(ApplyRestart.parseEmergencyParentPid("not-a-pid"));
	}

	@Test
	public void testMissingParentIsAlreadySafeToRecover() throws Exception {
		assertTrue(ApplyRestart.waitForParentProcessToExit(Long.MAX_VALUE, 1L));
	}
}
