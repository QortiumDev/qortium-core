package org.qortium.repository.hsqldb.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.arbitrary.misc.Service;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.account.AccountData;
import org.qortium.data.group.GroupApprovalData;
import org.qortium.data.group.GroupKickSummaryData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.TransactionRepository;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Base58;
import org.qortium.utils.Unicode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.qortium.transaction.Transaction.TransactionType.*;

public class HSQLDBTransactionRepository implements TransactionRepository {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBTransactionRepository.class);

	public static class RepositorySubclassInfo {
		public Class<?> clazz;
		public Constructor<?> constructor;
		public Method fromBaseMethod;
		public Method saveMethod;
		public Method deleteMethod;
	}

	private static final RepositorySubclassInfo[] subclassInfos;
	static {
		subclassInfos = new RepositorySubclassInfo[TransactionType.getMaxValue() + 1];

		for (TransactionType txType : TransactionType.values()) {
			RepositorySubclassInfo subclassInfo = new RepositorySubclassInfo();

			try {
				subclassInfo.clazz = Class.forName(
						String.join("", HSQLDBTransactionRepository.class.getPackage().getName(), ".", "HSQLDB", txType.className, "TransactionRepository"));
			} catch (ClassNotFoundException e) {
				LOGGER.trace(String.format("HSQLDBTransactionRepository subclass not found for transaction type \"%s\"", txType.name()));
				continue;
			}

			try {
				subclassInfo.constructor = subclassInfo.clazz.getConstructor(HSQLDBRepository.class);
			} catch (NoSuchMethodException | IllegalArgumentException e) {
				LOGGER.debug(String.format("HSQLDBTransactionRepository subclass constructor not found for transaction type \"%s\"", txType.name()));
				continue;
			}

			try {
				subclassInfo.fromBaseMethod = subclassInfo.clazz.getDeclaredMethod("fromBase", BaseTransactionData.class);
			} catch (IllegalArgumentException | SecurityException | NoSuchMethodException e) {
				LOGGER.debug(String.format("HSQLDBTransactionRepository subclass's \"fromBase\" method not found for transaction type \"%s\"", txType.name()));
			}

			try {
				subclassInfo.saveMethod = subclassInfo.clazz.getDeclaredMethod("save", TransactionData.class);
			} catch (IllegalArgumentException | SecurityException | NoSuchMethodException e) {
				LOGGER.debug(String.format("HSQLDBTransactionRepository subclass's \"save\" method not found for transaction type \"%s\"", txType.name()));
			}

			try {
				subclassInfo.deleteMethod = subclassInfo.clazz.getDeclaredMethod("delete", TransactionData.class);
			} catch (NoSuchMethodException e) {
				// Subclass has no "delete" method - this is OK
				subclassInfo.deleteMethod = null;
			} catch (IllegalArgumentException | SecurityException e) {
				LOGGER.debug(String.format("HSQLDBTransactionRepository subclass's \"save\" method not found for transaction type \"%s\"", txType.name()));
			}

			subclassInfos[txType.value] = subclassInfo;
		}

		LOGGER.trace("Static init reflection completed");
	}

	private HSQLDBTransactionRepository[] repositoryByTxType;

	protected HSQLDBRepository repository;

	public HSQLDBTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;

		this.repositoryByTxType = new HSQLDBTransactionRepository[TransactionType.getMaxValue() + 1];

		for (TransactionType txType : TransactionType.values()) {
			RepositorySubclassInfo subclassInfo = subclassInfos[txType.value];

			if (subclassInfo == null || subclassInfo.constructor == null)
				continue;

			try {
				this.repositoryByTxType[txType.value] = (HSQLDBTransactionRepository) subclassInfo.constructor.newInstance(repository);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | InstantiationException e) {
				LOGGER.debug(String.format("HSQLDBTransactionRepository subclass constructor failed for transaction type \"%s\"", txType.name()));
			}
		}
	}

	// Never called
	protected HSQLDBTransactionRepository() {
	}

	private static Integer getNullableInteger(ResultSet resultSet, int columnIndex) throws SQLException {
		Integer value = resultSet.getInt(columnIndex);
		return value == 0 && resultSet.wasNull() ? null : value;
	}

	// Fetching transactions / transaction height

	@Override
	public TransactionData fromSignature(byte[] signature) throws DataException {
		String sql = "SELECT type, creator, created_when, fee, tx_group_id, block_height, approval_status, approval_height, nonce "
				+ "FROM Transactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return null;

			TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

			byte[] creatorPublicKey = resultSet.getBytes(2);
			long timestamp = resultSet.getLong(3);

			Long fee = resultSet.getLong(4);
			if (fee == 0 && resultSet.wasNull())
				fee = null;

			int txGroupId = resultSet.getInt(5);

			Integer blockHeight = resultSet.getInt(6);
			if (blockHeight == 0 && resultSet.wasNull())
				blockHeight = null;

			ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(7));
			Integer approvalHeight = resultSet.getInt(8);
			if (approvalHeight == 0 && resultSet.wasNull())
				approvalHeight = null;

			Integer nonce = getNullableInteger(resultSet, 9);

			BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, creatorPublicKey, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);
			return this.fromBase(type, baseTransactionData);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction from repository", e);
		}
	}

	@Override
	public List<TransactionData> fromSignatures(List<byte[]> signatures) throws DataException {
		StringBuffer sql = new StringBuffer();

		sql.append("SELECT type, creator, created_when, fee, tx_group_id, block_height, approval_status, approval_height, nonce, signature ");
		sql.append("FROM Transactions WHERE signature IN (");
		sql.append(String.join(", ", Collections.nCopies(signatures.size(), "?")));
		sql.append(")");

		List<TransactionData> list;
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), (Object[]) signatures.toArray(new byte[0][]))) {
			if (resultSet == null) {
				return new ArrayList<>(0);
			}

			list = new ArrayList<>(signatures.size());

			do {
				TransactionType type = TransactionType.valueOf(resultSet.getInt(1));

				byte[] creatorPublicKey = resultSet.getBytes(2);
				long timestamp = resultSet.getLong(3);

				Long fee = resultSet.getLong(4);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(5);

				Integer blockHeight = resultSet.getInt(6);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(7));
				Integer approvalHeight = resultSet.getInt(8);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				Integer nonce = getNullableInteger(resultSet, 9);

				byte[] signature = resultSet.getBytes(10);

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, creatorPublicKey, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);

				TransactionData data = fromBase(type, baseTransactionData);
				if (data != null)
					list.add(data);
			} while( resultSet.next());

			return list;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transactions from repository", e);
		}
	}

	@Override
	public TransactionData fromHeightAndSequence(int height, int sequence) throws DataException {
		String sql = "SELECT signature FROM Transactions WHERE block_height = ? AND block_sequence = ?";
		
		try (ResultSet resultSet = this.repository.checkedExecute(sql, height, sequence)) {
			if (resultSet == null)
				return null;

			byte[] signature = resultSet.getBytes(1);

			return this.fromSignature(signature);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction height and sequence from repository", e);
		}
	}

	private TransactionData fromBase(TransactionType type, BaseTransactionData baseTransactionData) throws DataException {
		HSQLDBTransactionRepository txRepository = repositoryByTxType[type.value];

		if (txRepository == null)
			throw new DataException("Unsupported transaction type [" + type.name() + "] during fetch from HSQLDB repository");

		try {
			return (TransactionData) subclassInfos[type.value].fromBaseMethod.invoke(txRepository, baseTransactionData);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof DataException)
				throw (DataException) e.getCause();

			throw new DataException("Unsupported transaction type [" + type.name() + "] during fetch from HSQLDB repository");
		} catch (IllegalArgumentException | IllegalAccessException e) {
			throw new DataException("Unsupported transaction type [" + type.name() + "] during fetch from HSQLDB repository");
		}
	}

	/**
	 * Returns payments associated with a transaction's signature.
	 * <p>
	 * Used by various transaction types, like Payment, MultiPayment, ArbitraryTransaction.
	 * 
	 * @param signature
	 * @return list of payments, empty if none found
	 * @throws DataException
	 */
	protected List<PaymentData> getPaymentsFromSignature(byte[] signature) throws DataException {
		String sql = "SELECT recipient, amount, asset_id FROM SharedTransactionPayments WHERE signature = ? ORDER BY payment_index";

		List<PaymentData> payments = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return payments;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				String recipient = resultSet.getString(1);
				long amount = resultSet.getLong(2);
				long assetId = resultSet.getLong(3);

				payments.add(new PaymentData(recipient, assetId, amount));
			} while (resultSet.next());

			return payments;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch payments from repository", e);
		}
	}

	protected void savePayments(byte[] signature, List<PaymentData> payments) throws DataException {
		for (int index = 0; index < payments.size(); ++index) {
			PaymentData paymentData = payments.get(index);
			HSQLDBSaver saver = new HSQLDBSaver("SharedTransactionPayments");

			saver.bind("signature", signature).bind("recipient", paymentData.getRecipient())
				.bind("amount", paymentData.getAmount()).bind("asset_id", paymentData.getAssetId())
				.bind("payment_index", index);

			try {
				saver.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save payment into repository", e);
			}
		}
	}

	@Override
	public int getHeightFromSignature(byte[] signature) throws DataException {
		if (signature == null)
			return 0;

		String sql = "SELECT block_height from Transactions WHERE signature = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return 0;

			Integer blockHeight = resultSet.getInt(1);
			if (blockHeight == 0 && resultSet.wasNull())
				return 0;

			return blockHeight;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction's height from repository", e);
		}
	}

	@Override
	public boolean exists(byte[] signature) throws DataException {
		try {
			return this.repository.exists("Transactions", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to check for transaction in repository", e);
		}
	}

	// Transaction participants

	@Override
	public List<byte[]> getSignaturesInvolvingAddress(String address) throws DataException {
		String sql = "SELECT signature FROM TransactionParticipants WHERE participant = ?";

		List<byte[]> signatures = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch involved transaction signatures from repository", e);
		}
	}

	@Override
	public void saveParticipants(TransactionData transactionData, List<String> participants) throws DataException {
		byte[] signature = transactionData.getSignature();

		try {
			for (String participant : participants) {
				HSQLDBSaver saver = new HSQLDBSaver("TransactionParticipants");

				saver.bind("signature", signature).bind("participant", participant);

				saver.execute(this.repository);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to save transaction participant into repository", e);
		}
	}

	@Override
	public void deleteParticipants(TransactionData transactionData) throws DataException {
		try {
			this.repository.delete("TransactionParticipants", "signature = ?", transactionData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete transaction participants from repository", e);
		}
	}

	// Searching transactions

	@Override
	public Map<TransactionType, Integer> getTransactionSummary(int startHeight, int endHeight) throws DataException {
		String sql = "SELECT type, COUNT(signature) FROM Transactions "
			+ "WHERE block_height BETWEEN ? AND ? "
			+ "GROUP BY type";

		Map<TransactionType, Integer> transactionCounts = new EnumMap<>(TransactionType.class);

		try (ResultSet resultSet = this.repository.checkedExecute(sql, startHeight, endHeight)) {
			if (resultSet == null)
				return transactionCounts;

			do {
				int type = resultSet.getInt(1);
				int count = resultSet.getInt(2);

				transactionCounts.put(TransactionType.valueOf(type), count);
			} while (resultSet.next());

			return transactionCounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction counts from repository", e);
		}
	}

	@Override
	public List<byte[]> getSignaturesMatchingCriteria(Integer startBlock, Integer blockLimit, Integer txGroupId,
													  List<TransactionType> txTypes, Service service, String name, String address,
													  ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse) throws DataException {
		List<byte[]> signatures = new ArrayList<>();

		boolean hasAddress = address != null && !address.isEmpty();
		boolean hasTxTypes = txTypes != null && !txTypes.isEmpty();
		boolean hasHeightRange = startBlock != null || blockLimit != null;

		if (hasHeightRange && startBlock == null)
			startBlock = (reverse == null || !reverse) ? 1 : this.repository.getBlockRepository().getBlockchainHeight() - blockLimit;

		String signatureColumn = "Transactions.signature";
		List<String> whereClauses = new ArrayList<>();
		String groupBy = null;
		List<Object> bindParams = new ArrayList<>();

		// Tables, starting with Transactions
		StringBuilder tables = new StringBuilder(256);
		tables.append("Transactions");

		if (hasAddress) {
			tables.append(" JOIN TransactionParticipants ON TransactionParticipants.signature = Transactions.signature");
			groupBy = " GROUP BY TransactionParticipants.signature, Transactions.created_when";
			signatureColumn = "TransactionParticipants.signature";
		}

		if (service != null || name != null) {
			// These are for ARBITRARY transactions
			tables.append(" LEFT OUTER JOIN ArbitraryTransactions ON ArbitraryTransactions.signature = Transactions.signature");
		}

		// WHERE clauses next

		// Confirmation status
		switch (confirmationStatus) {
			case BOTH:
				break;

			case CONFIRMED:
				whereClauses.add("Transactions.block_height IS NOT NULL");
				break;

			case UNCONFIRMED:
				whereClauses.add("Transactions.block_height IS NULL");
				break;
		}

		// Height range
		if (hasHeightRange) {
			whereClauses.add("Transactions.block_height >= " + startBlock);

			if (blockLimit != null)
				whereClauses.add("Transactions.block_height < " + (startBlock + blockLimit));
		}

		if (txGroupId != null) {
			whereClauses.add("Transactions.tx_group_id = ?");
			bindParams.add(txGroupId);
		}

		if (hasTxTypes) {
			StringBuilder txTypesIn = new StringBuilder(256);
			txTypesIn.append("Transactions.type IN (");

			// ints are safe enough to use literally
			final int txTypesSize = txTypes.size();
			for (int tti = 0; tti < txTypesSize; ++tti) {
				if (tti != 0)
					txTypesIn.append(", ");

				txTypesIn.append(txTypes.get(tti).value);
			}

			txTypesIn.append(")");

			whereClauses.add(txTypesIn.toString());
		}

		if (service != null) {
			whereClauses.add("ArbitraryTransactions.service = ?");
			bindParams.add(service.value);
		}

		if (name != null) {
			whereClauses.add("lower(ArbitraryTransactions.name) = ?");
			bindParams.add(name.toLowerCase());
		}

		if (hasAddress) {
			whereClauses.add("TransactionParticipants.participant = ?");
			bindParams.add(address);
		}

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT ");
		sql.append(signatureColumn);
		sql.append(" FROM ");
		sql.append(tables);

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		if (groupBy != null)
			sql.append(groupBy);

		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		LOGGER.trace(() -> String.format("Transaction search SQL: %s", sql));

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching transaction signatures from repository", e);
		}
	}

	public List<byte[]> getSignaturesMatchingCriteria(TransactionType txType, byte[] publicKey,
			ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse) throws DataException {
		List<byte[]> signatures = new ArrayList<>();

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT signature FROM Transactions ");

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		if (txType != null) {
			whereClauses.add("type = ?");
			bindParams.add(txType.value);
		}

		if (publicKey != null) {
			whereClauses.add("creator = ?");
			bindParams.add(publicKey);
		}

		switch (confirmationStatus) {
			case BOTH:
				break;

			case CONFIRMED:
				whereClauses.add("Transactions.block_height IS NOT NULL");
				break;

			case UNCONFIRMED:
				whereClauses.add("Transactions.block_height IS NULL");
				break;
		}

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		LOGGER.trace(() -> String.format("Transaction search SQL: %s", sql));

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching transaction signatures from repository", e);
		}
	}

	@Override
	public List<byte[]> getSignaturesMatchingCriteria(TransactionType txType, byte[] publicKey,
			Integer minBlockHeight, Integer maxBlockHeight) throws DataException {
		List<byte[]> signatures = new ArrayList<>();

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT signature FROM Transactions ");

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		if (txType != null) {
			whereClauses.add("type = ?");
			bindParams.add(txType.value);
		}

		if (publicKey != null) {
			whereClauses.add("creator = ?");
			bindParams.add(publicKey);
		}

		if (minBlockHeight != null) {
			whereClauses.add("Transactions.block_height >= ?");
			bindParams.add(minBlockHeight);
		}

		if (maxBlockHeight != null) {
			whereClauses.add("Transactions.block_height <= ?");
			bindParams.add(maxBlockHeight);
		}

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		sql.append(" ORDER BY Transactions.created_when");

		LOGGER.trace(() -> String.format("Transaction search SQL: %s", sql));

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching transaction signatures from repository", e);
		}
	}


	public List<byte[]> getSignaturesMatchingCustomCriteria(TransactionType txType, List<String> whereClauses,
															List<Object> bindParams) throws DataException {
		List<byte[]> signatures = new ArrayList<>();

		String txTypeClassName = "";
		if (txType != null) {
			txTypeClassName = txType.className;
		}

		StringBuilder sql = new StringBuilder(1024);
		sql.append(String.format("SELECT signature FROM %sTransactions", txTypeClassName));

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		LOGGER.trace(() -> String.format("Transaction search SQL: %s", sql));

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching transaction signatures from repository", e);
		}
	}

	public List<byte[]> getSignaturesMatchingCustomCriteria(TransactionType txType, List<String> whereClauses,
															List<Object> bindParams, Integer limit) throws DataException {
		List<byte[]> signatures = new ArrayList<>();

		String txTypeClassName = "";
		if (txType != null) {
			txTypeClassName = txType.className;
		}

		StringBuilder sql = new StringBuilder(1024);
		sql.append(String.format("SELECT signature FROM %sTransactions", txTypeClassName));

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		if (limit != null) {
			sql.append(" LIMIT ?");
			bindParams.add(limit);
		}

		LOGGER.trace(() -> String.format("Transaction search SQL: %s", sql));

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching transaction signatures from repository", e);
		}
	}

	@Override
	public byte[] getLatestAutoUpdateTransaction(TransactionType txType, List<Integer> txGroupIds, Integer service) throws DataException {
		if (txGroupIds == null || txGroupIds.isEmpty())
			return null;

		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();
		sql.append("SELECT Transactions.signature FROM Transactions");

		if (service != null) {
			// This is for ARBITRARY transactions
			sql.append(" LEFT OUTER JOIN ArbitraryTransactions ON ArbitraryTransactions.signature = Transactions.signature");
		}

		sql.append(" WHERE type = ");
		// Enum int value safe to use literally
		sql.append(txType.value);

		sql.append(" AND tx_group_id IN (");
		sql.append(String.join(", ", Collections.nCopies(txGroupIds.size(), "?")));
		sql.append(")");
		bindParams.addAll(txGroupIds);

		if (service != null) {
			// This is for ARBITRARY transactions
			sql.append(" AND service = ?");
			bindParams.add(service);
		}

		// "approvalHeight > blockHeight" filters out 'auto-approved' transactions, i.e. those by group admins/owner
		sql.append(" AND block_height IS NOT NULL AND approval_height IS NOT NULL AND approval_height > block_height");

		// we want approved, not rejected!
		sql.append(" AND approval_status = ");
		// Enum int value safe to use literally
		sql.append(ApprovalStatus.APPROVED.value);

		sql.append(" ORDER BY created_when DESC LIMIT 1");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return null;

			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest auto-update transaction signature from repository", e);
		}
	}

	@Override
	public List<TransactionData> getTransactionsInvolvingName(String name, ConfirmationStatus confirmationStatus) throws DataException {
		TransactionType[] transactionTypes = new TransactionType[] {
				REGISTER_NAME, UPDATE_NAME, BUY_NAME, SELL_NAME
		}; // TODO: CancelSellNameTransaction?

		String reducedName = Unicode.sanitize(name);

		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();
		sql.append("SELECT Transactions.signature FROM Transactions");

		for (int ti = 0; ti < transactionTypes.length; ++ti) {
			sql.append(" LEFT OUTER JOIN ");
			sql.append(transactionTypes[ti].className);
			sql.append("Transactions USING (signature)");
		}

		sql.append(" WHERE Transactions.type IN (");
		for (int ti = 0; ti < transactionTypes.length; ++ti) {
			if (ti != 0)
				sql.append(", ");

			sql.append(transactionTypes[ti].value);
		}
		sql.append(")");

		// Confirmation status
		switch (confirmationStatus) {
			case BOTH:
				break;

			case CONFIRMED:
				sql.append(" AND Transactions.block_height IS NOT NULL");
				break;

			case UNCONFIRMED:
				sql.append(" AND Transactions.block_height IS NULL");
				break;
		}

		sql.append(" AND (RegisterNameTransactions.name = ?");
		bindParams.add(name);
		sql.append(" OR RegisterNameTransactions.reduced_name = ?");
		bindParams.add(reducedName);
		sql.append(" OR UpdateNameTransactions.name = ?");
		bindParams.add(name);
		sql.append(" OR (UpdateNameTransactions.reduced_new_name != '' AND UpdateNameTransactions.reduced_new_name = ?)");
		bindParams.add(reducedName);
		sql.append(" OR UpdateNameTransactions.new_name = ?");
		bindParams.add(name);
		sql.append(" OR SellNameTransactions.name = ?");
		bindParams.add(name);
		sql.append(" OR BuyNameTransactions.name = ?");
		bindParams.add(name);

		sql.append(") GROUP BY Transactions.signature, Transactions.created_when ORDER BY Transactions.created_when");

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping name-related transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch name-related transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getAssetTransactions(long assetId, ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		TransactionType[] transactionTypes = new TransactionType[] {
			ISSUE_ASSET, TRANSFER_ASSET, CREATE_ASSET_ORDER, CANCEL_ASSET_ORDER
		};

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT Transactions.signature FROM Transactions");

		for (int ti = 0; ti < transactionTypes.length; ++ti) {
			sql.append(" LEFT OUTER JOIN ");
			sql.append(transactionTypes[ti].className);
			sql.append("Transactions USING (signature)");
		}

		// assetID isn't in Cancel Asset Order so we need to join to the order
		sql.append(" LEFT OUTER JOIN AssetOrders ON AssetOrders.asset_order_id = CancelAssetOrderTransactions.asset_order_id");

		sql.append(" WHERE Transactions.type IN (");
		for (int ti = 0; ti < transactionTypes.length; ++ti) {
			if (ti != 0)
				sql.append(", ");

			sql.append(transactionTypes[ti].value);
		}
		sql.append(")");

		// Confirmation status
		switch (confirmationStatus) {
			case BOTH:
				break;

			case CONFIRMED:
				sql.append(" AND Transactions.block_height IS NOT NULL");
				break;

			case UNCONFIRMED:
				sql.append(" AND Transactions.block_height IS NULL");
				break;
		}

		sql.append(" AND (IssueAssetTransactions.asset_id = ");
		sql.append(assetId);
		sql.append(" OR TransferAssetTransactions.asset_id = ");
		sql.append(assetId);
		sql.append(" OR CreateAssetOrderTransactions.have_asset_id = ");
		sql.append(assetId);
		sql.append(" OR CreateAssetOrderTransactions.want_asset_id = ");
		sql.append(assetId);
		sql.append(" OR AssetOrders.have_asset_id = ");
		sql.append(assetId);
		sql.append(" OR AssetOrders.want_asset_id = ");
		sql.append(assetId);

		sql.append(") GROUP BY Transactions.signature, Transactions.created_when ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping asset-related transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch asset-related transactions from repository", e);
		}
	}

	@Override
	public List<TransferAssetTransactionData> getAssetTransfers(long assetId, String address, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		List<Object> bindParams = new ArrayList<>(3);

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT created_when, tx_group_id, fee, signature, sender, block_height, approval_status, approval_height, nonce, recipient, amount, asset_name "
				+ "FROM TransferAssetTransactions JOIN Transactions USING (signature) ");

		if (address != null)
			sql.append("JOIN Accounts ON public_key = sender ");

		sql.append("JOIN Assets USING (asset_id) WHERE asset_id = ?");
		bindParams.add(assetId);

		if (address != null) {
			sql.append(" AND ? IN (account, recipient) ");
			bindParams.add(address);
		}

		sql.append(" ORDER by created_when ");
		sql.append((reverse == null || !reverse) ? "ASC" : "DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<TransferAssetTransactionData> assetTransfers = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return assetTransfers;

			do {
				long timestamp = resultSet.getLong(1);
				int txGroupId = resultSet.getInt(2);
				long fee = resultSet.getLong(3);
				byte[] signature = resultSet.getBytes(4);
				byte[] creatorPublicKey = resultSet.getBytes(5);

				Integer blockHeight = resultSet.getInt(6);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(7));

				Integer approvalHeight = resultSet.getInt(8);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				Integer nonce = getNullableInteger(resultSet, 9);

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, creatorPublicKey, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);

				String recipient = resultSet.getString(10);
				long amount = resultSet.getLong(11);
				String assetName = resultSet.getString(12);

				assetTransfers.add(new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId, assetName));
			} while (resultSet.next());

			return assetTransfers;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch asset-transfer transactions from repository", e);
		}
	}

	@Override
	public List<GroupKickSummaryData> getGroupKicks(String memberAddress, Integer groupId,
			Long before, Long after, Integer limit, Integer offset, Boolean reverse) throws DataException {
		List<Object> bindParams = new ArrayList<>(6);
		StringBuilder sql = new StringBuilder(320);
		sql.append("SELECT gk.address, gk.group_id, gk.reason, t.created_when FROM GroupKickTransactions gk ");
		sql.append("INNER JOIN Transactions t ON gk.signature = t.signature ");
		sql.append("WHERE t.block_height IS NOT NULL ");
		if (memberAddress != null) {
			sql.append("AND gk.address = ? ");
			bindParams.add(memberAddress);
		}
		if (groupId != null) {
			sql.append("AND gk.group_id = ? ");
			bindParams.add(groupId);
		}
		if (before != null) {
			sql.append("AND t.created_when < ? ");
			bindParams.add(before);
		}
		if (after != null) {
			sql.append("AND t.created_when > ? ");
			bindParams.add(after);
		}
		sql.append("ORDER BY t.created_when ");
		sql.append((reverse != null && reverse) ? "DESC" : "ASC");
		sql.append(", gk.signature ");
		sql.append((reverse != null && reverse) ? "DESC" : "ASC");
		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<GroupKickSummaryData> results = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return results;
			do {
				String member = resultSet.getString(1);
				int gid = resultSet.getInt(2);
				String reason = resultSet.getString(3);
				long timestamp = resultSet.getLong(4);
				results.add(new GroupKickSummaryData(member, gid, reason, timestamp));
			} while (resultSet.next());
			return results;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group kicks from repository", e);
		}
	}

	public List<String> getConfirmedRewardShareCreatorsExcludingSelfShares() throws DataException {
		List<String> rewardShareCreators = new ArrayList<>();

		String sql = "SELECT account "
				+ "FROM RewardShareTransactions "
				+ "JOIN Accounts ON Accounts.public_key = RewardShareTransactions.minter_public_key "
				+ "JOIN Transactions ON Transactions.signature = RewardShareTransactions.signature "
				+ "WHERE block_height IS NOT NULL AND RewardShareTransactions.recipient != Accounts.account "
				+ "GROUP BY account "
				+ "ORDER BY account";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return rewardShareCreators;

			do {
				String address = resultSet.getString(1);

				rewardShareCreators.add(address);
			} while (resultSet.next());

			return rewardShareCreators;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward share creators from repository", e);
		}
	}

	public List<String> getConfirmedTransferAssetCreators() throws DataException {
		List<String> transferAssetCreators = new ArrayList<>();

		String sql = "SELECT account "
				+ "FROM TransferAssetTransactions "
				+ "JOIN Accounts ON Accounts.public_key = TransferAssetTransactions.sender "
				+ "JOIN Transactions ON Transactions.signature = TransferAssetTransactions.signature "
				+ "WHERE block_height IS NOT NULL AND TransferAssetTransactions.recipient != Accounts.account "
				+ "GROUP BY account "
				+ "ORDER BY account";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return transferAssetCreators;

			do {
				String address = resultSet.getString(1);

				transferAssetCreators.add(address);
			} while (resultSet.next());

			return transferAssetCreators;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transfer asset from repository", e);
		}
	}

	@Override
	public List<ChainParameterUpdateTransactionData> getChainParameterUpdates(Integer parameterId, ApprovalStatus approvalStatus,
			Integer txGroupId, Integer activationHeightFrom, Integer activationHeightTo, ConfirmationStatus confirmationStatus,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		List<ChainParameterUpdateTransactionData> transactions = new ArrayList<>();

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT Transactions.signature FROM Transactions ");
		sql.append("JOIN ChainParameterUpdateTransactions ON ChainParameterUpdateTransactions.signature = Transactions.signature ");

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		whereClauses.add("Transactions.type = ?");
		bindParams.add(CHAIN_PARAMETER_UPDATE.value);

		if (parameterId != null) {
			whereClauses.add("ChainParameterUpdateTransactions.parameter_id = ?");
			bindParams.add(parameterId);
		}

		if (approvalStatus != null) {
			whereClauses.add("Transactions.approval_status = ?");
			bindParams.add(approvalStatus.value);
		}

		if (txGroupId != null) {
			whereClauses.add("Transactions.tx_group_id = ?");
			bindParams.add(txGroupId);
		}

		if (activationHeightFrom != null) {
			whereClauses.add("ChainParameterUpdateTransactions.activation_height >= ?");
			bindParams.add(activationHeightFrom);
		}

		if (activationHeightTo != null) {
			whereClauses.add("ChainParameterUpdateTransactions.activation_height <= ?");
			bindParams.add(activationHeightTo);
		}

		if (confirmationStatus != null) {
			switch (confirmationStatus) {
				case BOTH:
					break;

				case CONFIRMED:
					whereClauses.add("Transactions.block_height IS NOT NULL");
					break;

				case UNCONFIRMED:
					whereClauses.add("Transactions.block_height IS NULL");
					break;
			}
		}

		sql.append("WHERE ");
		final int whereClausesSize = whereClauses.size();
		for (int wci = 0; wci < whereClausesSize; ++wci) {
			if (wci != 0)
				sql.append(" AND ");

			sql.append(whereClauses.get(wci));
		}

		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");
		sql.append(", Transactions.signature");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);
				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null)
					throw new DataException("Unable to fetch chain-parameter update transaction from repository?");

				transactions.add((ChainParameterUpdateTransactionData) transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | ClassCastException e) {
			throw new DataException("Unable to fetch chain-parameter update transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getApprovalPendingTransactions(Integer txGroupId, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT signature FROM Transactions WHERE Transactions.approval_status = ");
		// Enum int value safe to use literally
		sql.append(ApprovalStatus.PENDING.value);

		Object[] bindParams;

		if (txGroupId != null) {
			sql.append(" AND Transactions.tx_group_id = ?");
			bindParams = new Object[] { txGroupId };
		} else {
			bindParams = new Object[0];
		}

		sql.append(" ORDER BY created_when");
		if (reverse != null && reverse)
			sql.append(" DESC");

		sql.append(", signature");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<TransactionData> transactions = new ArrayList<>();

		// Find transactions with no corresponding row in BlockTransactions
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams)) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null)
					// Something inconsistent with the repository
					throw new DataException("Unable to fetch approval-pending transaction from repository?");

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch approval-pending transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getApprovalPendingTransactions(int blockHeight) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT signature FROM Transactions "
			+ "JOIN Groups on Groups.group_id = Transactions.tx_group_id "
			+ "WHERE Transactions.approval_status = ");
		// Enum int value safe to use literally
		sql.append(ApprovalStatus.PENDING.value);

		sql.append(" AND Transactions.block_height < ? - Groups.min_block_delay");
		sql.append(" ORDER BY Transactions.block_height, Transactions.created_when, Transactions.signature");

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), blockHeight)) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null)
					// Something inconsistent with the repository
					throw new DataException("Unable to fetch approval-expiring transaction from repository?");

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch approval-expiring transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getApprovalExpiringTransactions(int blockHeight) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT signature FROM Transactions "
			+ "JOIN Groups on Groups.group_id = Transactions.tx_group_id "
			+ "WHERE Transactions.approval_status = ");
		// Enum int value safe to use literally
		sql.append(ApprovalStatus.PENDING.value);

		sql.append(" AND Transactions.block_height < ? - Groups.max_block_delay");

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), blockHeight)) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null)
					// Something inconsistent with the repository
					throw new DataException("Unable to fetch approval-expiring transaction from repository?");

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch approval-expiring transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getApprovalTransactionDecidedAtHeight(int approvalHeight) throws DataException {
		String sql = "SELECT signature from Transactions WHERE approval_height = ?";

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, approvalHeight)) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null)
					// Something inconsistent with the repository
					throw new DataException("Unable to fetch approval-decided transaction from repository?");

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch approval-decided transactions from repository", e);
		}
	}

	@Override
	public GroupApprovalTransactionData getLatestApproval(byte[] pendingSignature, byte[] adminPublicKey) throws DataException {
		String sql = "SELECT signature FROM GroupApprovalTransactions "
			+ "NATURAL JOIN Transactions "
			+ "WHERE pending_signature = ? AND admin = ? AND block_height IS NOT NULL "
			+ "ORDER BY created_when DESC, signature DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pendingSignature, adminPublicKey)) {
			if (resultSet == null)
				return null;

			byte[] signature = resultSet.getBytes(1);

			return (GroupApprovalTransactionData) this.fromSignature(signature);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest transaction group-admin approval from repository", e);
		}
	}

	@Override
	public GroupApprovalData getApprovalData(byte[] pendingSignature, int approvalHeight) throws DataException {
		// Fetch latest approval data for pending transaction's signature
		// NOT simply number of GROUP_APPROVAL transactions as some may record opposition, or changed opinions
		// Also make sure that GROUP_APPROVAL transaction's signer still has approval authority for the group

		// Sub-query SQL to find latest GroupApprovalTransaction relating to passed pending signature
		String latestApprovalSql = "SELECT pending_signature, admin, approval, created_when, signature FROM GroupApprovalTransactions "
				+ "NATURAL JOIN Transactions WHERE pending_signature = ? AND block_height IS NOT NULL";

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT GAT.admin, GAT.approval, PendingTransactions.tx_group_id, PendingTransactions.type FROM (");
		sql.append(latestApprovalSql);
		sql.append(") AS GAT LEFT OUTER JOIN (");
		sql.append(latestApprovalSql);
		sql.append(") AS NewerGAT ON NewerGAT.admin = GAT.admin AND (NewerGAT.created_when > GAT.created_when OR (NewerGAT.created_when = GAT.created_when AND NewerGat.signature > GAT.signature)) "
				+ "JOIN Transactions AS PendingTransactions ON PendingTransactions.signature = GAT.pending_signature "
				+ "WHERE NewerGAT.admin IS NULL");

		GroupApprovalData groupApprovalData = new GroupApprovalData();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), pendingSignature, pendingSignature)) {
			if (resultSet == null)
				return groupApprovalData;

			do {
				byte[] adminPublicKey = resultSet.getBytes(1);
				boolean approval = resultSet.getBoolean(2);
				int txGroupId = resultSet.getInt(3);
				TransactionType pendingTransactionType = TransactionType.valueOf(resultSet.getInt(4));

				String adminAddress = Crypto.toAddress(adminPublicKey);
				if (!Group.canApprove(this.repository, txGroupId, adminAddress, pendingTransactionType, approvalHeight))
					continue;

				if (approval)
					groupApprovalData.approvingAdmins.add(adminPublicKey);
				else
					groupApprovalData.rejectingAdmins.add(adminPublicKey);
			} while (resultSet.next());

			return groupApprovalData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest transaction group-approval decisions from repository", e);
		}
	}

	@Override
	public boolean isConfirmed(byte[] signature) throws DataException {
		try {
			return this.repository.exists("BlockTransactions", "transaction_signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to check whether transaction is confirmed in repository", e);
		}
	}

	@Override
	public List<byte[]> getUnconfirmedTransactionSignatures() throws DataException {
		String sql = "SELECT signature FROM UnconfirmedTransactions ORDER by created_when DESC, signature DESC";

		List<byte[]> signatures = new ArrayList<>();

		// Find transactions with no corresponding row in BlockTransactions
		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return signatures;

			do {
				byte[] signature = resultSet.getBytes(1);

				signatures.add(signature);
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch unconfirmed transaction signatures from repository", e);
		}
	}

	@Override
	public List<TransactionData> getUnconfirmedTransactions(List<TransactionType> txTypes, byte[] creatorPublicKey,
															Integer limit, Integer offset, Boolean reverse) throws DataException {
		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		boolean hasTxTypes = txTypes != null && !txTypes.isEmpty();

		if (creatorPublicKey != null) {
			whereClauses.add("t.creator = ?");
			bindParams.add(creatorPublicKey);
		}

		StringBuilder sql = new StringBuilder(256);
		sql.append("SELECT t.signature, t.type, t.creator, t.created_when, t.fee, t.tx_group_id, t.block_height, t.approval_status, t.approval_height, t.nonce "
				+ "FROM UnconfirmedTransactions u JOIN Transactions t USING (signature)");

		if (hasTxTypes) {
			StringBuilder txTypesIn = new StringBuilder(256);
			txTypesIn.append("t.type IN (");

			// ints are safe enough to use literally
			final int txTypesSize = txTypes.size();
			for (int tti = 0; tti < txTypesSize; ++tti) {
				if (tti != 0)
					txTypesIn.append(", ");

				txTypesIn.append(txTypes.get(tti).value);
			}

			txTypesIn.append(")");

			whereClauses.add(txTypesIn.toString());
		}

		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		sql.append(" ORDER BY t.created_when");
		if (reverse != null && reverse)
			sql.append(" DESC");

		sql.append(", t.signature");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);
				TransactionType type = TransactionType.valueOf(resultSet.getInt(2));
				byte[] creatorPublicKey2 = resultSet.getBytes(3);
				long timestamp = resultSet.getLong(4);

				Long fee = resultSet.getLong(5);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(6);

				Integer blockHeight = resultSet.getInt(7);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(8));
				Integer approvalHeight = resultSet.getInt(9);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				Integer nonce = getNullableInteger(resultSet, 10);

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId,
						creatorPublicKey2, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);

				TransactionData transactionData = this.fromBase(type, baseTransactionData);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping unconfirmed transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch unconfirmed transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getUnconfirmedTransactions(TransactionType txType, byte[] creatorPublicKey) throws DataException {
		if (txType == null && creatorPublicKey == null)
			throw new IllegalArgumentException("At least one of txType or creatorPublicKey must be non-null");

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT t.signature, t.type, t.creator, t.created_when, t.fee, t.tx_group_id, t.block_height, t.approval_status, t.approval_height, t.nonce ");
		sql.append("FROM UnconfirmedTransactions u JOIN Transactions t USING (signature) ");
		sql.append("WHERE ");

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		if (txType != null) {
			whereClauses.add("t.type = ?");
			bindParams.add(Integer.valueOf(txType.value));
		}

		if (creatorPublicKey != null) {
			whereClauses.add("t.creator = ?");
			bindParams.add(creatorPublicKey);
		}

		final int whereClausesSize = whereClauses.size();
		for (int wci = 0; wci < whereClausesSize; ++wci) {
			if (wci != 0)
				sql.append(" AND ");

			sql.append(whereClauses.get(wci));
		}

		sql.append("ORDER BY t.created_when, t.signature");

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);
				TransactionType type = TransactionType.valueOf(resultSet.getInt(2));
				byte[] creatorPublicKey2 = resultSet.getBytes(3);
				long timestamp = resultSet.getLong(4);

				Long fee = resultSet.getLong(5);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(6);

				Integer blockHeight = resultSet.getInt(7);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(8));
				Integer approvalHeight = resultSet.getInt(9);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				Integer nonce = getNullableInteger(resultSet, 10);

				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId,
						creatorPublicKey2, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);

				TransactionData transactionData = this.fromBase(type, baseTransactionData);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping unconfirmed transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch unconfirmed transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getUnconfirmedTransactions(EnumSet<TransactionType> excludedTxTypes, Integer limit) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT signature FROM UnconfirmedTransactions ");
		sql.append("JOIN Transactions USING (signature) ");
		sql.append("WHERE type NOT IN (");

		boolean firstTxType = true;
		for (TransactionType txType : excludedTxTypes) {
			if (firstTxType)
				firstTxType = false;
			else
				sql.append(", ");

			sql.append(txType.value);
		}

		sql.append(")");
		sql.append("ORDER BY created_when, signature ");

		if (limit != null) {
			sql.append("LIMIT ?");
			bindParams.add(limit);
		}

		List<TransactionData> transactions = new ArrayList<>();

		// Find transactions with no corresponding row in BlockTransactions
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping unconfirmed transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch unconfirmed transactions from repository", e);
		}
	}

	@Override
	public List<TransactionData> getUnconfirmedTransactionsCreatedBefore(long timestamp) throws DataException {
		String sql = "SELECT t.signature, t.type, t.creator, t.created_when, t.fee, t.tx_group_id, t.block_height, t.approval_status, t.approval_height, t.nonce "
				+ "FROM UnconfirmedTransactions u JOIN Transactions t USING (signature) "
				+ "WHERE t.created_when < ? "
				+ "ORDER BY t.created_when, t.signature";

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, timestamp)) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);
				TransactionType type = TransactionType.valueOf(resultSet.getInt(2));
				byte[] creatorPublicKey = resultSet.getBytes(3);
				long ts = resultSet.getLong(4);

				Long fee = resultSet.getLong(5);
				if (fee == 0 && resultSet.wasNull())
					fee = null;

				int txGroupId = resultSet.getInt(6);

				Integer blockHeight = resultSet.getInt(7);
				if (blockHeight == 0 && resultSet.wasNull())
					blockHeight = null;

				ApprovalStatus approvalStatus = ApprovalStatus.valueOf(resultSet.getInt(8));
				Integer approvalHeight = resultSet.getInt(9);
				if (approvalHeight == 0 && resultSet.wasNull())
					approvalHeight = null;

				Integer nonce = getNullableInteger(resultSet, 10);

				BaseTransactionData baseTransactionData = new BaseTransactionData(ts, txGroupId,
						creatorPublicKey, fee, nonce, approvalStatus, blockHeight, approvalHeight, signature);
				TransactionData transactionData = this.fromBase(type, baseTransactionData);

				if (transactionData != null)
					transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch unconfirmed transactions created before timestamp from repository", e);
		}
	}

	@Override
	public List<TransactionData> getPaymentsBetweenAddresses(String recipientAddress, String senderAddress,
			Long amount, Integer startBlock, Integer blockLimit, ConfirmationStatus confirmationStatus, 
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		// Validate that at least one address is provided
		boolean hasRecipient = recipientAddress != null && !recipientAddress.isEmpty();
		boolean hasSender = senderAddress != null && !senderAddress.isEmpty();
		
		if (!hasRecipient && !hasSender)
			throw new IllegalArgumentException("At least one of recipientAddress or senderAddress must be provided");

		// Step 1: Look up sender's public key from address if provided (fast - primary key lookup)
		byte[] senderPublicKey = null;
		if (hasSender) {
			AccountData senderAccount = this.repository.getAccountRepository().getAccount(senderAddress);
			if (senderAccount == null || senderAccount.getPublicKey() == null) {
				// Sender account doesn't exist yet or has no public key, return empty list
				return new ArrayList<>();
			}
			senderPublicKey = senderAccount.getPublicKey();
		}

		boolean hasHeightRange = startBlock != null || blockLimit != null;

		// Calculate startBlock if blockLimit is specified without startBlock
		if (hasHeightRange && startBlock == null)
			startBlock = (reverse == null || !reverse) ? 1 : this.repository.getBlockRepository().getBlockchainHeight() - blockLimit;

		// Step 2: Query payments - optimize table order based on filters
		StringBuilder sql = new StringBuilder(1024);
		
		// Optimize query by starting from the table with the most selective filter
		if (hasRecipient && !hasSender) {
			// When only filtering by recipient, start from PaymentTransactions to use recipient index first
			sql.append("SELECT Transactions.signature FROM PaymentTransactions ");
			sql.append("JOIN Transactions ON Transactions.signature = PaymentTransactions.signature ");
		} else {
			// When filtering by sender or both, start from Transactions to use creator index
			sql.append("SELECT Transactions.signature FROM Transactions ");
			sql.append("JOIN PaymentTransactions ON PaymentTransactions.signature = Transactions.signature ");
		}

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();

		// Filter by transaction type (PAYMENT) - part of TransactionCreatorIndex (creator, type)
		whereClauses.add("Transactions.type = ?");
		bindParams.add(Integer.valueOf(TransactionType.PAYMENT.value));

		// Filter by recipient address if provided (uses existing PaymentTransactionsRecipientIndex)
		if (hasRecipient) {
			whereClauses.add("PaymentTransactions.recipient = ?");
			bindParams.add(recipientAddress);
		}

		// Filter by creator/sender's public key if provided (uses existing TransactionCreatorIndex)
		if (hasSender && senderPublicKey != null) {
			whereClauses.add("Transactions.creator = ?");
			bindParams.add(senderPublicKey);
		}

		// Filter by amount if provided (optional)
		if (amount != null) {
			whereClauses.add("PaymentTransactions.amount = ?");
			bindParams.add(amount);
		}

		// Filter by confirmation status
		switch (confirmationStatus) {
			case BOTH:
				break;

			case CONFIRMED:
				whereClauses.add("Transactions.block_height IS NOT NULL");
				break;

			case UNCONFIRMED:
				whereClauses.add("Transactions.block_height IS NULL");
				break;
		}

		// Height range (only for CONFIRMED transactions)
		if (hasHeightRange) {
			if (confirmationStatus == ConfirmationStatus.UNCONFIRMED)
				throw new DataException("Cannot specify block height range for unconfirmed transactions");

			whereClauses.add("Transactions.block_height >= " + startBlock);

			if (blockLimit != null)
				whereClauses.add("Transactions.block_height < " + (startBlock + blockLimit));
		}

		// Build WHERE clause
		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			final int whereClausesSize = whereClauses.size();
			for (int wci = 0; wci < whereClausesSize; ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		// Order by timestamp
		sql.append(" ORDER BY Transactions.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		LOGGER.trace(() -> String.format("Payment between addresses SQL: %s", sql));

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return transactions;

			do {
				byte[] signature = resultSet.getBytes(1);

				TransactionData transactionData = this.fromSignature(signature);

				if (transactionData == null) {
					// Transaction was deleted concurrently (e.g., expired transaction cleanup)
					// This is not an error - just skip this transaction
					LOGGER.trace(() -> String.format("Skipping payment transaction %s - no longer in repository (likely deleted concurrently)", Base58.encode(signature)));
					continue;
				}

				transactions.add(transactionData);
			} while (resultSet.next());

			return transactions;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch payment transactions between addresses from repository", e);
		}
	}

	@Override
	public List<String[]> getReceivedPaymentsForNotifications(String recipient, Long after, int limit) throws DataException {
		// Single indexed query — no per-row hydration.
		// Uses PaymentTransactionsRecipientIndex on PT.recipient and
		// the primary key join to Transactions for created_when / creator.
		StringBuilder sql = new StringBuilder(256);
		sql.append("SELECT PT.recipient, PT.amount, T.creator, T.created_when, T.signature ");
		sql.append("FROM PaymentTransactions PT ");
		sql.append("JOIN Transactions T ON T.signature = PT.signature ");
		sql.append("WHERE PT.recipient = ? AND T.block_height IS NOT NULL");

		List<Object> params = new ArrayList<>();
		params.add(recipient);

		if (after != null) {
			sql.append(" AND T.created_when > ?");
			params.add(after);
		}

		sql.append(" ORDER BY T.created_when DESC");
		HSQLDBRepository.limitOffsetSql(sql, limit, null);

		List<String[]> results = new ArrayList<>();

		try (ResultSet rs = this.repository.checkedExecute(sql.toString(), params.toArray())) {
			if (rs == null) return results;
			do {
				String recipientAddr = rs.getString(1);
				long amount          = rs.getLong(2);
				byte[] creatorKey    = rs.getBytes(3);
				long timestamp       = rs.getLong(4);
				byte[] sig           = rs.getBytes(5);

				String sender      = org.qortium.crypto.Crypto.toAddress(creatorKey);
				String amountStr   = org.qortium.utils.Amounts.prettyAmount(amount);
				String tsStr       = String.valueOf(timestamp);
				String sigStr      = sig != null ? org.qortium.utils.Base58.encode(sig) : null;

				results.add(new String[]{sender, recipientAddr, amountStr, tsStr, sigStr});
			} while (rs.next());

			return results;
		} catch (java.sql.SQLException e) {
			throw new DataException("Unable to fetch payment notifications history", e);
		}
	}

	@Override
	public void confirmTransaction(byte[] signature) throws DataException {
		try {
			this.repository.delete("UnconfirmedTransactions", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to remove transaction from unconfirmed transactions repository", e);
		}
	}

	@Override
	public void updateBlockHeight(byte[] signature, Integer blockHeight) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("Transactions");

		saver.bind("signature", signature).bind("block_height", blockHeight);

		try {
			saver.execute(repository);
		} catch (SQLException e) {
			throw new DataException("Unable to update transaction's block height in repository", e);
		}
	}

	@Override
	public void updateBlockSequence(byte[] signature, Integer blockSequence) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("Transactions");

		saver.bind("signature", signature).bind("block_sequence", blockSequence);

		try {
			saver.execute(repository);
		} catch (SQLException e) {
			throw new DataException("Unable to update transaction's block sequence in repository", e);
		}
	}

	@Override
	public void updateApprovalHeight(byte[] signature, Integer approvalHeight) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("Transactions");

		saver.bind("signature", signature).bind("approval_height", approvalHeight);

		try {
			saver.execute(repository);
		} catch (SQLException e) {
			throw new DataException("Unable to update transaction's approval height in repository", e);
		}
	}

	@Override
	public void unconfirmTransaction(TransactionData transactionData) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("UnconfirmedTransactions");

		saver.bind("signature", transactionData.getSignature()).bind("created_when", transactionData.getTimestamp());

		try {
			saver.execute(repository);
		} catch (SQLException e) {
			throw new DataException("Unable to add transaction to unconfirmed transactions repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("Transactions");

		// Do not include "block_height" or "approval_height" as they are modified a different way

		saver.bind("signature", transactionData.getSignature()).bind("type", transactionData.getType().value)
			.bind("creator", transactionData.getCreatorPublicKey()).bind("created_when", transactionData.getTimestamp())
			.bind("fee", transactionData.getFee()).bind("tx_group_id", transactionData.getTxGroupId())
			.bind("approval_status", transactionData.getApprovalStatus().value)
			.bind("nonce", transactionData.getType().supportsMempowFeeAlternative() ? transactionData.getNonce() : null);

		try {
			saver.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save transaction into repository", e);
		}

		// Now call transaction-type-specific save() method
		TransactionType type = transactionData.getType();
		HSQLDBTransactionRepository txRepository = repositoryByTxType[type.value];
		if (txRepository == null)
			throw new DataException("Unsupported transaction type [" + type.name() + "] during save into HSQLDB repository");

		try {
			subclassInfos[type.value].saveMethod.invoke(txRepository, transactionData);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof DataException)
				throw (DataException) e.getCause();

			throw new DataException("Exception during save of transaction type [" + type.name() + "] into HSQLDB repository");
		} catch (IllegalAccessException | IllegalArgumentException e) {
			throw new DataException("Unsupported transaction type [" + type.name() + "] during save into HSQLDB repository");
		}
	}

	@Override
	public void delete(TransactionData transactionData) throws DataException {
		// NOTE: The corresponding row in sub-table is deleted automatically by the database thanks to "ON DELETE CASCADE" in the sub-table's FOREIGN KEY
		// definition.
		try {
			this.repository.delete("Transactions", "signature = ?", transactionData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete transaction from repository", e);
		}
		try {
			this.repository.delete("UnconfirmedTransactions", "signature = ?", transactionData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to remove transaction from unconfirmed transactions repository", e);
		}

		// If transaction subclass has a "delete" method - call that now
		TransactionType type = transactionData.getType();
		if (subclassInfos[type.value].deleteMethod != null) {
			HSQLDBTransactionRepository txRepository = repositoryByTxType[type.value];

			try {
				subclassInfos[type.value].deleteMethod.invoke(txRepository, transactionData);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof DataException)
					throw (DataException) e.getCause();

				throw new DataException("Exception during delete of transaction type [" + type.name() + "] from HSQLDB repository");
			} catch (IllegalAccessException | IllegalArgumentException e) {
				throw new DataException("Unsupported transaction type [" + type.name() + "] during delete from HSQLDB repository");
			}
		}
	}

}
