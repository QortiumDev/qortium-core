package org.qortium.controller;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.account.AccountData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.PeerData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.transform.block.BlockTransformer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiteNodeTests {

	@Before
	public void before() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testLiteDataCapabilityRequiresSupportedNumericVersion() {
		assertFalse(LiteNode.isSupportedLiteDataCapability(null));
		assertFalse(LiteNode.isSupportedLiteDataCapability("1"));
		assertFalse(LiteNode.isSupportedLiteDataCapability(0));
		assertFalse(LiteNode.isSupportedLiteDataCapability(1));

		assertTrue(LiteNode.isSupportedLiteDataCapability(2L));
	}

	@Test
	public void testTransactionLimitNormalization() {
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST, LiteNode.normalizeTransactionLimit(0));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST, LiteNode.normalizeTransactionLimit(-1));
		assertEquals(25, LiteNode.normalizeTransactionLimit(25));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST,
				LiteNode.normalizeTransactionLimit(LiteNode.MAX_TRANSACTIONS_PER_REQUEST));
		assertEquals(LiteNode.MAX_TRANSACTIONS_PER_REQUEST,
				LiteNode.normalizeTransactionLimit(LiteNode.MAX_TRANSACTIONS_PER_REQUEST + 1));
	}

	@Test
	public void testPrefersUniqueLargestChainTipGroup() {
		Peer firstMajorityPeer = peerWithChainTip(1, bytes(64, 1));
		Peer minorityPeer = peerWithChainTip(2, bytes(64, 2));
		Peer secondMajorityPeer = peerWithChainTip(3, bytes(64, 1));

		List<Peer> peers = Arrays.asList(firstMajorityPeer, minorityPeer, secondMajorityPeer);

		assertEquals(Arrays.asList(firstMajorityPeer, secondMajorityPeer),
				LiteNode.preferChainTipAgreementPeers(peers));
	}

	@Test
	public void testTiedChainTipGroupsKeepAllPeers() {
		Peer firstPeer = peerWithChainTip(1, bytes(64, 1));
		Peer secondPeer = peerWithChainTip(2, bytes(64, 1));
		Peer thirdPeer = peerWithChainTip(3, bytes(64, 2));
		Peer fourthPeer = peerWithChainTip(4, bytes(64, 2));

		List<Peer> peers = Arrays.asList(firstPeer, secondPeer, thirdPeer, fourthPeer);

		assertEquals(peers, LiteNode.preferChainTipAgreementPeers(peers));
	}

	@Test
	public void testChainTipGroupsBelowAgreementKeepAllPeers() {
		Peer firstPeer = peerWithChainTip(1, bytes(64, 1));
		Peer secondPeer = peerWithChainTip(2, bytes(64, 2));
		Peer thirdPeer = peerWithChainTip(3, bytes(64, 3));

		List<Peer> peers = Arrays.asList(firstPeer, secondPeer, thirdPeer);

		assertEquals(peers, LiteNode.preferChainTipAgreementPeers(peers));
	}

	@Test
	public void testMissingChainTipDataDoesNotCreatePreferredGroup() {
		Peer missingTipPeer = peerWithoutChainTip(1);
		Peer missingSignaturePeer = peerWithChainTip(2, null);
		Peer validTipPeer = peerWithChainTip(3, bytes(64, 3));

		List<Peer> peers = Arrays.asList(missingTipPeer, missingSignaturePeer, validTipPeer);

		assertEquals(peers, LiteNode.preferChainTipAgreementPeers(peers));
	}

	@Test
	public void testStatsStartAtZero() {
		LiteNode liteNode = new LiteNode();

		assertEquals(0, liteNode.stats.requests.get());
		assertEquals(0, liteNode.stats.noCapablePeers.get());
		assertEquals(0, liteNode.stats.peerAttempts.get());
		assertEquals(0, liteNode.stats.emptyResponses.get());
		assertEquals(0, liteNode.stats.unexpectedResponses.get());
		assertEquals(0, liteNode.stats.successfulResponses.get());
		assertEquals(0, liteNode.stats.interruptedRequests.get());
	}

	@Test
	public void testTwoMatchingDataResponsesAgree() {
		LiteDataAnchor anchor = anchor(1);
		String fingerprint = anchoredFingerprint("same", anchor);

		LiteNode.LiteDataResult<String> result = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.data("first", fingerprint, anchor),
				LiteNode.LiteDataCandidate.data("second", fingerprint, anchor),
				LiteNode.LiteDataCandidate.data("third", anchoredFingerprint("different", anchor), anchor)));

		assertEquals(LiteNode.LiteDataStatus.AGREED, result.getStatus());
		assertEquals("first", result.getValue());
		assertEquals(anchor.getHeight(), result.getAnchor().getHeight());
	}

	@Test
	public void testTwoUnknownResponsesReturnUnknown() {
		LiteDataAnchor anchor = anchor(1);
		String fingerprint = LiteNode.liteDataAnchorFingerprint(anchor);

		LiteNode.LiteDataResult<String> result = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.unknown(fingerprint, anchor),
				LiteNode.LiteDataCandidate.unknown(fingerprint, anchor)));

		assertEquals(LiteNode.LiteDataStatus.UNKNOWN, result.getStatus());
		assertEquals(anchor.getHeight(), result.getAnchor().getHeight());
	}

	@Test
	public void testNoUsableAgreementReturnsUnavailable() {
		LiteDataAnchor anchor = anchor(1);

		LiteNode.LiteDataResult<String> noResponses = LiteNode.chooseAgreedResult(Collections.emptyList());
		assertEquals(LiteNode.LiteDataStatus.UNAVAILABLE, noResponses.getStatus());

		LiteNode.LiteDataResult<String> oneResponse = LiteNode.chooseAgreedResult(Collections.singletonList(
				LiteNode.LiteDataCandidate.data("first", anchoredFingerprint("same", anchor), anchor)));
		assertEquals(LiteNode.LiteDataStatus.UNAVAILABLE, oneResponse.getStatus());
	}

	@Test
	public void testDisagreeingResponsesConflict() {
		LiteDataAnchor anchor = anchor(1);

		LiteNode.LiteDataResult<String> dataVsUnknown = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.data("first", anchoredFingerprint("same", anchor), anchor),
				LiteNode.LiteDataCandidate.unknown(LiteNode.liteDataAnchorFingerprint(anchor), anchor)));
		assertEquals(LiteNode.LiteDataStatus.CONFLICTED, dataVsUnknown.getStatus());

		LiteNode.LiteDataResult<String> mismatchedData = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.data("first", anchoredFingerprint("first", anchor), anchor),
				LiteNode.LiteDataCandidate.data("second", anchoredFingerprint("second", anchor), anchor)));
		assertEquals(LiteNode.LiteDataStatus.CONFLICTED, mismatchedData.getStatus());
	}

	@Test
	public void testMatchingDataWithDifferentAnchorsConflicts() {
		LiteDataAnchor firstAnchor = anchor(1);
		LiteDataAnchor secondAnchor = anchor(2);

		LiteNode.LiteDataResult<String> result = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.data("first", anchoredFingerprint("same", firstAnchor), firstAnchor),
				LiteNode.LiteDataCandidate.data("second", anchoredFingerprint("same", secondAnchor), secondAnchor)));

		assertEquals(LiteNode.LiteDataStatus.CONFLICTED, result.getStatus());
	}

	@Test
	public void testUnknownResponsesWithDifferentAnchorsConflict() {
		LiteDataAnchor firstAnchor = anchor(1);
		LiteDataAnchor secondAnchor = anchor(2);

		LiteNode.LiteDataResult<String> result = LiteNode.chooseAgreedResult(Arrays.asList(
				LiteNode.LiteDataCandidate.unknown(LiteNode.liteDataAnchorFingerprint(firstAnchor), firstAnchor),
				LiteNode.LiteDataCandidate.unknown(LiteNode.liteDataAnchorFingerprint(secondAnchor), secondAnchor)));

		assertEquals(LiteNode.LiteDataStatus.CONFLICTED, result.getStatus());
	}

	@Test
	public void testNonComparableResponseConflicts() {
		LiteNode.LiteDataResult<String> result = LiteNode.chooseAgreedResult(Collections.singletonList(
				LiteNode.LiteDataCandidate.conflicted()));

		assertEquals(LiteNode.LiteDataStatus.CONFLICTED, result.getStatus());
	}

	@Test
	public void testAccountDataFingerprintCoversMessageFields() {
		AccountData accountData = new AccountData("Qaddress", bytes(32, 1), Group.NO_GROUP, 2, 3);
		AccountData matchingAccountData = new AccountData("Qaddress", bytes(32, 1), Group.NO_GROUP, 2, 3);
		AccountData changedAccountData = new AccountData("Qaddress", bytes(32, 2), Group.NO_GROUP, 2, 3);

		assertEquals(LiteNode.accountDataFingerprint(accountData), LiteNode.accountDataFingerprint(matchingAccountData));
		assertFalse(LiteNode.accountDataFingerprint(accountData).equals(LiteNode.accountDataFingerprint(changedAccountData)));
	}

	@Test
	public void testAccountBalanceFingerprintCoversMessageFields() {
		AccountBalanceData accountBalanceData = new AccountBalanceData("Qaddress", 0L, 100L);
		AccountBalanceData matchingAccountBalanceData = new AccountBalanceData("Qaddress", 0L, 100L);
		AccountBalanceData changedAccountBalanceData = new AccountBalanceData("Qaddress", 0L, 101L);

		assertEquals(LiteNode.accountBalanceFingerprint(accountBalanceData),
				LiteNode.accountBalanceFingerprint(matchingAccountBalanceData));
		assertFalse(LiteNode.accountBalanceFingerprint(accountBalanceData).equals(
				LiteNode.accountBalanceFingerprint(changedAccountBalanceData)));
	}

	@Test
	public void testNameFingerprintCoversMessageFields() {
		NameData nameData = nameData("name", "owner", 100L, bytes(64, 1));
		NameData matchingNameData = nameData("name", "owner", 100L, bytes(64, 1));
		NameData changedNameData = nameData("name", "owner", 101L, bytes(64, 1));

		assertEquals(LiteNode.nameDataFingerprint(nameData), LiteNode.nameDataFingerprint(matchingNameData));
		assertFalse(LiteNode.nameDataFingerprint(nameData).equals(LiteNode.nameDataFingerprint(changedNameData)));
	}

	@Test
	public void testNameListFingerprintUsesSortedNameFingerprints() {
		NameData firstNameData = nameData("first", "owner", 100L, bytes(64, 1));
		NameData secondNameData = nameData("second", "owner", 101L, bytes(64, 2));

		assertEquals(LiteNode.nameDataListFingerprint(Arrays.asList(firstNameData, secondNameData)),
				LiteNode.nameDataListFingerprint(Arrays.asList(secondNameData, firstNameData)));
	}

	@Test
	public void testTransactionListFingerprintUsesSortedSignatures() {
		TransactionData firstTransaction = paymentTransaction(bytes(64, 1));
		TransactionData secondTransaction = paymentTransaction(bytes(64, 2));
		TransactionData changedTransaction = paymentTransaction(bytes(64, 3));

		assertEquals(LiteNode.transactionListFingerprint(Arrays.asList(firstTransaction, secondTransaction)),
				LiteNode.transactionListFingerprint(Arrays.asList(secondTransaction, firstTransaction)));
		assertFalse(LiteNode.transactionListFingerprint(Collections.singletonList(firstTransaction)).equals(
				LiteNode.transactionListFingerprint(Collections.singletonList(changedTransaction))));
	}

	@Test
	public void testUnsignedTransactionListIsNotAgreeable() {
		TransactionData unsignedTransaction = paymentTransaction(null);

		assertEquals(null, LiteNode.transactionListFingerprint(Collections.singletonList(unsignedTransaction)));
	}

	private static NameData nameData(String name, String owner, long registered, byte[] reference) {
		return new NameData(name, name, owner, "{}", registered, null, false, null, null, reference, Group.NO_GROUP);
	}

	private static TransactionData paymentTransaction(byte[] signature) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(1L, Group.NO_GROUP, bytes(32, 1), 1L, signature);
		return new PaymentTransactionData(baseTransactionData, "recipient", 1L);
	}

	private static Peer peerWithChainTip(int index, byte[] signature) {
		Peer peer = peerWithoutChainTip(index);
		peer.setChainTipData(new BlockSummaryData(index, signature, bytes(32, index), index));
		return peer;
	}

	private static Peer peerWithoutChainTip(int index) {
		return new Peer(new PeerData(PeerAddress.fromString("127.0.0." + index + ":1234")), Peer.NETWORK);
	}

	private static LiteDataAnchor anchor(int seed) {
		return new LiteDataAnchor(seed, bytes(BlockTransformer.BLOCK_SIGNATURE_LENGTH, seed), 1000L + seed);
	}

	private static String anchoredFingerprint(String payloadFingerprint, LiteDataAnchor anchor) {
		return LiteNode.anchoredDataFingerprint(payloadFingerprint, LiteNode.liteDataAnchorFingerprint(anchor));
	}

	private static byte[] bytes(int size, int seed) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

}
