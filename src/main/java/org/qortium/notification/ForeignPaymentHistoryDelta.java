package org.qortium.notification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Tracks transaction hashes already present in one ElectrumX scripthash history. */
final class ForeignPaymentHistoryDelta {
	private final int maximumSeenTransactionHashes;

	static final class Entry {
		final String txHash;
		final int height;

		Entry(String txHash, int height) {
			this.txHash = Objects.requireNonNull(txHash);
			this.height = height;
		}
	}

	private final Set<String> seenTransactionHashes = new LinkedHashSet<>();

	ForeignPaymentHistoryDelta(int maximumSeenTransactionHashes) {
		if (maximumSeenTransactionHashes < 1)
			throw new IllegalArgumentException("Maximum seen transaction hashes must be positive");
		this.maximumSeenTransactionHashes = maximumSeenTransactionHashes;
	}

	void baseline(Collection<Entry> entries) {
		for (Entry entry : entries)
			markSeen(entry.txHash);
	}

	/** Returns unseen candidates without marking them, so failed transaction fetches can be retried. */
	List<Entry> candidates(Collection<Entry> entries) {
		List<Entry> added = new ArrayList<>();
		for (Entry entry : entries)
			if (!this.seenTransactionHashes.contains(entry.txHash))
				added.add(entry);

		return added;
	}

	void markSeen(String transactionHash) {
		if (!this.seenTransactionHashes.add(transactionHash))
			return;

		while (this.seenTransactionHashes.size() > this.maximumSeenTransactionHashes) {
			Iterator<String> iterator = this.seenTransactionHashes.iterator();
			iterator.next();
			iterator.remove();
		}
	}

	int size() {
		return this.seenTransactionHashes.size();
	}
}
