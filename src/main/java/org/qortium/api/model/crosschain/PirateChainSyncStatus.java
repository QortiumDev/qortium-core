package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(description = "Pirate wallet synchronization state")
public class PirateChainSyncStatus {
	public enum State {
		DISABLED,
		LOADING,
		SYNCHRONIZING,
		DEGRADED,
		READY
	}

	@Schema(description = "Stable wallet state")
	public State state;

	@Schema(description = "Human-readable status matching the legacy plain-text response")
	public String message;

	@Schema(description = "Blocks completed in the active synchronization range", nullable = true)
	public Long syncedBlocks;

	@Schema(description = "Total blocks in the active synchronization range", nullable = true)
	public Long totalBlocks;

	@Schema(description = "Whether Core must restart before native wallet operations can resume")
	public boolean restartRequired;

	@Schema(description = "Verified-import recovery marker: PENDING (durable rescan owed, not running), "
			+ "RECOVERING (native replay active), or RECOVERED (completed this runtime). OMITTED when "
			+ "recovery is not involved. While PENDING or RECOVERING, balances and histories are not "
			+ "final and the wallet reports itself unsynchronized.", nullable = true)
	public String recoveryState;

	@Schema(description = "Absolute wallet-scanned chain height, or null when unknown. Never a "
			+ "reinterpretation of the relative syncedBlocks/totalBlocks in-flight counters.", nullable = true)
	public Long scannedHeight;

	@Schema(description = "Absolute chain tip height, or null when unknown.", nullable = true)
	public Long tipHeight;

	@Schema(description = "Total ARRR balance in 8-decimal atomic units, or null when unknown.", nullable = true)
	public String totalBalanceAtomic;

	@Schema(description = "Verified/spendable ARRR balance in 8-decimal atomic units, or null when "
			+ "unknown (never a silent copy of the total balance).", nullable = true)
	public String verifiedBalanceAtomic;

	@Schema(description = "Epoch-millisecond timestamp this snapshot was observed.")
	public long observedAt;

	@Schema(description = "True when this snapshot is a cached reuse rather than a fresh observation: "
			+ "produced while the native wallet lane was busy with other work, or kept after a failed "
			+ "refresh attempt. A stale snapshot's state can never be READY.")
	public boolean stale;

	@Schema(description = "Active wallet backend: \"legacy\" or \"unified\". Reflects Core's current "
			+ "setting truthfully; never changes automatically.")
	public String backendMode;

	@Schema(description = "Base58(SHA-256(entropy)) identity of the wallet this snapshot actually "
			+ "belongs to - the same hash Core uses to name its on-disk wallet directories. NOT the "
			+ "entropy itself. Callers should assert this matches the hash of the entropy they supplied "
			+ "before trusting the snapshot.", nullable = true)
	public String walletIdentityHash;

	@Schema(description = "Sanitized last-error detail for a degraded/failed native wallet lane: no "
			+ "paths, no entropy, no stack trace. OMITTED when there is no recent error.", nullable = true)
	public LastError lastError;

	public PirateChainSyncStatus() {
	}

	public PirateChainSyncStatus(State state, String message, Long syncedBlocks, Long totalBlocks,
			boolean restartRequired) {
		this(state, message, syncedBlocks, totalBlocks, restartRequired, null);
	}

	public PirateChainSyncStatus(State state, String message, Long syncedBlocks, Long totalBlocks,
			boolean restartRequired, String recoveryState) {
		this.state = state;
		this.message = message;
		this.syncedBlocks = syncedBlocks;
		this.totalBlocks = totalBlocks;
		this.restartRequired = restartRequired;
		this.recoveryState = recoveryState;
	}

	public PirateChainSyncStatus(State state, String message, Long syncedBlocks, Long totalBlocks,
			boolean restartRequired, String recoveryState, Long scannedHeight, Long tipHeight,
			String totalBalanceAtomic, String verifiedBalanceAtomic, long observedAt, boolean stale,
			String backendMode, String walletIdentityHash, LastError lastError) {
		this.state = state;
		this.message = message;
		this.syncedBlocks = syncedBlocks;
		this.totalBlocks = totalBlocks;
		this.restartRequired = restartRequired;
		this.recoveryState = recoveryState;
		this.scannedHeight = scannedHeight;
		this.tipHeight = tipHeight;
		this.totalBalanceAtomic = totalBalanceAtomic;
		this.verifiedBalanceAtomic = verifiedBalanceAtomic;
		this.observedAt = observedAt;
		this.stale = stale;
		this.backendMode = backendMode;
		this.walletIdentityHash = walletIdentityHash;
		this.lastError = lastError;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	@Schema(description = "Sanitized last-error detail: a stable machine-readable code plus a "
			+ "human-readable message, neither of which ever contains paths, entropy or a stack trace.")
	public static class LastError {
		public String code;
		public String message;

		public LastError() {
		}

		public LastError(String code, String message) {
			this.code = code;
			this.message = message;
		}
	}
}
