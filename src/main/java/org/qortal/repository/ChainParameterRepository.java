package org.qortal.repository;

import org.qortal.data.blockchain.ChainParameterData;

public interface ChainParameterRepository {

	public ChainParameterData getEffectiveParameter(int parameterId, int height) throws DataException;

	public boolean hasParameterAtHeight(int parameterId, int activationHeight) throws DataException;

	public void save(ChainParameterData chainParameterData) throws DataException;

	public void delete(byte[] signature) throws DataException;
}
