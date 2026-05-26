package org.qortium.crosschain;

import org.qortium.data.at.ATData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.List;
import java.util.OptionalLong;

public interface ACCT {

	public byte[] getCodeBytesHash();

	public int getModeByteOffset();

	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException;

	public List<CrossChainTradeData> populateTradeDataList(Repository respository, List<ATData> atDataList) throws DataException;

	public CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) throws DataException;

	CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, long creationTimestamp, ATStateData atStateData, OptionalLong optionalBalance) throws DataException;

	public byte[] buildCancelMessage(String creatorAddress);

	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException;

}
