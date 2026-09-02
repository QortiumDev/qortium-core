package org.qortium.notification;

import org.junit.Test;
import org.qortium.crosschain.ElectrumMethods;
import org.qortium.crosschain.ElectrumProtocolVersion;
import org.qortium.crosschain.ElectrumXPushClient;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The push client reconnects by itself and the watcher's work is queued, so a request built for a 1.4
 * server can be about to run when the client has already moved to a 1.7 server. Nothing protocol-dependent
 * may cross that boundary, and nothing may be sent before negotiation has happened at all.
 */
public class ForeignPaymentConnectionEpochTests {

	private static final byte[] SCRIPT = new byte[] {
			0x76, (byte) 0xa9, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0x88, (byte) 0xac };

	private static ElectrumProtocolVersion version(String value) {
		return ElectrumProtocolVersion.parse(value).orElseThrow();
	}

	@Test
	public void testRequestBeforeNegotiationIsRefused() {
		ForeignPaymentNotificationService.NegotiatedFamily family = new ForeignPaymentNotificationService.NegotiatedFamily();

		IOException e = assertThrows(IOException.class, () -> family.require(1L, 1L));

		assertTrue(e.getMessage().contains("has not negotiated"));
	}

	@Test
	public void testFamilyFollowsTheConnectionAcrossAReconnect() throws Exception {
		ForeignPaymentNotificationService.NegotiatedFamily family = new ForeignPaymentNotificationService.NegotiatedFamily();

		// First connection: a 1.4 server, so the old scripthash family.
		family.negotiated(1L, version("1.4.2"));
		ElectrumMethods onOldServer = family.require(1L, 1L);
		assertFalse(onOldServer.usesScriptPubKey());
		assertEquals("blockchain.scripthash.subscribe", onOldServer.subscribe(SCRIPT).getMethod());

		// The connection drops, then comes back on a 1.7 server.
		family.cleared();
		assertThrows("work must not run against a dropped connection", IOException.class, () -> family.require(1L, 1L));

		family.negotiated(2L, version("1.7"));
		ElectrumMethods onNewServer = family.require(2L, 2L);
		assertTrue(onNewServer.usesScriptPubKey());
		assertEquals("blockchain.scriptpubkey.subscribe", onNewServer.subscribe(SCRIPT).getMethod());
	}

	@Test
	public void testWorkQueuedAgainstTheOldConnectionIsRefused() {
		ForeignPaymentNotificationService.NegotiatedFamily family = new ForeignPaymentNotificationService.NegotiatedFamily();
		family.negotiated(1L, version("1.4.2"));

		// The client reconnects and negotiates 1.7 while a task built for epoch 1 is still queued.
		family.negotiated(2L, version("1.7"));

		assertThrows("a 1.4 request must never be sent on the 1.7 connection", IOException.class,
				() -> family.require(1L, 2L));
	}

	@Test
	public void testNegotiatedButSupersededConnectionIsRefused() {
		ForeignPaymentNotificationService.NegotiatedFamily family = new ForeignPaymentNotificationService.NegotiatedFamily();
		family.negotiated(3L, version("1.7"));

		// Same epoch recorded, but the client has already moved on.
		assertThrows(IOException.class, () -> family.require(3L, 4L));
	}

	// --- history-too-large is a property of the address, not the connection ---

	@Test
	public void testHistoryTooLargeIsRecognisedFromTheRpcCode() {
		assertTrue(ForeignPaymentNotificationService.isHistoryTooLarge(
				new ElectrumXPushClient.RpcException(ForeignPaymentNotificationService.HISTORY_TOO_LARGE_ERROR_CODE, "history too large")));
		assertTrue(ForeignPaymentNotificationService.isHistoryTooLarge(
				new ElectrumXPushClient.RpcException(10001, "anything")));
		// Pre-1.7 servers say it in words rather than with a code.
		assertTrue(ForeignPaymentNotificationService.isHistoryTooLarge(new IOException("ElectrumX RPC error: history too large")));
	}

	@Test
	public void testOtherFailuresAreNotTreatedAsHistoryTooLarge() {
		assertFalse(ForeignPaymentNotificationService.isHistoryTooLarge(new IOException("connection reset")));
		assertFalse(ForeignPaymentNotificationService.isHistoryTooLarge(
				new ElectrumXPushClient.RpcException(-32601, "unknown method")));
		assertFalse(ForeignPaymentNotificationService.isHistoryTooLarge(null));
	}

}
