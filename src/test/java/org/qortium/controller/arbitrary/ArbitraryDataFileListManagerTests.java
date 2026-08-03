package org.qortium.controller.arbitrary;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.ArbitraryDataFile;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataStorageManager.StoragePolicy;
import org.qortium.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortium.data.network.PeerData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.network.message.ArbitraryDataFileListMessage;
import org.qortium.network.message.Message;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;
import org.qortium.utils.Triple;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataFileListManagerTests extends Common {

    private ArbitraryDataFileListManager fileListManager;
    private ArbitraryDataFileManager fileManager;

    @Before
    public void beforeTest() throws Exception {
        Common.useDefaultSettings();
        FieldUtils.writeField(ArbitraryDataManager.getInstance(), "powDifficultyOverride", 1, true);
        this.fileListManager = ArbitraryDataFileListManager.getInstance();
        this.fileManager = ArbitraryDataFileManager.getInstance();
        clearRelayState();
    }

    @After
    public void afterTest() throws Exception {
        clearRelayState();
        Common.useDefaultSettings();
    }

    @Test
    public void testLegacyFalseSettingStillForwardsRelayResponseWithoutLocalDownload() throws Exception {
        byte[] signature;
        try (Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "RELAY-TEST";

            RegisterNameTransactionData registerName = new RegisterNameTransactionData(
                    TestTransaction.generateBase(alice), name, "");
            registerName.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerName.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerName, alice);

            Path dataPath = ArbitraryUtils.generateRandomDataPath(128);
            ArbitraryDataFile dataFile = ArbitraryUtils.createAndMintTxn(repository,
                    Base58.encode(alice.getPublicKey()), dataPath, name, null,
                    ArbitraryTransactionData.Method.PUT, Service.ARBITRARY_DATA, alice, 64);
            signature = dataFile.getSignature();
        }

        Path legacySettings = Files.createTempFile("legacy-relay-setting", ".json");
        Files.write(legacySettings, "{\"relayModeEnabled\":false}\n".getBytes(StandardCharsets.UTF_8));
        Settings.fileInstance(legacySettings.toString());
        assertEquals(StoragePolicy.FOLLOWED_OR_VIEWED, Settings.getInstance().getStoragePolicy());

        byte[] advertisedHash = new byte[32];
        Arrays.fill(advertisedHash, (byte) 7);
        List<byte[]> hashes = List.of(advertisedHash);
        long now = NTP.getTime();
        int requestId = 41001;

        CapturingPeer requester = new CapturingPeer("127.0.0.1:9100");
        CapturingPeer firstHolder = new CapturingPeer("127.0.0.1:9101");
        CapturingPeer secondHolder = new CapturingPeer("127.0.0.1:9102");
        String signature58 = Base58.encode(signature);
        this.fileListManager.arbitraryDataFileListRequests.put(requestId,
                new Triple<>(signature58, requester, now));

        ArbitraryDataFileListMessage firstResponse = incomingFileList(requestId, signature, hashes,
                now, 1, "127.0.0.1:9101", "holder-1");
        ArbitraryDataFileListMessage secondResponse = incomingFileList(requestId, signature, hashes,
                now, 1, "127.0.0.1:9102", "holder-2");

        this.fileListManager.processNetworkArbitraryDataFileListMessages(List.of(
                new PeerMessage(firstHolder, firstResponse),
                new PeerMessage(secondHolder, secondResponse)));

        assertEquals(1, requester.sentMessages.size());
        ArbitraryDataFileListMessage forwarded = parseFileList(requester.sentMessages.get(0));
        assertEquals(requestId, forwarded.getId());
        assertArrayEquals(signature, forwarded.getSignature());
        assertEquals(1, forwarded.getHashes().size());
        assertArrayEquals(advertisedHash, forwarded.getHashes().get(0));
        assertEquals(Integer.valueOf(2), forwarded.getRequestHops());

        assertEquals(1, this.fileManager.arbitraryRelayMap.size());
        assertEquals(firstHolder.getPeerData(), this.fileManager.arbitraryRelayMap.get(0).getPeerData());
        assertEquals(0, localResponseCount());

        int directRequestId = 41002;
        this.fileListManager.arbitraryDataFileListRequests.put(directRequestId,
                new Triple<>(signature58, null, now));
        ArbitraryDataFileListMessage directResponse = incomingFileList(directRequestId, signature, hashes,
                now, 1, "127.0.0.1:9101", "holder-1");
        this.fileListManager.processNetworkArbitraryDataFileListMessages(
                List.of(new PeerMessage(firstHolder, directResponse)));

        assertEquals(1, requester.sentMessages.size());
        assertEquals(1, localResponseCount());
    }

    @Test
    public void testDiscoveryRetryLadderTiersAreReachable() {
        long now = NTP.getTime();
        String signature58 = "test-ladder-signature";

        // Tier 3 (15-minute cadence) must be reachable after the 40-attempt tier is exhausted.
        // Before the fix, its condition checked `count < 16` and could never fire.
        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(40, 0, now - 16 * 60 * 1000L));
        assertTrue(this.fileListManager.shouldMakeFileListRequestForSignature(signature58));

        // But not before 15 minutes have passed
        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(40, 0, now - 5 * 60 * 1000L));
        assertFalse(this.fileListManager.shouldMakeFileListRequestForSignature(signature58));

        // Terminal backoff is 30 minutes (was 6 hours)
        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(48, 0, now - 31 * 60 * 1000L));
        assertTrue(this.fileListManager.shouldMakeFileListRequestForSignature(signature58));

        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(48, 0, now - 20 * 60 * 1000L));
        assertFalse(this.fileListManager.shouldMakeFileListRequestForSignature(signature58));

        // A usable file list response refreshes the retry budget back into the 1-minute tier
        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(47, 3, now - 20 * 60 * 1000L));
        this.fileListManager.notedUsableFileListResponse(signature58);
        Triple<Integer, Integer, Long> refreshed = this.fileListManager.arbitraryDataSignatureRequests.get(signature58);
        assertEquals(Integer.valueOf(12), refreshed.getA());
        assertEquals(Integer.valueOf(3), refreshed.getB()); // direct-request count untouched
        assertTrue(this.fileListManager.shouldMakeFileListRequestForSignature(signature58));

        // But it never increases a small count
        this.fileListManager.arbitraryDataSignatureRequests.put(signature58,
                new Triple<>(3, 0, now));
        this.fileListManager.notedUsableFileListResponse(signature58);
        assertEquals(Integer.valueOf(3), this.fileListManager.arbitraryDataSignatureRequests.get(signature58).getA());

        this.fileListManager.arbitraryDataSignatureRequests.remove(signature58);
    }

    @Test
    public void testDirectConnectableResponseRecordsRelayFallback() throws Exception {
        byte[] signature;
        try (Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            String name = "FALLBACK-TEST";

            RegisterNameTransactionData registerName = new RegisterNameTransactionData(
                    TestTransaction.generateBase(alice), name, "");
            registerName.setFee(new RegisterNameTransaction(null, null).getUnitFee(registerName.getTimestamp()));
            TransactionUtils.signAndMint(repository, registerName, alice);

            Path dataPath = ArbitraryUtils.generateRandomDataPath(128);
            ArbitraryDataFile dataFile = ArbitraryUtils.createAndMintTxn(repository,
                    Base58.encode(alice.getPublicKey()), dataPath, name, null,
                    ArbitraryTransactionData.Method.PUT, Service.ARBITRARY_DATA, alice, 64);
            signature = dataFile.getSignature();
        }

        byte[] advertisedHash = new byte[32];
        Arrays.fill(advertisedHash, (byte) 9);
        List<byte[]> hashes = List.of(advertisedHash);
        long now = NTP.getTime();
        int requestId = 41003;

        // A response for our own (non-relay) request: sender 9103 advertises holder 9104 as
        // directly connectable. The recorded response must keep the sender as relay fallback.
        CapturingPeer sender = new CapturingPeer("127.0.0.1:9103");
        String signature58 = Base58.encode(signature);
        this.fileListManager.arbitraryDataFileListRequests.put(requestId,
                new Triple<>(signature58, null, now));

        ArbitraryDataFileListMessage outgoing = new ArbitraryDataFileListMessage(signature, hashes,
                now, 1, "127.0.0.1:9104", "holder-3", true, true); // isRelayPossible, isDirectConnectable
        outgoing.setId(requestId);
        ArbitraryDataFileListMessage response = parseFileList(outgoing);
        this.fileListManager.processNetworkArbitraryDataFileListMessages(
                List.of(new PeerMessage(sender, response)));

        List<ArbitraryFileListResponseInfo> responses = localResponses();
        assertEquals(1, responses.size());
        ArbitraryFileListResponseInfo responseInfo = responses.get(0);
        assertEquals(Boolean.TRUE, responseInfo.isDirectConnectable());
        assertEquals("127.0.0.1:9104", responseInfo.getPeerData().getAddress().toString());
        assertNotNull(responseInfo.getRelayFallbackPeerData());
        assertEquals(sender.getPeerData(), responseInfo.getRelayFallbackPeerData());
    }

    private static ArbitraryDataFileListMessage incomingFileList(int id, byte[] signature, List<byte[]> hashes,
                                                                  long requestTime, int requestHops,
                                                                  String peerAddress, String nodeId) throws Exception {
        ArbitraryDataFileListMessage outgoing = new ArbitraryDataFileListMessage(signature, hashes,
                requestTime, requestHops, peerAddress, nodeId, true, false);
        outgoing.setId(id);
        return parseFileList(outgoing);
    }

    private static ArbitraryDataFileListMessage parseFileList(Message message) throws Exception {
        Message parsed = Message.fromByteBuffer(ByteBuffer.wrap(message.toBytes()));
        assertNotNull(parsed);
        return (ArbitraryDataFileListMessage) parsed;
    }

    @SuppressWarnings("unchecked")
    private List<ArbitraryFileListResponseInfo> localResponses() throws IllegalAccessException {
        return (List<ArbitraryFileListResponseInfo>)
                FieldUtils.readField(this.fileManager, "arbitraryDataFileHashResponses", true);
    }

    private int localResponseCount() throws IllegalAccessException {
        return localResponses().size();
    }

    @SuppressWarnings("unchecked")
    private void clearRelayState() throws IllegalAccessException {
        this.fileListManager.arbitraryDataFileListRequests.clear();
        this.fileManager.arbitraryRelayMap.clear();
        List<ArbitraryFileListResponseInfo> responses = (List<ArbitraryFileListResponseInfo>)
                FieldUtils.readField(this.fileManager, "arbitraryDataFileHashResponses", true);
        responses.clear();
    }

    private static class CapturingPeer extends Peer {
        private final List<Message> sentMessages = Collections.synchronizedList(new ArrayList<>());

        private CapturingPeer(String address) {
            super(new PeerData(new PeerAddress(address)), Peer.NETWORKDATA);
        }

        @Override
        public boolean sendMessage(Message message) {
            this.sentMessages.add(message);
            return true;
        }
    }
}
