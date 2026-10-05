package org.qortium.crosschain;

/** Cached display diagnostics only. Never native access, session authority or spending readiness. */
public record WalletReadStatus(State state, Phase phase, Long retryAt) {
    public enum State { IDLE, IN_FLIGHT, OVERDUE, RETRY_SCHEDULED }
    public enum Phase { CHECK, SYNC, DAEMON, HISTORY, SAVE, BALANCE }
}
