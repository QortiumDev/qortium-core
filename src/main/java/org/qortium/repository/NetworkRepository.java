package org.qortium.repository;

import org.qortium.data.network.PeerData;
import org.qortium.network.PeerAddress;

import java.util.List;

public interface NetworkRepository {

	public List<PeerData> getAllPeers() throws DataException;

	public void save(PeerData peerData) throws DataException;

	public int delete(PeerAddress peerAddress) throws DataException;

	public int deleteAllPeers() throws DataException;

}
