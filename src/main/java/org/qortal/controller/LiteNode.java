package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.utils.Base58;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.qortal.network.message.MessageType.*;

public class LiteNode {

    private static final Logger LOGGER = LogManager.getLogger(LiteNode.class);

    public static final String LITE_DATA_CAPABILITY = "LITE_DATA";
    public static final int LITE_DATA_CAPABILITY_VERSION = 1;
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

        private LiteDataResult(LiteDataStatus status, T value) {
            this.status = status;
            this.value = value;
        }

        public static <T> LiteDataResult<T> agreed(T value) {
            return new LiteDataResult<>(LiteDataStatus.AGREED, value);
        }

        public static <T> LiteDataResult<T> unknown() {
            return new LiteDataResult<>(LiteDataStatus.UNKNOWN, null);
        }

        public static <T> LiteDataResult<T> unavailable() {
            return new LiteDataResult<>(LiteDataStatus.UNAVAILABLE, null);
        }

        public static <T> LiteDataResult<T> conflicted() {
            return new LiteDataResult<>(LiteDataStatus.CONFLICTED, null);
        }

        public LiteDataStatus getStatus() {
            return this.status;
        }

        public T getValue() {
            return this.value;
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

        private LiteDataCandidate(LiteDataCandidateType type, T value, String fingerprint) {
            this.type = type;
            this.value = value;
            this.fingerprint = fingerprint;
        }

        static <T> LiteDataCandidate<T> data(T value, String fingerprint) {
            return new LiteDataCandidate<>(LiteDataCandidateType.DATA, value, fingerprint);
        }

        static <T> LiteDataCandidate<T> unknown() {
            return new LiteDataCandidate<>(LiteDataCandidateType.UNKNOWN, null, null);
        }

        static <T> LiteDataCandidate<T> conflicted() {
            return new LiteDataCandidate<>(LiteDataCandidateType.CONFLICTED, null, null);
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
        GetAccountMessage getAccountMessage = new GetAccountMessage(address);
        AccountMessage accountMessage = (AccountMessage) this.sendMessage(getAccountMessage, ACCOUNT);
        if (accountMessage == null) {
            return null;
        }
        return accountMessage.getAccountData();
    }

    /**
     * Fetch account balance data from peers for given account address and asset ID
     * @param address - the account address to query
     * @return balance - the balance for this address and assetId, or null if not retrieved
     */
    public AccountBalanceData fetchAccountBalance(String address, long assetId) {
        GetAccountBalanceMessage getAccountMessage = new GetAccountBalanceMessage(address, assetId);
        AccountBalanceMessage accountMessage = (AccountBalanceMessage) this.sendMessage(getAccountMessage, ACCOUNT_BALANCE);
        if (accountMessage == null) {
            return null;
        }
        return accountMessage.getAccountBalanceData();
    }

    /**
     * Fetch list of transactions for given account address
     * @param address - the account address to query
     * @param limit - the maximum number of results to return
     * @param offset - the starting index
     * @return a list of TransactionData objects, or null if not retrieved
     */
    public List<TransactionData> fetchAccountTransactions(String address, int limit, int offset) {
        limit = normalizeTransactionLimit(limit);

        List<TransactionData> allTransactions = new ArrayList<>();
        while (allTransactions.size() < limit) {
            int requestLimit = Math.min(MAX_TRANSACTIONS_PER_MESSAGE, limit - allTransactions.size());
            GetAccountTransactionsMessage getAccountTransactionsMessage = new GetAccountTransactionsMessage(address, requestLimit, offset);
            TransactionsMessage transactionsMessage = (TransactionsMessage) this.sendMessage(getAccountTransactionsMessage, TRANSACTIONS);
            if (transactionsMessage == null) {
                // An error occurred, so give up instead of returning partial results
                return null;
            }

            List<TransactionData> transactions = transactionsMessage.getTransactions();
            if (transactions.size() > requestLimit)
                transactions = transactions.subList(0, requestLimit);

            allTransactions.addAll(transactions);
            if (transactions.size() < requestLimit) {
                // No more transactions to fetch
                break;
            }
            offset += requestLimit;
        }
        return allTransactions;
    }

    /**
     * Fetch list of names for given account address
     * @param address - the account address to query
     * @return a list of NameData objects, or null if not retrieved
     */
    public List<NameData> fetchAccountNames(String address) {
        GetAccountNamesMessage getAccountNamesMessage = new GetAccountNamesMessage(address);
        NamesMessage namesMessage = (NamesMessage) this.sendMessage(getAccountNamesMessage, NAMES);
        if (namesMessage == null) {
            return null;
        }
        return namesMessage.getNameDataList();
    }

    /**
     * Fetch info about a registered name
     * @param name - the name to query
     * @return a NameData object, or null if not retrieved
     */
    public NameData fetchNameData(String name) {
        GetNameMessage getNameMessage = new GetNameMessage(name);
        NamesMessage namesMessage = (NamesMessage) this.sendMessage(getNameMessage, NAMES);
        if (namesMessage == null) {
            return null;
        }
        List<NameData> nameDataList = namesMessage.getNameDataList();
        if (nameDataList == null || nameDataList.size() != 1) {
            return null;
        }
        // We are only expecting a single item in the list
        return nameDataList.get(0);
    }


    private Message sendMessage(Message message, MessageType expectedResponseMessageType) {
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
            return null;
        }

        Collections.shuffle(peers, RANDOM);
        int maxAttempts = Math.min(peers.size(), MAX_LITE_DATA_PEER_ATTEMPTS);

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
                return null;
            }

            if (responseMessage == null) {
                this.stats.emptyResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} didn't respond to {} message", peer, message.getType());
                continue;
            }
            else if (responseMessage.getType() != expectedResponseMessageType) {
                this.stats.unexpectedResponses.incrementAndGet();
                LOGGER.info("Lite-data peer {} responded with unexpected message type {} (should be {})", peer, responseMessage.getType(), expectedResponseMessageType);
                continue;
            }

            this.stats.successfulResponses.incrementAndGet();
            LOGGER.info("Lite-data peer {} responded with {} message", peer, responseMessage.getType());

            return responseMessage;
        }

        LOGGER.info("No capable lite-data peers returned a {} response for {} message", expectedResponseMessageType, message.getType());
        return null;
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

    static <T> LiteDataResult<T> chooseAgreedResult(List<LiteDataCandidate<T>> candidates) {
        Map<String, Integer> dataCountsByFingerprint = new HashMap<>();
        Map<String, T> valuesByFingerprint = new HashMap<>();
        Set<String> usableCategories = new HashSet<>();
        int unknownCount = 0;
        boolean hasNonComparableResponse = false;

        for (LiteDataCandidate<T> candidate : candidates) {
            if (candidate == null)
                continue;

            switch (candidate.type) {
                case UNKNOWN:
                    ++unknownCount;
                    usableCategories.add("UNKNOWN");
                    if (unknownCount >= REQUIRED_LITE_DATA_AGREEMENT)
                        return LiteDataResult.unknown();
                    break;

                case DATA:
                    if (candidate.fingerprint == null) {
                        hasNonComparableResponse = true;
                        break;
                    }

                    usableCategories.add("DATA:" + candidate.fingerprint);
                    valuesByFingerprint.putIfAbsent(candidate.fingerprint, candidate.value);
                    int count = dataCountsByFingerprint.getOrDefault(candidate.fingerprint, 0) + 1;
                    dataCountsByFingerprint.put(candidate.fingerprint, count);
                    if (count >= REQUIRED_LITE_DATA_AGREEMENT)
                        return LiteDataResult.agreed(valuesByFingerprint.get(candidate.fingerprint));
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
