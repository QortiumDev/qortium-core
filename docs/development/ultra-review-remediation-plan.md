# Ultra Review Remediation Plan

Status: active planning note  
Opened: 2026-08-13  
Review baseline: `8e8d2a24c4739c546e7dfb21b2c232c55fcda2ad`  
Source review SHA-256: `b04d140acc8439ed3f36c86ca66aff3a36a98e5864fc0734992b60fc19314f0f`

This is the tracked execution plan for the 2026-08-12 ultra review of Qortium
Core and the independent source verification completed on 2026-08-13. It keeps
the original capped findings, the lower-severity items cut from that cap, and
the verification nuances in one place. The source review is retained privately
outside this repository; this document contains no secrets or exploit recipe.

The plan is intentionally split into small reviewable tranches. Consensus,
repository replacement, peer-driven orphaning, transport identity, and public
API boundaries must not be bundled with unrelated cleanup. Each completed
tranche updates this file with its commit and verification evidence.

## Status And Acceptance Rules

- `Planned`: verified and assigned to a tranche, but no repair is accepted.
- `In progress`: the bounded tranche is being implemented or validated.
- `Complete`: the repair, regression tests, changelog, and required validation
  are committed. A local commit is not proof of release or deployment.
- `Hardening`: the reported exploit does not hold as shipped, but an invariant
  or defense-in-depth improvement remains planned.
- `Decision`: implementation depends on an explicit compatibility or policy
  choice recorded in this plan before code changes begin.

Every code tranche requires focused tests with `-DskipTests=false`, a serialized
full suite, `git diff --check`, an independent review of security- or
consensus-sensitive changes, and a matching `QORTIUM-CHANGELOG.md` entry. Maven
runs must never overlap in the same checkout because they share `target/` and
`dbtest/`.

## Tranches

| Tranche | Scope | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| T1 | Use the locally derived execution height for all three AT chain-query activation gates; add hostile claimed-height tests. | Complete | None | `fix(at): use local height for chain-query activation gates`; adversarial red test failed 2/2 before the repair; focused tests passed 29/29; full suite passed 3,004 with 68 skips; independent Codex review passed |
| T2 | Repair mixed-peer recovery-mode exit and genesis-height peer-ahead mint deferral, then contain the peer-claim watchdog by disabling it outside an explicit development profile. | Planned | T1 accepted and committed | State-transition, genesis-height minting-policy, and watchdog default/profile tests; coordinated rollout and live-height verification remain a release condition |
| T3 | Redesign watchdog orphaning around validated, locally anchored alternative-block evidence. | Planned | T2 containment | Adversarial peer-claim tests and transactional orphan/adoption tests |
| T4 | Make bootstrap acquisition and repository replacement fail-safe. | Planned | Operator trust/provenance decision | HTTPS/provenance, digest, truncation, staging, validation, rollback tests |
| T5 | Make generic defaults, Docker, Previewnet profiles, ports, seed lists, and chain identities coherent, including `.env.example`. | Planned | Decide whether generic Docker targets no network or explicit Previewnet | Cross-profile and container configuration invariants |
| T6 | Enforce transport-scoped QDN advertisements and chain/data HELLO identities. | Planned | None | Request/response and chain/data x IP/I2P matrix tests |
| T7 | Repair adaptive networking: per-peer/coalesced AIMD loss, a QDN-specific catch-up signal, a bounded GET_BLOCKS budget, and one in-flight ping per peer. | Planned | T2 recovery-state semantics for the catch-up signal | Deterministic loss, peer isolation, deadline, cancellation, and ping-order tests |
| T8 | Repository and API correctness: archive temp filtering, chat fee persistence, websocket envelope filtering, and malformed PEERS rejection. | Planned | None | Focused repository, serialization, websocket, and parser tests |
| T9 | Configuration and documentation hygiene: trigger registry/testnet parity, public-write classifier invariants, and missing changelog entries. | Decision | Trigger compatibility design | Config coverage, full handler-chain tests, docs reconciliation |
| T10 | Restrict persistent API TLS keystore permissions to the owning account. | Planned | None | Creation and existing-file permission tests across supported filesystems |

## Finding Ledger

| ID | Verified disposition | Priority | Tranche | Status | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| C-01 | `ChainATAPI` selects three opcode gates from peer-supplied `blockHeight` instead of the local parent height plus one. | Critical | T1 | Complete | Both height-mismatch directions pass at the exact trigger boundary for all three opcodes; existing bytecode-level pre-trigger and at-trigger tests pass |
| C-02 | Unknown feature-trigger names are accepted and silently unused. This is currently intentional forward-compatibility behavior, and `featureTriggers` is excluded from the chain-config handshake hash. | High safety / design | T9 | Decision | Decide strict registry plus activation-schedule compatibility mechanism |
| C-03 | A genesis-height node can bypass stale-tip protections and mint a competing block 2 despite fresh higher peers. This is local fork/liveness policy, not remote block-validation bypass. | Medium | T2 | Planned | Pending |
| N-01 | Java defaults combine mainnet chain identity with Previewnet seed addresses; an empty/default configuration cannot handshake with those seeds. | High | T5 | Planned | Pending |
| N-02 | The recovery watchdog can orphan up to three local tip blocks based on unverified peer tip and archive-capability claims. Peer connections are handshaked, but the asserted chain evidence is not authenticated. | High | T2/T3 | Planned | Pending |
| N-03 | Recovery mode remains active with a mixed fresh/stale peer set because exit requires every retained peer to be recent. | High | T2 | Planned | Pending |
| N-04 | GET_BLOCKS auto-degrade can spend about seven full batch timeouts on one dead peer before fallback. The loop is bounded and interruptible, but monopolizes synchronization and delays the watchdog. | Medium | T7 | Planned | Pending |
| N-05 | QDN yielding uses `Controller.isUpToDate()`, so a fresh node below the minting/sync peer quorum is permanently capped as if it were catching up. | Medium | T7 | Planned | Pending |
| N-06 | AIMD halves a process-global window once per expired chunk-map entry, allowing one stalled peer to collapse batching for all transfers. | High | T7 | Planned | Pending |
| N-07 | `peerPingTimeoutMillis` may exceed the fixed ping interval, permitting overlapping tasks and out-of-order consecutive-miss accounting. | Medium | T7 | Planned | Pending |
| S-01 | Bootstrap download does not verify trustworthy integrity/provenance and deletes the live repository before extraction or replacement validation. | High | T4 | Planned | Pending |
| S-02 | Public-write protection classifies several endpoints by exact path. The reported anonymous bypass is false for shipped profiles because the preceding access handler exact-matches the same path; shared classification remains necessary defense-in-depth. | Hardening | T9 | Hardening | Pending |
| S-03 | NAT/QDN request and response paths can disclose an I2P data destination to a clearnet peer, correlating IP and I2P identities. | High privacy | T6 | Planned | Pending |
| S-04 | Data-layer I2P HELLO messages expose the separate chain-layer I2P destination because capability construction knows transport but not network layer. | High privacy | T6 | Planned | Pending |
| S-05 | The persistent API TLS PKCS12 keystore is written with ambient umask permissions rather than owner-only permissions. The inspected managed runtime file was mode `0660`. | Medium | T10 | Planned | Pending |
| R-01 | Crash-left `.<start>-<end>.dat.tmp` files cause `BlockArchiveReader` filename parsing to throw and break archive reads. | High correctness | T8 | Planned | Pending |
| R-02 | Chat-store reconstruction replaces the signed transaction fee with zero, so a stored nonzero-fee CHAT cannot be faithfully re-served. | Medium correctness | T8 | Planned | Pending |
| R-03 | Group-chat history filters private control envelopes, but websocket live push forwards them as ordinary group messages. | Medium privacy/API | T8 | Planned | Pending |
| R-04 | A zero-entry PEERS message reaches `peerAddresses.get(0)`, producing repeated exception logging while leaving the peer connected. | Low | T8 | Planned | Pending |
| T-01 | The manual local-testnet profile omits the current feature-trigger schedule and therefore exercises different consensus behavior from Previewnet. | Medium test fidelity | T9 | Planned | Pending |
| D-01 | `.env.example` still maps legacy `1239x` ports while current generic Core defaults and Compose fallbacks use `1489x`. | Medium Docker | T5 | Planned | Pending |
| D-02 | The three adaptive-networking merges lack their required human-readable changelog entries. | Low documentation | T9 | Planned | Pending |

## Compatibility Decisions Still Required

### Feature-trigger validation

Rejecting unknown trigger names prevents misspellings, but it intentionally
reverses an existing forward-compatible configuration contract. More
importantly, it does not detect an omitted known trigger or a different height:
the whole `featureTriggers` map is currently excluded from the chain-config hash.
T9 must therefore decide both parts together:

1. whether this release line accepts only a registered set of trigger names;
2. how peers compare the active activation schedule without making future
   scheduling changes impossible.

### Generic Docker network target

An empty settings file must not silently mix identities. T5 must explicitly
choose whether generic Docker starts with no bootstrap network or starts an
explicit Previewnet profile. Ports, health checks, generated settings, chain
configuration, and seeds then move together.

### Bootstrap provenance

A checksum fetched from the same compromised server detects accidental damage
but does not authenticate the archive. Before T4, choose a trust root such as a
pinned release digest, signed manifest, or explicit operator-supplied digest.

## Current Work Boundary

Only T1 is authorized in the first implementation tranche. No recovery,
bootstrap, Docker, networking, API, testnet, release, deployment, or publication
changes are part of T1.
