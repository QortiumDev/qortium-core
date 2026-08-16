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
| T4 | Retire the dormant hosted whole-database bootstrap importer while preserving checkpoint-anchored peer archive fast-sync and the non-replacement local archive creation/validation tools. | Complete | None | Four implementation commits plus the tracked start; focused tests passed 95/95; clean serialized full suite passed 2,998 with 67 skips; packaged JAR contains archive fast-sync/local export but neither deleted importer class; `git diff --check` and three independent Codex reviews passed. Not released or deployed |
| T5 | Make generic defaults, Docker, Previewnet profiles, ports, seed lists, and chain identities coherent, including `.env.example`. | Complete | Explicit Docker Previewnet target approved | Six bounded implementation, test, and operator-documentation commits following the tracked start; focused configuration tests passed 41/41; shell syntax, fresh Preview and retained-generic Compose renders passed; the clean serialized full suite passed 3,008 with 67 skips; `git diff --check` and three independent Codex reviews passed. Not released or deployed; daemon-backed image smoke was unavailable to this user |
| T6 | Transport-scope QDN request/response advertisements, ingress, and relays; enforce chain/data x IP/I2P HELLO identities; keep online-account identity traffic on the chain layer. | Complete | Stable reward-node identity path repaired first | Seven bounded implementation and test commits following the tracked start; focused tests passed 89/89; clean serialized full suite passed 3,077 with 67 skips; packaged JAR inspection, `git diff --check`, and three independent Codex reviews passed. Not released, deployed, or live-network validated |
| T7 | Repair adaptive networking: per-peer/coalesced AIMD loss, a QDN-specific catch-up signal, a bounded GET_BLOCKS budget, and one in-flight ping per peer. | Complete | T2 recovery-state semantics for the catch-up signal | Three bounded implementation commits following the tracked start; focused networking and archive non-regression tests passed 74/74; clean serialized full suite passed 3,092 with 67 skips; packaged JAR inspection, `git diff --check`, and integrated diff review passed. Not released or deployed |
| T8 | Repository and API correctness: archive temp filtering, chat fee persistence, websocket envelope filtering, and malformed PEERS rejection. | Complete | None | Four repair commits plus a websocket lookup-gating follow-up after the tracked start; focused tests passed 80/80; clean serialized full suite passed 3,098 with 67 skips; packaged JAR, `git diff --check`, and integrated review passed. Not released or deployed |
| T9 | Configuration and documentation hygiene: strict trigger registry with activation-aware schedule compatibility, testnet parity, shared public-write classification, and missing changelog entries. | Complete | Activation-aware schedule capability approved | Ten bounded implementation, test, and documentation commits following the tracked start; focused T9 tests passed 81/81 and the post-cutover capacity regression passed 9/9; clean serialized full suite passed 3,108 with 67 skips; packaged JAR inspection, `git diff --check`, and three independent review lanes passed. Not released or deployed |
| T10 | Restrict persistent API TLS keystore permissions to the owning account. | Complete | None | Owner-only atomic persistence plus existing-file repair; focused tests passed 86/86; clean serialized full suite passed 3,116 with 67 skips; packaged JAR and `git diff --check` passed. Not released or deployed |

## Finding Ledger

| ID | Verified disposition | Priority | Tranche | Status | Completion evidence |
| --- | --- | --- | --- | --- | --- |
| C-01 | `ChainATAPI` selects three opcode gates from peer-supplied `blockHeight` instead of the local parent height plus one. | Critical | T1 | Complete | Both height-mismatch directions pass at the exact trigger boundary for all three opcodes; existing bytecode-level pre-trigger and at-trigger tests pass |
| C-02 | Unknown feature-trigger names are accepted and silently unused. This is currently intentional forward-compatibility behavior, and `featureTriggers` is excluded from the chain-config handshake hash. | High safety / design | T9 | Complete | All 17 effective triggers use a strict registry and canonical versioned schedule commitment, including legacy fallbacks and disabled omissions. Base chain hashes remain stable; schedule differences are tolerated before local enforcement and rejected for new, completing, and existing chain/data peers at or after it |
| C-03 | A genesis-height node can bypass stale-tip protections and mint a competing block 2 despite fresh higher peers. This is local fork/liveness policy, not remote block-validation bypass. | Medium | T2 | Complete | Recent-higher, equal, stale, missing-signature-field, old-version, and post-genesis cases pass. Genesis discovery retains a peer whose later-chain tip signer is not yet known locally while sequential block validation remains authoritative. The advertisement remains unverified and only defers local minting |
| N-01 | Java defaults combine mainnet chain identity with Previewnet seed addresses; an empty/default configuration cannot handshake with those seeds. | High | T5 | Complete | Generic Java defaults expose empty chain and data seed lists while retaining the default identity and `1489x` listeners. All three explicit Previewnet profiles remain test-network `previewchain.json` profiles with `2489x` listeners and separate nonempty, correctly ported chain/data seeds. Remembered and operator-configured peers are not purged |
| N-02 | The recovery watchdog can orphan up to three local tip blocks based on unverified peer tip and archive-capability claims. Peer connections are handshaked, but the asserted chain evidence is not authenticated. | High | T2/T3 | Complete | Automatic peer-claim orphaning was removed rather than replaced. Both former enable settings remain accepted but always evaluate false; all managed profiles declare both false; managed upgrade and rollback force and preserve both false values. Peer height, freshness, quorum, and archive claims cannot authorize local block deletion |
| N-03 | Recovery mode remains active with a mixed fresh/stale peer set because exit requires every retained peer to be recent. | High | T2 | Complete | Mixed and fresh peer sets exit to recent-only selection in the same attempt; stale-only recovery, timeout-gated entry, and no-handshake behavior pass |
| N-04 | GET_BLOCKS auto-degrade can spend about seven full batch timeouts on one dead peer before fallback. The loop is bounded and interruptible, but monopolizes the Synchronizer thread and delays fallback or later synchronization work. | Medium | T7 | Complete | Queueing and response waits now share an absolute retry budget of at most two configured response windows. Dead-peer, fast-failure, shutdown, cap-recovery, and deadline arithmetic tests pass; archive-backed serving and slow-sync fallback remain unchanged |
| N-05 | QDN yielding uses `Controller.isUpToDate()`, so a fresh node below the minting/sync peer quorum is permanently capped as if it were catching up. | Medium | T7 | Complete | QDN yields only while synchronization is active or one vetted recent peer advertises a strictly higher tip. Equal-height and below-quorum peers no longer cause permanent throttling; lite mode remains exempt |
| N-06 | AIMD halves a process-global window once per expired chunk-map entry, allowing one stalled peer to collapse batching for all transfers. | High | T7 | Complete | Every immediate serving peer has its own bounded AIMD window. One cleanup pass coalesces all expired requests for that peer into one loss, successful delivery is attributed to its serving peer, and tests prove stalled-peer isolation plus clean-window recovery |
| N-07 | `peerPingTimeoutMillis` may exceed the fixed ping interval, permitting overlapping tasks and out-of-order consecutive-miss accounting. | Medium | T7 | Complete | An atomic per-peer guard permits one ping task at a time and releases in `finally` after success, miss, disconnect, or interruption. Slow and interrupted task tests prove scheduling reopens without overlap |
| S-01 | The dormant hosted whole-database bootstrap path accepts an unauthenticated archive and deletes the live repository before extraction or replacement validation. Qortium operates no such bootstrap servers; checkpoint-anchored peer archive fast-sync is a separate content-addressed replay path. | High | T4 | Complete | Hosted acquisition, extraction, helper, tray, automatic-startup, and repository-swap paths are removed. Legacy runtime settings are inert and non-writable; managed upgrade/rollback preserves `false` plus an empty host list. Archive fast-sync retains its checkpoint, content, budget, and replay rules, with only the obsolete hosted-bootstrap suppression removed |
| S-02 | Public-write protection classifies several endpoints by exact path. The reported anonymous bypass is false for shipped profiles because the preceding access handler exact-matches the same path; shared classification remains necessary defense-in-depth. | Hardening | T9 | Complete | Main and gateway listeners share exact raw-path and terminal-wildcard authorization/work classification. Actual handler-chain tests cover path variants, canonical builder/process/QDN writes, future allowlisted writes, and prove an oversized unauthorized gateway request remains 403 rather than consuming protection |
| S-03 | NAT/QDN request and response paths can disclose an I2P data destination to a clearnet peer, correlating IP and I2P identities. | High privacy | T6 | Complete | Requests and responses are built per immediate recipient. IP addresses require proven clearnet reachability, including configured-address startup; live data-I2P destinations go only to I2P peers. Ingress and relays strip mismatches, cross-transport relay-capable replies become relay-only, and unusable direct-only replies are dropped without changing message IDs or encodings |
| S-04 | Data-layer I2P HELLO messages expose the separate chain-layer I2P destination because capability construction knows transport but not network layer. | High privacy | T6 | Complete | Initial and post-handshake HELLO tests cover chain/IP, chain/I2P, data/IP, and data/I2P. Each retains only its permitted routing identity plus the chain identity triple; wrong-layer capabilities are sanitized without disconnecting solely for those extras, and chain peers no longer seed data-I2P addresses |
| S-05 | The persistent API TLS PKCS12 keystore is written with ambient umask permissions rather than owner-only permissions. The inspected managed runtime file was mode `0660`. | Medium | T10 | Complete | New PKCS12 files are written only after an owner-only sibling temporary file is created and verified, then atomically installed and rechecked. Existing `0660` files are repaired without content changes before all four TLS services load them; unsafe paths and unsupported permission models fail closed |
| R-01 | Crash-left `.<start>-<end>.dat.tmp` files cause `BlockArchiveReader` filename parsing to throw and break archive reads. | High correctness | T8 | Complete | Archive discovery accepts only regular files named by the exact numeric `start-end.dat` contract; crash-temporary, extra-extension, malformed, reversed, and overflowing names are ignored without parsing exceptions |
| R-02 | Chat-store reconstruction replaces the signed transaction fee with zero, so a stored nonzero-fee CHAT cannot be faithfully re-served. | Medium correctness | T8 | Complete | Fresh and existing current-schema repositories carry a non-null fee column; the idempotent migration preserves existing rows at zero, while signature, batch, group, direct, and latest-direct reconstruction retain exact nonzero fees |
| R-03 | Group-chat history filters private control envelopes, but websocket live push forwards them as ordinary group messages. | Medium privacy/API | T8 | Complete | Live group notification uses the same stored MESSAGE-or-unclassified rule as history after a cheap subscription match; key requests are hidden while normal private messages remain visible, and lookup failure is fail-closed |
| R-04 | A zero-entry PEERS message reaches `peerAddresses.get(0)`, producing repeated exception logging while leaving the peer connected. | Low | T8 | Complete | Zero and negative counts are rejected at PEERS decoding before handler dispatch; a defensive chain-handler guard disconnects an empty legacy or internally constructed message before indexing |
| T-01 | The manual local-testnet profile omits the current feature-trigger schedule and therefore exercises different consensus behavior from Previewnet. | Medium test fidelity | T9 | Complete | The local profile explicitly names all 17 registered triggers: 16 already-active behaviors begin at genesis and reward bundles capture at 97 for payout at 100. Full settings validation pins the schedule, and reset-required adoption is documented |
| D-01 | `.env.example` maps legacy `1239x` ports while Docker lacks a coherent explicit network profile. | Medium Docker | T5 | Complete | The canonical Preview participant settings, Dockerfile, both Compose variants, dynamic health probe, and `.env.example` agree on `2489x`, with legacy `1239x` removed. Missing settings install byte-for-byte; existing empty, malformed, custom, or symlinked settings are preserved; concurrent initialization is complete and temporary-file-clean. Generic-volume upgrade and pre-T5 rollback boundaries are documented |
| D-02 | The three adaptive-networking merges lack their required human-readable changelog entries. | Low documentation | T9 | Complete | `docs(network): backfill adaptive networking changelog` records exact original squash titles and plain-language descriptions for PRs #212, #213, and #214 |

## Adjacent Hardening Discovered During Remediation

### A-01 — Normal fork-reorganization failure atomicity

Status: complete in Core source and local validation; not released or deployed.

During the T3 review, `syncToPeerChain()` was found to commit each local orphan
before it state-validates and commits the fetched alternative blocks
(`Synchronizer.java` around lines 1710-1760). An invalid later block, shutdown,
or processing failure can therefore leave the repository at the common block or
on a partially adopted chain. Existing block-processing callbacks,
notifications, caches, and separate-repository writes mean a savepoint wrapper
alone is not proof of atomicity. T3 does not change or claim to repair the
canonical synchronization path. A-01 now stages all local orphans and fully
validated replacement blocks under one outer repository transaction, commits
once, and emits callbacks only afterward. Invalid later blocks, exceptions
after partial processing, and final-commit failure restore the exact original
tip, balances, and minted counts without callbacks. Focused synchronization,
block, and orphan tests passed 35/35; the clean serialized full suite passed
3,120 tests with 67 skips and no failures or errors; the packaged JAR contains
the atomic adoption path; and `git diff --check` passed.

### A-02 — Local archive export copy isolation

Status: complete in source and local validation; not released or deployed.

`Bootstrap.create()` now captures an HSQLDB snapshot and block-archive copy
under the blockchain lock, then opens and sanitizes only that private copy.
Minting accounts, trade-bot rows, learned peers, the live tip, and live archive
remain unchanged throughout export. The completed archive and checksum are
published from a unique same-filesystem staging directory, and a failure after
archive publication restores any prior completed archive and checksum. Focused
bootstrap, import/export, API, and archive-fast-sync tests passed 48/48; the
clean serialized full suite passed 3,120 tests with 67 skips and no failures or
errors; the packaged JAR contains the local exporter and neither retired hosted
importer class; and `git diff --check` passed.

### A-03 — Windows developer-reference API exposure

Status: complete in source and local validation; not released or deployed.

`WindowsInstaller/Install Files/AppData/settings.json` now binds Core to IPv4
loopback, enables the IPv4/IPv6 loopback whitelist, keeps restricted API mode
enabled, and disables remote API-key bypass. The profile remains generic,
unseeded, and explicitly documented as a developer reference rather than a
supported Windows release artifact. Profile and effective-access tests passed
as part of a 78/78 focused API/settings matrix; the clean serialized full suite
passed 3,122 tests with 67 skips and no failures or errors; the packaged JAR
contains the settings and public-access implementation; and `git diff --check`
passed.

### A-04 — Reward-node identity installation persistence

Status: Core repair complete; Qortium Home release integration required.

The reward-node identity previously followed `Settings.userPath`, which is
empty for the managed launcher because it supplies an absolute settings path.
That placed the identity under the replaceable installation directory instead
of beside the persistent runtime settings. Core now stores the authoritative
identity beside the active settings file, copies a surviving valid legacy seed
forward without deleting it for rollback, and fails closed on an unsafe or
corrupt existing path.

Core can copy the legacy file only if it still exists when the new JAR first
starts. Qortium Home replaces its installation tree before that point, so its
installer must preserve `install/preview/reward-node/identity.key` into the
persistent runtime directory before replacement. That cross-repository change
is a managed-release blocker, though it is not a blocker to this Core source
fix or PR.

Core acceptance evidence: `fix(minting): persist reward identity beside active
settings`; migration, target-precedence, corrupt/symlink fail-closed,
permissions, concurrent-copy, and manager production-path tests passed 21/21.
This is source validation only. Existing managed installations are protected on
their first upgrade only after Qortium Home preserves the legacy file before
replacing the old installation; that integration has not shipped.

## Compatibility Decisions

### Feature-trigger validation

T9 resolved feature-trigger validation as a versioned compatibility contract.
This release accepts exactly the 17 registered trigger names and hashes their
canonical effective heights, including legacy fallback fields and omitted
disabled values, into a separately advertised version-1 schedule commitment.
The existing base chain-config hash remains unchanged.

Preview peers tolerate missing or different schedules only before the locally
derived next-block height 99,990, the reward-bundle capture/block-format
boundary. At and after that height, new, completing, and already-handshaked
chain and data peers must advertise the exact version and commitment; a peer's
claimed height never controls enforcement. Future schedule changes must use a
separately staged compatibility epoch/version while continuing to advertise
the frozen version-1 contract long enough for coordinated rollout. The generic
main profile omits an enforcement height and retains the disabled sentinel.

### Generic Docker network target

An empty generic settings file must not silently mix identities. The approved
T5 boundary keeps generic Core on its default/mainnet chain identity but removes
baked-in public Previewnet chain and data seeds. Docker becomes an explicit
Previewnet distribution: on first start only, it installs a tracked Previewnet
settings template; an existing volume settings file is never overwritten.
Container ports, health checks, `.env.example`, chain configuration, and both
seed layers move together to the Previewnet `2489x` values.

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

Managed Preview launchers persist `bootstrap=false` and `bootstrapHosts=[]` in
their runtime file and omit both from the generated snapshot so older merge
logic preserves the safe values after a full rollback. Directly launched custom
settings are clamped only in memory by current Core; operators of unmanaged
installations must clear both legacy values before rolling back to an older jar.

## External Compatibility Follow-ups

These consumers fail closed against current Core but should remove their stale
controls in their own repositories:

- `qortium-node/src/settingsView.ts` still presents `bootstrap`,
  `bootstrapHosts`, and `archiveFastReplayOnlyWhenBootstrapDisabled`;
- `Qortium-Python-CLI/qortium_cli/tools.py` and
  `Qortium-Python-CLI/qortium_cli/tools/__init__.py` still advertise the retired
  authenticated `GET /admin/bootstrap` action;
- `qortium-home` must copy a valid legacy reward-node identity from the
  replaceable Core install into the persistent runtime before its first Core
  package replacement containing the A-04 fix.

## Current Work Boundary

No implementation tranche is active. A-01, A-02, and A-03 are complete in Core
source and local validation but are not released or deployed. The next Core
boundary is release integration: update the Core 1.7.0 release candidate on top
of these repairs, validate the exact packaged tree, and prepare the Core-only
prerelease. It must not enter Home or Chat scope. A-04 Home integration remains
outside this session.

A-03 was completed by `security(windows): restrict developer-reference API
exposure`, following the tracked start commit. The focused API/settings matrix
passed 78/78; the clean serialized full suite passed 3,122 tests with 67 skips
and no failures or errors; the packaged JAR contains the settings and public
access implementation; and `git diff --check` passed. The inherited installer
remains a developer reference, and these results do not claim a release or
deployment.

A-02 was completed by `fix(bootstrap): isolate local archive export from live
state`, following the tracked start commit. The focused bootstrap,
import/export, API, and archive-fast-sync matrix passed 48/48; the clean
serialized full suite passed 3,120 tests with 67 skips and no failures or
errors; the packaged JAR contains the retained local exporter and neither
retired hosted importer class; and `git diff --check` passed. These results do
not claim a release or deployment.

A-01 was completed by `fix(sync): adopt peer forks atomically`, following the
tracked start commit. The focused synchronization, block, and orphan matrix
passed 35/35; the clean serialized full suite passed 3,120 tests with 67 skips
and no failures or errors; the packaged JAR contains the atomic adoption path;
and `git diff --check` passed. These results do not claim a release, deployment,
or live multi-node fork exercise.

T10 and every finding in the original ultra review are complete in Core source,
tests, and the local packaged artifact, but are not thereby released or
deployed.

T10 was completed by `security(api): restrict TLS keystore permissions` and
`test(api): prove keystore is restricted before writing`, following the tracked
start commit. The focused six-class API/TLS matrix passed 86/86; the clean
serialized full suite passed 3,116 tests with 67 skips and no failures or errors;
the packaged JAR contains the owner-only writer and the API, gateway, domain-map,
and development-proxy TLS consumers; `git diff --check` passed.

The PKCS12 writer creates and verifies a unique owner-only sibling temporary
file before writing any private-key bytes, forces the completed file, requires
an atomic same-filesystem replacement, and verifies the installed file. Existing
broad permissions are narrowed before read without changing contents. POSIX
filesystems require exact mode `0600`; owner-only ACL filesystems are supported;
symlinks, non-regular targets, unsupported permission models, and permission or
atomicity failures stop TLS startup. T10 changed no certificate, password,
keystore format, TLS protocol/cipher policy, API listener, setting, or route.

T9 is complete in Core source, tests, and the local packaged artifact but is not
released or deployed.

T9 was completed by `docs(network): backfill adaptive networking changelog`,
`fix(consensus): register and commit feature-trigger schedules`, `fix(network):
enforce feature-trigger schedules at activation`, `fix(api): protect public
writes on every API listener`, `docs(testnet): explain feature-trigger rehearsal
schedule`, `test(consensus): cover trigger registry configuration failures`,
`fix(network): close feature-schedule handshake cutover race`, `test(api): prove
gateway denial precedes work protection`, `test(network): cover trigger schedule
HELLO wire`, and `test(network): make capacity peers handshake-compatible`,
following the tracked start commit. The focused T9 matrix passed 81/81 and the
post-cutover capacity regression passed 9/9; the clean serialized full suite
passed 3,108 tests with 67 skips and no failures or errors; the packaged JAR
contains the shared route policy, protected gateway, schedule-aware handshake,
and strict trigger registry with Preview enforcement at 99,990; `git diff --check`
and three independent review lanes passed.

Aside from the deliberate disposable local-testnet schedule, T9 changed no
configured Preview feature-trigger height or Preview transaction/block validity
rule, base chain-config hash, API authorization rule, anti-abuse limit value,
message type/schema, or chain selection rule. It adds schedule capabilities to
HELLO, makes effective schedule agreement mandatory at the reward-bundle
capture boundary, and applies existing public-write protection to the gateway
listener. All Preview block producers and peers must run the compatible release
before local next-block height 99,990. Exact-candidate mixed-version testing and
coordinated rollout remain release conditions; these results do not claim
either one.

T8 was completed by `fix(archive): ignore temporary and malformed archive
files`, `fix(chat): preserve stored transaction fees`, `fix(chat): filter live
private control envelopes`, `fix(network): reject empty PEERS messages`, and
`fix(chat): gate websocket visibility lookups`, following the tracked start
commit. The focused seven-class suite passed 80/80; the clean serialized full
suite passed 3,098 tests with 67 skips and no failures or errors; the packaged
JAR contains the changed archive reader, chat store/migration, websocket, and
PEERS parser classes plus the baseline chat-fee column; `git diff --check` and
integrated review passed. T8 changed no block archive contents, CHAT validation
or fee rules, websocket or peer-exchange encodings, chain validation, or
consensus behavior.

T7 is complete in Core source, tests, and the local packaged artifact, but it is
not released or deployed. T7 changed no message schema, chain selection or
validation rule, QDN authorization, archive serving rule, or consensus behavior.

T7 was completed by `fix(qdn): isolate adaptive batching by peer`,
`fix(sync): bound GET_BLOCKS retry time`, and `fix(network): serialize peer ping
tasks`, following the tracked start commit. The combined twelve-class suite
passed 74/74, including archive-backed block and BLOCKS-message coverage; the
clean serialized full suite passed 3,092 tests with 67 skips and no failures or
errors; the packaged JAR contains the changed QDN requester, Synchronizer, Peer,
and PingTask classes; `git diff --check` passed; and integrated diff review found
no source blocker. These results do not claim a release, deployment, or live
mixed-speed network test.

T6 is complete in Core source and local validation but is not released or
deployed. T6 scoped per-recipient QDN requests and responses, inbound
and relay address handling, HELLO capability construction and sanitization,
chain-derived data-I2P registration, and online-account identity traffic. It
did not change message schemas or IDs, peer admission, chain identity checks,
QDN file authorization, bundle signatures, reward arithmetic, or archive and
reward consensus rules.

T6 was completed by `fix(minting): persist reward identity beside active
settings`, `fix(network): scope HELLO identities by layer and transport`,
`fix(qdn): scope file-list addresses to recipient transport`, `fix(network):
keep minting identity traffic on chain layer`, `fix(qdn): preserve configured
direct reachability`, `test(network): close T6 privacy acceptance matrix`, and
`test(qdn): cover I2P-to-IP relay privacy`, following the tracked start commit.
The focused eight-class suite passed 89/89; the clean serialized full suite
passed 3,077 tests with 67 skips and no failures or errors; the packaged JAR
contains the changed identity, HELLO, data-network, and QDN manager classes;
`git diff --check` passed; and three independent Codex reviews found no Core PR
blocker.

No network message type, field encoding, message ID, or chain-identity handshake
requirement changed. Routing capabilities and file-list addresses remain
optional existing fields. Upgraded peers sanitize legacy wrong-layer or
wrong-transport values rather than disconnecting solely for those extras, and
silently ignore data-layer online-identity messages. An upgraded node protects
its own advertisements in a mixed fleet but cannot prevent an unupgraded peer
from disclosing that peer's identities. Exact-candidate mixed-version IP/I2P
testing remains a release condition. Qortium Home must also preserve a valid
legacy reward identity before replacing a pre-fix installation, without
overwriting a valid persistent target; this external integration remains a
managed-release blocker.

T5 is complete in source and local validation but is not released or deployed.
T5 made generic starts unseeded and new Docker settings explicitly Previewnet,
without migrating or overwriting an existing settings file or database. A
retained generic Docker volume must keep matching `1489x` Compose values; a T5
Preview volume rolled back to pre-T5 assets must account for the old fixed
`14891` health probe. T5 changed no chain identity, checkpoint, feature trigger,
consensus rule, live node, release, or deployment.

T5 was completed by `fix(settings): separate generic defaults from Previewnet
seeds`, `fix(docker): initialize new volumes as Previewnet`, `docs(preview):
align documented outbound-peer target`, `test(config): pin explicit Previewnet
profiles`, `fix(docker): harden first-run settings installation`, and
`docs(docker): clarify generic startup and rollback`, following the tracked
start commit. The focused five-class suite passed 41/41; syntax checks passed
for all three Docker scripts; public and internal fresh-Preview Compose renders
and the retained-generic `1489x` override render passed; the clean serialized
full suite passed 3,008 tests with 67 skips; `git diff --check` passed; and three
independent Codex reviews found no remaining blocker. Docker daemon access is
not available to this user, so these results do not claim a daemon-backed image
smoke, release, deployment, or live-network verification. The separately
unassigned A-01 normal-reorg atomicity, A-02 copy-isolated local exporter, and
A-03 Windows API-profile hardening remain outside this tranche.

T4 was completed by `security(bootstrap): retire hosted database replacement`,
`fix(settings): keep hosted bootstrap retired on rollback`,
`fix(bootstrap): isolate local export temporary files`, and
`fix(bootstrap): make local export restoration fail closed`, following the
tracked start commit. The focused ten-class suite passed 95/95; the clean
serialized full suite passed 2,998 tests with 67 skips; the packaged JAR was
inspected and contains `ArchiveFastSyncManager` and local `Bootstrap` creation
but neither `ApplyBootstrap` nor `BootstrapNode`; `git diff --check` passed; and
three independent Codex reviews confirmed the S-01 removal and compatibility
boundary. These results do not claim release, deployment, or live-network
verification.

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
