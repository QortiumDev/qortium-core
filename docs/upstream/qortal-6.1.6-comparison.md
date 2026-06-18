# Qortal 6.1.6 Upstream Comparison

This document inventories the upstream Qortal changes between the Qortal
`6.1.5` and `6.1.6` release points so Qortium can decide which work belongs in
the fork.

The inventory sections are intentionally neutral. They record what changed,
where it changed, and which review bucket each change belongs in. The triage
worksheet at the end is for Qortium decisions made during review.

**Headline:** 6.1.6 is a security release. Its two largest changes (`fix c-01`
and `fix c-02`, from PRs #348/#349/#352 and an external audit) are
**consensus-level hard forks** gated behind new feature triggers. Everything
else is local/diagnostic/API and non-consensus.

## Compared Range

- Base branch: `qortal-6.1.5`
- Base commit: `590d03622588ba4282d1aec6787fdd0c5a5a7534` (tag `v6.1.5`)
- Target branch: `qortal-6.1.6`
- Target commit: `e83660c117d16cf2402c828d8042cb13f19a6a1a` (tag `v6.1.6`, `qortal/master`)
- Commits in range: 23 (18 non-merge)
- Files changed: 40
- Total diff size: 1,877 insertions and 141 deletions
- All paths are on the upstream package root `org.qortal` — porting requires
  re-rooting to `org.qortium`.

## Activation Heights (read this first)

Two new feature triggers were added. Both default to `9999999999999`
(effectively disabled) in `BlockChain.java`, and both are set to Qortal
**mainnet** height `2618180` in `src/main/resources/blockchain.json`.

| Trigger | `BlockChain.java` default | `blockchain.json` (Qortal mainnet) | `test-chain-v2.json` |
|---|---|---|---|
| `onlineAccountsSignatureV2Height` | `9999999999999` | `2618180` | `9999999999999` (off) |
| `assetOrderBoundsHeight` | `9999999999999` | `2618180` | `10` |

Notes:
- The online-accounts trigger was renamed mid-development from
  `onlineAccountsSignatureV2Timestamp` to `...V2Height` (commit `4facb4906`
  "switch to height") — it is **block-height based**, not timestamp based.
- Qortal's own `testnet/testchain.json` got **no** trigger entry (only a
  trailing-newline fix). Activation in tests is driven by `test-chain-v2.json`
  and the dedicated `OnlineAccountsTests` fixtures.
- **`2618180` is a Qortal mainnet height and must not be copied into Qortium.**
  Qortium must pick its own activation heights (and coordinate a network-wide
  rollout) for each fork it adopts.

## Change Areas

### Consensus / hard-fork: online-accounts signature scheme (`fix c-01`)

Commits `f19ccfc43` (fix c-01), `8cf01569b` (switch place of feature trigger),
`4facb4906` (switch to height). Files: `block/Block.java`,
`block/BlockChain.java`, `controller/OnlineAccountsManager.java`,
`crypto/Qortal25519Extras.java`, `data/block/BlockData.java`,
`src/main/resources/blockchain.json`, plus many `test-chain-v2-*.json`
fixtures and new tests (`SchnorrTests.java`, `network/OnlineAccountsTests.java`).

**The vulnerability.** The online-accounts path used a custom aggregate
signature scheme (`Qortal25519Extras.signForAggregation` /
`verifyAggregated`) in which the challenge was **independent of the nonce
point `R`**. That admits a universal forgery: an attacker could forge online
accounts signatures. Online accounts drive block minting eligibility and reward
distribution, so this is consensus-critical.

**The fix (V2 scheme, height ≥ `onlineAccountsSignatureV2Height`).** Switch the
online-accounts path to standard per-account Ed25519 signatures where the
challenge is bound to `R` and the public key `A` (`k = H(R || A || message)`):

- `Qortal25519Extras.sign()` / `verify()` — new standard Ed25519 sign/verify
  (uses the same 32-byte seed, so public keys are unchanged). `newPointAffine()`
  added for test support. The legacy `signForAggregation`/`verifyAggregated`
  remain for pre-fork verification.
- `Block.java` block creation: after the trigger, each online account's
  signature is stored **individually** (per-account Ed25519), in account-index
  order, via `BlockTransformer.encodeTimestampSignatures(...)`. Before the
  trigger, the single legacy aggregate signature is still produced.
- `Block.java` block validation (`areOnlineAccountsValid`): after the trigger,
  the signatures field length must equal `N × SIGNATURE_LENGTH + N × INT_LENGTH`
  (N = online reward-shares); each signature is verified individually against
  its own reward-share public key. Before the trigger, the legacy single
  aggregate verification path is used.
- **Block serialization changes** at the fork boundary: the online-accounts
  signatures field grows from 1 signature (64 bytes) to N signatures
  (N × 64 bytes), followed by N nonces as before. `BlockData
  .getOnlineAccountsSignaturesCount()` no longer hard-returns `1` — it derives
  the count from the actual byte length (with overflow/malformed guards), so it
  works for both schemes.
- `OnlineAccountsManager.java`: all sign/verify decisions are now made against
  `nextBlockHeight = currentBlockchainHeight + 1` (online accounts are prepared
  for the next block). A new `verifiedOnlineAccountSignatures` cache (keyed by
  online-account timestamp → set of exact `(publicKey, signature)` pairs) avoids
  re-verifying, bounded by `MAX_CACHED_SIGNATURE_TIMESTAMP_SETS` with explicit
  trim/expiry. Because `OnlineAccountData` equality deliberately ignores the
  signature bytes, the manager carefully removes stale legacy/V2 variants from
  the current set and import queue at the fork boundary (where the same
  timestamp/pubkey/nonce can carry either signature). New
  `getOnlineAccounts(timestamp, blockHeight)` filters cached entries to those
  valid for the height's scheme so legacy entries cannot leak into V2 blocks.
  Single-node-testnet fallback minting in `Block.java` also respects the active
  scheme.

**Replay safety.** Before the activation height, behaviour is byte-for-byte the
legacy aggregate scheme, so historic blocks replay identically.

### Consensus / hard-fork: asset-order integer bounds (`fix c-02`)

Commit `b975f0c4e` (PR #352). Files: `asset/Order.java`,
`transaction/CreateAssetOrderTransaction.java`, `utils/Amounts.java`,
`block/BlockChain.java`, `src/main/resources/blockchain.json`, test
`CreateAssetOrderValidationTests.java`, `test-chain-v2.json`.

**The vulnerability.** Asset-order committed-cost / trade math computed a
`BigInteger` then called `.longValue()`, which silently truncates / wraps a
value exceeding signed-long range. On a credit/refund path a wrap to a negative
long effectively **mints value** (the order commitment/refund is applied to a
balance). Large amount/price inputs could trigger this.

**The fix (height ≥ `assetOrderBoundsHeight`).**

- `CreateAssetOrderTransaction.isValid()`: reject orders whose `amount` or
  `price` exceeds `Asset.MAX_QUANTITY` (`INVALID_AMOUNT`); require the rounded
  committed cost and max-other-amount to fit in a **positive** signed long
  (`toPositiveLongOrNull`, else `INVALID_AMOUNT`); add a QORT `fee + cost`
  overflow guard (`NO_BALANCE`). Uses a rounded committed-cost for the
  non-divisible-asset integer check to match processing.
- `Order.java`: `calcHaveAssetCommittment` and the match path now narrow via
  `narrowToLong(value, boundsCheckActive)` which **throws** (fails closed) when
  the value does not fit in 63 bits, rather than silently wrapping negative. The
  bounds check is keyed off the block height being applied (tip+1 when
  processing, tip when orphaning) vs `getAssetOrderBoundsHeight()`.
- `Amounts.java`: new `roundUpScaled` / `roundDownScaled` returning `BigInteger`
  (the existing `*Multiply` longs delegate to them), so truncation is deferred
  to an explicit, checked narrowing.

**Replay safety.** Before the activation height the historic wrapping behaviour
is preserved, so existing blocks replay identically.

### Non-consensus: chat analysis support + API (`eb70a4f1c`)

Files: `api/resource/ChatResource.java`,
`controller/ChatTransactionDelegate.java`, `data/chat/ChatMemoryInfo.java`
(new), `data/chat/ChatStat.java` (new), `data/chat/GroupChatStat.java` (new),
`utils/ChatAnalysisUtils.java` (new).

- New read endpoints: `GET /chat/memory` (JVM memory + estimated chat-store
  bytes → `ChatMemoryInfo`), `GET /chat/stats` (top senders by estimated size →
  `List<ChatStat>`), `GET /chat/groupstats` (top groups → `List<GroupChatStat>`).
- `ChatAnalysisUtils` does in-memory analytics over the delegate's validated
  chat list (group by sender / group id, heuristic byte-size estimate).
- `ChatTransactionDelegate.isValid(...)` gains a `boolean signed` parameter:
  network-ingest still requires a valid signature (`isValid(chat, true, true)`),
  API build/process paths skip the signature check for not-yet-signed messages
  (`isValid(..., false, false)`). Public `getPrimaryNameByOwner()` added.
- **Not consensus-affecting:** this is the *delegate's* local validity check for
  the in-memory chat store; CHAT transactions are ephemeral in this model and
  are not mined into blocks.
- **Qortium porting caveat:** upstream's chat lives in the
  `ChatTransactionDelegate` in-memory model; **Qortium's chat lives in
  `ChatStoreRepository`** (`ChatRepository` is dead legacy — see project memory
  on chat repo duality). Any port of these analytics must target the store repo,
  not the upstream delegate.
- Minor nits: `/chat/groupstats` Swagger `@Schema` mislabels `ChatStat`;
  `offset`/`reverse` params accepted but unused; each call snapshots the full
  validated-chat list (O(n), unbounded, no caching) — polling could cause GC/lock
  pressure.

### Non-consensus: QDN folder-scanning optimization (`727918108`)

Files: `arbitrary/ArbitraryDataFile.java`,
`arbitrary/ArbitraryDataFolderSizeEstimator.java` (new),
`arbitrary/LongFileHandler.java` (new),
`controller/arbitrary/ArbitraryDataCleanupManager.java`,
`controller/arbitrary/ArbitraryDataStorageManager.java`, `settings/Settings.java`,
`utils/FilesystemUtils.java`, test `ArbitraryDataStorageCapacityTests.java`.

- Replaces the full recursive `FileUtils.sizeOfDirectory` walk (run every 10 min)
  with an in-memory `AtomicLong` running total (`ArbitraryDataFolderSizeEstimator`)
  mutated incrementally on file write/delete/cleanup, persisted across restarts
  via `LongFileHandler` to `qortal-backup/ArbitraryDataFolderSizeEstimate.dat`.
- A scheduled full recalc (configurable hour/frequency) corrects estimator drift.
- New settings: `dataStorageSizeCalculationHour` (default `23`),
  `dataStorageSizeCalculationFrequency` (default `1` day).
- **Not consensus-affecting** (storage bookkeeping only).
- Porting flags: hard-coded `qortal-backup/...` path must become the Qortium
  backup dir; the pre-existing temp-dir double-counting bug
  (`calculateDirectorySize` adds the data dir twice) is carried forward
  unchanged; two new files lack a trailing newline; estimator only tracks the
  three instrumented call sites (out-of-band changes drift until recalc).

### Non-consensus: search-resources `default=true` fix (`3670e8a93`, PR #347)

Files: `api/resource/ArbitraryResource.java`,
`repository/hsqldb/HSQLDBArbitraryRepository.java`,
`repository/hsqldb/HSQLDBCacheUtils.java`, test `HSQLDBCacheUtilsTests.java`.

- Bug: when `default=true` was requested without a `query`, results were not
  restricted to default resources (identified resources leaked into a
  default-only search).
- Fix: empty `identifier` treated as `null`; the contradictory combo
  `default=true` + non-null `identifier` now rejected with `INVALID_CRITERIA`;
  the default filter is applied unconditionally; query branch reduced to
  name-only matching.
- **Response-affecting (not consensus):** default searches now correctly exclude
  identified resources (results shrink for callers relying on the old leak), and
  the new `INVALID_CRITERIA` rejection is a hard break for callers passing both
  params. Note a subtle backend divergence: the SQL path treats "default" as
  `identifier = 'default'`, the cache path as `NULL OR 'default'`.

### Non-consensus: DB-cache start ordering (`4c5fbf93e`, PR #349)

File: `controller/Controller.java`. Moves the `HSQLDBDataCacheManager`
construction/`start()` to just before the API service starts, so the cache is
populated before the API serves reads. Pure relocation; no logic change.

### Non-consensus: smaller items

- `formatBytes` helper (`31f9565e0`, `eea354d76`, `6e460d5ff`): new
  `StringUtils.formatBytes(long)` human-readable formatter, applied to storage
  and `BlockArchiveWriter` log lines. Log/cosmetic only.
- Trade-bot backup fix (`b95ea2377`): `TradeBotUtils` moved
  `backupTradeBotData(...)` out of the per-entry loop and now backs up the whole
  batch, fixing incomplete/inconsistent backups. Local persistence only.
- Thread-dump diagnostics (`ff49f939e`, `787c35149`): `ThreadDumpScheduler` now
  logs per-thread CPU% (delta since last dump, sorted heaviest-first) and
  available processor count. Diagnostic logging only.
- README (`ffc60ccd0`): documentation rewording; Qortal-branded, would need
  Qortium rewording if ported.
- devnet settings then "set back" (`f7c1be2d4`, `6051dcfb4`): **net source
  effect on `Network.java`/`NetworkData.java` is a no-op** — `NetworkData.java`
  fully reverted; `Network.java` retains only a cosmetic re-indentation of the
  `MAINNET_MESSAGE_MAGIC` line. The real change riding in these commits is the
  `blockchain.json` activation heights (`2618180`), covered above.
- Version bump to `6.1.6` (`e83660c11`, `pom.xml`).

## Commit Inventory

| Commit | Subject | Bucket |
|---|---|---|
| `ffc60ccd0` | Revise README.md | Docs |
| `ff49f939e` | Thread dump CPU% per thread | Diagnostics |
| `eb70a4f1c` | Chat analysis support + API + unsigned-chat validation | API / non-consensus |
| `b95ea2377` | Trade bot state backup inconsistency fix | Local |
| `727918108` | Arbitrary data folder scanning optimizations | QDN / perf |
| `787c35149` | Thread dump available processors | Diagnostics |
| `3670e8a93` | Fix `default=true` in search resources (PR #347) | API |
| `31f9565e0` | Add `StringUtils.formatBytes` | Cosmetic |
| `eea354d76` | Use `StringUtils.formatBytes` | Cosmetic |
| `6e460d5ff` | Format | Cosmetic |
| `4c5fbf93e` | Start DB cache right before API (PR #349) | Startup |
| `b975f0c4e` | **fix c-02** (asset-order bounds, PR #352) | **Consensus / hard fork** |
| `f19ccfc43` | **fix c-01** (online-accounts signature V2) | **Consensus / hard fork** |
| `8cf01569b` | Switch place of feature trigger | **Consensus** (c-01 refinement) |
| `f7c1be2d4` | devnet settings | Network (reverted) |
| `4facb4906` | Switch to height (online-accounts trigger) | **Consensus** (c-01 refinement) |
| `6051dcfb4` | set back | Network (revert) + activation heights |
| `e83660c11` | Bump version to 6.1.6 | Release |
| (merges) | #347, #348, #349, develop, #352 | — |

## File Inventory (40 files)

Consensus-critical source:
- `src/main/java/org/qortal/block/Block.java`
- `src/main/java/org/qortal/block/BlockChain.java`
- `src/main/java/org/qortal/controller/OnlineAccountsManager.java`
- `src/main/java/org/qortal/crypto/Qortal25519Extras.java`
- `src/main/java/org/qortal/data/block/BlockData.java`
- `src/main/java/org/qortal/asset/Order.java`
- `src/main/java/org/qortal/transaction/CreateAssetOrderTransaction.java`
- `src/main/java/org/qortal/utils/Amounts.java`
- `src/main/resources/blockchain.json`

Non-consensus source: `api/resource/ChatResource.java`,
`api/resource/ArbitraryResource.java`, `controller/ChatTransactionDelegate.java`,
`controller/Controller.java`, `controller/ThreadDumpScheduler.java`,
`controller/arbitrary/ArbitraryDataCleanupManager.java`,
`controller/arbitrary/ArbitraryDataStorageManager.java`,
`controller/tradebot/TradeBotUtils.java`, `arbitrary/ArbitraryDataFile.java`,
`arbitrary/ArbitraryDataFolderSizeEstimator.java` (new),
`arbitrary/LongFileHandler.java` (new), `data/chat/ChatMemoryInfo.java` (new),
`data/chat/ChatStat.java` (new), `data/chat/GroupChatStat.java` (new),
`network/Network.java`, `repository/BlockArchiveWriter.java`,
`repository/hsqldb/HSQLDBArbitraryRepository.java`,
`repository/hsqldb/HSQLDBCacheUtils.java`, `settings/Settings.java`,
`utils/ChatAnalysisUtils.java` (new), `utils/FilesystemUtils.java`,
`utils/StringUtils.java`, `pom.xml`, `README.md`.

Tests added/changed: `test/SchnorrTests.java` (new),
`test/network/OnlineAccountsTests.java` (new, 343 lines),
`test/assets/CreateAssetOrderValidationTests.java` (new, 169 lines),
`test/arbitrary/ArbitraryDataStorageCapacityTests.java`,
`test/repository/HSQLDBCacheUtilsTests.java`.

Config/fixtures: `src/main/resources/blockchain.json`, `testnet/testchain.json`
(newline only), and ~18 `src/test/resources/test-chain-v2*.json` (feature-trigger
entries added).

## Porting Notes for Qortium

1. **Re-root packages** `org.qortal` → `org.qortium` and fix imports for every
   new/edited file.
2. **Choose Qortium-specific activation heights** for
   `onlineAccountsSignatureV2Height` and `assetOrderBoundsHeight`. Do **not**
   copy Qortal's `2618180`. Each is a coordinated hard fork — both change how
   blocks validate/serialize at the activation height, so the whole Qortium
   network must upgrade and agree on the height before it passes.
3. **The two consensus fixes are the priority.** They close real
   value-minting / forgery vulnerabilities. Evaluate whether Qortium's chain is
   exposed (it shares the same asset-order math and online-accounts scheme) and
   schedule activation accordingly.
4. **Chat analytics port** must target Qortium's `ChatStoreRepository`, not the
   upstream `ChatTransactionDelegate` model.
5. **Hard-coded paths / settings:** rename `qortal-backup/...` to the Qortium
   backup dir; carry the two new `Settings` keys; verify `Asset.MAX_QUANTITY`,
   `Transformer.*_LENGTH`, and `BlockTransformer.encodeTimestampSignatures`
   exist in Qortium (they are pre-existing upstream and present at
   `src/main/java/org/qortium/transform/block/BlockTransformer.java`).
6. **Pre-existing bug carried forward:** temp-dir size double-counting in
   `calculateDirectorySize()` — fix while porting rather than inherit it.
7. The devnet/network commits are a net no-op for source; do not port the
   cosmetic re-indentation.

## Triage Worksheet

Decisions recorded during the 2026-06-17 review. Both consensus fixes were ported behind
feature-trigger heights that default to a disabled sentinel (fail-closed). Activation heights
were chosen on 2026-06-17: **Previewnet = block 27000** (~2.5 days out at the then-current
~75s/block real rate, height ~24080, allowing time for the dozen-node network to update), and
**mainnet + local testnet = 0** (active from genesis, since neither chain has launched yet, so
the fixes apply from the first block with no legacy period). Both consensus triggers share the
same height per chain (one coordinated activation). Previewnet temporarily omitted these keys
while rollout risk was revisited; they are restored now that the chain-config fingerprint ignores
feature-trigger metadata and can carry this activation without changing peer compatibility.

| Change | Decision | Qortium activation height | Notes |
|---|---|---|---|
| c-01 online-accounts signature V2 | Ported, gated | Previewnet 27000; mainnet+testnet 0 | Per-account Ed25519 behind `onlineAccountsSignatureV2Height`. Adversarially reviewed: pre-activation path byte-for-byte identical (replay-safe); forgery closed (SchnorrTests); V2 block round-trip tested. Hard fork — changes block serialization at activation; Previewnet operators must update before block 27000. |
| c-02 asset-order bounds | Ported, gated | Previewnet 27000; mainnet+testnet 0 | Bounds + fail-closed narrowing behind `assetOrderBoundsHeight`. Reviewed: pre-activation byte-for-byte identical; full asset suite passes with the gate active. |
| Trade-bot backup fix | Adopted (`e2c461761`) | n/a | Backs up the whole batch instead of one record. |
| search `default=true` fix | Adopted (`f514b2939`) | n/a | Response-affecting; SQL path uses `='default'`, cache path uses `NULL OR 'default'` (parity kept with upstream). |
| QDN folder-size estimator | Adopted (`8dc56fdfa`) | n/a | Uses `qortium-backup/` path; kept Qortium's correct temp-dir measurement (did not port upstream's double-count). |
| formatBytes | Adopted (`10fbc93c2`) | n/a | Logging-only. README reword not ported (Qortal-branded). |
| DB-cache start ordering | Adopted (`06a243191`) | n/a | Real fix: cache now starts after bootstrap (`BlockChain.validate`), just before the API. |
| Chat analysis API | Not adopted | n/a | Architecture mismatch: upstream streams an in-memory `ChatTransactionDelegate`; Qortium is DB-backed (`ChatStoreRepository`). Unsigned-validation part already handled by `ChatService`; `/chat/memory` meaningless for a DB-backed store. Revisit only if a native SQL-aggregation stats feature is wanted. |
| Thread-dump diagnostics | Not adopted | n/a | Qortium has no `ThreadDumpScheduler` (stripped); would require re-introducing the feature. |
| devnet/`set back` (Network) | Not adopted | n/a | Net no-op on source (cosmetic re-indent only). |
