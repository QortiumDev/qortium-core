package org.qortium.network.i2p;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-lifetime I2P health evidence owned by a logical network (chain or QDN/data).
 *
 * <p>The owner retains one tracker while individual {@link SamSession} providers are replaced during
 * automatic recovery. This keeps the last LeaseSet self-check result and last successful inbound
 * protocol handshake visible while a failed session is being rebuilt.</p>
 */
public final class I2PHealthTracker {

	public enum LeaseSetLookupStatus {
		UNKNOWN,
		RESOLVED,
		NOT_RESOLVED
	}

	public static final class LeaseSetLookupEvidence {
		public final LeaseSetLookupStatus status;
		public final Long timestamp;

		private LeaseSetLookupEvidence(LeaseSetLookupStatus status, Long timestamp) {
			this.status = status;
			this.timestamp = timestamp;
		}
	}

	private final AtomicReference<LeaseSetLookupEvidence> leaseSetLookupEvidence =
			new AtomicReference<>(new LeaseSetLookupEvidence(LeaseSetLookupStatus.UNKNOWN, null));
	private final AtomicLong lastInboundHandshakeTimestamp = new AtomicLong(-1L);
	private final Runnable onChange;

	public I2PHealthTracker() {
		this(() -> { });
	}

	public I2PHealthTracker(Runnable onChange) {
		this.onChange = Objects.requireNonNull(onChange, "onChange");
	}

	/** Record the concluded result of one local-router LeaseSet self-lookup. */
	public void recordLeaseSetLookupStatus(LeaseSetLookupStatus status) {
		this.leaseSetLookupEvidence.set(new LeaseSetLookupEvidence(status, System.currentTimeMillis()));
		this.onChange.run();
	}

	/** Record a successfully completed inbound Qortium handshake over this I2P destination. */
	public void recordInboundHandshake() {
		this.lastInboundHandshakeTimestamp.set(System.currentTimeMillis());
		this.onChange.run();
	}

	public LeaseSetLookupEvidence getLeaseSetLookupEvidence() {
		return this.leaseSetLookupEvidence.get();
	}

	public Long getLastInboundHandshakeTimestamp() {
		return nullableTimestamp(this.lastInboundHandshakeTimestamp.get());
	}

	private static Long nullableTimestamp(long timestamp) {
		return timestamp >= 0L ? timestamp : null;
	}
}
