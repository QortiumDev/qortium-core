package org.qortium.notification;

import org.junit.Test;
import org.qortium.crosschain.ElectrumProtocolVersion;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
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
		// The push path must accept exactly what the pooled ElectrumX client accepts, 1.4 through 1.7.
		for (String version : List.of("1.4", "1.4.2", "1.5.3", "1.6", "1.6.0", "1.7", "1.7.0"))
			ForeignPaymentNotificationService.validateNegotiatedVersion(versionResponse(version));
	}

	@Test
	public void testNegotiatedVersionIsReturnedForFamilySelection() throws IOException {
		assertEquals(ElectrumProtocolVersion.parse("1.7").orElseThrow(),
				ForeignPaymentNotificationService.parseNegotiatedVersion(versionResponse("1.7")));
		assertEquals(ElectrumProtocolVersion.parse("1.4.2").orElseThrow(),
				ForeignPaymentNotificationService.parseNegotiatedVersion(versionResponse("1.4.2")));
	}

	@Test
	public void testHistoryIsParsedFromBothProtocolShapes() throws IOException {
		assertEquals(1, ForeignPaymentNotificationService.parseHistory(
				List.of(Map.of("tx_hash", "a".repeat(64), "height", 200004L))).size());
		assertEquals(1, ForeignPaymentNotificationService.parseHistory(
				Map.of("history", List.of(Map.of("tx_hash", "a".repeat(64), "height", 200004L)))).size());
	}

	@Test
	public void testVersionsOutsideTheRangeAreRejected() {
		for (String unsupportedVersion : List.of("1.1", "1.3", "1.8", "1.10", "1.20", "2.0")) {
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
