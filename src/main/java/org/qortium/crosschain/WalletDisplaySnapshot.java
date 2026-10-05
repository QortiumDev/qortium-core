package org.qortium.crosschain;

/** Last successfully observed data for display only; never financial readiness or custody authority. */
public record WalletDisplaySnapshot<T>(T data, long updatedAt) { }
