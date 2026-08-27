package org.qortium.api.resource;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiException;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.controller.ZcashFamilyWalletController;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CrossChainPirateChainResourceTests extends ApiCommon {
	private CrossChainPirateChainResource resource;

	@Before
	public void buildResource() {
		ApiCommon.installTestApiKey();
		this.resource = (CrossChainPirateChainResource) ApiCommon.buildResource(
				CrossChainPirateChainResource.class, ApiCommon.TEST_API_KEY);
	}

	@After
	public void cleanup() {
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		PirateChain.resetForTesting();
		ApiCommon.clearTestApiKey();
	}

	@Test
	public void testBalanceSelectorPreservesDefaultAndSelectsVerifiedBalance() throws Exception {
		PirateChainBalance balance = new PirateChainBalance(1200L, 800L);

		assertEquals(1200L, CrossChainPirateChainResource.selectWalletBalance(balance, false));
		assertEquals(800L, CrossChainPirateChainResource.selectWalletBalance(balance, true));
		assertThrows(ForeignBlockchainException.class,
				() -> CrossChainPirateChainResource.selectWalletBalance(null, true));
	}

	@Test
	public void testDisabledSyncStatusHasPlainAndStructuredContracts() {
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);

		Response plain = this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, null, "ignored");
		assertEquals(MediaType.TEXT_PLAIN_TYPE, plain.getMediaType());
		assertEquals("Pirate Chain wallet is disabled", plain.getEntity());

		Response structured = this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, true, "ignored");
		assertEquals(MediaType.APPLICATION_JSON_TYPE, structured.getMediaType());
		PirateChainSyncStatus status = (PirateChainSyncStatus) structured.getEntity();
		assertEquals(PirateChainSyncStatus.State.DISABLED, status.state);
		assertEquals("Pirate Chain wallet is disabled", status.message);
		assertFalse(status.restartRequired);
	}

	@Test
	public void testStructuredStatusMapsProgressAndRestartRequirement() {
		PirateChainSyncStatus synchronizing = CrossChainPirateChainResource.toStructuredStatus(
				ZcashFamilyWalletController.WalletSyncStatus.synchronizing(
						"Sync in progress (12 / 30)", 12L, 30L));
		assertEquals(PirateChainSyncStatus.State.SYNCHRONIZING, synchronizing.state);
		assertEquals(Long.valueOf(12), synchronizing.syncedBlocks);
		assertEquals(Long.valueOf(30), synchronizing.totalBlocks);
		assertFalse(synchronizing.restartRequired);

		PirateChainSyncStatus degraded = CrossChainPirateChainResource.toStructuredStatus(
				ZcashFamilyWalletController.WalletSyncStatus.degraded("Unavailable until Core restart"));
		assertEquals(PirateChainSyncStatus.State.DEGRADED, degraded.state);
		assertEquals("Unavailable until Core restart", degraded.message);
		assertTrue(degraded.restartRequired);
	}

	private static PirateChainVerifiedRecoveryRequest buildValidRecoveryRequest() {
		PirateChainVerifiedRecoveryRequest recoveryRequest = new PirateChainVerifiedRecoveryRequest();
		recoveryRequest.entropy58 = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV";
		recoveryRequest.pool = "sapling";
		recoveryRequest.spendingKey = "secret-extended-key-main1testvector";
		recoveryRequest.expectedAddress = "zs1expectedaddress";
		recoveryRequest.addressIndex = 0;
		recoveryRequest.birthdayHeight = 2_000_000;
		return recoveryRequest;
	}

	private static void setUnifiedWalletEnabled(boolean enabled) throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "pirateChainWalletUnified", enabled, true);
	}

	@Test
	public void testRecoveryValidationMatrix() {
		assertEquals("Missing request body",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(null));

		PirateChainVerifiedRecoveryRequest recoveryRequest = buildValidRecoveryRequest();
		assertNull(CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.entropy58 = "not-base58-!!";
		assertEquals("Invalid entropy bytes",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.entropy58 = "abc";
		assertEquals("Invalid entropy bytes",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.pool = "orchard";
		assertEquals("Pool must be sapling or ironwood",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.pool = "Sapling";
		assertEquals("Pool must be sapling or ironwood",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.spendingKey = "Secret-Extended-Key";
		assertEquals("Invalid spending key encoding",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.spendingKey = "  ";
		assertEquals("Invalid spending key encoding",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.expectedAddress = "Zs1MixedCase";
		assertEquals("Invalid expected address encoding",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.addressIndex = null;
		assertEquals("Address index must be between 0 and 4096",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.addressIndex = 4097;
		assertEquals("Address index must be between 0 and 4096",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		// The upstream limit is inclusive: 4096 itself is valid
		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.addressIndex = 4096;
		assertNull(CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.birthdayHeight = 0;
		assertEquals("Birthday height must be greater than zero",
				CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));

		// Labels are deliberately not pre-validated or normalized: upstream's Unicode-aware
		// trim and byte limit are authoritative and the label must pass through verbatim.
		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.label = "  Recovered wallet  ";
		assertNull(CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));
		assertEquals("  Recovered wallet  ", recoveryRequest.label);

		recoveryRequest = buildValidRecoveryRequest();
		recoveryRequest.label = "x".repeat(101);
		assertNull(CrossChainPirateChainResource.validateVerifiedRecoveryRequest(recoveryRequest));
	}

	@Test
	public void testMixedCaseDetection() {
		assertFalse(CrossChainPirateChainResource.isMixedCase("zs1alllower"));
		assertFalse(CrossChainPirateChainResource.isMixedCase("ZS1ALLUPPER"));
		assertFalse(CrossChainPirateChainResource.isMixedCase("1234567890"));
		assertTrue(CrossChainPirateChainResource.isMixedCase("Zs1Mixed"));
		assertTrue(CrossChainPirateChainResource.isMixedCase("zS1"));
	}

	@Test
	public void testRecoveryImportRejectsNonLoopbackRequestsBeforeAnythingElse() throws Exception {
		setUnifiedWalletEnabled(true);
		try {
			CrossChainPirateChainResource remoteResource = (CrossChainPirateChainResource) ApiCommon.buildResource(
					CrossChainPirateChainResource.class,
					ApiCommon.buildRequest("203.0.113.5", ApiCommon.TEST_API_KEY));

			ApiException exception = assertThrows(ApiException.class,
					() -> remoteResource.importVerifiedRecoveryKey(ApiCommon.TEST_API_KEY,
							buildValidRecoveryRequest()));
			assertEquals(403, exception.getResponse().getStatus());
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}

	@Test
	public void testRecoveryImportRequiresUnifiedWallet() {
		ApiException exception = assertThrows(ApiException.class,
				() -> this.resource.importVerifiedRecoveryKey(ApiCommon.TEST_API_KEY,
						buildValidRecoveryRequest()));
		assertTrue(String.valueOf(exception.getMessage()).contains("Unified"));
	}

	@Test
	public void testRecoveryImportRejectsInvalidRequestBeforeWalletWork() throws Exception {
		setUnifiedWalletEnabled(true);
		try {
			PirateChainVerifiedRecoveryRequest recoveryRequest = buildValidRecoveryRequest();
			recoveryRequest.pool = "orchard";
			ApiException exception = assertThrows(ApiException.class,
					() -> this.resource.importVerifiedRecoveryKey(ApiCommon.TEST_API_KEY, recoveryRequest));
			assertTrue(String.valueOf(exception.getMessage()).contains("Pool must be sapling or ironwood"));
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}

	@Test
	public void testRecoveryImportReportsDisabledWallet() throws Exception {
		setUnifiedWalletEnabled(true);
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
		try {
			ApiException exception = assertThrows(ApiException.class,
					() -> this.resource.importVerifiedRecoveryKey(ApiCommon.TEST_API_KEY,
							buildValidRecoveryRequest()));
			assertTrue(String.valueOf(exception.getMessage()).contains("disabled"));
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}
}
