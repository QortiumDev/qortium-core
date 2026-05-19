# Qortal 6.1.5 Upstream Comparison

This document inventories the upstream Qortal changes between the Qortal
`6.1.4` and `6.1.5` release points so Qortium can decide which work belongs in
the fork.

It is intentionally neutral. It records what changed, where it changed, and
which review bucket each change belongs in. It does not decide whether any
change should be kept, rewritten, postponed, or skipped for Qortium.

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

## Triage Worksheet

| Area | Type | Qortium decision | Integration notes | Follow-up tests |
| --- | --- | --- | --- | --- |
| Chat transaction delegate and chat routing | Feature / architecture change | Undecided | Review whether Qortium wants chat outside normal transaction storage, JSON persistence in `qortal-backup`, and singleton in-memory indexes. | Chat API behavior, websocket behavior, network transaction request behavior, restart persistence, invalidation timing. |
| AT executable monitoring API | API addition | Undecided | Review whether executable AT monitoring is useful for Qortium's expected node and explorer surfaces. | API response shape, repository filtering, Swagger output. |
| Batch primary-name lookup API | API addition | Undecided | Review compatibility with Qortium's name and primary-name direction. | Valid/invalid address handling, empty list behavior, missing primary names, authorization expectations. |
| Group analytics APIs | API addition | Undecided | Review whether group balance and yearly activity analytics belong in the base fork or remain Qortal-specific. | Query ordering, pagination, year boundaries, large group performance. |
| QDN blocked-name and filter fixes | Fix | Undecided | Review against Qortium's QDN and list-policy direction. | Blocked-name filtering, followed-only filtering, cache-enabled and SQL fallback paths. |
| QDN cleanup changes | Behavior change | Undecided | Review more aggressive deletion and removed repository checks before adopting. | Storage-threshold behavior, original-copy retention, random deletion safety, notification expectations. |
| Transaction importer hardening | Fix | Undecided | Review independently from chat delegation where possible. | GET_TRANSACTION batching, exception handling, signature-cache behavior. |
| Block-message exception isolation | Fix | Undecided | Review as a narrow network robustness fix. | Multi-block request batches with one failing block serialization path. |
| Thread dump scheduler | Diagnostic feature | Undecided | Review settings names, output location, retention behavior, and whether runtime diagnostics should be included by default. | Disabled-by-default behavior, interval scheduling, file cleanup, shutdown behavior. |
| Settings comments and trailing commas | Config parser behavior | Undecided | Review whether permissive settings parsing should be accepted for Qortium config files. | Comment lines, trailing commas, malformed JSON, path handling. |
| Provider-neutral PKCS12 keystore | Fix | Undecided | Review as a narrow compatibility fix for restricted admin export/import behavior. | PKCS12 load path with available default providers. |
| Jackson JSON utility | Dependency / utility | Undecided | Review only if a selected feature needs JSON import/export. | Dependency tree impact, serialization of target data type, missing/corrupt file behavior. |
| Version bump | Release metadata | Undecided | Do not directly apply to Qortium without deciding Qortium's own versioning scheme. | Maven version and generated package metadata. |
