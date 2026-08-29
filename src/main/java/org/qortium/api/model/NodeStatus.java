package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.controller.Controller;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.controller.Synchronizer;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
import org.qortium.network.PeerList;
import org.qortium.network.i2p.I2PHealthTracker;
import org.qortium.settings.Settings;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeStatus {

	public enum SyncPhase {
		LITE,
		SYNCED,
		SYNCHRONIZING,
		BEHIND,
		CONNECTING,
		STALE
	}

	public enum I2PLeaseSetLookupStatus {
		UNKNOWN,
		RESOLVED,
		NOT_RESOLVED
	}

	public final boolean isMintingPossible;
	public final boolean isSynchronizing;

	@Schema(
			description = "Sync progress percentage when Core knows a target height. May be null while the node is connecting or stale and has not learned a peer target height yet.",
			nullable = true
	)
	public final Integer syncPercent;

	@Schema(
			description = "Target block height used for sync progress. May be null while the node is connecting or stale and has not learned a peer target height yet.",
			nullable = true
	)
	public final Integer syncTargetHeight;

	@Schema(
			description = "Estimated blocks remaining to the target height. May be null while the node is connecting or stale and has not learned a peer target height yet.",
			nullable = true
	)
	public final Integer syncBlocksRemaining;

	@Schema(
			description = "Current sync phase. Always present; clients should use this during startup windows where numeric progress fields are not yet known."
	)
	public final SyncPhase syncPhase;

	public final int numberOfConnections;

	@Schema(
			description = "Number of handshaked chain peers that connected inbound to this node."
	)
	public final int numberOfInboundConnections;

	@Schema(
			description = "Number of handshaked chain peers this node connected to outbound."
	)
	public final int numberOfOutboundConnections;

	@Schema(
			description = "Number of handshaked chain peers connected over I2P. The direct-IP count is numberOfConnections minus this value."
	)
	public final int numberOfI2PConnections;

	public final int numberOfDataConnections;

	@Schema(
			description = "Number of handshaked QDN/data peers that connected inbound to this node."
	)
	public final int numberOfInboundDataConnections;

	@Schema(
			description = "Number of handshaked QDN/data peers this node connected to outbound."
	)
	public final int numberOfOutboundDataConnections;

	@Schema(
			description = "Number of handshaked QDN/data peers connected over I2P. The direct-IP count is numberOfDataConnections minus this value."
	)
	public final int numberOfI2PDataConnections;

	@Schema(
			description = "Whether this node currently appears reachable for inbound chain peer connections over direct IP. This does not include I2P reachability."
	)
	public final boolean isP2PInboundReachable;

	@Schema(
			description = "Whether the chain peer listen socket is bound locally."
	)
	public final boolean isP2PListenSocketAvailable;

	@Schema(
			description = "Whether the chain peer listen port was mapped through UPnP."
	)
	public final boolean isP2PPortMapped;

	@Schema(
			description = "Whether this node currently appears reachable for inbound QDN/data peer connections over direct IP. This does not include I2P reachability."
	)
	public final boolean isQDNInboundReachable;

	@Schema(
			description = "Whether the QDN/data listen socket is bound locally."
	)
	public final boolean isQDNListenSocketAvailable;

	@Schema(
			description = "Whether the QDN/data listen port was mapped through UPnP."
	)
	public final boolean isQDNPortMapped;

	@Schema(
			description = "Whether the chain network's I2P SAM session is currently established. This alone does not prove remote inbound reachability."
	)
	public final boolean isI2PChainSessionUp;

	@Schema(
			description = "Result of the chain I2P destination's last self-lookup through the local SAM router. RESOLVED is a LeaseSet publication signal, not independent proof that remote routers can reach it. UNKNOWN with no lookup timestamp means no check has concluded; UNKNOWN with a timestamp means the last check was inconclusive."
	)
	public final I2PLeaseSetLookupStatus i2pChainLeaseSetLookupStatus;

	@Schema(
			description = "Epoch-millisecond time when the chain I2P LeaseSet self-lookup result was concluded. Omitted or null before any check has concluded.",
			nullable = true
	)
	public final Long i2pChainLeaseSetLookupTimestamp;

	@Schema(
			description = "Epoch-millisecond time of the last successfully completed inbound chain peer handshake over I2P. Omitted or null if none has completed since Core started.",
			nullable = true
	)
	public final Long i2pChainLastInboundHandshakeTimestamp;

	@Schema(
			description = "Whether the QDN/data network's I2P SAM session is currently established. This alone does not prove remote inbound reachability."
	)
	public final boolean isI2PDataSessionUp;

	@Schema(
			description = "Result of the QDN/data I2P destination's last self-lookup through the local SAM router. RESOLVED is a LeaseSet publication signal, not independent proof that remote routers can reach it. UNKNOWN with no lookup timestamp means no check has concluded; UNKNOWN with a timestamp means the last check was inconclusive."
	)
	public final I2PLeaseSetLookupStatus i2pDataLeaseSetLookupStatus;

	@Schema(
			description = "Epoch-millisecond time when the QDN/data I2P LeaseSet self-lookup result was concluded. Omitted or null before any check has concluded.",
			nullable = true
	)
	public final Long i2pDataLeaseSetLookupTimestamp;

	@Schema(
			description = "Epoch-millisecond time of the last successfully completed inbound QDN/data peer handshake over I2P. Omitted or null if none has completed since Core started.",
			nullable = true
	)
	public final Long i2pDataLastInboundHandshakeTimestamp;

	public final int height;

	public NodeStatus() {
		this.isMintingPossible = OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures();

		Synchronizer synchronizer = Synchronizer.getInstance();
		Controller controller = Controller.getInstance();
		Network network = Network.getInstance();
		NetworkData networkData = NetworkData.getInstance();
		I2PHealthTracker.LeaseSetLookupEvidence chainLeaseSetEvidence =
				network.getI2PChainLeaseSetLookupEvidence();
		I2PHealthTracker.LeaseSetLookupEvidence dataLeaseSetEvidence =
				networkData.getI2PDataLeaseSetLookupEvidence();
		List<Peer> handshakedPeers = network.getImmutableHandshakedPeers();
		PeerList handshakedDataPeers = networkData.getImmutableHandshakedPeers();
		PeerConnectionStats chainPeerStats = calculatePeerConnectionStats(handshakedPeers.size(),
				countOutboundConnections(handshakedPeers), network.canAcceptInbound(), network.isListenSocketAvailable(),
				network.isPortMapped());
		PeerConnectionStats dataPeerStats = calculatePeerConnectionStats(handshakedDataPeers.size(),
				countOutboundConnections(handshakedDataPeers), networkData.canAcceptInbound(),
				networkData.isListenSocketAvailable(), networkData.isPortMapped());

		this.isSynchronizing = synchronizer.isSynchronizing();
		this.numberOfConnections = chainPeerStats.totalConnections;
		this.numberOfInboundConnections = chainPeerStats.inboundConnections;
		this.numberOfOutboundConnections = chainPeerStats.outboundConnections;
		this.numberOfI2PConnections = countI2PConnections(handshakedPeers);

		this.numberOfDataConnections = dataPeerStats.totalConnections;
		this.numberOfInboundDataConnections = dataPeerStats.inboundConnections;
		this.numberOfOutboundDataConnections = dataPeerStats.outboundConnections;
		this.numberOfI2PDataConnections = countI2PConnections(handshakedDataPeers);
		this.isP2PInboundReachable = chainPeerStats.inboundReachable;
		this.isP2PListenSocketAvailable = chainPeerStats.listenSocketAvailable;
		this.isP2PPortMapped = chainPeerStats.portMapped;
		this.isQDNInboundReachable = dataPeerStats.inboundReachable;
		this.isQDNListenSocketAvailable = dataPeerStats.listenSocketAvailable;
		this.isQDNPortMapped = dataPeerStats.portMapped;
		this.isI2PChainSessionUp = network.isI2PChainSessionUp();
		this.i2pChainLeaseSetLookupStatus = toApiLeaseSetLookupStatus(chainLeaseSetEvidence.status);
		this.i2pChainLeaseSetLookupTimestamp = chainLeaseSetEvidence.timestamp;
		this.i2pChainLastInboundHandshakeTimestamp = network.getLastI2PChainInboundHandshakeTimestamp();
		this.isI2PDataSessionUp = networkData.isI2PDataSessionUp();
		this.i2pDataLeaseSetLookupStatus = toApiLeaseSetLookupStatus(dataLeaseSetEvidence.status);
		this.i2pDataLeaseSetLookupTimestamp = dataLeaseSetEvidence.timestamp;
		this.i2pDataLastInboundHandshakeTimestamp = networkData.getLastI2PDataInboundHandshakeTimestamp();

		this.height = controller.getStatusChainHeight();

		SyncProgress syncProgress = calculateSyncProgress(this.height, synchronizer.getSyncTargetHeight(),
				this.isSynchronizing, getBestPeerHeight(handshakedPeers), controller.isUpToDate(),
				Settings.getInstance().isLite(), this.numberOfConnections, Settings.getInstance().getMinBlockchainPeers());

		this.syncPercent = syncProgress.syncPercent;
		this.syncTargetHeight = syncProgress.syncTargetHeight;
		this.syncBlocksRemaining = syncProgress.syncBlocksRemaining;
		this.syncPhase = syncProgress.syncPhase;
	}

	public static SyncProgress calculateSyncProgress(int height, Integer activeSyncTargetHeight,
			boolean isSynchronizing, Integer bestPeerHeight, boolean isUpToDate, boolean isLite,
			int numberOfConnections, int minBlockchainPeers) {
		Integer syncTargetHeight = chooseSyncTargetHeight(height, activeSyncTargetHeight, bestPeerHeight, isUpToDate);

		if (isLite) {
			int targetHeight = Math.max(0, height);
			return new SyncProgress(targetHeight, 0, 100, SyncPhase.LITE);
		}

		SyncPhase syncPhase;
		if (isSynchronizing) {
			syncPhase = SyncPhase.SYNCHRONIZING;
		} else if (syncTargetHeight != null && height < syncTargetHeight) {
			syncPhase = SyncPhase.BEHIND;
		} else if (isUpToDate) {
			syncPhase = SyncPhase.SYNCED;
		} else if (numberOfConnections < minBlockchainPeers) {
			syncPhase = SyncPhase.CONNECTING;
		} else {
			syncPhase = SyncPhase.STALE;
		}

		Integer syncBlocksRemaining = null;
		Integer syncPercent = null;

		if (syncPhase == SyncPhase.SYNCED) {
			if (syncTargetHeight == null)
				syncTargetHeight = Math.max(0, height);

			syncBlocksRemaining = 0;
			syncPercent = 100;
		} else if (syncTargetHeight != null && (height < syncTargetHeight || isSynchronizing)) {
			syncBlocksRemaining = Math.max(0, syncTargetHeight - height);
			syncPercent = calculateSyncPercent(height, syncTargetHeight);

			if (isSynchronizing && height < syncTargetHeight)
				syncPercent = Math.min(syncPercent, 99);
		}

		return new SyncProgress(syncTargetHeight, syncBlocksRemaining, syncPercent, syncPhase);
	}

	public static PeerConnectionStats calculatePeerConnectionStats(int totalConnections, int outboundConnections,
			boolean inboundReachable, boolean listenSocketAvailable, boolean portMapped) {
		int boundedTotalConnections = Math.max(0, totalConnections);
		int boundedOutboundConnections = Math.max(0, Math.min(outboundConnections, boundedTotalConnections));
		int inboundConnections = boundedTotalConnections - boundedOutboundConnections;

		return new PeerConnectionStats(boundedTotalConnections, inboundConnections, boundedOutboundConnections,
				inboundReachable, listenSocketAvailable, portMapped);
	}

	static I2PLeaseSetLookupStatus toApiLeaseSetLookupStatus(I2PHealthTracker.LeaseSetLookupStatus status) {
		switch (status) {
			case RESOLVED:
				return I2PLeaseSetLookupStatus.RESOLVED;
			case NOT_RESOLVED:
				return I2PLeaseSetLookupStatus.NOT_RESOLVED;
			case UNKNOWN:
			default:
				return I2PLeaseSetLookupStatus.UNKNOWN;
		}
	}

	private static Integer chooseSyncTargetHeight(int height, Integer activeSyncTargetHeight, Integer bestPeerHeight,
			boolean isUpToDate) {
		Integer syncTargetHeight = null;

		if (activeSyncTargetHeight != null && activeSyncTargetHeight > 0) {
			syncTargetHeight = activeSyncTargetHeight;
		} else if (bestPeerHeight != null && bestPeerHeight > 0) {
			syncTargetHeight = bestPeerHeight;
		} else if (isUpToDate) {
			syncTargetHeight = height;
		}

		return syncTargetHeight != null ? Math.max(height, syncTargetHeight) : null;
	}

	private static int calculateSyncPercent(int height, int syncTargetHeight) {
		if (syncTargetHeight <= 0)
			return 0;

		long boundedHeight = Math.max(0, (long) height);
		return (int) Math.min(100, (boundedHeight * 100L) / syncTargetHeight);
	}

	private static Integer getBestPeerHeight(List<Peer> peers) {
		Integer bestPeerHeight = null;

		for (Peer peer : peers) {
			BlockSummaryData chainTipData = peer.getChainTipData();
			if (chainTipData == null)
				continue;

			if (bestPeerHeight == null || chainTipData.getHeight() > bestPeerHeight)
				bestPeerHeight = chainTipData.getHeight();
		}

		return bestPeerHeight;
	}

	private static int countOutboundConnections(Iterable<Peer> peers) {
		int outboundConnections = 0;

		for (Peer peer : peers)
			if (peer.isOutbound())
				outboundConnections++;

		return outboundConnections;
	}

	public static int countI2PConnections(Iterable<Peer> peers) {
		int i2pConnections = 0;

		for (Peer peer : peers)
			if (peer.getPeerData().getAddress().isI2P())
				i2pConnections++;

		return i2pConnections;
	}

	public static class PeerConnectionStats {
		public final int totalConnections;
		public final int inboundConnections;
		public final int outboundConnections;
		public final boolean inboundReachable;
		public final boolean listenSocketAvailable;
		public final boolean portMapped;

		private PeerConnectionStats(int totalConnections, int inboundConnections, int outboundConnections,
				boolean inboundReachable, boolean listenSocketAvailable, boolean portMapped) {
			this.totalConnections = totalConnections;
			this.inboundConnections = inboundConnections;
			this.outboundConnections = outboundConnections;
			this.inboundReachable = inboundReachable;
			this.listenSocketAvailable = listenSocketAvailable;
			this.portMapped = portMapped;
		}
	}

	public static class SyncProgress {
		public final Integer syncTargetHeight;
		public final Integer syncBlocksRemaining;
		public final Integer syncPercent;
		public final SyncPhase syncPhase;

		private SyncProgress(Integer syncTargetHeight, Integer syncBlocksRemaining, Integer syncPercent,
				SyncPhase syncPhase) {
			this.syncTargetHeight = syncTargetHeight;
			this.syncBlocksRemaining = syncBlocksRemaining;
			this.syncPercent = syncPercent;
			this.syncPhase = syncPhase;
		}
	}

}
