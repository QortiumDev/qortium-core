package org.qortium.repository.hsqldb;

import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.crosschain.TradeBotFillData;
import org.qortium.repository.CrossChainRepository;
import org.qortium.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HSQLDBCrossChainRepository implements CrossChainRepository {

	protected HSQLDBRepository repository;

	public HSQLDBCrossChainRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public TradeBotData getTradeBotData(byte[] tradePrivateKey) throws DataException {
		String sql = "SELECT acct_name, trade_state, trade_state_value, "
				+ "creator_address, at_address, "
				+ "updated_when, local_asset_id, local_amount, "
				+ "trade_local_public_key, trade_local_public_key_hash, "
				+ "trade_local_address, secret, hash_of_secret, "
				+ "foreign_blockchain, trade_foreign_public_key, trade_foreign_public_key_hash, "
				+ "foreign_amount, foreign_key, last_transaction_signature, locktime_a, fill_slot_index, receiving_account_info, "
				+ "offered_foreign_blockchain, offered_trade_foreign_public_key, offered_trade_foreign_public_key_hash, "
				+ "offered_foreign_amount, offered_foreign_key, "
				+ "requested_foreign_blockchain, requested_trade_foreign_public_key, requested_trade_foreign_public_key_hash, "
				+ "requested_foreign_amount, requested_foreign_key, locktime_b, "
				+ "offered_foreign_receiving_account_info, requested_foreign_receiving_account_info "
				+ "FROM TradeBotStates "
				+ "WHERE trade_private_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, tradePrivateKey)) {
			if (resultSet == null)
				return null;

			String acctName = resultSet.getString(1);
			String tradeState = resultSet.getString(2);
			int tradeStateValue = resultSet.getInt(3);
			String creatorAddress = resultSet.getString(4);
			String atAddress = resultSet.getString(5);
			long timestamp = resultSet.getLong(6);
			long localAssetId = resultSet.getLong(7);
			long localAmount = resultSet.getLong(8);
			byte[] tradeLocalPublicKey = resultSet.getBytes(9);
			byte[] tradeLocalPublicKeyHash = resultSet.getBytes(10);
			String tradeLocalAddress = resultSet.getString(11);
			byte[] secret = resultSet.getBytes(12);
			byte[] hashOfSecret = resultSet.getBytes(13);
			String foreignBlockchain = resultSet.getString(14);
			byte[] tradeForeignPublicKey = resultSet.getBytes(15);
			byte[] tradeForeignPublicKeyHash = resultSet.getBytes(16);
			long foreignAmount = resultSet.getLong(17);
			String foreignKey = resultSet.getString(18);
			byte[] lastTransactionSignature = resultSet.getBytes(19);
			Integer lockTimeA = getNullableInteger(resultSet, 20);
			Integer fillSlotIndex = getNullableInteger(resultSet, 21);
			byte[] receivingAccountInfo = resultSet.getBytes(22);

			TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, acctName,
					tradeState, tradeStateValue,
					creatorAddress, atAddress, timestamp, localAssetId, localAmount,
					tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
					secret, hashOfSecret,
					foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
					foreignAmount, foreignKey, lastTransactionSignature, lockTimeA, fillSlotIndex, receivingAccountInfo);
			populateForeignForeignFields(tradeBotData, resultSet, 23);

			return tradeBotData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot trading state from repository", e);
		}
	}

	@Override
	public boolean existsTradeWithAtExcludingStates(String atAddress, List<String> excludeStates) throws DataException {
		if (excludeStates == null)
			excludeStates = Collections.emptyList();

		StringBuilder whereClause = new StringBuilder(256);
		whereClause.append("at_address = ?");

		Object[] bindParams = new Object[1 + excludeStates.size()];
		bindParams[0] = atAddress;

		if (!excludeStates.isEmpty()) {
			whereClause.append(" AND trade_state NOT IN (?");
			bindParams[1] = excludeStates.get(0);

			for (int i = 1; i < excludeStates.size(); ++i) {
				whereClause.append(", ?");
				bindParams[1 + i] = excludeStates.get(i);
			}

			whereClause.append(")");
		}

		try {
			return this.repository.exists("TradeBotStates", whereClause.toString(), bindParams);
		} catch (SQLException e) {
			throw new DataException("Unable to check for trade-bot state in repository", e);
		}
	}

	@Override
	public List<TradeBotData> getAllTradeBotData() throws DataException {
		String sql = "SELECT trade_private_key, acct_name, trade_state, trade_state_value, "
				+ "creator_address, at_address, "
				+ "updated_when, local_asset_id, local_amount, "
				+ "trade_local_public_key, trade_local_public_key_hash, "
				+ "trade_local_address, secret, hash_of_secret, "
				+ "foreign_blockchain, trade_foreign_public_key, trade_foreign_public_key_hash, "
				+ "foreign_amount, foreign_key, last_transaction_signature, locktime_a, fill_slot_index, receiving_account_info, "
				+ "offered_foreign_blockchain, offered_trade_foreign_public_key, offered_trade_foreign_public_key_hash, "
				+ "offered_foreign_amount, offered_foreign_key, "
				+ "requested_foreign_blockchain, requested_trade_foreign_public_key, requested_trade_foreign_public_key_hash, "
				+ "requested_foreign_amount, requested_foreign_key, locktime_b, "
				+ "offered_foreign_receiving_account_info, requested_foreign_receiving_account_info "
				+ "FROM TradeBotStates";

		List<TradeBotData> allTradeBotData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return allTradeBotData;

			do {
				byte[] tradePrivateKey = resultSet.getBytes(1);
				String acctName = resultSet.getString(2);
				String tradeState = resultSet.getString(3);
				int tradeStateValue = resultSet.getInt(4);
				String creatorAddress = resultSet.getString(5);
				String atAddress = resultSet.getString(6);
				long timestamp = resultSet.getLong(7);
				long localAssetId = resultSet.getLong(8);
				long localAmount = resultSet.getLong(9);
				byte[] tradeLocalPublicKey = resultSet.getBytes(10);
				byte[] tradeLocalPublicKeyHash = resultSet.getBytes(11);
				String tradeLocalAddress = resultSet.getString(12);
				byte[] secret = resultSet.getBytes(13);
				byte[] hashOfSecret = resultSet.getBytes(14);
				String foreignBlockchain = resultSet.getString(15);
				byte[] tradeForeignPublicKey = resultSet.getBytes(16);
				byte[] tradeForeignPublicKeyHash = resultSet.getBytes(17);
				long foreignAmount = resultSet.getLong(18);
				String foreignKey = resultSet.getString(19);
				byte[] lastTransactionSignature = resultSet.getBytes(20);
				Integer lockTimeA = getNullableInteger(resultSet, 21);
				Integer fillSlotIndex = getNullableInteger(resultSet, 22);
				byte[] receivingAccountInfo = resultSet.getBytes(23);

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, acctName,
						tradeState, tradeStateValue,
						creatorAddress, atAddress, timestamp, localAssetId, localAmount,
						tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
						secret, hashOfSecret,
						foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
						foreignAmount, foreignKey, lastTransactionSignature, lockTimeA, fillSlotIndex, receivingAccountInfo);
				populateForeignForeignFields(tradeBotData, resultSet, 24);
				allTradeBotData.add(tradeBotData);
			} while (resultSet.next());

			return allTradeBotData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot trading states from repository", e);
		}
	}

	@Override
	public void save(TradeBotData tradeBotData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("TradeBotStates");

		saveHelper.bind("trade_private_key", tradeBotData.getTradePrivateKey())
				.bind("acct_name", tradeBotData.getAcctName())
				.bind("trade_state", tradeBotData.getState())
				.bind("trade_state_value", tradeBotData.getStateValue())
				.bind("creator_address", tradeBotData.getCreatorAddress())
				.bind("at_address", tradeBotData.getAtAddress())
				.bind("updated_when", tradeBotData.getTimestamp())
				.bind("local_asset_id", tradeBotData.getLocalAssetId())
				.bind("local_amount", tradeBotData.getLocalAmount())
				.bind("trade_local_public_key", tradeBotData.getTradeLocalPublicKey())
				.bind("trade_local_public_key_hash", tradeBotData.getTradeLocalPublicKeyHash())
				.bind("trade_local_address", tradeBotData.getTradeLocalAddress())
				.bind("secret", tradeBotData.getSecret())
				.bind("hash_of_secret", tradeBotData.getHashOfSecret())
				.bind("foreign_blockchain", tradeBotData.getForeignBlockchain())
				.bind("trade_foreign_public_key", tradeBotData.getTradeForeignPublicKey())
				.bind("trade_foreign_public_key_hash", tradeBotData.getTradeForeignPublicKeyHash())
				.bind("foreign_amount", tradeBotData.getForeignAmount())
				.bind("foreign_key", tradeBotData.getForeignKey())
				.bind("last_transaction_signature", tradeBotData.getLastTransactionSignature())
				.bind("locktime_a", tradeBotData.getLockTimeA())
				.bind("fill_slot_index", tradeBotData.getFillSlotIndex())
				.bind("receiving_account_info", tradeBotData.getReceivingAccountInfo())
				.bind("offered_foreign_blockchain", tradeBotData.getOfferedForeignBlockchain())
				.bind("offered_trade_foreign_public_key", tradeBotData.getOfferedTradeForeignPublicKey())
				.bind("offered_trade_foreign_public_key_hash", tradeBotData.getOfferedTradeForeignPublicKeyHash())
				.bind("offered_foreign_amount", tradeBotData.getOfferedForeignAmount())
				.bind("offered_foreign_key", tradeBotData.getOfferedForeignKey())
				.bind("requested_foreign_blockchain", tradeBotData.getRequestedForeignBlockchain())
				.bind("requested_trade_foreign_public_key", tradeBotData.getRequestedTradeForeignPublicKey())
				.bind("requested_trade_foreign_public_key_hash", tradeBotData.getRequestedTradeForeignPublicKeyHash())
				.bind("requested_foreign_amount", tradeBotData.getRequestedForeignAmount())
				.bind("requested_foreign_key", tradeBotData.getRequestedForeignKey())
				.bind("locktime_b", tradeBotData.getLockTimeB())
				.bind("offered_foreign_receiving_account_info", tradeBotData.getOfferedForeignReceivingAccountInfo())
				.bind("requested_foreign_receiving_account_info", tradeBotData.getRequestedForeignReceivingAccountInfo());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade bot data into repository", e);
		}
	}

	@Override
	public List<TradeBotFillData> getTradeBotFillData(String atAddress) throws DataException {
		String sql = "SELECT slot_index, fill_state, updated_when, partner_address, partner_foreign_public_key_hash, "
				+ "hash_of_secret, locktime_a, local_amount, foreign_amount, p2sh_address "
				+ "FROM TradeBotFills WHERE at_address = ?";

		List<TradeBotFillData> fills = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress)) {
			if (resultSet == null)
				return fills;

			do {
				fills.add(new TradeBotFillData(atAddress,
						resultSet.getInt(1),
						resultSet.getString(2),
						resultSet.getLong(3),
						resultSet.getString(4),
						resultSet.getBytes(5),
						resultSet.getBytes(6),
						resultSet.getInt(7),
						resultSet.getLong(8),
						resultSet.getLong(9),
						resultSet.getString(10)));
			} while (resultSet.next());

			return fills;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot fills from repository", e);
		}
	}

	@Override
	public List<TradeBotFillData> getAllTradeBotFillData() throws DataException {
		String sql = "SELECT at_address, slot_index, fill_state, updated_when, partner_address, partner_foreign_public_key_hash, "
				+ "hash_of_secret, locktime_a, local_amount, foreign_amount, p2sh_address "
				+ "FROM TradeBotFills";

		List<TradeBotFillData> fills = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return fills;

			do {
				fills.add(new TradeBotFillData(resultSet.getString(1),
						resultSet.getInt(2),
						resultSet.getString(3),
						resultSet.getLong(4),
						resultSet.getString(5),
						resultSet.getBytes(6),
						resultSet.getBytes(7),
						resultSet.getInt(8),
						resultSet.getLong(9),
						resultSet.getLong(10),
						resultSet.getString(11)));
			} while (resultSet.next());

			return fills;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch all trade-bot fills from repository", e);
		}
	}

	@Override
	public TradeBotFillData getTradeBotFillData(String atAddress, byte[] hashOfSecret) throws DataException {
		String sql = "SELECT slot_index, fill_state, updated_when, partner_address, partner_foreign_public_key_hash, "
				+ "locktime_a, local_amount, foreign_amount, p2sh_address "
				+ "FROM TradeBotFills WHERE at_address = ? AND hash_of_secret = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, atAddress, hashOfSecret)) {
			if (resultSet == null)
				return null;

			return new TradeBotFillData(atAddress,
					resultSet.getInt(1),
					resultSet.getString(2),
					resultSet.getLong(3),
					resultSet.getString(4),
					resultSet.getBytes(5),
					hashOfSecret,
					resultSet.getInt(6),
					resultSet.getLong(7),
					resultSet.getLong(8),
					resultSet.getString(9));
		} catch (SQLException e) {
			throw new DataException("Unable to fetch trade-bot fill from repository", e);
		}
	}

	@Override
	public void save(TradeBotFillData tradeBotFillData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("TradeBotFills");

		saveHelper.bind("at_address", tradeBotFillData.getAtAddress())
				.bind("hash_of_secret", tradeBotFillData.getHashOfSecret())
				.bind("slot_index", tradeBotFillData.getSlotIndex())
				.bind("fill_state", tradeBotFillData.getState())
				.bind("updated_when", tradeBotFillData.getTimestamp())
				.bind("partner_address", tradeBotFillData.getPartnerAddress())
				.bind("partner_foreign_public_key_hash", tradeBotFillData.getPartnerForeignPublicKeyHash())
				.bind("locktime_a", tradeBotFillData.getLockTimeA())
				.bind("local_amount", tradeBotFillData.getLocalAmount())
				.bind("foreign_amount", tradeBotFillData.getForeignAmount())
				.bind("p2sh_address", tradeBotFillData.getP2shAddress());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade-bot fill into repository", e);
		}
	}

	@Override
	public int delete(byte[] tradePrivateKey) throws DataException {
		try {
			return this.repository.delete("TradeBotStates", "trade_private_key = ?", tradePrivateKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete trade-bot states from repository", e);
		}
	}

	private static void populateForeignForeignFields(TradeBotData tradeBotData, ResultSet resultSet, int columnIndex)
			throws SQLException {
		tradeBotData.setOfferedForeignBlockchain(resultSet.getString(columnIndex++));
		tradeBotData.setOfferedTradeForeignPublicKey(resultSet.getBytes(columnIndex++));
		tradeBotData.setOfferedTradeForeignPublicKeyHash(resultSet.getBytes(columnIndex++));
		tradeBotData.setOfferedForeignAmount(getNullableLong(resultSet, columnIndex++));
		tradeBotData.setOfferedForeignKey(resultSet.getString(columnIndex++));
		tradeBotData.setRequestedForeignBlockchain(resultSet.getString(columnIndex++));
		tradeBotData.setRequestedTradeForeignPublicKey(resultSet.getBytes(columnIndex++));
		tradeBotData.setRequestedTradeForeignPublicKeyHash(resultSet.getBytes(columnIndex++));
		tradeBotData.setRequestedForeignAmount(getNullableLong(resultSet, columnIndex++));
		tradeBotData.setRequestedForeignKey(resultSet.getString(columnIndex++));
		tradeBotData.setLockTimeB(getNullableInteger(resultSet, columnIndex++));
		tradeBotData.setOfferedForeignReceivingAccountInfo(resultSet.getBytes(columnIndex++));
		tradeBotData.setRequestedForeignReceivingAccountInfo(resultSet.getBytes(columnIndex));
	}

	private static Integer getNullableInteger(ResultSet resultSet, int columnIndex) throws SQLException {
		int value = resultSet.getInt(columnIndex);
		return value == 0 && resultSet.wasNull() ? null : value;
	}

	private static Long getNullableLong(ResultSet resultSet, int columnIndex) throws SQLException {
		long value = resultSet.getLong(columnIndex);
		return value == 0 && resultSet.wasNull() ? null : value;
	}

}
