package org.qortium.crosschain.monero;

import java.util.*;
import java.util.function.LongSupplier;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

/**
 * Durable admission/completion protocol for the single-worker native adapter.
 * This class performs no JNI calls and exposes no REST resource. Work objects are private
 * in-process receipts, not reusable client tokens. The adapter must report worker
 * completion even after cancellation and must never relay without takeRelay().
 */
final class MoneroSendMachine {
    static final long QUOTE_MILLIS = 120_000;
    static final class Rejected extends RuntimeException { Rejected() { super("XMR send operation not admissible"); } }
    static final class Work {
        private final String id;
        private final boolean relay;
        private boolean taken;
        private Work(String id, boolean relay) { this.id = id; this.relay = relay; }
        @Override public String toString() { return "XMR worker receipt [redacted]"; }
    }
    record Admission(View view, Work work) {
        @Override public String toString() { return "XMR admission [redacted]"; }
    }
    /** Mint immediately before the serialized ordinary sync, complete only with that sync's local-wallet lookup. */
    static final class Sync {
        private Sync() { }
        @Override public String toString() { return "XMR reconciliation receipt"; }
    }
    private Sync syncing;
    synchronized Sync beginReconciliation(String expectedSession) {
        owner(expectedSession);
        if (!work.isEmpty() || syncing != null) throw new Rejected();
        reconciled = false;
        syncing = new Sync();
        return syncing;
    }
    synchronized void abandonReconciliation(Sync receipt, String expectedSession) {
        owner(expectedSession);
        if (receipt == null || receipt != syncing) throw new Rejected();
        syncing = null; reconciled = false;
    }
    /** Caller supplies only exact local-wallet observations after a fresh ordinary sync; never daemon absence as failure. */
    record Observation(String txid, boolean confirmed, long confirmations, boolean unlocked, boolean inPool) {
        Observation {
            hex(txid, 64);
            require(confirmations >= 0 && (confirmed ? confirmations > 0 && !inPool : confirmations == 0 && !unlocked));
        }
        @Override public String toString() { return "XMR observation [redacted]"; }
    }
    private final MoneroSendJournal journal;
    private final LongSupplier wall, monotonic;
    private final Map<String, Work> work = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private String session;
    // Every prepare/commit after confirmed history requires a freshly supplied same-worker observation batch.
    private boolean reconciled;
    private long reconciledAt;

    MoneroSendMachine(MoneroSendJournal journal, String session, LongSupplier wall, LongSupplier monotonic) {
        this.journal = journal; this.session = uuid(session); this.wall = wall; this.monotonic = monotonic;
        journal.claimMachine();
        Map<String, Entry> next = copy(); boolean changed = false;
        for (Entry entry : List.copyOf(next.values())) {
            State state = entry.state();
            if (state == State.PREPARING || state == State.PREPARED) { next.put(entry.operationId(), entry.state(State.EXPIRED)); changed = true; }
            else if (state == State.RELAYING || state == State.BROADCAST || state == State.CONFIRMED_WAIT) {
                next.put(entry.operationId(), entry.state(State.UNKNOWN)); changed = true;
            }
        }
        if (changed) journal.replace(next);
    }
    private Map<String, Entry> copy() { return new LinkedHashMap<>(journal.read().entries()); }
    private void save(Entry entry) { Map<String, Entry> next = copy(); next.put(entry.operationId(), entry); journal.replace(next); }
    private void owner(String expected) { journal.check(); if (!Objects.equals(session, expected)) throw new Rejected(); }
    private Entry get(String id) { Entry entry = journal.read().entries().get(id); if (entry == null) throw new Rejected(); return entry; }
    private boolean held() { return journal.read().entries().values().stream().anyMatch(e -> holds(e.state())); }
    private View view(Entry entry) {
        Request request = entry.request(); Candidate candidate = entry.candidate();
        return new View(entry.operationId(), entry.state(), entry.state() == State.PREPARED ? entry.quoteDigest() : null,
                request == null ? null : request.address(), request == null ? null : request.amountAtomic(),
                candidate == null ? null : candidate.feeAtomic(), candidate == null ? null : candidate.txid(), held(),
                entry.expiresAt(), entry.confirmations(), entry.unlocked());
    }
    synchronized View status(String id, String expectedSession) { owner(expectedSession); expire(); return view(get(id)); }
    synchronized boolean walletHeld(String expectedSession) { owner(expectedSession); expire(); return held(); }
    private void requireReconciliation() {
        if (journal.read().entries().values().stream().anyMatch(e -> e.state() == State.CONFIRMED) && (!reconciled || monotonic.getAsLong() - reconciledAt > 2_000_000_000L)) throw new Rejected();
        reconciled = false; // one admission only; observations cannot authorize a later commit too
    }
    synchronized Admission prepare(Request request, String expectedSession) {
        owner(expectedSession); expire();
        Entry existing = journal.read().entries().get(request.operationId());
        if (existing != null) {
            if (!existing.requestDigest().equals(request.digest())) throw new Rejected();
            return new Admission(view(existing), null);
        }
        if (syncing != null || held() || journal.read().entries().size() >= MAX_ENTRIES) throw new Rejected();
        requireReconciliation();
        long now = wall.getAsLong(); require(now >= 0);
        Entry entry = new Entry(request.operationId(), State.PREPARING, request.digest(), request, null, null,
                session, now, Math.addExact(now, QUOTE_MILLIS), null, 0, false);
        save(entry); // no worker receipt may escape until durability succeeds
        Work receipt = new Work(entry.operationId(), false); work.put(entry.operationId(), receipt);
        deadlines.put(entry.operationId(), monotonic.getAsLong() + QUOTE_MILLIS * 1_000_000L);
        return new Admission(view(entry), receipt);
    }
    private Entry receipt(Work receipt, boolean relay) {
        journal.check();
        if (receipt == null || receipt.relay != relay || work.get(receipt.id) != receipt) throw new Rejected();
        Entry entry = get(receipt.id);
        if (entry.state() != (relay ? State.RELAYING : State.PREPARING)) throw new Rejected();
        return entry;
    }
    synchronized View finishPreparation(Work receipt, Candidate candidate) {
        Entry entry = receipt(receipt, false);
        State terminal = entry.cancellation() != null ? entry.cancellation() : !entry.session().equals(session) ? State.CANCELLED
                : expired(entry) ? State.EXPIRED : candidate == null ? State.PREPARE_FAILED : null;
        Entry next;
        if (terminal != null) next = entry.state(terminal);
        else {
            // A mismatching adapter result is a pre-relay failure, never a changed quote.
            if (!entry.request().address().equals(candidate.address()) || !entry.request().amountAtomic().equals(candidate.amountAtomic()))
                next = entry.state(State.PREPARE_FAILED);
            else next = new Entry(entry.operationId(), State.PREPARED, entry.requestDigest(), entry.request(), candidate,
                    candidate.digest(), entry.session(), entry.createdAt(), entry.expiresAt(), null, 0, false);
        }
        save(next); work.remove(entry.operationId());
        if (tombstone(next.state())) deadlines.remove(entry.operationId());
        return view(next);
    }
    synchronized View cancel(String id, String expectedSession) {
        owner(expectedSession); expire(); return cancel(get(id), State.CANCELLED);
    }
    private View cancel(Entry entry, State terminal) {
        if (entry.state() == State.PREPARING) {
            // Keep the durable guard until the worker returns; cancellation is not proof that native work stopped.
            if (entry.cancellation() == null) save(new Entry(entry.operationId(), entry.state(), entry.requestDigest(), entry.request(), null,
                    null, entry.session(), entry.createdAt(), entry.expiresAt(), terminal, 0, false));
            return view(get(entry.operationId()));
        }
        if (entry.state() == State.PREPARED) {
            Entry next = entry.state(terminal); save(next); deadlines.remove(entry.operationId()); return view(next);
        }
        if (tombstone(entry.state())) return view(entry);
        throw new Rejected(); // accepted relay is not cancellable
    }
    synchronized void changeSession(String newSession) {
        journal.check(); uuid(newSession); expire(); session = newSession; reconciled = false; syncing = null;
        for (Entry entry : List.copyOf(journal.read().entries().values()))
            if (entry.state() == State.PREPARING || entry.state() == State.PREPARED) cancel(entry, State.CANCELLED);
    }
    private boolean expired(Entry entry) {
        Long deadline = deadlines.get(entry.operationId());
        return deadline == null || wall.getAsLong() < entry.createdAt() || wall.getAsLong() >= entry.expiresAt()
                || monotonic.getAsLong() - deadline >= 0;
    }
    private void expire() {
        for (Entry entry : List.copyOf(journal.read().entries().values()))
            if ((entry.state() == State.PREPARING || entry.state() == State.PREPARED) && expired(entry)) cancel(entry, State.EXPIRED);
    }
    synchronized Admission commit(String id, String quoteDigest, String expectedSession) {
        owner(expectedSession); expire(); Entry entry = get(id);
        if (entry.candidate() == null || !Objects.equals(entry.quoteDigest(), quoteDigest)) throw new Rejected();
        if (entry.state() != State.PREPARED) {
            if (entry.state() == State.RELAYING || entry.state() == State.BROADCAST || entry.state() == State.UNKNOWN
                    || entry.state() == State.CONFIRMED_WAIT || entry.state() == State.CONFIRMED) return new Admission(view(entry), null);
            throw new Rejected();
        }
        if (syncing != null || !entry.session().equals(session) || journal.read().entries().values().stream()
                .anyMatch(e -> !e.operationId().equals(id) && holds(e.state()))) throw new Rejected();
        requireReconciliation();
        Entry next = entry.state(State.RELAYING); save(next);
        Work receipt = new Work(id, true); work.put(id, receipt); deadlines.remove(id);
        return new Admission(view(next), receipt);
    }
    synchronized Candidate takeRelay(Work receipt) {
        Entry entry = receipt(receipt, true);
        if (receipt.taken) throw new Rejected(); receipt.taken = true;
        return entry.candidate(); // adapter-private; never a response payload
    }
    synchronized View finishRelay(Work receipt, String returnedHash) {
        Entry entry = receipt(receipt, true);
        if (!receipt.taken) throw new Rejected();
        Entry next = entry.state(entry.candidate().txid().equals(returnedHash) ? State.BROADCAST : State.UNKNOWN);
        save(next); work.remove(entry.operationId()); return view(next);
    }
    /** A timeout revokes future completion, but does not claim the native worker is idle. Adapter must still join it. */
    synchronized void interruptWork(Work receipt) {
        if (receipt == null) return;
        if (receipt.relay) uncertainRelay(receipt);
        else cancel(receipt(receipt, false), State.CANCELLED);
    }
    synchronized void uncertainRelay(Work receipt) {
        Entry entry = receipt(receipt, true); save(entry.state(State.UNKNOWN)); work.remove(entry.operationId());
    }
    synchronized void reconcile(Sync receipt, Map<String, Observation> observations, String expectedSession) {
        owner(expectedSession);
        if (receipt == null || receipt != syncing || !work.isEmpty()) throw new Rejected();
        syncing = null;
        // Sync itself can be long; only a current, single-use completion grants the short admission window.
        // The trusted adapter must derive observations from this receipt's sync, never a cached batch.
        Objects.requireNonNull(observations);
        Map<String, Entry> next = copy(); boolean rollback = false;
        for (Entry entry : List.copyOf(next.values())) {
            if (!(entry.state() == State.BROADCAST || entry.state() == State.UNKNOWN || entry.state() == State.CONFIRMED_WAIT
                    || entry.state() == State.CONFIRMED)) continue;
            Observation seen = observations.get(entry.operationId());
            State state = State.UNKNOWN;
            if (seen != null && entry.candidate().txid().equals(seen.txid())) {
                if (seen.confirmed())
                    state = seen.confirmations() >= 10 && seen.unlocked() ? State.CONFIRMED : State.CONFIRMED_WAIT;
                else if (seen.inPool()) state = State.BROADCAST;
            }
            if (entry.state() == State.CONFIRMED && state != State.CONFIRMED) rollback = true;
            next.put(entry.operationId(), state == State.UNKNOWN ? entry.state(state)
                    : entry.observed(state, seen.confirmations(), seen.unlocked()));
        }
        if (rollback) for (Entry entry : List.copyOf(next.values())) {
            if (entry.state() == State.PREPARED) next.put(entry.operationId(), entry.state(State.CANCELLED));
            if (entry.state() == State.PREPARING) next.put(entry.operationId(), new Entry(entry.operationId(), entry.state(), entry.requestDigest(),
                    entry.request(), null, null, entry.session(), entry.createdAt(), entry.expiresAt(), State.CANCELLED, 0, false));
        }
        journal.replace(next); reconciledAt = monotonic.getAsLong(); reconciled = true;
    }
}
