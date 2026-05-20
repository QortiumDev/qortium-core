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
- `LiteNode` sends these requests to capable connected peers and requires
  matching peer responses before returning agreed data to API and
  account-balance callers.
- Lite-data responses now include `DATA` or `UNKNOWN` status plus a block
  height, block signature, and timestamp anchor. Lite nodes require agreement
  on both the data and that anchor.
- Full-node handlers in `Controller` answer the lite request messages from
  local repository data.

This is enough to prove the direction, but not enough to call lite mode safe or
complete.

## Findings

### Single-Peer Trust

`LiteNode` now chooses from a small shuffled set of peers that advertise
lite-data service, pass basic suitability filters, and preferably report the
same chain tip as at least one other capable peer. It requires two matching
usable responses before treating peer-backed account, balance, name, or
transaction-history data as agreed.

This removes the old first-peer-wins behavior and now prevents stale data and
current data from being treated as the same answer. It is still not a full
proof model. Two stale or dishonest peers can still agree on bad wallet-facing
data until later state-root proof work gives lite nodes a way to verify answers
cryptographically.

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

### Peer Capability Baseline

HELLO_V2 now advertises `LITE_DATA = 2` from non-lite nodes. Lite nodes require
that capability before sending peer-backed account, balance, name, or
transaction-history requests, so they no longer ask arbitrary peers or other
lite nodes for derived data.

### Response Shaping, Privacy, And DoS

Account transaction and account-name peer responses are capped server-side.
Lite transaction-history requests also have a total fetch cap when the API asks
for an unlimited result set.

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
server, advertises that role as `LITE_DATA = 2`, and makes lite nodes require
that capability before sending peer-backed account, balance, name, or
transaction-history requests. Account-name and account-transaction lite
responses are capped at 100 records per peer response until protocol-level
pagination is added for every response shape. Lite transaction-history requests
are capped at 500 total records per API call when the caller does not request a
smaller limit. `LiteNode` also tracks internal counters for request attempts,
empty or unexpected peer responses, successful responses, interruptions, and
cases where no capable peers are available.

### Phase 3: Multi-Peer Consistency

Reduce single-peer trust for wallet-facing data:

- request critical data from multiple independent peers
- compare responses and reject conflicting answers
- prefer peers whose chain tips agree with the majority of connected peers
- expose an internal result state that distinguishes unavailable, conflicted,
  and agreed data
- keep the API response behavior conservative when peers disagree

The first Phase 3 implementation requests each lite-data item from up to three
capable peers and requires two matching responses. Matching data responses are
returned as agreed data, no usable agreement is reported as unavailable, and
disagreeing usable responses are reported as conflicted. Lite API endpoints map
unavailable peer data to the existing no-reply error and conflicting peer data
to a dedicated conflict error, while agreed unknown data follows each endpoint's
normal unknown-data behavior.

The final Phase 3 cleanup also prefers the largest unique group of capable
peers that report the same chain tip, when that group is large enough to meet
the two-peer agreement requirement. Ambiguous or too-small chain-tip groups
fall back to the full eligible peer set.

### Phase 4: Proof Or Checkpoint Anchoring

If lite mode is expected to support higher-trust wallet decisions, add a
stronger data model:

- define which lite responses need cryptographic or checkpoint-backed evidence
- anchor responses to a chain tip or checkpoint that the lite node can compare
  across peers
- avoid presenting peer-reported balances or history as fully verified unless
  the response has enough evidence for that claim

The Phase 4 direction is defined in `docs/lite-node/lite-node-proof-anchoring.md`. The
first Phase 4 implementation anchors lite responses to the serving peer's
current chain tip and makes agreement include that anchor. The next larger
milestone is the state-root consensus design in
`docs/lite-node/lite-node-state-root-design.md`, followed by implementation of committed
roots and proof-bearing lite responses.

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

- Whether lite nodes should store any short-lived peer-response cache.
- Whether lite mode should support transaction creation flows before proof or
  checkpoint anchoring exists.
- How much privacy warning should be exposed to wallets and applications that
  use lite-node APIs.
