package org.qortium.repository.hsqldb;

import org.qortium.data.blockchain.ChainParameterData;
import org.qortium.repository.ChainParameterRepository;
import org.qortium.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBChainParameterRepository implements ChainParameterRepository {

	private final HSQLDBRepository repository;

	public HSQLDBChainParameterRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public ChainParameterData getEffectiveParameter(int parameterId, int height) throws DataException {
		String sql = "SELECT signature, activation_height, parameter_value FROM ChainParameterUpdates "
				+ "WHERE parameter_id = ? AND activation_height <= ? "
				+ "ORDER BY activation_height DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, parameterId, height)) {
			if (resultSet == null)
				return null;

			byte[] signature = resultSet.getBytes(1);
			int activationHeight = resultSet.getInt(2);
			byte[] value = resultSet.getBytes(3);

			return new ChainParameterData(signature, parameterId, activationHeight, value);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch effective chain parameter from repository", e);
		}
	}

	@Override
	public ChainParameterData getNextParameter(int parameterId, int height) throws DataException {
		String sql = "SELECT signature, activation_height, parameter_value FROM ChainParameterUpdates "
				+ "WHERE parameter_id = ? AND activation_height > ? "
				+ "ORDER BY activation_height ASC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, parameterId, height)) {
			if (resultSet == null)
				return null;

			byte[] signature = resultSet.getBytes(1);
			int activationHeight = resultSet.getInt(2);
			byte[] value = resultSet.getBytes(3);

			return new ChainParameterData(signature, parameterId, activationHeight, value);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch next chain parameter from repository", e);
		}
	}

	@Override
	public List<ChainParameterData> getParametersAtHeight(int activationHeight) throws DataException {
		String sql = "SELECT signature, parameter_id, parameter_value FROM ChainParameterUpdates "
				+ "WHERE activation_height = ? "
				+ "ORDER BY parameter_id ASC";

		List<ChainParameterData> parameters = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, activationHeight)) {
			if (resultSet == null)
				return parameters;

			do {
				byte[] signature = resultSet.getBytes(1);
				int parameterId = resultSet.getInt(2);
				byte[] value = resultSet.getBytes(3);

				parameters.add(new ChainParameterData(signature, parameterId, activationHeight, value));
			} while (resultSet.next());

			return parameters;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch chain parameters activating at height from repository", e);
		}
	}

	@Override
	public boolean hasParameterAtHeight(int parameterId, int activationHeight) throws DataException {
		try {
			return this.repository.exists("ChainParameterUpdates",
					"parameter_id = ? AND activation_height = ?", parameterId, activationHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to check chain parameter update in repository", e);
		}
	}

	@Override
	public void save(ChainParameterData chainParameterData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ChainParameterUpdates");

		saveHelper.bind("signature", chainParameterData.getSignature())
				.bind("parameter_id", chainParameterData.getParameterId())
				.bind("activation_height", chainParameterData.getActivationHeight())
				.bind("parameter_value", chainParameterData.getValue());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save chain parameter update into repository", e);
		}
	}

	@Override
	public void delete(byte[] signature) throws DataException {
		try {
			this.repository.delete("ChainParameterUpdates", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to delete chain parameter update from repository", e);
		}
	}
}
