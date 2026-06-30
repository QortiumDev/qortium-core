package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.controller.ArchiveFastSyncManager;
import org.qortium.controller.ArchiveFastSyncManager.ArchiveReplayStatus;
import org.qortium.controller.Controller;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.controller.Synchronizer;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
import org.qortium.network.PeerList;
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
			description = "Whether this node currently appears reachable for inbound chain peer connections."
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
			description = "Whether this node currently appears reachable for inbound QDN/data peer connections."
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

	public final int height;

	@Schema(
			description = "Whether the node is currently replaying archived blocks during startup archive fast-sync."
	)
	public final boolean isArchiveFastSyncing;

	@Schema(
			description = "Current block height replayed by archive fast-sync. Null when archive fast-sync replay is inactive.",
			nullable = true
	)
	public final Integer archiveFastSyncHeight;

	@Schema(
			description = "Target block height for the current archive fast-sync replay. Null when archive fast-sync replay is inactive.",
			nullable = true
	)
	public final Integer archiveFastSyncTargetHeight;

	@Schema(
			description = "Archive fast-sync replay percentage for the current replay range. Null when archive fast-sync replay is inactive.",
			nullable = true
	)
	public final Integer archiveFastSyncPercent;

	public NodeStatus() {
		this.isMintingPossible = OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures();

		Synchronizer synchronizer = Synchronizer.getInstance();
		Controller controller = Controller.getInstance();
		ArchiveFastSyncManager archiveFastSyncManager = ArchiveFastSyncManager.getInstance();
		Network network = Network.getInstance();
		NetworkData networkData = NetworkData.getInstance();
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

		this.numberOfDataConnections = dataPeerStats.totalConnections;
		this.numberOfInboundDataConnections = dataPeerStats.inboundConnections;
		this.numberOfOutboundDataConnections = dataPeerStats.outboundConnections;
		this.isP2PInboundReachable = chainPeerStats.inboundReachable;
		this.isP2PListenSocketAvailable = chainPeerStats.listenSocketAvailable;
		this.isP2PPortMapped = chainPeerStats.portMapped;
		this.isQDNInboundReachable = dataPeerStats.inboundReachable;
		this.isQDNListenSocketAvailable = dataPeerStats.listenSocketAvailable;
		this.isQDNPortMapped = dataPeerStats.portMapped;

		this.height = controller.getChainHeight();

		SyncProgress syncProgress = calculateSyncProgress(this.height, synchronizer.getSyncTargetHeight(),
				this.isSynchronizing, getBestPeerHeight(handshakedPeers), controller.isUpToDate(),
				Settings.getInstance().isLite(), this.numberOfConnections, Settings.getInstance().getMinBlockchainPeers());

		this.syncPercent = syncProgress.syncPercent;
		this.syncTargetHeight = syncProgress.syncTargetHeight;
		this.syncBlocksRemaining = syncProgress.syncBlocksRemaining;
		this.syncPhase = syncProgress.syncPhase;

		ArchiveReplayStatus archiveReplayStatus = archiveFastSyncManager.getArchiveReplayStatus();
		this.isArchiveFastSyncing = archiveReplayStatus.isActive;
		this.archiveFastSyncHeight = archiveReplayStatus.isActive ? archiveReplayStatus.height : null;
		this.archiveFastSyncTargetHeight = archiveReplayStatus.isActive ? archiveReplayStatus.targetHeight : null;
		this.archiveFastSyncPercent = archiveReplayStatus.isActive ? archiveReplayStatus.percent : null;
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
