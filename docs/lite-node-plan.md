# Lite Node Plan

This document records the current lite-node direction for Qortium and the
recommended work needed before lite mode should be treated as reliable for
wallet or application use.

## Purpose

Qortium lite nodes should be able to join the normal peer network without
storing the full blockchain database. Instead of depending on separate lite
servers, a lite node should connect to ordinary peers and request the limited
derived data it needs from those peers.

The intended model is:

- lite nodes participate as limited-access peers
- full or otherwise data-capable peers answer supported lite-data requests
- lite nodes do not mint blocks or synchronize the full chain
- wallet and application APIs should fail clearly when trusted-enough peer data
  is unavailable

## Current State

The current implementation already has a useful skeleton:

- `Settings.lite` switches the node into lite mode.
- Lite mode skips block synchronization, minting, pruning, trimming, archiving,
  and some startup database checks.
- The peer protocol has request and response messages for account data, account
  balances, account names, individual names, and account transactions.
- `LiteNode` sends these requests to connected peers and returns the peer's
  response to API and account-balance callers.
- Full-node handlers in `Controller` answer the lite request messages from
  local repository data.

This is enough to prove the direction, but not enough to call lite mode safe or
complete.

## Findings

### Single-Peer Trust

`LiteNode` currently chooses one random handshaked peer and accepts that peer's
response if the message type matches. There is no retry set, quorum, chain-tip
consistency check, proof, or lite-serving capability check.

This means one dishonest or stale peer can lie about wallet-facing data such as
balances, names, or transaction history.

### Transaction Validation And Relay

Lite nodes currently bypass normal signature validation in the incoming
transaction importer and return `OK` early from transaction import validation
after basic timestamp and fee checks.

Lite nodes do not mint blocks, but they can still become weak transaction relays
unless lite-specific validation is tightened.

### Phase 1 Correctness Baseline

The initial review found several concrete correctness gaps. The first Phase 1
cleanup addresses these baseline items and keeps them here as the reason for
the change:

- `GET_NAME` serialized the uninitialized field instead of the constructor
  argument, so lite name lookup could not reliably request the intended name.
- The account-info API read local repository account data instead of
  using the existing lite account-data fetch path.
- Some lite API paths assumed peer fetches always return non-null data, which
  could turn a peer failure into a server-side null-pointer failure.
- Lite balance lookup could return zero when no peer data was available, which
  made an unavailable peer response look like a real empty balance.
- Lite transaction listing had nullable `reverse` handling and did client-side
  ordering after peer fetches.
- Lite account-name lookup ignored `limit`, `offset`, and `reverse` because the
  current peer message has no pagination fields.

### Missing Peer Capability

HELLO_V2 capabilities currently advertise QDN availability, but not lite-data
service availability. A lite node therefore cannot deliberately choose peers
that claim they can serve lite data, and it can accidentally ask another lite
node or unsuitable peer for derived data.

### Response Shaping, Privacy, And DoS

Account transaction responses are capped server-side, but the lite client can
loop until it has fetched all transactions when the API limit is zero. Account
name responses currently return all names for an owner.

Lite data requests also reveal the queried address or name to selected peers.
That is acceptable only if the product treats lite mode as peer-visible and
documents the privacy tradeoff.

## Recommended Work

### Phase 1: Correctness Baseline

Fix the concrete bugs before expanding lite mode:

- fix `GET_NAME` serialization and add a round-trip test
- route account-info lookup through `LiteNode.fetchAccountData()` in lite mode
- make lite balance lookup fail clearly when peer data is unavailable
- normalize nullable lite API options before use
- return clear repository/API errors when peer data is unavailable
- make lite name and transaction API behavior match non-lite pagination and
  ordering semantics where the current wire messages can support it
- add focused tests for the lite message classes and lite API branches

### Phase 2: Peer Selection And Service Limits

Make peer-backed data requests intentional and bounded:

- advertise a lite-data-serving capability during HELLO_V2 for nodes that can
  answer lite requests
- filter lite-data requests to peers that advertise the capability and are not
  known stale, misbehaving, too old, genesis-only, or on an inferior tip
- retry across a small shuffled peer set before failing
- add server-side limits to every lite-data response shape
- add request logging and counters so lite-data load can be observed

The first Phase 2 implementation treats every non-lite node as a lite-data
server, advertises that role as `LITE_DATA = 1`, and makes lite nodes require
that capability before sending peer-backed account, balance, name, or
transaction-history requests. Account-name and account-transaction lite
responses are capped at 100 records per peer response until protocol-level
pagination is added for every response shape.

### Phase 3: Multi-Peer Consistency

Reduce single-peer trust for wallet-facing data:

- request critical data from multiple independent peers
- compare responses and reject conflicting answers
- prefer peers whose chain tips agree with the majority of connected peers
- expose an internal result state that distinguishes unavailable, conflicted,
  and agreed data
- keep the API response behavior conservative when peers disagree

### Phase 4: Proof Or Checkpoint Anchoring

If lite mode is expected to support higher-trust wallet decisions, add a
stronger data model:

- define which lite responses need cryptographic or checkpoint-backed evidence
- anchor responses to a chain tip or checkpoint that the lite node can compare
  across peers
- avoid presenting peer-reported balances or history as fully verified unless
  the response has enough evidence for that claim

This phase should be designed separately because it affects protocol shape,
storage assumptions, and wallet trust messaging.

## Acceptance Criteria

Lite mode should not be treated as ready until:

- known message serialization and API null-handling bugs are fixed
- lite request and response message round trips are covered by tests
- lite API branches have focused tests for success, no-peer, and unknown-data
  cases
- lite nodes do not rely on one arbitrary peer for critical wallet-facing data
- lite data requests use explicit peer capability and retry behavior
- server-side lite responses are bounded
- user-facing or API-facing behavior makes peer-reported trust limits clear

## Open Decisions

- Whether phase 3 quorum should require exact response equality or allow a
  majority result when one peer fails to respond.
- Whether lite nodes should store any short-lived peer-response cache.
- Whether lite mode should support transaction creation flows before proof or
  checkpoint anchoring exists.
- How much privacy warning should be exposed to wallets and applications that
  use lite-node APIs.
