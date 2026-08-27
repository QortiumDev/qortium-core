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
