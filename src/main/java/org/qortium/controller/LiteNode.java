package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.account.AccountData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.data.naming.NameData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.message.*;
import org.qortium.utils.Base58;
import org.qortium.utils.ByteArray;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.qortium.network.message.MessageType.*;

public class LiteNode {

    private static final Logger LOGGER = LogManager.getLogger(LiteNode.class);

    public static final String LITE_DATA_CAPABILITY = "LITE_DATA";
    public static final int LITE_DATA_CAPABILITY_VERSION = 2;
    public static final int REQUIRED_LITE_DATA_AGREEMENT = 2;
    public static final int MAX_LITE_DATA_PEER_ATTEMPTS = 3;
    public static final int MAX_NAMES_PER_MESSAGE = 100;
    public static final int MAX_TRANSACTIONS_PER_MESSAGE = 100;
    public static final int MAX_TRANSACTIONS_PER_REQUEST = 500;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static LiteNode instance;


    public Map<Integer, Long> pendingRequests = Collections.synchronizedMap(new HashMap<>());

    final StatsSnapshot stats = new StatsSnapshot();

    static class StatsSnapshot {
        public final AtomicLong requests = new AtomicLong();
        public final AtomicLong noCapablePeers = new AtomicLong();
        public final AtomicLong peerAttempts = new AtomicLong();
        public final AtomicLong emptyResponses = new AtomicLong();
        public final AtomicLong unexpectedResponses = new AtomicLong();
        public final AtomicLong successfulResponses = new AtomicLong();
        public final AtomicLong interruptedRequests = new AtomicLong();
    }

    public enum LiteDataStatus {
        AGREED,
        UNKNOWN,
        UNAVAILABLE,
        CONFLICTED
    }

    public static final class LiteDataResult<T> {
        private final LiteDataStatus status;
        private final T value;
        private final LiteDataAnchor anchor;

        private LiteDataResult(LiteDataStatus status, T value, LiteDataAnchor anchor) {
            this.status = status;
            this.value = value;
            this.anchor = anchor;
        }

        public static <T> LiteDataResult<T> agreed(T value) {
            return agreed(value, null);
        }

        public static <T> LiteDataResult<T> agreed(T value, LiteDataAnchor anchor) {
            return new LiteDataResult<>(LiteDataStatus.AGREED, value, anchor);
        }

        public static <T> LiteDataResult<T> unknown() {
            return unknown(null);
        }

        public static <T> LiteDataResult<T> unknown(LiteDataAnchor anchor) {
            return new LiteDataResult<>(LiteDataStatus.UNKNOWN, null, anchor);
        }

        public static <T> LiteDataResult<T> unavailable() {
            return new LiteDataResult<>(LiteDataStatus.UNAVAILABLE, null, null);
        }

        public static <T> LiteDataResult<T> conflicted() {
            return new LiteDataResult<>(LiteDataStatus.CONFLICTED, null, null);
        }

        public LiteDataStatus getStatus() {
            return this.status;
        }

        public T getValue() {
            return this.value;
        }

        public LiteDataAnchor getAnchor() {
            return this.anchor;
        }

        public boolean isAgreed() {
            return this.status == LiteDataStatus.AGREED;
        }
    }

    private enum LiteDataCandidateType {
        DATA,
        UNKNOWN,
        CONFLICTED
    }

    static final class LiteDataCandidate<T> {
        private final LiteDataCandidateType type;
        private final T value;
        private final String fingerprint;
        private final LiteDataAnchor anchor;

        private LiteDataCandidate(LiteDataCandidateType type, T value, String fingerprint, LiteDataAnchor anchor) {
            this.type = type;
            this.value = value;
            this.fingerprint = fingerprint;
            this.anchor = anchor;
        }

        static <T> LiteDataCandidate<T> data(T value, String fingerprint) {
            return data(value, fingerprint, null);
        }

        static <T> LiteDataCandidate<T> data(T value, String fingerprint, LiteDataAnchor anchor) {
            return new LiteDataCandidate<>(LiteDataCandidateType.DATA, value, fingerprint, anchor);
        }

        static <T> LiteDataCandidate<T> unknown() {
            return unknown(null, null);
        }

        static <T> LiteDataCandidate<T> unknown(String fingerprint, LiteDataAnchor anchor) {
            return new LiteDataCandidate<>(LiteDataCandidateType.UNKNOWN, null, fingerprint, anchor);
        }

        static <T> LiteDataCandidate<T> conflicted() {
            return new LiteDataCandidate<>(LiteDataCandidateType.CONFLICTED, null, null, null);
        }
    }

    private static final class AnchoredLiteData<T> {
        private final LiteDataResponseStatus status;
        private final T value;
        private final LiteDataAnchor anchor;

        private AnchoredLiteData(LiteDataResponseStatus status, T value, LiteDataAnchor anchor) {
            this.status = status;
            this.value = value;
            this.anchor = anchor;
        }

        static <T> AnchoredLiteData<T> data(T value, LiteDataAnchor anchor) {
            return new AnchoredLiteData<>(LiteDataResponseStatus.DATA, value, anchor);
        }

        static <T> AnchoredLiteData<T> unknown(LiteDataAnchor anchor) {
            return new AnchoredLiteData<>(LiteDataResponseStatus.UNKNOWN, null, anchor);
        }
    }

    public LiteNode() {

    }

    public static synchronized LiteNode getInstance() {
        if (instance == null) {
            instance = new LiteNode();
        }

        return instance;
    }


    /**
     * Fetch account data from peers for given account address
     * @param address - the account address to query
     * @return accountData - the account data for this address, or null if not retrieved
     */
    public AccountData fetchAccountData(String address) {
        return agreedValueOrNull(fetchAccountDataResult(address));
    }

    public LiteDataResult<AccountData> fetchAccountDataResult(String address) {
        GetAccountMessage getAccountMessage = new GetAccountMessage(address);
        return this.sendMessageResult(getAccountMessage, ACCOUNT,
                message -> {
                    AccountMessage accountMessage = (AccountMessage) message;
                    return anchoredLiteData(accountMessage.getStatus(), accountMessage.getAnchor(), accountMessage.getAccountData());
                },
                LiteNode::accountDataFingerprint);
    }

    /**
     * Fetch account balance data from peers for given account address and asset ID
     * @param address - the account address to query
     * @return balance - the balance for this address and assetId, or null if not retrieved
     */
    public AccountBalanceData fetchAccountBalance(String address, long assetId) {
        return agreedValueOrNull(fetchAccountBalanceResult(address, assetId));
    }

    public LiteDataResult<AccountBalanceData> fetchAccountBalanceResult(String address, long assetId) {
        GetAccountBalanceMessage getAccountMessage = new GetAccountBalanceMessage(address, assetId);
        return this.sendMessageResult(getAccountMessage, ACCOUNT_BALANCE,
                message -> {
                    AccountBalanceMessage accountBalanceMessage = (AccountBalanceMessage) message;
                    return anchoredLiteData(accountBalanceMessage.getStatus(), accountBalanceMessage.getAnchor(),
                            accountBalanceMessage.getAccountBalanceData());
                },
                LiteNode::accountBalanceFingerprint);
    }

    /**
     * Fetch list of transactions for given account address
     * @param address - the account address to query
     * @param limit - the maximum number of results to return
     * @param offset - the starting index
     * @return a list of TransactionData objects, or null if not retrieved
     */
    public List<TransactionData> fetchAccountTransactions(String address, int limit, int offset) {
        return agreedValueOrNull(fetchAccountTransactionsResult(address, limit, offset));
    }

    public LiteDataResult<List<TransactionData>> fetchAccountTransactionsResult(String address, int limit, int offset) {
        limit = normalizeTransactionLimit(limit);

        List<TransactionData> allTransactions = new ArrayList<>();
        LiteDataAnchor agreedAnchor = null;
        while (allTransactions.size() < limit) {
            final int requestLimit = Math.min(MAX_TRANSACTIONS_PER_MESSAGE, limit - allTransactions.size());
            GetAccountTransactionsMessage getAccountTransactionsMessage = new GetAccountTransactionsMessage(address, requestLimit, offset);
            LiteDataResult<List<TransactionData>> result = this.sendMessageResult(getAccountTransactionsMessage, TRANSACTIONS,
                    message -> {
                        TransactionsMessage transactionsMessage = (TransactionsMessage) message;
                        if (transactionsMessage.getStatus() == LiteDataResponseStatus.UNKNOWN)
                            return AnchoredLiteData.unknown(transactionsMessage.getAnchor());

                        List<TransactionData> transactions = transactionsMessage.getTransactions();
                        if (transactions == null)
                            return null;

                        if (transactions.size() > requestLimit)
                            transactions = transactions.subList(0, requestLimit);

                        return AnchoredLiteData.data(new ArrayList<>(transactions), transactionsMessage.getAnchor());
                    },
                    LiteNode::transactionListFingerprint);

            if (!result.isAgreed())
                return result;

            List<TransactionData> transactions = result.getValue();
            agreedAnchor = result.getAnchor();

            allTransactions.addAll(transactions);
            if (transactions.size() < requestLimit) {
                // No more transactions to fetch
                break;
            }
            offset += requestLimit;
        }
        return LiteDataResult.agreed(allTransactions, agreedAnchor);
    }

    /**
     * Fetch list of names for given account address
     * @param address - the account address to query
     * @return a list of NameData objects, or null if not retrieved
     */
    public List<NameData> fetchAccountNames(String address) {
        return agreedValueOrNull(fetchAccountNamesResult(address));
    }

    public LiteDataResult<List<NameData>> fetchAccountNamesResult(String address) {
        GetAccountNamesMessage getAccountNamesMessage = new GetAccountNamesMessage(address);
        return this.sendMessageResult(getAccountNamesMessage, NAMES,
                message -> {
                    NamesMessage namesMessage = (NamesMessage) message;
                    if (namesMessage.getStatus() == LiteDataResponseStatus.UNKNOWN)
                        return AnchoredLiteData.unknown(namesMessage.getAnchor());

                    List<NameData> nameDataList = namesMessage.getNameDataList();
                    return nameDataList == null ? null : AnchoredLiteData.data(new ArrayList<>(nameDataList), namesMessage.getAnchor());
                },
                LiteNode::nameDataListFingerprint);
    }

    /**
     * Fetch info about a registered name
     * @param name - the name to query
     * @return a NameData object, or null if not retrieved
     */
    public NameData fetchNameData(String name) {
        return agreedValueOrNull(fetchNameDataResult(name));
    }

    public LiteDataResult<NameData> fetchNameDataResult(String name) {
        GetNameMessage getNameMessage = new GetNameMessage(name);
        return this.sendMessageResult(getNameMessage, NAMES,
                message -> {
                    NamesMessage namesMessage = (NamesMessage) message;
                    if (namesMessage.getStatus() == LiteDataResponseStatus.UNKNOWN)
                        return AnchoredLiteData.unknown(namesMessage.getAnchor());

                    List<NameData> nameDataList = namesMessage.getNameDataList();
                    if (nameDataList == null || nameDataList.size() != 1)
                        return null;

                    // We are only expecting a single item in the list
                    return AnchoredLiteData.data(nameDataList.get(0), namesMessage.getAnchor());
                },
                LiteNode::nameDataFingerprint);
    }


    private <T> LiteDataResult<T> sendMessageResult(Message message, MessageType expectedResponseMessageType,
            Function<Message, AnchoredLiteData<T>> dataExtractor, Function<T, String> fingerprintExtractor) {
        this.stats.requests.incrementAndGet();

        // Needs a mutable copy of the unmodifiableList
        List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

        peers.removeIf(peer -> !canServeLiteData(peer));
        peers.removeIf(Controller.hasMisbehaved);
        peers.removeIf(Controller.hasOnlyGenesisBlock);
        peers.removeIf(Controller.hasOldVersion);
        peers.removeIf(Controller.hasInferiorChainTip);

        if (peers.isEmpty()) {
            this.stats.noCapablePeers.incrementAndGet();
            LOGGER.info("No capable lite-data peers available to send {} message to", message.getType());
            return LiteDataResult.unavailable();
        }

        peers = preferChainTipAgreementPeers(peers);
        Collections.shuffle(peers, RANDOM);
        int maxAttempts = Math.min(peers.size(), MAX_LITE_DATA_PEER_ATTEMPTS);
        List<LiteDataCandidate<T>> candidates = new ArrayList<>();

        for (int i = 0; i < maxAttempts; ++i) {
            Peer peer = peers.get(i);

            LOGGER.info("Sending {} message to lite-data peer {} ({}/{})...", message.getType(), peer, i + 1, maxAttempts);

            Message responseMessage;

            try {
                this.stats.peerAttempts.incrementAndGet();
                responseMessage = peer.getResponse(message);

            } catch (InterruptedException e) {
                this.stats.interruptedRequests.incrementAndGet();
                Thread.currentThread().interrupt();
                return LiteDataResult.unavailable();
            }

            if (responseMessage == null) {
                this.stats.emptyResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} didn't respond to {} message", peer, message.getType());
                continue;
            }
            else if (responseMessage.getType() == GENERIC_UNKNOWN) {
                this.stats.unexpectedResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} returned legacy unanchored unknown data for {} message", peer, message.getType());
                continue;
            }
            else if (responseMessage.getType() != expectedResponseMessageType) {
                this.stats.unexpectedResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} responded with unexpected message type {} (should be {})", peer, responseMessage.getType(), expectedResponseMessageType);
                continue;
            }

            AnchoredLiteData<T> responseData = dataExtractor.apply(responseMessage);
            if (responseData == null || responseData.status == null) {
                this.stats.unexpectedResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} returned non-comparable {} data for {} message", peer, expectedResponseMessageType, message.getType());
                candidates.add(LiteDataCandidate.conflicted());
                continue;
            }

            String anchorFingerprint = liteDataAnchorFingerprint(responseData.anchor);
            if (anchorFingerprint == null) {
                this.stats.unexpectedResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} returned unanchored {} data for {} message", peer, expectedResponseMessageType, message.getType());
                candidates.add(LiteDataCandidate.conflicted());
                continue;
            }

            if (responseData.status == LiteDataResponseStatus.UNKNOWN) {
                candidates.add(LiteDataCandidate.unknown(anchorFingerprint, responseData.anchor));
            } else {
                String payloadFingerprint = responseData.value == null ? null : fingerprintExtractor.apply(responseData.value);
                String responseFingerprint = anchoredDataFingerprint(payloadFingerprint, anchorFingerprint);
                if (responseFingerprint == null) {
                    this.stats.unexpectedResponses.incrementAndGet();
                    LOGGER.info("Lite-data peer {} returned non-comparable {} data for {} message", peer, expectedResponseMessageType, message.getType());
                    candidates.add(LiteDataCandidate.conflicted());
                    continue;
                }

                candidates.add(LiteDataCandidate.data(responseData.value, responseFingerprint, responseData.anchor));
            }

            this.stats.successfulResponses.incrementAndGet();
            LOGGER.info("Lite-data peer {} responded with comparable {} message", peer, responseMessage.getType());

            LiteDataResult<T> result = chooseAgreedResult(candidates);
            if (hasLiteDataAgreement(result))
                return result;
        }

        LiteDataResult<T> result = chooseAgreedResult(candidates);
        LOGGER.info("Lite-data {} request for {} finished with {} status", message.getType(), expectedResponseMessageType, result.getStatus());
        return result;
    }

    private static <T> AnchoredLiteData<T> anchoredLiteData(LiteDataResponseStatus status, LiteDataAnchor anchor, T value) {
        if (status == LiteDataResponseStatus.UNKNOWN)
            return AnchoredLiteData.unknown(anchor);

        return AnchoredLiteData.data(value, anchor);
    }

    static boolean canServeLiteData(Peer peer) {
        return peer != null && isSupportedLiteDataCapability(peer.getPeerCapability(LITE_DATA_CAPABILITY));
    }

    static boolean isSupportedLiteDataCapability(Object capability) {
        if (!(capability instanceof Number))
            return false;

        return ((Number) capability).intValue() >= LITE_DATA_CAPABILITY_VERSION;
    }

    static int normalizeTransactionLimit(int limit) {
        if (limit <= 0)
            return MAX_TRANSACTIONS_PER_REQUEST;

        return Math.min(limit, MAX_TRANSACTIONS_PER_REQUEST);
    }

    static List<Peer> preferChainTipAgreementPeers(List<Peer> peers) {
        Map<ByteArray, List<Peer>> peersByChainTip = new HashMap<>();

        for (Peer peer : peers) {
            BlockSummaryData chainTipData = peer.getChainTipData();
            if (chainTipData == null || chainTipData.getSignature() == null)
                continue;

            peersByChainTip.computeIfAbsent(ByteArray.wrap(chainTipData.getSignature()), ignored -> new ArrayList<>())
                    .add(peer);
        }

        List<Peer> preferredPeers = null;
        boolean tiedLargestGroup = false;

        for (List<Peer> chainTipPeers : peersByChainTip.values()) {
            if (preferredPeers == null || chainTipPeers.size() > preferredPeers.size()) {
                preferredPeers = chainTipPeers;
                tiedLargestGroup = false;
            } else if (chainTipPeers.size() == preferredPeers.size()) {
                tiedLargestGroup = true;
            }
        }

        if (preferredPeers != null && preferredPeers.size() >= REQUIRED_LITE_DATA_AGREEMENT && !tiedLargestGroup)
            return new ArrayList<>(preferredPeers);

        return peers;
    }

    private static <T> T agreedValueOrNull(LiteDataResult<T> result) {
        return result.isAgreed() ? result.getValue() : null;
    }

    private static boolean hasLiteDataAgreement(LiteDataResult<?> result) {
        return result.getStatus() == LiteDataStatus.AGREED || result.getStatus() == LiteDataStatus.UNKNOWN;
    }

    static <T> LiteDataResult<T> chooseAgreedResult(List<LiteDataCandidate<T>> candidates) {
        Map<String, Integer> dataCountsByFingerprint = new HashMap<>();
        Map<String, Integer> unknownCountsByFingerprint = new HashMap<>();
        Map<String, T> valuesByFingerprint = new HashMap<>();
        Map<String, LiteDataAnchor> anchorsByFingerprint = new HashMap<>();
        Set<String> usableCategories = new HashSet<>();
        boolean hasNonComparableResponse = false;

        for (LiteDataCandidate<T> candidate : candidates) {
            if (candidate == null)
                continue;

            switch (candidate.type) {
                case UNKNOWN:
                    if (candidate.fingerprint == null) {
                        hasNonComparableResponse = true;
                        break;
                    }

                    usableCategories.add("UNKNOWN:" + candidate.fingerprint);
                    anchorsByFingerprint.putIfAbsent(candidate.fingerprint, candidate.anchor);
                    int unknownCount = unknownCountsByFingerprint.getOrDefault(candidate.fingerprint, 0) + 1;
                    unknownCountsByFingerprint.put(candidate.fingerprint, unknownCount);
                    if (unknownCount >= REQUIRED_LITE_DATA_AGREEMENT)
                        return LiteDataResult.unknown(anchorsByFingerprint.get(candidate.fingerprint));
                    break;

                case DATA:
                    if (candidate.fingerprint == null) {
                        hasNonComparableResponse = true;
                        break;
                    }

                    usableCategories.add("DATA:" + candidate.fingerprint);
                    valuesByFingerprint.putIfAbsent(candidate.fingerprint, candidate.value);
                    anchorsByFingerprint.putIfAbsent(candidate.fingerprint, candidate.anchor);
                    int count = dataCountsByFingerprint.getOrDefault(candidate.fingerprint, 0) + 1;
                    dataCountsByFingerprint.put(candidate.fingerprint, count);
                    if (count >= REQUIRED_LITE_DATA_AGREEMENT)
                        return LiteDataResult.agreed(valuesByFingerprint.get(candidate.fingerprint),
                                anchorsByFingerprint.get(candidate.fingerprint));
                    break;

                case CONFLICTED:
                    hasNonComparableResponse = true;
                    break;
            }
        }

        if (usableCategories.size() > 1 || hasNonComparableResponse)
            return LiteDataResult.conflicted();

        return LiteDataResult.unavailable();
    }

    static String liteDataAnchorFingerprint(LiteDataAnchor anchor) {
        if (anchor == null || anchor.getBlockSignature() == null)
            return null;

        StringBuilder fingerprint = new StringBuilder();
        appendFingerprintField(fingerprint, anchor.getHeight());
        appendFingerprintField(fingerprint, fingerprintBytes(anchor.getBlockSignature()));
        appendFingerprintField(fingerprint, anchor.getTimestamp());
        return fingerprint.toString();
    }

    static String anchoredDataFingerprint(String payloadFingerprint, String anchorFingerprint) {
        if (payloadFingerprint == null || anchorFingerprint == null)
            return null;

        StringBuilder fingerprint = new StringBuilder();
        appendFingerprintField(fingerprint, payloadFingerprint);
        appendFingerprintField(fingerprint, anchorFingerprint);
        return fingerprint.toString();
    }

    static String accountDataFingerprint(AccountData accountData) {
        if (accountData == null)
            return null;

        StringBuilder fingerprint = new StringBuilder();
        appendFingerprintField(fingerprint, accountData.getAddress());
        appendFingerprintField(fingerprint, fingerprintBytes(accountData.getPublicKey()));
        appendFingerprintField(fingerprint, accountData.getDefaultGroupId());
        appendFingerprintField(fingerprint, accountData.getLevel());
        appendFingerprintField(fingerprint, accountData.getBlocksMinted());
        return fingerprint.toString();
    }

    static String accountBalanceFingerprint(AccountBalanceData accountBalanceData) {
        if (accountBalanceData == null)
            return null;

        StringBuilder fingerprint = new StringBuilder();
        appendFingerprintField(fingerprint, accountBalanceData.getAddress());
        appendFingerprintField(fingerprint, accountBalanceData.getAssetId());
        appendFingerprintField(fingerprint, accountBalanceData.getBalance());
        return fingerprint.toString();
    }

    static String nameDataFingerprint(NameData nameData) {
        if (nameData == null)
            return null;

        StringBuilder fingerprint = new StringBuilder();
        appendFingerprintField(fingerprint, nameData.getName());
        appendFingerprintField(fingerprint, nameData.getReducedName());
        appendFingerprintField(fingerprint, nameData.getOwner());
        appendFingerprintField(fingerprint, nameData.getData());
        appendFingerprintField(fingerprint, nameData.getRegistered());
        appendFingerprintField(fingerprint, nameData.getUpdated());
        appendFingerprintField(fingerprint, nameData.isForSale());
        appendFingerprintField(fingerprint, nameData.getSalePrice());
        appendFingerprintField(fingerprint, nameData.getSaleRecipient());
        appendFingerprintField(fingerprint, fingerprintBytes(nameData.getReference()));
        appendFingerprintField(fingerprint, nameData.getCreationGroupId());
        return fingerprint.toString();
    }

    static String nameDataListFingerprint(List<NameData> nameDataList) {
        if (nameDataList == null)
            return null;

        List<String> nameFingerprints = new ArrayList<>(nameDataList.size());
        for (NameData nameData : nameDataList) {
            String fingerprint = nameDataFingerprint(nameData);
            if (fingerprint == null)
                return null;

            nameFingerprints.add(fingerprint);
        }

        Collections.sort(nameFingerprints);
        return listFingerprint(nameFingerprints);
    }

    static String transactionListFingerprint(List<TransactionData> transactions) {
        if (transactions == null)
            return null;

        List<String> transactionSignatures = new ArrayList<>(transactions.size());
        for (TransactionData transactionData : transactions) {
            if (transactionData == null || transactionData.getSignature() == null)
                return null;

            transactionSignatures.add(Base58.encode(transactionData.getSignature()));
        }

        Collections.sort(transactionSignatures);
        return listFingerprint(transactionSignatures);
    }

    private static String listFingerprint(List<String> itemFingerprints) {
        StringBuilder fingerprint = new StringBuilder();
        for (String itemFingerprint : itemFingerprints)
            appendFingerprintField(fingerprint, itemFingerprint);

        return fingerprint.toString();
    }

    private static String fingerprintBytes(byte[] bytes) {
        return bytes == null ? null : Base58.encode(bytes);
    }

    private static void appendFingerprintField(StringBuilder fingerprint, Object value) {
        if (value == null) {
            fingerprint.append("-1;");
            return;
        }

        String field = value.toString();
        fingerprint.append(field.length()).append(':').append(field).append(';');
    }

}
