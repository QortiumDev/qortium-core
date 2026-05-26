package org.qortium.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.controller.Controller;
import org.qortium.data.arbitrary.ArbitraryResourceData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.event.DataMonitorEvent;
import org.qortium.event.EventBus;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.utils.Base58;
import org.qortium.utils.StartupStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ArbitraryDataCacheManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataCacheManager.class);

    private static ArbitraryDataCacheManager instance;
    private volatile boolean isStopping = false;

    /** Queue of arbitrary transactions that require cache updates */
    private final List<ArbitraryTransactionData> updateQueue = Collections.synchronizedList(new ArrayList<>());

    private static final NumberFormat FORMATTER = NumberFormat.getNumberInstance();

    static {
        FORMATTER.setGroupingUsed(true);
    }

    public static synchronized ArbitraryDataCacheManager getInstance() {
        if (instance == null) {
            instance = new ArbitraryDataCacheManager();
        }

        return instance;
    }

    /**
     * Are The Latest Signatures Populated?
     *
     * Assessing if the latest signatures have been cached for QDN data.
     *
     * @param connection a database connection
     *
     * @return true if the signatures have been cached, otherwise false
     *
     * @throws SQLException
     */
    private static boolean isLatestSignaturePopulated(Connection connection) throws SQLException {
        String sql = "SELECT latest_signature_populated FROM DatabaseInfo WHERE latest_signature_populated = 1";
        PreparedStatement preparedStatement = connection.prepareStatement(sql);

        try (ResultSet rs = preparedStatement.executeQuery()) {
            if (rs.next()) {
                return true;
            }
            else {
                return false;
            }
        }
    }

    /**
     * Populate The Latest Signatures, If Necessary
     *
     * If it hasn't been done already, cache the latest signature for QDN data item.
     *
     * @param connection a connection to the database
     *
     * @throws DataException
     */
    public static void populateLatestSignaturesIfNecessary(Connection connection) throws DataException {

        try {
            if (!isLatestSignaturePopulated(connection)) {

                StartupStatus.update("Gathering latest signatures for QDN ...");
                LOGGER.info("Gathering latest signatures for QDN ...");

                try (Statement arbitraryTransactionSelectionStatement = connection.createStatement()) {
                    ResultSet resultSet = arbitraryTransactionSelectionStatement.executeQuery(
                            "SELECT signature, service, name, identifier, update_method " +
                                    "FROM Transactions t " +
                                    "JOIN ArbitraryTransactions a ON t.signature = a.signature " +
                                    "WHERE name IS NOT NULL " +
                                    "ORDER BY created_when DESC "
                    );

                    Map<ArbitraryTransactionDataHashWrapper, byte[]> signatureByData = new HashMap<>();

                    // process arbitrary transaction results
                    while (resultSet.next()) {
                        ArbitraryTransactionDataHashWrapper wrapper = new ArbitraryTransactionDataHashWrapper(
                                resultSet.getInt(2),
                                resultSet.getString(3),
                                resultSet.getString(4)
                        );

                        if (signatureByData.containsKey(wrapper)) {
                            continue;
                        }

                        int updateMethod = resultSet.getInt(5);
                        if (updateMethod == ArbitraryTransactionData.Method.DELETE.value) {
                            signatureByData.put(wrapper, null);
                            continue;
                        }

                        signatureByData.put(wrapper, resultSet.getBytes(1));
                    }

                    LOGGER.info("Updating {} arbitrary resources with latest signatures", signatureByData.size());

                    // Preserve the connection's auto-commit mode so callers keep their expected transaction behavior.
                    boolean autoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);

                    try {
                        if (!signatureByData.isEmpty()) {
                            String populateSql = "UPDATE ArbitraryResourcesCache SET latest_signature = ? WHERE name = ? AND service = ? AND identifier = ?";
                            try (PreparedStatement preparedStatement = connection.prepareStatement(populateSql)) {
                                int updateCount = 0;

                                // for each signature by data pairing, prepare a database update statement and add it to a batch
                                for (Map.Entry<ArbitraryTransactionDataHashWrapper, byte[]> entry : signatureByData.entrySet()) {
                                    if (entry.getValue() == null) {
                                        continue;
                                    }

                                    preparedStatement.setBytes(1, entry.getValue());

                                    ArbitraryTransactionDataHashWrapper wrapper = entry.getKey();
                                    preparedStatement.setString(2, wrapper.getName());
                                    preparedStatement.setInt(3, entry.getKey().getService());

                                    String identifier = entry.getKey().getIdentifier();
                                    preparedStatement.setString(4, identifier != null ? identifier : "default");

                                    preparedStatement.addBatch();
                                    updateCount++;
                                }

                                if (updateCount > 0) {
                                    preparedStatement.executeBatch();
                                }
                            }
                        }

                        String deleteSql = "DELETE FROM ArbitraryResourcesCache WHERE name = ? AND service = ? AND identifier = ?";
                        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteSql)) {
                            int deleteCount = 0;

                            for (Map.Entry<ArbitraryTransactionDataHashWrapper, byte[]> entry : signatureByData.entrySet()) {
                                if (entry.getValue() != null) {
                                    continue;
                                }

                                ArbitraryTransactionDataHashWrapper wrapper = entry.getKey();
                                preparedStatement.setString(1, wrapper.getName());
                                preparedStatement.setInt(2, wrapper.getService());

                                String identifier = wrapper.getIdentifier();
                                preparedStatement.setString(3, identifier != null ? identifier : "default");

                                preparedStatement.addBatch();
                                deleteCount++;
                            }

                            if (deleteCount > 0) {
                                preparedStatement.executeBatch();
                            }
                        }

                        LOGGER.info("Updated arbitrary resources with latest signatures");

                        try (Statement stmt = connection.createStatement()) {
                            stmt.execute("UPDATE ArbitraryResourcesCache SET lower_case_name = LCASE(name)");
                        }

                        LOGGER.info("Updated arbitrary resources with lower case names for indexing purposes");

                        // update latest signature populated flag to database info
                        String updateFlagSql = "UPDATE DatabaseInfo SET latest_signature_populated = 1";
                        try (PreparedStatement updateFlagStatement = connection.prepareStatement(updateFlagSql)) {

                            updateFlagStatement.executeUpdate();

                            // verify the change
                            String verifyFlagSql = "SELECT latest_signature_populated FROM DatabaseInfo";
                            try (PreparedStatement verifyFlagStatement = connection.prepareStatement(verifyFlagSql);
                                 ResultSet rs = verifyFlagStatement.executeQuery()) {

                                if (rs.next() && rs.getInt("latest_signature_populated") == 1) {
                                    LOGGER.info("latest signature populated flag has been set");
                                }
                                else {
                                    LOGGER.info("latest signature populated flag has not been set");
                                }
                            }
                        }

                        connection.commit();
                    } catch (SQLException e) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackException) {
                            e.addSuppressed(rollbackException);
                        }
                        throw e;
                    } finally {
                        connection.setAutoCommit(autoCommit);
                    }

                    LOGGER.info("arbitrary resources data latest signatures committed");
                }
            }
        } catch (SQLException e) {
            throw new DataException(e.getMessage());
        } finally {
            StartupStatus.update("Proceeding to Start Qortium ...");
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data Cache Manager");
        Thread.currentThread().setPriority(NORM_PRIORITY);

        try {
            while (!Controller.isStopping()) {
                try {
                    Thread.sleep(500L);

                    // Process queue
                    processResourceQueue();
                } catch (InterruptedException e) {
                    // Check if we're shutting down
                    if (Controller.isStopping()) {
                        LOGGER.info("Arbitrary Data Cache Manager shutting down");
                        break;
                    }
                    LOGGER.warn("Arbitrary Data Cache Manager interrupted, retrying...", e);
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                    Thread.sleep(600_000L); // wait 10 minutes to continue
                }
            }

            // Clear queue before terminating thread
            processResourceQueue();
        } catch (InterruptedException e) {
            if (!Controller.isStopping()) {
                LOGGER.error("Arbitrary Data Cache Manager interrupted unexpectedly", e);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();
    }


    private void processResourceQueue() {
        if (this.updateQueue.isEmpty()) {
            // Nothing to do
            return;
        }

        try (final Repository repository = RepositoryManager.getRepository()) {
            // Take a snapshot of resourceQueue, so we don't need to lock it while processing
            List<ArbitraryTransactionData> resourceQueueCopy = List.copyOf(this.updateQueue);

            for (ArbitraryTransactionData transactionData : resourceQueueCopy) {
                // Best not to return when controller is stopping, as ideally we need to finish processing


                // Remove from the queue regardless of outcome
                this.updateQueue.remove(transactionData);

                // Update arbitrary resource caches
                try {
                    ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
                    arbitraryTransaction.updateArbitraryResourceCacheIncludingMetadata(repository, new HashSet<>(0), new HashMap<>(0));
                    repository.saveChanges();

                    // Update status as separate commit, as this is more prone to failure
                    arbitraryTransaction.updateArbitraryResourceStatus(repository);
                    repository.saveChanges();


                    EventBus.INSTANCE.notify(
                        new DataMonitorEvent(
                            System.currentTimeMillis(),
                            transactionData.getIdentifier(),
                            transactionData.getName(),
                            transactionData.getService().name(),
                            "updated resource cache and status, queue",
                            transactionData.getTimestamp(),
                            transactionData.getTimestamp()
                        )
                    );

                } catch (DataException e) {
                    repository.discardChanges();
                    LOGGER.error("Repository issue while updating arbitrary resource caches", e);
                }
            }
        } catch (DataException e) {
            LOGGER.error("Repository issue while processing arbitrary resource cache updates", e);
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    public void addToUpdateQueue(ArbitraryTransactionData transactionData) {
        this.updateQueue.add(transactionData);
    }

    public boolean needsArbitraryResourcesCacheRebuild(Repository repository) throws DataException {
        List<ArbitraryTransactionData> transactions = repository.getArbitraryRepository().getLatestArbitraryTransactions();
        if (transactions == null || transactions.isEmpty()) {
            LOGGER.debug("No relevant arbitrary transactions exist to build cache from");
            return false;
        }

        Set<ArbitraryTransactionDataHashWrapper> seenResources = new HashSet<>();
        for (ArbitraryTransactionData transactionData : transactions) {
            if (transactionData.getService() == null) {
                continue;
            }

            ArbitraryTransactionDataHashWrapper wrapper = new ArbitraryTransactionDataHashWrapper(transactionData);
            if (!seenResources.add(wrapper)) {
                continue;
            }

            if (transactionData.getMethod() == ArbitraryTransactionData.Method.DELETE) {
                continue;
            }

            ArbitraryResourceData cachedResource = repository.getArbitraryRepository()
                    .getArbitraryResource(transactionData.getService(), transactionData.getName(), transactionData.getIdentifier());
            if (cachedResource == null) {
                return true;
            }
        }

        LOGGER.debug("Arbitrary resources cache already built");
        return false;
	}

    public boolean buildArbitraryResourcesCache(Repository repository, boolean forceRebuild) throws DataException {
        if (Settings.getInstance().isLite()) {
            // Lite nodes have no blockchain
            return false;
        }

        try {
            // Skip if already built
            if (!needsArbitraryResourcesCacheRebuild(repository) && !forceRebuild) {
                LOGGER.debug("Arbitrary resources cache already built");
                return false;
            }

            LOGGER.info("Building arbitrary resources cache...");
            StartupStatus.update("Building QDN cache - please wait...");

            final int batchSize = Settings.getInstance().getBuildArbitraryResourcesBatchSize();
            int offset = 0;

            List<ArbitraryTransactionData> allArbitraryTransactionsInDescendingOrder
                    = repository.getArbitraryRepository().getLatestArbitraryTransactions();

            LOGGER.info("arbitrary transactions: count = " + allArbitraryTransactionsInDescendingOrder.size());

            List<ArbitraryResourceData> resources = repository.getArbitraryRepository().getArbitraryResources(null, null, true);

            Map<ArbitraryTransactionDataHashWrapper, ArbitraryResourceData> resourceByWrapper = new HashMap<>(resources.size());
            for( ArbitraryResourceData resource : resources ) {
                resourceByWrapper.put(
                    new ArbitraryTransactionDataHashWrapper(resource.service.value, resource.name, resource.identifier),
                    resource
                );
            }

            LOGGER.info("arbitrary resources: count = " + resourceByWrapper.size());

            Set<ArbitraryTransactionDataHashWrapper> latestTransactionsWrapped = new HashSet<>(allArbitraryTransactionsInDescendingOrder.size());

            // Loop through all ARBITRARY transactions, and determine latest state
            while (!Controller.isStopping()) {
                LOGGER.info(
                    "Fetching arbitrary transactions {} - {} / {} Total",
                    FORMATTER.format(offset),
                    FORMATTER.format(offset+batchSize-1),
                    FORMATTER.format(allArbitraryTransactionsInDescendingOrder.size())
                );

                List<ArbitraryTransactionData> transactionsToProcess
                    = allArbitraryTransactionsInDescendingOrder.stream()
                        .skip(offset)
                        .limit(batchSize)
                        .collect(Collectors.toList());

                if (transactionsToProcess.isEmpty()) {
                    // Complete
                    break;
                }

                try {
                    for( ArbitraryTransactionData transactionData : transactionsToProcess) {
                        if (transactionData.getService() == null) {
                            // Unsupported service - ignore this resource
                            continue;
                        }

                        latestTransactionsWrapped.add(new ArbitraryTransactionDataHashWrapper(transactionData));

                        // Update arbitrary resource caches
                        ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
                        arbitraryTransaction.updateArbitraryResourceCacheIncludingMetadata(repository, latestTransactionsWrapped, resourceByWrapper);
                    }
                    repository.saveChanges();
                } catch (DataException e) {
                    repository.discardChanges();

                    LOGGER.error(e.getMessage(), e);
                }
                offset += batchSize;
            }

            // Now refresh all statuses
            refreshArbitraryStatuses(repository);

            LOGGER.info("Completed build of arbitrary resources cache.");
            return true;
        }
        catch (DataException e) {
            LOGGER.info("Unable to build arbitrary resources cache: {}. The database may have been left in an inconsistent state.", e.getMessage());

            // Throw an exception so that the node startup is halted, allowing for a retry next time.
            repository.discardChanges();
            throw new DataException("Build of arbitrary resources cache failed.");
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);

            return false;
        }
    }

    private boolean refreshArbitraryStatuses(Repository repository) throws DataException {
        try {
            LOGGER.info("Refreshing arbitrary resource statuses for locally hosted transactions...");
            StartupStatus.update("Refreshing statuses - please wait...");

            final int batchSize = Settings.getInstance().getBuildArbitraryResourcesBatchSize();
            int offset = 0;

            List<ArbitraryTransactionData> allHostedTransactions
                = ArbitraryDataStorageManager.getInstance()
                    .listAllHostedTransactions(repository, null, null);

            // Loop through all ARBITRARY transactions, and determine latest state
            while (!Controller.isStopping()) {
                LOGGER.info(
                    "Fetching hosted transactions {} - {} / {} Total",
                    FORMATTER.format(offset),
                    FORMATTER.format(offset+batchSize-1),
                    FORMATTER.format(allHostedTransactions.size())
                );

                List<ArbitraryTransactionData> hostedTransactions
                    = allHostedTransactions.stream()
                        .skip(offset)
                        .limit(batchSize)
                        .collect(Collectors.toList());

                if (hostedTransactions.isEmpty()) {
                    // Complete
                    break;
                }

                try {
                    // Loop through hosted transactions
                    for (ArbitraryTransactionData transactionData : hostedTransactions) {

                        // Determine status and update cache
                        ArbitraryTransaction arbitraryTransaction = new ArbitraryTransaction(repository, transactionData);
                        arbitraryTransaction.updateArbitraryResourceStatus(repository);
                    }
                    repository.saveChanges();
                } catch (DataException e) {
                    repository.discardChanges();

                    LOGGER.error(e.getMessage(), e);
                }

                offset += batchSize;
            }

            LOGGER.info("Completed refresh of arbitrary resource statuses.");
            return true;
        }
        catch (DataException e) {
            LOGGER.info("Unable to refresh arbitrary resource statuses: {}. The database may have been left in an inconsistent state.", e.getMessage());

            // Throw an exception so that the node startup is halted, allowing for a retry next time.
            repository.discardChanges();
            throw new DataException("Refresh of arbitrary resource statuses failed.");
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);

            return false;
        }
    }

}
