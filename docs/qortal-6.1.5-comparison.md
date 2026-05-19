# Qortal 6.1.5 Upstream Comparison

This document inventories the upstream Qortal changes between the Qortal
`6.1.4` and `6.1.5` release points so Qortium can decide which work belongs in
the fork.

The inventory sections are intentionally neutral. They record what changed,
where it changed, and which review bucket each change belongs in. The triage
sections record Qortium decisions made during review so the remaining work can
be planned without revisiting settled choices.

## Compared Range

- Base branch: `qortal-6.1.4`
- Base commit: `dac844c4ea6d802f12e45dd61b42bd03b728dcf3`
- Target branch: `qortal-6.1.5`
- Target commit: `590d03622588ba4282d1aec6787fdd0c5a5a7534`
- Commits in range: 24
- Files changed: 31
- Total diff size: 2,685 insertions and 271 deletions

## Change Areas

### Chat Transaction Delegate And Chat Routing

The largest change adds `ChatTransactionDelegate`, a singleton that implements
`ChatRepository` behavior outside the normal transaction repository path. It
loads chat transaction data from the existing database and from
`qortal-backup/ChatTransactions.json`, validates incoming chat transactions,
keeps in-memory indexes by signature, reference, chat reference, group, and
involved address, tracks recent chat activity by sender, and periodically saves
validated chat data back to JSON.

Chat API reads, active-chat websockets, and chat-message websockets are moved
from direct repository reads to the delegate. Incoming network chat
transactions and `/transactions/process` chat submissions are delegated instead
of being imported into the standard unconfirmed transaction queue. The standard
transaction import path still handles non-chat transactions.

Related changes:

- `ChatRepository` gains `getAllChatData()`.
- `HSQLDBChatRepository` can load all stored chat transactions for delegate
  startup.
- `Controller` starts and stops the delegate with the node lifecycle.
- `TransactionImporter` includes delegated chat transactions when peers request
  transactions by signature.
- Old delegated chat messages are invalidated after a short time window.

### New API Endpoints And Data Models

Several read APIs are added:

- `GET /at/executables` lists executable AT details using the new
  `ATDataDisplayDetail` DTO.
- `POST /names/list` accepts a list of addresses and returns primary names for
  those addresses.
- `GET /groups/balances` lists groups with member counts and total member QORT
  balances using the new `GroupBalanceData` DTO.
- `GET /groups/topbans/{year}`, `/groups/topkicks/{year}`,
  `/groups/topjoins/{year}`, and `/groups/topleaves/{year}` list group action
  activity using the new `GroupMemberTransactionCounterData` DTO.

Repository interfaces and HSQLDB implementations are extended to support those
APIs, including primary-name lookup helpers, all-group-membership reads, group
member balance aggregation, and yearly group action counters.

### QDN And Arbitrary Data Cleanup

The QDN changes affect cache filtering, cleanup pressure, and storage-size
calculation:

- Blocked names are no longer fetched for arbitrary resource indexes.
- HSQLDB arbitrary-resource filtering now only applies followed-name and
  blocked-name suppliers when the matching Boolean wrapper is actually `true`,
  not merely non-null.
- Storage cleanup deletes larger random batches when the data directory is over
  the configured threshold.
- Some random-deletion safeguards and notifications tied to repository lookups
  are removed from the cleanup path.
- Storage-size calculation is wrapped in broader exception handling.
- File age helpers are moved into `FilesystemUtils`.

### Synchronization, Import, And Message Handling Fixes

Network and synchronization handling changes include:

- `TransactionImporter` wraps scheduled GET_TRANSACTION handling in a
  fail-safe `try` block.
- GET_TRANSACTION message batches are copied into `ArrayList` before
  truncation so `addAll`/sublist behavior stays supported.
- Block-message handling catches exceptions per requested block so one failing
  block response does not stop the remaining block responses in the same batch.
- `ChatTransactionDelegate` receives follow-up synchronization fixes after its
  initial introduction.

### Settings, Diagnostics, JSON, Keystore, And Versioning

Settings parsing now supports lines beginning with `#` as comments and removes
simple trailing commas before `}` or `]` while loading JSON settings. A thread
dump scheduler is added behind two settings, `threadDumpInterval` and
`threadDumpExpiration`; when enabled, it writes periodic thread dumps to a
`thread-dumps` directory and removes expired dump files.

Jackson Databind is added for JSON import/export support used by `JsonUtils`.
The admin keystore path changes PKCS12 loading from provider-specific
`KeyStore.getInstance("PKCS12", "BC")` to provider-neutral
`KeyStore.getInstance("PKCS12")`. The Maven project version is bumped from
`6.1.4` to `6.1.5`.

## Commit Inventory

The `Files` column is the direct changed-file count for ordinary commits and
the first-parent changed-file count for merge commits.

| Commit | Title | Area | Files | Plain-language effect |
| --- | --- | --- | ---: | --- |
| `8e5f48f5b` | no longer fetching indices published by blocked names | QDN filtering | 1 | Stops index fetching for arbitrary resources published by blocked names. |
| `f4a404f85` | the boolean wrapper was evaluated for non-null only, now the boolean value is also evaluated | QDN filtering | 1 | Fixes cache filtering so optional followed/blocked filters only apply when the Boolean value is true. |
| `0ad102d7a` | JSON import/export support | JSON utility | 2 | Adds Jackson Databind and a JSON helper for saving/loading chat transaction lists. |
| `ad04799f4` | new database queries | Repository support | 6 | Adds repository queries needed by chat delegation, primary-name lookup, and group analytics. |
| `dbf37099a` | ChatTransactionDelegate initial entry for a chat transaction storage alternative to database storage | Chat delegate | 1 | Introduces the in-memory and JSON-backed chat transaction delegate. |
| `5f351ea57` | removing chat transaction storage from the database and delegating everything to the new ChatTransactionDelegate singleton | Chat delegate | 6 | Routes chat APIs, websockets, API transaction processing, and network chat import through the delegate. |
| `9f86980c4` | fixed numerous bugs related to synchronization | Chat delegate | 1 | Adds follow-up synchronization fixes inside the delegate. |
| `2d4d233f0` | wrapped message scheduler code in a try catch block as a fail-safe, put signatures in an ArrayList to ensure the addAll operation is supported | Transaction importer | 1 | Makes scheduled transaction-request handling more defensive. |
| `2cf923549` | new api call to help monitor executable AT's | API feature | 2 | Adds an endpoint and DTO for listing executable AT details. |
| `c24af5a37` | added new methods to and moved methods to FilesystemUtils | Utility cleanup | 3 | Moves file-recency and filesystem helper methods into `FilesystemUtils`. |
| `588bd81cf` | adding thread dump scheduler as an optional feature, you must change your settings to enable it | Diagnostics | 3 | Adds optional periodic thread-dump generation and expiration settings. |
| `aa3a6ed6a` | New API to fetch a list of Primary Names based on a list of addresses | API feature | 1 | Adds a batch primary-name lookup endpoint. |
| `a0895d8e6` | Merge pull request #339 from IceBurst/6.1.4+ | Merge | 1 | Merges the initial IceBurst change group into upstream. |
| `234603551` | invalidate old chat messages | Chat delegate | 1 | Adds expiration behavior for old delegated chat messages. |
| `162d77fb5` | Allow for comments, lines starting with '#' Correct simple comma errors,] <-- Get it :) | Settings parser | 1 | Allows comment lines and simple trailing commas in settings JSON. |
| `95bf446bb` | Merge pull request #340 from IceBurst/6.1.4+ | Merge | 1 | Merges the follow-up IceBurst change group into upstream. |
| `c0b47d4fe` | added exception handling for each block message, so when an exception occurs the other messages still get processed | Block messages | 1 | Handles block-response exceptions per block request instead of stopping the whole batch. |
| `01f9e2c39` | made the QDN cleanup process more aggressive, removed dead code and unused variables | QDN cleanup | 2 | Increases random cleanup pressure and simplifies parts of the arbitrary-data cleanup path. |
| `170104b23` | Replaced KeyStore.getInstance("PKCS12", "BC") with KeyStore.getInstance("PKCS12") | Keystore | 1 | Uses provider-neutral PKCS12 keystore loading. |
| `e5e725ae5` | Merge pull request #342 from Qortal/bugfix/keystore-pkcs12 | Merge | 1 | Merges the PKCS12 keystore fix into upstream. |
| `db8b62f64` | new API calls for group analytics | API feature | 6 | Adds group balance and yearly group action analytics endpoints, DTOs, and repository queries. |
| `7e8c10948` | Merge remote-tracking branch 'origin/develop' into develop | Merge | 1 | Merges the keystore branch back into develop. |
| `0090b331b` | Merge pull request #343 from Qortal/develop | Merge | 31 | Merges the develop branch change set into the release branch. |
| `590d03622` | Bump version to 6.1.5 | Versioning | 1 | Updates the Maven project version to `6.1.5`. |

## File Inventory

| File | Status | Diff | Area | What changed |
| --- | --- | ---: | --- | --- |
| `pom.xml` | Modified | +6/-1 | Dependency and versioning | Adds Jackson Databind and bumps the project version to `6.1.5`. |
| `src/main/java/org/qortal/api/resource/AtResource.java` | Modified | +79/-0 | API feature | Adds `GET /at/executables`. |
| `src/main/java/org/qortal/api/resource/ChatResource.java` | Modified | +19/-36 | Chat delegate | Replaces repository-backed chat reads and validation with delegate calls. |
| `src/main/java/org/qortal/api/resource/GroupsResource.java` | Modified | +150/-0 | API feature | Adds group balances and yearly top ban/kick/join/leave endpoints. |
| `src/main/java/org/qortal/api/resource/NamesResource.java` | Modified | +57/-0 | API feature | Adds batch primary-name lookup by address list. |
| `src/main/java/org/qortal/api/resource/TransactionsResource.java` | Modified | +31/-3 | Chat delegate | Delegates valid chat transactions during `/transactions/process`. |
| `src/main/java/org/qortal/api/restricted/resource/AdminResource.java` | Modified | +2/-2 | Keystore | Removes the explicit Bouncy Castle provider from PKCS12 keystore loading. |
| `src/main/java/org/qortal/api/websocket/ActiveChatsWebSocket.java` | Modified | +4/-6 | Chat delegate | Reads active chats from the delegate. |
| `src/main/java/org/qortal/api/websocket/ChatMessagesWebSocket.java` | Modified | +7/-8 | Chat delegate | Reads matching chat messages from the delegate. |
| `src/main/java/org/qortal/controller/ChatTransactionDelegate.java` | Added | +1276/-0 | Chat delegate | Adds the delegated chat transaction store, indexes, validation, JSON persistence, and `ChatRepository` implementation. |
| `src/main/java/org/qortal/controller/Controller.java` | Modified | +52/-30 | Lifecycle and block messages | Starts/stops the chat delegate and thread-dump scheduler, and handles block-message exceptions per request. |
| `src/main/java/org/qortal/controller/ThreadDumpScheduler.java` | Added | +146/-0 | Diagnostics | Adds optional scheduled thread-dump file generation and cleanup. |
| `src/main/java/org/qortal/controller/TransactionImporter.java` | Modified | +51/-30 | Chat delegate and importer | Routes chat transaction messages to the delegate and hardens scheduled GET_TRANSACTION handling. |
| `src/main/java/org/qortal/controller/arbitrary/ArbitraryDataCleanupManager.java` | Modified | +31/-64 | QDN cleanup | Increases cleanup batch size, shortens over-limit wait, and simplifies random file deletion. |
| `src/main/java/org/qortal/controller/arbitrary/ArbitraryDataStorageManager.java` | Modified | +52/-42 | QDN storage | Wraps storage-size calculation and manager loop work in broader exception handling. |
| `src/main/java/org/qortal/data/at/ATDataDisplayDetail.java` | Added | +105/-0 | API DTO | Adds the response model for executable AT details. |
| `src/main/java/org/qortal/data/group/GroupBalanceData.java` | Added | +29/-0 | API DTO | Adds the response model for group member counts and balances. |
| `src/main/java/org/qortal/data/group/GroupMemberTransactionCounterData.java` | Added | +42/-0 | API DTO | Adds the response model for group action counters. |
| `src/main/java/org/qortal/data/group/PeriodicActivityData.java` | Added | +56/-0 | API DTO | Adds a reusable year/month/day activity counter model. |
| `src/main/java/org/qortal/repository/ChatRepository.java` | Modified | +1/-0 | Repository API | Adds `getAllChatData()`. |
| `src/main/java/org/qortal/repository/GroupRepository.java` | Modified | +12/-1 | Repository API | Adds group balance, membership, and yearly action counter methods. |
| `src/main/java/org/qortal/repository/NameRepository.java` | Modified | +3/-1 | Repository API | Adds all-primary-name lookup support. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBArbitraryRepository.java` | Modified | +2/-2 | QDN filtering | Fixes optional followed/blocked filter activation. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBChatRepository.java` | Modified | +60/-0 | Chat delegate | Adds loading of all stored chat transaction data. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBGroupRepository.java` | Modified | +218/-0 | API queries | Adds group member balance and yearly group action counter queries. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBNameRepository.java` | Modified | +22/-0 | API queries | Adds all-primary-name lookup support. |
| `src/main/java/org/qortal/settings/Settings.java` | Modified | +49/-19 | Settings parser and diagnostics | Adds thread-dump settings and settings JSON comment/trailing-comma cleanup. |
| `src/main/java/org/qortal/utils/ArbitraryIndexUtils.java` | Modified | +1/-1 | QDN filtering | Stops index fetching for blocked names. |
| `src/main/java/org/qortal/utils/ArbitraryTransactionUtils.java` | Modified | +3/-25 | Utility cleanup | Uses moved filesystem recency helpers and removes duplicated helper code. |
| `src/main/java/org/qortal/utils/FilesystemUtils.java` | Modified | +40/-0 | Utility cleanup | Adds file-age and deletion helpers shared by cleanup and diagnostics code. |
| `src/main/java/org/qortal/utils/JsonUtils.java` | Added | +79/-0 | JSON utility | Adds Jackson-based JSON save/load helpers for chat transaction lists. |

## Review Decisions So Far

Qortium has accepted the narrow fixes that improve compatibility or robustness
without adding Qortal-specific product surface. These include provider-neutral
PKCS12 keystore loading, QDN blocked-name index filtering, QDN Boolean filter
handling, unconfirmed transaction response hardening, per-block response
exception isolation, and batch primary-name lookup.

The batch primary-name API was adapted instead of copied exactly. Qortium uses
`POST /names/primary` and intentionally omits the upstream `/names/list` alias
because `list` is already overloaded by other user-facing concepts.

Several upstream additions are skipped or deferred because they should be
designed for Qortium instead of inherited directly: permissive settings JSON,
the thread-dump scheduler, executable AT monitoring, group analytics,
filesystem helper movement, and aggressive QDN cleanup.

The chat transaction delegate is the remaining architectural item. Qortium
wants to use much of the direction, but it needs a deeper piece-by-piece plan
covering storage, JSON persistence, API routing, websocket routing, validation,
network propagation, and cache refresh behavior before implementation begins.

Jackson JSON support and chat repository loading helpers should not be ported
for runtime chat storage. The remaining repository helpers are selected only
where a kept feature needs them: batch primary-name lookup is ported, while
global primary-name loading, global group-membership loading, and group
analytics-only helpers are skipped for now. The Qortium version bump should be
decided last.

## Chat Delegate Detailed Review

The chat delegate is being reviewed as a set of smaller behavior decisions,
because the upstream commits combine storage, validation, API routing, network
propagation, persistence, and lifecycle changes.

### Qortium Chat Plan Summary

Qortium should use the upstream chat delegate as design input, not as code to
copy directly. The Qortium version should make a dedicated transient chat store
the source of truth for runtime chat, while keeping memory use limited to
bounded, rebuildable hot caches.

Do not use the upstream singleton `ChatTransactionDelegate` as the authoritative
store, do not use periodic JSON files for runtime chat persistence, and do not
use full-table name, group, or balance caches as validation authority. Local
API chat submission should validate and store synchronously. Peer-originated
chat can use a bounded asynchronous ingress path if needed.

REST chat APIs, chat websockets, and peer transaction lookup should all read
from the dedicated chat store or a service backed by it. Bounded hot caches can
accelerate recent signatures, active chats, and rate-limit checks, but cache
misses and rebuilds should fall back to the store.

Private group chat should be designed as Core-managed privacy, not only as a
client convention. Closed groups currently control joining and approval, but
Core does not make their chat private by default. Current Qortal Hub behavior
is useful reference material: Hub encrypts private-group chat payloads before
submission and publishes group key material through QDN `DOCUMENT_PRIVATE`
resources such as `symmetric-qchat-group-{groupId}`. Qortium should not import
that client implementation directly or treat QDN key publishes as the selected
Core design yet. Instead, the chat-store foundation should preserve ciphertext
and metadata cleanly so a later Core encryption phase can define the envelope,
key distribution, rotation, and plaintext rejection policy deliberately.

### Implementation Phases

1. Add the chat retention setting, dedicated chat-store schema, repository
   methods, and service boundary. Store chat data as opaque bytes and preserve
   encryption-related metadata without assuming message contents are readable.
2. Build one Qortium chat validator with duplicate checks, retention rules,
   rate-limit accounting, and current blocked-name and group validation
   behavior preserved.
3. Route `/chat`, `/chat/compute`, and `/transactions/process` through the
   Qortium chat service while preserving clear local API success and failure
   semantics.
4. Route peer-originated chat through bounded ingress, and serve peer
   transaction-signature inventory plus `GET_TRANSACTION` lookups from the chat
   store.
5. Route chat websockets and notifier delivery through the same chat-store
   service, then add lifecycle startup, cleanup, and shutdown handling for the
   selected runtime components.
6. Design and implement Core-managed private group chat encryption as a later
   phase before private group chat is considered complete. This phase should
   decide the encrypted payload envelope, key distribution, key rotation,
   recovery behavior for new members, and whether plaintext closed-group CHAT
   submissions are rejected.
7. Add concurrency and regression coverage for duplicate signatures, retention
   cleanup, rate limits, API submission, peer ingress, REST reads, websocket
   delivery, and shutdown.

### Storage Model

Qortium should not use upstream's full in-memory validated chat list as the
source of truth. The goal is still to separate chat from the normal
unconfirmed transaction store, but chat should live in a dedicated transient
chat store first. Memory should be used only for bounded acceleration, such as
latest active chats, recent sender rate limits, and short-lived
signature/reference lookups for currently advertised messages.

This avoids making recent chat volume scale directly with JVM heap, avoids
using periodic JSON backup as the main durability path, and leaves room for a
real chat database with expiry and indexes.

The chat store should treat message `data` as opaque payload bytes. It should
preserve `isEncrypted`, `isText`, `txGroupId`, `recipient`, `chatReference`,
signatures, timestamps, and sender metadata exactly enough for validation,
routing, peer lookup, REST output, and future encryption enforcement. It should
not add plaintext message indexing or decrypted-content storage as part of the
6.1.5 chat-store foundation.

### Private Group Chat Encryption Direction

Qortium should aim for closed-group chat to be private by default in Core, but
that should be a separate design phase after the dedicated chat-store and
routing foundation is in place. The first chat-store implementation should not
depend on Hub-side encryption, but it should avoid decisions that would make
Core-managed encryption difficult later.

The dedicated planning notes now live in
[`docs/private-group-chat-encryption.md`](private-group-chat-encryption.md).
That document records the current Hub reference model, the Qortium direction,
the decisions still needed before implementation, and the recommended first
implementation shape. The 6.1.5 comparison should continue to track the release
triage result, while the encryption document owns the next design phase.

### Chat Store Indexing And Hot Caches

The dedicated chat store should own the authoritative lookup paths for chat
signature, transaction reference, chat reference, group and timestamp, direct
participants, sender, and expiry. These indexes should support REST reads,
websocket initial reads, peer `GET_TRANSACTION` replies, retention cleanup, and
rate-limit accounting without depending on a full in-memory mirror of recent
chat.

Bounded hot caches are acceptable as optimizations for recent signature lookup,
latest active chats, and per-sender rate-limit timestamps. They should be
rebuildable from the chat store, pruned by the relevant retention or rate-limit
window, and never treated as the only copy of accepted chat data.

### Chat Expiry And Cleanup

Qortium should give node operators a local settings value for chat retention
instead of tying chat cleanup to the blockchain transaction expiry period once
chat is no longer stored in the normal unconfirmed transaction path.

Use a dedicated `chatMessageRetentionPeriod` setting in `settings.json`, stored
in milliseconds, with a default of `86400000` to preserve today's effective
24-hour behavior. The setting should control deletion from the dedicated chat
store first, with bounded memory caches pruned afterward. It should not change
transaction validity or consensus behavior, and it should remain separate from
`recentChatMessagesMaxAge`, which is a rate-limit window rather than a chat
history retention policy.

### JSON Backup And Restore

Qortium should not port upstream's periodic JSON backup as the main chat
persistence mechanism. Once chat has a dedicated store, that store should be
the source of truth. Runtime chat persistence should not depend on rewriting a
full `ChatTransactions.json` snapshot every few minutes or loading that file
into memory on startup.

Readable export or backup tooling can be designed later as an explicit admin
or diagnostic feature, but it should not be part of the core chat storage path.
This also means `JsonUtils` and the extra Jackson Databind dependency should
not be added solely for chat persistence unless a later accepted feature needs
them.

### Name Cache And Blocked-Name Checks

Qortium can use a primary-name cache for chat display enrichment, but it should
not use that cache as the full blocked-name validation policy. Current chat
validation checks all names owned by the sender against the local blocked-name
list, and Qortium should preserve that behavior.

If chat validation moves into a delegate, blocked-name checks should either
query the sender's owned names at validation time or use a separate cache that
tracks all owned names by owner. Primary-name-only lookup should remain a
display optimization, not a moderation rule.

### Group Membership Cache

Qortium should not port the upstream global group-membership cache as-is. Group
membership caching should be treated as an optional performance optimization,
not a change in chat validation behavior. Current validation checks that the
sender belongs to the chat group and, for group-recipient chats, that the
recipient belongs to the group too. Qortium should preserve those checks.

The first Qortium chat-store design can keep repository-backed group
membership validation or use a small on-demand cache with short expiry or clear
membership-change invalidation. Avoid a five-minute global refresh as the
primary authority, because it can be stale after joins, leaves, kicks, bans, or
other group changes, and because memory use grows with every group membership
on the chain.

### Balance Cache And Chat PoW

Qortium should not port the upstream global balance cache for chat memory-PoW
difficulty. Chat nonce validation should use the sender's current confirmed
native-asset balance, as the existing transaction validation path does, rather
than a broad five-minute cache of all non-zero balances.

If validation batching creates repeated balance lookups for the same sender,
Qortium can add small batch-local memoization inside one validation pass. Avoid
using stale balance data as the authority for PoW difficulty, because senders
near the balance threshold could otherwise be accepted or rejected differently
depending on cache timing.

### Incoming Chat Validation Queue

Qortium should not port the upstream incoming chat queue as-is. The upstream
delegate lets `/transactions/process` and peer-originated chat submissions add
chat transactions to a background queue, then returns normal processing success
before the chat has actually passed validation. Invalid queued chats can fail
later without a useful response to the submitting caller.

The first Qortium design should either validate and store local API submissions
synchronously, or use a hybrid model where local API submissions return a real
validation result while peer-originated chats use a bounded, signature-deduped
queue. Any asynchronous chat queue should have explicit capacity, duplicate
handling, and status semantics, rather than presenting a queued chat as already
processed.

### Chat Validation Logic

Qortium should not copy the upstream delegate validation method as-is. It
partially duplicates the current `ChatTransaction` and
`Transaction.isValidUnconfirmed()` path, but it also depends on upstream caches
that Qortium does not want to use as validation authority and it does not
preserve every current Qortium rule.

The Qortium chat-store implementation should use one explicit chat validator
for API submissions, peer-originated chats, and store insertion. That validator
should preserve current behavior for signature and memory-PoW checks, fresh
confirmed native-asset balance lookup, blocked-address checks, all-owned-name
blocked checks, sender and recipient group membership, recipient address
validation, data length validation, duplicate signature checks, and existing
general-chat support. It should replace only the checks that depend on the
normal unconfirmed transaction table, using chat-store duplicate checks,
pending-queue duplicate checks, chat-store-backed rate limiting, and the new
chat retention setting instead.

### Rate-Limit Accounting

Qortium should keep the existing `maxRecentChatMessagesPerAccount` and
`recentChatMessagesMaxAge` settings, but the dedicated chat store should be
the authority for accepted-message counts once chat leaves the normal
unconfirmed transaction table.

A bounded per-sender timestamp cache can be added for active senders if needed,
but it should be backed by the chat store and pruned by the same rate-limit
window. If peer-originated chat uses an asynchronous queue, pending or reserved
messages should count toward the sender's limit so a burst cannot bypass the
limit before validation completes.

### Chat API Read Routing

Qortium should keep the current chat read API surface but route reads through a
Qortium chat-store service or repository instead of the upstream singleton
delegate. The dedicated chat store should be the source of truth for
`/chat/messages`, `/chat/messages/count`, `/chat/message/{signature}`, and
`/chat/active`, with bounded hot caches added only as implementation
optimizations if active-chat or recent-message reads need them.

The upstream transaction `reference` filter can be useful, but it should be
added only with clear API documentation that distinguishes the transaction
reference field from the existing `chatreference` reply/reference field.

### Chat Build And Compute Routing

Qortium should not route `/chat` and `/chat/compute` through the upstream
singleton delegate. These endpoints should eventually use the same Qortium chat
validator and nonce helper as chat submission, with modes appropriate for
unsigned and pre-submit transactions.

The current `ChatTransaction` path can remain until the dedicated chat-store
implementation is ready, but the final design should compute chat nonce
difficulty from the sender's fresh confirmed native-asset balance and then
revalidate enough to prove the computed nonce matches the current difficulty.
The build and compute endpoints should not depend on the upstream global
balance cache or delegate validation method.

### `/transactions/process` Chat Routing

Qortium should not copy the upstream `/transactions/process` chat delegation
semantics. CHAT transactions should leave the normal unconfirmed transaction
pool, but local API submission should still return success only after the chat
has been validated and stored in the dedicated chat store.

The Qortium path should detect CHAT submissions, run the Qortium chat
validator, store accepted chats in the chat store, notify local listeners, and
return the existing API success shape only after those steps succeed.
Peer-originated chats can still use a bounded asynchronous queue if selected,
but `/transactions/process` should preserve clear accepted-or-rejected
semantics for local clients.

### Network Transaction Routing

Qortium should route peer-originated CHAT messages away from the normal
unconfirmed transaction importer, but it should send them through a bounded
Qortium chat-ingress path rather than the upstream singleton delegate. Accepted
peer chats should be validated, stored in the dedicated chat store, and then
made available by signature for peer transaction lookup.

For the 6.1.5 integration path, Qortium can keep using the existing peer
transaction-signature and `GET_TRANSACTION` messages for chat compatibility,
but those responses should be backed by chat-store lookups instead of broad
in-memory delegate maps. A separate chat inventory protocol can be considered
later if Qortium wants to stop mixing transient chat with transaction
inventory.

### Websocket Routing

Qortium chat websockets should follow the same backend decision as the REST
chat read APIs. Initial websocket reads should come from the Qortium chat-store
service or repository, not the upstream singleton delegate, so `/chat/messages`,
`/chat/active`, active-chat websockets, and chat-message websockets all use the
same source of truth.

Live websocket notifications should fire only after a chat has been validated
and stored. The accepted `ChatTransactionData` can be used as the event payload,
but conversion to websocket `ChatMessage` output should use the same
store/service enrichment path as REST reads so encoding, name enrichment, and
filtering remain consistent.

### JSON Dependency And Helper

Qortium should not port the upstream `JsonUtils` helper or add a direct
Jackson Databind dependency for runtime chat persistence. The helper exists to
support upstream's `qortal-backup/ChatTransactions.json` source-of-truth path,
which Qortium does not want to use.

Readable chat export or import can be designed later as explicit admin or
diagnostic tooling with clear failure handling and atomic writes. It should not
be part of the core chat storage path.

### Existing HSQLDB Chat Row Loading

Qortium does not need a backwards-compatibility path for existing chat rows,
because it is intended as a clean baseline for new chains rather than an
upgrade path for existing Qortal or Qortium nodes.

Do not port upstream's `ChatRepository.getAllChatData()` loader or add a chat
backfill path solely to warm delegated chat state from old `ChatTransactions`
rows. The dedicated chat store should be designed as the only runtime chat
storage model for new chains.

### Controller Lifecycle

Qortium should not start or stop the upstream `ChatTransactionDelegate`
singleton. A Qortium-specific chat service lifecycle should be added only for
runtime components that the final design actually keeps, such as a bounded peer
ingress queue, retention cleanup, optional bounded caches, or notification
wiring.

That lifecycle should start after the repository and chain services it depends
on are ready, and before API or network chat handling relies on it. Shutdown
should stop queues, cleanup jobs, and cache executors cleanly, but it should not
perform JSON backup.

### Thread-Safety Follow-Up Fixes

Qortium should not port the upstream thread-safety follow-up fixes as code
unless the upstream delegate is ported, which is not the selected direction.
Those fixes are tied to the upstream singleton's mutable in-memory maps and
schedulers.

The Qortium chat service should still apply the concurrency lessons from those
fixes. The final design should define clear ownership and locking for peer
ingress queues, pending signatures, accepted chat storage, hot signature lookup,
active-chat caches, rate-limit caches, retention cleanup, and notifier
delivery. Tests should cover concurrent peer and API submissions, duplicate
signatures, cleanup while reads are active, and websocket notification during
retention cleanup.

### Current Qortium Chat Implementation Status

Qortium's non-encryption chat-store foundation is now implemented through a
series of focused commits. The completed pieces are the dedicated transient
chat table, Qortium chat validation service, REST chat reads, local
`/transactions/process` CHAT submission, chat build and nonce-compute routing,
chat websocket reads and notifier delivery, peer-originated chat ingress, peer
chat signature inventory and `GET_TRANSACTION` lookup, and retention cleanup
lifecycle.

Additional deterministic concurrency coverage now checks duplicate local API
submission, peer-ingress deduplication, API/peer duplicate interaction,
cleanup while reads are active, notifier registration churn, and shutdown with
queued chat. Live Jetty websocket integration coverage remains optional. The
remaining chat work before calling private group chat complete is the separate
Core-managed private group encryption phase. That phase still needs decisions
for payload envelope, key distribution, rotation, recovery for new members,
and whether plaintext closed-group CHAT submissions should be rejected.

### Repository Helper Queries

Qortium should keep only the upstream repository helpers that support accepted
features. The batch primary-name lookup helper is already ported for
`POST /names/primary`.

Do not port the upstream all-primary-name lookup for the global chat name
cache, the all-group-membership lookup for the global group-membership cache,
or the chat row loader for backwards compatibility. Group analytics-only
repository helpers should remain skipped with the group analytics APIs. If a
future Qortium chat feature needs broader name or group data, it should design
bounded or event-invalidated queries for that feature instead of inheriting the
upstream full-table cache warmers.

### QDN Storage Manager Hardening

Qortium should keep the idea of making QDN storage-size checks resilient to
unexpected filesystem failures, but it should not copy the upstream broad
exception handling as-is. The useful part is preventing one directory-size
calculation error from stopping future storage checks.

A Qortium version should catch filesystem and runtime scan failures around
storage-size calculation or inside that method, log them clearly, and preserve
the existing clean `InterruptedException` shutdown behavior. This should remain
separate from the skipped aggressive QDN deletion changes.

## Triage Worksheet

| Area | Type | Qortium decision | Integration notes | Follow-up tests |
| --- | --- | --- | --- | --- |
| Chat transaction delegate and chat routing | Feature / architecture change | Implemented Qortium version / encryption deferred | Implemented with Qortium's dedicated transient chat store, validator, REST/API routing, websocket routing, peer ingress, peer inventory, retention cleanup, and deterministic concurrency coverage. Do not port the upstream singleton delegate or JSON backup path. Private group encryption remains a later Core-managed design phase. | Optional live websocket integration coverage; later encryption behavior tests. |
| AT executable monitoring API | API addition | Skip upstream / defer custom | Do not port the hardcoded Qortal endpoint. A future Qortium endpoint should be generic, registry-backed, bounded, and free of q-fund, lottery, escrow, or old per-coin ACCT assumptions. | Only needed if a custom endpoint is designed later. |
| Batch primary-name lookup API | API addition | Ported with Qortium API shape | Implemented as `POST /names/primary`. The upstream `/names/list` alias was intentionally omitted because `list` is confusing beside existing list concepts. | Valid/invalid address handling, empty list behavior, missing primary names, duplicate addresses, lite-mode rejection. |
| Group analytics APIs | API addition | Skip upstream / defer custom | Do not port the Qortal group balance and activity leaderboard endpoints. If Qortium needs analytics later, design them as optional explorer or admin tooling with clearer names, bounds, and amount handling. | Only needed if custom analytics are designed later. |
| QDN blocked-name and filter fixes | Fix | Ported | Accepted the narrow blocked-name index exclusion and Boolean-wrapper filter fixes. These match Qortium's local list-policy direction. | Blocked-name filtering, followed-only filtering, cache-enabled and SQL fallback paths. |
| QDN cleanup and storage hardening | Behavior change / robustness | Split decision | Do not port the aggressive cleanup change as-is. It greatly increases random deletion pressure and removes safety checks and notifications. Keep only a narrow QDN storage-manager hardening idea: storage-size scan failures should be logged without killing future checks, while shutdown interrupts remain clean. | Future cleanup tests should cover storage thresholds, original-copy retention, random deletion safety, notification behavior, storage-size scan failures, and shutdown interruption. |
| Filesystem helper move | Utility cleanup | Skip for now | Do not move file-age helpers only for parity. Revisit if a kept feature needs shared filesystem cleanup helpers. | Only needed if a future feature uses the shared helpers. |
| Transaction importer hardening | Fix | Ported narrow fix | Accepted the scheduler fail-safe and mutable-signature-list fix without adopting the upstream chat-delegate routing pieces yet. | GET_TRANSACTION batching, exception handling, signature-cache behavior. |
| Block-message exception isolation | Fix | Ported | Accepted as a narrow network robustness fix so one failing block response does not stop the rest of a queued response batch. | Multi-block request batches with one failing block serialization path. |
| Thread dump scheduler | Diagnostic feature | Skip upstream / defer custom | Do not port the upstream scheduler. A future Qortium diagnostics feature should choose its own settings, output path, retention policy, and API/admin integration. | Only needed if custom diagnostics are designed later. |
| Settings comments and trailing commas | Config parser behavior | Skip | Keep strict JSON parsing. Qortium should expose settings APIs for new users instead of making manual settings edits more permissive, while advanced users can use valid JSON directly. | Existing strict settings parsing tests are sufficient unless settings APIs change. |
| Provider-neutral PKCS12 keystore | Fix | Ported | Accepted as a narrow compatibility fix for restricted admin certificate behavior. | PKCS12 load path with available default providers. |
| Repository helper queries | Repository support | Partial / selected only | Primary-name batch lookup support is ported. Do not port chat-loading helpers for backwards compatibility, all-primary-name loading for the upstream name cache, all-group-membership loading for the upstream group cache, or group analytics-only helpers. | Keep tests for `POST /names/primary`; add future helper tests only for selected Qortium-specific features. |
| Jackson JSON utility | Dependency / utility | Skip for chat persistence | Do not add the upstream `JsonUtils` helper or a direct Jackson Databind dependency for runtime chat storage. Revisit only if Qortium later designs explicit admin export/import tooling. | Only needed if a future export/import feature is designed. |
| Version bump | Release metadata | Complete | Qortium now identifies as version 6.1.5 after selected upstream changes were either integrated, skipped with documented rationale, or deferred as Qortium-specific future work. | Maven version and generated package metadata. |
