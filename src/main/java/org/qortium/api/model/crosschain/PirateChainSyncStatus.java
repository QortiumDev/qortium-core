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
}
