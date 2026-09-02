package org.qortium.notification;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The push connections used for foreign-payment notifications must accept exactly the protocol range the
 * pooled ElectrumX connections accept, or a server usable by one is refused by the other.
 */
public class ForeignPaymentProtocolVersionTests {

	private static List<Object> versionResponse(Object protocolVersion) {
		return List.of("ElectrumX 1.16.0", protocolVersion);
	}

	@Test
	public void testSupportedVersionsAreAccepted() throws IOException {
		ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse("1.2"));
		ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse("1.3"));
		ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse("1.4"));
		// The pooled ElectrumX client accepts the whole 1.4.x family, so this comparator must too.
		ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse("1.4.2"));
	}

	@Test
	public void testVersionsOutsideTheRangeAreRejected() {
		for (String unsupportedVersion : List.of("1.1", "1.5", "1.7.0", "1.10", "1.20", "2.0")) {
			IOException e = assertThrows(unsupportedVersion, IOException.class,
					() -> ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse(unsupportedVersion)));
			assertTrue(e.getMessage().contains("unsupported protocol version"));
		}
	}

	@Test
	public void testMalformedResponsesAreRejected() {
		assertThrows(IOException.class, () -> ForeignPaymentNotificationService.validateNegotiatedVersion(null));
		assertThrows(IOException.class, () -> ForeignPaymentNotificationService.validateNegotiatedVersion("1.4"));
		assertThrows(IOException.class, () -> ForeignPaymentNotificationService.validateNegotiatedVersion(List.of("ElectrumX 1.16.0")));
		assertThrows(IOException.class, () -> ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse(1.4d)));
		assertThrows(IOException.class, () -> ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse("1.x")));
	}
}
