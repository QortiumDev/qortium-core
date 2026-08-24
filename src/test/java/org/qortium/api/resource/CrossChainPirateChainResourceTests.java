package org.qortium.api.resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.controller.ZcashFamilyWalletController;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
