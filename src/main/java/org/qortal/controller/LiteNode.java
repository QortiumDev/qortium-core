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

import java.security.SecureRandom;
import java.util.*;

import static org.qortal.network.message.MessageType.*;

public class LiteNode {

    private static final Logger LOGGER = LogManager.getLogger(LiteNode.class);

    public static final String LITE_DATA_CAPABILITY = "LITE_DATA";
    public static final int LITE_DATA_CAPABILITY_VERSION = 1;
    public static final int MAX_LITE_DATA_PEER_ATTEMPTS = 3;
    public static final int MAX_NAMES_PER_MESSAGE = 100;
    public static final int MAX_TRANSACTIONS_PER_MESSAGE = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static LiteNode instance;


    public Map<Integer, Long> pendingRequests = Collections.synchronizedMap(new HashMap<>());

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
        List<TransactionData> allTransactions = new ArrayList<>();
        if (limit == 0) {
            limit = Integer.MAX_VALUE;
        }
        int batchSize = Math.min(limit, MAX_TRANSACTIONS_PER_MESSAGE);

        while (allTransactions.size() < limit) {
            GetAccountTransactionsMessage getAccountTransactionsMessage = new GetAccountTransactionsMessage(address, batchSize, offset);
            TransactionsMessage transactionsMessage = (TransactionsMessage) this.sendMessage(getAccountTransactionsMessage, TRANSACTIONS);
            if (transactionsMessage == null) {
                // An error occurred, so give up instead of returning partial results
                return null;
            }
            allTransactions.addAll(transactionsMessage.getTransactions());
            if (transactionsMessage.getTransactions().size() < batchSize) {
                // No more transactions to fetch
                break;
            }
            offset += batchSize;
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
        // Needs a mutable copy of the unmodifiableList
        List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

        peers.removeIf(peer -> !canServeLiteData(peer));
        peers.removeIf(Controller.hasMisbehaved);
        peers.removeIf(Controller.hasOnlyGenesisBlock);
        peers.removeIf(Controller.hasOldVersion);
        peers.removeIf(Controller.hasInferiorChainTip);

        if (peers.isEmpty()) {
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
                responseMessage = peer.getResponse(message);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            if (responseMessage == null) {
                LOGGER.info("Lite-data peer {} didn't respond to {} message", peer, message.getType());
                continue;
            }
            else if (responseMessage.getType() != expectedResponseMessageType) {
                LOGGER.info("Lite-data peer {} responded with unexpected message type {} (should be {})", peer, responseMessage.getType(), expectedResponseMessageType);
                continue;
            }

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

}
