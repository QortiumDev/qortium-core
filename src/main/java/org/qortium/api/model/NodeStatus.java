package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.controller.Controller;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.controller.Synchronizer;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
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

	public final int numberOfDataConnections;

	public final int height;

	public NodeStatus() {
		this.isMintingPossible = OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures();

		Synchronizer synchronizer = Synchronizer.getInstance();
		Controller controller = Controller.getInstance();
		List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();

		this.isSynchronizing = synchronizer.isSynchronizing();
		this.numberOfConnections = handshakedPeers.size();

		this.numberOfDataConnections = NetworkData.getInstance().getImmutableHandshakedPeers().size();

		this.height = controller.getChainHeight();

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
