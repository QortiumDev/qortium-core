package org.qortium.api.resource;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiException;
import org.qortium.api.ApiError;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainSendContract;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.api.model.crosschain.PirateChainSyncStatus;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainWalletInitializationRequest;
import org.qortium.controller.PirateChainWalletController;
import org.qortium.controller.ZcashFamilyWalletController;
import org.bitcoinj.base.Bech32;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.PirateChain;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.utils.Base58;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CrossChainPirateChainResourceTests extends ApiCommon {
	private CrossChainPirateChainResource resource;
    @org.junit.Rule public org.junit.rules.TemporaryFolder sendTemp = new org.junit.rules.TemporaryFolder();
    private String originalWalletsPath;


	@Before
	public void buildResource() throws Exception {
        originalWalletsPath = Settings.getInstance().getWalletsPath();
        FieldUtils.writeField(Settings.getInstance(), "walletsPath", sendTemp.getRoot().getAbsolutePath(), true);
		ApiCommon.installTestApiKey();
		this.resource = (CrossChainPirateChainResource) ApiCommon.buildResource(
				CrossChainPirateChainResource.class, ApiCommon.TEST_API_KEY);
	}

	@After
	public void cleanup() throws Exception {
        var service = (org.qortium.crosschain.PirateChainSendService) FieldUtils.readStaticField(org.qortium.crosschain.PirateChainSendRuntime.class, "service", true);
        if (service != null) service.close();
        FieldUtils.writeStaticField(org.qortium.crosschain.PirateChainSendRuntime.class, "service", null, true);
        FieldUtils.writeField(Settings.getInstance(), "walletsPath", originalWalletsPath, true);
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
	public void testBalanceSelectorReturnsTotalWhenVerifiedNotRequestedButRejectsUnknownVerified()
			throws Exception {
		PirateChainBalance unknownVerified = new PirateChainBalance(1200L, 1200L, false);

		// ?verified=false (or omitted) must keep returning the total balance even when the verified
		// figure is unknown - only an explicit ?verified=true request is affected.
		assertEquals(1200L, CrossChainPirateChainResource.selectWalletBalance(unknownVerified, false));

		ForeignBlockchainException.BalanceUnavailableException exception = assertThrows(
				ForeignBlockchainException.BalanceUnavailableException.class,
				() -> CrossChainPirateChainResource.selectWalletBalance(unknownVerified, true));
		assertEquals("ARRR_VERIFIED_BALANCE_UNAVAILABLE", exception.getMessage());
		// Home's read adapter matches on this stable substring, not the exact message: keep it.
		assertTrue(exception.getMessage().contains("BALANCE_UNAVAILABLE"));
	}

	@Test
	public void testDisabledSyncStatusHasPlainAndStructuredContracts() {
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);

		// The legacy plain-text contract never carries balances/identity, so it stays permissive
		// even for a non-entropy placeholder.
		Response plain = this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, null, "ignored");
		assertEquals(MediaType.TEXT_PLAIN_TYPE, plain.getMediaType());
		assertEquals("Pirate Chain wallet is disabled", plain.getEntity());

		Response structured = this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, true,
				Base58.encode(new byte[32]));
		assertEquals(MediaType.APPLICATION_JSON_TYPE, structured.getMediaType());
		PirateChainSyncStatus status = (PirateChainSyncStatus) structured.getEntity();
		assertEquals(PirateChainSyncStatus.State.DISABLED, status.state);
		assertEquals("Pirate Chain wallet is disabled", status.message);
		assertFalse(status.restartRequired);
	}

	@Test
	public void testStructuredSyncStatusRequiresValidEntropyAndLeaksNoIdentityWhenAbsent() {
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);

		ApiException nullEntropy = assertThrows(ApiException.class,
				() -> this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, true, null));
		assertEquals(400, nullEntropy.getResponse().getStatus());

		ApiException malformedEntropy = assertThrows(ApiException.class,
				() -> this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, true, "not-base58-!!"));
		assertEquals(400, malformedEntropy.getResponse().getStatus());

		ApiException shortEntropy = assertThrows(ApiException.class,
				() -> this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, true, Base58.encode(new byte[16])));
		assertEquals(400, shortEntropy.getResponse().getStatus());

		// The legacy plain-text contract is unaffected by this: it never carries balances/identity,
		// so a null/general-status request remains permitted and returns no wallet-bound data.
		Response plain = this.resource.getPirateChainSyncStatus(ApiCommon.TEST_API_KEY, null, null);
		assertEquals(MediaType.TEXT_PLAIN_TYPE, plain.getMediaType());
	}

	@Test
	public void testStopDisablesWithoutCreatingAReplacementController() {
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		PirateChainWalletController controller = PirateChainWalletController.getInstance();
		assertEquals(ZcashFamilyWalletController.LifecycleState.NEW, controller.getLifecycleState());

		assertEquals("true", this.resource.stopPirateChainSingleton(ApiCommon.TEST_API_KEY));
		assertFalse(Settings.getInstance().isWalletEnabled(PirateChain.CURRENCY_CODE));
		assertEquals(ZcashFamilyWalletController.LifecycleState.TERMINATED, controller.getLifecycleState());
		assertNull(PirateChainWalletController.getInstance());

		assertEquals("true", this.resource.stopPirateChainSingleton(ApiCommon.TEST_API_KEY));
		assertNull(PirateChainWalletController.getInstance());

		assertEquals("true", this.resource.startPirateChainSingleton(ApiCommon.TEST_API_KEY));
		PirateChainWalletController restarted = PirateChainWalletController.getInstance();
		assertNotSame(controller, restarted);
		assertEquals(ZcashFamilyWalletController.LifecycleState.RUNNING, restarted.getLifecycleState());
		assertEquals("true", this.resource.stopPirateChainSingleton(ApiCommon.TEST_API_KEY));
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

	@Test
	public void testStructuredStatusCarriesSnapshotBackendModeAndLastError() throws Exception {
		try {
			setUnifiedWalletEnabled(false);
			PirateChainSyncStatus legacy = CrossChainPirateChainResource.toStructuredStatus(
					ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized")
							.withSnapshot(200L, 200L, "100000000", "90000000", "identityHash58"));
			assertEquals(PirateChainSyncStatus.State.READY, legacy.state);
			assertEquals(Long.valueOf(200), legacy.scannedHeight);
			assertEquals(Long.valueOf(200), legacy.tipHeight);
			assertEquals("100000000", legacy.totalBalanceAtomic);
			assertEquals("90000000", legacy.verifiedBalanceAtomic);
			assertEquals("identityHash58", legacy.walletIdentityHash);
			assertEquals("legacy", legacy.backendMode);
			assertFalse(legacy.stale);
			assertNull(legacy.lastError);

			setUnifiedWalletEnabled(true);
			PirateChainSyncStatus unified = CrossChainPirateChainResource.toStructuredStatus(
					ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));
			assertEquals("unified", unified.backendMode);

			PirateChainSyncStatus degradedWithError = CrossChainPirateChainResource.toStructuredStatus(
					ZcashFamilyWalletController.WalletSyncStatus.degraded("Unavailable until Core restart")
							.withLastError("CORE_RESTART_REQUIRED", "Native wallet is unavailable until Core restart"));
			assertEquals("CORE_RESTART_REQUIRED", degradedWithError.lastError.code);
			assertEquals("Native wallet is unavailable until Core restart", degradedWithError.lastError.message);

			// A stale snapshot cannot be reported READY even though the underlying status was ready.
			PirateChainSyncStatus stale = CrossChainPirateChainResource.toStructuredStatus(
					ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized").asStale());
			assertEquals(PirateChainSyncStatus.State.SYNCHRONIZING, stale.state);
			assertTrue(stale.stale);
		} finally {
			setUnifiedWalletEnabled(false);
		}
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

	private static PirateChainWalletInitializationRequest buildValidInitializationRequest() {
		PirateChainWalletInitializationRequest initializationRequest =
				new PirateChainWalletInitializationRequest();
		initializationRequest.entropy58 = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV";
		initializationRequest.initializationMode = "NEW_AT_CURRENT_TIP";
		return initializationRequest;
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
	public void testKnownNewInitializationValidationMatrix() {
		assertEquals("Missing request body",
				CrossChainPirateChainResource.validateWalletInitializationRequest(null));

		PirateChainWalletInitializationRequest initializationRequest = buildValidInitializationRequest();
		assertNull(CrossChainPirateChainResource.validateWalletInitializationRequest(initializationRequest));

		initializationRequest = buildValidInitializationRequest();
		initializationRequest.entropy58 = "not-base58-!!";
		assertEquals("Invalid entropy bytes",
				CrossChainPirateChainResource.validateWalletInitializationRequest(initializationRequest));

		initializationRequest = buildValidInitializationRequest();
		initializationRequest.entropy58 = "abc";
		assertEquals("Invalid entropy bytes",
				CrossChainPirateChainResource.validateWalletInitializationRequest(initializationRequest));

		initializationRequest = buildValidInitializationRequest();
		initializationRequest.initializationMode = "CONSERVATIVE";
		assertEquals("Initialization mode must be NEW_AT_CURRENT_TIP",
				CrossChainPirateChainResource.validateWalletInitializationRequest(initializationRequest));

		initializationRequest = buildValidInitializationRequest();
		initializationRequest.initializationMode = null;
		assertEquals("Initialization mode must be NEW_AT_CURRENT_TIP",
				CrossChainPirateChainResource.validateWalletInitializationRequest(initializationRequest));
	}

	@Test
	public void testKnownNewInitializationRejectsNonLoopbackRequestsBeforeAnythingElse() {
		CrossChainPirateChainResource remoteResource = (CrossChainPirateChainResource) ApiCommon.buildResource(
				CrossChainPirateChainResource.class,
				ApiCommon.buildRequest("203.0.113.5", ApiCommon.TEST_API_KEY));

		ApiException exception = assertThrows(ApiException.class,
				() -> remoteResource.initializeKnownNewWallet(ApiCommon.TEST_API_KEY,
						buildValidInitializationRequest()));
		assertEquals(403, exception.getResponse().getStatus());
	}

	@Test
	public void testKnownNewInitializationRequiresUnifiedWallet() {
		ApiException exception = assertThrows(ApiException.class,
				() -> this.resource.initializeKnownNewWallet(ApiCommon.TEST_API_KEY,
						buildValidInitializationRequest()));
		assertTrue(String.valueOf(exception.getMessage()).contains("Unified"));
	}

	@Test
	public void testKnownNewInitializationRejectsInvalidRequestBeforeWalletWork() throws Exception {
		setUnifiedWalletEnabled(true);
		try {
			PirateChainWalletInitializationRequest initializationRequest = buildValidInitializationRequest();
			initializationRequest.initializationMode = "CONSERVATIVE";
			ApiException exception = assertThrows(ApiException.class,
					() -> this.resource.initializeKnownNewWallet(ApiCommon.TEST_API_KEY, initializationRequest));
			assertTrue(String.valueOf(exception.getMessage()).contains("NEW_AT_CURRENT_TIP"));
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}

	@Test
	public void testStructuredStatusCarriesRecoveryMarker() {
		PirateChainSyncStatus recovering = CrossChainPirateChainResource.toStructuredStatus(
				ZcashFamilyWalletController.WalletSyncStatus.recovering(
						"Recovering imported keys...", "RECOVERING"));
		assertEquals(PirateChainSyncStatus.State.SYNCHRONIZING, recovering.state);
		assertEquals("RECOVERING", recovering.recoveryState);

		PirateChainSyncStatus readyRecovered = CrossChainPirateChainResource.toStructuredStatus(
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized")
						.withRecoveryMarker("RECOVERED"));
		assertEquals(PirateChainSyncStatus.State.READY, readyRecovered.state);
		assertEquals("RECOVERED", readyRecovered.recoveryState);

		PirateChainSyncStatus plain = CrossChainPirateChainResource.toStructuredStatus(
				ZcashFamilyWalletController.WalletSyncStatus.ready("Synchronized"));
		assertNull(plain.recoveryState);
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

	// ---------------------------------------------------------------- send contract (protocol v1)

	private static final String VALID_SAPLING;
	static {
		byte[] payload = new byte[43];
		for (int i = 0; i < payload.length; i++)
			payload[i] = (byte) (i * 11 + 5);
		VALID_SAPLING = Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", payload);
	}

	private static PirateChainSendRequest buildValidSendRequest() {
		PirateChainSendRequest sendRequest = new PirateChainSendRequest();
		sendRequest.entropy58 = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV";
		sendRequest.receivingAddress = VALID_SAPLING;
		sendRequest.arrrAmount = "1.5";
		sendRequest.memo = "thanks";
		sendRequest.idempotencyKey = "123e4567-e89b-12d3-a456-426614174000";
		return sendRequest;
	}

	private static void assertRejected(PirateChainSendRequest sendRequest, ApiError expectedError, String expectedReason) {
		CrossChainPirateChainResource.SendRequestRejection rejection =
				CrossChainPirateChainResource.validateSendRequest(sendRequest);
		assertNotNull("expected rejection " + expectedReason, rejection);
		assertEquals(expectedError, rejection.error);
		assertEquals(expectedReason, rejection.reason);
		assertTrue(rejection.message().startsWith(expectedReason + ": "));
	}

	@Test
	public void testSendContractAdvertisesProtocolVersionTwoAndFixedFee() {
		PirateChainSendContract contract = this.resource.getPirateChainSendContract(ApiCommon.TEST_API_KEY);
		assertEquals(2, contract.sendProtocolVersion);
        assertEquals(Settings.getInstance().getPirateChainNet().name(), contract.network);
		assertEquals("FIXED", contract.feePolicy);
		assertEquals("10000", contract.feeAtomic);
		assertEquals(8, contract.amountDecimals);
		assertEquals(512, contract.maxMemoBytes);
		assertEquals(java.util.List.of("sapling"), contract.recipientAddressTypes);
	}

	@Test
	public void testSendContractRequiresApiKey() {
		CrossChainPirateChainResource unauthenticated = (CrossChainPirateChainResource) ApiCommon.buildResource(
				CrossChainPirateChainResource.class, ApiCommon.buildRequest("127.0.0.1", "wrong-key"));
		ApiException exception = assertThrows(ApiException.class,
				() -> unauthenticated.getPirateChainSendContract("wrong-key"));
		assertEquals(403, exception.getResponse().getStatus());
		assertEquals(ApiError.UNAUTHORIZED.getCode(), exception.error);
	}

	@Test
	public void testSendValidationMatrixInDocumentedOrder() {
		assertNull(CrossChainPirateChainResource.validateSendRequest(buildValidSendRequest()));

		// 1. body
		assertRejected(null, ApiError.INVALID_DATA, "MISSING_BODY");

		// 2. entropy (128) - checked before everything else even when later fields are bad too
		PirateChainSendRequest sendRequest = buildValidSendRequest();
		sendRequest.entropy58 = null;
		sendRequest.arrrAmount = "bad";
		sendRequest.receivingAddress = "bad";
		assertRejected(sendRequest, ApiError.INVALID_PRIVATE_KEY, "ENTROPY_INVALID");
		sendRequest = buildValidSendRequest();
		sendRequest.entropy58 = "not-base58-!!";
		assertRejected(sendRequest, ApiError.INVALID_PRIVATE_KEY, "ENTROPY_INVALID");
		sendRequest = buildValidSendRequest();
		sendRequest.entropy58 = Base58.encode(new byte[16]);
		assertRejected(sendRequest, ApiError.INVALID_PRIVATE_KEY, "ENTROPY_INVALID");

		// 3. feePerByte (125): any non-null value, including "0", "", or a former default
		sendRequest = buildValidSendRequest();
		sendRequest.feePerByte = "0.00000100";
		sendRequest.idempotencyKey = "bad";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "FEE_PER_BYTE_UNSUPPORTED");
		sendRequest = buildValidSendRequest();
		sendRequest.feePerByte = "";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "FEE_PER_BYTE_UNSUPPORTED");
		sendRequest = buildValidSendRequest();
		sendRequest.feePerByte = "0";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "FEE_PER_BYTE_UNSUPPORTED");

		// 4. idempotencyKey (125): canonical lowercase UUID only
		sendRequest = buildValidSendRequest();
		sendRequest.idempotencyKey = null;
		sendRequest.arrrAmount = "bad";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "IDEMPOTENCY_KEY_INVALID");
		for (String badKey : new String[] { "", "123E4567-E89B-12D3-A456-426614174000",
				"{123e4567-e89b-12d3-a456-426614174000}", "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
				"123e4567e89b12d3a456426614174000", "123e4567-e89b-12d3-a456-42661417400", " 123e4567-e89b-12d3-a456-426614174000" }) {
			sendRequest = buildValidSendRequest();
			sendRequest.idempotencyKey = badKey;
			assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "IDEMPOTENCY_KEY_INVALID");
		}

		// 5. amount (125): reason token comes from the ARRR parser
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = null;
		sendRequest.receivingAddress = "bad";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_MISSING");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "0";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_ZERO");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "1e8";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_NOT_PLAIN_DECIMAL");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "1.000000001";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_NOT_PLAIN_DECIMAL");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "-1";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_NOT_PLAIN_DECIMAL");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "200000001";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_TOO_LARGE");
		sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "99999999999999999999";
		assertRejected(sendRequest, ApiError.INVALID_CRITERIA, "AMOUNT_TOO_LARGE");

		// 6. address (102): unsupported prefixes get their own token, everything else is invalid
		sendRequest = buildValidSendRequest();
		sendRequest.receivingAddress = null;
		sendRequest.memo = "\u0000";
		assertRejected(sendRequest, ApiError.INVALID_ADDRESS, "RECIPIENT_INVALID");
		sendRequest = buildValidSendRequest();
		sendRequest.receivingAddress = "";
		assertRejected(sendRequest, ApiError.INVALID_ADDRESS, "RECIPIENT_INVALID");
		for (String unsupported : new String[] {
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "pirate", new byte[43]),
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "u", new byte[43]),
				"t1KhEjLJv3nMxUQcPqHi3xYjXwbNdq3UzC3",
				"RSbvYmSgn6rnNe8yPgFyrqK8BwWM6hVU7X",
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "ztestsapling", new byte[43]) }) {
			sendRequest = buildValidSendRequest();
			sendRequest.receivingAddress = unsupported;
			assertRejected(sendRequest, ApiError.INVALID_ADDRESS, "RECIPIENT_UNSUPPORTED");
		}
		for (String invalid : new String[] { VALID_SAPLING.toUpperCase(), "Zs" + VALID_SAPLING.substring(2),
				VALID_SAPLING.substring(0, 77) + (VALID_SAPLING.endsWith("q") ? "p" : "q"),
				Bech32.encodeBytes(Bech32.Encoding.BECH32M, "zs", new byte[43]),
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", new byte[42]),
				Bech32.encodeBytes(Bech32.Encoding.BECH32, "zs", new byte[44]),
				" " + VALID_SAPLING, VALID_SAPLING + "\n", "zs1" }) {
			sendRequest = buildValidSendRequest();
			sendRequest.receivingAddress = invalid;
			assertRejected(sendRequest, ApiError.INVALID_ADDRESS, "RECIPIENT_INVALID");
		}

		// 7. memo (115)
		sendRequest = buildValidSendRequest();
		sendRequest.memo = "x".repeat(513);
		assertRejected(sendRequest, ApiError.INVALID_DATA, "MEMO_TOO_LONG");
		sendRequest = buildValidSendRequest();
		sendRequest.memo = "a\u0000b";
		assertRejected(sendRequest, ApiError.INVALID_DATA, "MEMO_CONTROL_CHARACTER");
		sendRequest = buildValidSendRequest();
		sendRequest.memo = "\uD83D";
		assertRejected(sendRequest, ApiError.INVALID_DATA, "MEMO_LONE_SURROGATE");
		sendRequest = buildValidSendRequest();
		sendRequest.memo = null;
		assertNull(CrossChainPirateChainResource.validateSendRequest(sendRequest));
		sendRequest = buildValidSendRequest();
		sendRequest.memo = "";
		assertNull(CrossChainPirateChainResource.validateSendRequest(sendRequest));
	}

	@Test
	public void testSendRejectionMessagesNeverEchoTheRequestFields() {
		PirateChainSendRequest sendRequest = buildValidSendRequest();
		sendRequest.receivingAddress = VALID_SAPLING.toUpperCase();
		String message = CrossChainPirateChainResource.validateSendRequest(sendRequest).message();
		assertFalse(message.contains(VALID_SAPLING.toUpperCase()));
		assertFalse(message.contains(sendRequest.entropy58));

		sendRequest = buildValidSendRequest();
		sendRequest.memo = "private\u0000memo";
		message = CrossChainPirateChainResource.validateSendRequest(sendRequest).message();
		assertFalse(message.contains("private"));

		sendRequest = buildValidSendRequest();
		sendRequest.entropy58 = "not-base58-!!";
		message = CrossChainPirateChainResource.validateSendRequest(sendRequest).message();
		assertFalse(message.contains("not-base58"));
	}

	@Test
	public void testSendNullBodyIsBadRequestNotNullPointer() {
		ApiException exception = assertThrows(ApiException.class,
				() -> this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, null));
		assertEquals(400, exception.getResponse().getStatus());
		assertEquals(ApiError.INVALID_DATA.getCode(), exception.error);
		assertTrue(String.valueOf(exception.getMessage()).startsWith("MISSING_BODY"));
	}

	@Test
	public void testSendValidationRunsBeforeAnyWalletOrModeCheck() throws Exception {
		// Legacy backend + disabled wallet: a malformed request is still reported as such (400),
		// not as an unsupported mode or a not-ready wallet.
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
		PirateChainSendRequest sendRequest = buildValidSendRequest();
		sendRequest.arrrAmount = "1e8";
		ApiException exception = assertThrows(ApiException.class,
				() -> this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, sendRequest));
		assertEquals(400, exception.getResponse().getStatus());
		assertEquals(ApiError.INVALID_CRITERIA.getCode(), exception.error);
		assertTrue(String.valueOf(exception.getMessage()).startsWith("AMOUNT_NOT_PLAIN_DECIMAL"));
	}

	@Test
	public void testSendRejectsLegacyBackendWithInvalidCriteria() {
		// Default test settings run the legacy (non-Unified) backend.
		assertFalse(Settings.getInstance().isPirateChainWalletUnified());
		ApiException exception = assertThrows(ApiException.class,
				() -> this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, buildValidSendRequest()));
		assertEquals(400, exception.getResponse().getStatus());
		assertEquals(ApiError.INVALID_CRITERIA.getCode(), exception.error);
		assertTrue(String.valueOf(exception.getMessage()).startsWith(PirateChain.WALLET_MODE_UNSUPPORTED_REASON));
	}

    @Test public void expectedNetworkMismatchIsRejectedWithoutAdmission() throws Exception {
        setUnifiedWalletEnabled(true);
        try {
            var input = buildValidSendRequest(); input.expectedNetwork = "wrong-network";
            ApiException failure = assertThrows(ApiException.class, () -> resource.sendPirateChain(ApiCommon.TEST_API_KEY, input));
            assertEquals(ApiError.INVALID_CRITERIA.getCode(), failure.error);
            assertEquals("ARRR_SEND_NETWORK_MISMATCH", failure.getMessage());
            assertNull(FieldUtils.readStaticField(org.qortium.crosschain.PirateChainSendRuntime.class, "service", true));
        } finally { setUnifiedWalletEnabled(false); }
    }

	@Test
	public void testSendReportsDisabledWalletAsNotReadyNotNullPointer() throws Exception {
		setUnifiedWalletEnabled(true);
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
		try {
			ApiException exception = assertThrows(ApiException.class,
					() -> this.resource.sendPirateChain(ApiCommon.TEST_API_KEY, buildValidSendRequest()));
			assertEquals(503, exception.getResponse().getStatus());
			assertEquals(ApiError.FOREIGN_WALLET_NOT_READY.getCode(), exception.error);
			assertEquals(1205, exception.error);
			assertEquals(PirateChain.WALLET_DISABLED_REASON, exception.getMessage());
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}

	@Test
	public void testSendCoinsGuardsDisabledAndLegacyWalletsWithoutNullPointer() throws Exception {
		Settings.getInstance().enableWallet(PirateChain.CURRENCY_CODE);
		PirateChain pirateChain = PirateChain.getInstance();
		assertNotNull(pirateChain);

		// isValidAddress now delegates to the canonical Sapling check (trade paths use it too).
		assertTrue(pirateChain.isValidAddress(VALID_SAPLING));
		assertFalse(pirateChain.isValidAddress(VALID_SAPLING.toUpperCase()));
		assertFalse(pirateChain.isValidAddress(Bech32.encodeBytes(Bech32.Encoding.BECH32M, "zs", new byte[43])));

		// Legacy backend: refused before any controller/native work.
		assertFalse(Settings.getInstance().isPirateChainWalletUnified());
		ForeignBlockchainException.WalletNotReadyException legacy = assertThrows(
				ForeignBlockchainException.WalletNotReadyException.class,
				() -> org.qortium.crosschain.PirateSendTestEntry.send(buildValidSendRequest().entropy58, VALID_SAPLING, 150_000_000L, null));
		assertEquals(PirateChain.WALLET_MODE_UNSUPPORTED_REASON, legacy.getMessage());

		// Unified backend but wallet disabled: the controller singleton is null, which used to NPE.
		setUnifiedWalletEnabled(true);
		Settings.getInstance().disableWallet(PirateChain.CURRENCY_CODE);
		try {
			assertNull(PirateChainWalletController.getInstance());
			ForeignBlockchainException.WalletNotReadyException disabled = assertThrows(
					ForeignBlockchainException.WalletNotReadyException.class,
					() -> org.qortium.crosschain.PirateSendTestEntry.send(buildValidSendRequest().entropy58, VALID_SAPLING, 150_000_000L, null));
			assertEquals(PirateChain.WALLET_DISABLED_REASON, disabled.getMessage());
		} finally {
			setUnifiedWalletEnabled(false);
		}
	}
}
