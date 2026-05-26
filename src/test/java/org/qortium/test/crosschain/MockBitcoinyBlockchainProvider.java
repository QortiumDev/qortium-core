package org.qortium.test.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyBlockchainProvider;
import org.qortium.crosschain.BitcoinyRawTransactionParser;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ChainableServerConnection;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.TransactionHash;
import org.qortium.crosschain.UnspentOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class MockBitcoinyBlockchainProvider extends BitcoinyBlockchainProvider {

	private final String netId;
	private final Map<String, List<UnspentOutput>> unspentOutputsByAddress = new HashMap<>();
	private final Map<String, List<UnspentOutput>> unspentOutputsByScript = new HashMap<>();
	private final Map<String, List<TransactionHash>> transactionHashesByScript = new HashMap<>();
	private final Map<String, byte[]> rawTransactionsByHash = new HashMap<>();
	private final Map<String, BitcoinyTransaction> transactionsByHash = new HashMap<>();
	private final List<byte[]> broadcastTransactions = new ArrayList<>();
	private Bitcoiny blockchain;

	MockBitcoinyBlockchainProvider(String netId) {
		this.netId = netId;
	}

	void addUnspentOutput(String address, UnspentOutput unspentOutput) {
		this.unspentOutputsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(unspentOutput);
	}

	void addUnspentOutput(byte[] scriptPubKey, UnspentOutput unspentOutput) {
		this.unspentOutputsByScript.computeIfAbsent(HashCode.fromBytes(scriptPubKey).toString(), key -> new ArrayList<>()).add(unspentOutput);
	}

	void addAddressTransaction(byte[] scriptPubKey, TransactionHash transactionHash) {
		this.transactionHashesByScript.computeIfAbsent(HashCode.fromBytes(scriptPubKey).toString(), key -> new ArrayList<>()).add(transactionHash);
	}

	void addRawTransaction(String txHash, byte[] rawTransaction) {
		this.rawTransactionsByHash.put(txHash, rawTransaction);
		this.transactionsByHash.put(txHash, BitcoinyRawTransactionParser.parse(txHash, rawTransaction));
	}

	void addTransaction(BitcoinyTransaction transaction) {
		this.transactionsByHash.put(transaction.txHash, transaction);
	}

	List<byte[]> getBroadcastTransactions() {
		return this.broadcastTransactions;
	}

	@Override
	public void setBlockchain(Bitcoiny blockchain) {
		this.blockchain = blockchain;
	}

	@Override
	public String getNetId() {
		return this.netId;
	}

	@Override
	public int getCurrentHeight() {
		return 100;
	}

	@Override
	public List<CompactBlock> getCompactBlocks(int startHeight, int count) {
		return Collections.emptyList();
	}

	@Override
	public List<byte[]> getRawBlockHeaders(int startHeight, int count) {
		List<byte[]> headers = new ArrayList<>(count);
		for (int i = 0; i < count; ++i) {
			byte[] header = new byte[80];
			int timestamp = 1_700_000_000 + i;
			header[68] = (byte) timestamp;
			header[69] = (byte) (timestamp >> 8);
			header[70] = (byte) (timestamp >> 16);
			header[71] = (byte) (timestamp >> 24);
			headers.add(header);
		}
		return headers;
	}

	@Override
	public List<Long> getBlockTimestamps(int startHeight, int count) {
		List<Long> timestamps = new ArrayList<>(count);
		for (int i = 0; i < count; ++i)
			timestamps.add(1_700_000_000L + i);
		return timestamps;
	}

	@Override
	public long getConfirmedBalance(byte[] scriptPubKey) {
		return this.unspentOutputsByScript.getOrDefault(HashCode.fromBytes(scriptPubKey).toString(), Collections.emptyList()).stream()
				.mapToLong(unspentOutput -> unspentOutput.value)
				.sum();
	}

	@Override
	public long getConfirmedAddressBalance(String base58Address) {
		return this.unspentOutputsByAddress.getOrDefault(base58Address, Collections.emptyList()).stream()
				.mapToLong(unspentOutput -> unspentOutput.value)
				.sum();
	}

	@Override
	public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
		byte[] rawTransaction = this.rawTransactionsByHash.get(txHash);
		if (rawTransaction == null)
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");

		return rawTransaction;
	}

	@Override
	public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
		return getRawTransaction(HashCode.fromBytes(txHash).toString());
	}

	@Override
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		BitcoinyTransaction transaction = this.transactionsByHash.get(txHash);
		if (transaction == null)
			throw new ForeignBlockchainException.NotFoundException("mock transaction not found");

		return transaction;
	}

	@Override
	public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) {
		List<TransactionHash> transactionHashes = new ArrayList<>(this.transactionHashesByScript.getOrDefault(HashCode.fromBytes(scriptPubKey).toString(), Collections.emptyList()));
		if (!includeUnconfirmed)
			transactionHashes.removeIf(transactionHash -> transactionHash.height == 0);

		return transactionHashes;
	}

	@Override
	public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) {
		return Collections.emptyList();
	}

	@Override
	public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) {
		return new ArrayList<>(this.unspentOutputsByAddress.getOrDefault(address, Collections.emptyList()));
	}

	@Override
	public List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) {
		return new ArrayList<>(this.unspentOutputsByScript.getOrDefault(HashCode.fromBytes(scriptPubKey).toString(), Collections.emptyList()));
	}

	@Override
	public void broadcastTransaction(byte[] rawTransaction) {
		this.broadcastTransactions.add(rawTransaction);
	}

	@Override
	public Set<ChainableServer> getServers() {
		return new HashSet<>();
	}

	@Override
	public Set<ChainableServer> getUselessServers() {
		return new HashSet<>();
	}

	@Override
	public ChainableServer getCurrentServer() {
		return null;
	}

	@Override
	public boolean addServer(ChainableServer server) {
		return false;
	}

	@Override
	public boolean removeServer(ChainableServer server) {
		return false;
	}

	@Override
	public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) {
		return Optional.empty();
	}

	@Override
	public List<ChainableServerConnection> getServerConnections() {
		return Collections.emptyList();
	}

	@Override
	public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
		return null;
	}

	Bitcoiny getBlockchain() {
		return this.blockchain;
	}

}
