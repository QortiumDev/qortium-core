package org.qortium.api.resource;

import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wire-level proof of the documented omission contracts: optional recovery fields are
 * OMITTED from the JSON body when absent, and present when set. The Java object mapping
 * alone cannot prove this because the JAXB/MOXy null policy decides the actual bytes.
 */
public class PirateRecoveryApiSerializationTests {

	private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

	private static String marshal(Object model) throws Exception {
		MOXyJsonProvider provider = new MOXyJsonProvider();
		provider.setIncludeRoot(false);
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		provider.writeTo(model, model.getClass(), model.getClass(), NO_ANNOTATIONS, JSON, headers, output);
		return output.toString(StandardCharsets.UTF_8);
	}

    @Test
    public void testWalletSessionContractUsesWritableJsonModel() throws Exception {
        Object model = new CrossChainPirateChainResource.WalletSessionContract();
        MOXyJsonProvider provider = new MOXyJsonProvider();
        assertTrue(provider.isWriteable(model.getClass(), model.getClass(), NO_ANNOTATIONS, JSON));
        org.junit.Assert.assertEquals("{\"contract\":\"qortium-arrr-wallet-session-v1\"}", marshal(model));
    }

	@Test
	public void testSyncStatusOmitsAbsentRecoveryStateAndCarriesPresentOne() throws Exception {
		String plain = marshal(new PirateChainSyncStatus(PirateChainSyncStatus.State.READY,
				"Synchronized", null, null, false));
		assertFalse("recoveryState must be omitted when null: " + plain, plain.contains("recoveryState"));

		String recovering = marshal(new PirateChainSyncStatus(PirateChainSyncStatus.State.SYNCHRONIZING,
				"Recovering imported keys...", null, null, false, "RECOVERING"));
		assertTrue(recovering.contains("\"recoveryState\""));
		assertTrue(recovering.contains("RECOVERING"));
	}

	@Test
	public void testSyncStatusOmitsAbsentSnapshotFieldsAndCarriesPresentOnes() throws Exception {
		String minimal = marshal(new PirateChainSyncStatus(PirateChainSyncStatus.State.LOADING,
				"Not initialized yet", null, null, false, null, null, null, null, null,
				1_700_000_000_000L, false, "legacy", null, null));
		assertFalse("scannedHeight must be omitted when null: " + minimal, minimal.contains("scannedHeight"));
		assertFalse("tipHeight must be omitted when null: " + minimal, minimal.contains("tipHeight"));
		assertFalse("totalBalanceAtomic must be omitted when null: " + minimal, minimal.contains("totalBalanceAtomic"));
		assertFalse("verifiedBalanceAtomic must be omitted when null: " + minimal, minimal.contains("verifiedBalanceAtomic"));
		assertFalse("walletIdentityHash must be omitted when null: " + minimal, minimal.contains("walletIdentityHash"));
		assertFalse("lastError must be omitted when null: " + minimal, minimal.contains("lastError"));
		assertTrue(minimal.contains("\"backendMode\":\"legacy\""));
		// observedAt must be a bare JSON number (epoch milliseconds), never a quoted string - Home's
		// adapter parses it as a number.
		assertTrue("observedAt must serialize as an unquoted JSON number: " + minimal,
				minimal.contains("\"observedAt\":1700000000000"));
		assertFalse("observedAt must not be a JSON string: " + minimal, minimal.contains("\"observedAt\":\""));
		assertTrue(minimal.contains("\"stale\":false"));

		PirateChainSyncStatus.LastError lastError =
				new PirateChainSyncStatus.LastError("NATIVE_LANE_DEGRADED", "Native wallet lane is degraded");
		String full = marshal(new PirateChainSyncStatus(PirateChainSyncStatus.State.DEGRADED,
				"Unavailable until Core restart", null, null, true, null, 100L, 200L,
				"100000000", "90000000", 1_700_000_000_000L, true, "unified", "identityHash58", lastError));
		assertTrue(full.contains("\"scannedHeight\":100"));
		assertTrue(full.contains("\"tipHeight\":200"));
		assertTrue(full.contains("\"totalBalanceAtomic\":\"100000000\""));
		assertTrue(full.contains("\"verifiedBalanceAtomic\":\"90000000\""));
		assertTrue(full.contains("\"walletIdentityHash\":\"identityHash58\""));
		assertTrue(full.contains("\"stale\":true"));
		assertTrue(full.contains("\"backendMode\":\"unified\""));
		assertTrue(full.contains("NATIVE_LANE_DEGRADED"));
		assertTrue(full.contains("Native wallet lane is degraded"));
	}

	@Test
	public void testRecoveryResultOmitsAbsentRescanFloorAndCarriesPresentOne() throws Exception {
		String completed = marshal(new PirateChainVerifiedRecoveryResult(42L, "sapling", "zs1canonical",
				7, 1_999_000, true, false, null));
		assertFalse("requiredRescanFromHeight must be omitted when null: " + completed,
				completed.contains("requiredRescanFromHeight"));

		String pending = marshal(new PirateChainVerifiedRecoveryResult(42L, "sapling", "zs1canonical",
				7, 1_999_000, false, true, 1_999_000L));
		assertTrue(pending.contains("\"requiredRescanFromHeight\""));
		assertTrue(pending.contains("1999000"));
	}
}
