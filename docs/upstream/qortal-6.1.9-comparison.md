# Qortal 6.1.9 Upstream Comparison

This document inventories the upstream Qortal changes between the Qortal
`6.1.8` and `6.1.9` release points so Qortium can decide which work belongs in
the fork.

The inventory sections are intentionally neutral. They record what changed,
where it changed, and which review bucket each change belongs in. The triage
worksheet at the end contains proposed Qortium dispositions pending owner
review.

**Headline:** 6.1.9 is a local database/QDN-service follow-up. It adds no feature
trigger, changes no chain configuration, and has no consensus or hard-fork
effect. Most HSQLDB and storage-API work is already present in Qortium, often
with Qortium-specific migration, staging-upload, retained-store, or API-security
differences.

## Compared Range

- Base branch: `v6.1.8`
- Base commit: `bb6b1bf7dbca32e90bfca8ce15f86e89335497a3` (tag `v6.1.8`)
- Target branch: `qortal/master`
- Target commit: `108bf191d42d710ec617f535af30cfd82fc03c87` (tag `v6.1.9`,
  `qortal/master`)
- Commits in range: 14 (10 non-merge)
- Files changed: 11
- Total diff size: 196 insertions and 28 deletions
- All paths are on the upstream package root `org.qortal` — porting requires
  re-rooting to `org.qortium`.

## Consensus And Activation Check

**No feature trigger was added or changed.** The range contains no
`BlockChain.java`, `blockchain.json`, test-chain configuration, transaction
validation, block serialization, peer-message, or peer-version-floor change.

`Service.java` changes local QDN validation/packaging semantics, but service IDs
remain opaque chain data and this does not create a consensus trigger or change
chain configuration.

There are no Qortal-mainnet activation heights or other Qortal-mainnet-only
values in this release to carry into Qortium. If later upstream work introduces
such values, they must never be copied into Qortium without a separate
network-wide decision.

## Change Areas

### HSQLDB cache configuration, repository URL, and diagnostics

Commits `00658cbf55f45265a1283a6162b31e3debad5948`,
`341a5e084a35a0d12683cc8eba7401927db3b86f`, and
`5ec1ce3355530a4ef4b24bdf7f5bc83515024e34`. Files:
`api/resource/AdminResource.java` (new), `controller/Controller.java`,
`data/system/StorageInfo.java` (new), and `settings/Settings.java`.

- `00658cbf5` adds `hsqldbCacheRows` and `hsqldbCacheSize` settings (defaults
  50,000 rows and 65,536 KiB), their getters, and
  `Controller.buildRepositoryUrl(String)`. The URL builder adds the cache
  parameters and initially enables `hsqldb.management.jmx=true`. It also adds
  `GET /admin/dbpool`, backed by `HSQLDBRepositoryFactory
  .getDbConnectionsStates()`, and introduces the `StorageInfo` DTO.
- `341a5e084` removes the JMX URL parameter and changes repository-URL logging
  from INFO to DEBUG.
- `5ec1ce335` makes `Controller.getRepositoryUrl()` call
  `buildRepositoryUrl(...)`; without it, the newly configurable cache parameters
  would not be used by the normal startup path.

**Classification:** configuration, diagnostics, and local API surface; not
consensus.

**Qortium relevance:** Qortium already has the effective final URL behavior in
`src/main/java/org/qortium/controller/Controller.java`: cache settings are
included, JMX is absent, and the URL is debug-logged. The settings and getters
are present in `src/main/java/org/qortium/settings/Settings.java`, and are also
registered as restart-required writable settings. This was carried in Qortium
commit `1082b1131`; Qortium therefore did not inherit the brief JMX exposure.

Qortium's `src/main/java/org/qortium/api/restricted/resource/AdminResource.java`
and `HSQLDBRepositoryFactory` already support monitored pool state internally,
but do not expose the upstream unrestricted `/admin/dbpool` endpoint. That is a
deliberate API/security shape difference and should not be overwritten by a
straight re-root.

### QDN storage-information API

Commit `249b6693ab1564cdfe70b0a9deaf07a9e907546d`. File:
`api/resource/DataResource.java`.

Adds API-key-protected `GET /data/storage/info`. The endpoint calls
`ArbitraryDataStorageManager.getTotalDirectorySize()` and
`getStorageCapacity()`, returning `StorageInfo` with `usedBytes` and nullable
`capacityBytes` (null until capacity calculation completes).

**Classification:** API surface and diagnostics; not consensus.

**Qortium relevance:** the equivalent endpoint already exists at
`src/main/java/org/qortium/api/resource/DataResource.java`, including API-key
protection and an OpenAPI schema referencing
`src/main/java/org/qortium/data/system/StorageInfo.java`. It was included in
Qortium commit `1082b1131`.

If Qortium Home's node settings/dashboard is to call this endpoint through its
QDN bridge, the Home-side QDN/app request allowlist must separately permit
`/data/storage/info`. Core having the endpoint does not itself authorize that
bridge request.

### HSQLDB arbitrary-transaction query work

Commits `1e3d4ca70b9cbdd5aceb0dc7d5f97d63b7f94c47`,
`cf737b4588f2fcec468d346cda65622a54847442`, and
`080bb9eabb983b49498c9f31b9775b9ffb24f5fb`. Files:
`repository/hsqldb/HSQLDBArbitraryRepository.java`,
`repository/hsqldb/HSQLDBDatabaseUpdates.java`, and
`repository/hsqldb/transaction/HSQLDBArbitraryTransactionRepository.java`.

- `1e3d4ca70` denormalizes `Transactions.created_when` into
  `ArbitraryTransactions.created_when`, backfills it in database update 52,
  adds `ArbitraryNameCreatedIndex (name, created_when DESC)`, and writes the
  timestamp in `HSQLDBArbitraryTransactionRepository.save()`. It also qualifies
  `created_when` across arbitrary-transaction query methods, avoiding ambiguous
  join columns and supporting the new index.
- `cf737b458` changes
  `getArbitraryTransactionSignaturesLite()` to read its six columns directly
  from `ArbitraryTransactions`, removing an unnecessary join to `Transactions`.
- `080bb9eab` rewrites the limited
  `getLatestArbitraryTransactions(Integer)` path: it first obtains the newest
  signatures through the indexed `ArbitraryTransactions` scan, then hydrates
  only those signatures through the joined query and reapplies ordering. The
  unlimited path remains a normal joined scan.

**Classification:** local performance and repository maintenance; not
consensus.

**Qortium relevance:** Qortium already has the denormalized column, backfill,
write path, index, qualified ordering, no-join lite query, and limited two-step
hydration. The comparable implementations are in:

- `src/main/java/org/qortium/repository/hsqldb/HSQLDBDatabaseUpdates.java`
- `src/main/resources/repository/hsqldb-baseline.sql`
- `src/main/java/org/qortium/repository/hsqldb/transaction/HSQLDBArbitraryTransactionRepository.java`
- `src/main/java/org/qortium/repository/hsqldb/HSQLDBArbitraryRepository.java`

Qortium commit `d71a1a2b3` implements the migration idempotently by checking
for the column and index at startup, rather than using upstream's linear schema
case 52. Its method bodies are not byte-identical: Qortium has its own
transaction-data constructor shape, guarded empty-signature handling in the
two-step query, retained-chat/store work, wildcard QDN filtering, staged-upload
behavior, archive work, and later adaptive-networking changes. The relevant
query plans are nevertheless already rewritten:

- the limited latest query uses the two-step index scan;
- the lite-signature query has no `Transactions` join;
- timestamp predicates/orderings use the denormalized
  `ArbitraryTransactions.created_when` where that is the indexed path.

Any further query port must therefore be benchmarked against Qortium's current
methods, not applied by file-level cherry-pick.

### Cleanup-manager refresh throttle

Commit `261163877cc362423797030c363a0694e2415c30`. File:
`controller/arbitrary/ArbitraryDataCleanupManager.java`.

Adds a six-hour `TRANSACTION_LIST_REFRESH_INTERVAL`. The cleanup manager records
when it fetched the full arbitrary-transaction list and refreshes it only after
a complete scan and when that interval has elapsed, instead of re-querying on
every pagination wrap.

**Classification:** local performance; not consensus.

**Qortium relevance:** the same throttle is already present in
`src/main/java/org/qortium/controller/arbitrary/ArbitraryDataCleanupManager.java`
from `d71a1a2b3`. Qortium has subsequently added startup and periodic cleanup
for staged uploads, system temporary files, and pre-broadcast `_misc` data, so
the surrounding lifecycle differs; the six-hour list-refresh condition itself
is already in place.

### Multi-file QDN service definitions

Commit `f2fc3c8f6f36e16f05276638e72fa92c7fd05dda`. File:
`arbitrary/misc/Service.java`.

Changes the `single` flag from `true` to `false` for public and private
`FILE`/`FILE_PRIVATE`, `IMAGE`/`IMAGE_PRIVATE`, and
`DOCUMENT`/`DOCUMENT_PRIVATE` services. This permits multi-file directory
packaging where the applicable service validator allows it; it does not add or
change a service ID or size limit.

**Classification:** QDN feature/validation semantics; not consensus and not a
feature trigger.

**Qortium relevance:** Qortium intentionally diverges in
`src/main/java/org/qortium/arbitrary/misc/Service.java`. Its FILE, IMAGE, and
DOCUMENT variants remain single-file, while Qortium introduced explicit
multi-file service types such as `FILES`, `IMAGE_GALLERY`, and selected
multi-file public media/document services. Its private variants remain
single-blob encrypted because `Service.validate()` enforces the encrypted
envelope through the `single` path; simply copying upstream's private-service
flips would bypass that Qortium privacy enforcement.

This is important for QDN interoperability, but it is not a chain-compatibility
change. Qortium Home rendering content fetched from Qortal through
`qortalRequest` uses Qortal-published content; Qortium Core's local service
validator is not invoked merely because Home renders it. Service-definition
drift can still affect publisher expectations, service metadata, and whether
Qortium-side tooling accepts the same directory layout, so Home must continue
to treat Qortal service metadata and multi-file resources according to Qortal's
definitions rather than infer them from Qortium Core.

### Release version

Commit `108bf191d42d710ec617f535af30cfd82fc03c87`. File: `pom.xml`.

Bumps the upstream Maven version from `6.1.8` to `6.1.9`.

**Classification:** release metadata; not consensus.

**Qortium relevance:** Qortium's `pom.xml` is independently versioned (`1.6.3`
at review time). As with the 6.1.8 comparison, Qortium does not adopt upstream
version bumps. This range also makes **no** `minPeerVersion` change: the only
version-related upstream file is `pom.xml`, so there is no Qortal peer-version
floor to port.

## Commit Inventory

| Commit | Subject | Bucket |
|---|---|---|
| `00658cbf55f45265a1283a6162b31e3debad5948` | feat: make HSQLDB connection parameters configurable | Configuration / API |
| `249b6693ab1564cdfe70b0a9deaf07a9e907546d` | feat: add /data/storage/info API endpoint | API |
| `341a5e084a35a0d12683cc8eba7401927db3b86f` | fix: remove JMX from DB URL and demote URL log to debug | Diagnostics |
| `5ec1ce3355530a4ef4b24bdf7f5bc83515024e34` | fix: update getRepositoryUrl to use buildRepositoryUrl method | Configuration |
| `1e3d4ca70b9cbdd5aceb0dc7d5f97d63b7f94c47` | feat: optimize HSQLDB arbitrary data queries | QDN / performance |
| `261163877cc362423797030c363a0694e2415c30` | perf: throttle transaction list refresh to every 6 hours in cleanup manager | QDN / performance |
| `cf737b4588f2fcec468d346cda65622a54847442` | perf: drop unnecessary JOIN in getArbitraryTransactionSignaturesLite | QDN / performance |
| `080bb9eabb983b49498c9f31b9775b9ffb24f5fb` | perf: replace LIMIT-over-JOIN with two-step index scan in getLatestArbitraryTransactions | QDN / performance |
| `f2fc3c8f6f36e16f05276638e72fa92c7fd05dda` | expanding multi-file services | QDN feature semantics |
| `108bf191d42d710ec617f535af30cfd82fc03c87` | Bump version to 6.1.9 | Release |

## File Inventory

| File | Status | Diff | Area | What changed |
|---|---|---:|---|---|
| `pom.xml` | Modified | +1/-1 | Release | Bumps upstream version to `6.1.9`. |
| `src/main/java/org/qortal/api/resource/AdminResource.java` | Added | +50/-0 | API | Adds `/admin/dbpool` pool-monitoring endpoint. |
| `src/main/java/org/qortal/api/resource/DataResource.java` | Modified | +29/-0 | API | Adds API-key-protected `/data/storage/info`. |
| `src/main/java/org/qortal/arbitrary/misc/Service.java` | Modified | +6/-6 | QDN service semantics | Expands selected FILE, IMAGE, and DOCUMENT services to multi-file. |
| `src/main/java/org/qortal/controller/Controller.java` | Modified | +11/-2 | Configuration / diagnostics | Builds cache-configured HSQLDB URL, then removes JMX and demotes URL logging. |
| `src/main/java/org/qortal/controller/arbitrary/ArbitraryDataCleanupManager.java` | Modified | +11/-3 | QDN performance | Throttles full transaction-list refreshes to six hours. |
| `src/main/java/org/qortal/data/system/StorageInfo.java` | Added | +21/-0 | API DTO | Holds storage usage and nullable capacity. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBArbitraryRepository.java` | Modified | +41/-15 | QDN performance | Adds timestamp/index query work, no-join lite lookup, and two-step limited latest lookup. |
| `src/main/java/org/qortal/repository/hsqldb/HSQLDBDatabaseUpdates.java` | Modified | +11/-0 | Database migration | Adds the denormalized timestamp column and index. |
| `src/main/java/org/qortal/repository/hsqldb/transaction/HSQLDBArbitraryTransactionRepository.java` | Modified | +2/-1 | Database write path | Stores `created_when` in arbitrary-transaction rows. |
| `src/main/java/org/qortal/settings/Settings.java` | Modified | +13/-0 | Configuration | Adds cache-row/cache-size settings and accessors. |

## Porting Notes for Qortium

1. Re-root any selected upstream source from `org.qortal` to `org.qortium`;
   do not copy Qortal API/resource placement without checking Qortium's
   restricted/public API boundary.
2. This release adds no feature trigger and no chain configuration. There is no
   Qortal mainnet height to reuse, and no consensus deployment to schedule.
3. Preserve Qortium's HSQLDB migration shape. Its idempotent schema checks and
   baseline schema differ from upstream's numbered migration sequence, and its
   arbitrary-data repository has independent staged-upload, retained-store,
   archive, filtering, and networking work.
4. Keep `/data/storage/info` API-key protected. If Home needs it, separately
   review the Node QDN app settings/dashboard request allowlist.
5. Do not copy the multi-file private-service flag changes without a dedicated
   private-directory encryption design. Qortium's current single-blob
   enforcement is intentional.
6. Do not adopt upstream `6.1.9` version metadata or infer a peer floor from
   it. Qortium versions and network compatibility remain independent.

## Triage Worksheet

**Proposed dispositions, pending owner review.** The owner records final
decisions separately; these rows are an implementation/review starting point,
not accepted Qortium policy.

| Change | Proposed disposition | Rationale |
|---|---|---|
| `00658cbf5` HSQLDB cache configuration and `/admin/dbpool` | Adapt | Qortium already carries the final cache-setting URL behavior; retain its no-JMX/debug-log form and review any pool endpoint only within Qortium's restricted API model. |
| `249b6693a` `/data/storage/info` | Adopt | Equivalent API-key-protected endpoint and DTO already exist in `1082b1131`; only a Home bridge allowlist decision could remain. |
| `341a5e084` JMX removal and debug logging | Adopt | Qortium's existing URL omits JMX and logs at DEBUG. |
| `5ec1ce335` use `buildRepositoryUrl()` | Adopt | Qortium's `getRepositoryUrl()` already calls its cache-configured builder. |
| `1e3d4ca70` arbitrary timestamp/index optimization | Adapt | Qortium already has the column, write path, index, and qualified queries, but through an idempotent migration and divergent repository methods. |
| `261163877` cleanup refresh throttle | Adopt | The six-hour refresh behavior is already present; preserve Qortium's additional staging and cleanup safeguards. |
| `cf737b458` no-join lite-signature query | Adopt | Qortium already reads lite signatures directly from `ArbitraryTransactions`. |
| `080bb9eab` two-step latest-query scan | Adapt | Qortium already uses the two-step plan with an empty-result guard and its own transaction-data shape; benchmark before changing it further. |
| `f2fc3c8f6` multi-file FILE/IMAGE/DOCUMENT services | Skip | Qortium intentionally uses typed multi-file services and single-blob private encryption; a straight port would create service-definition and privacy-enforcement drift. |
| `108bf191d` version bump | Skip | Qortium versions and peer compatibility are managed independently; upstream changed no peer-version floor. |


