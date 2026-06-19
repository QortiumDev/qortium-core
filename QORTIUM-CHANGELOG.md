# Qortium Change Log

This is the main human-readable record of the Qortium fork effort.
It is written for non-developers first, with the goal of making each change
easy to follow without reading code.

## What Qortium Is

Qortium is the working name for a stripped-down and cleaned-up fork of Qortal
Core.

The aim is to keep the parts that are useful as a starting foundation while
reducing inherited complexity, removing project-specific assumptions, and
making the codebase easier for other teams to understand and adapt into their
own chain.

## Early Goals

- keep the history clean and easy to read
- make each logical change its own commit
- explain every meaningful change in plain language
- separate Qortium-specific direction from upstream Qortal messaging
- turn stable architectural decisions into tracked documentation over time

## How To Use This File

- update this file with every intentional Qortium commit
- use one entry per commit
- make each entry title match the commit message exactly
- keep each entry to one combined plain-language description
- keep entries understandable to non-developers
- use this file as the public narrative of the fork, alongside the technical
  git history

## Change Entries

### 2026-06-18 - network: suppress I2P fallback for connected TCP peers

Stops normal fallback mode from repeatedly dialing a peer's advertised I2P destination when that same peer is already connected over direct TCP. The chain and QDN/data peer selectors now treat I2P destinations advertised by an active IP peer as already covered, while still allowing `i2pPreferred` to force I2P for testing or privacy. Focused tests cover both chain and data selection so public seeds stay on the fast TCP path and I2P remains reserved for peers that need it.

### 2026-06-18 - docs: document I2P fallback rollout status

Updates the I2P fallback transport design notes and preview runbooks to match the current branch. The design document now records that both chain and QDN/data I2P wiring are implemented, that forced live two-node testing has validated the fallback path, and that the remaining work is normal fallback-mode validation, real QDN resource testing, recovery checks, packaging, and final PR prep. The tester guide and seed operator runbook now explain the current external `i2pd` requirement, local SAM bridge, peer `transport` field, and local-file reseed workaround for hardened hosts.

### 2026-06-18 - network: compare I2P peers without DNS resolution

Stops peer equality checks from trying to resolve `.b32.i2p` addresses through the normal DNS/socket path. I2P peer addresses are now compared directly by their peer-address kind, host, and port, while direct IP peers keep the existing socket-address comparison. This removes noisy runtime errors during I2P peer maintenance and keeps fallback peers visible without treating I2P destinations like resolvable TCP hostnames.

### 2026-06-18 - network: harden I2P peer selection visibility

Tightens I2P fallback behavior after live forced-I2P testing. The chain and data networks now skip their own advertised `.b32.i2p` destinations, avoid launching duplicate outbound I2P dials to the same peer while a previous attempt is still in progress, and log QDN/data I2P dial outcomes with the same clarity as chain dials. Connected peer API responses now include a simple `transport` field so operators can see whether each active chain or data connection is using direct IP transport or I2P without inferring it from the address string.

### 2026-06-18 - network: log I2P chain dial outcomes

Adds clear runtime logging around chain-network I2P outbound dials. Forced-I2P tests now report when the node starts a SAM `STREAM CONNECT`, whether i2pd establishes the stream, and whether the chain peer connection succeeds or fails, making fallback transport problems visible without trace-level logs.

### 2026-06-18 - network: wire I2P fallback into chain peers

Connects the I2P fallback transport to the main chain network. Nodes now bring up a separate chain-network SAM destination, advertise it as an `I2P` handshake capability, learn peer chain destinations from handshakes, preserve `.b32.i2p` addresses in normal peer exchange, and can accept or dial chain peers through I2P while keeping direct TCP as the default path. The existing `i2pPreferred` setting can force I2P for testing, and focused tests cover chain capability advertisement, peer learning, transport selection, and PEERS-message propagation.

### 2026-06-18 - network: refresh I2P SAM sessions during fallback retries

Gives each QDN I2P fallback startup attempt a fresh SAM session nickname while keeping the same persisted I2P destination keys. This prevents a slow or timed-out SAM forward setup from leaving i2pd with a stale session name that blocks later retries with `DUPLICATED_ID`; the node can keep retrying cleanly and still advertise the same stable `.b32.i2p` address once the router finishes building and publishing tunnels.

### 2026-06-18 - network: remove stale peer-version gates and harden I2P data fallback

Removes several old Qortal-era peer-version gates that blocked Qortium `1.0.0` nodes from using capabilities they already support: QDN data-peer discovery no longer requires a `6.0.0` peer, QDN metadata relay no longer filters peers by an inherited relay minimum, fast-sync no longer requires an old `5.5.0` numeric version threshold, and the unused foreign-fee message minimum is removed. This keeps compatibility decisions tied to Qortium's current protocol and capabilities instead of inherited version numbers. The QDN I2P fallback startup path is also made more resilient: if the SAM data session or inbound forward times out during startup, the node closes that failed attempt, logs the retry, and keeps trying instead of leaving I2P unavailable until restart; outbound I2P data connects now log whether I2P is disabled, not ready, or restarting after a down session.

### 2026-06-18 - network: wire I2P fallback into QDN data peers

Connects the I2P fallback transport to the QDN data network. Nodes now bring up a separate data-network SAM destination when I2P is enabled, advertise that destination as an `I2P_QDN` handshake capability, learn both direct TCP and I2P QDN routes from chain peers, and keep direct TCP as the primary path while allowing I2P as the fallback route for peers that cannot accept inbound TCP. The data-network listener handles I2P forwarded streams without blocking the selector thread, outbound data-peer connects can use the I2P stream provider, and new settings expose the SAM host, SAM port, separate chain/data key paths, preferred-transport toggle, and future embedded-router toggle. Focused tests cover the new capability round trip, handshake advertisement, disabled-QDN handling, and direct-versus-I2P data-peer learning behavior.

### 2026-06-18 - network: add I2P fallback transport groundwork

Adds the first foundation for I2P fallback networking: a small transport seam, a thin SAM v3 session client for external i2pd, persistent `.b32.i2p` destination handling, and opt-in live integration tests that prove two local SAM sessions can exchange bytes over I2P and reject an invalid destination. Peer addresses now carry an IP-or-I2P kind, parse valid `.b32.i2p` hosts without DNS or IP-literal validation, keep I2P ports out of the TCP path, and round-trip through the existing PEERS message format. This keeps direct TCP as the normal path while preparing the data and chain networks to add I2P as a fallback route for nodes that cannot accept inbound TCP connections.

### 2026-06-18 - gui: shrink startup splash logo

Reduces the startup splash logo display from 500x500 to 250x250 while keeping the splash width and status text size the same. The splash now uses less vertical space and sizes its window around the smaller logo instead of retaining the older tall frame.

### 2026-06-18 - api: mark configured development groups in group responses

Group API responses now include an API-only `isDevGroup` flag alongside the existing `isMintingGroup` flag. Both values are derived from the chain configuration active at the next block height, so clients can tell whether a group is currently configured for development authority, minting eligibility, both, or neither without guessing from the group name or ID.

### 2026-06-18 - consensus: add hash-neutral feature trigger container

Adds a generic `featureTriggers` object for future consensus activation heights and excludes that whole object from the chain-config fingerprint. Future trigger metadata can now be shipped under one hash-neutral container, so adding a new activation key later does not require a separate preparatory release just to teach older nodes another top-level field to ignore.

The two existing top-level trigger fields are still supported for the current configs and remain excluded from the fingerprint for compatibility. When a trigger height is present in `featureTriggers`, the node uses that value first; otherwise it falls back to the existing top-level field or to the disabled sentinel. Tests now cover that the whole container is hash-neutral and that unknown future trigger names can be parsed and carried without adding new feature-trigger code yet.

### 2026-06-18 - consensus: restore Previewnet feature triggers to chain config

Adds the block-27000 Previewnet activation entries for the online-account signature V2 and asset-order bounds fixes back into both bundled Previewnet chain config copies. These keys were temporarily removed while rollout risk was revisited; restoring them now keeps the coordinated Previewnet hard-fork height explicit in the chain config again.

This no longer changes the chain-config fingerprint, because the core now excludes both feature-trigger fields from the hash used during peer handshakes. That means Previewnet can carry the chosen activation metadata without splitting peer compatibility, while mainnet and the local testnet remain active from genesis and normal chain-defining config fields still affect the fingerprint. The upstream 6.1.6 comparison worksheet now matches the restored Previewnet block-27000 activation state.

### 2026-06-17 - fast-sync: write archive chunks through configured checkpoints

Lets archive nodes write a block-archive chunk that ends at a configured checkpoint even when that chunk has not reached the normal 10 MiB archive-file target yet. This keeps the default chunk size behaviour for ordinary archive progress, but makes checkpoint-backed fast-sync testable on small or young networks like Previewnet, where the whole chain up to the checkpoint can be far smaller than 10 MiB.

When the next configured checkpoint is already within the trimmed, archive-eligible block range, the archiver now points the writer at that checkpoint and relaxes the size gate for that checkpoint-ending file only. The writer still stops at the normal size target first if the range grows large enough, so large networks continue producing regular-sized chunks before the final checkpoint-spanning piece. After a successful write, the archiver advances from the last written block height, which keeps the next archive pass moving forward from the correct height. New tests cover checkpoint selection without introducing another runtime setting.

### 2026-06-17 - consensus: unpin Previewnet feature triggers from chain config

Removes the block-27000 Previewnet activation entries for the online-account signature V2 and asset-order bounds fixes from both bundled Previewnet chain config copies. With those keys omitted, Previewnet falls back to the disabled sentinel values already built into the code, so the hard-forking consensus fixes stay off until a new coordinated activation height is chosen.

The chain-config fingerprint now ignores the two feature-trigger fields in the same way it already ignores checkpoints. That means adding, removing, or revising this rollout metadata no longer makes otherwise-compatible nodes reject each other at the peer handshake. Normal chain-defining config fields still affect the fingerprint, and new tests cover both sides of that behavior. The upstream 6.1.6 comparison note now records that the Previewnet block-27000 activation was withdrawn before release.

### 2026-06-17 - fast-sync: enable archive-chunk fast-replay by default (engages only where a checkpoint is pinned)

Turns the archive-chunk fast-sync on by default, since helping new nodes catch up quickly is the whole point of the feature. The switch that was previously off is now on out of the box, but it stays completely inert unless the network's chain configuration pins a checkpoint to anchor the trust — so in practice this enables fast-sync on previewnet (which pins a checkpoint at height 24000) and changes nothing on mainnet or testnet, neither of which has a checkpoint. It also only ever runs on a genesis-fresh node, so existing nodes keep their chains untouched; a brand-new or reset node with a configured bootstrap still prefers the bootstrap. Previewnet seed nodes already serve chunks by default (serving was never gated behind this switch), so flipping this default lets a fresh previewnet node download and replay those chunks automatically instead of validating every block from genesis.

### 2026-06-17 - fast-sync: archive-chunk requester (part 2) with atomic checkpoint-anchored replay and serving-path hardening

Completes the archive-chunk fast-sync by adding the network requester that the trust core was built for, and hardens the whole feature after an adversarial security review. Still entirely OFF by default — a fresh node does nothing differently unless an operator turns it on.

The new requester (`ArchiveFastSyncManager`) is a one-shot, startup-only background task. On a genesis-fresh node that has opted in, it finds peers advertising an archive, fetches the (untrusted) chunk manifest, and downloads the block-archive chunks covering height 2 up to the release-pinned checkpoint. It downloads the checkpoint-spanning chunk first and rejects an obviously-wrong chain after a single chunk; it streams each chunk to disk and frees it before fetching the next, so memory use stays flat regardless of how far back the checkpoint is; and it refuses any manifest that does not contiguously cover the checkpoint or that claims more data than a sane archive of that height could hold. Each chunk is verified against the SHA-256 the manifest advertises (a tampered chunk is discarded) and against its own header. The blocks are then replayed and committed **all-or-nothing** under the blockchain lock: nothing is written to the database unless the entire range — including the fully validated checkpoint block — succeeds and the block at the checkpoint height reproduces the pinned signature, so a forged prefix is rolled back rather than left behind. After a successful replay the node refreshes its chain-tip view and hands off to normal sync. The manager is wired into node start-up and shut-down, gated behind a new default-off setting, and stays out of the way of a configured bootstrap.

A security review of the trust boundary found and fixed a critical flaw in the replay core: skipping the block-signature check below the checkpoint also skipped every transaction's own signature check (the only place transaction signatures are verified during replay), which would have let a malicious peer keep genuine block signatures while substituting forged, unsigned transactions underneath them. Block and transaction signatures are now verified at every height; only the genuinely expensive online-account signature and memory-proof-of-work checks are skipped below the checkpoint, which the checkpoint already attests. The chunk-serving side (on by default for archive nodes) was hardened too: a peer can no longer force repeated full-archive re-hashing by interleaving bogus requests, chunk slices are now served by a positional read instead of loading the whole file into memory, the serving handlers are bounded by per-message thread limits and guarded against stray exceptions, and the archive file caches were made safe for concurrent serving. New tests cover the manifest-coverage gate and the download-size budget; the existing archive and CORS regression tests continue to pass.

### 2026-06-17 - fast-sync: trusted archive-chunk replay core (verify + checkpoint-gated crypto-skip)

Adds the trust core of the archive-chunk fast-sync — the mechanism that lets a node build its chain state from downloaded block-archive chunks far faster than validating every block from genesis one at a time. This is the consumer half of the chunk-distribution protocol whose serving side already shipped; it is entirely OFF by default and changes nothing until explicitly enabled.

Two pieces: (1) `Block.isValid`/`areOnlineAccountsValid` gain an internal "trusted replay" mode that skips only the expensive per-block signature and memory-proof-of-work checks while still building and locally verifying all derived state — used solely for blocks strictly below a release-pinned checkpoint, whose history the checkpoint already attests. Every existing caller is unchanged (the no-argument methods delegate with trusted-replay off), so behaviour is byte-for-byte identical when the feature is unused. (2) A new `ArchiveChunkImporter` verifies a downloaded chunk against its advertised SHA-256 (content-addressing — a tampered chunk is rejected before it touches disk), sanity-checks its header against the claimed height range, writes it into the local archive, and replays its blocks with the checkpoint-gated crypto-skip. The release-pinned checkpoint is the only trust anchor; the manifest is untrusted and recomputable. New tests cover the integrity gates (genuine chunk accepted, tampered/short/wrong-range chunk rejected). The network requester that fetches chunks from peers, and full replay-to-state integration tests, follow as the next increment.

Upgrades poll creation, editing, voting, storage, and results so polls are more flexible while keeping existing single-option clients working. Poll names and option names now have matching 400-byte validation and database storage, poll descriptions can be omitted and are stored as empty text, and polls may carry an optional `startTime`; scheduled polls cannot be voted on before that time, and the start time can only be changed before the poll has started. The allowed option count rises from 100 to 1000.

Voting now supports selecting multiple options in one transaction through `optionIndexes`, while the legacy `optionIndex` field still works for single-choice votes and `0` still clears a vote. Each submitted vote replaces the voter's full active selection set, duplicate or mixed clear/real selections are rejected, and orphaning restores the previous selection set. Poll result APIs continue to report option-level counts and trust-weighted totals, now with `totalVoters` so multi-select polls can distinguish unique voters from total selected-option votes. The HSQLDB schema is bumped to version 2 with a v1 upgrade path that widens poll storage, adds start-time columns, and expands vote storage to one row per selected option.

### 2026-06-17 - lists: add "!" negation exceptions to QDN lists and make canStoreData block-first

Builds on the four-list rework. The `followedQdn` and `blockedQdn` wildcard lists now support
gitignore-style negation: an entry beginning with `!` is an exception, and within a list the last
pattern that matches a resource decides the outcome. This lets a broad rule carry narrow
exceptions — for example a `blockedQdn` of `["VIDEO", "!VIDEO/BOB"]` blocks every video except those
published under the name BOB, and the same works for `followedQdn`. (A literal leading `!` can be
written as `\!`.) Because order now matters, address-alias entries are expanded to their owned-name
patterns in place rather than appended at the end.

Blocking still always takes precedence over following — a follow never re-admits something the block
list blocks; use a `!` exception inside the block list instead. To make that precedence consistent,
`canStoreData` now checks the block list first like the rest of the code (download, serve, status,
cleanup, search): previously, under the `FOLLOWED` storage policy only, it consulted just the follow
list and ignored the block list, so a resource that was both followed and blocked could still be
retained. It is now rejected, matching `shouldPreFetchData`. New tests cover negation/last-match-wins
in the matcher and block-wins-plus-negation through the storage manager.

### 2026-06-17 - consensus: add the Previewnet height-24000 checkpoint and validate it on all full nodes

Adds the first trusted checkpoint to the Previewnet chain config — block height 24000 pinned to its block signature (verified identical across both seed nodes, and 180+ blocks deep so it is final). A checkpoint lets a node confirm its copy of history matches a known-good block at that height; it is the trust anchor the planned fast-sync will rely on so a node can accept downloaded history without being steered onto a wrong chain. Because checkpoints were already excluded from the chain-config fingerprint in an earlier update, adding this data does not change the fingerprint and so does not disturb peering.

Two supporting changes: checkpoint validation now runs on all non-lite nodes (it was previously gated to "top-only" nodes, a mode Qortium no longer supports, so the check was effectively dead code and never ran). And the behaviour on a checkpoint mismatch is now deliberate: a near-empty node (still effectively at genesis) resyncs from genesis as before, while an already-synced node logs a prominent error and does NOT wipe and resync. The latter avoids a dangerous failure mode — if a shipped checkpoint were ever wrong, every node carries the same data, so auto-wiping would make the whole network resync in a loop; a loud error lets an operator investigate instead. The checkpoint is Previewnet-only (mainnet and testnet have no chain yet).

### 2026-06-17 - lists: rework follow/block lists into four lists with wildcard QDN patterns and read-time chat blocking

Reworks how the node decides which QDN resources to mirror and which chat messages to show. Previously three lists were consulted by name or address (`followedNames`, `blockedNames`, `blockedAddresses`), and the node also scanned any list whose name *started with* one of those words (so `blockedNames_custom1`, `blockedNames_custom2`, … were all merged together). The node now consults exactly four lists, each by its exact name, and never scans prefixed list groups:

- **`followedQdn` / `blockedQdn`** — gitignore-style wildcard patterns over a resource's `SERVICE/NAME/IDENTIFIER`. `*` matches any run of characters within a segment, and omitted trailing segments match anything, so a single entry can target broad sets of resources. Examples: `BLOG_POST` (all blog posts), `APP/BOB` (any app from BOB), `VIDEO/*/cat*` (videos whose identifier starts with `cat`), `*/TOM` (anything published under the name TOM), `*/*/*fish*` (anything whose identifier contains `fish`). The NAME segment may also be an address, which is shorthand for "any name owned by that address" (resolved against current name ownership). SERVICE and NAME are matched case-insensitively; the IDENTIFIER is case-sensitive. These lists keep their previous *power*: they drive what the node auto-downloads, stores, serves and deletes — the background follower now scans recent arbitrary transactions and keeps those matching a follow pattern (and not a block pattern), and blocked resources are still prevented from being fetched/served and are cleaned up from storage. Follow-mode storage capacity, which used to be divided by the number of followed names, is now divided by the number of follow patterns.

- **`blockedChatNames` / `blockedChatAddresses`** — exact names/addresses (names case-insensitive, addresses case-sensitive) whose chat messages are hidden from this node's local chat APIs. Crucially, blocking a chat sender no longer rejects their messages during validation: the messages are still stored and relayed across the network, so other nodes are unaffected. The hiding happens only when *this* node serves chat over its REST endpoints and live websockets (message search, single-message lookup, message counts, active-chat previews, and the live message/active-chat streams), keeping page counts and previews consistent by filtering in the database query where possible.

Because the QDN search filter is now a wildcard pattern match rather than a simple name lookup, the two SQL search paths can no longer push follow/block filtering into the database query; when either is active the node filters and paginates the results in Java instead (the in-memory cache path filters before paging, so it is unaffected). The generic `/lists` API is unchanged and still manages any named list, including these four. Existing on-disk lists under the old names are no longer consulted by the node — operators who used `followedNames`/`blockedNames`/`blockedAddresses` will need to re-create their entries under the new list names (and, for QDN, as wildcard patterns).

A few deliberate behaviours are worth noting. Chat name-blocking matches the sender's *primary* name (the name shown in chat); blocking a name that someone owns only as a secondary name does not hide them — block their primary name or their address instead. The follower auto-downloads followed *named* resources; resources published without a name are not proactively swept (the same as before the change), though they are still filtered and blocked correctly wherever they are encountered. On the rarely-used service-less, latest-only resource search, a name whose newest matching resource is blocked is dropped from that result rather than falling back to an older non-blocked one.

New unit tests cover the wildcard matcher against each documented example and the new list accessors; the existing list, storage-policy, storage-capacity, cache-filter and chat test suites pass against the reworked behaviour.

### 2026-06-17 - core: prefer an explicitly-provided external blockchain config over the bundled one

Fixes a config-loading regression where the node ignored an external blockchain config file that a caller explicitly pointed at, if a config of the same name was also bundled inside the jar. The earlier change that bundles the network config (e.g. `previewchain.json`) with the release made `BlockChain.fileInstance` read the classpath-bundled resource *before* the external file, so an operator (or a test) supplying their own file named `blockchain.json` had it silently shadowed by the bundled one. The node now uses the external file at the given path when it exists, and only falls back to the bundled resource when no external file is present (erroring only when neither is found). Default mainnet startup is unaffected — it supplies no config name and so always loads the bundled `blockchain.json`, and cannot be shadowed by a stray file. This restored 22 pre-existing test failures (the account-trust, network-magic and memory-PoW config-validation tests, which write a deliberately-invalid config to a temp directory and expect it to be loaded and rejected); previewnet, testnet and the test harness all continue to load identical configuration bytes.

Replaces the placeholder `99999` activation height for both consensus feature triggers (`onlineAccountsSignatureV2Height` and `assetOrderBoundsHeight`) with their chosen values. On **Previewnet** both activate at **block 27000** — roughly 2.5 days ahead of the current chain tip at the network's real ~75-second block rate, giving the node operators time to update before the hard fork engages. On **mainnet** and the **local testnet** both are set to **0**, so the fixes apply from the very first block; neither of those chains has launched yet, so there is no legacy period to preserve and the secure behaviour is in force from genesis. The unit-test chain configurations are unchanged. Previewnet operators must be running this build before their chain reaches block 27000, or they will fork off at that height.

Fixes a universal-forgery vulnerability in the online-accounts signature scheme (upstream Qortal audit finding "c-01"). Online accounts — which determine minting eligibility and reward distribution — were signed with a custom aggregate scheme whose challenge did not depend on the signature's nonce point, allowing an attacker to forge valid-looking online-account signatures for accounts they do not control.

Once activated, online accounts and blocks use standard per-account Ed25519 signatures (challenge bound to the nonce point and the public key), each verified individually against its own public key. Blocks at and above the trigger store one signature per online account instead of a single aggregate, and block validation derives the signature count from the stored bytes so both layouts are handled. The change is gated behind a new feature-trigger height, `onlineAccountsSignatureV2Height`: below it, behaviour is byte-for-byte the legacy aggregate scheme so historic blocks replay identically; at and above it the secure per-account scheme applies. The field defaults to a disabled sentinel (fail-closed). New crypto tests reproduce the forgery and confirm the secure verify rejects it while the legacy verify accepted it, and a new test mints and validates a block end-to-end with the secure scheme active; the full existing block, minting and online-account test suites pass with the trigger off, confirming the legacy path is unchanged.

This is a hard fork — it changes block serialization at the activation height. The activation height is a PLACEHOLDER (`99999`) in `blockchain.json` and `previewchain.json` and MUST be replaced with a chosen future height, coordinated network-wide, before any release. Ported from upstream Qortal 6.1.6.

### 2026-06-17 - consensus: bound asset-order amounts behind a feature trigger (c-02)

Fixes a value-minting vulnerability in asset-order processing (upstream Qortal audit finding "c-02"). When an order's committed cost was computed, an intermediate value that exceeded the range of a signed 64-bit integer was silently truncated, which on a refund/credit path could wrap into a negative number and effectively mint asset value out of nothing.

Once activated, the node now rejects any order whose amount or price exceeds the maximum asset quantity, or whose committed cost does not fit in a positive signed long, and on the processing path it fails closed by throwing rather than silently wrapping. The change is gated behind a new feature-trigger height, `assetOrderBoundsHeight`: below that height behaviour is byte-for-byte identical to before, so existing blocks replay unchanged; at and above it the bounds checks apply. The field defaults to a disabled sentinel, so any chain config that omits the key keeps the fix off (fail-closed). New tests cover pre-activation parity, post-activation rejection, the rounded-commitment overflow and divisibility cases, and that the processing path fails closed instead of minting; the full existing asset-order test suite also passes with the trigger active, confirming the change is transparent for normal orders.

The activation height is a PLACEHOLDER (`99999`) in `blockchain.json` and `previewchain.json` and MUST be replaced with a chosen future height, coordinated network-wide, before any release — `99999` may already be below the current chain height. Ported from upstream Qortal 6.1.6.

### 2026-06-17 - docs: record 6.1.6 upstream triage decisions

Filled in the triage worksheet in the Qortal 6.1.6 comparison with the decisions made during review: which non-consensus fixes were ported (with their commit references), that the two consensus fixes (online-accounts signature rework and asset-order bounds) are deferred to a planned hard fork, and that the chat-analysis API and thread-dump diagnostics were not adopted — the former because Qortium's chat is database-backed rather than the in-memory model the upstream feature depends on, the latter because Qortium has no thread-dump scheduler.

### 2026-06-17 - core: start the data cache after bootstrap, just before the API

On startup the node builds an in-memory cache of arbitrary (QDN) resources so the resource-search API can answer quickly. That cache was being started early in startup, before the step that can replace the entire database with a downloaded bootstrap. On a node that bootstraps, the cache was therefore built from the empty pre-bootstrap database and then left stale once the real database was swapped in, so resource searches could return nothing until the cache was next refreshed. The cache is now started later — after the bootstrap and chain-validation step, and immediately before the API begins serving requests — so it is always built from the final database. Behaviour is unchanged on a node that does not bootstrap. Ported from upstream Qortal 6.1.6.

### 2026-06-17 - docs: add Qortal 6.1.6 upstream comparison

Added an inventory of the upstream Qortal changes between the 6.1.5 and 6.1.6 release points, in the same neutral style as the existing 6.1.5 comparison. It records what changed and which review bucket each change belongs to, highlights the two new consensus feature triggers (the online-accounts signature rework and the asset-order bounds fix) and their activation heights, lists the non-consensus changes, and gives porting notes for Qortium. This guides which of the 6.1.6 changes are adopted into the fork and is the companion document for the safe (non-consensus) fixes ported in the surrounding commits.

### 2026-06-17 - core: log storage and archive sizes in human-readable units

Several status log lines reported sizes as a raw number of bytes — for example "Total used: 55700516352 bytes". A new helper, `StringUtils.formatBytes`, turns a byte count into a compact binary-unit string such as "51.88 GB" or "512 bytes". The QDN storage-capacity log line and the block-archive writer's progress and total-size log lines now use it, so those messages are easier to read at a glance. This is a logging-only change with no effect on behaviour. Ported from upstream Qortal 6.1.6.

### 2026-06-17 - qdn: track hosted-data folder size incrementally instead of rescanning

To know how much of its storage allowance is in use, the node periodically measured the total size of its hosted-data and temporary folders by walking every file on disk. On a node hosting a large amount of QDN data that full scan is slow and I/O-heavy, and it ran every ten minutes. The node now keeps a running total in memory that is adjusted as files are written, deleted, or cleared to free capacity, and saves that total to a small file (`qortium-backup/ArbitraryDataFolderSizeEstimate.dat`) so it survives a restart. A full rescan still runs, but only on first start when there is no saved total, and then on a configurable schedule — by default once a day at 23:00 local time — to correct any drift. Two new settings, `dataStorageSizeCalculationHour` and `dataStorageSizeCalculationFrequency`, control that schedule. Ported from upstream Qortal 6.1.6, while keeping Qortium's correct temporary-folder measurement (the upstream version mistakenly measures the data folder twice).

### 2026-06-17 - api: exclude identified resources from default-resource searches

The arbitrary-resource search endpoint (`GET /arbitrary/resources/search`) supports a `default=true` filter that is meant to return only "default" resources — those published without an identifier. When no text query was supplied this filter was not applied at all, so identified (non-default) resources leaked into a default-only search; and when a query was supplied the default case was matched inconsistently. Now `default=true` always restricts results to default resources, a query in default mode searches names only, an empty identifier is treated as no identifier, and asking for `default=true` together with an explicit identifier is rejected as invalid criteria. The correction is applied to both the database query path and the in-memory resource cache, and three new tests cover the no-query, query, and literal-"default"-identifier cases. This brings the search endpoint in line with the `GET /arbitrary/resources` endpoint, which already had the guard. Ported from upstream Qortal 6.1.6.

### 2026-06-17 - tradebot: back up the full trade-bot batch instead of a single record

When a node takes several cross-chain trade offers at once, it prepares one trade-bot record per offer and then funds them all together. Previously the node wrote its safety backup inside that per-offer loop and saved only the single record it had just prepared, so the on-disk backup never reflected the whole batch being processed and could be left inconsistent if the node stopped partway through. The backup now runs once, after every record in the batch has been prepared, and saves all of them — so the backup matches what the node is actually about to fund. Ported from upstream Qortal 6.1.6.

### 2026-06-16 - gui: match splash screen to system theme

The startup splash screen now checks the desktop's light-or-dark appearance when the graphical Core starts. Dark mode keeps the existing black splash with the current Qortium logo and white status text, while light mode uses a white splash with an inverted black logo and black status text. The detection is best-effort across macOS, Windows, and common Linux desktops, includes a manual `qortium.splash.theme` override for light, dark, or system behavior, and falls back to the existing dark splash when the platform setting cannot be read.

### 2026-06-15 - api: add group kicks by group and group bans by member endpoints

Two new REST endpoints complete the symmetric read surface for group kicks and bans.

`GET /groups/kicks/{groupId}` returns all confirmed kick transactions that have occurred in a given group, with optional filters for kicked member address, timestamp range, pagination, and sort order. Previously only `GET /groups/kicks/member?address=` existed, which required knowing a specific member address; Q-Apps had no way to fetch the full kicked-member history for a group.

`GET /groups/bans/member?address=` returns all current bans for a given address across all groups. Previously only `GET /groups/bans/{groupId}` existed, requiring callers to probe every group individually to find a member's ban status.

Both endpoints validate their inputs and return the same data shapes as the corresponding existing endpoints (`GroupKickInfo[]` and `GroupBanData[]` respectively). The underlying repository method for kicks was extended to make the member address filter optional, which is required for the by-group query.

### 2026-06-15 - docs: prepare v1.0.0-preview.14 release notes

Added the release-prep notes for the v1.0.0-preview.14 prerelease. This preview packages the chat-history fix that keeps a group's messages visible after a member leaves, and the minter synchronization-wedge work that stops a node that is merely behind from minting a competing fork, keeps a node retrying synchronization on its own, and automatically recovers a node already stuck on a short self-minted fork. The docs index now points to the newest Core preview release-prep document.

### 2026-06-15 - core: unit-test the fork-recovery watchdog gate logic

Added automated tests covering the decision rules of the new fork-recovery step — confirming it stays inactive for a healthy or legitimately-alone node, waits out its required sustained and cool-down periods, respects its limit on how many blocks it may discard, and only acts once all of its safety conditions are met. The decision logic was separated from the surrounding machinery so it can be tested directly, with no change to how it behaves.

### 2026-06-15 - core: add Tier-3 fork-recovery watchdog (auto-orphan a stale self-fork)

Building on the synchronization-wedge prevention, this adds an automatic recovery step for a node that is already stuck on its own short side-chain. When a node's latest block is old, several distinct healthy peers are clearly ahead of it, and it has been unable to catch up for several minutes, the node now discards its own top block (just the one, with strict limits on how far back it can ever go) and asks to synchronize, so the normal, fully-checked download can take over and rejoin the real chain. It only ever discards its own blocks — it never accepts unverified ones — stays completely inactive on a healthy node or one that is legitimately alone, and can be turned off in settings.

### 2026-06-15 - core: prevent and auto-recover from the stale-catch-up minter sync wedge

A minting node that had fallen behind the network could get permanently stuck on a chain of its own making and stop catching up, even while it still had healthy connections. Two things combined to cause this: when the node's own latest block was old it would start minting new blocks on top of that old block instead of waiting to download the newer ones, and once it had done so it kept its own short side-chain because it briefly looked "heavier" than the real one; separately, the node only ever tried to synchronize in response to a message from another peer, with nothing to make it retry on its own if those messages stopped.

The node no longer mints its own competing block while a healthy, up-to-date peer is visibly ahead of it — it waits and synchronizes instead — and it now retries synchronization on its own on a periodic heartbeat so a node can no longer quietly stall. A peer that is genuinely ahead is now recognized by block height alone, removing a timing quirk that previously let the safeguard miss, and a single late or failed peer message no longer discards the rest of a batch of peer chain-tip updates.

### 2026-06-15 - chat: keep group messages from members who have left

Previously, when an account left (or was removed from) a group, all of its past messages disappeared from that group's chat history straight away. The node was filtering group messages down to only those sent by people who are members of the group right now, so anything said by someone who later left vanished the instant they left — even though the message itself had not yet expired.

The node no longer hides a group message based on whether its sender is still a member. Group messages now stay visible to everyone and simply expire naturally on the normal retention schedule, the same way they always did for members who stay. This affects the live `/chat/messages` and message-count endpoints and the chat WebSocket. The same now-unused "must still be a member" filter was also removed from an older, unused copy of the chat-message code so the two stay consistent. A new test publishes a group message from a non-member and confirms it is still returned.

Added the release-prep notes for the v1.0.0-preview.13 prerelease. This preview packages the upload-based QDN preview endpoint that lets mobile and remote-node clients preview local content without sharing the node's machine, the relaxed render content-security-policy that lets QDN-rendered apps run workers and WebAssembly, and the JAXB discriminator fix that restores the group-transaction build endpoints. The docs index now points to the newest Core preview release-prep document.

### 2026-06-14 - api: allow QDN-rendered apps to run workers and WebAssembly

When the node serves a QDN app, it attaches a content-security-policy to each file. The app's main HTML page was already allowed to run the code many apps need, but the app's separate JavaScript and worker files were served under a stricter policy that forbids the in-worker code execution and WebAssembly that engines like EmulatorJS/Emscripten rely on — so those apps failed to start even after their data had downloaded.

JavaScript assets (including worker scripts) are now served with a policy that permits that in-worker execution and WebAssembly, and that allows spawning workers from the app's own origin or from blob URLs; the app's HTML page also now allows creating workers. All other (non-script) assets stay locked to their own origin exactly as before, so the relaxation is limited to executable scripts. The local developer proxy was updated to match, so proxied development testing behaves like the node's render. A test confirms script assets receive the worker/WebAssembly policy while plain assets stay restrictive.

### 2026-06-13 - api: add upload-based QDN preview for remote clients

The existing preview endpoint builds a temporary, unpublished preview from a file or folder on the node's own machine, so it only works when the app and the node share a computer (the desktop case). This adds a companion endpoint that takes the content in the request body instead — base64-encoded, as a single file or a ZIP of a folder — so the node can build the same preview even when it runs on a different device from the app. That makes local-content preview possible from the mobile app, and from a desktop app pointed at a remote node, where handing the node a local path is impossible. (Base64 is used because that is the body format a mobile app can reliably send to the node.) A single HTML file sent for the website service is wrapped as index.html automatically, matching how the desktop preview stages a standalone page.

The decoded content is staged to a temporary location (bounded in size, and a ZIP is extracted with path-traversal protection), built into the same temporary /render preview URL the existing endpoint returns, and then deleted. The call requires the node's API key, and nothing is signed or published.

### 2026-06-13 - docs: prepare v1.0.0-preview.12 release notes

Added the release-prep notes for the v1.0.0-preview.12 prerelease. This preview packages the serving-side block-archive distribution groundwork, the new local per-block online-account history used by block online-account lookups, the preview chain configuration now bundled inside the jar, and the checkpoint-safe chain-config fingerprint change needed before trusted checkpoints can be rolled out gradually. The notes also record the tested archive message and manifest coverage, call out that automatic archive downloading remains off, and update the docs index to point at the newest Core preview release-prep document.

### 2026-06-13 - test: cover block-archive distribution messages and manifest

Adds tests for the new block-archive distribution work: wire round-trip tests for each of the new peer messages (the values survive serialization and decode unchanged), and tests that build a real two-chunk archive and confirm the manifest reports the correct chunk ranges, sizes and SHA-256 fingerprints, that raw chunk bytes match the on-disk files, and that rebuilding the manifest over the same archive produces an identical result.

### 2026-06-13 - api: expose block-archive manifest endpoint

Adds a read-only API call, GET /blocks/archive/manifest, that returns this node's block-archive manifest as JSON: the archive format version and, for each chunk, its height range, SHA-256 and size. This lets tooling (for example a stable node that publishes a canonical manifest for others to verify against) read the manifest without going through the peer-to-peer layer. It returns an error if the node isn't keeping a block archive.

### 2026-06-13 - network: add block-archive manifest and chunk distribution protocol

Adds the peer-to-peer messages that let nodes share block-archive data directly: one pair to request and return a node's archive manifest, and one pair to request and return a slice of an archive chunk. Chunk files are larger than a single network message may carry, so chunks are transferred in byte-range slices and reassembled by the requester, which can then verify the whole chunk against the manifest. Nodes also now advertise, during the connection handshake, how far they have archived, so a node can later pick peers that hold the ranges it needs.

For now this is only the serving and advertising side: a node answers these requests (controlled by a new setting, on by default) but nothing yet downloads from peers. A second setting is reserved for the future download-and-replay step and is off. Everything here is additive and backward-compatible: the new advertised capability is not part of the check that decides whether two nodes are on the same network, and a node that doesn't recognise the new message types simply ignores them without dropping the connection. That means this can roll out gradually across the network with no coordinated upgrade, priming peers to serve archive data before the download side is switched on later.

### 2026-06-13 - core: add block-archive chunk and manifest read API

A node that keeps a block archive stores it as a series of fixed range files (for example "blocks 2 to 1247"). This adds the ability to read that archive as discrete, shareable chunks: list the chunk ranges, read a chunk's raw bytes, and build a manifest — a list of every chunk with its height range, size, and a SHA-256 fingerprint of the chunk file.

Because archive chunks are byte-identical on any node that archived the same blocks, the manifest is reproducible: two nodes produce the same fingerprints. That is what will later let a node download archive chunks from many peers and verify each one against a trusted manifest before trusting its contents. The manifest is cached and rebuilt only when the archive changes. This is read-only groundwork and changes nothing about how existing nodes behave.

### 2026-06-13 - core: exclude checkpoints from the chain-config fingerprint

Nodes refuse to connect to peers running a different chain configuration, by comparing a fingerprint computed from the whole config file. That fingerprint is now computed with the optional "checkpoints" list left out.

Checkpoints are a trust aid that lets a node confirm its copy of history matches known-good block signatures at chosen heights; they do not define the chain itself, and two nodes with different checkpoint lists (or none) are still on the very same network. Including them in the fingerprint would have meant that simply adding or updating a checkpoint made existing nodes treat the updated ones as a foreign network and stop talking to them — a forced, all-at-once upgrade every time.

With checkpoints excluded, the fingerprint no longer changes when checkpoints are added or edited, so checkpoint data can be rolled out gradually without splitting the network. This change is deliberately introduced while no network has any checkpoints yet, so it does not alter any current fingerprint: old and new builds compute the same value and keep peering normally. It is the groundwork that lets trusted checkpoints be added in a later, separate step.

### 2026-06-13 - preview: bundle preview chain config inside the jar

The preview network's chain configuration (previewchain.json) is now packaged inside the release jar, and the node loads it from there. Previously this file only existed as a loose file shipped alongside the jar, which meant the config — and anything we want to ship with it, such as trusted checkpoints — could go missing, get edited by hand, or fall out of step with the running code.

When a node is told to use a named chain config, it now looks for that config bundled inside the jar first and uses it when present; if the name isn't bundled (for example a custom or local testnet config), it falls back to reading an external file exactly as before. The bundled preview config is a byte-for-byte copy of the existing one, so the chain it describes is unchanged: nodes on the old and new builds compute the same chain-config fingerprint and keep peering with each other normally. Mainnet already loaded its bundled config this way; this brings preview into line.

### 2026-06-13 - core: track per-block online accounts for historical lookup

Each block records which accounts were online using compact positional numbers — a slot in the constantly-changing list of all minting accounts — rather than the accounts themselves. That works for validating a block as it arrives, but the numbers stop meaning anything once minting accounts join or leave, so asking the node "who was online for block N?" later returned nothing useful (an empty list, even for very recent blocks). This made it impossible for apps to show the online accounts behind a block.

The node now keeps its own local record, written as each block is processed, of the actual reward-share identities that were online for every block — stored as stable keys that stay meaningful no matter how the minting set changes afterwards. The block online-accounts API reads this record first and falls back to the old behavior only for blocks a node processed before this change. This is purely local, node-side data: it does not change the blockchain, block contents, or validation in any way, so nodes can adopt it independently without a coordinated network upgrade. A node that re-syncs from scratch fills in the full history; a freshly bootstrapped node fills in from its bootstrap point onward. The record is removed for any block that gets orphaned.

This also fixes two latent crashes in the same lookup that previously made it silently return an empty list: one when an online account was at level 0, and one when any account owned more than one registered name. With the index in place and those crashes fixed, the block online-accounts lookup returns real data for the first time.

### 2026-06-12 - preview: fix relative release package output paths

The preview package builder accepted `--output=target/...` in its usage text and release-prep docs, but it changed into the staging directory before invoking `zip`, so relative output paths were interpreted from the wrong location and failed. The script now resolves caller-relative output paths to absolute paths before packaging, so documented release commands create the preview zip where operators expect.

### 2026-06-12 - docs: prepare v1.0.0-preview.11 release notes

Added the release-prep notes for the v1.0.0-preview.11 prerelease. This preview packages the launcher merge behavior that preserves local operator settings across restarts, the name-free QDN preview endpoint for apps that need to preview files or folders before an account has a registered name, and the small git hygiene cleanup for local assistant notes and generated update-package artifacts. The docs index now points to the newest Core preview release-prep document.

### 2026-06-12 - api: add name-free QDN preview endpoint for any service

QDN previews used to be possible only through the publish endpoints, which insist on a registered name, a synced blockchain, and an accurate clock even though a preview never touches the chain — that is why the preview feature in other clients refuses to work for accounts without a name. A new `POST /arbitrary/preview/{service}` endpoint now accepts a local file or directory path and returns a temporary viewing URL for any QDN service — websites and apps, but also videos, audio, images, and documents — so client apps like Qortium Home can let anyone check how content will look and behave before publishing it. The preview endpoint also reports a proper error instead of a success message when a preview cannot be generated.

### 2026-06-12 - preview: preserve local settings changes across restarts

The preview launcher used to rebuild the runtime settings file from the release template on every start, so any setting changed through the node's settings API or by editing the file by hand — for example enabling the API documentation page — silently reverted to the default on the next stop/start cycle. The launcher now merges instead: a new `MergeSettings` helper inside the node jar compares the runtime settings file against a snapshot of the template that generated it, keeps every setting the operator added, changed, or removed, and lets settings the operator never touched keep following the template shipped with each release. This works the same on Linux/macOS and Windows launchers, existing nodes keep their current changes on first upgrade, and `reset` still returns a node to a clean template state.

Local assistant notes and generated update-package artifacts are now ignored by git, keeping `CLAUDE.md` and `qortium.update` out of normal working-tree status while preserving the source, tests, and release files that should be reviewed and committed.

### 2026-06-11 - qdn: preserve accent setting across app renders

QDN app rendering now carries the selected accent setting through Core-rendered pages, loading screens, development proxy pages, and in-app navigation links, matching the existing theme, language, and text-size propagation. The injected page metadata escapes the accent value before exposing it to app JavaScript, so malformed query values cannot break out of the generated script block.

### 2026-06-11 - core: keep restart tray visible during handoff

Restart, bootstrap, and auto-update handoffs now keep their flashing tray indicator in the helper process that survives after the old Core shuts down, instead of trying to animate from the Core process that is about to exit. Normal restarts also avoid the old fixed wait sequence by polling for shutdown and repository-lock release, so the node can come back as soon as it is ready while keeping bounded safety waits. Auto-update restarts now launch the installed `qortium.jar` after replacement instead of continuing from the staging update jar.

### 2026-06-10 - docs: prepare v1.0.0-preview.9 release notes

Added the release-prep notes for the v1.0.0-preview.9 prerelease. This preview packages the new minting-group lookup API used by Qortium Home and the chat app's start-minting flow, the completed translation pass across all twenty Home languages with its CI completeness test, and the remaining Electrum TLS and developer-proxy hardening. The notes also record that both public preview seeds are switching to QDN auto-update for this release.

### 2026-06-10 - api: report minting groups in group lookups

Group API responses now say whether each group is one of the chain's configured minting groups at the current height. The flag appears in the group list, group search, single-group lookup, groups-by-owner, and groups-by-member endpoints, so apps like Home and the chat app can show minting-related options only where they apply. The endpoints also now share one common routine for filling in the extra display fields (member count, owner's primary name, and the new minting flag), so all group lookups report them consistently.

### 2026-06-10 - i18n: add Portuguese translations

Added Portuguese translations for all three Core message catalogs — API errors, transaction validation results, and the system tray menu — completing Core's language parity with the Home app's twenty languages. Vocabulary follows the Home app's Portuguese catalog (for example nó for node, pares for peers, and cunhagem for minting), written in the same neutral Portuguese readable by both Brazilian and European users. The CI translation test validates the new bundles automatically.

### 2026-06-09 - i18n: add Estonian translations

Added Estonian translations for all three Core message catalogs — API errors, transaction validation results, and the system tray menu — bringing Core toward language parity with the Home app, which gained Estonian alongside its other new languages. Vocabulary follows the Home app's Estonian catalog (for example sõlm for node, partnerid for peers, and mintimine for minting) so users see consistent terminology across the stack. The CI translation test validates the new bundles automatically.

### 2026-06-09 - i18n: add Arabic translations

Added Arabic translations for all three Core message catalogs — API errors, transaction validation results, and the system tray menu — bringing Core toward language parity with the Home app, which gained Arabic alongside its other new languages. Vocabulary follows the Home app's Arabic catalog (for example node, peers, height, and minting terms) so users see consistent terminology across the stack. The CI translation test validates the new bundles automatically.

### 2026-06-09 - test: enforce translation bundle completeness in CI

Translation completeness used to be checked by a standalone helper program that someone had to remember to run by hand, which is how a missing API error key went unnoticed across all seventeen languages. That helper is now a regular JUnit test that verifies every language carries exactly the keys the code can ask for — API error names, transaction validation results, and system tray labels — and that the root English fallback bundles stay identical to the English bundles. The pull request workflow runs the new test alongside the existing API regression test, so any future bundle drift fails CI instead of shipping silently.

### 2026-06-09 - i18n: honor full locale tags for translation lookups

API error translation previously kept only the bare language code from a client's request, so a browser asking for "zh-CN" or "zh-TW" was reduced to "zh" — which has no bundle — and Chinese clients always received English error messages. The API now passes the client's full locale tag through to the translator, so Simplified and Traditional Chinese resolve to their own bundles. The default value of the `localeLang` setting was widened the same way, from the machine's bare language code to its full tag, so nodes on region-specific locales pick the right bundle out of the box; existing explicit `localeLang` values keep working unchanged.

### 2026-06-09 - i18n: fall back to English when a language has no translations

Previously, when Core was asked for a language it has no translations for — for example a node running on an Arabic- or Turkish-language machine — every translated string rendered as a raw placeholder like `!!ar:SysTray.MINTING_ENABLED!!`, because the lookup had no final fallback. Each translation bundle now ships a root English copy that is served whenever no language-specific bundle matches, and the translator no longer detours through the machine's default language on the way there, so the fallback is always predictable English rather than whatever language the host system happens to use.

### 2026-06-09 - i18n: rename Japanese translation bundles from jp to ja

Japanese translations were stored under the "jp" suffix, but "jp" is the country code — the language code that Java and browsers actually send is "ja". Because of that mismatch, a node running on a Japanese-language machine, or an API client asking for Japanese, never found the Japanese bundle and saw raw placeholder text like `!!ja:SysTray.MINTING_ENABLED!!` instead. The three Japanese bundles are now named with the correct "ja" code, matching the code the Home app uses, so Japanese systems pick up the translations automatically.

### 2026-06-09 - i18n: add missing LITE_DATA_CONFLICT API error translations

The API error catalog defines a LITE_DATA_CONFLICT error for lite nodes that receive conflicting answers from different peers, but none of the seventeen translation bundles carried a message for it, so the project's translation checker flagged every language as out of sync. Every ApiError bundle now includes a translated message for this error, and a stray double space in the Romanian transaction-validity bundle was tidied so all bundles parse with identical keys. The translation checker now reports every bundle in sync.

### 2026-06-09 - security: corroborate ElectrumX chain height across servers

Hardened height-based cross-chain decisions against a single malicious or lagging ElectrumX server. `getCurrentHeight()` now samples a bounded subset of connected servers and returns the median reading, so one server lying in either direction cannot skew the refund/locktime timing that bounds atomic-trade safety, and persistent height outliers are penalized so the pool drifts away from them. The check is fail-safe — it never throws or stalls on disagreement, briefly caches the corroborated height to bound load, and falls back to a single-server read when too few servers are connected to corroborate.

### 2026-06-09 - crosschain: refresh Electrum server list with TLS pins

Regenerated the bundled Electrum server list with the updated refresh tool so every SSL server now ships a pinned `certificateSha256Fingerprint` captured from its live leaf certificate. All 442 SSL entries across 18 coin/networks were re-verified against their chain's genesis hash and current height during the refresh, restoring pinned connectivity for the many self-signed servers that strict validation had been rejecting — including NMC, which previously had no usable default servers. LBC keeps its single plaintext TCP seed because no public SSL LBC servers verified; it remains gated behind the existing `allowPlaintextElectrumServers` opt-in.

### 2026-06-09 - security: pin generated Electrum TLS certificates

Taught the Electrum server refresh tool to capture each reachable SSL server's current leaf certificate SHA-256 fingerprint and write it into the generated server list as `certificateSha256Fingerprint`. Fingerprints are captured with a trust manager that records the leaf certificate and then deliberately aborts the handshake, so the generator never trusts an unverified certificate, and self-signed servers are pinned and kept instead of being dropped by strict verification. Regenerating the bundled list now ships explicit pins, giving cross-chain connections certificate-pinned identity for the default servers without a trust-on-first-use exposure window.

### 2026-06-09 - security: add Electrum TLS trust modes with first-use pinning

Added an `electrumTlsTrustMode` setting (`STRICT`, `PINNED_ONLY`, `TOFU`) that governs how SSL ElectrumX servers are trusted when they carry no explicit certificate pin, and made trust-on-first-use the default. In `TOFU` mode Core records each server's leaf certificate SHA-256 fingerprint on the first connection into a persistent `lists/electrum-tls-fingerprints.json` store, then pins to it for every later connection, so a changed certificate is rejected instead of silently re-trusted. This restores connectivity to self-signed ElectrumX servers — which strict certificate validation had begun rejecting — while keeping explicit per-server pins authoritative, `STRICT` available for publicly trusted certificates only, and `PINNED_ONLY` available for operators that want to refuse any unpinned server. A new design note documents the trust model and the CodeQL disposition for the remaining pinned trust-manager alert.

### 2026-06-08 - security: constrain dev proxy asset content types

Changed the developer proxy's non-HTML streaming path so it no longer forwards arbitrary upstream content types directly to the browser. The proxy now defaults streamed assets to `application/octet-stream`, only preserves known frontend asset MIME types, and keeps content-sniffing protection in place so document-like responses such as XML are not rendered through the generic asset stream.

### 2026-06-08 - security: harden dev proxy response streaming

Changed developer-proxy responses so HTML, non-HTML streams, and proxy error bodies include browser content-sniffing protection, with plain-text proxy errors also receiving a restrictive content security policy. The developer proxy still intentionally renders loopback-only development HTML after injecting the QDN bridge, and that intentional local-development render path is now documented for CodeQL instead of being reported as an unreviewed XSS finding.

### 2026-06-08 - docs: prepare v1.0.0-preview.8 release notes

Added a release-prep note for the next Core prerelease after the security-hardening PR merges. The note records the intended preview tag, release highlights, operator compatibility notes, validation already run on the PR, post-merge packaging steps, and a draft GitHub prerelease body with placeholders for final build hashes and the merged commit.

### 2026-06-08 - security: avoid exposing upload chunk error details

Changed QDN chunk upload rejection responses so oversized chunks still return `413 Payload Too Large` but no longer include internal exception text in the response body. Core continues to log the rejected chunk context for operators while callers receive a fixed, non-revealing error message.

### 2026-06-08 - security: reject overloaded QDN chunk processing

Changed QDN chunk processing so a saturated chunk worker pool rejects new chunks instead of running validation and disk I/O on the submitting message worker. When the chunk processor queue is full, Core now logs the dropped chunk and disconnects the sending peer, preserving the dedicated chunk pool boundary during burst load.

### 2026-06-08 - security: protect chain parameter builders

Changed chain-parameter transaction-builder endpoints so they require the Core API key before constructing unsigned update transactions. The builders now share a single authorization and production-mode guard, advertise `apiKey` security in the generated API docs, and return the existing unauthorized API error when callers omit or send an invalid key.

### 2026-06-08 - security: downgrade raw QDN HTML downloads

Changed raw QDN file downloads so browser-executable HTML and XHTML MIME types are served as `application/octet-stream` instead of being trusted from the file extension. QDN apps and sites still render through the dedicated render path, while raw download responses no longer encourage browsers to execute stored HTML directly from the download endpoint.

### 2026-06-08 - security: stop reflecting CORS request headers

Changed API CORS responses so Core no longer echoes arbitrary headers from browser preflight requests. The allowlist is now fixed to the expected API headers, including `X-API-KEY`, which keeps browser compatibility for legitimate calls while removing a header-reflection path from public API responses.

### 2026-06-08 - security: generate API SSL keystore passwords

Changed API SSL startup and certificate regeneration so Core no longer creates generated PKCS12 keystores with the inherited static `default` password. When SSL starts with the legacy default still active, Core now generates a random URL-safe keystore password, saves it into the active settings file atomically, updates the live settings instance, and reuses operator-configured passwords unchanged.

### 2026-06-08 - security: bound QDN chunked uploads

Changed QDN chunk upload handling so Core copies incoming multipart chunks through a bounded temp-file path before replacing stored upload chunks. Chunked uploads now reject requests that would push the assembled upload over the QDN maximum file size with `413 Payload Too Large`, and oversized attempts clean up partial temp files instead of allowing API-key-authenticated callers to grow the upload directory without bound.

### 2026-06-08 - security: bound dev proxy HTML buffering

Added a decoded-size limit for developer-proxy HTML responses before Core buffers and rewrites them for local app development. Oversized HTML, including compressed responses that expand beyond the limit, now returns `413 Payload Too Large` instead of allowing an upstream development server to grow Core heap usage without bound, while non-HTML proxy responses continue to stream directly.

### 2026-06-08 - security: validate QDN download byte ranges

Changed QDN file downloads so HTTP `Range` headers are parsed with strict single-range validation before streaming starts. Malformed or unsatisfiable byte ranges now return `416 Requested Range Not Satisfiable` with the proper `Content-Range` response instead of falling through to unhandled parsing errors or invalid content-length math, and large valid ranges use long content lengths instead of truncating to an integer.

### 2026-06-08 - security: validate Electrum TLS certificates

Replaced the all-trusting Electrum TLS socket factory with default certificate validation and optional SHA-256 certificate pins for explicitly trusted self-signed Electrum servers. Electrum server settings, generated server resources, API server info, and the refresh tool now preserve `certificateSha256Fingerprint` metadata so pinned servers can be configured without weakening TLS validation for every SSL connection.

### 2026-06-08 - security: require explicit developer proxy enablement

Changed the developer proxy so it cannot be started unless `devProxyEnabled` is explicitly set, while keeping start and stop protected by the API key. The proxy now omits `unsafe-eval` from its default HTML content security policy, offers `devProxyUnsafeEvalEnabled` only as a deliberate development compatibility option, and documents that proxied HTML is trusted local development content rather than a production safety boundary.

### 2026-06-08 - security: require opt-in for plaintext Electrum servers

Changed Electrum-compatible BTC-like server selection so plaintext TCP servers are ignored unless `allowPlaintextElectrumServers` is explicitly enabled. Core now applies that policy to generated defaults, configured server overrides, peer-discovered servers, manual add-server requests, and the final socket creation path, keeping SSL as the normal cross-chain transport while preserving a deliberate compatibility escape hatch.

### 2026-06-07 - api: add QDN text size render context

Added text-size support to the QDN render context alongside the existing theme and language values. Render and dev-proxy requests can now pass a `textSize` query parameter, Core injects it into rendered app HTML as `_qdnTextSize`, and the QDN app helper preserves it when apps link to other QDN resources.

### 2026-06-07 - api: honor QDN render identifiers in authorization

Updated QDN render authorization so Core checks the identifier that is actually being rendered instead of always checking only the service and publishing name. Identifier-specific authorizations now protect a single resource, while service/name authorizations still intentionally cover all identifiers under that published service when a broader grant is requested.

### 2026-06-06 - api: fix group bans response schema

Corrected the API documentation for the group bans endpoint so `/groups/bans/{groupid}` advertises `GroupBanData` responses instead of join-request objects. This matches the endpoint's actual return value and keeps generated API docs from implying that bans and pending join requests share the same response shape.

### 2026-06-06 - docs: fix preview runtime directory names

Corrected the managed desktop runtime examples in the preview README so they use the Qortium Home and Core app-data folder names. The preview launcher guidance now points managed installs at `qortium-core` for Core runtime files and `qortium-home` for Home data instead of the old typoed `qortal-*` paths.

### 2026-06-06 - preview: support persistent runtime directories

Changed the preview launchers so generated settings, database files, QDN data, logs, PID files, and API keys can live in a stable runtime directory outside the release folder. The launchers now rewrite the generated local settings to use that runtime directory, and auto-update restarts keep the PID, log directory, and Log4j settings in the replacement process, which lets managed desktop installs keep the same synced Core data and API key across app updates instead of creating a fresh runtime folder each time.

### 2026-06-04 - Stop auto-update restart after failed jar replacement

Changed the auto-update apply helper so it only restarts Core after the verified replacement JAR has actually been copied into place. If the replacement JAR is missing or cannot be written after the retry window, the helper now stops instead of relaunching the existing old JAR, which prevents an approved update from cycling through the same download, failed apply, and restart path repeatedly.

### 2026-06-02 - Preserve preview pid files across auto-updates

Changed the preview launchers and auto-update restart path so Core keeps track of the replacement Java process after an approved update restarts the node. Preview `run.pid` files now get refreshed when the updated process starts, including for already-running preview nodes that still have an existing `run.pid`, which keeps operator scripts and status checks pointed at the live process instead of an old pre-update PID.

### 2026-06-02 - Refresh tray sync progress from peer height

Changed the tray tooltip so its syncing percentage and blocks-remaining count use the same current peer-height progress calculation as the node status API. This keeps the desktop tooltip moving while a preview node catches up, instead of leaving an older remaining-block estimate visible after the local height has advanced.

### 2026-06-02 - Expose preview peer reachability diagnostics

Added inbound and outbound peer counts plus P2P and QDN reachability flags to the node status response so preview testers can tell the difference between outbound-only connectivity and public reachability. Preview participants now target four outbound chain peers when enough reachable peers are available, and the preview docs explain how firewall or router forwarding affects peer counts.

### 2026-06-01 - build(deps-dev): bump org.apache.maven.plugins:maven-surefire-plugin from 3.5.5 to 3.5.6

Updated the Maven Surefire Plugin to the current patch release so Qortium's JUnit test runner stays current. This keeps the existing Maven test workflow and skip flags intact while picking up upstream Surefire maintenance fixes and improvements.

### 2026-06-01 - build(deps-dev): bump org.apache.maven.plugins:maven-dependency-plugin from 3.10.0 to 3.11.0

Updated the Maven Dependency Plugin to the current minor release so Qortium's build tooling stays current. This keeps the existing Swagger UI unpacking workflow intact while picking up upstream dependency-plugin maintenance fixes and improvements.

### 2026-06-01 - build(deps-dev): bump org.webjars:swagger-ui from 5.32.5 to 5.32.6

Updated the bundled Swagger UI webjar to the current patch release so Qortium's generated API documentation interface stays current. The build still unpacks, patches, and serves Swagger UI the same way, while picking up upstream Swagger UI maintenance and dependency fixes.

### 2026-06-01 - build(deps): bump org.apache.tika:tika-core from 3.3.0 to 3.3.1

Updated Apache Tika Core to the current patch release so Qortium keeps the dependency used for upload MIME and extension detection current. This preserves the existing QDN upload detection path while picking up upstream Tika maintenance fixes and dependency updates.

### 2026-06-01 - Document remaining code scanning triage

Added a tracked security triage note for the remaining GitHub CodeQL alerts so the project can separate intended QDN, local-file, developer-proxy, and ElectrumX compatibility behavior from findings that still need design work. This gives future alert dismissals and fixes a shared reference instead of treating the remaining scanner count as one generic bug list.

### 2026-06-01 - Harden cross-chain ledger CSV responses

Changed cross-chain trade ledger exports so market, currency, fee, and exchange labels are escaped before being written into CSV rows. This keeps the ledger export readable while preventing those labels from breaking CSV columns or being treated as spreadsheet formulas when opened.

### 2026-06-01 - Resolve ZIP extraction paths through shared guard

Changed ZIP extraction so archive entries are normalized through the shared relative-path guard before Core creates directories or writes files. Absolute archive entries are now rejected explicitly, and the streaming unzip path has matching traversal coverage, keeping QDN archive extraction inside its intended destination directory.

### 2026-06-01 - Constrain repository data imports to export files

Changed repository data imports so Core reads only a named file from the configured export directory instead of accepting an arbitrary local filesystem path. Startup recovery, bootstrap re-imports, and the admin import endpoint now pass export filenames directly, which keeps local trade-bot and minting-account restore workflows intact while narrowing the remaining repository import path scan surface.

### 2026-06-01 - Parameterize asset balance filters

Changed the asset-balance repository query so asset ID filters are bound as prepared-statement parameters instead of being written directly into the temporary `VALUES` table SQL. The public API already validates those filters as numeric asset IDs, and this keeps the database path consistent with the address filters while reducing the remaining SQL-injection scan surface.

### 2026-06-01 - Clean up ZIP entry sanitizer alerts

Changed ZIP entry-name cleanup to use direct character handling instead of regular expressions, so uploaded names with repeated whitespace cannot trigger slow regex behavior. Also documented the directory inspection step for authenticated local file uploads as intentional, keeping the existing local upload workflow intact while preventing the scanner from treating the recent ZIP-entry validation change as a new path issue.

### 2026-06-01 - Validate ZIP archive entry names

Changed ZIP creation so archive entry names are sanitized and checked as safe relative paths before they are written into an archive. Unsafe enclosing folder names such as parent-directory traversal are now rejected, while ZIP extraction continues to keep sanitized entries inside the target directory. This tightens archive handling without changing the normal QDN compression and extraction flow.

### 2026-06-01 - Guard AT timestamp height arithmetic

Changed automated-transaction timestamp math so block-height additions are done explicitly in `long` values and checked before converting back to the integer height expected by the CIYAM timestamp type. This removes an implicit narrowing conversion flagged by the scanner and makes an impossible overflow fail clearly instead of silently wrapping the target block height.

### 2026-06-01 - Contain upload and list filenames

Added a shared helper for resolving a single filename inside a known base directory, then used it for chunk-upload working files, raw string and base64 upload temp files, and local resource-list JSON files. Upload chunk directories now reject path-like service, name, identifier, filename, and chunk values instead of trimming them down to a leaf name, and resource lists can no longer be created or loaded through traversal-style names. This narrows another set of path-handling security findings while keeping normal chunked QDN uploads and list persistence behavior intact.

### 2026-06-01 - Harden browser-facing security boundaries

Tightened several browser-facing paths highlighted by the security scan. Q-App fallback navigation now confirms generated resource links stay on the current origin before redirecting, the developer proxy validates its loopback source before building the upstream URL, QDN plain-text responses now declare a plain-text content type while the loading page keeps an HTML content type, loading-page template values are escaped before entering JavaScript strings, and cross-chain ledger downloads reuse the safe attachment filename helper. This reduces the remaining XSS, open-redirect, and SSRF scan surface without changing normal QDN rendering, developer proxy, or ledger download behavior.

### 2026-06-01 - Contain arbitrary download file paths

Added a shared filesystem helper for resolving request-style resource paths inside a fixed base directory, including leading-slash paths that should still mean "inside this resource." Raw `/arbitrary` downloads now use that helper for the `filepath` query parameter, and QDN rendering delegates to the same helper, so traversal attempts and invalid path text are rejected consistently before Core reads or streams files.

### 2026-06-01 - Contain QDN render file paths

Changed QDN rendering so requested file paths are resolved through the existing safe base-directory helper before Core reads or streams content from an extracted resource. Leading slashes are still treated as paths inside the QDN resource, but parent traversal, backslash traversal, and invalid path text are rejected before a filesystem path is used. This keeps render requests inside the extracted QDN directory without changing normal APP, WEBSITE, or single-file resource routing.

### 2026-06-01 - Harden QDN render metadata escaping

Changed the QDN HTML rewrite path so injected render metadata is written as escaped JavaScript string data instead of raw script markup, and so generated script, base, and meta tags are built as DOM elements. Relative render assets now receive encoded identifier query parameters without breaking URL fragments, Q-App navigation URLs encode service, name, identifier, path, and query values before building links, and the gateway modal writes message text without treating it as HTML. This reduces the QDN browser-side XSS alert surface while keeping the existing render, gateway, and proxy flows intact.

### 2026-06-01 - Address first CodeQL security findings

Removed an unused API response-unmarshalling path that exposed XML parsing risk, tightened the developer proxy so it only opens loopback HTTP targets, made group approval threshold serialization check the byte range explicitly, sanitized attachment download filenames before building response headers, stopped chunk-upload errors from returning internal exception details, replaced regex-heavy filename cleanup with direct character handling, and changed a local key-storage test page to write private key text without treating it as HTML. This starts reducing the inherited CodeQL alert baseline with narrow fixes that do not change chain rules or QDN storage behavior.

### 2026-06-01 - Move repository references to QortiumDev main

Updated the current repository links, branch-targeted automation, release helper defaults, preview operator and tester instructions, gateway help links, Windows installer metadata, and proxy test fixture to use `QortiumDev/qortium-core` on the `main` branch. This prepares Qortium Core to live under the project organization with a normal default branch while leaving historical changelog and dependency-provenance references untouched.

### 2026-06-01 - Add Core-managed direct private chat helpers

Added local API-key-protected helpers for direct private chats so desktop callers can ask Core to resolve recipient public keys, encrypt direct messages, sign and store CHAT transactions, and list direct conversations with decrypted data when the message uses Qortium's new Core-managed direct message format. This keeps private keys inside the trusted local API boundary and gives Qortium Home a safer surface to build direct messaging on without copying QDN app-side crypto.

### 2026-06-01 - Avoid reserved PID variables in the Windows preview stop helper

Renamed the Windows preview stop helper's local process ID variables so they no longer conflict with PowerShell's built-in `$PID` value. This lets the helper read `run.pid`, stop the preview node, and clean up stale PID files without failing before the stop logic runs.

### 2026-06-01 - Capture Java version output safely in the Windows preview launcher

Changed the Windows preview launcher so Java version detection reads the `java -version` output through redirected process streams instead of treating Java's stderr output as a PowerShell error. This keeps the Java 17 check working under strict error handling before Core starts.

### 2026-06-01 - Hide the Windows preview Java process window

Changed the Windows preview launcher so it starts the Java process without leaving a visible command window behind. This keeps Qortium Home's managed Core startup quieter for Windows testers while preserving the same process arguments, working directory, PID file, and preview log files.

### 2026-05-29 - Tighten the coverage profile baseline

Made the coverage profile easier to use and more meaningful for future checks. Running the coverage profile now turns unit tests on automatically, writes the JaCoCo report during verification, ignores generated Zcash protocol stubs that are not useful coverage targets, and raises the minimum coverage levels to better match the current tested codebase.

### 2026-05-29 - Document optional status progress fields

Clarified the `/admin/status` response documentation so apps know that the sync phase is always present, while numeric progress fields can be absent during a fresh startup before Core has learned a peer target height. This matches the existing behavior and helps clients handle the short connecting or stale window without treating it as an error.

### 2026-05-28 - Gossip inbound peer listen addresses

Changed peer discovery so a node that connects inbound can have its advertised listen address treated as recently reachable in memory. This helps preview participants learn about active outside peers through the seed nodes instead of only seeing the fixed seed list, while leaving long-term peer persistence rules unchanged.

### 2026-05-28 - Expose preview render reads publicly

Allowed preview network public API users to read QDN render URLs from the bundled preview settings. This lets Qortium Home and other preview browsers load public APP, WEBSITE, and media resources through `/render` while keeping render authorization and other write-style routes private.

### 2026-05-28 - build(deps): bump io.netty:netty-bom from 4.2.13.Final to 4.2.14.Final

Updated Qortium's Netty networking library bundle from 4.2.13.Final to 4.2.14.Final. This picks up upstream networking fixes while keeping Qortium's own peer protocol, preview ports, and runtime behavior unchanged.

### 2026-05-28 - security: replace java.util.Random with SecureRandom (Q4 + Q5)

Replaced predictable random number generation in QDN request IDs and online-account nonce setup with shared secure random generators. This makes peer-facing request identifiers and minting-related nonce starting points harder to predict without changing the surrounding transaction, block, or QDN behavior.

### 2026-05-28 - security: validate backup name before SQL string interpolation

Added a strict backup-name check before the repository builds its HSQLDB backup command. Current backup callers already use fixed safe names, but this prevents future code from passing names with quotes, path separators, or other unsafe characters into a database command or backup path.

### 2026-05-28 - security: require API key for GET /admin/settings/{setting}

Locked down the single-setting admin read endpoint so it now requires the local API key like other admin controls. This prevents callers with basic API access from reading internal node settings through reflection without authentication, while keeping normal authenticated administration unchanged.

### 2026-05-28 - Give preview stops more time to shut down cleanly

Extended the preview stop helpers so they wait longer for Core to shut down through the API before forcing the Java process to exit. This gives the node more room to finish database, backup, and update-restart cleanup on slower machines or VPS hosts, while still keeping an eventual force-stop fallback for stuck processes.

### 2026-05-27 - Record QDN auto-update retry smoke target

Added a small operator-note update for testing the improved QDN auto-update retry path. This commit is intentionally non-consensus and exists so preview nodes can prove that a single manual `/admin/update` request keeps retrying a missing QDN binary until the resource is ready, while another node can still test automatic `INSTALL` mode.

### 2026-05-27 - Continue QDN auto-update downloads after stalls

Changed manual QDN auto-update installs so a missing binary download keeps retrying after the first `/admin/update` request instead of requiring another operator call. Update status now reports retry progress, stale download state, next retry timing, and active QDN peer count, while the QDN chunk requester refreshes source discovery when a batch has pending chunks but no useful request path.

### 2026-05-27 - Document preview runtime jar replacement

Updated the preview seed operator runbook to call out that `preview/qortium.jar` takes priority over the freshly built jar in `target/`. Operators now have an explicit copy step after building, which prevents a seed from restarting on an older release-style runtime jar by accident.

### 2026-05-27 - Retry stalled QDN chunk downloads

Changed QDN chunk download retry handling so a timed-out chunk request can retry the same source again instead of permanently treating that peer as already tried. Download batches now keep temporarily unavailable chunks pending for another attempt, which matters for preview auto-updates and other QDN resources that may initially have only one reachable holder.

### 2026-05-27 - Record local QDN auto-update smoke target

Added a small operator-note update for the preview network's local QDN auto-update publish test. This commit is intentionally non-consensus and exists so the nodes can test the normal one-step local publish flow with a newer build, separate from the staged seed-hosted update flow.

### 2026-05-27 - Record staged QDN auto-update smoke target

Added a small operator-note update for the next preview auto-update smoke test. This commit is intentionally non-consensus and exists so the preview nodes can test the staged seed-hosted QDN update flow from a newer build without changing chain rules, genesis data, ports, or public API policy.

### 2026-05-27 - Harden QDN auto-update staging and status

Changed QDN auto-update handling so manual install requests only report `INSTALL_STARTED` after the pinned update binary is local, hash verified, and the apply-update helper has been scheduled. If update chunks are still missing, the admin status now reports download preparation and chunk progress instead of pretending the install started, and background install mode retries missing-data updates sooner. The publish helper can also split seed-hosted QDN staging from local signing, letting a restricted seed host update chunks without holding the publishing private key.

### 2026-05-27 - Fix auto-update helper transaction layouts

Updated the QDN auto-update publishing and approval helper scripts for Qortium's current transaction format. The helpers no longer ask the removed address last-reference API for account references, and the AUTO_UPDATE manifest transaction builder now serializes the current ARBITRARY transaction layout directly. This keeps preview auto-update testing aligned with the cleaned-up baseline transaction model.

### 2026-05-27 - Record preview auto-update smoke target

Added a small operator-note update that creates a newer non-consensus build for
the preview network's first QDN auto-update smoke test. This lets the seed nodes
exercise approved QDN update install paths without changing chain rules,
genesis data, public API policy, or runtime behavior beyond the build identity.

### 2026-05-27 - Prepare preview QDN auto-update testing

Prepared the preview network to test approved QDN-based jar updates without
making automatic installs the default. Preview settings now explicitly keep
auto-update off, public API rules are tested to keep update install endpoints
private, the preview launcher preserves a local seed auto-update mode across
restarts, the background updater checks soon after startup, and the auto-update
publishing and approval helpers now have preview-friendly zero-fee MemoryPoW
paths.

### 2026-05-27 - Expose preview node read APIs

Changed the default preview participant and seed profiles so preview nodes can
serve limited public read-only API and QDN browsing requests while keeping
write, admin, utility, list-management, and peer mutation routes local-only.
Public API path matching now supports prefix wildcards, allowing Home and other
clients to discover usable public nodes from the initial preview network.

### 2026-05-27 - Accept same-block QDN publish and delete

Fixed QDN block validation so a synced node can accept a block that publishes
and then deletes the same resource in transaction order. This prevents nodes
that did not already have both unconfirmed transactions from rejecting an
otherwise valid block, and makes preview sync stalls easier to diagnose by
logging the exact transaction validation reason at normal info level.

### 2026-05-27 - Document preview first-sync expectations

Clarified the public preview tester guide so new testers know that `status --wait`
only means the local API is reachable, and that a fresh preview node may still
need a few minutes to find peers and sync from genesis. This sets expectations
for the current no-snapshot preview flow without adding bootstrap or snapshot
support yet.

### 2026-05-27 - Fix preview release logging

Fixed the preview launcher and release package so application logs are written
reliably to `preview/qortium.log` and launcher output is captured in
`preview/run.log`. The preview package now includes a smoke check that extracts
the tester zip, starts a temporary headless node, and verifies both logs are
created before a release is published.

### 2026-05-27 - Improve admin status sync progress

Expanded the node status response with a clearer sync target height, remaining block count, and sync phase so clients can explain first-run catch-up and stale-node states more accurately. The existing sync percentage is still available for compatibility, but active synchronization no longer reports 100 percent complete while the node is still behind its known target height.

### 2026-05-27 - Fix CHAT typed JSON builder requests

Fixed the CHAT transaction builder so clients can submit either the existing endpoint-style JSON payload or a full transaction-style JSON payload that includes `"type": "CHAT"`. Malformed JAXB/MOXy request bodies are now reported as bad requests instead of falling through as server errors, which gives clients a clearer failure when a submitted JSON body cannot be parsed.

### 2026-05-26 - Document public preview readiness checks

Expanded the public preview tester guide with local status, peer, seed-status, and troubleshooting checks, and added a seed-operator runbook for updating, starting, stopping, firewalling, verifying, and resetting public preview seed nodes. The preview release package now includes both tester and operator guidance so public testing can start from the same repeatable instructions used for the current seed-node setup.

### 2026-05-26 - Fix stale chain catch-up beyond summary batches

Fixed stale chain catch-up when a node is more than one block-summary batch behind reachable peers. Nodes now accept partial peer-summary batches during comparison instead of wrongly expecting the peer's latest tip inside the partial response, and stale catch-up minting now defers to any valid newer peer tip instead of treating a previously filtered tip as permission to mint a local fork.

### 2026-05-26 - Expose limited read-only seed API endpoints

Added a public API allowlist for preview seed nodes so external clients can read only `GET /admin/status` and `GET /peers/known` while the normal API remains restricted to local administration. The Regxa and Netcup seed profiles now enable that limited read-only access for public discovery without exposing settings, logs, peer mutation, or other API routes.

### 2026-05-26 - Limit long MemoryPoW benchmarks to active difficulties

Reduced the optional long MemoryPoW benchmark tests so they stop at difficulty 12, which is the highest difficulty currently used by Qortium's configured fee-alternative settings. This keeps the benchmark useful for active preview parameters without spending many extra minutes on difficulty levels that are not currently user-facing.

### 2026-05-26 - Fix transaction and API test regressions

Fixed several regressions uncovered by the broader test run after recent preview and transaction changes. QDN delete transactions now validate and orphan cleanly when the delete transaction is already in the unconfirmed pool, delete tombstones no longer try to relocate missing data files, AT account lookups now handle invalid public-key bytes safely for read-style checks, and the affected API, chat, message, and QDN tests now assert the current configured behavior.

### 2026-05-26 - Drain queued handshake messages after each handshake task

Fixed a peer-handshake scheduling edge case where multiple handshake messages could be read from a socket at once, but only the first message was scheduled for processing. Follow-up handshake messages that are already queued are now processed after the previous handshake task clears its pending flag, preventing QDN data peers from getting stuck before completion.

### 2026-05-26 - Treat already-stopped preview nodes as stopped

Improved the preview stop script so it treats a node as successfully stopped if the API shutdown request already caused the process to exit before the script reaches the fallback kill step. This removes confusing restart failures during normal preview maintenance.

### 2026-05-26 - Bound preview stop API shutdown requests

Added a timeout to the preview stop script's API shutdown request so a node that accepts the stop command but never closes the HTTP request cannot block the rest of the restart flow. The script can now fall back to process termination and continue with a clean restart instead of hanging indefinitely.

### 2026-05-26 - Keep QDN peer ports distinct from blockchain peer ports

Fixed QDN peer discovery so a blockchain peer and QDN data peer on the same host are treated as distinct endpoints when they use different ports. Preview nodes can now learn and connect to seed QDN ports such as `24894` even when the same seed is already known on the blockchain peer port `24892`.

### 2026-05-26 - Refresh preview genesis timestamp after startup fix

Refreshed the preview genesis timestamp again after the fresh-startup fix was applied. This keeps the final three-node preview reset close to the current clock without changing any other chain settings.

### 2026-05-26 - Allow fresh preview startup with no QDN resources

Fixed fresh-node startup when the QDN resource cache has no rows to update. Empty preview chains now skip empty latest-signature update and deletion batches instead of stopping before the API starts, allowing a reset chain to initialize cleanly.

### 2026-05-26 - Refresh preview genesis timestamp for public reset

Refreshed the preview network genesis timestamp for the next public reset. This lets the three-node preview setup start from a recent chain origin instead of immediately entering stale catch-up mode, while keeping the rest of the preview chain configuration unchanged.

### 2026-05-26 - Reset Qortium Core version to 1.0.0

Reset Qortium Core's active release version to 1.0.0 for the new Qortium baseline. The Maven build version, default peer compatibility floor, preview and testnet peer-version settings, installer metadata, and active version tests now agree on the 1.0.0 line so freshly rebuilt nodes can connect after the preview chain reset.

### 2026-05-26 - Standardize Qortium image resources

Standardized the remaining Qortium image resource filenames and locations so app icons, tray icons, and installer artwork are easier to understand and maintain. Runtime icons now live under clear app and tray resource folders, stale runtime ICO references were removed, and the Windows installer banner uses a direct Qortium installer filename.

### 2026-05-26 - Replace Qortium icon assets

Replaced the remaining inherited application, tray, and Windows installer artwork with the prepared Qortium icon set. The stale splash image and unreferenced installer bitmap were removed so packaged resources no longer carry old branding that is not used by the current desktop or installer flows.

### 2026-05-26 - Document reserved transaction type IDs

Documented the historical transaction type IDs that Qortium is keeping reserved instead of compacting away. Former airdrop, account flag, forging enablement, and account level transaction IDs are now recorded as reserved metadata while remaining inactive, so future work can intentionally revisit those concepts without old or removed transaction IDs being accepted as valid transactions today.

### 2026-05-26 - Reset Qortium archive format baseline

Reset Qortium's block archive serialization baseline so archive version 1 now uses the current compact block layout with AT state hashes. The old per-AT-state archive layout and version 2 archive default were removed from archive reads, writes, rebuilds, serialized block export, and tests, keeping the archive format versioning system ready for future changes without carrying inherited compatibility paths.

### 2026-05-26 - Reset Qortium network protocol baseline

Reset Qortium's peer network protocol baseline so the current capability-aware handshake and compact block, block-summary, peer-list, signature, and online-account messages are the only active formats. The old version-suffixed message classes and peer-version fallback branches were removed or renamed to baseline names, so nodes now rely on Qortium chain identity and minimum-version checks instead of carrying inherited wire-format compatibility paths.

### 2026-05-26 - Reset Qortium transaction version baseline

Reset Qortium's transaction version baseline to version 1 while keeping the current transaction layouts. The old timestamp gate for transaction version 6 was removed, typed AT transactions are now always serialized with their payment-or-message type, and arbitrary transactions now always use the current resource/payment layout under the new baseline.

### 2026-05-26 - Reset Qortium block version baseline

Reset Qortium's block version baseline from the inherited version 4 marker to version 1. New genesis and minted blocks now use the Qortium baseline version directly, chain configs no longer need to carry a block-version field, and an old pre-version-2 feature gate was removed because version 1 now represents the current Qortium block format.

### 2026-05-26 - Reset HSQLDB baseline schema version

Reset the fresh Qortium HSQLDB baseline to schema version 1 now that all current tables are part of the baseline schema. Existing repositories that still report the temporary version 2 state are no longer accepted as current, which keeps the new baseline clean while preserving the update system for future database changes after a chain is started.

### 2026-05-26 - Prefer synchronization during stale chain catch-up

Changed stale chain catch-up so a node that can see a clearly newer peer tip will defer local minting and synchronize first. During stale catch-up, synchronizer peer selection now prefers the highest and newest eligible peer tip instead of picking randomly, reducing the chance that a delayed node creates avoidable local fork blocks while a better seed chain is already reachable.

### 2026-05-26 - Raise preview outbound peer target

Raised the preview network's outbound peer target so tester and seed profiles try to keep two peer connections instead of stopping after one. This gives preview nodes a better chance of staying connected to both public seed paths while keeping the blockchain peer minimum low enough for small-network testing.

### 2026-05-26 - Remove remaining non-image Qortal text

Cleaned up the last active non-image Qortal wording found outside historical and provenance notes. A fresh-database error now refers to inherited upstream database versions, and crypto test messages and temporary paths now use Qortium naming.

### 2026-05-26 - Rename Java package namespace to Qortium

Renamed the active Java package namespace from `org.qortal` to `org.qortium` across source code, tests, build settings, runtime entrypoints, logging config, tools, and installer metadata. This removes one of the last active Qortal identity markers from normal builds and stack traces while keeping historical fork-provenance notes and foreign-coin server defaults unchanged.

### 2026-05-26 - Remove package namespace from public test commands

Updated public testing docs so Maven examples use simple test class selectors instead of the inherited `org.qortal` Java package path. This keeps the remaining package namespace detail out of normal user-facing verification commands while leaving the actual runtime Java package and main class unchanged for a later, larger refactor.

### 2026-05-26 - Remove remaining active Qortal branding

Removed several active Qortal-era names from the public-facing Qortium tree. The command-line helper is now named `qortium` instead of `qort`, the remaining `qortal.ico` resource was replaced with a Qortium-named icon, cross-chain API metadata now says `supportsLocalChainTrades` instead of `supportsQortTrades`, and older test strings and active docs were adjusted to use neutral Qortium wording. Historical provenance notes, upstream-reference docs, foreign-coin server defaults, and the deferred `org.qortal` Java package namespace remain unchanged.

### 2026-05-26 - Move Qortium network ports to Qortium ranges

Changed Qortium's default network ports away from the inherited Qortal ranges. Mainnet now uses the `1489x` range and testnet or preview profiles use the `2489x` range for API, peer, developer proxy, and QDN traffic. The preview and testnet settings, scripts, Docker profiles, helper tools, and setup docs were updated together so new nodes, seed nodes, and local tooling all agree on the Qortium-specific ports.

### 2026-05-26 - Add on-chain QDN resource deletion

Added an on-chain delete method for QDN resources so a name owner can publish a deletion record instead of only replacing a resource with empty files. Deleted resources are removed from the searchable resource cache, stay hidden across cache rebuilds, can be restored by orphaning the delete transaction, and can be republished later with a new PUT. The API now has builders for unsigned delete transactions while the existing local delete endpoint remains focused on clearing cached or hosted data from the local node.

### 2026-05-25 - preview: add second public seed profile

Added the Netcup preview seed at `185.207.104.78` alongside the existing Regxa seed at `146.103.42.59`. Preview participants now start with both seed nodes in their peer list, seed operators have separate launch profiles for each public IP, the seed profiles know about each other, and preview genesis includes a separate Netcup minting authorization. The preview packaging, reset scripts, docs, and local ignored seed-key notes now include the second seed configuration.

### 2026-05-25 - api: allow fee-free builders before mempow nonce

Changed raw unsigned transaction builder validation so MemoryPoW-capable transactions can be built with a zero fee before their fee-alternative nonce has been computed. Normal transaction processing and block validation still require either a paid fee or a valid MemoryPoW nonce, but local API clients can now build transactions such as name registration, pass the raw bytes to the nonce-compute endpoint, and then sign and submit them without needing a native-asset balance first.

### 2026-05-25 - mempow: use monotonic timing for nonce work

Changed memory proof-of-work timing so online account nonce logs measure elapsed work with a monotonic clock and never divide by a zero-millisecond duration. Fast nonce calculations now report a small finite elapsed time and hashrate instead of `Infinity`, and memory proof-of-work timeouts now use monotonic elapsed time so clock or NTP offset changes cannot distort timeout behavior.

### 2026-05-25 - network: let stale catch-up synchronize older tips

Extended stale-chain catch-up handling through the inner synchronizer checks that collect common blocks, compare peer chains, and fetch peer blocks. This prevents delayed preview, testnet, or newly launched chains from passing the first peer filters but then quietly refusing to synchronize because their own latest block timestamp is still older than the normal recent-tip window.

### 2026-05-25 - network: allow stale chains to catch up

Added an explicit stale-chain catch-up mode for nodes whose latest block timestamps are older than the normal recent-tip threshold but whose next block can already be minted safely. This lets delayed preview, testnet, or newly launched chains continue syncing, broadcasting online accounts, and minting toward current time without loosening block timestamp validation. The preview genesis timestamp was also refreshed so the next public preview reset starts near normal one-minute block timing.

### 2026-05-25 - network: reject peers with different chain identity

Added a chain identity check to the peer handshake so nodes advertise and compare their network ID, genesis block signature, and base blockchain config hash before they are accepted as compatible peers. This prevents preview, testnet, mainnet, or stale-package nodes from connecting long enough to exchange blocks that only fail later during sync, making reset and public preview setup safer and easier to diagnose. The preview runtime cleanup and ignore rules now also cover the fallback backup folder name that can be created by older or default settings.

### 2026-05-24 - preview: add release package builder

Added a preview release packaging script that builds or reuses the current jar and creates `target/qortium-preview.zip` with the jar, preview configs, Unix scripts, Windows wrappers, and tester guide. The preview README now documents the packaging command and clarifies that generated runtime files, local settings, logs, API keys, keystores, backups, and preview secrets are intentionally excluded from the release zip.

### 2026-05-24 - preview: improve cross-platform launch scripts

Improved the preview launcher so Unix-like systems can start without `setsid`, release-zip users get clearer jar-location guidance, and successful starts print the next status/stop/reset commands. Added Windows batch and PowerShell wrappers for starting, checking, stopping, and resetting preview nodes, with reset cleanup covering the separate Windows error log.

### 2026-05-24 - preview: add public tester guide

Added a simple public preview tester guide with separate download-zip and source-build paths, OS-specific start/status/stop/reset commands, prerequisites, reset notes, and preview expectations. The existing preview README now points testers to the guide while staying focused on technical seed-node and preview-profile details, and the build helper now prints preview commands after a successful source build.

### 2026-05-24 - api: add generic fee MemoryPoW nonce compute

Added a shared transaction API endpoint for computing MemoryPoW fee-alternative nonces on raw unsigned transactions. This gives public preview users a standard way to prepare zero-fee group joins, approvals, native-asset bootstrap proposals, and other normal transactions before the native asset exists, without adding transaction-specific nonce endpoints for every builder.

### 2026-05-24 - preview: remove native prefunds from genesis

Removed the preview network's genesis native-asset issue and starter account balances so the public preview starts without asset ID `0` and without prefunded accounts. The preview genesis still keeps the initial minting authorizations needed for the seed and local starter nodes to produce blocks, while native rewards remain paused until the development group explicitly bootstraps the native asset later.

### 2026-05-24 - config: lower default MemoryPoW difficulties

Lowered the configured MemoryPoW defaults so preview and forked networks can use proof-of-work as a practical spam control without making normal fee-free transactions unnecessarily slow. Fee alternatives and confirmable messages now use difficulty 12, arbitrary transactions use difficulty 11, unconfirmable messages use difficulty 10, and chat remains at difficulty 8.

### 2026-05-24 - consensus: use one chat proof-of-work difficulty

Removed the native-asset balance threshold from CHAT proof-of-work. Chat messages now use one configured MemoryPoW difficulty whether or not the chain has a native asset and whether or not the sender has any native balance, keeping chat spam prevention predictable across bootstrap and no-native-asset networks.

### 2026-05-24 - consensus: make current message behavior the baseline

Removed the legacy MESSAGE transaction mode that depended on the old MemoryPoW update timestamp. Qortium now always uses the current MESSAGE rules, where only messages to AT addresses can be confirmed in blocks, regular MESSAGE transactions remain unconfirmed coordination records, and the two active message proof-of-work settings use clear confirmable and unconfirmable names instead of v1/v2 labels.

### 2026-05-24 - test: add repeatable MemoryPoW benchmark mode

Added a repeatable benchmark mode to the MemoryPoW test helper so Qortium can measure real transaction proof-of-work costs before changing preview or main chain difficulty settings. The helper now initializes test time correctly for timeout-based runs, reports per-difficulty timing summaries, and keeps the older simple timing mode available for quick manual checks.

### 2026-05-24 - config: move transaction MemoryPoW difficulties into chain config

Moved the MemoryPoW difficulty values used by transaction fee fallback, arbitrary data publishing, chat, and messages out of hard-coded Java constants and into each chain's blockchain configuration. The default values stay the same, but mainnet, preview, testnet, and test chains can now choose their own transaction proof-of-work difficulty profile without rebuilding the jar.

### 2026-05-24 - preview: refresh public candidate chain settings

Refreshed the preview-network genesis timestamp for the public-preview candidate reset and shortened the preview-only chain-parameter activation delay to 20 blocks. This keeps the reset chain aligned with the current launch window and lets the governance proposal workflow be tested through activation before inviting public testers.

### 2026-05-24 - preview: allow local participant transaction builders

Allowed preview participant nodes to use normal local transaction-builder API calls while keeping the public seed node restricted. This lets testers build payments, group joins, governance proposals, and other signed transactions through their own local node without opening the seed API or weakening the local-only API whitelist.

### 2026-05-24 - gui: set application taskbar icon

Centralized Qortium's desktop application icons so the startup splash window and supported Java taskbars use the same stable Qortium logo images instead of tray-state artwork or platform defaults. The GUI startup path now sets the Java taskbar icon when the desktop supports it, keeps headless startup as a safe no-op, and has focused tests proving the packaged app icon resources load correctly.

### 2026-05-24 - preview: detect headless launch mode

Changed the preview-network launcher so it only starts Java in headless mode when the local environment appears to have no desktop display. Local desktop participants can now get the normal Qortium splash and tray UI by default, while VPS and terminal-only nodes still avoid graphical startup automatically. The launcher also supports explicit `--headless` and `--gui` overrides for cases where automatic detection is not the desired behavior.

### 2026-05-24 - preview: harden background launch script

Forced the preview-network launch helper to start Java in headless mode and detach it into its own session. Preview nodes are meant to run as background server processes on local machines and VPS hosts, so avoiding desktop tray initialization and shell-session cleanup makes the launcher more reliable when testing from terminals, SSH sessions, and other automation.

### 2026-05-24 - preview: bootstrap native asset funds

Added the preview network's native asset and initial account balances to genesis so early testers can use normal paid transactions instead of depending on slow memory proof-of-work fallback paths. The seed and local starter minting accounts now begin with preview funds they can use for chat, QDN publishing, minting-group joins, and sending small amounts to new testers during the alpha preview.

### 2026-05-24 - sync: answer startup block requests promptly

Reduced the initial delay before the queued block and block-summary request processors start handling peer requests. Fresh nodes can be asked for their first minted blocks within seconds of startup, so waiting a full minute before answering `GET_BLOCK` requests caused synchronization retries to time out during preview launch and made peers look unresponsive even though the block existed locally.

### 2026-05-24 - preview: handle delayed genesis startup

Made fresh preview-network startup tolerate a genesis timestamp that is already outside the normal recent-block window. The minter can now produce the first post-genesis block without waiting for recovery mode, synchronization can use stale peers while recovery mode is active so delayed chains can catch up instead of forking blindly, and the preview stop helper now force-exits an old node after a timeout so reset scripts do not hang during relaunch.

### 2026-05-24 - preview: refresh alpha launch timestamp

Refreshed the preview-network genesis timestamp for the clean two-node launch after fixing the genesis-only peer synchronization path. This keeps the alpha preview reset aligned with the actual startup window so the seed and local participant can begin from a fresh shared genesis.

### 2026-05-24 - sync: recognize shared genesis-only peers

Fixed a fresh-network synchronization edge case where two nodes that were both still at genesis could misclassify each other as having no common block. Qortium now recognizes a peer reporting the same genesis block as sharing that common block, preventing the peer from being marked as misbehaved before the first post-genesis block is minted.

### 2026-05-24 - preview: refresh alpha genesis timestamp

Refreshed the preview-network genesis timestamp for the initial two-node launch attempt. Keeping the preview genesis close to the scheduled start time prevents the first post-genesis blocks from looking stale to peer selection and synchronization logic while this alpha chain is still being reset freely.

### 2026-05-24 - preview: add initial minting accounts

Added two initial preview minting accounts so the VPS seed and one local test node can start producing blocks from the fixed preview genesis once each node installs its own private minting key locally. The chain config only includes the public account and minting authorization data, while the generated private keys are kept in an ignored local preview secrets folder.

### 2026-05-24 - preview: add public alpha network profile

Added a separate preview-network runtime profile for the shared Qortium alpha demo network. The local `testnet/` folder remains a disposable single-node lab, while `preview/` uses fixed shared chain data, accelerated preview reward and account-level timing, a lower share-bin activation count for small preview groups, connects participants to the VPS seed at `146.103.42.59`, keeps generated runtime files out of git, and documents that minting identities still need to be added later before preview blocks can advance.

### 2026-05-23 - test: suppress expected negative-path error logs

Added a test-only logging helper and used it around restart and minting checks that intentionally trigger failure paths. The production errors still log normally, but expected negative-path tests no longer make the Surefire reports look like they contain real runtime errors.

### 2026-05-23 - utils: avoid EPC critical log during shutdown

Changed the execute-produce-consume worker utility so normal shutdown no longer reports the final worker thread exit as a critical error. Unexpected worker-pool death still logs loudly, but clean shutdowns now leave the test and node logs focused on real problems.

### 2026-05-23 - test: close repository transactions in warning cases

Cleaned up repository tests that intentionally trigger failed orphan work or direct database writes so they now explicitly commit or roll back before closing their repository handles. This removes noisy critical transaction warnings from the test reports and makes the remaining test output easier to trust.

### 2026-05-23 - api: register update-poll transaction JSON fields

Registered update-poll transactions with the shared transaction JSON model so API responses and JSON serialization include the poll update fields, including the owner address, owner public key, poll ID, replacement poll name, and replacement description.

### 2026-05-23 - crosschain: build legacy spends without bitcoinj wallets

Reworked standard Bitcoiny spend creation so legacy wallet spends are built with Qortium's own deterministic key and raw transaction code instead of bitcoinj's wallet parser. This lets Bitcoin test4 and custom altcoin network settings build spend transactions without failing on network identifiers that bitcoinj does not recognize.

### 2026-05-23 - test: parse mock bitcoiny raw transactions

Repaired the mock Bitcoiny blockchain provider so raw transactions registered by tests are also available through the parsed transaction lookup used by HTLC secret discovery. This keeps cross-chain HTLC tests focused on redeem-script behavior instead of failing because the mock provider only stored raw bytes.

### 2026-05-23 - test: repair transaction importer sync setup

Repaired transaction importer tests so local API chat submissions run with the same up-to-date single-node test setup used by transaction API coverage. This keeps the tests focused on chat import, deduplication, and notification behavior instead of failing on missing peer synchronization preconditions.

### 2026-05-23 - arbitrary: preserve metadata mime type fallback

Repaired arbitrary data metadata creation so single-file uploads still fall back to the filename when content-based MIME detection cannot provide a usable value. This keeps common files such as text documents from losing their MIME type in stored metadata.

### 2026-05-23 - polls: repair poll id and update behavior

Repaired poll updates so existing polls are changed by their stable numeric poll ID instead of relying on a generic save path that did not reliably handle renames. The poll tests now read assigned create-poll IDs from the confirmed transaction record and keep update-poll serialization checks focused on the optional end-time field.

### 2026-05-23 - test: use valid synthetic account keys

Changed account and repository performance fixtures to create deterministic accounts from valid private keys instead of treating arbitrary random bytes as public keys. The account balance history test now uses a direct payment for its balance change instead of assuming a newly minted block rewards Alice, keeping the tests stable under current reward and Ed25519 key validation rules.

### 2026-05-23 - test: repair chat fixture setup

Repaired chat and private-group test fixtures so they follow the same rules enforced by normal group creation and secured API calls. The affected tests now use group names within the configured limit and provide the expected test API key when calling secured chat endpoints.

### 2026-05-23 - test: repair chain parameter update coverage

Repaired chain-parameter test coverage so the generic transaction serialization suite knows how to build chain-parameter update transactions, and so account trust tests match the current suspicious-rater validation rules. The tests now explicitly cover that a one-rater suspicious threshold remains invalid under the configured category-policy caps.

### 2026-05-23 - network: keep peer direction as reachability preference

Changed peer direction handling so a working peer connection is not disconnected only because it is not the preferred node-ID direction. Qortium now keeps stable fallback connections until a preferred-direction replacement has actually connected, and it uses a clearer inbound reachability signal for P2P and QDN direct-connect decisions.

### 2026-05-23 - docs: update category policy chain parameter reference

Updated the on-chain chain-parameter design reference so the account trust category policy table is documented as an implemented parameter instead of a planned one. The reference now includes its activation behavior, typed API paths, validation expectations, and trust snapshot impact.

### 2026-05-23 - test: cover account trust category policy activation

Added activation tests for the account trust category policy chain parameter. The tests show that approved policy tables only affect trust derivation at their activation height, refresh trust snapshots when they activate, and are removed again if the approval-settlement block is orphaned.

### 2026-05-23 - api: add account trust category policy parameter endpoints

Added typed API support for proposing and reading the account trust category policy table. The API can now build development-group proposals from category-policy objects, return the effective policy table for a block height, and show decoded category policies in chain-parameter effective-value and proposal summaries.

### 2026-05-23 - consensus: add account trust category policy parameter

Added the account trust category policy table as an approved on-chain chain parameter. Development-group proposals can now carry the canonical category thresholds and score caps, nodes can read the active overlay by block height, and trust derivation knows this parameter affects trust snapshots.

### 2026-05-23 - consensus: add contextual chain parameter validation

Added activation-height-aware validation for chain parameter updates that need to compare against other effective policy values. Suspicious account trust rater-count proposals now have to stay high enough for the active category-policy caps to reach their suspicious thresholds, preparing the validator for the full account trust category policy table.

### 2026-05-23 - consensus: add account trust category policy codec

Added the deterministic encoder, decoder, and validation rules for the planned account trust category policy chain parameter. This prepares the full trust threshold and cap table for a future development-group vote while keeping the parameter inactive until the on-chain registry, API builder, and activation path are added.

### 2026-05-23 - consensus: make account trust category policies height-aware

Prepared account trust category policy thresholds and caps for on-chain governance by routing trust derivation and API policy and explanation views through a height-aware category policy settings object. Current behavior still falls back to `blockchain.json`, but the threshold and cap reads now have the same shape needed for the planned full-table chain parameter.

### 2026-05-23 - docs: define account trust category policy parameter format

Defined the planned on-chain format for account trust category policy updates before implementing it. The design reserves one atomic parameter for the full category-policy table, fixes the category and level ordering, records the validation rules that keep thresholds and caps coherent, and keeps the active trust-weight category config-only for now.

### 2026-05-23 - consensus: add on-chain suspicious trust decision updates

Added development-group approved updates for the suspicious trust decision requirements used by account trust level decisions. Qortium can now store voted suspicious rater, branch, and rating-confidence requirements on chain, apply them to trust derivation from the approved activation height, expose typed API helpers for proposing and reading the values, and refresh trust snapshots when those values activate.

### 2026-05-23 - consensus: add on-chain positive trust branch count updates

Added development-group approved updates for the positive trust branch count used by account trust level decisions. Qortium can now store a voted independent-branch requirement on chain, apply it to trust derivation from the approved activation height, expose typed API helpers for proposing and reading the value, and refresh trust snapshots when the voted value activates.

### 2026-05-23 - consensus: make account trust decision settings height-aware

Prepared account trust level decision thresholds for on-chain governance by grouping the positive branch and suspicious decision requirements into one height-aware policy object. Trust derivation and API explanations now carry the decision settings used for that derivation, while existing config defaults and API fields stay unchanged until individual voted parameters are added.

### 2026-05-23 - consensus: add on-chain account trust manager energy hop updates

Added development-group approved updates for account trust manager energy hops. Qortium can now store a voted manager-hop count on chain, apply it to account trust derivation from the approved activation height, expose typed API helpers for proposing and reading the value, and refresh trust snapshots when the voted value activates.

### 2026-05-23 - consensus: make account trust manager energy hops height-aware

Prepared account trust manager energy hops for on-chain governance by routing trust derivation and trust policy views through height-aware lookup methods. The active value still falls back to `blockchain.json`, but the manager-energy flow now has the same lookup shape as other voted account trust policy settings.

### 2026-05-23 - consensus: add on-chain account trust starting energy updates

Added development-group approved updates for account trust starting energy. Qortium can now store a voted starting-energy value on chain, apply it to account trust derivation from the approved activation height, expose typed API helpers for proposing and reading the value, and refresh trust snapshots when the voted value activates.

### 2026-05-23 - consensus: make account trust starting energy height-aware

Prepared account trust starting energy for on-chain governance by routing trust derivation and trust policy views through height-aware lookup methods. The active value still falls back to `blockchain.json`, but the consensus path now has the same shape as other voted trust policy settings before the starting-energy parameter is added.

### 2026-05-23 - api: add long chain parameter value support

Added API and decoding support for plain signed-long chain parameter values without adding a new voted parameter yet. Future policy values that are stored as 8-byte integers can now be exposed through plain `longValue` fields and displayed as ordinary base-10 numbers, while amount-style parameters continue to use decimal amount formatting.

### 2026-05-23 - consensus: refresh trust snapshots on chain parameter activation

Added the activation hook that refreshes stored account trust snapshots when an approved on-chain parameter affects trust snapshot output. Trust status vote-weight updates now refresh snapshot rows at their activation height and roll back correctly when that activation block is orphaned, while unrelated parameter activations leave snapshots untouched.

### 2026-05-23 - docs: define account trust policy parameter roadmap

Documented the proposed path for adding more account trust policy values to the on-chain parameter system. The roadmap keeps the next phase focused on simple scalar settings, reserves planned parameter IDs for those values, and defers larger category policy tables until their binary format and cross-field validation rules are designed.

### 2026-05-23 - api: expose chain parameter validation metadata

Added structured validation hints to the chain-parameter metadata API so wallets, governance tools, and operators can see the accepted value shape for each voted parameter without hardcoding it separately. The metadata now reports numeric minimums, integer-list lengths, ordered labels, and positive-value requirements for the currently supported on-chain parameters.

### 2026-05-23 - consensus: tighten chain parameter validation guards

Tightened the first on-chain parameter validation rules so approved reward-share weight updates must keep a positive level 1 reward floor, and trust status vote-weight updates cannot set every status to zero weight. Also updated the remaining account vote-weight helper to use the active on-chain trust vote weights, keeping direct account lookups aligned with the API and voting paths.

### 2026-05-23 - consensus: add on-chain trust status vote weight updates

Added development-group approved updates for the trust status vote-weight percentages used by account-trust voting calculations. Qortium can now store voted weights for Suspicious, Unverified, Bronze, Silver, and Gold statuses on chain, apply them automatically from the approved activation height, expose typed API helpers for proposing and reading the values, and keep falling back to `blockchain.json` when no approved overlay exists.

### 2026-05-22 - consensus: add on-chain account rating cooldown updates

Added development-group approved updates for the account rating change cooldown. Qortium can now store a voted cooldown block count on chain, apply it to future rating changes from the approved activation height, expose typed API helpers for proposing and reading the value, and continue falling back to `blockchain.json` when no approved overlay exists.

### 2026-05-22 - docs: refresh on-chain parameter reference

Updated the on-chain chain-parameter reference so it lists reward share weights as an already supported voted parameter, documents the canonical weight format, and points chain builders to the typed API endpoints for proposing and reading reward-share weight overlays.

### 2026-05-22 - cleanup: remove stale reward-share minting leftovers

Removed an unused reward-share validation result and its translations, and cleaned up stale wording that still described payout reward shares as possible minting or online-account keys. This keeps the code comments and validation catalog aligned with the current model where self-shares mint and non-self reward shares only allocate payouts.

### 2026-05-22 - consensus: index online accounts by self-shares

Changed online-account block indexes so they are built from self-share minting-key records only. Non-self reward shares still record payout allocations, but they no longer occupy online-account index space, shift valid minting keys, or appear in signer lookups as possible block minters.

### 2026-05-22 - docs: clarify reward-share minting wording

Clarified API wording and code comments around reward shares so operators can distinguish self-share minting-key records from non-self payout records. Self-share private keys are the only reward-share keys that belong in BlockMinter, while non-self reward shares allocate a minter's earned rewards to recipients.

### 2026-05-22 - consensus: decouple reward-share payouts from minting keys

Changed reward shares so non-self shares are payout allocation records instead of separate minting keys. A minter now earns rewards through their online self-share, then the minter's configured payout shares are applied to that earned amount and recipients can receive rewards without being online or in the minting group. Total external payout shares are capped at 100 percent, and the practical per-minter reward-share limit is raised to 100 records.

### 2026-05-22 - consensus: remove reward-share creation eligibility gate

Removed the old minting-eligibility check from reward-share creation. Accounts can now publish self-share signing-key records or normal reward-share payout records without already being eligible to mint, while block production and online-account checks still ignore reward-share keys whose minter is not currently allowed to mint. This keeps reward shares focused on payout/signing records instead of using them as the minting permission mechanism.

### 2026-05-22 - consensus: add on-chain reward share weight updates

Added development-group approved updates for reward share weights across account levels 1 through 10. Qortium can now store a voted 10-weight reward curve on chain, normalize those weights into active reward-share bins from the approved activation height, expose builder and effective-value API helpers, and continue falling back to the configured `blockchain.json` reward curve when no approved overlay exists.

### 2026-05-22 - test: set NTP offset for no-native bootstrap tests

Pinned the test clock for the no-native asset bootstrap suite after it loads its custom settings. This lets those tests validate native-asset bootstrapping behavior instead of failing early because transactions and online-account timestamps cannot be checked against an unset test clock.

### 2026-05-22 - rewards: split reward shares into individual level weights

Changed the baseline reward share table from five two-level bins into ten individual level bins that follow the simple level-weight curve from level 1 through level 10. This gives each account level its own payout step while preserving full reward distribution through the existing normalized reward logic, and it prepares the reward curve for a later on-chain governance parameter that can use human-readable weights.

### 2026-05-22 - test: generate valid random test accounts

Changed the shared test helper that creates random accounts so it derives a real account from a random private key instead of inventing raw public-key bytes. This keeps tests compatible with public-key validation when they need a throwaway recipient address, and avoids masking reward-batching behavior behind invalid test data.

### 2026-05-22 - api: add effective chain parameter state view

Added a read-only chain-parameter state view so operators can see every supported parameter's effective value at a chosen height, whether it comes from the base chain config or an approved on-chain overlay, and whether another approved value is scheduled for a future activation height. This makes voted parameter changes easier to audit without changing consensus behavior.

### 2026-05-22 - consensus: add on-chain name registration fee updates

Added development-group approved updates for the name-registration transaction unit fee. Qortium can now store an approved name-registration fee overlay on chain, apply it from its activation height during `REGISTER_NAME` fee validation, and expose typed API helpers for building and inspecting name-registration fee proposals while keeping the normal transaction unit fee separate.

### 2026-05-22 - consensus: add on-chain unit fee updates

Added development-group approved updates for the normal transaction unit fee. Qortium can now store an approved unit-fee overlay on chain, apply it from its activation height during normal transaction fee validation, and expose typed API helpers for building and inspecting unit-fee proposals while leaving the separate name-registration fee schedule unchanged.

### 2026-05-22 - consensus: start share-bin activation checks at level one

Changed the reward share-bin activation rule so the minimum-online-minter check starts at level one instead of level seven. The level 1-2 bin remains the bottom payout floor, so underpopulated higher bins can safely cascade downward without trying to merge below the configured reward levels. This makes the existing activation-count parameter apply across the reward ladder while keeping the activation start level as a static chain setting.

### 2026-05-22 - test: align reward tests with level-gated payouts

Updated older reward tests so they wait until the test minter reaches a reward-paying account level before expecting block reward balances to change. This keeps the tests aligned with Qortium's current rule that level zero accounts may mint blocks but are not included in reward share bins.

### 2026-05-22 - consensus: mirror reward distribution when orphaning

Changed reward orphaning so it calculates the same positive reward split used during block processing and then reverses the final balance changes. This keeps tiny rounding remainders from being left behind when reward-bearing blocks are orphaned, making block reward processing and orphaning exact opposites again.

### 2026-05-22 - consensus: add on-chain share-bin activation count

Added the first integer-based chain parameter update for the reward share-bin activation count. Development-group approved proposals can now change the minimum number of online minters required before a reward share bin is treated as active, with the value stored on chain and applied automatically from its activation height while falling back to `blockchain.json` when no approved overlay exists.

### 2026-05-22 - refactor: centralize chain parameter metadata

Moved chain-parameter metadata and value display decoding into the shared `ChainParameter` definition so future parameters can be added in one place without duplicating API-specific descriptions, paths, or formatting rules. The public API shape and consensus behavior remain unchanged.

### 2026-05-22 - consensus: add chain parameter activation guardrails

Added a required activation lead time for on-chain chain-parameter updates. Approved proposals must now leave a configured number of blocks before activation, which gives operators time to see and prepare for a voted-in change instead of allowing a parameter update to take effect immediately after approval.

### 2026-05-22 - api: add chain parameter update visibility

Added a readable chain-parameter proposal view so operators can inspect development-group proposals without manually searching raw transactions. The new endpoint lists block reward update proposals with decoded values, approval status, activation height, current yes and no vote counts, approval-authority count, and whether an approved proposal is currently effective.

### 2026-05-22 - api: add chain parameter metadata and block reward builder

Changed the chain-parameter API from a generic binary update builder to a safer first public surface for supported parameters. Nodes now list the available on-chain parameters and provide a block-reward-specific builder that accepts a normal reward amount, fills in the recommended fee when needed, and still emits the same development-group approved `CHAIN_PARAMETER_UPDATE` transaction bytes for signing and submission.

### 2026-05-22 - consensus: add on-chain chain parameter updates

Added the first development-group approved chain-parameter update path. Qortium can now store approved parameter overlays on chain, require explicit future activation heights, and apply an approved block reward change from that height onward while falling back to `blockchain.json` when no approved overlay exists. The new transaction, schema migration, API builder/query endpoints, tests, and chain-builder documentation create the narrow foundation for later fee, reward-split, and trust-parameter updates without requiring a rebuilt jar for every change.

### 2026-05-22 - consensus: remove group approval rejection threshold

Changed group approval so no votes no longer reject a pending transaction by meeting the approval threshold. Approval-required transactions now either approve when enough yes votes arrive after the minimum delay or expire at the maximum delay, while no votes remain recorded as visible opposition without acting as vetoes.

### 2026-05-22 - api: default arbitrary search confirmation status

Changed arbitrary transaction search so requests that omit the confirmation status now default to confirmed transactions instead of failing with an internal server error. QDN transaction searches can now use the shorter query form safely, while callers that need unconfirmed or mixed results can still request those statuses explicitly.

### 2026-05-21 - api: default transaction search confirmation status

Changed transaction search so requests that omit the confirmation status now default to confirmed transactions instead of failing with an internal server error. Address-based transaction searches can now use the shorter query form safely, while callers that need unconfirmed or mixed results can still request those statuses explicitly.

### 2026-05-21 - rewards: remove admin block reward payouts

Removed the inherited special block reward payouts for minting group admins and development group admins. Block rewards now go only to eligible online minters in the configured minting groups, with active level-share bins normalized so the available reward is fully distributed through normal minter and reward-share rules. The related tests were updated to cover normalized minter payouts, and the old admin-replacement test fixtures were removed.

### 2026-05-21 - qdn: count resource chunks by transaction

Changed QDN resource status reporting so local chunk counts are based on the files that belong to the specific resource transaction instead of every file in the surrounding storage directory. Tiny on-chain resources now report accurate progress such as one local item out of one expected item, rather than inflated counts from unrelated temporary files.

### 2026-05-21 - api: validate missing transaction creators

Changed transaction API validation so requests with a missing or malformed creator public key fail with a normal transaction validation error instead of an internal server error. This keeps malformed group creation requests, including requests that use the wrong public-key field name, from producing raw Jetty 500 responses.

### 2026-05-21 - testnet: start batch rewards at genesis

Changed the single-node testnet chain setup so batch rewards are active from the beginning of a fresh chain. The testnet keeps the short local batch size of 10 blocks and the 3-block online-account proof window, making the reward behavior easy to observe without waiting for a high activation height.

### 2026-05-21 - testnet: ignore local data directory

Added the local single-node testnet data directory to git ignore rules so QDN storage and other runtime files created during live testnet runs do not appear as repository changes.

### 2026-05-21 - tray: show node status in Linux tooltip

Changed the Linux tray tooltip so desktop panels that only display the StatusNotifier title still show the live node status, including minting state, peer count, and height. Panels that support the longer tooltip description still keep the build version detail.

### 2026-05-21 - testnet: quiet single-node startup logs

Cleaned up local single-node testnet startup behavior so fresh runs report build metadata once and skip peer-sync messages that do not apply to an intentional no-peer node. The Linux tray now publishes a real Qortium icon, tooltip, and context menu through the desktop StatusNotifier path, and the smoke helper checks the latest startup log so those regressions are easier to catch.

### 2026-05-21 - repository: reboot HSQLDB schema baseline

Replaced the inherited HSQLDB migration replay with a direct Qortium baseline schema for fresh repositories. New databases now initialize to the first Qortium schema version without replaying old Qortal upgrade steps, while inherited repository versions fail with a clear reset/bootstrap message instead of pretending to be supported.

### 2026-05-21 - testnet: add live smoke checks

Added a live smoke-test helper for the disposable single-node testnet so testers can verify more than basic API reachability. The helper checks that the node is running as a local testnet, the default minting account is installed, blocks are being minted, expected empty-startup resources are still empty, default groups exist, no peers are connected, and the startup log has no unexpected errors or warnings.

### 2026-05-21 - foreign-fees: treat missing backups as normal startup

Changed foreign-fee backup import logging so a fresh node that has no existing foreign-fee backup files reports that state as informational instead of warning-level. This keeps new local testnet logs focused on real problems while preserving visibility that there was nothing to import.

### 2026-05-21 - api: use Jetty baseResource for docs static files

Changed the API documentation static-file servlet setup to use Jetty's current `baseResource` init parameter instead of the deprecated `resourceBase` name. This removes a startup warning from nodes with API documentation enabled while preserving the existing Swagger UI and disabled-documentation pages.

### 2026-05-21 - testnet: use admin API key for minting setup

Changed the local testnet startup helper so it reads the generated admin API key before adding the default minting key. This keeps the single-node testnet walkthrough working now that protected admin API calls require the API key even from localhost.

### 2026-05-21 - Disable public QDN auth bypass by default

Changed QDN API defaults so normal nodes require the admin API key before serving raw or rendered QDN data through API routes. Operators can still explicitly enable public QDN serving with `qdnAuthBypassEnabled`, and gateway mode continues to force public access because that is required for gateway operation.

### 2026-05-21 - Validate public keys before account construction

Changed public-key account construction so malformed public keys are rejected before Qortium derives an address or builds Ed25519 key parameters. Trade-bot create requests now reject missing or wrong-sized creator public keys as invalid request data instead of relying on lower-level account construction to fail later.

### 2026-05-21 - Validate crypto signature inputs before verification

Changed signature verification so malformed public keys, signatures, and messages are rejected with a clear `false` result before they reach the Ed25519 verifier. This keeps external bad input from becoming a server error while avoiding a broad exception catch around normal cryptographic verification.

### 2026-05-21 - Hash decoded auto-update JARs in manifests

Changed QDN auto-update manifests so their SHA-256 hash now covers the decoded JAR that Qortium writes and runs, instead of the XORed transport file published to QDN. The update file is still stored as `qortium.update`, but the integrity check now describes the executable bytes directly, and the publishing documentation and helper script use the same definition.

### 2026-05-21 - Clarify developer proxy GET-only request handling

Changed a developer proxy code comment so it no longer presents non-GET request forwarding as an unfinished bug. The comment now explains that the proxy currently exposes GET handlers only, and that future non-GET support would need deliberate request-body forwarding.

### 2026-05-21 - Limit QDN HTML rewrite buffering

Changed QDN HTML rendering so very large HTML files are rejected before they are loaded into memory for rewriting. This keeps normal HTML rendering behavior while putting a clear memory guard around the parser path.

### 2026-05-21 - Set QDN renderer content length before streaming

Changed QDN file rendering so non-HTML file responses declare their content length before bytes are streamed to the browser. This keeps response headers consistent for servlet containers while preserving the existing streamed file contents.

### 2026-05-21 - Handle developer proxy HTML content types and encoding

Changed the developer proxy so local frontend routes that return HTML are rewritten even when the URL does not end in `.html`. The proxy now asks upstream servers for uncompressed responses, safely decodes compressed HTML if it is still returned, and keeps rewritten HTML responses internally consistent.

### 2026-05-21 - Stream developer proxy non-HTML responses

Changed the developer proxy so non-HTML responses from a local frontend server are streamed directly instead of being fully buffered in memory. HTML responses are still buffered for rewriting, while ordinary assets keep their existing proxy headers and content behavior.

### 2026-05-21 - Close ForeignFeesManager backup writers safely

Changed foreign-fee backup writing so JSON backup files are written with safe UTF-8 file helpers instead of manually closed writers. This preserves the existing backup file contents while removing another close-on-failure risk.

### 2026-05-21 - Close ArbitraryResource string temp writer safely

Changed the string-upload path for QDN publishing so temporary files are written with an automatically closed UTF-8 writer. This preserves the existing uploaded-string contents and trailing newline while removing another manual file-close path from the API resource.

### 2026-05-21 - Close QDN metadata and patch writers safely

Changed QDN metadata and patch-file writing so writers are closed automatically even if a write fails. This keeps generated metadata and patch files using explicit UTF-8 output and removes another set of manual file-close paths.

### 2026-05-21 - Close ResourceList writers safely

Changed local resource-list saving so the JSON writer is always closed automatically, even if a write fails. This keeps list files using the same UTF-8 encoding used when they are read back and removes another manual file-close path.

### 2026-05-21 - Remove legacy HTTP auto-update support

Removed the remaining legacy HTTP auto-update settings and manifest handling so Qortium only uses the QDN-based update path. This keeps the update baseline simpler, avoids carrying old repository-list configuration, and makes `autoUpdateMode` the single update policy reported by the admin API.

### 2026-05-21 - Remove auto-update boolean setting

Removed the old `autoUpdateEnabled` setting so Qortium uses only `autoUpdateMode` to control update behavior. This keeps the baseline settings model simpler and avoids maintaining two names for the same policy.

### 2026-05-21 - Keep auto-update guard after apply launch

Changed auto-update install tracking so Qortium keeps the update in-progress flag set after the update helper process has been launched. This prevents repeated manual or automatic update attempts from starting multiple apply-update helper processes during the shutdown and restart handoff.

### 2026-05-21 - Prevent repeated restart apply attempts

Changed restart scheduling so Qortium refuses duplicate restart-apply requests while one is already scheduled or running. This prevents repeated authenticated admin calls or internal restart triggers from launching multiple restart helper processes before the node shuts down and comes back up.

### 2026-05-21 - Prevent repeated bootstrap apply attempts

Changed bootstrap apply scheduling so Qortium refuses duplicate bootstrap-apply requests while one is already scheduled or running. This prevents repeated authenticated admin calls from launching multiple bootstrap replacement processes before the node shuts down and restarts.

### 2026-05-21 - Set developer proxy upstream timeouts

Changed the developer proxy so connections to the local frontend server use fixed connect and read timeouts. This keeps a stalled local dev server from leaving Qortium proxy requests waiting indefinitely while preserving normal successful proxy behavior.

### 2026-05-21 - Handle developer proxy loopback redirect aliases

Changed the developer proxy so redirects that point back to the local frontend server through loopback aliases or protocol-relative locations are still kept inside Qortium's proxy. This prevents browser navigation from bypassing the proxy when a dev server uses `localhost`, `127.0.0.1`, IPv6 loopback, or `//` redirect URLs for the same local port.

### 2026-05-21 - Rewrite developer proxy local redirect locations

Changed the developer proxy so absolute redirects that point back to the local frontend server are returned as proxy-relative locations. This keeps browser navigation inside Qortium's developer proxy while still preserving relative redirects and redirects to external sites.

### 2026-05-21 - Preserve developer proxy redirects

Changed the developer proxy so redirects from a local frontend server are passed back to the browser with their original status and location instead of being followed internally by Qortium. This keeps browser navigation and frontend dev-server routing behavior visible to the app being tested.

### 2026-05-21 - Filter developer proxy request headers safely

Changed the developer proxy so it no longer forwards caller-owned request headers such as host, compression, and connection-control headers to the local frontend server. Safe custom headers still pass through, while the proxy keeps control of headers that could interfere with HTML rewriting or local proxy behavior.

### 2026-05-21 - Forward developer proxy response headers correctly

Changed the developer proxy so safe response headers from a local frontend server are forwarded reliably instead of being skipped by the old header-copy loop. The proxy still keeps control of content length, content type, content security policy, and connection-level headers so rewritten responses remain consistent.

### 2026-05-21 - Proxy developer error responses correctly

Changed the developer proxy so upstream error responses, such as 404 pages from a local frontend server, are returned with their original status and body instead of being converted into a Qortium API error. This makes the developer proxy behave more like a normal local web proxy when a requested file or route is missing.

### 2026-05-21 - Close developer proxy response streams safely

Changed the developer proxy so upstream response streams are closed automatically if reading proxied content fails. This keeps proxy responses the same while avoiding leaked connection streams during developer proxy I/O errors.

### 2026-05-21 - Close SSL certificate input streams safely

Changed SSL keystore creation so the generated CA and server certificate files are closed automatically if certificate parsing or keystore setup fails. This keeps certificate and keystore behavior unchanged while avoiding leaked file handles on SSL setup errors.

### 2026-05-21 - Close QDN renderer file streams safely

Changed QDN file rendering so source files are closed automatically if streaming a regular file to the API response fails. This keeps rendered content behavior the same while avoiding leaked file handles on response I/O errors.

### 2026-05-21 - Close random-access file reads safely

Changed utility file reads so random-access files are closed automatically if seeking or reading fails. This keeps the existing read behavior while avoiding leaked file handles on I/O errors.

### 2026-05-21 - Close block archive output streams safely

Changed block archive writing so the archive file output stream is always closed automatically if a write fails partway through. This keeps archive creation behavior the same on success while avoiding leaked file handles on write errors.

### 2026-05-21 - Remove legacy local auth bypass setting

Removed the unused local-auth bypass setting now that Qortium always requires the admin API key for protected API calls, including loopback requests. This keeps new chain settings cleaner and avoids suggesting that local network origin can still replace administrative authentication.

### 2026-05-21 - Remove legacy API key setting support

Removed the old settings-file API key migration path so Qortium only uses the dedicated `apikey.txt` file for administrative API credentials. This keeps the unreleased baseline cleaner for new chains and avoids carrying forward legacy credential handling that Qortium does not need.

### 2026-05-20 - Prevent overlapping bootstrap operations

Changed bootstrap maintenance so full-chain validation and bootstrap creation share one in-progress guard. Authenticated callers now get a quick conflict response when another bootstrap operation is already running, reducing duplicate heavy work and preventing overlapping access to shared bootstrap temp and output paths.

### 2026-05-20 - Prevent concurrent bootstrap validation

Changed bootstrap validation so only one full-chain validation can run at a time on a node. Additional authenticated requests now fail quickly with a clear conflict response instead of starting another expensive validation pass, reducing the chance that administrative use can overload the node.

### 2026-05-20 - Close archive compression streams safely

Changed archive compression so ZIP and 7z creation now close file streams and archive entries automatically when compression succeeds or fails. This prevents leaked file handles during interrupted archive creation while keeping the same archive formats and output layout.

### 2026-05-20 - Close file digest streams safely

Changed file digest handling so Qortium now closes digest input streams automatically even when a file read fails partway through. This prevents leaked file handles during exceptional reads while keeping the same SHA-256 digest behavior for normal file hashing.

### 2026-05-20 - Stop accepting API keys in URLs

Changed API authentication so the admin API key is no longer accepted through `apiKey` URL parameters. Local scripts and restart helpers now send the key in the `X-API-KEY` header instead, reducing the chance that admin credentials appear in shell history, process listings, browser history, or request logs.

### 2026-05-20 - Restrict API key file permissions

Changed API key storage so the local `apikey.txt` file is created and maintained as owner-readable and owner-writable only where the operating system supports those permissions. Existing key files are tightened when loaded, reducing the chance that another local user can read the admin API key.

### 2026-05-20 - Require auth for API key generation

Changed API key generation so the admin endpoint now requires the current API key before rotating credentials. First-time API keys are created locally during API startup instead of through an unauthenticated HTTP request, which keeps remote callers from bootstrapping their own administrative access.

### 2026-05-20 - Fix loopback API auth bypass

Changed Qortium's protected API authorization so requests from localhost must provide the same API key as any other caller. The old local auth bypass setting is still accepted in settings files for compatibility, but local network origin is no longer treated as proof that an administrative request is trusted.

### 2026-05-20 - Fix unauthenticated developer proxy control

Protected Qortium's developer proxy controls with the normal API key check and limited the proxy to local development targets. Remote callers can no longer start, stop, or retarget the proxy without authorization, and authorized use is constrained away from arbitrary hosts and Qortium's own API ports.

### 2026-05-20 - Fix unsafe QDN patch path handling

Hardened QDN patch merging so patch metadata cannot point added, modified, or removed files outside the intended source and merge folders. Qortium now rejects unsafe patch paths before applying file changes and normalizes internal containment checks used by cleanup safeguards.

### 2026-05-20 - Fix unsafe 7z bootstrap extraction

Hardened Qortium's 7z archive extraction so a malicious bootstrap archive cannot write files outside the intended extraction folder. Bootstrap imports now reject unsafe archive entries instead of silently creating parent folders and writing wherever the entry path points, while normal nested bootstrap archives continue to extract as expected.

### 2026-05-20 - chat: recover private group historical keys

Added historical private group chat key recovery for cases where a current member is missing the key for an older message from a previous membership epoch. Clients can now include the missing epoch id when requesting a specific key, recovery can relay matching historical key announcements without exposing raw keys, and validation still keeps new user messages and rotation requests tied to the current group membership.

### 2026-05-20 - chat: document private group chat workflow

Documented the current Core-managed private group chat client workflow and added an end-to-end API regression covering active chat discovery, inbox counts, missing-key reporting, key request recovery, and successful decryption after a relayed announcement. This turns the private group chat design notes into practical developer guidance for using the implemented local APIs.

### 2026-05-20 - chat: add private group message count API

Added a local private group message count API so clients can count encrypted closed-group inbox messages without fetching every page. The count uses the same filters as the private inbox, counts messages even when the local node is missing the key, and excludes key announcements, key requests, rotation requests, malformed rows, and other non-message chat records.

### 2026-05-20 - chat: add private group active chat API

Added a local private group active-chat API so clients can list the closed groups available to an account and see each group's latest private user message without reading raw control envelopes. The new endpoint decrypts the latest message when possible, reports missing keys clearly when it cannot, includes empty private groups for complete chat lists, and leaves key requests or relays as explicit follow-up actions.

### 2026-05-20 - chat: classify private group chat envelopes

Added internal classification for stored private group chat envelopes so normal chat views can distinguish real private group messages from key announcements, key requests, and rotation requests. Public chat search, counts, and active-chat summaries now hide that control traffic while direct signature lookup and private group recovery scans can still see the raw records they need.

### 2026-05-20 - chat: add private group key request recovery

Added an explicit private group chat recovery API that lets a current group member scan stored key requests and relay matching signed key announcements the local node already knows. This completes the missing-key loop without making inbox reads publish transactions, avoids duplicate relays for the same key in one call, and reports unavailable, stale, invalid, duplicate, or relayed requests clearly to clients.

### 2026-05-20 - chat: add private group inbox API

Added a side-effect-free private group chat inbox API so local clients can list closed-group user messages and receive decrypted data when the matching key is available. Messages that cannot be decrypted now report the missing epoch and key id instead of failing the whole read, giving clients a clear path to request or relay the missing key without exposing control envelopes as user chat.

### 2026-05-20 - chat: honor private group rotation requests

Finished the private group chat rotation-request flow by making local sends look for the newest accepted owner/admin request in the current membership epoch before reusing a cached group key. When a valid request is present, Qortium now publishes and uses a fresh key for future messages while keeping older keys available so older messages still decrypt.

### 2026-05-20 - chat: add private group rotation requests

Added signed private group chat rotation requests so a closed-group owner or admin can ask current members to move future sends to a fresh key for the current membership epoch. Qortium now creates, validates, stores, and exposes a local API for these request envelopes while rejecting requests from ordinary members.

### 2026-05-20 - chat: add local private group key rotation

Added local private group chat key rotation so a current closed-group member can publish a fresh key announcement for the current membership epoch and use that new key for future sends. Older keys remain available for decrypting older messages, and send selection now prefers the newest locally created key instead of whichever cached key was touched most recently.

### 2026-05-20 - chat: relay private group key announcements

Added local support for relaying private group chat key announcements without exposing raw group keys. A current closed-group member can now republish a previously stored or cached signed key announcement for the current membership epoch, giving peers another chance to recover a missing key while preserving the original announcement signature and validation rules.

### 2026-05-20 - chat: add private group key requests

Added signed private group chat key requests so a current closed-group member can ask the group for a missing encryption key without exposing message contents or raw key material. Qortium now creates, validates, stores, and exposes a local API for these request envelopes while still rejecting rotation requests until the separate rotation policy is implemented.

### 2026-05-20 - chat: support private group chat historical epochs

Allowed private group chat decryption to use the key announcement that matches the message's original membership epoch instead of requiring the group to still have that same current member set. Older messages can now remain readable to accounts that were included in the signed key announcement, while newer members still cannot use a node-wide cached key to decrypt messages from before they joined, and new outgoing messages continue to use the current group membership epoch.

### 2026-05-20 - chat: rehydrate private group keys from announcements

Made private group chat decryption recover missing local keys from stored key announcements. A recipient node can now clear or miss its in-memory key cache, scan the retained group chat history for a matching signed key announcement, unwrap the group key for its local account, cache it, and continue decrypting without requiring the sender's node-local cache state.

### 2026-05-20 - chat: require private envelopes for closed groups

Started enforcing the private group chat envelope format for closed-group broadcast CHAT transactions. Closed groups now reject plaintext or malformed broadcast chat payloads, accept encrypted private message envelopes and valid signed key announcements, and leave open-group, no-group, and direct-recipient chat behavior unchanged while key-request and rotation-request flows wait for their own APIs.

### 2026-05-20 - chat: add private group chat local APIs

Added restricted local APIs for sending and decrypting Core-managed private closed-group chat messages. The new API can create a cached group key for the current membership epoch, publish a signed key announcement when needed, store the encrypted message as a signed CHAT transaction, and decrypt messages when the local node already has the matching key, while leaving closed-group plaintext enforcement for a later step.

### 2026-05-20 - chat: add private group chat key cache

Added an in-memory private group chat key cache for the next stage of closed-group encryption. The cache can store locally created keys, verify and unwrap received key announcements for a local recipient, look up exact or current-epoch keys with defensive copies, and keep this state process-local until chat APIs and announcement rehydration are wired in later.

### 2026-05-20 - chat: add private group chat key announcements

Added signed private group chat key announcements that bind a shared group key to a specific closed-group membership epoch. Qortium can now create a key announcement with wrapped copies for every current member, verify that the announcement was signed by a current member and covers the exact current member set, and let each recipient unwrap their own copy of the group key without changing live chat routing yet.

### 2026-05-20 - chat: add private group chat membership epochs

Added private group chat membership epoch helpers so closed-group encryption can identify the exact member set a group key belongs to. Qortium can now derive a stable epoch id from a closed group's current member public keys, reject groups that cannot safely be encrypted yet because a member has no known public key, and keep this foundation separate from live chat routing until the key-announcement and API layers are ready.

### 2026-05-20 - chat: add private group chat crypto helpers

Added private group chat crypto helpers for the next stage of closed-group encryption. The new helper can generate group keys and nonces, compute context-bound key ids, encrypt and decrypt messages with AES-GCM associated data, and wrap shared group keys to individual members using existing account shared-secret derivation without changing live chat behavior yet.

### 2026-05-20 - chat: add private group chat envelope primitives

Added the first private group chat code foundation by introducing a versioned envelope format for encrypted closed-group chat payloads. The new primitives define structural message, key announcement, key request, and rotation request envelopes with strict parsing and defensive copies, giving later encryption, key-cache, API, and validation work a stable data shape without changing live chat behavior yet.

### 2026-05-20 - docs: revise private group chat encryption design

Revised Qortium's private group chat encryption design to use shared random group keys for each membership epoch instead of per-sender keys. The updated design keeps key distribution inside Core without QDN, accepts identical member sets reusing the same epoch id, allows multiple signed key announcements in one epoch, lets any node relay signed announcements without exposing raw keys, and documents the larger shared-key blast radius as an accepted simplicity tradeoff.

### 2026-05-20 - docs: define private group chat encryption design

Defined Qortium's first private group chat encryption design before implementation starts. The chat planning doc now chooses Core-managed closed-group encryption with cached per-sender keys for each membership epoch, no QDN key publishing, automatic future-key changes when group membership changes, owner/admin rotation requests, missing-key request handling, and a staged implementation path for envelope, key cache, API, validation, and integration tests.

### 2026-05-20 - docs: refresh chain parameter audit

Refreshed the chain-parameter audit so it matches Qortium's current cleanup state instead of the older April branch snapshot. The audit now treats native-asset and runtime identity cleanup as mostly complete, identifies production `blockchain.json` and genesis governance defaults as the next high-priority fork-parameter work, and keeps test fixtures, API naming, cross-chain naming, and package branding as separate follow-up tracks.

### 2026-05-20 - docs: document trusted-seed launch model

Documented Qortium's trusted-seed launch model for the account trust network. The trust docs now explain that the first launch relies on socially trusted early Minting group members as the practical seed set, treats that as an accepted launch assumption instead of an unresolved seed-guard blocker, and keeps seed eligibility as a future review item if minting membership becomes more open or permissionless.

### 2026-05-20 - docs: update trust launch readiness results

Updated the trust-network launch checklist with current local verification results after aligning the test harness with Qortium's active trust defaults. The checklist now records the passing focused policy, transaction API, documented readiness, expanded trust/API, and long benchmark runs while keeping seed eligibility as the remaining launch policy question.

### 2026-05-20 - test: initialize transaction API sync state

Updated the transaction API tests to initialize the controller's latest-block cache and run those tests as a local single-node testnet. This keeps the production synchronization guard intact while letting `/transactions/process` tests reach the transaction validation paths they are meant to cover.

### 2026-05-20 - test: align trust policy defaults

Updated the account trust policy tests so their pinned launch expectations match Qortium's current Silver and Bronze vote-weight defaults. The tests now verify the configured 70% Silver and 40% Bronze multipliers, and the custom-policy cases replace those current defaults instead of older calibration values.

### 2026-05-20 - docs: label documentation status

Added status labels to the documentation index so testers, developers, chain builders, and contributors can tell which pages are current guidance, active planning notes, legacy reference material, or upstream Qortal comparison material. The inherited auto-update, Windows installer, upstream network, and QDN app docs now also open with short status notices, making the documentation set safer to navigate before the larger content rewrites begin.

### 2026-05-20 - docs: add first testnet walkthrough

Added a clearer first-test path around the local single-node testnet so testers can build the jar, start the disposable chain, confirm the API and block height, stop the node, and reset the generated runtime files without copying long cleanup commands. The new status and reset helpers keep the recommended onboarding flow visible from the README while leaving the existing start and stop behavior unchanged.

### 2026-05-20 - docs: add tester build helper

Added an interactive build helper for testers who are not comfortable starting with raw Maven commands. The helper checks for Java 17, a JDK compiler, and Maven, explains the exact build command before running it, points users to official install docs when prerequisites are missing, and prints the next local testnet commands after a successful jar build.

### 2026-05-20 - docs: organize documentation layout

Organized the markdown documentation by topic so the repository root stays focused on the README and the human-readable changelog. The new documentation index points testers, developers, chain builders, and contributors to the right guides, while inherited and transitional notes remain available under legacy or upstream folders instead of being mixed into the main root docs.

### 2026-05-19 - docs: refresh Qortium README

Rewrote the root README so the repository now opens with Qortium's current purpose, status, and practical build paths instead of inherited Qortal project copy. The new README gives testers a direct single-node testnet quick start, keeps normal local and Docker run instructions concise, and links to the main Qortium docs without moving the broader markdown files yet.

### 2026-05-19 - tx: publicize creators before confirmed processing

Moved normal confirmed transaction creator metadata into the account table before each transaction's own processing runs, so a first transaction can establish its public key without a separate publicize or placeholder balance transaction. Group joins now also handle missing default-group rows defensively, and the local testnet genesis can join its default minter directly without a zero-balance setup transaction.

### 2026-05-19 - scripts: harden main node lifecycle helpers

Updated the main start and stop helper scripts so normal local node operation is safer and easier to diagnose. The start script now works from any directory, avoids launching a duplicate node when an active pid file exists, uses the built jar directly instead of copying it into the repository root, and prints the jar and log paths it is using. The stop script now works from any directory, handles missing API keys and stale pid files cleanly, supports an explicit API port override, and avoids broad process matching that could stop the wrong Qortium node.

### 2026-05-19 - testnet: default to local single-node testing

Refreshed the bundled testnet setup so testers can start with a local single-node chain instead of assembling a multi-node testnet first. The testnet chain template is now compact, current-format, and neutral: it has no prefunded accounts, no native asset issued at genesis, and only one default minting-group member with a matching minting key so the local node can produce blocks. The testnet settings now default to safer loopback-only access, and the helper scripts generate disposable local runtime files with a fresh genesis timestamp, add the default local minting key, and stop the local node cleanly.

### 2026-05-19 - feat: authorize minting keys from minting-group joins

Added an optional minting public key to minting-group join transactions so an account can publish its minting authorization as part of joining the group. Qortium now records that authorization as a self-share in the existing reward-share table only when needed, leaves existing self-shares alone, rejects minting keys that already belong to another account, and treats self-share percentages as ignored minting-authorization metadata instead of real reward-splitting percentages.

### 2026-05-19 - deps: update Netty 4.2 baseline

Updated Qortium's Netty dependency family to the current 4.2 maintenance baseline used by gRPC networking. The startup defaults now keep Netty on the pooled allocator unless an operator explicitly chooses another allocator, which gives the fork the security and maintenance fixes from Netty 4.2 while avoiding an unrelated allocator-behavior change during the first rollout.

### 2026-05-19 - deps: update Tika Core MIME detection baseline

Updated Qortium's Apache Tika Core dependency to the current 3.3 maintenance baseline used for upload MIME detection and filename-extension recovery. This keeps QDN upload handling on the maintained Tika line while preserving Qortium's existing upload flow and stored data behavior.

### 2026-05-19 - deps: update Jersey maintenance baseline

Updated Qortium's Jersey dependency family to the current 2.x maintenance baseline used by the API server. This keeps the existing `javax.ws.rs` API model in place while taking the latest Jersey 2.x fixes for the REST, multipart, and injection libraries that serve Qortium's HTTP endpoints.

### 2026-05-19 - deps: update Protobuf Java patch baseline

Updated Qortium's Protobuf Java runtime dependency to the current 3.25 patch baseline. This keeps the committed generated gRPC and protobuf sources authoritative while taking the latest patch fixes in the runtime library used by light-client RPC code and other protobuf parsing paths.

### 2026-05-19 - docs: plan private group chat encryption

Added a dedicated planning document for Core-managed private group chat encryption. The notes explain why closed groups are not private enough today, summarize Qortal Hub's current QDN-based encrypted group chat model as reference material, and record the Qortium direction before implementation begins: closed-group chat should become private by default in Core, use a Qortium-owned encrypted payload envelope, keep chat-store payloads opaque, and decide key distribution, rotation, history, and plaintext rejection deliberately. The 6.1.5 comparison now points to this new design document instead of carrying the next phase directly.

### 2026-05-19 - build: update Qortium version to 6.1.5

Updated Qortium's Maven project version and active GitHub branch filters to 6.1.5 now that the selected upstream Qortal 6.1.5 changes have been integrated, skipped with documented rationale, or deferred as Qortium-specific future work. This makes generated build metadata and repository automation line up with the completed 6.1.5 integration checkpoint.

### 2026-05-19 - test: add chat concurrency hardening coverage

Added deterministic concurrency coverage for Qortium's dedicated chat runtime. The tests now exercise simultaneous duplicate local chat submissions, concurrent peer-ingress duplicates, local API and peer duplicate interaction, cleanup while chat reads are active, notifier registration churn during notification delivery, and importer shutdown with queued chat, giving the completed non-encryption chat-store foundation stronger protection before the later private-group encryption phase.

### 2026-05-19 - test: harden dedicated chat runtime coverage

Added regression coverage around Qortium's dedicated chat runtime so duplicate CHAT submissions across local API and peer-ingress paths are stored and notified only once, already stored peer chats are ignored without duplicate notifications, queued peer chat does not break importer shutdown, and retained chat remains readable after cleanup. The 6.1.5 comparison notes now mark the non-encryption chat-store foundation as implemented while keeping Core-managed private group encryption as the next separate design phase.

### 2026-05-19 - chat: add dedicated chat retention cleanup

Added a small lifecycle manager that periodically removes expired messages from Qortium's dedicated transient chat store using the existing chat retention setting. This keeps chat storage bounded over time without touching normal transaction history or the standard unconfirmed transaction pool, and wires the cleanup task into node startup and shutdown alongside the other chat runtime pieces.

### 2026-05-19 - chat: serve peer chat inventory from dedicated store

Updated peer transaction lookup and unconfirmed-signature inventory so retained chat messages in Qortium's dedicated transient chat store are visible through the existing peer transaction request flow. Peers can now fetch stored CHAT data by signature, receive stored CHAT signatures in unconfirmed inventory, and avoid re-requesting chat signatures this node already has or has queued for peer chat ingress.

### 2026-05-19 - chat: route peer chat ingress to dedicated store

Updated incoming peer chat handling so CHAT transaction messages are validated through Qortium's dedicated chat service and stored in the dedicated transient chat store instead of entering the normal unconfirmed transaction importer. Accepted peer chats now notify local chat listeners after storage, while peer signature inventory and `GET_TRANSACTION` lookup remain unchanged for the next network chat phase.

### 2026-05-19 - chat: route chat websockets to dedicated store

Updated chat websocket reads so live chat clients use Qortium's dedicated transient chat store instead of the old chat transaction repository. Local signed chat submissions now notify chat websocket listeners only after the message has been validated and saved in the dedicated store, while peer networking and broadcast behavior remain unchanged for later chat phases.

### 2026-05-19 - chat: route chat build APIs through dedicated service

Updated the local chat build and nonce-compute APIs so they validate through Qortium's dedicated chat service instead of the old unconfirmed transaction path. This keeps chat creation aligned with the dedicated transient chat store, uses the same proof-of-work difficulty rules for nonce creation and signature checks, preserves the existing raw unsigned CHAT response format, and still avoids storing, broadcasting, or notifying from the build-only endpoints.

### 2026-05-19 - chat: route local chat submissions to dedicated store

Updated `/transactions/process` so signed local chat submissions are validated through Qortium's dedicated chat service and stored in the dedicated transient chat store instead of the normal unconfirmed transaction tables. The endpoint keeps the existing success response shapes for API v1 and v2, leaves ordinary transactions on the existing unconfirmed path, and deliberately avoids peer broadcast or websocket notification until those chat paths are routed in later commits.

### 2026-05-19 - chat: route chat read APIs to dedicated store

Updated the REST chat read endpoints so they read from Qortium's dedicated transient chat store instead of the old unconfirmed transaction tables. Chat message search, message lookup by signature, active-chat discovery, and message counts now use the new store, with count returning the true number of matching stored messages rather than the size of a paged result. Chat submission, websockets, and peer networking remain unchanged for later chat phases.

### 2026-05-19 - chat: add dedicated chat validation service

Added a Qortium-owned chat validation service for the dedicated chat store. The service validates signed chat messages against the current chat rules, uses the new chat store for duplicate and rate-limit checks, stores accepted messages without adding them to the normal unconfirmed transaction pool, and keeps the live API, websocket, and peer routing unchanged for the next planned chat phases.

### 2026-05-19 - chat: add dedicated transient chat store

Added the first storage foundation for Qortium's planned chat redesign. Chat messages can now be saved in a dedicated transient chat table that is separate from block transaction history, preserves the original message bytes and encryption flags, supports direct and group chat queries, counts recent messages for rate-limit decisions, and removes old messages based on a configurable retention period. This does not route live APIs, websockets, or peer traffic to the new store yet; it gives the next chat-work phases a tested place to move those paths deliberately.

### 2026-05-19 - docs: record private chat encryption direction

Updated the 6.1.5 chat plan so Qortium's dedicated chat store is built with future Core-managed private group encryption in mind. The notes now clarify that closed groups are not private by default today, that Qortal Hub's client-side group encryption is useful reference material but not the selected Core design, and that the first chat-store implementation should preserve opaque message bytes and encryption metadata without adding plaintext assumptions.

### 2026-05-19 - fix: harden QDN storage-size checks

Updated QDN storage-size calculation so unexpected filesystem scan failures are logged without stopping future storage checks or corrupting the last successful capacity values. The calculation now counts a separate temporary data directory correctly instead of counting the main data directory twice, while keeping shutdown interruption behavior clean and avoiding the upstream aggressive QDN cleanup changes.

### 2026-05-19 - docs: record upstream 6.1.5 triage decisions

Updated the upstream Qortal 6.1.5 comparison document so it records which changes Qortium has already ported, skipped, deferred, or left for deeper planning. The document now includes a detailed Qortium chat plan that rejects the upstream singleton delegate as the source of truth, chooses a dedicated transient chat store, records the selected repository-helper decisions, and separates the skipped aggressive QDN cleanup behavior from the narrow storage-manager hardening idea.

### 2026-05-19 - api: add batch primary-name lookup

Added a batch primary-name lookup API so wallets, explorers, and other clients can resolve the primary names for several addresses with one request instead of repeating the single-address endpoint. The new endpoint keeps the response in the same order as the request, preserves duplicate addresses, reports valid addresses without primary names as `null`, and stays full-node-only until Qortium has a designed lite-data batch lookup path.

### 2026-05-19 - qdn: exclude blocked names from index discovery

Updated QDN index discovery so index resources published by locally blocked names are excluded while filling the arbitrary index cache. This keeps blocked-name policy from only hiding final resource results while still allowing blocked publishers to influence local index discovery.

### 2026-05-19 - fix: isolate block response failures per request

Updated GET_BLOCK response handling so a failure while building or sending one known block response is logged for that block instead of stopping the rest of the queued block requests. This keeps peer responses more resilient under partial block data, serialization, or send failures without changing block validation or synchronization rules.

### 2026-05-19 - fix: harden unconfirmed transaction response scheduler

Updated the scheduled network response path for unconfirmed transaction signature requests so unexpected failures are logged instead of stopping future scheduler runs. The response now builds signatures in a mutable list while preserving Qortium's existing transaction flow and deliberately avoiding Qortal's separate chat-delegate behavior.

### 2026-05-19 - fix: respect false QDN cache filter options

Fixed cached QDN resource filtering so `followedOnly=false` and `excludeBlocked=false` are treated as disabled filters instead of being applied just because the setting was present. This keeps the cache-backed search path aligned with the SQL fallback path and avoids hiding resources when callers explicitly pass false.

### 2026-05-19 - fix: use provider-neutral PKCS12 keystore loading

Updated the restricted admin certificate helper to load PKCS12 keystores through Java's default PKCS12 support instead of requiring the Bouncy Castle provider by name. This keeps the certificate and SAN lookup behavior the same while reducing provider-specific assumptions inherited from upstream Qortal.

### 2026-05-19 - docs: compare upstream Qortal 6.1.5 changes

Added a neutral comparison document for the upstream Qortal `6.1.4` to `6.1.5` release range so Qortium can review the 24 commits and 31 changed files before deciding what belongs in the fork. The document groups the changes by area, records every commit and changed file, and leaves each Qortium integration decision marked as undecided for later triage.

### 2026-05-18 - docs: define lite-node state-root design

Added the Phase 4B lite-node state-root design so Qortium has a concrete target for moving from anchored peer agreement to cryptographically verified lite data. The design records one combined post-block state root, the first account, balance, name, and owner-name state covered by proofs, deterministic key and value encoding requirements, block processing and orphaning expectations, and the later proof-bearing lite response shape while deliberately deferring complete address-history proofs to a separate committed index design.

### 2026-05-18 - lite: anchor lite-data responses to chain tips

Added the first Phase 4 lite-node anchoring step by making lite-data peers return account, balance, name, and transaction responses with a clear data status plus the block height, block signature, and timestamp behind the answer. Lite nodes now require peers to agree on both the returned data and that block anchor before treating a response as agreed, and unknown account, balance, or name lookups now use anchored response messages instead of unanchored generic unknown replies. This improves freshness and chain-context checking for lite mode while keeping the documentation clear that these are still peer-agreed answers, not cryptographic state proofs.

### 2026-05-18 - docs: define lite-node proof anchoring plan

Added the Phase 4 lite-node design note so the project has a clear path from peer-agreed lite data toward stronger verification. The note separates anchored peer responses from true cryptographic state proofs, explains why full proof support requires future block-level state commitments, and records one combined state root as the preferred starting design before any consensus or protocol changes are made.

### 2026-05-18 - lite: prefer chain-tip agreement for lite-data peers

Finished the remaining Phase 3 lite-node peer-selection cleanup by preferring capable lite-data peers that report the same chain tip when a clear agreement group is available. This keeps lite requests from mixing peers on different tips when there is a better agreed peer group, while preserving the existing fallback behavior when the connected peers do not provide a clear enough chain-tip group.

### 2026-05-18 - lite: require agreement for lite-data responses

Finished the first Phase 3 lite-node trust pass by requiring peer-backed account, balance, name, and transaction lookups to reach two matching responses before they are treated as agreed data. Lite APIs now distinguish no usable peer data from conflicting peer data, preserve clear unknown-data behavior when peers agree the data is unknown, and avoid presenting a single peer's wallet-facing response as authoritative.

### 2026-05-18 - lite: add multi-peer lite-data result model

Added the internal result model Qortium needs before lite nodes can stop trusting the first peer response. Lite-data lookups now have a shared vocabulary for agreed, unknown, unavailable, and conflicted results, plus canonical comparison helpers for account data, balances, names, name lists, and transaction lists so later peer responses can be compared consistently instead of relying on object equality or message byte storage.

### 2026-05-18 - lite: finish phase two lite-data limits

Finished the second lite-node pass by capping total lite transaction-history fetches, adding internal counters for lite-data peer request outcomes, and updating the lite-node plan so it reflects the capability routing and response limits now in place. This keeps lite peer requests bounded while leaving multi-peer agreement and proof work for the later trust phases.

### 2026-05-18 - lite: retry capable peers for lite data

Updated lite nodes to send lite-data requests only to peers that explicitly advertise lite-data service, skip peers that are stale or unsuitable, and retry a small shuffled set before reporting that peer data is unavailable. Also added an initial server-side bound for lite name and transaction responses so non-lite nodes can serve lite requests by default without returning unbounded result sets.

### 2026-05-18 - lite: advertise lite-data peer capability

Started the second lite-node phase by making ordinary non-lite Qortium nodes advertise that they can serve lite-data requests during peer handshake, and fixed capability decoding so HELLO_V2 maps are read from the right part of the message. Lite nodes do not advertise this role, which gives later lite request routing a clear network contract instead of guessing which peers can answer wallet and name lookups.

### 2026-05-18 - lite: finish phase one lite-node coverage

Finished the first lite-node correctness pass by making lite balance lookups report a clear no-reply error when peer data is unavailable instead of returning a misleading zero balance. Added focused round-trip coverage for the remaining lite account, balance, name, and transaction request messages so the baseline peer message formats are checked by tests before later lite-node trust and peer-selection work begins.

### 2026-05-18 - lite: fix phase one lite-node correctness gaps

Added the first lite-node planning note and fixed the most immediate lite-mode correctness gaps it identified. Lite name lookup now serializes the requested name correctly, account-info requests use the lite fetch path when lite mode is enabled, and lite API branches fail with a clear no-reply error when peer data is unavailable instead of falling into null handling problems.

### 2026-05-18 - test: clean up name save repository transaction

Updated the name save regression test so it explicitly discards the temporary repository writes it creates while checking direct name registration and unregister behavior. This keeps the test behavior the same while preventing repository close from logging the cleaned-up test transaction as a critical uncommitted-work warning.

### 2026-05-18 - deps: update ICU4J maintenance baseline

Updated Qortium's ICU4J dependency family to the current stable maintenance release and refreshed the dependency security review to record the new baseline. This keeps Unicode normalization and charset support current while preserving the existing name, asset, group, and poll sanitization behavior.

### 2026-05-18 - build: update Maven plugin maintenance baseline

Updated Qortium's Maven compiler, dependency, install, and resource plugins to current Maven 3-compatible maintenance releases, and pinned the dependency plugin version explicitly in the build. This keeps the packaging and resource-processing toolchain current without moving the project onto Maven 4 beta plugin lines.

### 2026-05-18 - ci: refresh security-scan Maven cache

Updated Qortium's OSV scan workflows and documented local scan command so Maven cache keys include checked-in local Maven artifacts and SBOM generation forces dependency-resolution retries. This prevents older cache entries from carrying stale missing-artifact state into the security scan path after local dependency provenance changes.

### 2026-05-18 - ci: refresh Maven cache after local dependency changes

Updated the main GitHub build workflow so Maven cache keys include Qortium's checked-in local Maven artifacts and the first Maven step forces dependency-resolution retries. This keeps older GitHub cache entries from remembering that the vendored AT artifact was missing before it was added to the repository.

### 2026-05-18 - docs: mark dependency security review current

Updated Qortium's dependency security review so it reflects the resolved direct-dependency cleanup, the locally vendored AT artifact, the Maven-resolved SBOM scanning path, and the latest stable dependency-maintenance versions. This keeps the public security notes aligned with the current branch instead of leaving earlier findings written as future work.

### 2026-05-18 - deps: update stable maintenance dependencies

Updated a conservative batch of stable runtime and build dependencies, including Apache Commons utilities, Guava, json.org, java-diff-utils, XZ, Swagger UI, and two Maven build plugins. These updates keep common parsing, archive, utility, API-documentation, and packaging support closer to maintained upstream baselines without taking on larger major-version or prerelease migrations.

### 2026-05-18 - ci: enable full OSV Maven resolution

Updated Qortium's OSV GitHub workflows to scan Maven-resolved CycloneDX dependency reports instead of raw manifests. Maven now resolves the full dependency graph, including the locally vendored AT artifact, before OSV checks the generated SBOM for vulnerable packages.

### 2026-05-18 - deps: vendor AT dependency for local resolution

Vendored Qortium's pinned CIYAM AT dependency into the repository's local Maven artifact tree and removed the remaining JitPack repository dependency from the main build. This keeps the consensus-critical AT VM pinned to the same QuickMythril fork commit while making fresh builds and dependency scanners resolve it from tracked project provenance instead of a build-on-demand service.

### 2026-05-18 - docs: refresh dependency security status

Refreshed Qortium's dependency security review after the latest maintenance batch. The review now records that GitHub dependency and code-scanning alerts are currently clear, automated OSV and Dependabot monitoring are active, Log4j, jsoup, Commons Net, and the Maven build plugins have been moved to current maintenance baselines, and local OSV source scanning still uses the documented no-resolution mode because the inherited `AT` dependency cannot be resolved from normal Maven repositories.

### 2026-05-18 - build: update Maven maintenance plugins

Updated Qortium's Maven jar packaging and test runner plugins to current maintenance releases. This keeps the build tooling aligned with supported plugin versions while preserving the existing package layout, default skipped-test behavior, focused CI checks, and Java 17 build target.

### 2026-05-18 - deps: update stable runtime hygiene dependencies

Updated Qortium's jsoup and Apache Commons Net runtime dependencies to current stable maintenance releases. These updates keep HTML parsing and network utility code closer to maintained upstream baselines without changing Qortium APIs, consensus rules, transaction formats, runtime settings, or user-facing behavior.

### 2026-05-18 - deps: update Log4j maintenance baseline

Updated Qortium's Log4j dependency family to the current Log4j 2 maintenance line. This keeps the node's logging stack on a fresh security-supported baseline without changing log configuration, runtime settings, APIs, consensus behavior, or transaction handling.

### 2026-05-18 - ci: keep security automation actionable

Adjusted the new security automation so it stays useful on the current Qortium branch. The main GitHub Actions build now checks that the project packages successfully and runs the focused API regression test added for the Jetty CORS cleanup, while Dependabot routine update monitoring avoids major-version migration pull requests that need separate compatibility work instead of being treated as small security-maintenance updates.

### 2026-05-18 - api: replace deprecated Jetty CORS filter

Replaced Jetty's deprecated CORS filter with a small Qortium-owned servlet filter shared by the API, gateway, domain-map, and development proxy services. The new filter keeps the existing permissive cross-origin behavior and preflight handling while removing a Jetty 12 deprecation warning that would otherwise become future API-server maintenance work.

### 2026-05-18 - ci: add dependency vulnerability monitoring

Added automated dependency monitoring so Qortium does not depend on occasional local scans to notice new security advisories. Pull requests to `qortium-6.1.4` now get OSV comparison scanning, the active branch gets a weekly full OSV scan, and Dependabot watches both Maven dependencies and GitHub Actions workflow dependencies for update candidates.

### 2026-05-18 - ci: modernize GitHub Actions security baseline

Updated Qortium's GitHub Actions workflows to use the current checkout, cache, and Java setup action generations, keep workflow token permissions read-only by default, and run the main build workflow against the active `qortium-6.1.4` branch instead of the stale inherited `master` target. This keeps CI on the Java 17 baseline while reducing the chance that outdated workflow actions become the next maintenance or security weak point.

### 2026-05-18 - api: expose supported cross-chain blockchains

Added a read-only cross-chain discovery API so wallets, explorers, and other apps can ask a Qortium node which foreign blockchains it supports. The new endpoint reports configured metadata such as blockchain name, currency code, active network, wallet-enabled state, and supported trade capabilities without starting wallets or checking live foreign-chain servers, giving apps a safer way to adapt to each Qortium chain's supported blockchain set.

### 2026-05-18 - deps: update bitcoinj security baseline

Updated Qortium from bitcoinj `0.16.3` to `0.17.1` so the inherited cross-chain signing boundary uses the patched bitcoinj line. The migration adapts Qortium's Bitcoiny networks, HTLC signing, transaction builders, address helpers, deterministic-wallet tests, and Pirate Chain `t3`/`b` P2SH compatibility to bitcoinj's newer API layout while keeping the existing cross-chain behavior intact. The resolved runtime tree no longer includes the old vulnerable OkHttp and Okio transitive path, and the refreshed OSV runtime scan found no vulnerable dependency entries.

### 2026-05-18 - deps: update gRPC and Netty security baseline

Updated Qortium's gRPC runtime and pinned the Netty dependency family to a patched 4.1 line so the Zcash-family light-client networking path no longer resolves the vulnerable Netty 4.1.110 modules. This keeps the change limited to dependency resolution and records bitcoinj as the remaining larger security migration because it requires cross-chain API compatibility work.

### 2026-05-18 - docs: record Jetty follow-up considerations

Recorded the remaining Jetty and Java 17 follow-up considerations before moving to the next dependency-security item. The security review now explains that the Java 17 baseline and Jetty smoke coverage are complete enough to proceed, while keeping the deprecated Jetty CORS filter and older GitHub Actions versions visible as later cleanup work.

### 2026-05-18 - fix: allow empty QDN cache startup

Fixed fresh repository startup when there are no QDN signatures to populate yet. Qortium now skips the empty database batch, still records the QDN cache as populated, and keeps the node able to continue toward API startup on a clean database.

### 2026-05-18 - build: align Java 17 runtime requirements

Aligned Qortium's documented runtime requirement, local start scripts, and GitHub Actions builds with the Java 17 baseline introduced by the Jetty 12 migration. The cleanup also removes stale Jetty 10 wording from websocket comments so the code and project notes point to the same maintained runtime target before the next dependency-security migration.

### 2026-05-18 - deps: migrate Jetty to 12 EE8

Moved Qortium from Jetty 10 to Jetty 12's maintained EE8 line so the API server, gateway, proxy, HTTP/2 support, and websocket endpoints keep their existing `javax.servlet` behavior while using a current Jetty runtime. This raises the build target to Java 17 and updates the embedded Jetty error handler to the Jetty 12 request/response callback API without changing API routes, websocket paths, CORS settings, TLS settings, or node configuration semantics.

### 2026-05-18 - deps: update direct security dependencies

Updated the first batch of direct security-sensitive dependencies without changing Qortium APIs, consensus behavior, transaction formats, or runtime settings. Log4j, Tika, Commons Lang, and Swagger's Jackson path now resolve to patched lines, and the old JUnit dependency inherited through `json-simple` is excluded from compile/runtime scope while keeping JUnit available only for tests. A leftover production-code use of JUnit assertions in the testing block minter was replaced with normal `DataException` checks so runtime code no longer needs JUnit.

### 2026-05-18 - docs: record dependency security review

Recorded the first dependency security review for the Qortium baseline. The new note explains which vulnerable dependencies can be handled as a narrow maintenance batch, why bitcoinj, Netty, and Jetty need separate follow-up work, and where the local scan artifacts came from. This keeps the security review visible in tracked project history instead of leaving it only in terminal output.

### 2026-05-17 - test: cover minting-seed farm pressure

Added launch-stress coverage for farm accounts that join the Minting group and try to gain account-trust influence through subject-only ratings or same-branch support. Those farm patterns now have regression coverage proving they remain Unverified with zero effective voting weight, while the launch-readiness notes record that a stronger independent-seed-branch pressure run exposed a separate seed-eligibility gap for later policy work.

### 2026-05-17 - test: add trust launch acceptance scenario

Added one combined launch acceptance scenario for the account trust network. The test walks through honest Bronze, Silver, and Gold onboarding, isolated farm-ring resistance, Suspicious mint blocking, cooldown-gated recovery from a mistaken negative rating, same-branch rating limits, and a no-evidence Minting group member that can still mint but has no voting weight. This gives the launch review a single end-to-end trust-network story without changing trust policy, transaction rules, APIs, schemas, or runtime behavior.

### 2026-05-17 - api: expose trust profile vote weight

Added raw `blocksMinted` and effective trust-weighted vote weight to the account trust profile API so wallets and explorers can use one trust-profile response for an account's status, multiplier, raw minting history, and active influence. The trust docs now describe `trust-profile` as the preferred account display source for these values, reducing the chance that clients show raw history as active governance power.

### 2026-05-17 - test: audit trust weighted influence paths

Added regression coverage that checks trust-weighted influence across poll tallies, frozen poll results, resource-rating summaries, and trust-summary totals for Gold, Silver, Bronze, Unverified, and Suspicious accounts. The tests now pin the difference between raw `blocksMinted` history and effective trust-weighted influence so future changes do not accidentally count unverified or suspicious accounts, or bypass the current 100/70/40 launch multipliers.

### 2026-05-17 - docs: add trust network client integration guide

Added a wallet and explorer integration guide for the account trust network so clients have one practical reference for account trust display, rating previews, cooldown handling, delayed confirmation visibility, network dashboards, and effective vote-weight presentation. The existing trust docs and launch checklist now point to the guide, reducing the risk that clients show raw `blocksMinted` as active governance power or treat protected-window rating delays as failed transactions.

### 2026-05-17 - test: verify trust network launch readiness

Added API coverage proving a delayed `RATE_ACCOUNT` transaction remains visible through the unconfirmed transaction API while it waits through the protected online-account window, then confirms once the window ends. The trust-network launch checklist now records the latest local focused test run, full readiness suite, and long benchmark results so the current launch profile has an auditable verification checkpoint without changing trust scoring, consensus rules, transaction schemas, or policy defaults.

### 2026-05-17 - docs: add trust network launch readiness checklist

Added a dedicated trust-network launch readiness checklist that records Qortium's current trust defaults, required verification commands, open launch-review decisions, and the conditions for treating the trust network as launch-ready. The existing trust docs now point to this checklist so policy review, benchmark review, and client readiness can be tracked in one place without changing consensus rules, APIs, transactions, or config values.

### 2026-05-17 - core: rebalance trust vote multipliers

Changed Qortium's default trust vote multipliers so Gold still counts for 100% of earned `blocksMinted`, Silver now counts for 70%, and Bronze now counts for 40%. This keeps Unverified and Suspicious accounts at zero effective voting or resource-rating weight, while making Bronze participation more meaningful and keeping Gold clearly strongest. The trust graph, rating rules, Suspicious blocking, minting eligibility, and stored snapshot mechanics are unchanged.

### 2026-05-17 - test: cover trust rating client workflow

Added API-level workflow coverage for account trust ratings so wallet and explorer behavior is tested as one coherent path. The new tests load trust profile, explanation, policy, preview, cooldown, unsigned rating transaction, confirmation timing, transaction signing, block confirmation, and post-confirmation refresh for both adding and removing a rating. This strengthens confidence in the documented client flow without changing trust scoring, transaction validation, public APIs, schemas, or policy defaults.

### 2026-05-17 - docs: add trust rating workflow guide

Added a practical account trust rating workflow to the trust-network guide so wallet builders, explorer builders, and reviewers can follow the intended user path from profile review through category choice, confidence selection, preview, cooldown checks, transaction building, confirmation timing, submission, and post-confirmation refresh. This keeps the trust policy unchanged while making the existing APIs easier to apply correctly.

### 2026-05-17 - test: add realistic trust launch graph scenarios

Added realistic launch-community trust scenarios that combine honest onboarding, a no-evidence Minting group seed, isolated farm clusters, Suspicious trust blocking, and mistaken-rating recovery in the same style of graph. The new coverage keeps the current trust policy unchanged while proving Gold, Silver, Bronze, Unverified, and Suspicious outcomes stay stable across mixed launch conditions.

### 2026-05-17 - api: expose generic transaction confirmation timing

Added a generic transaction confirmation timing API so clients can tell when a valid transaction is accepted but waiting for a later confirmable block. This applies the same explanation path to account ratings, reward-share changes, privilege transfers, and any future transaction type with height-based confirmation limits, without changing transaction validation, mempool behavior, block minting, or trust scoring.

### 2026-05-17 - test: cover delayed account rating confirmation windows

Added end-to-end coverage for account ratings submitted near protected reward-window blocks. The new test proves a `RATE_ACCOUNT` transaction can sit unconfirmed through the online-account capture and batch distribution window without changing stored ratings or trust snapshots, then applies normally when the next confirmable block is minted and rolls back cleanly if that block is orphaned.

### 2026-05-17 - test: add trust bootstrap walkthrough scenario

Added a step-by-step trust-network bootstrap test that starts with stored ratings but no qualified trust evidence, then walks through Minting group seed energy, Manager trust, Trainer trust, Player trust, and final Subject trust. The new coverage shows that Minting group membership seeds the graph without automatically granting Manager status, unqualified ratings remain auditable without adding weight, and only qualified Player support turns Subject trust into active Gold vote weight.

### 2026-05-17 - core: tune trust and reward launch policy

Adjusted the Qortium launch policy for trust-weighted influence and batched rewards. Silver trust now counts for 75% of earned `blocksMinted` weight while Gold remains 100% and Bronze remains 25%, keeping strong but not final trust closer to full participation, and poll/resource rating tallies now read those percentages from the configured trust policy instead of hardcoded values. Batch rewards now use 100-block batches with a 10-block online-account capture window, and account-rating transactions wait for that protected reward window to end before confirming, matching the delayed-confirmation approach already used for reward-share changes. The account-rating cooldown remains 1,440 blocks and is documented as applying only to the same rater, target, and category edge.

### 2026-05-17 - docs: record trust launch performance review

Recorded the latest local performance review for the account trust network. The updated guide now includes refreshed static and churn benchmark numbers for the medium and large generated trust graphs, and the Aura trust-tier note explains that the latest review stayed close to the existing baseline. This keeps the launch documentation honest about current performance while making clear that these are local reference measurements, not consensus limits, and that no immediate trust-derivation optimization is currently required before launch.

### 2026-05-17 - test: add trust launch transition scenarios

Added launch-transition tests for the account trust network. The new coverage shows that changing the Minting group seed set can dilute and later restore Subject trust, removing Manager-layer support cascades through Trainer, Player, and Subject trust, losing evaluator-category trust drops downstream Subject support, and signed support removal plus block orphaning restores the prior rating, snapshot, and status-change history. This strengthens pre-launch confidence without changing trust scoring, transactions, APIs, schema, or default policy values.

### 2026-05-17 - api: preview account rating impact

Added a read-only account-rating impact preview so wallets and explorers can show what a proposed `RATE_ACCOUNT` change would do before a user signs it. The new endpoint checks the same rating, no-op, self-rating, unknown-target, and cooldown rules as the transaction path, then applies valid candidate ratings only in memory to compare the current live trust result with the preview result. This helps users understand rating changes and removals without changing active ratings, stored trust snapshots, or trust-status history.

### 2026-05-17 - test: add trust launch stress scenarios

Added signed-transaction launch stress tests for the account trust network. The new coverage shows how the current policy behaves when one supporter rates many accounts, two independent supporters rate many accounts, a mixed onboarding batch reaches different tiers, support is removed after cooldown, Suspicious status recovers after a negative rating is removed, and extra Minting group seed members dilute support outcomes. This gives launch reviewers clearer pressure-test evidence without changing trust scoring, transactions, APIs, schema, or default policy values.

### 2026-05-17 - test: document honest trust onboarding scenarios

Added signed-transaction trust-network onboarding scenario tests for the current launch policy. The new coverage proves Minting group members without trust evidence can still mint while carrying zero vote weight, trusted supporters can move accounts through audit-only, Bronze, Silver, and Gold outcomes, support removal is delayed by the account-rating cooldown, and the trust profile and explanation APIs show the result. This documents the ordinary onboarding path without changing trust scoring, transactions, APIs, schema, or default policy values.

### 2026-05-17 - test: harden launch trust adversarial flows

Added signed-transaction adversarial trust-network scenario tests for the current launch policy. The new coverage proves isolated farm rings stay Unverified, same-branch positive or negative evidence remains visible without granting trust or Suspicious blocking, independent negative evidence can block minting, and the account-rating cooldown blocks rapid trust-edge flipping while still allowing recovery after the configured window. This strengthens launch confidence without changing trust scoring, transactions, APIs, schema, or default policy values.

### 2026-05-17 - api: expose account rating cooldown status

Added a read-only account-rating cooldown API so wallets and explorers can show whether one rater, target, and category edge can be changed in the next block. The endpoint reports the active rating, configured cooldown, latest change height, earliest allowed height, remaining blocks, and a simple allowed flag without changing consensus validation. This gives users a clear explanation before they try to change or remove a rating too soon.

### 2026-05-17 - core: add account rating churn cooldown

Added a configurable cooldown for changing or removing the same account-rating edge. Qortium now records the block height of each `RATE_ACCOUNT` change, rejects repeated changes by the same rater for the same target and category until the configured window passes, exposes the cooldown through the trust-policy API, and keeps the default launch profile at 1,440 blocks while still allowing derived chains and tests to disable it with `0`. This slows rapid trust-edge flipping without preventing first ratings or ratings in other categories, and the docs and tests now cover the new rule.

### 2026-05-17 - test: add launch trust review scenarios

Added launch-profile trust-network scenario tests that check how Qortium treats no-evidence minting members, accounts with one trusted supporter, accounts with two independent supporters, same-branch positive and negative evidence, and independent negative ratings that make an account Suspicious. These tests keep the current trust defaults unchanged while giving launch reviewers clearer proof that trust weight, minting allowance, status summaries, and rating removal recovery behave as intended.

### 2026-05-17 - api: add trust network health summary

Extended the account trust summary API with health fields for launch and explorer monitoring. The summary now reports snapshot row completeness, active rating counts by category, total stored trust-status changes, and latest trust-change metadata so clients can see whether the stored trust graph looks current and internally complete without fetching every rating or snapshot.

### 2026-05-17 - test: add trust rating churn benchmarks

Added deterministic and opt-in benchmark coverage for account-rating churn in the trust network. The scale tests now prove that a synthetic trust graph can have ratings changed and removed while keeping complete snapshot rows, and the long trust-network benchmark now reports refresh timing across repeated churn rounds so Qortium can decide later whether rating-change limits or trust-derivation optimization are needed before launch.

### 2026-05-17 - docs: record trust graph scale expectations

Recorded the current trust-network benchmark baseline so Qortium maintainers can see how the synthetic trust graph performs before launch. The account trust guide now documents the opt-in benchmark command, the latest local medium and large profile timings, and the limits of those numbers as reference measurements rather than consensus guarantees, while the Aura trust-tier note now points follow-up work toward larger stress profiles, rating churn controls, or derivation optimization only if benchmark review shows they are needed.

### 2026-05-17 - test: pin launch trust policy profile

Documented Qortium's current launch trust policy profile and added tests that pin the default trust thresholds, per-rating caps, branch requirements, Suspicious requirements, seed-energy settings, vote multipliers, and main-versus-test-chain config parity. This makes future trust-policy changes intentional and reviewable without changing the active defaults.

### 2026-05-17 - test: harden trust explanation coverage

Added focused trust explanation API coverage so Qortium can prove account trust screens have enough audit detail to explain Unverified, Gold, and Suspicious results. The tests now check stored versus live explanations, configured thresholds and caps, branch and confidence requirements, top positive and negative impacts, and same-branch evidence that remains visible without granting trust.

### 2026-05-17 - test: add transaction-level trust calibration scenarios

Added transaction-level trust-network calibration scenarios that run the visible account-rating evidence through signed `RATE_ACCOUNT` transactions, block processing, stored snapshot refreshes, status-change history, rating removal, orphan handling, and minting eligibility. This proves the trust-policy behavior works through the actual chain path without changing Qortium defaults, public APIs, transactions, minting, voting, or rating rules.

### 2026-05-17 - test: add trust policy calibration scenarios

Added test-only permissive, current, and strict trust-policy calibration scenarios so Qortium-derived chains can see how threshold, branch, and Suspicious-confidence choices change the same evidence. These examples keep Qortium's active defaults unchanged while making launch policy tradeoffs easier to review.

### 2026-05-17 - test: harden trust snapshot lifecycle

Added stronger lifecycle coverage for stored account trust snapshots and trust-status change history. The tests now prove initial snapshot population from Minting group seed state, same-height snapshot replacement, rollback cleanup of orphaned change rows, and disk-backed repository reopen persistence without changing trust scoring, minting, voting, ratings, or public APIs.

### 2026-05-17 - test: add trust network scale validation

Added deterministic synthetic scale coverage and opt-in long benchmarks for the account trust network. The default test suite now proves that a larger multi-category trust graph can be derived and stored with complete per-account snapshots, while local benchmark runs can print derivation and refresh timings for bigger generated graphs before Qortium launch defaults are finalized.

### 2026-05-16 - api: add trust status change history

Added stored trust-status change history and a read-only API for explorer and admin audit screens. Qortium now records when derived account trust levels or statuses move between snapshot refreshes, removes orphaned change rows during rollback, and lets clients filter recent changes by account, category, previous status, and new status.

### 2026-05-16 - test: add trust policy calibration matrix

Added focused policy calibration tests for the trust network settings that Qortium-derived chains can tune before launch. The new coverage proves that vote multipliers, positive caps, Suspicious caps, Suspicious rater count, and Suspicious confidence requirements each change decisions, capped scores, or effective weight in the expected direction without changing the current default policy.

### 2026-05-16 - docs: update trust network implementation status

Updated the Aura trust-tier design note so it describes the trust network as an implemented Qortium system rather than a staged proposal. The document now separates completed mechanics from remaining pre-launch hardening work, making it clearer that future trust-network work is mainly calibration, observability, and performance testing.

### 2026-05-16 - api: clarify raw and effective trust weights

Clarified the boundary between raw `blocksMinted` history and effective trust-weighted influence. Account and poll API descriptions now label effective vote weight separately from raw audit weight, and the trust-network guide explains that clients should use effective weight fields for governance and weighted rating displays.

### 2026-05-16 - test: add trust network scenario coverage

Added broader trust-network scenario tests that model honest independent branches, isolated farm rings, same-branch positive lift attempts, and coordinated negative ratings. These tests make the intended trust behavior easier to calibrate before launch by proving that raw same-branch evidence remains auditable but cannot by itself create positive trust or Suspicious mint blocking.

### 2026-05-16 - core: require independent positive rating branches

Changed positive trust level decisions so accounts need enough independent trust branches as well as enough capped score before they can become Bronze, Silver, or Gold. Qortium now exposes the positive branch requirement in trust policy and explanation data, keeps same-branch positive rating evidence visible as raw score, and documents that same-branch raters cannot by themselves lift another account into a positive trust tier.

### 2026-05-16 - core: require independent suspicious rating branches

Changed Suspicious trust decisions so negative account-rating evidence must now come from enough independent trust branches as well as enough independent raters. Qortium now tracks the first positive Manager hop out of each Minting group seed as a trust branch, carries that branch through later positive trust, exposes branch counts in trust explanation data, and documents that same-branch negative raters can record evidence but cannot by themselves block another account from minting.

### 2026-05-15 - test: harden trust network policy scenarios

Added regression tests for additional trust-network safety cases before changing the policy further. The new coverage locks that untrusted positive ratings do not create active trust score, low-confidence negative ratings do not make an account Suspicious even when raw negative score is large, and single qualified raters cannot lift Trainer or Player trust through the configured caps by themselves, while two qualified Manager raters can lift Trainer trust once capped support reaches the threshold.

### 2026-05-15 - api: add trust network summary endpoint

Added a read-only trust network summary API that lets clients inspect the stored account trust graph without fetching every snapshot row. The summary reports active Subject status counts, minting seed counts, minting allowance counts, raw `blocksMinted` weight, effective trust-weighted vote influence, snapshot metadata, and per-category status counts so explorers and governance tools can show network health more efficiently while leaving trust scoring, minting, voting, ratings, and transactions unchanged.

### 2026-05-15 - core: clean trust network baseline schema

Folded the trust-network database schema into its final pre-launch Qortium shape so new chains create category-aware account ratings, account-rating query indexes, rate-account transaction categories, and trust snapshot query indexes directly instead of stepping through older development migrations. This keeps trust scoring, minting, voting, rating, and API behavior unchanged while making the baseline schema cleaner for future Qortium-derived chains.

### 2026-05-15 - core: refresh trust snapshots only when inputs change

Changed stored account trust snapshot refreshes so ordinary blocks no longer recompute the full trust graph when ratings and minting seed membership have not changed. Qortium now refreshes snapshots for account-rating changes, active Minting group membership changes, minting-group configuration boundaries, and first-time snapshot population, while keeping poll result freezing, minting checks, vote weights, resource-rating weights, and public trust API behavior unchanged.

### 2026-05-15 - refactor: rename trust graph DTOs

Renamed the remaining preview-named account trust helper data classes now that the old trust-preview endpoint has been removed. Trust rating counts, category summaries, and category impacts now use neutral trust-network names while keeping the same response fields and behavior for trust profile, explanation, derivation, snapshot, voting, minting, and resource-rating code.

### 2026-05-15 - api: remove trust preview endpoint

Removed the old trust-preview endpoint now that Qortium has stable trust profile, explanation, policy, snapshot, and derivation APIs for account trust screens and audit tools. The shared trust rating-count and category DTO helpers remain because the current trust APIs still use them, but preview-only evaluator-impact, mutual-positive, and score-summary response fields were removed to keep the baseline API surface focused before launch.

### 2026-05-15 - api: scale trust snapshot queries

Moved stored trust snapshot filtering, paging, and stored trust-derivation account selection into repository queries so clients can page and filter the trust network without forcing the API to load broad snapshot sets first. This keeps the public trust API behavior and response shapes unchanged while giving larger Qortium-derived communities a better path for account trust browsing and audit screens.

### 2026-05-15 - test: cover account rating removals

Added regression coverage for account-rating removals so rating `0` is proven to remove only the intended active account-rating edge, refresh stored trust snapshots, clear derived Suspicious mint blocking when enough negative evidence is removed, and restore the prior rating and trust result when the removal block is orphaned. This strengthens the trust-network safety model without changing rating transactions, trust scoring, minting, voting, or API behavior.

### 2026-05-15 - docs: add trust network guide

Added a public-facing account trust network guide that explains why Qortium uses on-chain trust ratings, how signed confidence ratings and the Manager, Trainer, Player, and Subject categories work, how the Minting group seeds the graph, how Gold/Silver/Bronze/Unverified/Suspicious status affects minting and vote weight, and which APIs clients should use to display or audit trust state. The existing Aura trust-tier note remains the detailed implementation-history document, while the new guide gives users and chain builders a clearer current-system overview.

### 2026-05-15 - api: add trust policy endpoint

Added a read-only trust policy API that exposes the active chain-configured trust settings clients need to explain account trust results. The endpoint returns the active weighting category, Manager seed-energy flow settings, Suspicious requirements, Gold/Silver/Bronze/Unverified/Suspicious vote multipliers, and each category's thresholds and caps without changing trust scoring, snapshots, ratings, minting, voting, or resource-rating behavior.

### 2026-05-15 - api: add trust profile endpoint

Added a read-only trust profile API that returns one known account's active Subject trust status, vote-weight multiplier, minting trust allowance, minting seed membership, stored snapshot metadata, and per-category trust/rating-count summary in one response. This gives clients a compact way to display the trust network without combining lower-level snapshot, summary, preview, and explanation endpoints, while leaving trust scoring, ratings, minting, voting, and resource-rating behavior unchanged.

### 2026-05-15 - core: index account rating queries

Added focused database indexes for the account-rating trust graph so common inbound, outbound, category-filtered, summary, and paged listing queries have a better scaling path as the graph grows. This does not change account-rating transactions, trust scoring, snapshots, voting, minting, or public API behavior; it prepares the existing trust-network data model for larger communities.

### 2026-05-15 - core: remove manual account trust status

Removed the older manual account trust-status storage and comparison fields now that Qortium uses stored Aura-style Subject snapshots as the active trust source. Account info, poll vote details, resource-rating summaries, and trust previews now show one snapshot-derived trust status instead of comparing manual and derived values, reducing ambiguity for new Qortium-derived chains.

### 2026-05-15 - core: lock Aura trust scoring parity

Added trust-graph regression coverage and documentation that lock Qortium's current Aura matching boundary. Manager energy is budgeted while it flows through four positive Manager-rating hops, but final Manager, Trainer, Player, and Subject category scores continue to use Aura's direct rating impact formula instead of a second outbound budget split. Qortium's capped level decisions and independent-rater Suspicious checks remain deliberate consensus hardening around the Aura-style raw scores.

### 2026-05-15 - api: add trust explanation endpoint

Added a read-only account trust explanation API so clients can show why an account currently maps to Gold, Silver, Bronze, Unverified, or Suspicious. The endpoint combines the stored snapshot status used for active enforcement with configured thresholds, caps, requirement checks, and top rating impacts, and it can optionally recalculate live graph evidence for comparison without changing ratings, snapshots, minting, voting, or resource-rating behavior.

### 2026-05-15 - core: make trust policy chain-configurable

Moved Qortium's trust-network policy values into the blockchain config while keeping the current default behavior unchanged. Derived trust thresholds, per-rating caps, Suspicious requirements, Manager seed-energy flow settings, the active Subject weighting category, and Gold/Silver/Bronze vote multipliers are now chain parameters, so new Qortium-derived chains can tune the trust network without editing Java code.

### 2026-05-15 - core: extract trust policy rules

Moved Qortium's Aura-style trust graph thresholds, per-rating caps, seed-energy constants, active weighting category, Suspicious requirements, and level-to-status mapping into a dedicated trust policy class. This keeps the graph derivation focused on building scores while leaving the consensus-facing policy values in one reviewable place, without changing trust scores, snapshots, vote weights, resource-rating weights, mint eligibility, or API output.

### 2026-05-15 - core: cap suspicious trust rating impact

Added capped Suspicious decision scoring to the Aura-style trust graph so one trusted negative rating can no longer block an account from minting by itself. Qortium still stores and exposes raw negative score and raw evaluator impacts for audit, but derived Suspicious status now requires the signed capped decision score to cross the category's negative threshold with at least two independent medium-confidence negative raters. Accounts with raw negative evidence that does not meet that stricter Suspicious rule now remain Unverified instead of being treated as Suspicious.

### 2026-05-15 - core: cap trust rating level impact

Added per-rating caps to positive Aura-style trust level decisions so one evaluator can no longer assign a positive trust level by itself. Qortium still stores and exposes each category's raw score and raw evaluator impacts, but level and mapped status now use an additional capped level-decision score, with snapshots and APIs returning the cap details for audit. Negative/Suspicious scoring is intentionally unchanged in this step and remains the next trust-network policy area to review.

### 2026-05-14 - core: use Aura manager energy flow

Changed Qortium's derived trust graph to follow the recovered Aura node scorer more closely for Manager trust. Manager energy now starts from the minting group, flows through four positive Manager-rating hops, splits by confidence along the way, and only then scores Manager ratings with the same positive and negative impact formula used by Aura. This replaces the earlier one-hop seed split, keeps Qortium's decentralized minting group as the seed set, documents that the recovered Aura scorer does not implement separate cap constants, and adds tests for four-hop scoring, confidence splitting, negative Manager flagging, snapshots, and API previews.

### 2026-05-14 - core: add trust graph behavior tests

Added adversarial regression tests for the active Aura-style trust graph. The tests confirm that isolated positive farm rings do not gain trust without a minting-seed path, weak negative ratings cannot make a target Suspicious, trusted Player-level negative ratings can block minting through derived Suspicious status, orphaning restores the prior trust snapshot and mint eligibility, and one seed account's Manager energy is split across positive Manager ratings instead of being multiplied.

### 2026-05-14 - api: align trust preview with derived trust

Changed the account trust preview API so its main trust status, evaluator weights, and evaluator impacts use the active stored Aura-style Subject trust snapshot instead of the older manual account trust status. The preview still includes stored/manual trust status and stored-impact fields as audit context, and the derived status fields now mirror the active Subject status used by voting, resource ratings, and mint eligibility.

### 2026-05-14 - core: use derived trust for mint eligibility

Changed mint eligibility to use the stored Aura-style Subject trust snapshot instead of the older manual account trust status. Minting-group membership remains the base permission, missing Subject snapshots are treated as Unverified, and only a derived Suspicious Subject status blocks a minting-group member from minting or being counted through a reward-share. Stored account trust status remains visible as audit context but no longer controls active minting eligibility.

### 2026-05-14 - core: use derived trust for voting weights

Changed active poll vote weights, frozen poll close-time weights, and resource-rating weighted summaries to use the stored Aura-style Subject trust snapshot instead of the older manual account trust status. Stored account trust status remains visible as audit context and still controls the current Suspicious minting block, while the derived Subject snapshot now provides the active Gold, Silver, Bronze, Unverified, or Suspicious multiplier for voting and resource-rating weight. This moves Qortium from previewing the decentralized trust graph to using it for the first governance-weight surfaces, while closed polls still freeze their final result at the closing block.

### 2026-05-14 - api: add derived trust audit fields

Added read-only derived trust audit fields beside the current stored trust fields on account info, open poll vote details, and resource-rating summaries. These fields show how the stored Aura-style Subject snapshot would affect vote or rating weight, while the existing stored trust status remains the active source for minting, poll totals, frozen poll results, and resource-rating weights. This also corrects stored trust snapshot list lookup so the full snapshot can be audited reliably. Together these changes give clients a practical way to compare current behavior with the derived trust model before Qortium decides whether to use derived trust for enforcement.

### 2026-05-14 - core: skip empty poll migration batches

Skipped empty poll migration batches when a repository has no rows to backfill. This keeps fresh or minimal databases from failing during startup migrations while preserving the same poll-name and poll-id backfill behavior whenever there is data to update.

### 2026-05-14 - api: expose account trust snapshots

Exposed the stored Aura-style account trust snapshot through read-only account-rating APIs. The trust derivation list now reads the block-anchored snapshot by default while still allowing a live recalculation for comparison, and a raw snapshot endpoint lets clients inspect the stored per-account, per-category rows directly with filters for account, category, mapped trust status, seed membership, and level. This makes the trust graph easier to audit without changing minting, voting, resource-rating weights, or stored account trust status.

### 2026-05-14 - core: store derived account trust snapshots

Stored the current Aura-style account trust derivation as deterministic repository state after each processed block and restored it when blocks are orphaned. The snapshot records each account's Subject, Player, Trainer, and Manager score, level, mapped trust status, minting-seed membership, inbound rating counts, and snapshot block height, giving Qortium a consensus-ready trust-state foundation without yet changing stored account trust status, minting eligibility, poll voting weight, resource-rating weight, or API enforcement behavior.

### 2026-05-13 - api: add account trust derivation listing

Added a read-only listing API for the Aura-style account trust derivation preview. Clients can now inspect the current derived trust graph across all minting seed members and accounts involved in active account ratings, filter by final derived status, seed membership, or category level, and page through the result without asking for one account at a time. This remains preview-only and does not change stored trust status, minting eligibility, poll voting weight, resource-rating weight, or consensus rules.

### 2026-05-13 - core: add aura-style trust derivation preview

Added category-aware account ratings and a read-only Aura-style trust derivation preview. Account ratings can now be recorded separately for Subject, Player, Trainer, and Manager trust, while the preview uses current minting-group members as the starting seed, splits a seed account's positive Manager influence across its outgoing Manager ratings, and derives manager, trainer, player, and subject scores without changing stored trust status, minting eligibility, poll voting weight, or resource-rating weight. This lets Qortium inspect the graph-based trust model before deciding whether derived trust should affect consensus behavior.

### 2026-05-13 - core: use aura-style account rating confidence

Changed account ratings to use Aura-style signed confidence values instead of the earlier Trusted, Known, and Untrusted labels. Accounts can now record positive or negative confidence from 1 through 4, use 0 to remove an active rating, and the trust preview now shows confidence distributions plus evaluator impact based on each rater's current trust status and effective vote weight, without changing stored trust status, minting eligibility, or governance weight.

### 2026-05-13 - api: clarify poll and rating transaction inputs

Clarified the API documentation for poll voting, poll updates, resource ratings, and account ratings so client builders can see the intended stable poll ID inputs and neutral removal values directly from the transaction builder endpoints. Added focused regression coverage for raw poll vote-removal and poll-update transaction builders, account-rating removal through `UNKNOWN`, and poll option updates that shrink the option list without leaving stale choices behind.

### 2026-05-13 - core: add poll update transactions

Added owner-signed poll update transactions so poll owners can correct poll names, descriptions, options, and end times before voting starts, while polls with active votes can only have an existing future end time extended. Poll updates use stable poll IDs instead of names, remember the previous poll details for clean orphan handling, reject changes after a poll closes, and include translated validation text for invalid poll owners.

### 2026-05-13 - core: make poll IDs the voting key

Changed poll voting so `VOTE_ON_POLL` transactions now reference the stable numeric poll ID instead of the editable poll name. The poll table now uses that ID as its primary key while keeping poll names unique for lookup and display, active vote storage and vote APIs can work directly by ID, and regression tests cover voting, vote removal, schema constraints, JSON output, and orphaning a vote after a poll name changes.

### 2026-05-13 - core: use poll IDs for poll child tables

Moved the internal poll option, active vote, frozen result, and frozen voter-detail tables from poll-name links to numeric poll IDs. Existing poll-name transactions and API routes continue to work, while the stored poll state now uses the stable ID foundation added in the previous change, making poll data closer to the cleaner group-style structure planned for Qortium.

### 2026-05-13 - core: add poll identifiers

Added numeric poll IDs as a stable lookup field alongside existing poll names. Poll list, search, and detail responses now include the assigned ID, create-poll transactions remember the ID after processing and clear it when orphaned, and new ID-based poll detail and vote-result API routes let clients fetch a poll without relying only on the human-readable poll name.

### 2026-05-13 - api: add searchable poll listing

Added a searchable poll listing API so clients can find polls by name, description, owner, open or closed status, end-time presence, and published-time range without fetching every poll and filtering locally. Poll names are now stored with a normalized search key and indexed alongside published and end-time fields, giving poll discovery a scalable path before the later poll ID work.

### 2026-05-13 - core: allow poll vote removal

Allowed poll voters to remove their active vote by submitting a `VOTE_ON_POLL` transaction with option index `0`. Real poll choices now use option indexes 1 through the number of poll options, removed votes are deleted instead of counted, closed polls still reject any vote change or removal, and poll creation now requires at least two separate option entries so comma-packed option strings are rejected.

### 2026-05-13 - core: allow rating removal

Allowed users to remove their own active resource rating by submitting a `RATE_RESOURCE` transaction with rating `0`, matching the existing account-rating behavior where `UNKNOWN` clears an active account relationship. Real resource ratings remain limited to 1 through 10, repeated no-op rating changes are still rejected, and removed ratings no longer count toward resource summaries, averages, or trust-weighted rating totals.

### 2026-05-13 - api: add decentralized account trust preview

Added a read-only account trust preview that summarizes active on-chain `RATE_ACCOUNT` relationships without changing consensus state. Users can now inspect a target account's stored trust status, current vote multiplier, inbound and outbound rating evidence, mutual positive relationships, and simple positive, negative, and net preview scores. This keeps the next trust-network step decentralized and testable while leaving minting eligibility, poll vote weight, resource-rating weight, and stored account trust status unchanged.

### 2026-05-13 - core: add account rating transactions

Added the first native account-to-account trust graph transaction for Qortium. Accounts can now record directed Trusted, Known, or Untrusted relationships with other known public-key accounts, clear their own active relationship back to Unknown, and query active relationship summaries without changing minting permission or vote weight yet. This creates deterministic on-chain graph evidence for later trust-tier derivation while keeping the current Gold, Silver, Bronze, Unverified, and Suspicious account status rules unchanged.

### 2026-05-13 - core: add resource rating transactions

Replaced poll-based app ratings with a standalone resource rating transaction for the new Qortium baseline. Accounts can now rate existing public QDN resources from 1 to 10, update their own rating without creating duplicate active votes, and expose summaries that include unweighted totals plus raw `blocksMinted` and trust-tier weighted averages. This keeps general polls separate from resource ratings and gives derived chains a cleaner foundation for app, website, document, and other public QDN reputation features.

### 2026-05-13 - core: freeze closed poll results

Stored a close-time snapshot for polls with an end time so their final vote counts, trust-weighted totals, raw `blocksMinted` totals, and per-voter audit weights stop changing after the closing block. Poll and app-rating APIs now use the frozen snapshot for closed polls, while open polls still show live trust-weighted results, and orphaning the closing block removes the snapshot so chain reorgs can recalculate it correctly.

### 2026-05-13 - i18n: translate remaining validation messages

Translated the remaining validation messages that were still written in English inside non-English language files. This keeps user-facing transaction error text more consistent across supported languages without changing validation behavior or consensus rules.

### 2026-05-13 - core: add poll end times

Added optional end times for polls so new polls can close at a defined timestamp while older and open-ended polls keep their existing behavior. Poll creation now stores the close time on chain, poll and app-rating APIs expose it, and vote transactions are rejected once the containing block reaches the poll's end time, including attempts to change an existing vote. This only stops further voting; freezing the final trust-weighted results remains the next planned step.

### 2026-05-13 - api: expose trust-tier vote audit fields

Added read-only trust-tier audit fields to account, poll-vote, and app-rating responses so users can see how raw `blocksMinted` becomes effective voting weight. Existing `totalWeight` and `voteWeight` fields continue to report trust-weighted values, while new raw totals and per-option raw weights show the original `blocksMinted` totals, and full poll-vote responses now include each voter's trust status, multiplier, raw weight, and effective weight for easier review.

### 2026-05-13 - core: add account trust-tier vote weights

Added the first account trust-tier foundation for Qortium minting and governance, along with the Aura trust-tier planning note that records the local BrightID reference repos and the practical difference between BrightID, Bitu, and Aura for this design. Accounts now have a stored trust status that defaults to Unverified, Suspicious accounts are blocked from minting even if they remain in the minting group, and poll vote weight now uses the account's current trust-tier multiplier instead of raw `blocksMinted` alone. The planning note also records the repository-only first slice, the need for deterministic accepted trust-status updates instead of live external lookups, and the later idea of poll end times that can lock vote weights when a poll closes.

### 2026-05-11 - qdn: add service capability registry

Moved QDN app-library rating service policy into a dedicated capability registry instead of keeping accepted services hidden inside the ratings API. App, website, plugin, extension, and game ratings are now handled through the same registry-backed path, unsupported service filters fail clearly, and tests cover the registry rules and app-rating API filtering.

### 2026-05-11 - core: harden QDN update authority

Made QDN auto-update authority stricter and more operator-visible. Approved update manifests must now pin the exact QDN binary transaction, mutable name/identifier fallback lookup is rejected, and `/admin/update` reports manifest approval metadata, pinned binary metadata, active development-group details, and the configured auto-update mode. Operators can choose `OFF`, `CHECK_ONLY`, `NOTIFY`, or `INSTALL`, with legacy `autoUpdateEnabled` settings still mapped for compatibility.

### 2026-05-11 - crosschain: extract ACCT registry

Separated ACCT code-hash, ACCT name, filtered ACCT map, and ACCT trade-bot lookup into a dedicated registry while keeping trade-presence and crosschain validation just as strict as before. Foreign-chain registration now stays focused on chain/network lookup, inactive ACCT versions remain unregistered, and new tests lock down the allowed ACCT mappings before later ACCT work continues.

### 2026-05-11 - gui: add native tray abstraction

Reworked system tray handling behind a safe node-tray abstraction with Linux StatusNotifierItem support, AWT fallback, and no-op behavior when the desktop tray is unavailable or tests run headless. Core notification paths no longer depend directly on AWT tray types, GUI tests now cover headless tray safety and factory fallback behavior, and settings metadata is marked internal so settings loading stays stable under MOXy.

### 2026-05-11 - api: add group search endpoint

Added a group search API backed by repository and HSQLDB search support. Clients can now search group names and descriptions, page through results, reverse sort order, and filter by all, open, or closed groups while receiving the same member count and owner primary-name enrichment as the existing group list APIs.

### 2026-05-11 - crosschain: persist Bitcoiny Electrum server settings

Added persistent per-coin Electrum server settings for BTC-like chains. Runtime add/remove server API calls now update the active settings file before changing the live ElectrumX provider, generated server lists remain the default source, users can add custom servers, disable built-in servers, or replace defaults for a specific coin/network, and tests cover settings validation, server-list merging, and persisted add/remove behavior.

### 2026-05-11 - api: add guarded settings save endpoint

Added a restricted settings update path that can merge allowlisted changes into the active settings file, validate the result, and save it atomically. The new API rejects unknown, disallowed, or wrongly typed setting changes, reports which saved settings need a restart to fully take effect, and keeps broad settings persistence separate from later Electrum-specific server configuration work.

### 2026-05-11 - crosschain: harden foreign-foreign trade validation

Added shared foreign/foreign HTLC amount validation so maker create checks positive amounts, chain minimums, and P2SH-fee overflow consistently through direct and public API paths. Added filter helper coverage, reserved-state restart recovery coverage, and funding-amount assertions for both offered/requested HTLC funding paths.

### 2026-05-11 - crosschain: add foreign-foreign pair filters

Added exact offered/requested blockchain filters for foreign/foreign trade discovery. APIs and websockets can now distinguish directional pairs such as BTC-for-LTC from LTC-for-BTC while keeping the existing broad blockchain filter that matches either side of a foreign/foreign trade. Trade-bot state listing uses the same matching rules, the public tradebot response path now allows the maker and taker entries needed for a same-node foreign/foreign lifecycle, and tests cover broad, one-sided, exact, swapped, and local-asset-filtered offer results.

### 2026-05-11 - crosschain: guard foreign-foreign chain support

Added an explicit per-chain capability for BTC-like foreign/foreign trades so newly registered Bitcoiny chains are not automatically available for public foreign/foreign offers before their HTLC behavior is validated. Bitcoin and Litecoin are currently opted in because the deterministic foreign/foreign lifecycle and recovery coverage uses that pair, while other BTC-like chains now fail the existing invalid-criteria path until they are deliberately enabled.

### 2026-05-11 - crosschain: test foreign-foreign restart recovery

Added deterministic restart-style recovery coverage for BTC-like foreign/foreign trades. The new tests close and reopen repository scopes around persisted maker and taker trade-bot states, then verify that funding, HTLC declarations, maker redeem/secret reveal, taker redeem, and both refund paths continue without duplicate foreign-chain spends after the simulated restart boundary.

### 2026-05-11 - crosschain: test foreign-foreign public lifecycle

Added deterministic end-to-end coverage for the public foreign/foreign trade-bot path. The new test creates a BTC/LTC-style offer through the API, deploys the coordination AT, reserves it through the API, and then drives the mocked maker and taker HTLC lifecycle through funding, declarations, maker redeem, secret reveal, taker redeem, and final completion. The foreign/foreign design note and API schema wording now match the enabled single-fill public API surface.

### 2026-05-11 - crosschain: enable foreign-foreign tradebot API

Registered the foreign/foreign ACCT and trade-bot path so single-fill BTC-like foreign/foreign offers can be created and reserved through the public trade-bot API. Maker create now builds the unsigned coordination AT deployment without local-asset funding, taker respond now submits the reservation message from the generated trade key, and offer discovery can show foreign/foreign offers under either participating blockchain while keeping them out of local-asset filters and price estimates. Tests cover registry lookup, dispatcher routing, API create/respond validation, and offer filtering.

### 2026-05-10 - crosschain: submit foreign-foreign taker reservations from tradebot

Changed the internal foreign/foreign taker reservation path so the trade-bot submits the reservation MESSAGE using its generated taker trade key instead of returning an unsigned transaction for an external signer. Taker trade-bot state is now persisted only after the reservation message is accepted, matching the later autonomous AT messages that must use the same generated key. Tests cover successful reservation submission, rejected-message cleanup, and unchanged invalid-criteria handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker refund path

Added the taker-side failure-path handling for future foreign/foreign swaps. If the taker has funded and declared the requested-chain HTLC but the maker never redeems it or reveals the secret, the coordination AT now times out of trading as refunded, and the foreign/foreign trade-bot waits for the taker refund locktime before submitting or recognizing the requested-chain refund and marking the taker entry refunded. Deterministic tests cover AT timeout cleanup, waiting before refund, median-locktime safety, expired refund submission, refund-in-progress handling, and preferring a revealed secret over refund while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker refund path

Added the first internal failure-path handling for future foreign/foreign swaps. If the maker has funded and declared the offered-chain HTLC but the taker never declares a requested-chain HTLC, the foreign/foreign trade-bot now waits for the maker refund locktime, submits or recognizes the offered-chain refund, cancels the coordination AT, and only then marks the maker entry refunded. Deterministic tests cover waiting before refund, submitting an expired refund, and treating refund-in-progress as complete while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker offered-chain redeem

Added the internal taker completion path for future foreign/foreign swaps. After the maker reveals the swap secret, the foreign/foreign trade-bot can now recover the secret from either the Qortium coordination AT or the requested-chain HTLC redeem transaction, validate it against the committed hash, redeem the maker offered-chain HTLC, treat in-progress or already-redeemed offered-chain spends as complete, and finish the taker entry. Deterministic tests cover Qortium secret recovery, requested-chain secret recovery, invalid secret rejection, offered-chain redeem broadcast, redeem-in-progress idempotency, and waiting when no secret is available while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker redeem secret reveal

Added the internal maker completion path for future foreign/foreign swaps. After the taker has declared a funded requested-chain HTLC, the foreign/foreign trade-bot can now verify the requested-chain HTLC, redeem it with the maker secret, wait while redeem is in progress, reveal the secret to the coordination AT only after confirmed requested-chain redeem, and finish the maker entry once the AT records the redeem. Deterministic tests cover waiting for the taker HTLC, maker redeem broadcast, redeem-in-progress idempotency, delayed secret reveal, confirmed secret reveal, maker completion, and unsafe taker locktime handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker HTLC funding

Added the internal taker-side progress path for future foreign/foreign swaps. After the maker has declared a funded offered-chain HTLC, the foreign/foreign trade-bot can now verify the maker HTLC, derive a safety-margined taker locktime, fund the requested-chain HTLC, avoid rebroadcasting while funding is already in progress, and declare the taker locktime to the coordination AT. Deterministic tests cover waiting for the maker HTLC, requested-chain funding, funding-in-progress idempotency, funded HTLC declaration, and unsafe taker locktime handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker HTLC funding

Added the internal maker-side progress path for future foreign/foreign swaps. The foreign/foreign trade-bot can now advance a maker entry after AT confirmation, wait for a taker reservation, derive and fund the maker offered-chain HTLC, declare the maker locktime to the coordination AT, cancel stale or unsafe reservations, and keep active entries protected from deletion. Deterministic tests cover AT confirmation, undeployed expiry, funding, funding-in-progress idempotency, funded HTLC declaration, stale cancellation, unsafe locktime cancellation, and delete rules while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign tradebot create groundwork

Added the internal foreign/foreign trade-bot create and reserve groundwork while keeping the public API route disabled. The direct `BitcoinyForeignForeignTradeBot` path can now build unsigned maker DEPLOY_AT transactions and taker reservation MESSAGE transactions, validate supported BTC-like chain pairs, wallet keys, P2PKH receiving addresses, positive amounts, and minimum timeout, and persist offered/requested foreign-chain trade-bot state for later HTLC-driving work. New deterministic tests cover valid maker/taker state, repository round-trips, invalid criteria, and continued public-route rejection.

### 2026-05-10 - crosschain: harden foreign HTLC secret recovery

Updated BTC-like HTLC secret recovery to use the current decoded transaction provider path instead of the older raw-transaction address scan, only accepting confirmed redeem transactions that spend the expected P2SH script and reveal a 32-byte secret. Added deterministic tests for valid redeem secrets, refunds, wrong scripts, malformed scripts, unconfirmed redeems, and trade-bot secret resolver hooks. This prepares the foreign/foreign trade-bot to recover safely if a secret is revealed on a foreign chain before it is posted back to Qortium.

### 2026-05-10 - crosschain: persist foreign-foreign trade-bot state

Added the trade-bot persistence groundwork needed for future foreign/foreign swaps. Trade-bot state can now store separate offered and requested foreign-chain blockchains, public keys, wallet keys, amounts, locktimes, and receiving account info, JSON backup/import preserves those fields, and the shared Bitcoiny HTLC helper can build scripts from explicit refund/redeem roles. Foreign/foreign trade creation remains disabled until the trade-bot flow is implemented.

### 2026-05-10 - crosschain: implement inactive foreign-foreign ACCT state machine

Implemented the local coordination bytecode for inactive `BitcoinyForeignForeignACCTv1` offers, including reservation, maker/taker HTLC declarations, timeout-margin checks, secret reveal, cancellation, local-payment refund protection, trade data extraction, and deterministic tests. The new ACCT remains unregistered and unavailable through user-facing trade creation until the trade-bot/API wiring is implemented.

### 2026-05-10 - crosschain: add inactive foreign-foreign trade foundation

Prepared the API/data shape for future BTC-like foreign/foreign swaps by adding a `SELL_FOREIGN_FOR_FOREIGN` direction, dual offered/requested foreign-chain fields, and an inactive `BitcoinyForeignForeignACCTv1`/trade-bot skeleton. The new direction is recognized but deliberately rejected by user-facing trade creation until the real single-fill protocol is implemented.

### 2026-05-10 - crosschain: prepare foreign-foreign trade groundwork

Prepared for future BTC-like foreign/foreign atomic swaps without enabling a new trade type yet. Added a design note for Qortium-coordinated foreign/foreign trades, extracted shared Bitcoiny HTLC trade-bot support for script derivation, funded amount checks, status handling, funding, redeem, refund, and timeout safety, and kept the existing reverse `BitcoinyACCTv5` behavior wired through that shared helper.

### 2026-05-10 - crosschain: keep reverse split-fill ACCT v6 internal

Kept the unfinished `BitcoinyACCTv6` reverse split-fill ACCT as an internal work-in-progress instead of a user-facing protocol. The v6 bytecode and deterministic tests remain available for development, but v6 is no longer registered for public ACCT name or code-hash lookup, so normal APIs continue to expose the completed v4/v5 trade flows until v6 has matching trade-bot and API support.

### 2026-05-10 - test: stabilize split-fill ACCT v4 cancellation tests

Stabilized the deterministic `BitcoinyACCTv4` split-fill tests by using repository-sequenced transaction timestamps instead of wall-clock timestamps and by giving the cancellation-wait scenario enough test-chain time before the active fill expires. This keeps the test focused on the intended behavior: a cancelled split-fill offer should remain cancelled while active fills wait for refund, then finish cleanly after the refund window passes.

### 2026-05-10 - crosschain: add reverse split-fill ACCT foundation

Added `BitcoinyACCTv6` as the first reverse split-fill ACCT foundation, where one maker foreign-coin offer can be reserved in separate fill slots and each fill moves through taker reservation, maker foreign-lock declaration, taker local-asset lock, maker redeem, refund, or cancellation independently. The new parser is registered for recognized v6 ATs while the active trade-bot flow remains on v5 until the API and bot paths are wired in a later step. Deploy AT validation and repository storage limits now allow the larger ACCT bytecode/state sizes already needed by recent ACCT versions, and deterministic v5/v6 tests cover the deployed reverse flows without relying on live foreign-chain services.

### 2026-05-10 - test: expand reverse ACCT trade-bot coverage

Added deterministic `BitcoinyACCTv5` trade-bot tests for the foreign-first reverse trade flow. The tests now mock foreign-chain HTLC status and transaction broadcasts so the default suite can cover maker funding and foreign-lock declaration, stale reservation cancellation, unsafe locktime cancellation, taker local-lock transaction building, maker local-asset redemption and secret reveal, taker foreign HTLC redemption, and maker foreign HTLC refund handling without depending on live Electrum servers.

### 2026-05-09 - crosschain: make reverse ACCT trades foreign-first

Redesigned `BitcoinyACCTv5` reverse trades so the maker uses a maker-owned secret and funds a taker-specific foreign HTLC before the taker locks any local-chain asset. Reverse takers now reserve an offer with a zero-payment message, wait for the maker's funded foreign HTLC declaration, then use the new `/crosschain/tradebot/locklocal` API step to build the unsigned local asset lock transaction; the maker can only claim the local asset by revealing the secret on Qortium, which lets the taker redeem the foreign HTLC. The old local API balance-reservation safeguard was removed because public offers no longer require taker funds to be locked before the maker proves foreign liquidity, and the reverse trade flow now enforces a 30-minute foreign locktime safety margin, expires stale reservations, and lets the maker trade address cancel before local assets are locked. The reverse trade design docs and deterministic v5 tests cover the new reservation, foreign-lock, local-lock, redeem, refund, cancel, and premature-lock refund behavior.

### 2026-05-09 - crosschain: add reverse ACCT trades

Added the first reverse cross-chain trade implementation with `BitcoinyACCTv5`, where the maker offers BTC-like foreign funds and the taker escrows a selected local-chain asset into the AT. Trade creation can now request `SELL_FOREIGN` offers, the local API checks the maker's foreign wallet balance against existing open reverse offers for the same wallet key and rejects unverifiable wallet balances, the response API builds unsigned local escrow message transactions for takers to sign, the maker trade bot funds the foreign HTLC after the local escrow locks, and deterministic tests cover v5 trade data, lock/redeem/refund/cancel behavior, registry wiring, API-facing bot routing, reverse HTLC key-role ordering, and local reservation math.

### 2026-05-09 - crosschain: document reverse ACCT trade design

Added a reverse cross-chain trade design note for future `SELL_FOREIGN` ACCT flows where the maker offers foreign-chain funds and the taker escrows a chosen local-chain asset into an AT. The AT asset tests now include a deterministic feasibility check proving that a later local-asset payment can be detected by AT bytecode, bound to the payment sender, and refunded to that same sender, which confirms the core primitive needed before implementing the next ACCT version.

### 2026-05-09 - crosschain: expose ACCT trade direction

Added an explicit ACCT trade direction field so current offers and trades report that they are `SELL_LOCAL`, meaning the maker escrows a local-chain asset in the AT and the taker pays foreign-chain funds through the HTLC flow. This prepares the API and trade model for future reverse trades without changing current trade behavior.

### 2026-05-09 - refactor: rename ACCT trade roles to maker and taker

Renamed the crosschain ACCT trade-bot role terminology from Alice/Bob to maker/taker, including persisted trade-bot state names, HTLC API descriptions, comments, logs, and local helper names. The state numeric values are unchanged, keeping current behavior intact while making split-fill and future reverse-trade flows easier to reason about.

### 2026-05-09 - test: expand split-fill ACCT coverage

Expanded deterministic ACCT v4 coverage for split-fill offers by testing unfilled offer cancellation, active fill refund back into an offer, cancellation that waits for active fills to refund, and maker-side pending fill cleanup. The trade-bot now exposes a small package-level helper for stale pending fill state transitions so the behavior can be tested without relying on live foreign-chain calls.

### 2026-05-08 - crosschain: add split-fill ACCT offers

Added a new Bitcoiny ACCT v4 and trade-bot flow that lets one local-asset offer be filled in separate slots instead of forcing a single buyer to take the whole amount. Trade creation can now set minimum and maximum local fill amounts, response calls can request a specific fill size, offer summaries expose total, remaining, active, completed, and slot counts, and maker-side fill records are saved and backed up so active split fills can be tracked across restarts. BTC-like chains now create v4 offers by default while existing v3 and Pirate ACCT parsers remain available for recognized code hashes.

### 2026-05-08 - crosschain: support local asset ACCT trades

Updated the crosschain ACCT and trade-bot flow so offers can trade any spendable local-chain asset, not only the native asset. Trade creation, offer/trade summaries, price and websocket filters, trade-bot import/export data, AT payout bytecode, and repository storage now carry a `localAssetId` and `localAmount`; fresh Qortium chains use the new schema directly without keeping old Qortal trade-bot migration paths.

### 2026-05-08 - crosschain: add transparent Zcash Bitcoiny support

Added Zcash as a registered transparent-only Bitcoiny crosschain coin with ZEC mainnet chain identity, SLIP-44 wallet metadata, two-byte `t1`/`t3` address prefixes, SSL Electrum server metadata, and deterministic transaction-builder coverage. ZEC spends and HTLC redeem/refund transactions use ZIP225/v5 transparent serialization while shielded, unified, Sapling, and Orchard wallet flows remain outside the generic BTC-like path.

### 2026-05-08 - crosschain: add Bitcoin Cash testnet4 support

Added Bitcoin Cash TEST4 as a supported BCH network using BCHN testnet4 parameters, `bchtest:` CashAddr metadata, and generated Electrum server metadata from the 1209k `tbch4` list. BCH mainnet remains the default when no network override is configured.

### 2026-05-08 - crosschain: add Bitcoin testnet4 support

Added Bitcoin TEST4 as a supported BTC network using the BIP94 testnet4 genesis parameters, refreshed Electrum server metadata from the 1209k `tbtc4` list, and moved the default test settings from BTC TEST3 to BTC TEST4 while keeping TEST3 available for legacy fixtures. The Electrum server refresh tool can now restrict a refresh to selected networks, making it possible to update BTC TEST4 without rewriting existing BTC mainnet or TEST3 server entries.

### 2026-05-08 - crosschain: add Bitcoin Cash Bitcoiny support

Added Bitcoin Cash as a registered Bitcoiny crosschain coin with BCH wallet metadata, CashAddr address support, a fork-specific BIP122 trade reference, refreshed SSL Electrum servers, and common wallet/HTLC/ACCT coverage. Because BCH shares Bitcoin's genesis and uses fork-ID transaction signatures, the generic runtime now has a BCH signing path that keeps BCH trades distinct from BTC trades while filtering out CashToken-prefixed outputs so token UTXOs are not accidentally spent as plain BCH.

### 2026-05-07 - crosschain: add VerusCoin Bitcoiny support

Added VerusCoin as a registered transparent Bitcoiny crosschain coin using the shared chain-spec model, including VRSC wallet metadata, address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT coverage, and chain-spec documentation. Because VRSC shares KMD's genesis block, the generic Bitcoiny ACCT uses a Verus checkpoint-based BIP122 chain reference so VRSC trades cannot collide with KMD, while VerusID, PBaaS, reserve/multicurrency transactions, and shielded wallet behavior remain outside the generic BTC-like path.

### 2026-05-07 - crosschain: add Verge Bitcoiny support

Added Verge as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, XVG address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. Because XVG uses six decimal places and includes a transaction timestamp field in ordinary transparent transactions, the shared Bitcoiny runtime now has a timestamped legacy transaction builder/parser for XVG spends and HTLC redeem/refund transactions while leaving Verge stealth, messaging, and Tor/I2P routing features outside generic BTC-like support.

### 2026-05-07 - crosschain: add Peercoin Bitcoiny support

Added Peercoin as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, PPC address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. Because PPC uses six decimal places and Peercoin's current transaction serialization, the shared Bitcoiny runtime now carries per-chain decimal metadata and builds PPC spend and HTLC transactions as version 3 while still being able to parse older pre-version-3 Peercoin transactions that include a transaction timestamp field.

### 2026-05-07 - crosschain: add LBRY Credits Bitcoiny support

Added LBRY Credits as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, LBC address headers, Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This enables ordinary transparent LBC trade and wallet support while deliberately leaving LBRY claim, publish, update, and content-discovery features outside the generic BTC-like path; detected claim outputs are filtered out of normal wallet spend selection so they are not accidentally spent as plain LBC.

### 2026-05-07 - crosschain: add Komodo Bitcoiny support

Added Komodo as a registered transparent Bitcoiny crosschain coin with mainnet chain identity, SLIP-44 metadata, BIP122 routing, KMD address headers, Electrum server refresh metadata, and common registry coverage. Because KMD uses Sapling-era transaction rules, the generic Bitcoiny runtime now has a signed raw transaction wrapper and a Sapling-transparent signing path for KMD sends and HTLC redeem/refund transactions, while shielded KMD wallet behavior remains out of scope.

### 2026-05-07 - docs: document Zcash-family native wallet requirements

Documented why HUSH, Zcash, and other Sapling-family chains should remain planned rather than active until their Rust JNI wallet libraries, QDN wallet packages, Sapling parameter files, lightwalletd servers, wallet behavior, parser behavior, and live verification path are reproducible. Also clarified that KMD should use the generic BTC-like Bitcoiny path if the intended support is transparent Electrum-compatible KMD, and only use the Zcash-family path if native Sapling wallet support is actually required.

### 2026-05-07 - crosschain: add generic Zcash-family support

Moved Pirate Chain wallet, lightwalletd, QDN wallet-library loading, and raw Zcash-style transaction parsing onto shared Zcash-family foundations, leaving the existing Pirate classes as thin compatibility wrappers. This avoids copying the Pirate-specific wallet/controller/light-client code when later adding HUSH, Zcash, or other Sapling-family chains, while keeping Pirate Chain as the only active Zcash-family runtime for now and documenting the remaining transparent-parser boundary.

### 2026-05-07 - crosschain: add Firo Bitcoiny support

Added Firo as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, transparent address headers, exact PoW limit parameters, SSL-first Electrum server seeds, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This enables ordinary transparent FIRO trade and wallet support while deliberately leaving Spark, Lelantus, Zerocoin, and exchange-address flows outside the generic BTC-like path.

### 2026-05-07 - crosschain: add Namecoin Bitcoiny support

Added Namecoin as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including verified mainnet parameters, BIP122 chain identity, SLIP-44 wallet metadata, SSL-first Electrum server refresh support, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This adds ordinary NMC trade and wallet support while deliberately leaving Namecoin name registration/update features for a later pass; detected Namecoin name outputs are filtered out of normal wallet spend selection so name UTXOs are not accidentally spent as plain NMC.

### 2026-05-07 - crosschain: add Bitcoiny chain spec guardrails

Changed shared Bitcoiny trade ATs to store the active network's BIP122 chain reference instead of a Qortium-assigned numeric foreign-chain id, while keeping SLIP-44 coin types as wallet metadata for coins that have them. The chain-spec docs now list the registered mainnet BIP122 ids, planned SLIP-44 wallet metadata for Namecoin, Firo, and Komodo, a compatibility precheck, and a reusable coin-spec checklist for future BTC-like chain additions. The dependency provenance docs now mark altcoinj/libdohj as retired and document the remaining upstream bitcoinj signing boundary. The Bitcoiny spec tests now include a compact manifest check for each registered BTC-like chain's SLIP-44 metadata, BIP122 chain id, supported networks, mainnet genesis hash, address headers, and BIP32 headers so accidental identity changes or incomplete chain registration are caught before more coins are added. The broader crosschain tests also fixed a raw transaction parser edge case for valid empty input scripts and made the shared wallet balance fixture derive its expected balance from the mock provider instead of assuming only one funded wallet address.

### 2026-05-07 - crosschain: add internal Bitcoiny transaction model

Added a Qortium-owned Bitcoiny transaction value object that serializes legacy Bitcoin-like inputs, outputs, locktime, variable-length fields, and transaction IDs deterministically. The new tests round-trip parser fixtures through this write-side model, giving the project a covered transaction serialization layer before attempting to replace bitcoinj's remaining signing boundary.

### 2026-05-07 - crosschain: internalize Bitcoiny spend output lookup

Moved Bitcoiny spend preparation onto Qortium's deterministic key scanner for locating spendable outputs before bitcoinj signs the transaction. The remaining bitcoinj spend boundary now receives a precomputed UTXO list instead of driving wallet/keychain address discovery itself, and the old bitcoinj-specific key scanning helpers were removed while preserving existing single-recipient and multi-recipient spend behavior.

### 2026-05-07 - crosschain: remove legacy Bitcoiny wallet repair

Removed the old Bitcoiny wallet repair endpoint and its pre-2024 key-scanning path, which was only relevant to historical wallets from the inherited chain and depended on a bitcoinj UTXO provider. New Qortium baseline chains keep the normal deterministic wallet balance, address, and spend paths while dropping this legacy repair surface as part of the ongoing bitcoinj dependency reduction.

### 2026-05-07 - crosschain: internalize HTLC scriptSig building

Moved HTLC refund and redeem scriptSig construction onto Qortium-owned push-data serialization instead of bitcoinj ScriptBuilder and ScriptChunk helpers. HTLC transaction signing still uses bitcoinj at the transaction boundary, but the actual secret, signature, public-key, and redeem-script stack encoding is now shared through BitcoinyScript and covered by deterministic tests.

### 2026-05-07 - crosschain: internalize HTLC scriptSig parsing

Moved HTLC secret discovery onto Qortium-owned raw transaction and scriptSig push parsing for both Bitcoin-like and PirateChain HTLCs, using the generic Bitcoiny raw parser for Bitcoin-like transactions and the Pirate decoder for Zcash-style Pirate transactions. The shared Bitcoiny script helper now extracts push-data chunks directly, so HTLC read-only secret/status paths no longer need bitcoinj transaction or script parsing, while HTLC transaction signing remains on bitcoinj until the dedicated transaction builder is replaced.

### 2026-05-07 - crosschain: internalize Bitcoiny raw transaction parsing

Added a Qortium-owned raw transaction parser for Bitcoin-like transaction inputs, outputs, lock time, legacy encoding, and segwit witness skipping. Bitcoiny output resolution now reads raw transaction output scripts and values through this parser instead of constructing a bitcoinj `Transaction`, moving another read-only crosschain path off bitcoinj while keeping spend construction and signing on bitcoinj for the later transaction-builder replacement.

### 2026-05-06 - crosschain: internalize Bitcoiny wallet derivation

Added Qortium-owned BIP32 extended-key parsing and public child-key derivation for Bitcoin-like wallets, including Base58Check validation, secp256k1 public derivation, and deterministic P2PKH scan address generation. Wallet balance scans, transaction history scans, address-info lookups, unused receive-address selection, and spending-candidate previews now use the internal derivation path instead of bitcoinj Wallet/keychain APIs, while actual spend construction and signing remain on bitcoinj until the transaction-builder replacement is ready.

### 2026-05-06 - crosschain: add Bitcoiny address scripts

Added internal Bitcoiny address and script helpers for Base58Check addresses, segwit address decoding, and standard P2PKH/P2SH/witness output scripts. Wallet scanning, HTLC status checks, Litecoin P2SH normalization, crosschain API validation, and related tests now use those Qortium helpers for address hashes and scriptPubKeys instead of asking bitcoinj to build them, while transaction signing remains on bitcoinj for a later replacement step.

### 2026-05-06 - crosschain: remove altcoinj and internalize outputs

Removed the remaining altcoinj/libdohj dependency by moving PirateChain mainnet onto Qortium's shared static network parameter model and replacing the old libdohj parity tests for Litecoin, Dogecoin, DigiByte, and Ravencoin with deterministic static-parameter checks. Bitcoiny unspent-output and transaction-output lookups now return Qortium value objects instead of bitcoinj `TransactionOutput` objects, so HTLC redeem/refund code accepts resolved `UnspentOutput` data and only converts back to bitcoinj structures internally while signing.

### 2026-05-06 - crosschain: migrate Bitcoin to static params

Moved Bitcoin mainnet, testnet3, and regtest away from bitcoinj's network parameter classes and onto the shared `StaticBitcoinyParams` model, including Bitcoin's genesis transaction, network headers, DNS seeds, BIP32 headers, segwit address prefixes, regtest settings, and deterministic parity tests against the previous params. This keeps BTC on the same reusable parameter path as the other Bitcoiny coins while preserving the existing `MAIN`, `TEST3`, and `REGTEST` network choices.

### 2026-05-06 - crosschain: migrate Litecoin to static params

Moved Litecoin mainnet, testnet4, and regtest away from inherited libdohj parameter classes and onto the shared `StaticBitcoinyParams` model, including Litecoin's custom genesis transaction, scrypt chain hashes, network headers, DNS seeds, monetary settings, and deterministic tests for the static params. The Litecoin public test network is now named `TEST4` to match the genesis hash and Electrum servers already used by Qortium, and the old Litecoin P2SH address normalizer now uses shared static params instead of a libdohj subclass. Unsupported Dogecoin, DigiByte, Ravencoin, and Dash testnet/regtest choices are no longer advertised until those networks have real params and usable servers.

### 2026-05-06 - crosschain: migrate Dogecoin to static params

Moved Dogecoin mainnet and testnet away from the inherited libdohj parameter classes and onto the shared `StaticBitcoinyParams` model, including Dogecoin's custom genesis transaction, no-fixed-cap money setting, network headers, DNS seeds, and parity tests against the previous params. This also corrects the registered Dogecoin testnet genesis hash so the DOGE testnet spec points at Dogecoin's actual testnet instead of the inherited Litecoin testnet value, and tightens the shared static genesis handling so non-double-SHA chains like Dash and Ravencoin can expose their configured chain hashes consistently in tests.

### 2026-05-06 - crosschain: migrate DigiByte to static params

Moved DigiByte mainnet away from the inherited libdohj parameter class and onto the shared `StaticBitcoinyParams` model, reusing the custom genesis transaction/header support introduced for Ravencoin and adding parity tests against the previous DigiByte params. This continues reducing production reliance on altcoinj/libdohj-specific params classes while keeping runtime wallet, HTLC, and ACCT behavior covered by the shared Bitcoiny tests.

### 2026-05-06 - crosschain: migrate Ravencoin to static params

Moved Ravencoin mainnet away from the inherited libdohj parameter class and onto the shared `StaticBitcoinyParams` model, including custom genesis-header support, interval and monetary parameter hooks, and parity tests against the previous Ravencoin params. This is the first existing BTC-like coin migrated to the shared parameter model and establishes the safer pattern for moving the remaining coins away from altcoinj/libdohj-specific params classes.

### 2026-05-06 - crosschain: add Dash Bitcoiny support

Added Dash as the first new reusable BTC-like chain after the Bitcoiny registry cleanup, including shared static network parameters, registry/spec metadata, verified SSL Electrum seeds, shared deterministic wallet/HTLC/ACCT test coverage, and documentation for selecting the Dash network. The Electrum refresh tool now also preserves existing generated server source and response-time metadata when refreshing a single coin, preventing unrelated server-list churn.

### 2026-05-06 - crosschain: remove supported-blockchain facade

Removed the temporary `SupportedBlockchain` enum facade and moved the remaining wallet settings, PirateChain ACCT, PirateChain trade-bot, and test/helper lookups onto `ForeignBlockchainRegistry` entries. The registry now also owns ACCT-to-trade-bot routing, so new registered crosschain families can add runtime, ACCT, and trade-bot routing in one central lookup path instead of updating a separate trade-bot map.

### 2026-05-06 - crosschain: route foreign fees through registry

Moved foreign-fee offer scanning, local fee processing, backup/import, and ACCT lookup from the `SupportedBlockchain` facade to `ForeignBlockchainRegistry` entries. Foreign-fee handling now iterates the registry and processes any enabled registry entry backed by a `Bitcoiny` runtime, keeping this runtime path ready for new registered BTC-like chains without adding enum cases. Also cleaned the archive trade-bot import test so it commits imported repository changes before close, avoiding a repository leak diagnostic during focused verification.

### 2026-05-06 - crosschain: route API filters through registry

Moved crosschain API and websocket blockchain filters from `SupportedBlockchain` enum parameters to `ForeignBlockchainRegistry` string lookups. API callers can now use registry names or currency codes, websocket sessions normalize filters to canonical registry names, and the remaining trade/ledger/P2SH helper paths resolve ACCT and Bitcoiny instances through the registry instead of adding new enum dependencies. The shared API test helper now also fails when an expected API error is not thrown, making invalid-filter regression tests meaningful.

### 2026-05-06 - crosschain: route backend ACCT lookups through registry

Added direct `ForeignBlockchainRegistry` helpers for registered Bitcoiny entries, entry names, and required-name validation, then moved trade-bot, presence, and HTLC lookup paths off the temporary `SupportedBlockchain` facade. This keeps new backend crosschain code pointed at the data-driven registry while the enum remains only as compatibility plumbing for older callers.

### 2026-05-06 - crosschain: resolve Bitcoiny ACCT chains through registry

Moved Bitcoiny ACCT build and parse logic off the `SupportedBlockchain` facade so shared Bitcoiny trade ATs now accept registry entries and resolve stored foreign-chain ids through `ForeignBlockchainRegistry`. This keeps the core ACCT format path data-driven and makes future BTC-like coins depend on registry metadata instead of enum-specific build helpers.

### 2026-05-06 - crosschain: move Bitcoiny lifecycle into registry

Moved registered Bitcoiny runtime ownership out of the `SupportedBlockchain` enum and into `ForeignBlockchainRegistry`, which now builds BTC-like entries directly from `BitcoinyChainSpecs` and the PirateChain entry explicitly. Bitcoiny specs now carry canonical registry names, and `SupportedBlockchain` is reduced to a compatibility facade that delegates instance lookup, ACCT lookup, ids, currency codes, and test resets back to the registry.

### 2026-05-06 - crosschain: add foreign blockchain registry

Added a central foreign blockchain registry that resolves chain names, currency codes, Bitcoiny foreign-chain ids, and ACCT implementations from one place while keeping `SupportedBlockchain` as a temporary compatibility facade. Trade-bot offer creation now accepts a foreign blockchain string, so the API can move toward data-driven chain registration instead of requiring callers to serialize a Java enum. Bitcoiny wallet creation now also propagates the selected bitcoinj context consistently, which keeps registered multi-coin tests isolated when BTC-like chains run in one JVM.

### 2026-05-06 - crosschain: streamline Bitcoiny spec definitions

Condensed the Bitcoiny chain specification registry with a reusable builder so each BTC-like chain now supplies mostly metadata instead of repeating the same mainnet, testnet, regtest, fee, and Electrum refresh wiring. Remaining user-facing crosschain text was also made more generic so the API describes foreign-chain funds and supported Bitcoiny chains instead of naming only the originally supported coins.

### 2026-05-06 - crosschain: simplify Bitcoiny seed and API data

Moved Bitcoiny Electrum server fallback data fully into the generated JSON resource so the refresh tool no longer rewrites Java hardcoded server lists, and existing generated seeds are preserved as refresh input before 1209k and Electrum peer discovery add updates. The Bitcoiny trade and HTLC API models also drop old Bitcoin-only compatibility aliases in favor of generic foreign-chain fields, reducing baseline API clutter for new chains.

### 2026-05-06 - crosschain: parameterize Bitcoiny chain tests

Replaced the duplicated Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin crosschain test classes with one registered Bitcoiny test suite driven by per-chain fixtures, so new BTC-like coins can reuse the same deterministic wallet, fee, and HTLC coverage without another copied test wrapper. Bitcoiny chain lookup is now centralized in `SupportedBlockchain`, reducing repeated type checks across the API, trade-bot, foreign-fee, and helper-app paths.

### 2026-05-06 - crosschain: collapse Bitcoiny coin wrappers

Replaced the separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin runtime wrappers with one registry-backed Bitcoiny implementation that is created from each chain specification. BTC-like network selection now uses a generic `bitcoinyNetworks` settings map keyed by currency code, Bitcoin's default spend fee and Litecoin's address normalization live in the shared chain specs, and the generic Bitcoiny API route now validates supported chains through the registry instead of hardcoding every coin in the path.

### 2026-05-06 - crosschain: register Bitcoiny chain specs

Moved BTC-like chain metadata into a shared Bitcoiny chain specification registry so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin now describe their display names, ticker symbols, supported networks, fee defaults, minimum order amounts, and Electrum refresh metadata in one reusable place. The existing coin wrappers and settings remain compatible for now, but they now delegate their network definitions to the registry, giving future coins a single model to copy into instead of scattering metadata across wrappers and tools.

### 2026-05-06 - crosschain: centralize Bitcoiny chain definitions

Centralized the BTC-like chain lifecycle behind shared Bitcoiny chain definitions so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin no longer each maintain their own singleton setup. Built-in Electrum fallback servers now live in one `BitcoinyServers` helper, and the refresh tool updates those named fallback lists directly while preserving the generated JSON server resource as the preferred runtime source.

### 2026-05-05 - crosschain: condense Bitcoiny chain plumbing

Reduced duplicated Bitcoin-like chain code by moving common network metadata into shared static Bitcoiny network delegates, adding a generic send request model, and replacing the separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin API resources with one generic Bitcoiny resource that resolves chains by name or ticker. Litecoin address normalization now lives behind the shared Bitcoiny address hook so the generic send path can preserve its P2SH handling.

### 2026-05-05 - crosschain: configure Bitcoiny coins generically

Moved the remaining BTC-like coin wrappers onto a shared configured Bitcoiny implementation so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin now reuse the same provider setup, fee, and minimum-order plumbing. The unused dynamic BitcoinyTBD experiment was removed, the supported-blockchain registry now carries stable currency codes, and the crosschain helper apps can resolve any configured Bitcoiny chain instead of being limited to hard-coded BTC and LTC switches.

### 2026-05-05 - crosschain: add generic Bitcoiny ACCTv3

Replaced the duplicated Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin ACCTv3 and trade-bot implementations with one shared Bitcoiny ACCTv3 path that stores the selected foreign blockchain in the AT data itself. Trade offers, trade-bot responses, websocket updates, HTLC helpers, price estimates, and foreign-fee handling now read the foreign chain from parsed trade data instead of inferring it from separate per-coin classes, while PirateChain remains on its dedicated ACCT path.

### 2026-05-05 - api: add manual QDN auto-update controls

Added restricted `GET /admin/update` and `POST /admin/update` endpoints so operators can manually check for and schedule the latest approved QDN auto-update even when automatic background updates are disabled. The manual path reuses the same approved development-group manifest lookup, QDN binary validation, SHA-256 verification, and apply-update restart flow as the background updater, with status details returned for unavailable, current, newer, and already-installing cases.

### 2026-05-05 - core: route auto-updates through QDN

Changed auto-update transport so approved update manifests now point to QDN-hosted `AUTO_UPDATE_BINARY` resources instead of GitHub auto-update branches. The updater starts from `autoUpdateEnabled` without requiring `autoUpdateRepos`, ignores legacy HTTP manifests, fetches the pinned QDN binary transaction by signature when available, verifies the SHA-256 hash of the XORed update bytes, and then uses the existing apply-update restart path. The publish/build helper scripts now create a local `qortium.update`, publish it to QDN, submit a compact dev-group `AUTO_UPDATE` manifest for approval, and document the new approval workflow.

### 2026-05-05 - test: tighten skipped-test guardrails

Added a default test hygiene check that fails if new `@Ignore` annotations are introduced, added a manual xvfb-backed GUI display workflow for splash and tray tests, and gave the JaCoCo coverage profile low starting thresholds for instruction, branch, and line coverage so regressions are visible without making the baseline unrealistic. PirateChain crosschain tests now avoid counting inherited BTC-like deterministic wallet and HTLC checks as skipped when those paths do not apply to ARRR, while adding deterministic Pirate HTLC script and P2SH address coverage.

### 2026-05-05 - test: add JaCoCo coverage reporting

Added an opt-in JaCoCo coverage profile to the Maven test workflow so deterministic test runs can produce HTML, XML, and CSV coverage reports without enforcing a pass/fail threshold yet. The CI test command now enables the repo's JUnit test property explicitly, generates the coverage report, and prints instruction, branch, and line coverage from the generated JaCoCo CSV summary, while the testing guide documents the local coverage command, report location, and MemoryPoW exclusion needed to keep full-suite coverage runs practical. The full-suite coverage pass also exposed and fixed poll-prefix pagination so `limit` and `offset` apply to polls instead of joined poll-option rows.

### 2026-05-05 - test: require explicit GUI display opt-in

Tightened the GUI test split so splash and system-tray display tests now require both a display-capable JVM and `-Dqortium.runGuiDisplayTests=true`, while the normal headless Maven path continues to exercise the splash no-op behavior. The testing guide now documents the GUI display opt-in command, including an `xvfb-run` example for automated display-backed runs.

### 2026-05-04 - test: tighten crosschain HTLC fixture coverage

Expanded BTC-like crosschain HTLC tests so the default suite builds deterministic in-memory fixtures for secret discovery and funded-status detection across supported coins, instead of relying on inherited live fixture checks for coins without known public HTLC data. Live PirateChain fixture checks now fail clearly when no configured light-client server returns the expected fixture data, and the testing guide now includes concrete commands for each opt-in integration test group.

### 2026-05-04 - test: add deterministic bootstrap and ElectrumX coverage

Added local deterministic coverage for bootstrap host HTTP checks and ElectrumX UTXO parsing so the default test suite validates more of the external-service logic without depending on public infrastructure. Bootstrap tests now exercise HEAD request status handling, archive size validation, and freshness validation against a loopback HTTP server, while ElectrumX tests now cover mock unspent-output parsing and confirmed/unconfirmed filtering. Explicit live ElectrumX runs also fail clearly if no Bitcoin testnet servers are configured.

### 2026-05-04 - test: improve opt-in test defaults

Made conditional test groups more useful in normal Maven runs by adding a headless splash no-op test, keeping graphical splash and tray checks scoped to display-capable runs, and extending MemoryPoW's default coverage with known full-buffer verification fixtures through difficulty 14 while leaving expensive compute benchmarks opt-in. Added a testing guide that lists the remaining opt-in properties for long MemoryPoW, GUI display, live bootstrap, live ElectrumX, live crosschain, and live repository integrity checks.

### 2026-05-04 - test: replace ignored tests with active coverage

Removed the remaining `@Ignore` annotations from the test suite by turning the skipped online-account API placeholder into deterministic empty-state API coverage, replacing the old nonce-compression diagnostic with active `ONLINE_ACCOUNTS_V3` nonce serialization checks, and changing the live repository name-integrity scan into an explicit opt-in check with real assertions. The name integrity tests now also cover deterministic reduced-name persistence without depending on a live repository.

### 2026-05-04 - test: make crosschain checks deterministic

Replaced ignored BTC-like crosschain stubs with deterministic mock-provider coverage for wallet scanning, spend building, wallet balances, median block time, and HTLC secret/status handling while keeping public ElectrumX, PirateChain, and other live crosschain checks behind explicit opt-in properties. The Dogecoin, Digibyte, Litecoin, Ravencoin, Bitcoin, HTLC, and PirateChain crosschain tests now provide default offline coverage without depending on unavailable testnet servers, and the opt-in PirateChain funded-HTLC checks now tolerate inconsistent light-client servers by using confirmed UTXOs and trying each configured live fixture server.

### 2026-05-04 - test: enforce clean transfer-privs recipients

Simplified TRANSFER_PRIVS processing for Qortium's new-chain rules so privilege transfers only create and populate a previously unused recipient account, removed the obsolete stored flag for transfers into existing recipients, and replaced the ignored transfer-privs test class with active coverage for clean-recipient success, orphan cleanup, funded non-genesis senders, and rejection of already-used recipient accounts.

### 2026-05-04 - test: make block serialization deterministic

Replaced the ignored random all-transaction block serialization test with deterministic round-trip coverage for an empty minted block and a minted block containing a payment transaction. The test now checks block length, serialized block metadata, transaction count, and embedded transaction bytes without trying to synthesize unrelated transaction setup inside a single fragile test.

### 2026-05-04 - test: unskip useful PoW and block timestamp checks

Converted MemoryPoW tests from a fully ignored class into fast deterministic default coverage, including an 8 MiB difficulty-8 compute smoke test. The long MemoryPoW benchmark is split by difficulty and remains available via `-Dqortium.runLongMempowTests=true`. The block timestamp API now returns genesis when it is the nearest block at or before the requested timestamp, and the previously ignored timestamp API test now asserts deterministic genesis and minted-block lookups.

### 2026-05-04 - test: harden live bootstrap host checks

Kept bootstrap host availability checks opt-in behind `-Dqortium.runLiveBootstrapChecks=true`, but changed explicit live runs to fail when no hosts are configured instead of silently skipping. Live checks now use bounded HTTP timeouts and can target release hosts directly with `-Dqortium.liveBootstrapHosts=https://host-one,https://host-two`.

### 2026-05-04 - test: keep ElectrumX coverage deterministic

Changed ElectrumX tests so default Maven runs exercise deterministic mock RPC responses instead of depending on public testnet ElectrumX servers, while the live server checks remain available with `-Dqortium.runLiveElectrumXTests=true`. ElectrumX header parsing now accepts numeric JSON counts from servers that return integers or numeric strings, retries another server after malformed block-header JSON, and connection logging no longer assumes a blockchain object is attached when tests construct the provider directly.

### 2026-05-04 - test: align arbitrary data and asset API checks

Updated arbitrary data tests to match the current PUT-only publishing behavior while still checking explicit PATCH rejection, made binary arbitrary-data diffs fall back to whole-file patches instead of failing on non-text input, adjusted raw on-chain arbitrary-data size checks for AES-GCM overhead, and fixed account persistence so existing genesis account rows gain their public key when the account later signs a transaction. The previously failing arbitrary-data and asset API test groups now pass without involving the external ElectrumX tests.

### 2026-05-04 - core: reserve asset 0 for explicit native bootstrap

Changed asset issuance so normal assets can be issued before the native asset exists while still starting at asset ID `1`, leaving asset ID `0` reserved for an explicit native bootstrap request. `ISSUE_ASSET` now carries an optional requested asset ID, only accepts `0` for development-group-approved native bootstrap, rejects repeated native bootstrap attempts after asset `0` exists, and keeps zero-quantity issuance limited to the native asset. Tests now cover normal pre-native asset issuance, native bootstrap after normal assets, one-time native creation, rejected non-native ID requests, and serialization round trips for the new field.

### 2026-05-03 - build: keep Maven plugins out of runtime shade

Removed Maven build plugins from the project's runtime dependency list while leaving them configured in the build plugin section, excluded Maven plugin artifacts that the AT dependency publishes transitively as compile dependencies, removed unused or redundant runtime dependencies for JavaMail, the direct servlet API, and Swagger's servlet initializer, excluded duplicate JAXB, JSON-P, and activation API jars where equivalent implementation/runtime jars remain available, and filtered Java module descriptors out of the shaded runtime jar. The package-info and build-helper plugins are still available during the build, but build-tool dependency trees and several duplicate API jars are no longer pulled into Qortium's shaded runtime jar.

### 2026-05-03 - build: clean import export unchecked warning

Tightened repository import/export JSON handling by typing the parsed result, validating exported array items before treating them as JSON objects, and using try-with-resources for backup file writes. This preserves the existing backup formats while removing the unchecked compiler notice from the HSQLDB import/export helper.

### 2026-05-03 - build: clean block archive reader unchecked warnings

Replaced raw archive file-list entries and unchecked casts in the block archive reader with typed map entries. Archive file selection and last-height calculation still use the same cached filename metadata, but the code now compiles without that unchecked warning.

### 2026-05-03 - build: clean name integrity comparator warning

Replaced the raw comparator used by the name database integrity checker with a typed transaction comparator. This preserves the existing block-height, timestamp, and signature ordering while removing the next unchecked compiler notice from the warning cleanup pass.

### 2026-05-03 - crypto: update X25519 field decode call

Updated the Ed25519 helper's X25519 field decoding call to Bouncy Castle's current no-offset overload. This preserves the same point-decoding behavior while removing the final Bouncy Castle deprecation warning introduced by the dependency update.

### 2026-05-03 - crosschain: update deprecated library API usage

Updated the Pirate Chain trade bot to call bitcoinj's current Bech32 encoding API explicitly, and documented the remaining non-dust-output overrides as intentional compatibility points with bitcoinj/libdohj's abstract network-parameter interface. This keeps cross-chain behavior unchanged while reducing avoidable dependency deprecation warnings.

### 2026-05-03 - build: clean remaining low-risk compiler notices

Removed raw map types from the API map adapter, replaced unchecked cache-filter test casts with checked helper methods, updated utility code to use current SevenZ and ICU Unicode APIs, and removed the deprecated trade-bot `xprv58` response fallback in favor of the existing `foreignKey` field. This keeps the baseline API stricter while reducing compiler noise before tackling the remaining third-party cross-chain deprecations.

### 2026-05-03 - api: replace deprecated optional SSL connector

Replaced the API service's deprecated Jetty `OptionalSslConnectionFactory` usage with the supported `DetectorConnectionFactory` plus `SslConnectionFactory` pattern. The API connector still detects TLS on the shared API port, routes encrypted connections through ALPN for HTTP/2 or HTTP/1.1, and falls through to HTTP/1.1 for clear-text requests.

### 2026-05-03 - build: clean low-risk compiler notices

Updated the AT logger wrapper to use Java's standard supplier type instead of Log4j's deprecated supplier interface, and marked the network-message clone helper's generic clone cast as intentionally unchecked. This removes low-risk compiler notices without changing message cloning or custom AT log-level behavior.

### 2026-05-03 - build: clarify HSQLDB varargs binds

Updated HSQLDB repository queries that bind typed arrays into varargs helper methods so the arrays are explicitly treated as SQL bind parameter lists. This preserves the existing query behavior while removing javac warnings about ambiguous non-varargs calls.

### 2026-05-03 - build: disable implicit javac annotation processing

Set the Maven compiler plugin to disable javac annotation processing explicitly. Qortium's normal build relies on Maven code-generation plugins for generated sources rather than javac annotation processors, so this keeps current behavior while avoiding javac's warning about future annotation-processing defaults.

### 2026-05-03 - crypto: use AES-GCM for QDN encryption

Replaced the baseline QDN/arbitrary-data AES file encryption format with `AES/GCM/NoPadding`. New encrypted payloads now store a 12-byte GCM nonce followed by ciphertext and the 16-byte authentication tag, removing the inherited CBC padding format and legacy AES fallback paths. Decryption now writes to a temporary file and only moves the plaintext into place after the GCM authentication tag verifies.

### 2026-05-03 - crypto: tighten TLS protocol defaults

Restricted API, gateway, domain-map, dev-proxy, and Electrum SSL setup to modern TLS defaults. Server-side Jetty SSL now uses a shared TLS policy that enables TLS 1.3/TLS 1.2, excludes legacy SSL/TLS protocol names, and includes HTTP/2-safe AEAD cipher suites. Electrum SSL sockets now request the `TLS` context instead of the legacy `SSL` alias and enable only TLS 1.3/TLS 1.2 when supported.

### 2026-05-03 - build: update Bouncy Castle to 1.84

Updated all Bouncy Castle runtime dependencies from `1.78.1` to `1.84`, covering the core provider, TLS/JSSE provider, PKIX certificate utilities, and shared utility jar. This keeps Qortium on the current Bouncy Castle release line before the follow-up TLS and encryption hardening changes.

### 2026-05-03 - network: replace vendored UPnP with jUPnP

Replaced the inherited locally vendored WaifUPnP jar with the maintained `org.jupnp:org.jupnp` Maven Central dependency. Added a small internal port-mapping abstraction so P2P and QDN networking no longer call a specific UPnP library directly, while keeping automatic router mapping best-effort and non-fatal if a router does not support it.

### 2026-05-03 - build: document and reproduce local dependency patches

Added a HSQLDB verification/rebuild script that downloads the official Maven Central HSQLDB jar, verifies checksums, and confirms Qortium's checked-in patched jar differs only by changing the manifest seal line from `Sealed: true` to `Sealed: false`. Expanded dependency provenance docs with HSQLDB checksums, the verification process, and the locally vendored WaifUPnP artifact so nonstandard dependencies now have tracked ownership and reproducibility notes.

### 2026-05-03 - build: own altcoinj dependency fork

Moved the inherited altcoinj Maven dependency from the IceBurst GitHub coordinate to the Qortium-controlled `QuickMythril/altcoinj` fork while keeping the same pinned bitcoinj `0.16` compatibility commit. Expanded dependency provenance notes with the bitcoinj -> libdohj -> altcoinj fork chain and the foreign-chain parameter support carried by that dependency.

### 2026-05-03 - build: own CIYAM AT dependency fork

Moved the CIYAM AT Maven dependency from the inherited IceBurst GitHub coordinate to the Qortium-controlled `QuickMythril/AT` fork while keeping the same pinned `v1.4.3` commit, making the change behavior-neutral. Added dependency provenance notes for the AT fork chain, the remaining inherited `altcoinj` fork, and the locally patched HSQLDB jar so future dependency ownership work has a tracked starting point.

### 2026-05-03 - core: allow ATs to use arbitrary spendable assets

Updated AT payment validation so AT accounts can receive any existing spendable asset while still rejecting unspendable assets. ATs can now pay arbitrary spendable assets through the asset-specific payout function, including native asset surplus after reserving the current round's maximum execution fees. Tests cover non-configured asset deposits, payouts, native surplus payouts, and unspendable asset rejection.

### 2026-05-03 - core: add asset-specific AT payment reads

Added AT chain functions for reading incoming payment amounts by asset id and counting payment entries addressed to the AT. New ATs can now process mixed configured-asset plus native-asset multipayments without changing the legacy single-amount/single-asset functions, which still report mixed-asset multipayments as ambiguous.

### 2026-05-03 - core: expose MULTI_PAYMENT entries to ATs

Added MULTI_PAYMENT transactions to AT inbox lookup and AT payment decoding. ATs now treat multipayments as payment-like incoming transactions, summing only entries addressed to the AT when they share one asset, while mixed-asset entries to the same AT remain ambiguous for the existing single-amount/single-asset AT functions. Tests cover single-entry multipayments, repeated AT recipients, mixed external recipients, mixed assets, and native fee top-ups.

### 2026-05-03 - core: expose MESSAGE payments to ATs

Updated AT transaction decoding so MESSAGE transactions with attached payments continue to report as messages while also exposing their payment amount and asset id through the existing amount and asset-id AT functions. Tests now cover configured-asset message payments, native message fee top-ups, and no-payment messages.

### 2026-05-03 - core: broaden AT asset support

Added a native fee reserve to DEPLOY_AT so an AT can be funded with both its configured working asset and asset `0` for execution fees in the same transaction. ATs now use their configured asset for default balance, payout, and final refund semantics while block processing continues to charge execution fees in the native asset, and new chain function codes let ATs inspect configured asset IDs, query asset balances, read incoming payment asset IDs, and pay non-native asset balances. External asset transfers to existing ATs now allow only the AT's configured asset plus native fee top-ups, and tests cover deploy funding, native fee separation, configured-asset payouts, rejected wrong-asset transfers, and transfer-asset visibility.

### 2026-05-03 - core: tighten reduced-name anti-spoofing

Changed reduced-name sanitizing so lowercase `i` joins the same reduced homoglyph class as uppercase `I`, lowercase `l`, `1`, and `|`, treats visually blank filler characters as whitespace before trimming and reduced-name generation, and strips control/format/private-use codepoints during normal name validation. This closes inherited spoofing gaps that allowed visually confusing names such as `sample-label` and `sample-Iabel`, names with trailing blank filler characters, or names with hidden bidirectional display controls to remain distinct, and adds coverage for names, groups, assets, and direct Unicode sanitizer behavior while leaving multi-character visual substitutions as a separate policy decision.

### 2026-05-03 - chat: restore general chat support

Restored public groupless CHAT transactions by removing the inherited API, peer-import, and websocket blocks against messages with no group and no direct recipient. General Chat continues to use the existing CHAT validation rules, including MemoryPoW, timestamp bounds, data-size limits, block lists, and recent-message rate limiting, and tests now cover import, retrieval, direct-chat separation, and active-chat listing for group 0.

### 2026-05-03 - core: enforce group invite expiries

Fixed group invite and ban expiry calculations so long time-to-live values do not overflow before being converted to milliseconds. Added timestamp-aware active-invite lookup for validation and group processing so expired invites can no longer be used to join closed groups or canceled as if they were still pending.

### 2026-05-03 - test: discard name integrity repair changes

Updated name integrity repair tests to explicitly discard temporary repository changes after their assertions. This keeps the tests focused on repair behavior while preventing repository-close warnings about uncommitted test work.

### 2026-05-03 - core: stop arbitrary name validation repairs

Removed ARBITRARY transaction pre-processing that rebuilt name table state before validation. Added coverage for named arbitrary publishes so validation rejects missing name state until an explicit name integrity rebuild restores the name.

### 2026-05-03 - core: stop name-sale validation repairs

Removed SELL_NAME, CANCEL_SELL_NAME, and BUY_NAME pre-processing that rebuilt name table state before validation. Updated name integrity tests so sale, cancel-sale, and buy-name validation reject missing current name state until an explicit name integrity rebuild restores the sale state.

### 2026-05-03 - core: stop update-name validation repairs

Removed UPDATE_NAME pre-processing that rebuilt name table state before validation. Updated integrity tests so missing current-name and destination-name state is only repaired by explicit name integrity rebuilds, keeping update-name validation focused on current repository state.

### 2026-05-03 - core: default transaction preProcess to no-op

Changed transaction pre-processing to default to a no-op in the base transaction class and removed redundant no-op overrides from individual transaction classes. This keeps preProcess overrides limited to transaction types that actually need preparatory behavior.

### 2026-05-03 - core: stop register-name validation repairs

Removed the REGISTER_NAME validation-time rebuild of name table state so register-name validation no longer mutates repository name data as a side effect. Updated name integrity tests to verify validation uses the current repository state, while explicit name integrity repair still restores missing name data and then causes duplicate registration attempts to be rejected.

### 2026-05-03 - i18n: synchronize translation bundles

Updated the translation checker so it discovers available language bundles instead of relying on a short hardcoded list. Fixed underscore locale loading for bundles such as zh_CN and zh_TW, removed stale translation keys, added missing current keys across the existing bundles, and cleaned malformed UTF-8 bundle headers so the checker now reports all discovered translation bundles as synchronized.

### 2026-05-03 - core: add explicit asset ownership transfers

Removed direct owner-change fields from UPDATE_GROUP and UPDATE_ASSET so those transactions only update mutable metadata and settings. Added explicit SELL_ASSET_OWNERSHIP, CANCEL_SELL_ASSET_OWNERSHIP, and BUY_ASSET_OWNERSHIP transactions, including public sales, direct recipient sales, zero-price direct gifts, orphan restoration, API builders, and native asset ownership-transfer rejection.

### 2026-05-03 - core: allow asset renames

Added optional asset renaming to UPDATE_ASSET, including reduced-name collision checks, updated transaction persistence and wire format, and orphan restoration for asset name history.

### 2026-05-03 - core: allow explicit primary name updates

Added an optional primary-name setting to UPDATE_NAME so owners can set or clear their primary name explicitly, with orphan restoration. Removed the restrictions that prevented updating or selling a primary name when the owner had multiple names.

### 2026-05-03 - core: allow group renames

Added optional group renaming to UPDATE_GROUP, including reduced-name collision checks, updated transaction persistence and wire format, and orphan restoration for group name history.

### 2026-05-03 - core: fix update group mutable settings

Made UPDATE_GROUP apply and orphan its approval block delay fields. UPDATE_GROUP no longer transfers group ownership or auto-adds the requested owner as a member/admin; ownership changes are reserved for future explicit group sale transactions.

### 2026-05-02 - core: support direct name sales

Added optional direct-sale recipients to name sale state so sellers can list a name for a specific buyer. Direct sales can use a zero price, but the recipient still has to submit a BUY_NAME transaction to accept the transfer; public name sales still require a positive price. Orphan handling now restores direct-sale recipient details for canceled and completed name sales.

### 2026-05-02 - core: enable publicize account bootstrap

Re-enabled PUBLICIZE as a fee-or-MemoryPoW transaction so new accounts can publish their public key without needing native coins first. Valid unseen addresses now return placeholder account data instead of an unknown-address error, while public-key lookups still return false until the account key has actually appeared on chain.

### 2026-05-02 - api: expose addresses in voting API responses

Added direct voter address fields to poll vote API data and VOTE_ON_POLL transaction JSON while keeping the existing voter public key fields. This lets clients display voter addresses without making a separate public-key lookup, and adds regression coverage confirming TRANSFER_PRIVS transaction JSON exposes both the original creator address and recipient address.

### 2026-05-02 - api: complete transfer privs API surface

Added the missing TRANSFER_PRIVS transaction API surface. Generic transaction responses can now expose typed transfer-privs data, and `/addresses/transferprivs` can build a validated raw unsigned TRANSFER_PRIVS transaction for clients to sign.

### 2026-05-02 - api: fix cross-chain current server reporting

Fixed cross-chain server configuration reporting so a disconnected foreign-chain provider no longer marks every configured server as current. The current-server flag now only appears when the provider actually has a matching current server.

### 2026-05-02 - api: expose minter address in block summaries

Added `minterAddress` to block summary API responses and populated it from reward-share minting state for range and signer-summary endpoints. This makes summary responses consistent with full block responses and avoids requiring clients to resolve minter public keys themselves; signer summaries also now preserve current repository results when there are no archived matches.

### 2026-05-02 - api: add local list discovery

Added an authenticated `GET /lists` API path that returns the known local list names in stable sorted order. This completes the basic list-management surface so clients can discover available node-local lists before fetching or updating a specific list.

### 2026-05-02 - test: remove stale feature trigger config

Removed obsolete feature trigger keys from the testnet chain config and mirrored test chain fixtures. Chain configs now only list the active `transactionV6Timestamp` feature trigger, matching the current baseline code and avoiding inherited no-op transition settings.

### 2026-05-02 - core: rename Ed25519 crypto extras

Renamed the inherited `Qortal25519Extras` Java helper to `Ed25519Extras` so the active crypto utility naming describes the Ed25519/X25519 and aggregate-signature behavior without carrying Qortal branding. This keeps the existing `org.qortal` package namespace unchanged.

### 2026-05-02 - installer: neutralize Windows installer identity

Updated the Windows installer project from inherited Qortal naming to Qortium naming. The Advanced Installer project, installer output, application executable, bundled JAR, shortcuts, log defaults, install directory, icon filename, visible installer artwork, and upgrade family now use Qortium identity while keeping the current Java main class namespace unchanged.

### 2026-05-02 - core: preserve QDN raw-data filenames

Changed QDN resource rebuilding so raw on-chain data can restore the original filename when transaction metadata records a single source file. Resources without metadata still use the existing `data` fallback, and rebuilt metadata paths are validated so they cannot escape the reader output directory.

### 2026-05-02 - docs: neutralize small helper examples

Updated narrow helper documentation that still used inherited Qortal or QORT examples. The database sqltool example now uses the Qortium URL ID, and the testnet genesis guidance now refers to native asset funds instead of QORT funds.

### 2026-05-02 - core: replace Qortal QDN category metadata

Replaced the inherited `QORTAL` arbitrary metadata category with a neutral `NETWORK` category. QDN helper validation and arbitrary metadata tests now use `NETWORK`, so new baseline chains no longer expose a Qortal-branded category in the active metadata vocabulary.

### 2026-05-02 - tools: neutralize auto-update release helpers

Updated auto-update and release helper defaults from inherited Qortal release names to Qortium names. Release-note generation now defaults to the Qortium repository, working directory, JAR, EXE, ZIP, and release title, and the auto-update documentation no longer points at the old Qortal mirror example.

### 2026-05-02 - core: neutralize TLS and small runtime examples

Neutralized remaining low-risk runtime examples and comments that still used inherited Qortal identity. Generated SSL certificate subjects now use Qortium-local names, account and resource comments use generic chain terminology, and internal HSQLDB notes describe the project-specific extension without Qortal branding.

### 2026-05-02 - tools: neutralize helper script naming and defaults

Updated helper scripts that still exposed inherited Qortal naming in active defaults. API helpers now use neutral example hosts and Qortium API-key paths, QDN publishing uses `QDN_PRIVKEY`, the transaction helper uses current native cross-chain field names, and the transaction helper no longer depends on removed account last-reference state.

### 2026-05-02 - runtime: neutralize log and testnet identity defaults

Updated runtime and testnet defaults that still used inherited Qortal naming. Log files now default to Qortium names, the testnet stop script now looks for and reports Qortium processes, and the testnet bootstrap native asset is labelled generically as `NATIVE` instead of using the old QORT identity.

### 2026-05-02 - api: neutralize public API labels and examples

Updated public API documentation labels and examples that still used inherited Qortal wording. The OpenAPI title now says Qortium API, name-transaction metadata examples use neutral chain wording, peer host examples use generic hostnames, and API-facing source comments no longer use Qortal as the public chain label.

### 2026-05-02 - core: neutralize runtime UI identity strings

Updated active runtime and GUI-facing identity strings from inherited Qortal wording to Qortium wording. Node version strings now use the `qortium-` prefix, the main controller thread is named Qortium, splash and system tray text now refer to Qortium Core, and the tray icon resource filenames have been moved to Qortium names while leaving the existing artwork unchanged.

### 2026-05-02 - test: fix repository interrupt test setup

Fixed the repository interrupt test so HSQLDB is explicitly allowed to call its test-only Java sleep routine under the test JVM. The test now also reports the underlying exception when setup or execution fails, shuts down its scheduler, and clears the deliberate interrupt before later repository or JUnit cleanup can inherit it.

### 2026-05-02 - core: neutralize database schema domain names

Renamed the inherited Qortal-branded HSQLDB domain types to reusable neutral names. Database schema definitions now use `AccountAddress`, `AccountPublicKey`, `PrivateKeySeed`, and `AssetAmount`, and the remaining runtime SQL casts were updated to match the neutral schema while leaving table names, column names, APIs, and Java package names unchanged.

### 2026-05-02 - core: neutralize QDN app compatibility names

Moved QDN app and content compatibility names from inherited Qortal terms to Qortium-neutral terms without legacy fallbacks. QDN links now use `qdn://`, injected app helpers use `qdnRequest`, resource metadata lives under `.qdn`, encrypted and raw QDN payload prefixes use `qdn*` names, and the default avatar identifier is now `qdn_avatar`.

### 2026-05-02 - core: neutralize AT API naming

Renamed the inherited Qortal-branded AT integration classes and chain-specific function-code enum to neutral `Chain*` names while preserving the underlying CIYAM AT function-code values and behavior. The chain address conversion function now uses the Java name `CONVERT_B_TO_CHAIN_ADDRESS` but still maps to function code `0x0512`, so generated AT bytecode semantics remain unchanged.

### 2026-05-02 - core: neutralize cross-chain native-side naming

Renamed active cross-chain native-side API fields, helper methods, ledger labels, and docs from inherited Qortal wording to neutral role-based wording. Cross-chain trade data now uses names such as `atAddress`, `creatorAddress`, `creatorTradeAddress`, `partnerAddress`, and `partnerReceivingAddress`, and ACCT helpers now refer to trade ATs without Qortal-specific method names.

### 2026-05-02 - core: neutralize runtime/build identity defaults

Moved the active runtime and build defaults from inherited Qortal names to Qortium names. New nodes now default to `QortiumKeyStore.jks`, `qortium-backup`, `qortium.jar`, `qortium.exe`, `qortium.update`, Qortium Docker paths and environment names, and `org.qortium:qortium` build coordinates, while the Java package namespace remains unchanged for a later dedicated refactor.

### 2026-05-02 - core: neutralize network identity defaults

Moved the peer network message magic out of inherited hardcoded QORT constants and into the chain configuration. Qortium's main and test chain configs now use `QRTM` and `qrtm` as their default network identifiers, and both normal peer networking and data networking read those values from the active chain config so future forks can choose their own network identity without editing Java constants.

### 2026-05-02 - core: remove native asset compatibility alias

Removed the temporary `Asset.QORT` Java compatibility alias now that production and test code use `Asset.NATIVE` for protocol asset ID `0`. This makes the native asset identity explicit in code and leaves any remaining historical QORT wording for separate documentation or branding cleanup.

### 2026-05-01 - core: refactor cross-chain native-side naming from QORT to native

Refactored the cross-chain trade and trade-bot surfaces so the chain-side asset is described as the native asset instead of QORT. Cross-chain request and response fields now use `nativeAmount`, `fundingNativeAmount`, and `nativeBalance`, trade-bot backups and repository storage use `nativeAmount` and `native_amount`, cross-chain labels now use `NATIVE` as the neutral display code, and the remaining cross-chain native balance checks now use `Asset.NATIVE`.

### 2026-05-01 - docs: neutralize native asset wording

Neutralized simple QORT wording in native-asset comments, API descriptions, translation strings, and demo UI text so those surfaces describe the chain's native asset without hardcoding the inherited QORT name. Cross-chain trade labels and `qortAmount`-style API fields remain unchanged for the dedicated cross-chain native-side refactor.

### 2026-05-01 - test/docs: replace test native asset aliases

Replaced the remaining direct test use of the deprecated `Asset.QORT` alias with `Asset.NATIVE` and cleaned up nearby test comments that were only describing native-asset balances or fees. This removes another layer of inherited QORT naming from the test suite while deliberately leaving cross-chain QORT trade labels for the later native-side cross-chain cleanup.

### 2026-05-01 - test/docs: neutralize native asset fixtures

Renamed the standard test-chain native asset fixture from QORT to NATIVE and moved shared test helper defaults to the neutral native asset constant. This keeps tests aligned with Qortium's asset `0` direction without changing production chain behavior, while the audit notes now distinguish the neutralized native fixture labels from broader inherited test identities that still need cleanup.

### 2026-05-01 - core: define native asset bootstrap rules

Defined runtime native asset bootstrap as a development-group-governed asset issuance instead of letting any first asset issue claim asset `0`. When no native asset exists, an ordinary `ISSUE_ASSET` transaction must now target one of the active configured development groups, pass group approval, and can still create asset `0` with either zero or positive initial quantity. The approval path remains coinless because `GROUP_APPROVAL` is covered by the shared MemoryPoW fee-alternative policy, and tests now cover rejection outside the development group plus approved bootstrap into native rewards.

### 2026-05-01 - core: introduce neutral native asset id

Introduced `Asset.NATIVE` as the neutral name for protocol asset ID `0` and moved core fee, reward, payment, and AT fee handling to that name while keeping `Asset.QORT` as a temporary compatibility alias for inherited surfaces that still need cleanup. Native asset bootstrap can now issue asset `0` with zero initial quantity, allowing a chain to define its native reward asset without preallocating any coins while keeping zero-quantity non-native assets invalid.

### 2026-05-01 - core: remove QORT genesis asset from main chain

Removed the inherited QORT asset issue from Qortium's main genesis configuration. A fresh main-chain baseline now starts without a pre-created native asset, so asset `0` can be created later by the first asset issuance while block rewards remain paused until that native asset exists.

### 2026-05-01 - test: cover no-native-asset bootstrap

Added a dedicated test-chain fixture with no genesis native asset or native balances, plus tests that mint safely before asset `0` exists, issue the first asset as asset `0`, and confirm rewards begin once that native asset exists. The MemoryPoW fee-policy tests now also include asset issuance as a representative normal transaction, keeping the coinless bootstrap path covered without making the test suite perform production-cost proof-of-work.

### 2026-05-01 - core: skip native rewards until native asset exists

Changed block reward distribution so Qortium does not create native asset balances before the native asset exists. This keeps minting safe for a future no-genesis-native-asset bootstrap: blocks can still be minted, account progress can still advance, and native rewards simply wait until asset `0` has been issued.

### 2026-05-01 - test: cover mempow fee alternatives for normal transactions

Added broader tests for the shared normal-transaction MemoryPoW fee policy across representative transaction shapes. The tests now check that normal transactions can use either a sufficient paid fee or a valid MemoryPoW nonce, while missing nonces, invalid nonces, and negative fees are still rejected, without changing production behavior.

### 2026-05-01 - core: allow zero-fee processing for mempow transactions

Made the transaction processing paths treat only positive declared fees as balance-moving fees, so a valid zero-fee MemoryPoW transaction does not debit or later refund any QORT. Positive low fees accepted through MemoryPoW are still charged exactly as signed, multipayment validation now relies on the shared payment balance check instead of a broken redundant fee check, and balance recording no longer creates artificial zero-value fee movements for zero-fee transactions.

### 2026-05-01 - core: validate mempow nonce for normal transactions

Made the normal transaction fee policy enforce the new MemoryPoW alternative. Eligible user-created transactions can now pass fee validation with either a sufficient paid fee or a valid MemoryPoW nonce, while missing, invalid, or negative-fee cases are rejected. Block validation now checks the same fee-or-mempow rule so this policy applies to confirmed transactions as well as unconfirmed imports.

### 2026-05-01 - core: add nonce support to normal transaction data

Added a shared MemoryPoW nonce field to normal transaction data so future fee-free transaction support can bind a nonce directly into each transaction's signed bytes and stored repository record. This only adds nonce carriage for eligible normal transaction types; it does not yet compute, validate, or accept MemoryPoW as a replacement for normal transaction fees.

### 2026-05-01 - core: define mempow fee policy for normal transactions

Defined the central Qortium policy for when ordinary user-submitted transactions will be allowed to use a MemoryPoW nonce instead of a paid fee. This commit only adds the shared policy hooks and keeps existing transaction behavior unchanged until later commits add nonce data and verification for eligible transaction types.

### 2026-05-01 - core: remove transaction reference from wire and schema

Removed the inherited general transaction reference from Qortium transaction data, raw transaction bytes, API lookup, chat responses, and database storage. Transaction signatures now cover a smaller baseline payload without the unused reference slot, while independent block, chat, group, name, and asset references remain in place for their own domain-specific state.

### 2026-05-01 - core: remove account last-reference state

Removed the inherited account last-reference record from Qortium accounts, APIs, lite-node account messages, and database schema. Transaction builders now use the deprecated transaction reference slot only as a neutral legacy field, so accounts no longer need a stored sequencing reference before creating or receiving transactions.

### 2026-05-01 - core: disable transaction reference semantics

Stopped using the inherited transaction reference as an account sequencing rule. Transactions no longer need a sender last-reference, processing no longer advances or reverts account last-reference values, and serialization keeps a neutral placeholder when the old field is missing or malformed. This keeps the legacy database and API field in place for now while preparing Qortium for participation models that do not require accounts to already own coins.

### 2026-04-30 - core: tighten null-owned group approval

Allowed null-owned groups to update their own group settings through the same approval path now used for admin, invite, kick, and ban management, instead of requiring the impossible null-account owner signature. Approval decision counting now ignores votes from accounts that are no longer current approval authorities, so old admin votes cannot keep counting after group authority changes.

### 2026-04-30 - test: align default test chain with Qortium genesis

Updated the default test-chain genesis to use the same neutral public development and minting groups as the main Qortium chain, removing inherited Alice-owned group setup, group updates, seeded minting joins, and the seeded reward-share from that fixture. Tests now use explicit bootstrap helpers when they need deterministic minting or development-admin state, keeping the fixture closer to a clean chain while preserving practical test setup.

### 2026-04-30 - test: declare dev groups in reward fixtures

Updated the specialized reward and minting test-chain fixtures to declare which group supplies development-admin reward recipients. This preserves their existing reward expectations now that block rewards use configured development groups instead of an implicit group 1 rule.

### 2026-04-30 - core: honor configured dev groups for block rewards

Changed block reward distribution to read development-admin reward recipients from the configured development group list instead of directly assuming group 1, and to skip the development-admin reward bucket when no real non-null admins exist. This lets a neutral chain start without seeded development admins while avoiding empty-recipient reward distribution failures.

### 2026-04-30 - core: let null-owned groups bootstrap from members

Made Qortium's seeded development and minting groups public so new accounts can join without preloaded privileged accounts, and changed null-owned group approval so members can approve group actions only while there are no usable non-null admins. Once a real admin exists, approval authority returns to the admin set, keeping the bootstrap path open without making public membership permanently equivalent to admin control.

### 2026-04-30 - core: keep reward orphaning on the active repository

Changed block reward recipient lookup to use the same repository connection that is currently processing or orphaning the block. This prevents reward orphaning from reading stale group-admin state while approved group-admin changes are being unwound, so tests that add or remove development admins can return cleanly to the genesis balance snapshot.

### 2026-04-30 - core: seed neutral development and minting groups

Updated Qortium's main genesis configuration to replace inherited Qortal reward-share, join, admin, and group-update seed entries with two neutral null-account-owned groups: `development` for dev approval and `minting` for minting eligibility. The native asset issue remains unchanged for now, while the active minting group configuration now points to the new minting group.

### 2026-04-30 - build: install patched HSQLDB before tests

Moved the local patched HSQLDB jar installation into Maven's `validate` phase so fresh workspaces install the repository's `Sealed: false` HSQLDB artifact before compile and test classpaths are built. This prevents DB-backed tests from loading the sealed Central jar and failing when Qortium's local `org.hsqldb.jdbc` classes are present.

### 2026-04-30 - core: configure auto-update dev groups

Changed Qortium so auto-update approval no longer depends on a hardcoded dev-group ID in code. The chain config now exposes `devGroupIds` using the same height-based multi-group structure as minting groups, with the current baseline still set to group `1` for compatibility while the larger genesis-governance replacement is designed. Auto-updates remain disabled by default, so operators must opt in before this configured update-discovery path is used.

### 2026-04-30 - docs: audit remaining hardcoded chain parameters

Recorded a focused audit of the remaining Qortal-specific chain parameters and branding assumptions still present in the Qortium baseline, including the main genesis governance entries, native asset naming, runtime ports and paths, build and package identity, test fixtures, Q-Apps naming, and cross-chain ACCT coupling. This gives the next cleanup work a tracked priority order before the project starts extracting those assumptions into clearer fork-facing configuration.

### 2026-04-25 - core: remove unused legacy transaction type placeholders

Removed the unused `AIRDROP` and `ENABLE_FORGING` transaction type placeholders from Qortium's active transaction enum. These legacy IDs no longer had transaction classes, data models, transformers, repository tables, API endpoints, or working validation paths, so removing the enum entries makes fresh chains treat those old numeric IDs as unsupported instead of exposing incomplete transaction types inherited from Qora-era designs.

### 2026-04-25 - core: remove legacy v1 ACCT support

Removed the old v1 cross-chain trade contracts for Bitcoin, Litecoin, and Dogecoin so Qortium's baseline only exposes the current v3 ACCT flow for those coins. This trims inherited compatibility code that a fresh Qortium chain does not need, removes legacy v1 trade-bot and API entry points, and keeps the remaining tests focused on the active cross-chain contract generation path before a later generic ACCT design is introduced.

### 2026-04-25 - core: remove genesis account-level seeding

Removed Qortal's genesis-only `ACCOUNT_LEVEL` transaction type and deleted the pre-leveled account entries from Qortium's main, testnet, and test-chain genesis configs. New chains now start accounts at level zero and rely on configured minting-group membership plus natural `blocksMinted` progression for level growth, while the runtime account-level system and reward bins remain available after accounts mint enough blocks.

### 2026-04-25 - core: allow group-member level-zero minting

Changed Qortium so a level-zero account that belongs to a configured minting group can create a reward-share and mint valid blocks, while block timing and chain-weight calculations use a minimum minting weight of one to avoid divide-by-zero behavior without pretending the account has gained a higher level. The baseline configuration now permits level-zero minting and reward-share creation, and online-account level reporting ignores unknown or ineligible reward-share keys instead of treating them as level zero.

### 2026-04-24 - core: use baseline fee and reward-share rules from genesis

Changed Qortium so the inherited historical schedules for standard transaction fees, name registration fees, and reward-share account limits are collapsed to Qortium's baseline values from timestamp zero, mirrored the same baseline into test chain configs, and updated the related tests so new chains start directly with a 0.01 QORT transaction fee, a 1.25 QORT name fee, and six reward shares per minting account, while reward-share recipients receive only their QORT payout percentage instead of also gaining `blocksMinted` progress or account-level increases from the minter's online reward-share.

### 2026-04-24 - core: remove stale Qora legacy remnants

Changed Qortium so the remaining legacy Qora wording and dead compatibility hooks are removed from the active baseline, including the obsolete broken-MD160 address mode, the final QORA migration table cleanup statement, and old test logging categories that still pointed at `org.qora` package names, leaving new chains with the current address format and cleaner fork-oriented defaults.

### 2026-04-24 - core: remove legacy QORA migration rewards

Changed Qortium so new chains no longer carry Qortal's legacy QORA holder reward migration system, removed the special QORA marker assets and imported QORA balances from the main and test chain configs, removed the reward-distribution code and repository state that tracked QORT-from-QORA payouts, and updated reward tests to use the normal account-level and admin reward flow without a QORA carryover bucket.

### 2026-04-24 - core: remove Qortal historical block fixes

Changed Qortium so fresh chains no longer apply Qortal's old hardcoded balance and name repair patches at inherited block heights, removed the historical patch classes and balance-delta files, removed the Qortal-specific checkpoint from the main chain config, and made checkpoint handling safe for fork configs that do not define checkpoints so Qortium is no longer tied to Qortal mainnet repair history.

### 2026-04-24 - core: allow arbitrary fee or mempow from genesis

Changed Qortium so ARBITRARY and QDN publish transactions can use either a normal sufficient fee or a zero-fee MemoryPoW nonce from genesis, removed the inherited optional-fee rollout trigger, and kept the broader MemoryPoW transaction timestamp untouched for the remaining message and publicize transaction rules.

### 2026-04-24 - core: remove account flags

Changed Qortium so the inherited account flag system is removed after founder privileges stopped using it, including the genesis-only flag transaction, the stored account flag field, flag transfer behavior, and the remaining no-op genesis entries, leaving account levels, group membership, and explicit chain records as the active ways to represent account status.

### 2026-04-24 - core: remove founder seed data

Changed Qortium so the inherited founder flag seed data is no longer included in the main chain, testnet, or mirrored test-chain genesis configs, updated the testnet setup notes to use reward shares and explicit account levels instead of founder flags, and renamed the remaining founder-focused reward fixture so it now describes the admin replacement reward behavior that is actually being tested.

### 2026-04-24 - core: remove founder privilege behavior

Changed Qortium so founder flags no longer grant a special effective minting level or a separate reward-share limit, leaving account level and the normal timestamp-based reward-share cap as the only active rules for those paths, and removed the inherited founder-only config fields from the main chain, testnet, and mirrored test-chain configs while updating tests to rely on explicit account levels instead of founder status.

### 2026-04-24 - core: use admin reward replacement from genesis

Changed Qortium so the reward path that replaced founder leftover rewards with the current minter-admin and dev-admin replacement model now applies from genesis, removed the inherited activation height from the main chain and mirrored test chain configs, and updated reward tests so founder flags no longer create a special block reward bucket while the existing admin split remains unchanged for now.

### 2026-04-20 - core: use V3 online accounts PoW from genesis

Changed Qortium so the online-accounts proof-of-work and timestamp cadence no longer preserve the inherited rollout from older settings to harder proof-of-work and then later lower-cost proof-of-work, collapsed that logic to the latest 10-minute online-account window and low-cost V3 MemoryPoW difficulty from genesis, removed the old timestamps from the main chain and mirrored test chain configs, and updated the online-accounts test to validate the new single baseline instead of forcing the obsolete pre-transition modulus path.

### 2026-04-20 - core: use minting group rules from genesis

Changed Qortium so minting-group membership is the baseline minting rule from genesis, removed the inherited temporary name-check rollout and later group-check activation triggers, updated block processing and online-account validation to use the minting-group path directly, and adjusted the test-chain genesis data so the default fork baseline includes a dedicated minting group from block 1.

### 2026-04-20 - core: remove ignoreLevelForRewardShareHeight trigger

Changed Qortium so minting level no longer gates ordinary minting or reward-share creation, removed the later trigger that had switched the chain onto that model, and kept the remaining name and minting-group rules as the live restrictions instead so the fork now treats group-based minting eligibility as the long-term baseline instead of preserving a later level-ignoring transition height.

### 2026-04-20 - core: remove onlineAccountMinterLevelValidationHeight trigger

Changed Qortium so the inherited online-account rule that rejects level-zero reward-share minters now applies from genesis until the later point where reward-share level is deliberately ignored again, and removed the separate activation height from the main chain and mirrored test chain configs so online-account block building and validation no longer preserve that extra historical branch.

### 2026-04-20 - core: remove transfer privs disable window

Changed Qortium so the temporary transfer-privileges freeze tied to the old penalty-fix intervention window is gone, removed the related start and end timestamps from the main chain and mirrored test chain configs, and left ordinary transfer-privs validation to follow the remaining stable rules instead of a historical February 2024 shutdown period.

### 2026-04-20 - core: remove rewardshare disable window

Changed Qortium so the temporary reward-share shutdown window is gone, removed the related disable and re-enable heights from the main chain and mirrored test chain configs, and left reward-share creation and cancellation to follow the remaining long-term validation rules instead of a historical block-height pause that had been inserted during later intervention work.

### 2026-04-20 - core: remove unconfirmableRewardSharesHeight trigger

Changed Qortium so reward-share transactions and transferred-privilege transactions are now blocked during the batch reward online-account capture and distribution window whenever batch rewards are active, instead of waiting for a separate later trigger height, and removed the related trigger from the main chain and mirrored test chain configs so that protection now follows the batch reward system directly.

### 2026-04-20 - core: remove rewardShareLimitTimestamp trigger

Changed Qortium so the extra inherited rule that reserved one reward-share slot for a self-share after a later timestamp is gone, leaving only the ordinary maximum reward-share cap in place for non-founder accounts, and removed the related trigger from the main chain and mirrored test chain configs so reward-share creation no longer depends on that additional policy cutoff.

### 2026-04-20 - core: use V2 reward shares from genesis

Changed Qortium so the fork no longer carries the old two-stage minting reward schedule, collapsed the account-level share table to the later V2 percentages from genesis, fixed the legacy QORA-holder reward share to its reduced 1% baseline from genesis too so the reward pool stays internally consistent, removed the old transition config from the main chain and mirrored test chain configs, and updated the reward tests to match the new single baseline instead of orphaning across the inherited activation boundary.

### 2026-04-20 - tests: fix batch reward minted-block assertions

Changed the batch reward test so it now measures Alice's minted-block count relative to the seeded starting value provided by the test chain's genesis `ACCOUNT_LEVEL` transactions instead of assuming pre-seeded accounts always start from zero minted blocks, which keeps the test aligned with the earlier `blocksMinted` seeding cleanup.

### 2026-04-20 - core: remove blocksMintedAdjustment state

Changed Qortium so the old split between real minted blocks and synthetic minted-block adjustments is gone, folded that synthetic progress into ordinary `blocksMinted` for genesis account-level bootstrapping and transferred privileges, removed the adjustment field from account state, lite-node account messages, sponsorship and mintership reports, and transfer-privs history, and removed the related trigger from the main chain config and mirrored test chain config so account level recalculation now always uses one consistent minted-block total.

### 2026-04-20 - core: remove fixBatchRewardHeight trigger

Changed Qortium so the later batch-reward compatibility gate is gone and the batch reward, share-bin, and minted-block accounting paths now follow the existing minting-group membership rules directly instead of preserving a separate later activation height, and removed the related trigger from the main chain config and mirrored test chain configs so the batch reward system no longer carries that extra historical branch.

### 2026-04-20 - core: remove shareBinFix trigger

Changed Qortium so minting reward share bins always use the corrected level-to-bin lookup from genesis instead of preserving the older off-by-one behavior that shifted even-numbered levels into the next reward bin until a later trigger height, and removed the related trigger from the main chain config and mirrored test chain configs so reward distribution no longer depends on that inherited compatibility branch.

### 2026-04-20 - data: remove blocksMintedPenalty state

Changed Qortium so the old blocks-minted penalty field and its remaining plumbing are fully gone, removed the stored account column, the lite-node account message field, the penalty-specific API endpoints and models, the dead penalty values from sponsorship and mintership reports, and the last unused penalty-fix config and test-fixture leftovers, which completes the cleanup after the earlier behavior-only removal and leaves the fork without any leftover self-sponsorship penalty state.

### 2026-04-20 - core: remove blocksMintedPenalty behavior

Changed Qortium so the remaining historical penalty value no longer affects founder minting privileges, reward-share eligibility, effective minting level, transfer-privs validation, account level recalculation, or poll vote weighting, while deliberately leaving the stored penalty field and related reporting/state plumbing in place for now so behavior is removed first without yet changing the underlying data model.

### 2026-04-20 - core: remove selfSponsorshipAlgoV3Height trigger

Changed Qortium so the final historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger and the last remaining self-sponsorship snapshot plumbing from the main chain config and mirrored test chain configs, dropped the V3-only cleanup code and tests, and removed the temporary reward-share confirmation freeze tied to that inherited intervention window because a new-chain fork does not need to preserve the final retrospective sweep.

### 2026-04-20 - core: remove selfSponsorshipAlgoV2Height trigger

Changed Qortium so the second historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger and V2-only snapshot/config plumbing from the main chain config and mirrored test chain configs, dropped the V2-only cleanup code and tests, and removed the temporary V2-era freezes on asset transfers, name registration, and reward-share confirmation because a new-chain fork does not need to preserve that inherited anti-abuse intervention window.

### 2026-04-20 - core: remove selfSponsorshipAlgoV1Height trigger

Changed Qortium so the first historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger from the main chain config and mirrored test chain configs, and dropped the V1-only cleanup code and tests because a new-chain fork does not need to preserve that inherited retrospective run.

### 2026-04-20 - core: remove atValidateHeight trigger

Changed Qortium so AT deployment now depends only on structural validity instead of preserving the later upstream code-hash allowlist that restricted deployments to a curated set of approved AT programs, and removed the related trigger from the main chain config and mirrored test chain config so any structurally valid AT can be deployed from genesis.

### 2026-04-20 - core: remove adminQueryFixHeight trigger

Changed Qortium so group admin lookups always use the correct address-specific repository query instead of preserving the older upstream fallback that could return the wrong admin row for a group and corrupt cached admin references during orphaning, removed the related trigger from the main chain config and mirrored test chain configs, and tightened the group orphaning tests to verify the right admin reference is restored.

### 2026-04-20 - core: remove nullGroupMembershipHeight trigger

Changed Qortium so null-owned groups always use the approval-based membership rules instead of preserving the older upstream split where invites could bypass approval while kicks and bans were blocked until a later trigger height, and removed the related trigger from the main chain config and mirrored test chain configs so the null-owner governance model applies from genesis.

### 2026-04-20 - core: remove cancelSellNameValidationTimestamp trigger

Changed Qortium so cancel-sell-name transactions always require the target name to be actively for sale instead of preserving the older upstream exception that could let duplicate sale cancellations validate after the sale had already been removed, and removed the related trigger from the main chain config and mirrored test chain configs so the stricter rule applies from genesis.

### 2026-04-20 - core: remove chatReferenceTimestamp trigger

Changed Qortium so optional chat reply references are treated as part of the baseline CHAT transaction format instead of preserving the older upstream split between pre-reference and post-reference chat serialization, removed the related trigger from the main chain config and mirrored test chain configs, and kept the existing nullable chat-reference storage and search behavior available from genesis.

### 2026-04-20 - core: remove feeValidationFixTimestamp trigger

Changed Qortium so reward-share transactions always enforce the corrected fee-balance rule from genesis instead of preserving the older upstream exemption that let an initial self-share skip the normal “can this declared fee actually be paid” check, while keeping the existing zero-fee self-share behavior and removing the related trigger from the main chain config and mirrored test chain configs.

### 2026-04-20 - core: remove disableReferenceTimestamp trigger

Changed Qortium so transaction references are treated as a baseline fixed-size field instead of preserving the older upstream switch from strict last-reference matching to relaxed validation, removed the related trigger from the main chain config and mirrored test chain configs, and simplified transaction validation so any non-null 64-byte reference now follows the same rule from genesis.

### 2026-04-19 - core: remove transactionV5Timestamp trigger

Changed Qortium so transaction version 5 is treated as the baseline instead of preserving the older upstream switch from version 4 to version 5, removed the related trigger from the main chain config and all mirrored test chain configs, and cleaned up the arbitrary transaction and message-building paths so they now follow the baseline version rules directly.

### 2026-04-19 - core: remove calcChainWeightTimestamp trigger

Changed Qortium so chain-weight comparisons always use the same shared block range instead of preserving the older upstream rule that could keep counting extra blocks on a longer chain, and removed the related trigger from the main chain config and all mirrored test chain configs so peer sync decisions now follow the cleaner baseline behavior everywhere.

### 2026-04-19 - core: remove newBlockSigHeight trigger

Changed Qortium so block minter signatures always use the full previous block signature instead of preserving the older upstream split behavior that only used the first half of the parent signature, and removed the related trigger from the main chain config and all mirrored test chain configs so the fork starts with the stronger baseline rule everywhere.

### 2026-04-19 - core: remove atFindNextTransactionFix trigger

Changed Qortium so ATs always use the corrected “find the next transaction after this timestamp” behavior instead of preserving the old upstream off-by-one compatibility branch, and removed the related trigger from the main chain config and all mirrored test chain configs because a new-chain fork does not need to replay that historical bug.

### 2026-04-20 - core: allow multiple names from genesis

Changed Qortium so accounts can own multiple names from genesis instead of preserving the old one-name-per-account transition height, made primary-name bookkeeping active from genesis for name registration, rename, buy, and orphan flows, removed the dead `oneNamePerAccount` config flag and the old one-time primary-name migration hook, and updated chat-name lookups and naming tests to use the primary-name model as the baseline behavior.

### 2026-04-19 - network: move initial peers into settings

Changed Qortium so the initial peer list now comes from an optional
`initialPeers` array in `settings.json` instead of shipping built-in Qortal
seed nodes, which keeps first-run and bootstrap-created peer lists under the
operator’s control while still preserving the existing behavior of only seeding
peers when the local peer database is empty.

### 2026-04-19 - core: disable automatic bootstrap by default

Changed Qortium so automatic bootstrap recovery is off unless an operator
explicitly turns it on and provides bootstrap hosts in `settings.json`,
removed the built-in upstream bootstrap host defaults, made missing-host
bootstrap requests fail immediately instead of entering a broken retry path,
and kept the inherited manual/bootstrap path available only when someone has
deliberately configured it.

### 2026-04-19 - core: disable automatic updates by default

Changed Qortium so automatic updates are off unless an operator explicitly
turns them on and provides update mirror URLs in `settings.json`, which stops
the fork from shipping upstream Qortal update endpoints as defaults while still
leaving the inherited manual opt-in update flow available until a better
Qortium-specific update process is designed.

### 2026-04-19 - docs: add Qortium changelog baseline

Added the main human-readable Qortium change log, documented what Qortium is
and what the early goals of the fork are, and set the basic rule that each
future Qortium commit should add one plain-language entry that matches the git
commit message so people can follow the project history without reading code.
