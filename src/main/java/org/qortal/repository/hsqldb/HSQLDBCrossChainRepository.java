package org.qortal.repository.hsqldb;

import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.CrossChainRepository;
import org.qortal.repository.DataException;

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
				+ "foreign_amount, foreign_key, last_transaction_signature, locktime_a, receiving_account_info "
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
			Integer lockTimeA = resultSet.getInt(20);
			if (lockTimeA == 0 && resultSet.wasNull())
				lockTimeA = null;
			byte[] receivingAccountInfo = resultSet.getBytes(21);

			return new TradeBotData(tradePrivateKey, acctName,
					tradeState, tradeStateValue,
					creatorAddress, atAddress, timestamp, localAssetId, localAmount,
					tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
					secret, hashOfSecret,
					foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
					foreignAmount, foreignKey, lastTransactionSignature, lockTimeA, receivingAccountInfo);
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
				+ "foreign_amount, foreign_key, last_transaction_signature, locktime_a, receiving_account_info "
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
				Integer lockTimeA = resultSet.getInt(21);
				if (lockTimeA == 0 && resultSet.wasNull())
					lockTimeA = null;
				byte[] receivingAccountInfo = resultSet.getBytes(22);

				TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, acctName,
						tradeState, tradeStateValue,
						creatorAddress, atAddress, timestamp, localAssetId, localAmount,
						tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
						secret, hashOfSecret,
						foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
						foreignAmount, foreignKey, lastTransactionSignature, lockTimeA, receivingAccountInfo);
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
				.bind("receiving_account_info", tradeBotData.getReceivingAccountInfo());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save trade bot data into repository", e);
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

}
