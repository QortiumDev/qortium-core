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
| T2 | Repair mixed-peer recovery-mode exit and genesis-height peer-ahead mint deferral, then contain the peer-claim watchdog by disabling it outside an explicit development profile. | Complete | T1 accepted and committed | Six implementation commits plus focused tests passed 42/42 and the serialized full suite passed 3,020 with 68 skips; `git diff --check` and three independent Codex reviews passed. Not released or deployed; coordinated rollout and live-height verification remain a release condition |
| T3 | Retire automatic peer-claim orphaning while retaining both old setting names as permanently disabled compatibility inputs. | Complete | T2 containment | `security(sync): retire peer-claim orphaning` plus the two-key rollback follow-up; red tests failed 2/4 before retirement and 1/1 before rollback repair; focused tests passed 30/30; serialized full suite passed 3,008 with 68 skips; `git diff --check` and three independent Codex reviews passed. Not released or deployed |
| T4 | Retire the dormant hosted whole-database bootstrap importer while preserving checkpoint-anchored peer archive fast-sync and the non-replacement local archive creation/validation tools. | In progress | None | Legacy-setting, API/tray, exporter, archive-fast-sync, managed upgrade/rollback, focused/full-suite, and independent-review evidence |
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
| C-03 | A genesis-height node can bypass stale-tip protections and mint a competing block 2 despite fresh higher peers. This is local fork/liveness policy, not remote block-validation bypass. | Medium | T2 | Complete | Recent-higher, equal, stale, missing-signature-field, old-version, and post-genesis cases pass. Genesis discovery retains a peer whose later-chain tip signer is not yet known locally while sequential block validation remains authoritative. The advertisement remains unverified and only defers local minting |
| N-01 | Java defaults combine mainnet chain identity with Previewnet seed addresses; an empty/default configuration cannot handshake with those seeds. | High | T5 | Planned | Pending |
| N-02 | The recovery watchdog can orphan up to three local tip blocks based on unverified peer tip and archive-capability claims. Peer connections are handshaked, but the asserted chain evidence is not authenticated. | High | T2/T3 | Complete | Automatic peer-claim orphaning was removed rather than replaced. Both former enable settings remain accepted but always evaluate false; all managed profiles declare both false; managed upgrade and rollback force and preserve both false values. Peer height, freshness, quorum, and archive claims cannot authorize local block deletion |
| N-03 | Recovery mode remains active with a mixed fresh/stale peer set because exit requires every retained peer to be recent. | High | T2 | Complete | Mixed and fresh peer sets exit to recent-only selection in the same attempt; stale-only recovery, timeout-gated entry, and no-handshake behavior pass |
| N-04 | GET_BLOCKS auto-degrade can spend about seven full batch timeouts on one dead peer before fallback. The loop is bounded and interruptible, but monopolizes the Synchronizer thread and delays fallback or later synchronization work. | Medium | T7 | Planned | Pending |
| N-05 | QDN yielding uses `Controller.isUpToDate()`, so a fresh node below the minting/sync peer quorum is permanently capped as if it were catching up. | Medium | T7 | Planned | Pending |
| N-06 | AIMD halves a process-global window once per expired chunk-map entry, allowing one stalled peer to collapse batching for all transfers. | High | T7 | Planned | Pending |
| N-07 | `peerPingTimeoutMillis` may exceed the fixed ping interval, permitting overlapping tasks and out-of-order consecutive-miss accounting. | Medium | T7 | Planned | Pending |
| S-01 | The dormant hosted whole-database bootstrap path accepts an unauthenticated archive and deletes the live repository before extraction or replacement validation. Qortium operates no such bootstrap servers; checkpoint-anchored peer archive fast-sync is a separate content-addressed replay path. | High | T4 | In progress | Retire the hosted importer and every automatic/manual replacement entry point; keep legacy settings inert and downgrade-safe; preserve archive fast-sync unchanged |
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

## Adjacent Hardening Discovered During Remediation

### A-01 — Normal fork-reorganization failure atomicity

Status: unassigned; requires a separately approved design tranche.

During the T3 review, `syncToPeerChain()` was found to commit each local orphan
before it state-validates and commits the fetched alternative blocks
(`Synchronizer.java` around lines 1710-1760). An invalid later block, shutdown,
or processing failure can therefore leave the repository at the common block or
on a partially adopted chain. Existing block-processing callbacks,
notifications, caches, and separate-repository writes mean a savepoint wrapper
alone is not proof of atomicity. T3 does not change or claim to repair the
canonical synchronization path; this item needs its own failure-atomic adoption
design and transactional regression tests.

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

### Legacy hosted bootstrap retirement

The reviewed `.7z` downloader/importer is not Qortium's active bootstrap
architecture: Qortium operates no hosted whole-database bootstrap servers, all
shipped profiles disable that path, and fresh Preview nodes use peer-served
archive chunks bound to a release-pinned checkpoint and replayed through block
validation. T4 therefore removes the dormant hosted importer instead of
introducing a new snapshot publisher, signing key, or trust root. Local archive
creation and validation remain available as operator utilities that do not
acquire or replace a remote repository, but creation still performs its existing
temporary live-repository data preparation and restoration.

## Current Work Boundary

T4 is active and is limited to retiring hosted whole-database acquisition and
replacement, making its legacy configuration inert and rollback-safe, and
preserving checkpoint-anchored archive fast-sync. T3 is complete in source and
local validation but is not released or deployed. T4 does not authorize a new
bootstrap service or signing key, changes to checkpoint or replay consensus
rules, release/deployment work, or the separately unassigned A-01 normal-reorg
atomicity design.

T3 was completed by `security(sync): retire peer-claim orphaning` and
`fix(settings): keep retired orphan flags disabled on rollback`. The autonomous
orphan path and its state were removed; both compatibility inputs always
evaluate false; all managed profiles declare both false; and managed upgrades
plus older-release rollbacks preserve both safe values. Red tests failed 2/4
before retirement and 1/1 before the rollback repair. The focused five-class
suite passed 30/30, the serialized full suite passed 3,008 tests with 68 skips,
`git diff --check` passed, and three independent Codex reviews found no remaining
blocker. These results do not claim release, deployment, live-network
verification, or failure atomicity in the unchanged normal reorganization path.

T2 was completed by `security(sync): disable peer-claim recovery watchdog by
default`, `fix(sync): exit recovery mode when a recent peer returns`,
`fix(minting): defer genesis minting to a fresh higher peer`, and the corrective
follow-ups `fix(settings): preserve watchdog disablement across rollback`,
`fix(sync): allow genesis discovery before signer state exists`, and
`test(sync): cover recovery entry state transitions`. The focused six-class
suite passed 42/42, the serialized full suite passed 3,020 tests with 68 skips,
`git diff --check` passed, and three independent Codex reviews found no remaining
blocker. These results do not claim release, deployment, or live-network
verification.
