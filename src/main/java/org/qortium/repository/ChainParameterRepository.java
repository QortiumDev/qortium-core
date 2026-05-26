package org.qortium.repository;

import org.qortium.data.blockchain.ChainParameterData;

import java.util.List;

public interface ChainParameterRepository {

	public ChainParameterData getEffectiveParameter(int parameterId, int height) throws DataException;

	public ChainParameterData getNextParameter(int parameterId, int height) throws DataException;

	public List<ChainParameterData> getParametersAtHeight(int activationHeight) throws DataException;

	public boolean hasParameterAtHeight(int parameterId, int activationHeight) throws DataException;

	public void save(ChainParameterData chainParameterData) throws DataException;

	public void delete(byte[] signature) throws DataException;
}
