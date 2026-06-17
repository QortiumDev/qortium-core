package org.qortium.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.list.QdnFilter;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.utils.ListUtils;
import org.qortium.utils.NamedThreadFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Follower {

    private static final Logger LOGGER = LogManager.getLogger(Follower.class);

    private ScheduledExecutorService service
            = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Follower", Thread.NORM_PRIORITY));

    private Follower() {

    }

    private static Follower instance;

    public static Follower getInstance() {

        if( instance == null ) {
            instance = new Follower();
        }

        return instance;
    }

    public void start() {

        // fetch arbitrary transactions from followed names from the last 100 blocks every 2 minutes
        service.scheduleWithFixedDelay(() -> fetch(OptionalInt.of(100)), 10, 2, TimeUnit.MINUTES);

        // fetch arbitrary transaction from followed names from any block every 24 hours
        service.scheduleWithFixedDelay(() -> fetch(OptionalInt.empty()), 4, 24, TimeUnit.HOURS);
    }

    private void fetch(OptionalInt limit) {

        try {
            // Build the follow/block matchers once for this pass (resolves any address aliases once).
            QdnFilter followFilter = ListUtils.followedQdnFilter();
            if (followFilter.isEmpty()) {
                // Not following anything, so there is nothing to auto-fetch
                return;
            }
            QdnFilter blockFilter = ListUtils.blockedQdnFilter();

            List<ArbitraryTransactionData> candidates;

            // open database to get the latest arbitrary transactions across all resources
            try (final Repository repository = RepositoryManager.getRepository()) {

                List<ArbitraryTransactionData> latestArbitraryTransactions
                        = repository.getArbitraryRepository().getLatestArbitraryTransactions();

                if (limit.isPresent()) {
                    final int blockHeightThreshold = repository.getBlockRepository().getBlockchainHeight() - limit.getAsInt();

                    candidates = latestArbitraryTransactions.stream()
                            .filter(tx -> tx.getBlockHeight() != null && tx.getBlockHeight() > blockHeightThreshold)
                            .collect(Collectors.toList());
                } else {
                    candidates = latestArbitraryTransactions;
                }

            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
                candidates = new ArrayList<>(0);
            }

            // keep only the resources we follow (and don't block), matched by wildcard pattern
            List<ArbitraryTransactionData> followed = candidates.stream()
                    .filter(tx -> followFilter.matches(tx.getService(), tx.getName(), tx.getIdentifier()))
                    .filter(tx -> !blockFilter.matches(tx.getService(), tx.getName(), tx.getIdentifier()))
                    .collect(Collectors.toList());

            // collect processed transaction hashes, so we don't fetch outdated transactions
            Set<ArbitraryTransactionDataHashWrapper> processedTransactions = new HashSet<>();

            ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

            // for each followed arbitrary transaction: process, evaluate, fetch
            for (ArbitraryTransactionData arbitraryTransaction : followed) {

                boolean examined = false;

                try (final Repository repository = RepositoryManager.getRepository()) {

                    // if not processed
                    if (!processedTransactions.contains(new ArbitraryTransactionDataHashWrapper(arbitraryTransaction))) {
                        boolean isLocal = repository.getArbitraryRepository().isDataLocal(arbitraryTransaction.getSignature());

                        // if not local, then continue to evaluate
                        if (!isLocal) {

                            // evaluate fetching status for this transaction on this node
                            ArbitraryDataExamination examination = storageManager.shouldPreFetchData(repository, arbitraryTransaction);

                            // if the evaluation passed, then fetch
                            examined = examination.isPass();
                        }
                        // if locally stored, then nothing needs to be done

                        // add to processed transactions
                        processedTransactions.add(new ArbitraryTransactionDataHashWrapper(arbitraryTransaction));
                    }
                }

                // if passed examination for fetching, then fetch
                if (examined) {
                    LOGGER.info("for {} on {}, fetching {}", arbitraryTransaction.getName(), arbitraryTransaction.getService(), arbitraryTransaction.getIdentifier());
                    boolean fetched = ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(arbitraryTransaction);

                    LOGGER.info("fetched = " + fetched);
                }

                // pause a second before moving on to another transaction
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
