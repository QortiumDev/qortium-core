package org.qortium.crosschain;
import org.qortium.settings.Settings;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Class BlockchainCache
 *
 * Cache blockchain information to reduce redundant RPCs to the ElectrumX servers.
 */
public class BlockchainCache {

    /**
     * Keys With History
     *
     * Deterministic Keys with any transaction history.
     */
    private Queue<String> keysWithHistory = new ConcurrentLinkedDeque<>();

    /**
     * Transactions By Hash
     *
     * Transaction Hash -> Transaction
     */
    private ConcurrentHashMap<String, BitcoinyTransaction> transactionByHash = new ConcurrentHashMap<>();

    /**
     * Cache Limit
     *
     * If this limit is reached, the cache will be cleared or reduced.
     * <p>
     * Held in a lazy holder so that loading this class does not itself require settings to have
     * been loaded: the limit is read on first cache use instead of in {@code <clinit>}. Behaviour
     * with settings present is unchanged - the value is still read once and then fixed.
     */
    private static final class CacheLimitHolder {
        static final int CACHE_LIMIT = Settings.getInstance().getBlockchainCacheLimit();
    }

    /** Cache limit, read from settings on first use. */
    private static int cacheLimit() {
        return CacheLimitHolder.CACHE_LIMIT;
    }

    public void addKeyWithHistory(String key) {
        if( this.keysWithHistory.size() > cacheLimit() ) {
            this.keysWithHistory.remove();
        }

        this.keysWithHistory.add(key);
    }

    public boolean keyHasHistory( String key ) {
        return this.keysWithHistory.contains(key);
    }

    /**
     * Add Transaction By Hash
     *
     * @param hash the transaction hash
     * @param transaction the transaction
     */
    public void addTransactionByHash( String hash, BitcoinyTransaction transaction ) {

        if( this.transactionByHash.size() > cacheLimit() ) {
            this.transactionByHash.clear();
        }

        this.transactionByHash.put(hash, transaction);
    }

    /**
     * Get Transaction By Hash
     *
     * @param hash the transaction hash
     *
     * @return the transaction, empty if the hash is not in the cache
     */
    public Optional<BitcoinyTransaction> getTransactionByHash( String hash ) {
        return Optional.ofNullable( this.transactionByHash.get(hash) );
    }
}
