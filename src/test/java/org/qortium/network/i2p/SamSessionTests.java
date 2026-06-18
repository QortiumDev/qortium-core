package org.qortium.network.i2p;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SamSessionTests {

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void testB32DestinationValidation() {
		assertTrue(SamSession.isValidB32Destination("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p"));
		assertTrue(SamSession.isValidB32Destination("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST.b32.i2p"));

		assertFalse(SamSession.isValidB32Destination(null));
		assertFalse(SamSession.isValidB32Destination(""));
		assertFalse(SamSession.isValidB32Destination("0000000000000000000000000000000000000000000000000000.b32.i2p"));
		assertFalse(SamSession.isValidB32Destination("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.i2p"));
		assertFalse(SamSession.isValidB32Destination("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p SILENT=true"));
		assertFalse(SamSession.isValidB32Destination("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p\nSTREAM CLOSE"));
	}

	@Test
	public void testRejectsUnsafeSessionId() throws Exception {
		Path keyFile = this.temporaryFolder.newFile("sam.keys").toPath();

		new SamSession("127.0.0.1", 7656, "qortium_Test-1.2~3", keyFile);

		assertInvalidSessionId("");
		assertInvalidSessionId("qortium test");
		assertInvalidSessionId("qortium\nSTREAM CLOSE");
		assertInvalidSessionId("qortium ID=other");
	}

	private void assertInvalidSessionId(String sessionId) throws Exception {
		Path keyFile = this.temporaryFolder.newFile().toPath();
		try {
			new SamSession("127.0.0.1", 7656, sessionId, keyFile);
		} catch (IllegalArgumentException e) {
			return;
		}
		throw new AssertionError("Expected invalid session ID to be rejected: " + sessionId);
	}
}
