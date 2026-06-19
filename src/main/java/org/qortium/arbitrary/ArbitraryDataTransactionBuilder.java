package org.qortium.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.arbitrary.ArbitraryDataDiff.DiffType;
import org.qortium.arbitrary.ArbitraryDataDiff.ModifiedPath;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortium.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortium.arbitrary.misc.Category;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.AES;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.ArbitraryTransactionData.Compression;
import org.qortium.data.transaction.ArbitraryTransactionData.DataType;
import org.qortium.data.transaction.ArbitraryTransactionData.Method;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Base58;
import org.qortium.utils.FilesystemUtils;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ArbitraryDataTransactionBuilder {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataTransactionBuilder.class);

    // Maximum number of PATCH layers allowed
    private static final int MAX_LAYERS = 10;
    // Maximum size difference (out of 1) allowed for PATCH transactions
    private static final double MAX_SIZE_DIFF = 0.2f;
    // Maximum proportion of files modified relative to total
    private static final double MAX_FILE_DIFF = 0.5f;

    private final String publicKey58;
    private final long fee;
    private final Path path;
    private final String name;
    private Method method;
    private final Service service;
    private final String identifier;
    private final Repository repository;

    // Metadata
    private final String title;
    private final String description;
    private final List<String> tags;
    private final Category category;
    private final String entryPoint;

    private int chunkSize = ArbitraryDataFile.CHUNK_SIZE;

    private ArbitraryTransactionData arbitraryTransactionData;
    private ArbitraryDataFile arbitraryDataFile;

    public ArbitraryDataTransactionBuilder(Repository repository, String publicKey58, long fee, Path path, String name,
                                           Method method, Service service, String identifier,
                                           String title, String description, List<String> tags, Category category) {
        this(repository, publicKey58, fee, path, name, method, service, identifier, title, description, tags, category, null);
    }

    public ArbitraryDataTransactionBuilder(Repository repository, String publicKey58, long fee, Path path, String name,
                                           Method method, Service service, String identifier,
                                           String title, String description, List<String> tags, Category category,
                                           String entryPoint) {
        this.repository = repository;
        this.publicKey58 = publicKey58;
        this.fee = fee;
        this.path = path;
        this.name = name;
        this.method = method;
        this.service = service;

        // If identifier is a blank string, or reserved keyword "default", treat it as null
        if (identifier == null || identifier.isEmpty() || identifier.equals("default")) {
            identifier = null;
        }
        this.identifier = identifier;

        // Metadata (optional)
        this.title = ArbitraryDataTransactionMetadata.limitTitle(title);
        this.description = ArbitraryDataTransactionMetadata.limitDescription(description);
        this.tags = ArbitraryDataTransactionMetadata.limitTags(tags);
        this.category = category;

        // entryPoint (optional). Treat blank as null.
        this.entryPoint = (entryPoint == null || entryPoint.isBlank()) ? null : entryPoint;
    }

    public void build() throws DataException {
        try {
            this.preExecute();
            this.checkMethod();
            this.createTransaction();
        }
        finally {
            this.postExecute();
        }
    }

    private void preExecute() {

    }

    private void postExecute() {

    }

    private void checkMethod() throws DataException {
        if (this.method == null) {
            // We need to automatically determine the method
            this.method = this.determineMethodAutomatically();
        }
    }

    private Method determineMethodAutomatically()  {
      
        return Method.PUT;

    }

    private void createTransaction() throws DataException {
        arbitraryDataFile = null;
        try {
            Long now = NTP.getTime();
            if (now == null) {
                throw new DataException("NTP time not synced yet");
            }

            int transactionVersion = Transaction.getVersionByTimestamp(now);

            if (publicKey58 == null || path == null) {
                throw new DataException("Missing public key or path");
            }
            byte[] creatorPublicKey = Base58.decode(publicKey58);

            // Single file resources are handled differently, especially for very small data payloads, as these go on chain
            final boolean isSingleFileResource = FilesystemUtils.isSingleFileResource(path, false);
            final boolean shouldUseOnChainData = (isSingleFileResource && AES.getEncryptedFileSize(Files.size(path)) <= ArbitraryTransaction.MAX_DATA_SIZE);

            // Use zip compression if data isn't going on chain
            Compression compression = shouldUseOnChainData ? Compression.NONE : Compression.ZIP;

            ArbitraryDataWriter arbitraryDataWriter = new ArbitraryDataWriter(path, name, service, identifier, method,
                    compression, title, description, tags, category, entryPoint);
            try {
                arbitraryDataWriter.setChunkSize(this.chunkSize);
                arbitraryDataWriter.save();
            } catch (IOException | DataException | InterruptedException | RuntimeException | MissingDataException e) {
                LOGGER.info("Unable to create arbitrary data file: {}", e.getMessage());
                throw new DataException(e.getMessage());
            }

            // Get main file
            arbitraryDataFile = arbitraryDataWriter.getArbitraryDataFile();
            if (arbitraryDataFile == null) {
                throw new DataException("Arbitrary data file is null");
            }

            // Get metadata file
            ArbitraryDataFile metadataFile = arbitraryDataFile.getMetadataFile();
            if (metadataFile == null && arbitraryDataFile.chunkCount() > 1) {
                throw new DataException(String.format("Chunks metadata data file is null but there are %d chunks", arbitraryDataFile.chunkCount()));
            }

            // Default to using a data hash, with data held off-chain
            ArbitraryTransactionData.DataType dataType = ArbitraryTransactionData.DataType.DATA_HASH;
            byte[] data = arbitraryDataFile.digest();

            // For small, single-chunk resources, we can store the data directly on chain
            if (shouldUseOnChainData && arbitraryDataFile.getBytes().length <= ArbitraryTransaction.MAX_DATA_SIZE && arbitraryDataFile.chunkCount() == 0) {
                // Within allowed on-chain data size
                dataType = DataType.RAW_DATA;
                data = arbitraryDataFile.getBytes();
            }

            final BaseTransactionData baseTransactionData = new BaseTransactionData(now, Group.NO_GROUP,
                    creatorPublicKey, fee, null);
            final int size = (int) arbitraryDataFile.size();
            final int nonce = 0;
            byte[] secret = arbitraryDataFile.getSecret();

            final byte[] metadataHash = (metadataFile != null) ? metadataFile.getHash() : null;
            final List<PaymentData> payments = new ArrayList<>();

            ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData,
                    transactionVersion, service.value, nonce, size, name, identifier, method,
                    secret, compression, data, dataType, metadataHash, payments);

            this.arbitraryTransactionData = transactionData;

        } catch (DataException | IOException e) {
            if (arbitraryDataFile != null) {
                arbitraryDataFile.deleteAll(true);
            }
            throw new DataException(e);
        }

    }

    private boolean isMetadataEqual(ArbitraryDataTransactionMetadata existingMetadata) {
        if (existingMetadata == null) {
            return !this.hasMetadata();
        }
        if (!Objects.equals(existingMetadata.getTitle(), this.title)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getDescription(), this.description)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getCategory(), this.category)) {
            return false;
        }
        if (!Objects.equals(existingMetadata.getTags(), this.tags)) {
            return false;
        }
        return true;
    }

    private boolean hasMetadata() {
        return (this.title != null || this.description != null || this.category != null || this.tags != null);
    }

    public void computeNonce() throws DataException {
        if (this.arbitraryTransactionData == null) {
            throw new DataException("Arbitrary transaction data is required to compute nonce");
        }

        ArbitraryTransaction transaction = (ArbitraryTransaction) Transaction.fromData(repository, this.arbitraryTransactionData);
        LOGGER.info("Computing nonce...");
        transaction.computeNonce();

        Transaction.ValidationResult result = transaction.isValidUnconfirmed();
        if (result != Transaction.ValidationResult.OK) {
            arbitraryDataFile.deleteAll(true);
            throw new DataException(String.format("Arbitrary transaction invalid: %s", result));
        }
        LOGGER.info("Transaction is valid");
    }

    public ArbitraryTransactionData getArbitraryTransactionData() {
        return this.arbitraryTransactionData;
    }

    public ArbitraryDataFile getArbitraryDataFile() {
        return this.arbitraryDataFile;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

}
