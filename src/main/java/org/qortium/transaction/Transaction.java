package org.qortium.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.controller.TransactionImporter;
import org.qortium.crypto.Crypto;
import org.qortium.crypto.MemoryPoW;
import org.qortium.data.block.BlockData;
import org.qortium.data.group.GroupApprovalData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.GroupRepository;
import org.qortium.repository.Repository;
import org.qortium.settings.Settings;
import org.qortium.transform.Transformer;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.NTP;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public abstract class Transaction {

	private static final int MEMPOW_FEE_ALTERNATIVE_BUFFER_SIZE = 8 * 1024 * 1024; // bytes

	// Transaction types
	public enum TransactionType {
		GENESIS(1, false),
		PAYMENT(2, false),
		REGISTER_NAME(3, true),
		UPDATE_NAME(4, true),
		SELL_NAME(5, false),
		CANCEL_SELL_NAME(6, false),
		BUY_NAME(7, false),
		CREATE_POLL(8, true),
		VOTE_ON_POLL(9, false),
		ARBITRARY(10, true),
		ISSUE_ASSET(11, true),
		TRANSFER_ASSET(12, false),
		CREATE_ASSET_ORDER(13, false),
		CANCEL_ASSET_ORDER(14, false),
		MULTI_PAYMENT(15, false),
		DEPLOY_AT(16, true),
		MESSAGE(17, true),
		CHAT(18, false),
		PUBLICIZE(19, false),
		// 20 reserved: former AIRDROP transaction type.
		AT(21, false),
		CREATE_GROUP(22, true),
		UPDATE_GROUP(23, true),
		ADD_GROUP_ADMIN(24, true),
		REMOVE_GROUP_ADMIN(25, true),
		GROUP_BAN(26, true),
		CANCEL_GROUP_BAN(27, true),
		GROUP_KICK(28, true),
		GROUP_INVITE(29, true),
		CANCEL_GROUP_INVITE(30, true),
		JOIN_GROUP(31, false),
		LEAVE_GROUP(32, false),
		GROUP_APPROVAL(33, false),
		SET_GROUP(34, false),
		UPDATE_ASSET(35, true),
		// 36 reserved: former ACCOUNT_FLAGS transaction type.
		// 37 reserved: former ENABLE_FORGING transaction type.
		REWARD_SHARE(38, false),
		// 39 reserved: former ACCOUNT_LEVEL transaction type.
		TRANSFER_PRIVS(40, false),
		PRESENCE(41, false),
		SELL_ASSET_OWNERSHIP(42, false),
		CANCEL_SELL_ASSET_OWNERSHIP(43, false),
		BUY_ASSET_OWNERSHIP(44, false),
		RATE_RESOURCE(45, false),
		RATE_ACCOUNT(46, false),
		UPDATE_POLL(47, true),
		CHAIN_PARAMETER_UPDATE(48, true),
		/** Chain-authorizes one immutable public QDN THUMBNAIL revision as a group's avatar. */
		SET_GROUP_AVATAR(49, true),
		SET_ACCOUNT_AVATAR(50, false);

		public final int value;
		public final boolean needsApproval;
		public final String valueString;
		public final String className;
		public final Class<?> clazz;
		public final Constructor<?> constructor;

		private static final Map<Integer, TransactionType> map = stream(TransactionType.values()).collect(toMap(type -> type.value, type -> type));
		private static final Map<Integer, String> reservedIds = Map.of(
				20, "former AIRDROP transaction type",
				36, "former ACCOUNT_FLAGS transaction type",
				37, "former ENABLE_FORGING transaction type",
				39, "former ACCOUNT_LEVEL transaction type");
		private static final int maxValue = stream(TransactionType.values()).mapToInt(type -> type.value).max().orElse(0);

		TransactionType(int value, boolean needsApproval) {
			this.value = value;
			this.needsApproval = needsApproval;
			this.valueString = String.valueOf(value);

			String[] classNameParts = this.name().toLowerCase().split("_");

			for (int i = 0; i < classNameParts.length; ++i)
				classNameParts[i] = classNameParts[i].substring(0, 1).toUpperCase().concat(classNameParts[i].substring(1));

			this.className = String.join("", classNameParts);

			Class<?> subClazz = null;
			Constructor<?> subConstructor = null;

			try {
				subClazz = Class.forName(String.join("", Transaction.class.getPackage().getName(), ".", this.className, "Transaction"));

				try {
					subConstructor = subClazz.getConstructor(Repository.class, TransactionData.class);
				} catch (NoSuchMethodException | SecurityException e) {
					LOGGER.debug(String.format("Transaction subclass constructor not found for transaction type \"%s\"", this.name()));
				}
			} catch (ClassNotFoundException e) {
				LOGGER.debug(String.format("Transaction subclass not found for transaction type \"%s\"", this.name()));
			}

			this.clazz = subClazz;
			this.constructor = subConstructor;
		}

		public static TransactionType valueOf(int value) {
			return map.get(value);
		}

		public static boolean isReservedId(int value) {
			return reservedIds.containsKey(value);
		}

		public static String getReservedIdDescription(int value) {
			return reservedIds.get(value);
		}

		public static int getMaxValue() {
			return maxValue;
		}

		public boolean supportsMempowFeeAlternative() {
			switch (this) {
				case GENESIS:
				case ARBITRARY:
				case MESSAGE:
				case CHAT:
				case AT:
				case REWARD_SHARE:
				case PRESENCE:
					return false;

				default:
					return true;
			}
		}
	}

	// Group-approval status
	public enum ApprovalStatus {
		NOT_REQUIRED(0),
		PENDING(1),
		APPROVED(2),
		REJECTED(3),
		EXPIRED(4),
		INVALID(5);

		public final int value;

		private static final Map<Integer, ApprovalStatus> map = stream(ApprovalStatus.values()).collect(toMap(result -> result.value, result -> result));

		ApprovalStatus(int value) {
			this.value = value;
		}

		public static ApprovalStatus valueOf(int value) {
			return map.get(value);
		}
	}

	// Validation results
	public enum ValidationResult {
		OK(1),
		INVALID_ADDRESS(2),
		NEGATIVE_AMOUNT(3),
		NEGATIVE_FEE(4),
		NO_BALANCE(5),
		INVALID_REFERENCE(6),
		INVALID_NAME_LENGTH(7),
		INVALID_VALUE_LENGTH(8),
		NAME_ALREADY_REGISTERED(9),
		NAME_DOES_NOT_EXIST(10),
		INVALID_NAME_OWNER(11),
		NAME_ALREADY_FOR_SALE(12),
		NAME_NOT_FOR_SALE(13),
		BUYER_ALREADY_OWNER(14),
		INVALID_AMOUNT(15),
		INVALID_SELLER(16),
		NAME_NOT_NORMALIZED(17),
		INVALID_DESCRIPTION_LENGTH(18),
		INVALID_OPTIONS_COUNT(19),
		INVALID_OPTION_LENGTH(20),
		DUPLICATE_OPTION(21),
		POLL_ALREADY_EXISTS(22),
		POLL_ALREADY_HAS_VOTES(23),
		POLL_DOES_NOT_EXIST(24),
		POLL_OPTION_DOES_NOT_EXIST(25),
		ALREADY_VOTED_FOR_THAT_OPTION(26),
		INVALID_DATA_LENGTH(27),
		INVALID_QUANTITY(28),
		ASSET_DOES_NOT_EXIST(29),
		INVALID_RETURN(30),
		HAVE_EQUALS_WANT(31),
		ORDER_DOES_NOT_EXIST(32),
		INVALID_ORDER_CREATOR(33),
		INVALID_PAYMENTS_COUNT(34),
		NEGATIVE_PRICE(35),
		INVALID_CREATION_BYTES(36),
		INVALID_TAGS_LENGTH(37),
		INVALID_AT_TYPE_LENGTH(38),
		INVALID_AT_TRANSACTION(39),
		INSUFFICIENT_FEE(40),
		ASSET_DOES_NOT_MATCH_AT(41),
		ASSET_ALREADY_EXISTS(43),
		MISSING_CREATOR(44),
		TIMESTAMP_TOO_OLD(45),
		TIMESTAMP_TOO_NEW(46),
		TOO_MANY_UNCONFIRMED(47),
		GROUP_ALREADY_EXISTS(48),
		GROUP_DOES_NOT_EXIST(49),
		INVALID_GROUP_OWNER(50),
		ALREADY_GROUP_MEMBER(51),
		GROUP_OWNER_CANNOT_LEAVE(52),
		NOT_GROUP_MEMBER(53),
		ALREADY_GROUP_ADMIN(54),
		NOT_GROUP_ADMIN(55),
		INVALID_LIFETIME(56),
		INVITE_UNKNOWN(57),
		BAN_EXISTS(58),
		BAN_UNKNOWN(59),
		BANNED_FROM_GROUP(60),
		JOIN_REQUEST_EXISTS(61),
		INVALID_GROUP_APPROVAL_THRESHOLD(62),
		GROUP_ID_MISMATCH(63),
		INVALID_GROUP_ID(64),
		TRANSACTION_UNKNOWN(65),
		TRANSACTION_ALREADY_CONFIRMED(66),
		INVALID_TX_GROUP_ID(67),
		TX_GROUP_ID_MISMATCH(68),
		MULTIPLE_NAMES_FORBIDDEN(69),
		INVALID_ASSET_OWNER(70),
		AT_IS_FINISHED(71),
		NO_FLAG_PERMISSION(72),
		NOT_MINTING_ACCOUNT(73),
		REWARD_SHARE_UNKNOWN(76),
		INVALID_REWARD_SHARE_PERCENT(77),
		PUBLIC_KEY_UNKNOWN(78),
		INVALID_PUBLIC_KEY(79),
		AT_UNKNOWN(80),
		AT_ALREADY_EXISTS(81),
		GROUP_APPROVAL_NOT_REQUIRED(82),
		GROUP_APPROVAL_DECIDED(83),
		MAXIMUM_REWARD_SHARES(84),
		TRANSACTION_ALREADY_EXISTS(85),
		NO_BLOCKCHAIN_LOCK(86),
		ORDER_ALREADY_CLOSED(87),
		CLOCK_NOT_SYNCED(88),
		ASSET_NOT_SPENDABLE(89),
		SELF_SHARE_EXISTS(91),
		ACCOUNT_ALREADY_EXISTS(92),
		INVALID_GROUP_BLOCK_DELAY(93),
		INCORRECT_NONCE(94),
		INVALID_TIMESTAMP_SIGNATURE(95),
		ADDRESS_BLOCKED(96),
		NAME_BLOCKED(97),
		GROUP_APPROVAL_REQUIRED(98),
		ACCOUNT_NOT_TRANSFERABLE(99),
		TRANSFER_PRIVS_DISABLED(100),
		TEMPORARY_DISABLED(101),
		GENERAL_TEMPORARY_DISABLED(102),
		INVALID_BUYER(103),
		ASSET_ALREADY_FOR_SALE(104),
		ASSET_NOT_FOR_SALE(105),
		POLL_CLOSED(106),
		INVALID_RATING(107),
		INVALID_RESOURCE(108),
		RESOURCE_DOES_NOT_EXIST(109),
		ALREADY_RATED_RESOURCE(110),
		INVALID_ACCOUNT_RATING(111),
		ACCOUNT_RATING_UNCHANGED(112),
		CANNOT_RATE_SELF(113),
		INVALID_POLL_OWNER(114),
		ACCOUNT_RATING_CHANGE_TOO_SOON(115),
		POLL_NOT_STARTED(116),
		INVALID_AVATAR_OWNER(117),
		AT_VERSION_NOT_YET_ACTIVE(118),
		INVALID_BUT_OK(999),
		NOT_YET_RELEASED(1000),
		NOT_SUPPORTED(1001);

		public final int value;

		private static final Map<Integer, ValidationResult> map = stream(ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

		ValidationResult(int value) {
			this.value = value;
		}

		public static ValidationResult valueOf(int value) {
			return map.get(value);
		}
	}

	private static final Logger LOGGER = LogManager.getLogger(Transaction.class);

	public static final int CURRENT_VERSION = 1;

	// Properties

	protected Repository repository;
	protected TransactionData transactionData;
	/** Cached creator account. Use <tt>getCreator()</tt> to access. */
	private PublicKeyAccount creator = null;

	// Constructors

	/**
	 * Basic constructor for use by subclasses.
	 * 
	 * @param repository
	 * @param transactionData
	 */
	protected Transaction(Repository repository, TransactionData transactionData) {
		this.repository = repository;
		this.transactionData = transactionData;
	}

	/**
	 * Returns subclass of Transaction constructed using passed transaction data.
	 * <p>
	 * Uses transaction-type in transaction data to call relevant subclass constructor.
	 * 
	 * @param repository
	 * @param transactionData
	 * @return a Transaction subclass, or null if a transaction couldn't be determined/built from passed data
	 */
	public static Transaction fromData(Repository repository, TransactionData transactionData) {
		TransactionType type = transactionData.getType();

		try {
			Constructor<?> constructor = type.constructor;

			if (constructor == null)
				throw new IllegalStateException("Unsupported transaction type [" + type.value + "] during fetch from repository");

			return (Transaction) constructor.newInstance(repository, transactionData);
		} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | InstantiationException e) {
			throw new IllegalStateException("Internal error with transaction type [" + type.value + "] during fetch from repository");
		}
	}

	// Getters / Setters

	public TransactionData getTransactionData() {
		return this.transactionData;
	}

	public void setRepository(Repository repository) {
		this.repository = repository;
	}

	// More information

	public static long getDeadline(TransactionData transactionData) {
		// Calculate deadline to include transaction in a block
		return transactionData.getTimestamp() + BlockChain.getInstance().getTransactionExpiryPeriod();
	}

	public long getDeadline() {
		return Transaction.getDeadline(transactionData);
	}

	/** Returns whether transaction's fee is at least the effective normal unit fee. */
	public boolean hasMinimumFee() throws DataException {
		return this.transactionData.getFee() >= this.getEffectiveUnitFee(this.transactionData.getTimestamp());
	}

	public long feePerByte() {
		try {
			return this.transactionData.getFee() / TransactionTransformer.getDataLength(this.transactionData);
		} catch (TransformationException e) {
			throw new IllegalStateException("Unable to get transaction byte length?");
		}
	}

	/** Returns whether transaction's fee is at least amount needed to cover byte-length of transaction. */
	public boolean hasMinimumFeePerByte() throws DataException {
		long unitFee = this.getEffectiveUnitFee(this.transactionData.getTimestamp());
		int maxBytePerUnitFee = BlockChain.getInstance().getMaxBytesPerUnitFee();

		// If the unit fee is zero, any fee is enough to cover the byte-length of the transaction
		if (unitFee == 0) {
			return true;
		}
		return this.feePerByte() >= maxBytePerUnitFee / unitFee;
	}

	/** Returns whether this transaction can use a MemoryPoW nonce as an alternative to the normal paid fee. */
	protected boolean canUseMempowFeeAlternative() {
		return canUseMempowFeeAlternative(this.transactionData.getType());
	}

	public boolean supportsMempowFeeAlternative() {
		return this.canUseMempowFeeAlternative();
	}

	protected static boolean canUseMempowFeeAlternative(TransactionType transactionType) {
		return transactionType.supportsMempowFeeAlternative();
	}

	/** Returns whether this transaction carries a positive fee. */
	protected boolean hasPaidFee() {
		Long fee = this.transactionData.getFee();
		return fee != null && fee > 0;
	}

	/** Returns whether this transaction satisfies the fee policy. */
	protected boolean hasValidFeeOrMempow() throws DataException {
		if (this.hasMinimumFee() && this.hasMinimumFeePerByte())
			return true;

		return this.canUseMempowFeeAlternative() && this.hasValidMempowFeeNonce();
	}

	/** Returns whether this transaction has a valid MemoryPoW fee-alternative nonce. */
	protected boolean hasValidMempowFeeNonce() throws DataException {
		Integer nonce = this.transactionData.getNonceOrNull();
		if (nonce == null || nonce < 0)
			return false;

		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new DataException("Unable to transform transaction to byte array for MemoryPoW verification", e);
		}

		TransactionTransformer.clearMempowFeeNonce(transactionBytes);

		return MemoryPoW.verify2(transactionBytes, this.getMempowFeeAlternativeBufferSize(),
				this.getMempowFeeAlternativeDifficulty(), nonce);
	}

	protected int getMempowFeeAlternativeBufferSize() {
		return MEMPOW_FEE_ALTERNATIVE_BUFFER_SIZE;
	}

	protected int getMempowFeeAlternativeDifficulty() {
		return BlockChain.getInstance().getMempowFeeAlternativeDifficulty();
	}

	public void computeMempowFeeNonce() throws DataException {
		if (!this.canUseMempowFeeAlternative())
			throw new DataException(String.format("%s transactions do not support MemoryPoW fee alternatives",
					this.transactionData.getType()));

		byte[] transactionBytes;

		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(this.transactionData);
		} catch (TransformationException e) {
			throw new DataException("Unable to transform transaction to byte array for MemoryPoW computation", e);
		}

		TransactionTransformer.clearMempowFeeNonce(transactionBytes);

		this.transactionData.setNonce(MemoryPoW.compute2(transactionBytes,
				this.getMempowFeeAlternativeBufferSize(), this.getMempowFeeAlternativeDifficulty()));
	}

	protected ValidationResult validateMempowFeePolicy() throws DataException {
		Long fee = this.transactionData.getFee();
		if (fee == null)
			return ValidationResult.INSUFFICIENT_FEE;

		if (fee < 0)
			return ValidationResult.NEGATIVE_FEE;

		return this.hasValidFeeOrMempow() ? ValidationResult.OK : ValidationResult.INSUFFICIENT_FEE;
	}

	public long calcRecommendedFee() {
		int dataLength;
		try {
			dataLength = TransactionTransformer.getDataLength(this.transactionData);
		} catch (TransformationException e) {
			throw new IllegalStateException("Unable to get transaction byte length?");
		}

		int maxBytePerUnitFee = BlockChain.getInstance().getMaxBytesPerUnitFee();

		int unitFeeCount = ((dataLength - 1) / maxBytePerUnitFee) + 1;

		try {
			return this.getEffectiveUnitFee(this.transactionData.getTimestamp()) * unitFeeCount;
		} catch (DataException e) {
			throw new IllegalStateException("Unable to determine effective transaction unit fee", e);
		}
	}

	/**
	 * Calculate unit fee for a given transaction type
	 *
	 * FUTURE: add "accountLevel" parameter if needed - the level of the transaction creator
	 * @param timestamp - the transaction's timestamp for fallback fee schedules
	 * @return
	 */
	public long getUnitFee(Long timestamp) {
		return BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp);
	}

	protected long getEffectiveUnitFee(Long timestamp) throws DataException {
		if (this.repository == null)
			return this.getUnitFee(timestamp);

		int nextBlockHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		return BlockChain.getInstance().getUnitFeeAtHeight(this.repository, nextBlockHeight, timestamp);
	}

	/**
	 * Return the transaction version number that should be used, based on passed timestamp.
	 * <p>
	 * Qortium uses version 1 as its baseline transaction version.
	 * 
	 * @param timestamp
	 * @return transaction version number
	 */
	public static int getVersionByTimestamp(long timestamp) {
		return CURRENT_VERSION;
	}

	/**
	 * Get block height for this transaction in the blockchain.
	 * 
	 * @return height, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws DataException
	 */
	public int getHeight() throws DataException {
		return this.repository.getTransactionRepository().getHeightFromSignature(this.transactionData.getSignature());
	}

	/**
	 * Get number of confirmations for this transaction.
	 * 
	 * @return confirmation count, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws DataException
	 */
	public int getConfirmations() throws DataException {
		int ourHeight = getHeight();
		if (ourHeight == 0)
			return 0;

		int blockChainHeight = this.repository.getBlockRepository().getBlockchainHeight();
		if (blockChainHeight == 0)
			return 0;

		return blockChainHeight - ourHeight + 1;
	}

	/**
	 * Returns a list of recipient addresses for this transaction.
	 * 
	 * @return list of recipients addresses, or empty list if none
	 * @throws DataException
	 */
	public abstract List<String> getRecipientAddresses() throws DataException;

	/**
	 * Returns a list of involved addresses for this transaction.
	 * <p>
	 * "Involved" means sender or recipient.
	 * 
	 * @return list of involved addresses, or empty list if none
	 * @throws DataException
	 */
	public List<String> getInvolvedAddresses() throws DataException {
		// Typically this is all the recipients plus the transaction creator/sender
		List<String> participants = new ArrayList<>(getRecipientAddresses());
		participants.add(0, this.getCreator().getAddress());
		return participants;
	}

	// Navigation

	/**
	 * Return transaction's "creator" account.
	 * 
	 * @return creator
	 * @throws DataException
	 */
	protected PublicKeyAccount getCreator() {
		if (this.creator == null)
			this.creator = new PublicKeyAccount(this.repository, this.transactionData.getCreatorPublicKey());

		return this.creator;
	}

	// Processing

	public void sign(PrivateKeyAccount signer) {
		try {
			this.transactionData.setSignature(signer.sign(TransactionTransformer.toBytesForSigning(transactionData)));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for signing", e);
		}
	}

	public boolean isSignatureValid() {
		byte[] signature = this.transactionData.getSignature();
		if (signature == null)
			return false;

		try {
			return Crypto.verify(this.transactionData.getCreatorPublicKey(), signature, TransactionTransformer.toBytesForSigning(transactionData));
		} catch (TransformationException e) {
			throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
		}
	}

	/**
	 * Returns whether transaction can be added to unconfirmed transactions.
	 * 
	 * @return transaction validation result, e.g. OK
	 * @throws DataException
	 */
	public ValidationResult isValidUnconfirmed() throws DataException {
		return this.isValidUnconfirmed(false);
	}

	/**
	 * Returns whether transaction can be built as raw, unsigned API output.
	 * <p>
	 * This follows normal unconfirmed validation, but allows a MemoryPoW-capable
	 * transaction to omit its fee-alternative nonce because API clients may need
	 * raw unsigned bytes before asking the core to compute that nonce.
	 *
	 * @return transaction validation result, e.g. OK
	 * @throws DataException
	 */
	public ValidationResult isValidUnconfirmedForUnsignedBuild() throws DataException {
		return this.isValidUnconfirmed(true);
	}

	private ValidationResult isValidUnconfirmed(boolean allowMissingMempowFeeNonce) throws DataException {
		final Long now = NTP.getTime();
		if (now == null)
			return ValidationResult.CLOCK_NOT_SYNCED;

		// Expired already?
		if (now >= this.getDeadline())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a timestamp too far into future are too new
		long maxTimestamp = now + Settings.getInstance().getMaxTransactionTimestampFuture();
		if (this.transactionData.getTimestamp() > maxTimestamp)
			return ValidationResult.TIMESTAMP_TOO_NEW;

		// Check fee is sufficient
		ValidationResult feeValidationResult = allowMissingMempowFeeNonce
				? isFeeValidForUnsignedBuild()
				: isFeeValid();
		if (feeValidationResult != ValidationResult.OK)
			return feeValidationResult;

		if (Settings.getInstance().isLite()) {
			// Everything from this point is difficult to validate for a lite node, since it has no blocks.
			// For now, we will assume it is valid, to allow it to move around the network easily.
			// If it turns out to be invalid, other full/top-only nodes will reject it on receipt.
			// Lite nodes would never mint a block, so there's not much risk of holding invalid transactions.
			// TODO: implement lite-only validation for each transaction type
			return ValidationResult.OK;
		}

		byte[] creatorPublicKey = this.transactionData.getCreatorPublicKey();
		if (creatorPublicKey == null)
			return ValidationResult.MISSING_CREATOR;

		if (creatorPublicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			return ValidationResult.INVALID_PUBLIC_KEY;

		PublicKeyAccount creator = this.getCreator();

		// Reject if unconfirmed pile already has X transactions from same creator
		if (countUnconfirmedByCreator(creator) >= Settings.getInstance().getMaxUnconfirmedPerAccount())
			return ValidationResult.TOO_MANY_UNCONFIRMED;

		// Transactions with a expiry prior to latest block's timestamp are too old
		// Not relevant for lite nodes, as they don't have any blocks
		BlockData latestBlock = repository.getBlockRepository().getLastBlock();
		if (this.getDeadline() <= latestBlock.getTimestamp())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Check transaction's txGroupId
		if (!this.isValidTxGroupId())
			return ValidationResult.INVALID_TX_GROUP_ID;

		// Check transaction is valid
		ValidationResult result = this.isValid();
		if (result != ValidationResult.OK)
			return result;

		result = this.isValidAtTimestamp(now);
		if (result != ValidationResult.OK)
			return result;

		// Check transaction is processable
		result = this.isProcessable();

		return result;
	}

	private ValidationResult isFeeValidForUnsignedBuild() throws DataException {
		ValidationResult result = this.isFeeValid();
		if (result != ValidationResult.INSUFFICIENT_FEE)
			return result;

		Long fee = this.transactionData.getFee();
		if (fee == null || fee < 0)
			return result;

		if (!this.canUseMempowFeeAlternative())
			return result;

		Integer nonce = this.transactionData.getNonceOrNull();
		// Wire formats with an always-present nonce use zero as the unsigned-build
		// sentinel. Home attests that sentinel before computing the real MemoryPoW.
		return nonce == null || nonce == 0 ? ValidationResult.OK : result;
	}

	/** Returns whether transaction's fee is valid. Might be overriden in transaction subclasses. */
	public ValidationResult isFeeValid() throws DataException {
		return this.validateMempowFeePolicy();
	}

	protected boolean isValidTxGroupId() throws DataException {
		int txGroupId = this.transactionData.getTxGroupId();

		// If transaction type doesn't need approval then we insist on NO_GROUP
		if (!this.transactionData.getType().needsApproval)
			return txGroupId == Group.NO_GROUP;

		// Handling NO_GROUP
		if (txGroupId == Group.NO_GROUP)
			// true if NO_GROUP txGroupId is allowed for approval-needing tx types
			return !BlockChain.getInstance().getRequireGroupForApproval();

		// Group even exist?
		if (!this.repository.getGroupRepository().groupExists(txGroupId))
			return false;

		GroupRepository groupRepository = this.repository.getGroupRepository();

		// Is transaction's creator is group member?
		PublicKeyAccount creator = this.getCreator();
		if (groupRepository.memberExists(txGroupId, creator.getAddress()))
			return true;

		return false;
	}

	private int countUnconfirmedByCreator(PublicKeyAccount creator) throws DataException {
		List<TransactionData> unconfirmedTransactions = TransactionImporter.getInstance().unconfirmedTransactionsCache;
		if (unconfirmedTransactions == null) {
			unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
		}

		// We exclude CHAT transactions as they never get included into blocks and
		// have spam/DoS prevention by requiring proof of work
		Predicate<TransactionData> hasSameCreatorButNotChat = transactionData -> {
			if (transactionData.getType() == TransactionType.CHAT)
				return false;

			return Arrays.equals(creator.getPublicKey(), transactionData.getCreatorPublicKey());
		};

		return (int) unconfirmedTransactions.stream().filter(hasSameCreatorButNotChat).count();
	}

	/**
	 * Returns sorted, unconfirmed transactions, excluding invalid and unconfirmable.
	 * 
	 * @return sorted, unconfirmed transactions
	 * @throws DataException
	 */
	public static List<TransactionData> getUnconfirmedTransactions(Repository repository) throws DataException {
		BlockData latestBlockData = repository.getBlockRepository().getLastBlock();

		EnumSet<TransactionType> excludedTxTypes = EnumSet.of(TransactionType.CHAT, TransactionType.PRESENCE);
		List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions(excludedTxTypes, null);

		unconfirmedTransactions.sort(getDataComparator());

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();
			Transaction transaction = Transaction.fromData(repository, transactionData);

			// Must be confirmable and valid
			if (!transaction.isConfirmable() || transaction.isStillValidUnconfirmed(latestBlockData.getTimestamp()) != ValidationResult.OK)
				unconfirmedTransactionsIterator.remove();
		}

		return unconfirmedTransactions;
	}

	/**
	 * Returns invalid, unconfirmed transactions.
	 * 
	 * @return sorted, invalid, unconfirmed transactions
	 * @throws DataException
	 */
	public static List<TransactionData> getInvalidTransactions(Repository repository) throws DataException {
		BlockData latestBlockData = repository.getBlockRepository().getLastBlock();

		List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
		List<TransactionData> invalidTransactions = new ArrayList<>();

		unconfirmedTransactions.sort(getDataComparator());

		Iterator<TransactionData> unconfirmedTransactionsIterator = unconfirmedTransactions.iterator();
		while (unconfirmedTransactionsIterator.hasNext()) {
			TransactionData transactionData = unconfirmedTransactionsIterator.next();
			Transaction transaction = Transaction.fromData(repository, transactionData);

			if (transaction.isStillValidUnconfirmed(latestBlockData.getTimestamp()) != ValidationResult.OK)
				invalidTransactions.add(transactionData);
		}

		return invalidTransactions;
	}

	/**
	 * Returns whether transaction is still a valid unconfirmed transaction.
	 * <p>
	 * This is like {@link #isValidUnconfirmed()} but only needs to perform
	 * a subset of those checks.
	 * 
	 * @return transaction validation result, e.g. OK
	 * @throws DataException
	 */
	private ValidationResult isStillValidUnconfirmed(long blockTimestamp) throws DataException {
		final Long now = NTP.getTime();
		if (now == null)
			return ValidationResult.CLOCK_NOT_SYNCED;

		// Expired already?
		if (now >= this.getDeadline())
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a expiry prior to latest block's timestamp are too old
		if (this.getDeadline() <= blockTimestamp)
			return ValidationResult.TIMESTAMP_TOO_OLD;

		// Transactions with a timestamp too far into future are too new
		// Skipped because this test only applies at instant of submission

		// Check fee is sufficient
		// Skipped because this is checked upon submission and the result would be the same now

		// Reject if unconfirmed pile already has X transactions from same creator
		// Skipped because this test only applies at instant of submission

		// Check transaction's txGroupId
		// Skipped because this is checked upon submission and the result would be the same now

		// Check transaction is valid
		ValidationResult result = this.isValid();
		if (result != ValidationResult.OK)
			return result;

		result = this.isValidAtTimestamp(now);
		if (result != ValidationResult.OK)
			return result;

		// Check transaction is processable
		result = this.isProcessable();

		return result;
	}

	/**
	 * Returns whether transaction needs to go through group-admin approval.
	 * <p>
	 * This test is more than simply "does this transaction type need approval?"
	 * because group admins bypass approval for transactions attached to their group.
	 * 
	 * @throws DataException
	 */
	public boolean needsGroupApproval() throws DataException {
		// Does this transaction type bypass approval?
		if (!this.transactionData.getType().needsApproval)
			return false;

		int txGroupId = this.transactionData.getTxGroupId();

		if (txGroupId == Group.NO_GROUP)
			return false;

		GroupRepository groupRepository = this.repository.getGroupRepository();

		if (!groupRepository.groupExists(txGroupId))
			// Group no longer exists? Possibly due to blockchain orphaning undoing group creation?
			return true; // stops tx being included in block but it will eventually expire

		String groupOwner = this.repository.getGroupRepository().getOwner(txGroupId);
		boolean groupOwnedByNullAccount = Objects.equals(groupOwner, Group.NULL_OWNER_ADDRESS);

		// If transaction's creator is group admin (of group with ID txGroupId) then auto-approve
		// This is disabled for null-owned groups, since these require approval from other admins
		PublicKeyAccount creator = this.getCreator();
		if (!groupOwnedByNullAccount && groupRepository.adminExists(txGroupId, creator.getAddress()))
			return false;

		return true;
	}

	public void setInitialApprovalStatus() throws DataException {
		if (this.needsGroupApproval()) {
			transactionData.setApprovalStatus(ApprovalStatus.PENDING);
		} else {
			transactionData.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
		}
	}

	public Boolean getApprovalDecision(int approvalHeight) throws DataException {
		// Grab latest decisions from repository
		GroupApprovalData groupApprovalData = this.repository.getTransactionRepository().getApprovalData(this.transactionData.getSignature(), approvalHeight);
		if (groupApprovalData == null)
			return null;

		// We need group info
		int txGroupId = this.transactionData.getTxGroupId();
		GroupData groupData = repository.getGroupRepository().fromGroupId(txGroupId);
		ApprovalThreshold approvalThreshold = groupData.getApprovalThreshold();

		// Fetch total number of accounts currently allowed to approve this group's transactions
		int totalAuthorities = Group.countApprovalAuthorities(repository, txGroupId, this.transactionData.getType(), approvalHeight);
		if (totalAuthorities <= 0)
			return null;

		int approvingAuthorities = countCurrentApprovalAuthorities(groupApprovalData.approvingAdmins, txGroupId, approvalHeight);

		// Are there enough approvals?
		if (approvalThreshold.meetsTheshold(approvingAuthorities, totalAuthorities))
			return true;

		// Rejection votes are recorded as opposition, but pending transactions fail by expiry.
		return null;
	}

	private int countCurrentApprovalAuthorities(List<byte[]> publicKeys, int groupId, int approvalHeight) throws DataException {
		int count = 0;

		for (byte[] publicKey : publicKeys) {
			String address = Crypto.toAddress(publicKey);
			if (Group.canApprove(this.repository, groupId, address, this.transactionData.getType(), approvalHeight))
				++count;
		}

		return count;
	}

	/**
	 * Import into our repository as a new, unconfirmed transaction.
	 * <p>
	 * @implSpec <i>blocks</i> to obtain blockchain lock
	 * <p>
	 * If transaction is valid, then:
	 * <ul>
	 * <li>calls {@link Repository#discardChanges()}</li>
	 * <li>calls {@link Controller#onNewTransaction(TransactionData, Peer)}</li>
	 * </ul>
	 * 
	 * @throws DataException
	 */
	public ValidationResult importAsUnconfirmed() throws DataException {
		// Attempt to acquire blockchain lock
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		blockchainLock.lock();

		try {
			// Check transaction doesn't already exist
			if (repository.getTransactionRepository().exists(transactionData.getSignature()))
				return ValidationResult.TRANSACTION_ALREADY_EXISTS;

			// Fix up approval status
			this.setInitialApprovalStatus();

			this.preProcess();

			ValidationResult validationResult = this.isValidUnconfirmed();
			if (validationResult != ValidationResult.OK)
				return validationResult;

			/*
			 * We call discardChanges() to restart repository 'transaction', discarding any
			 * transactional table locks, hence reducing possibility of deadlock or
			 * "serialization failure" with HSQLDB due to reads.
			 * 
			 * We should be OK to proceed after validation check as we're protected by
			 * BLOCKCHAIN_LOCK so no other thread will be writing the same transaction.
			 */
			repository.discardChanges();

			repository.getTransactionRepository().save(transactionData);
			repository.getTransactionRepository().unconfirmTransaction(transactionData);

			this.onImportAsUnconfirmed();

			repository.saveChanges();

			// Notify controller of new transaction
			Controller.getInstance().onNewTransaction(transactionData);

			return ValidationResult.OK;
		} finally {
			/*
			 * We call discardChanges() to restart repository 'transaction', discarding any
			 * transactional table locks, hence reducing possibility of deadlock or
			 * "serialization failure" with HSQLDB due to reads.
			 * 
			 * "Serialization failure" most likely caused by existing transaction check above,
			 * where multiple threads are importing transactions
			 * and one thread finds existing an transaction, returns (unlocking blockchain lock),
			 * then another thread immediately obtains lock, tries to delete above existing transaction
			 * (e.g. older PRESENCE transaction) but can't because first thread's repository
			 * session still has row-lock on existing transaction and hasn't yet closed
			 * repository session. Deadlock caused by race condition.
			 * 
			 * Hence we clear any repository-based locks before releasing blockchain lock.
			 */
			repository.discardChanges();

			blockchainLock.unlock();
		}
	}

	/**
	 * Callback for when a transaction is imported as unconfirmed.
	 * <p>
	 * Called after transaction is added to repository, but before commit.
	 * <p>
	 * Blockchain lock is being held during this time.
	 */
	protected void onImportAsUnconfirmed() throws DataException {
		/* To be optionally overridden */
	}

	/**
	 * Returns whether transaction is 'confirmable' - i.e. is of a type that
	 * can be included in a block. Some transactions are 'unconfirmable'
	 * and therefore must remain in the mempool until they expire.
	 * @return
	 */
	public boolean isConfirmable() {
		/* To be optionally overridden */
		return true;
	}

	/**
	 * Returns whether transaction is confirmable in a block at a given height.
	 * @return
	 */
	public boolean isConfirmableAtHeight(int height) {
		/* To be optionally overridden */
		return true;
	}

	/**
	 * Returns whether transaction can be added to the blockchain.
	 * <p>
	 * Checks if transaction can have {@link TransactionHandler#process()} called.
	 * <p>
	 * Transactions that have already been processed will return false.
	 * 
	 * @return true if transaction can be processed, false otherwise
	 * @throws DataException
	 */
	public abstract ValidationResult isValid() throws DataException;

	public ValidationResult isValidAtTimestamp(long timestamp) throws DataException {
		return ValidationResult.OK;
	}

	/**
	 * Returns whether transaction can be processed.
	 * <p>
	 * With group-approval, even if a transaction had valid values
	 * when submitted, by the time it is approved these values
	 * might become invalid, e.g. because dependencies might
	 * have changed.
	 * <p>
	 * For example, with UPDATE_ASSET, the asset owner might have
	 * changed between submission and approval and so the transaction
	 * is invalid because the previous owner (as specified in the
	 * transaction) no longer has permission to update the asset.
	 * 
	 * @throws DataException
	 */
	public ValidationResult isProcessable() throws DataException {
		return ValidationResult.OK;
	}

	/**
	 * Pre-process a transaction before validating or processing the block.
	 *
	 * @throws DataException
	 */
	public void preProcess() throws DataException {
		// Nothing to do
	}

	/**
	 * Ensure confirmed transaction creator metadata exists before transaction-specific processing.
	 *
	 * @throws DataException
	 */
	public void processCreatorAccount() throws DataException {
		this.getCreator().ensureAccount();
	}

	/**
	 * Actually process a transaction, updating the blockchain.
	 * <p>
	 * Processes transaction, updating balances, references, assets, etc. as appropriate.
	 * 
	 * @throws DataException
	 */
	public abstract void process() throws DataException;

	/**
	 * Ensure transaction creator exists and subtract transaction fees.
	 * 
	 * @throws DataException
	 */
	public void processReferencesAndFees() throws DataException {
		Account creator = getCreator();

		// Keep creator public-key metadata even though references are no longer updated.
		creator.ensureAccount();

		long fee = transactionData.getFee();

		// Update transaction creator's balance
		if (fee > 0)
			creator.modifyAssetBalance(Asset.NATIVE, - fee);
	}

	/**
	 * Undo transaction, updating the blockchain.
	 * <p>
	 * Undoes transaction, updating balances, references, assets, etc. as appropriate.
	 * 
	 * @throws DataException
	 */
	public abstract void orphan() throws DataException;

	/**
	 * Restore transaction fees without changing legacy account references.
	 * 
	 * @throws DataException
	 */
	public void orphanReferencesAndFees() throws DataException {
		Account creator = getCreator();

		long fee = transactionData.getFee();

		// Update transaction creator's balance
		if (fee > 0)
			creator.modifyAssetBalance(Asset.NATIVE, fee);
	}


	// Comparison

	/** Returns comparator that sorts ATTransactions first, then by timestamp, then by signature */
	public static Comparator<Transaction> getComparator() {
		class TransactionComparator implements Comparator<Transaction> {

			private Comparator<TransactionData> transactionDataComparator;

			public TransactionComparator(Comparator<TransactionData> transactionDataComparator) {
				this.transactionDataComparator = transactionDataComparator;
			}

			// Compare by type, timestamp, then signature
			@Override
			public int compare(Transaction t1, Transaction t2) {
				TransactionData td1 = t1.getTransactionData();
				TransactionData td2 = t2.getTransactionData();

				return transactionDataComparator.compare(td1, td2);
			}

		}

		return new TransactionComparator(getDataComparator());
	}

	public static Comparator<TransactionData> getDataComparator() {
		class TransactionDataComparator implements Comparator<TransactionData> {

			// Compare by type, timestamp, then signature
			@Override
			public int compare(TransactionData td1, TransactionData td2) {
				// AT transactions come before non-AT transactions
				if (td1.getType() == TransactionType.AT && td2.getType() != TransactionType.AT)
					return -1;

				// Non-AT transactions come after AT transactions
				if (td1.getType() != TransactionType.AT && td2.getType() == TransactionType.AT)
					return 1;

				// If both transactions are AT type, then preserve existing ordering.
				if (td1.getType() == TransactionType.AT)
					return 0;

				// Both transactions are non-AT so compare timestamps
				int result = Long.compare(td1.getTimestamp(), td2.getTimestamp());

				if (result == 0)
					// Same timestamp so compare signatures
					result = new BigInteger(td1.getSignature()).compareTo(new BigInteger(td2.getSignature()));

				return result;
			}

		}

		return new TransactionDataComparator();
	}

	@Override
	public int hashCode() {
		return this.transactionData.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof TransactionData))
			return false;

		return this.transactionData.equals(other);
	}

}
