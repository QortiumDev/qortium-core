package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service;
import cash.z.wallet.sdk.rpc.Service.*;
import com.google.common.hash.HashCode;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.transform.TransformationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

public class ZcashFamilyLightClient extends BitcoinyBlockchainProvider {

	private static final Logger LOGGER = LogManager.getLogger(ZcashFamilyLightClient.class);
	private static final Random RANDOM = new Random();

	private static final int RESPONSE_TIME_READINGS = 5;
	private static final long MAX_AVG_RESPONSE_TIME = 500L;
	private static final int MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;
	private static final int MAX_INBOUND_METADATA_BYTES = 8 * 1024;
	private static final int TX_CACHE_SIZE = 1000;

	public static class Server implements ChainableServer {
		private final String hostname;
		private final ConnectionType connectionType;
		private final int port;
		private final List<Long> responseTimes = new ArrayList<>();

		public Server(String hostname, ConnectionType connectionType, int port) {
			this.hostname = hostname;
			this.connectionType = connectionType;
			this.port = port;
		}

		@Override
		public void addResponseTime(long responseTime) {
			while (this.responseTimes.size() > RESPONSE_TIME_READINGS)
				this.responseTimes.remove(0);

			this.responseTimes.add(responseTime);
		}

		@Override
		public long averageResponseTime() {
			if (this.responseTimes.size() < RESPONSE_TIME_READINGS)
				return 0L;

			OptionalDouble average = this.responseTimes.stream().mapToDouble(a -> a).average();
			return average.isPresent() ? Double.valueOf(average.getAsDouble()).longValue() : 0L;
		}

		@Override
		public String getHostName() {
			return this.hostname;
		}

		@Override
		public int getPort() {
			return this.port;
		}

		@Override
		public ChainableServer.ConnectionType getConnectionType() {
			return this.connectionType;
		}

		@Override
		public boolean equals(Object other) {
			if (other == this)
				return true;

			if (!(other instanceof ChainableServer))
				return false;

			ChainableServer otherServer = (ChainableServer) other;
			return this.connectionType == otherServer.getConnectionType()
					&& this.port == otherServer.getPort()
					&& this.hostname.equals(otherServer.getHostName());
		}

		@Override
		public int hashCode() {
			return this.hostname.hashCode() ^ this.port ^ this.connectionType.hashCode();
		}

		@Override
		public String toString() {
			return String.format("%s:%s:%d", this.connectionType.name(), this.hostname, this.port);
		}
	}

	private final ZcashFamilyWalletConfig config;
	private final String netId;
	@SuppressWarnings("unused")
	private final String expectedGenesisHash;
	@SuppressWarnings("unused")
	private final Map<ChainableServer.ConnectionType, Integer> defaultPorts = new EnumMap<>(ChainableServer.ConnectionType.class);
	private final IntSupplier defaultBirthdaySupplier;
	private Bitcoiny blockchain;

	private final Set<ChainableServer> servers = new HashSet<>();
	private final List<ChainableServer> remainingServers = new ArrayList<>();
	private final Set<ChainableServer> uselessServers = Collections.synchronizedSet(new HashSet<>());
	private final Object serverLock = new Object();
	private ChainableServer currentServer;
	private ManagedChannel channel;
	private final ChainableServerConnectionRecorder recorder = new ChainableServerConnectionRecorder(100);

	@SuppressWarnings("serial")
	private final Map<String, BitcoinyTransaction> transactionCache = Collections.synchronizedMap(new LinkedHashMap<>(TX_CACHE_SIZE + 1, 0.75F, true) {
		@Override
		public boolean removeEldestEntry(Map.Entry<String, BitcoinyTransaction> eldest) {
			return size() > TX_CACHE_SIZE;
		}
	});

	public ZcashFamilyLightClient(ZcashFamilyWalletConfig config, String netId, String genesisHash,
			Collection<? extends ChainableServer> initialServerList,
			Map<ChainableServer.ConnectionType, Integer> defaultPorts,
			IntSupplier defaultBirthdaySupplier) {
		this.config = config;
		this.netId = netId;
		this.expectedGenesisHash = genesisHash;
		this.servers.addAll(initialServerList);
		this.defaultPorts.putAll(defaultPorts);
		this.defaultBirthdaySupplier = defaultBirthdaySupplier;
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
	public int getCurrentHeight() throws ForeignBlockchainException {
		BlockID latestBlock = this.getCompactTxStreamerStub().getLatestBlock(null);

		if (!(latestBlock instanceof BlockID))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getLatestBlock gRPC");

		return (int) latestBlock.getHeight();
	}

	@Override
	public List<CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocksIterator = this.getCompactTxStreamerStub().getBlockRange(range);
		List<CompactBlock> blocks = new ArrayList<>();
		blocksIterator.forEachRemaining(blocks::add);

		return blocks;
	}

	@Override
	public List<byte[]> getRawBlockHeaders(int startHeight, int count) throws ForeignBlockchainException {
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocks = this.getCompactTxStreamerStub().getBlockRange(range);
		List<byte[]> rawBlockHeaders = new ArrayList<>();

		while (blocks.hasNext()) {
			CompactBlock block = blocks.next();
			if (block.getHeader() == null)
				throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getBlockRange gRPC");

			rawBlockHeaders.add(block.getHeader().toByteArray());
		}

		return rawBlockHeaders;
	}

	@Override
	public List<Long> getBlockTimestamps(int startHeight, int count) throws ForeignBlockchainException {
		BlockID startBlock = BlockID.newBuilder().setHeight(startHeight).build();
		BlockID endBlock = BlockID.newBuilder().setHeight(startHeight + count - 1).build();
		BlockRange range = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

		Iterator<CompactBlock> blocks = this.getCompactTxStreamerStub().getBlockRange(range);
		List<Long> rawBlockTimestamps = new ArrayList<>();

		while (blocks.hasNext()) {
			CompactBlock block = blocks.next();
			if (block.getTime() <= 0)
				throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getBlockRange gRPC");

			rawBlockTimestamps.add(Long.valueOf(block.getTime()));
		}

		return rawBlockTimestamps;
	}

	@Override
	public long getConfirmedBalance(byte[] script) throws ForeignBlockchainException {
		throw new ForeignBlockchainException("getConfirmedBalance not yet implemented for " + this.config.getDisplayName());
	}

	@Override
	public long getConfirmedAddressBalance(String base58Address) throws ForeignBlockchainException {
		AddressList addressList = AddressList.newBuilder().addAddresses(base58Address).build();
		Balance balance = this.getCompactTxStreamerStub().getTaddressBalance(addressList);

		if (!(balance instanceof Balance))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getConfirmedAddressBalance gRPC");

		return balance.getValueZat();
	}

	@Override
	public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		GetAddressUtxosArg getAddressUtxosArg = GetAddressUtxosArg.newBuilder().addAddresses(address).build();
		GetAddressUtxosReplyList replyList = this.getCompactTxStreamerStub().getAddressUtxos(getAddressUtxosArg);

		if (!(replyList instanceof GetAddressUtxosReplyList))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getUnspentOutputs gRPC");

		List<GetAddressUtxosReply> unspentList = replyList.getAddressUtxosList();
		if (unspentList == null)
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getUnspentOutputs gRPC");

		List<UnspentOutput> unspentOutputs = new ArrayList<>();
		for (GetAddressUtxosReply unspent : unspentList) {
			int height = (int) unspent.getHeight();
			if (!includeUnconfirmed && height <= 0)
				continue;

			unspentOutputs.add(new UnspentOutput(unspent.getTxid().toByteArray(), unspent.getIndex(), height,
					unspent.getValueZat(), unspent.getScript().toByteArray(), unspent.getAddress()));
		}

		return unspentOutputs;
	}

	@Override
	public List<UnspentOutput> getUnspentOutputs(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		String address = this.blockchain.deriveP2shAddress(script);
		return this.getUnspentOutputs(address, includeUnconfirmed);
	}

	@Override
	public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
		return getRawTransaction(HashCode.fromString(txHash).asBytes());
	}

	@Override
	public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
		ByteString byteString = ByteString.copyFrom(txHash);
		TxFilter txFilter = TxFilter.newBuilder().setHash(byteString).build();
		RawTransaction rawTransaction = this.getCompactTxStreamerStub().getTransaction(txFilter);

		if (!(rawTransaction instanceof RawTransaction))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getTransaction gRPC");

		return rawTransaction.getData().toByteArray();
	}

	@Override
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		BitcoinyTransaction transaction = transactionCache.get(txHash);
		if (transaction != null)
			return transaction;

		ByteString byteString = ByteString.copyFrom(HashCode.fromString(txHash).asBytes());
		TxFilter txFilter = TxFilter.newBuilder().setHash(byteString).build();
		RawTransaction rawTransaction = this.getCompactTxStreamerStub().getTransaction(txFilter);

		if (!(rawTransaction instanceof RawTransaction))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " getTransaction gRPC");

		try {
			transaction = ZcashFamilyTransactionParser.deserializeRawTransaction(txHash, rawTransaction.getData().toByteArray());
			transactionCache.put(txHash, transaction);
			return transaction;
		} catch (TransformationException e) {
			throw new ForeignBlockchainException.NetworkException("Unable to parse raw transaction from " + this.config.getDisplayName() + " getTransaction gRPC: " + e.getMessage());
		}
	}

	@Override
	public List<TransactionHash> getAddressTransactions(byte[] script, boolean includeUnconfirmed) throws ForeignBlockchainException {
		throw new ForeignBlockchainException("getAddressTransactions not yet implemented for " + this.config.getDisplayName());
	}

	@Override
	public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		try {
			BlockID endBlock = this.getCompactTxStreamerStub().getLatestBlock(null);
			BlockID startBlock = BlockID.newBuilder().setHeight(this.defaultBirthdaySupplier.getAsInt()).build();
			BlockRange blockRange = BlockRange.newBuilder().setStart(startBlock).setEnd(endBlock).build();

			TransparentAddressBlockFilter blockFilter = TransparentAddressBlockFilter.newBuilder()
					.setAddress(address)
					.setRange(blockRange)
					.build();
			Iterator<Service.RawTransaction> transactionIterator = this.getCompactTxStreamerStub().getTaddressTxids(blockFilter);

			List<RawTransaction> rawTransactions = new ArrayList<>();
			transactionIterator.forEachRemaining(rawTransactions::add);

			List<BitcoinyTransaction> transactions = new ArrayList<>();
			for (RawTransaction rawTransaction : rawTransactions) {
				Long height = rawTransaction.getHeight();
				if (!includeUnconfirmed && (height == null || height == 0))
					continue;

				BitcoinyTransaction bitcoinyTransaction = ZcashFamilyTransactionParser.deserializeRawTransaction(rawTransaction.getData().toByteArray());
				bitcoinyTransaction.height = height.intValue();
				transactions.add(bitcoinyTransaction);
			}

			return transactions;
		} catch (RuntimeException | TransformationException e) {
			throw new ForeignBlockchainException(String.format("Unable to get transactions for address %s: %s", address, e.getMessage()));
		}
	}

	@Override
	public void broadcastTransaction(byte[] transactionBytes) throws ForeignBlockchainException {
		ByteString byteString = ByteString.copyFrom(transactionBytes);
		RawTransaction rawTransaction = RawTransaction.newBuilder().setData(byteString).build();
		SendResponse sendResponse = this.getCompactTxStreamerStub().sendTransaction(rawTransaction);

		if (!(sendResponse instanceof SendResponse))
			throw new ForeignBlockchainException.NetworkException("Unexpected output from " + this.config.getDisplayName() + " broadcastTransaction gRPC");

		if (sendResponse.getErrorCode() != 0)
			throw new ForeignBlockchainException.NetworkException(String.format("Unexpected error code from %s broadcastTransaction gRPC: %d",
					this.config.getDisplayName(), sendResponse.getErrorCode()));
	}

	@Override
	public Set<ChainableServer> getServers() {
		return this.servers;
	}

	@Override
	public Set<ChainableServer> getUselessServers() {
		return this.uselessServers;
	}

	@Override
	public ChainableServer getCurrentServer() {
		return this.currentServer;
	}

	@Override
	public boolean addServer(ChainableServer server) {
		return this.servers.add(server);
	}

	@Override
	public boolean removeServer(ChainableServer server) {
		boolean removedServer = this.servers.remove(server);
		boolean removedRemaining = this.remainingServers.remove(server);
		return removedServer || removedRemaining;
	}

	@Override
	public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) throws ForeignBlockchainException {
		closeServer(requestedBy, "Connecting to different server by request.");
		Optional<ChainableServerConnection> connection = makeConnection(server, requestedBy);

		if (!connection.isPresent() || !connection.get().isSuccess())
			haveConnection();

		return connection;
	}

	@Override
	public List<ChainableServerConnection> getServerConnections() {
		return this.recorder.getConnections();
	}

	@Override
	public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
		return new ZcashFamilyLightClient.Server(hostName, type, port);
	}

	private CompactTxStreamerGrpc.CompactTxStreamerBlockingStub getCompactTxStreamerStub() throws ForeignBlockchainException {
		synchronized (this.serverLock) {
			if (this.remainingServers.isEmpty())
				this.remainingServers.addAll(this.servers);

			while (haveConnection()) {
				if (!this.remainingServers.isEmpty()) {
					long averageResponseTime = this.currentServer.averageResponseTime();
					if (averageResponseTime > MAX_AVG_RESPONSE_TIME) {
						String message = String.format("Slow average response time %dms from %s - trying another server...",
								averageResponseTime, this.currentServer.getHostName());
						LOGGER.info(message);
						this.closeServer(this.getClass().getSimpleName(), message);
						continue;
					}
				}

				return CompactTxStreamerGrpc.newBlockingStub(this.channel);
			}

			LOGGER.info("Error: No connected {} lightwalletd servers when trying to make RPC call", this.config.getDisplayName());
			throw new ForeignBlockchainException.NetworkException("No connected " + this.config.getDisplayName() + " lightwalletd servers when trying to make RPC call");
		}
	}

	private boolean haveConnection() throws ForeignBlockchainException {
		if (this.currentServer != null && this.channel != null && !this.channel.isShutdown())
			return true;

		while (!this.remainingServers.isEmpty()) {
			ChainableServer server = this.remainingServers.remove(RANDOM.nextInt(this.remainingServers.size()));
			Optional<ChainableServerConnection> connection = makeConnection(server, this.getClass().getSimpleName());
			if (connection.isPresent() && connection.get().isSuccess())
				return true;
		}

		return false;
	}

	private Optional<ChainableServerConnection> makeConnection(ChainableServer server, String requestedBy) {
		LOGGER.info(() -> String.format("Connecting to %s", server));

		ManagedChannel tempChannel = null;
		try {
			ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(server.getHostName(), server.getPort());
			channelBuilder.maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES);
			channelBuilder.maxInboundMetadataSize(MAX_INBOUND_METADATA_BYTES);
			if (server.getConnectionType() == ChainableServer.ConnectionType.SSL)
				channelBuilder.useTransportSecurity();
			else
				channelBuilder.usePlaintext();

			tempChannel = channelBuilder.build();

			CompactTxStreamerGrpc.CompactTxStreamerBlockingStub stub = CompactTxStreamerGrpc.newBlockingStub(tempChannel);
			LightdInfo lightdInfo = stub.withDeadlineAfter(10, TimeUnit.SECONDS).getLightdInfo(Empty.newBuilder().build());

			if (lightdInfo == null || lightdInfo.getBlockHeight() <= 0) {
				shutdownChannel(tempChannel);
				return Optional.of(this.recorder.recordConnection(server, requestedBy, true, false, "lightd info issues"));
			}

			synchronized (this.serverLock) {
				this.channel = tempChannel;
				this.currentServer = server;
			}

			LOGGER.info(() -> String.format("Connected to %s", server));
			return Optional.of(this.recorder.recordConnection(server, requestedBy, true, true, EMPTY));
		} catch (Exception e) {
			if (tempChannel != null)
				shutdownChannel(tempChannel);

			String notes = CrossChainUtils.getNotes(e);
			if (e instanceof StatusRuntimeException) {
				StatusRuntimeException statusException = (StatusRuntimeException) e;
				Throwable cause = statusException.getCause();
				String causeText = cause == null ? "none" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
				LOGGER.warn("{} gRPC failure details for {} -> code: {}, description: {}, cause: {}",
						this.config.getDisplayName(),
						server,
						statusException.getStatus().getCode(),
						statusException.getStatus().getDescription(),
						causeText);
			}
			LOGGER.warn("Unable to connect to {} lightwalletd server {}: {}", this.config.getDisplayName(), server, notes);
			return Optional.of(this.recorder.recordConnection(server, requestedBy, true, false, notes));
		}
	}

	private Optional<ChainableServerConnection> closeServer(ChainableServer server, String notes, String requestedBy) {
		final ChainableServerConnection connection;

		synchronized (this.serverLock) {
			if (this.currentServer == null || !this.currentServer.equals(server) || this.channel == null)
				return Optional.empty();

			connection = this.recorder.recordConnection(server, requestedBy, false, true, notes);

			if (!this.channel.isShutdown()) {
				try {
					this.channel.shutdown();
					if (!this.channel.awaitTermination(10, TimeUnit.SECONDS))
						LOGGER.warn("Timed out gracefully shutting down connection: {}.", this.channel);
				} catch (Exception e) {
					LOGGER.error("Unexpected exception while waiting for channel termination", e);
				}
			}

			if (!this.channel.isTerminated()) {
				try {
					this.channel.shutdownNow();
					if (!this.channel.awaitTermination(15, TimeUnit.SECONDS))
						LOGGER.warn("Timed out forcefully shutting down connection: {}.", this.channel);
				} catch (Exception e) {
					LOGGER.error("Unexpected exception while waiting for channel termination", e);
				}
			}

			this.channel = null;
			this.currentServer = null;
		}

		return Optional.of(connection);
	}

	private Optional<ChainableServerConnection> closeServer(String requestedBy, String notes) {
		synchronized (this.serverLock) {
			return this.closeServer(this.currentServer, notes, requestedBy);
		}
	}

	private void shutdownChannel(ManagedChannel channel) {
		if (channel == null)
			return;

		if (!channel.isShutdown()) {
			try {
				channel.shutdown();
				if (!channel.awaitTermination(5, TimeUnit.SECONDS))
					LOGGER.debug("Channel did not terminate gracefully, forcing shutdown");
			} catch (InterruptedException e) {
				LOGGER.debug("Interrupted while waiting for channel termination");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOGGER.debug("Exception during graceful channel shutdown: {}", e.getMessage());
			}
		}

		if (!channel.isTerminated()) {
			try {
				channel.shutdownNow();
				if (!channel.awaitTermination(5, TimeUnit.SECONDS))
					LOGGER.debug("Channel did not terminate forcefully");
			} catch (InterruptedException e) {
				LOGGER.debug("Interrupted while waiting for forceful channel termination");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOGGER.debug("Exception during forceful channel shutdown: {}", e.getMessage());
			}
		}
	}
}
