# Archive Replay Performance Investigation

Status: diagnostic branch, 2026-06-30.

## Problem

Archive chunks now let a fresh Previewnet node fetch and verify archived block data, but the local
replay step is slower than expected. A Leap 16 Qube replay of blocks `2..30000` showed the archive
replay rate dropping from roughly `168 blocks/sec` near block `501` to under `5 blocks/sec` by block
`21441`, while the node held the blockchain lock in one atomic HSQLDB transaction.

The archive path is therefore solving the network/bootstrap correctness problem, but it is not yet
solving the fast-bootstrap problem.

## Current Evidence

The 2026-06-30 Leap run used PR #84 before this diagnostic branch. The JVM was CPU-bound rather than
swapping or disk-full. `jcmd Thread.print` samples repeatedly put the hot thread in:

```text
Archive Fast-Sync Manager
  Block.process()
  Block.increaseAccountLevels()
  HSQLDBAccountRepository.modifyMintedBlockCounts()
  HSQLDB disk-backed AVL/index reads/inserts
```

That evidence points first at `Block.process()` account-level work and HSQLDB transaction/index
growth, not at archive chunk download, transaction persistence, or signature verification.

## Diagnostic Changes

This branch adds archive-replay-only timing collection:

- `ArchiveChunkImporter` already reports recent-window rate and top-level replay timings at INFO.
- `Block.process()` now accepts a thread-local timing listener.
- Archive replay installs that listener only around `block.process()`.
- Normal sync does not emit the new timing logs and does not change behavior.

The INFO progress line now includes recent average milliseconds per block for named `Block.process()`
subphases, including:

- `increaseAccountLevels`
- `increaseAccountLevels.getExpandedAccounts`
- `increaseAccountLevels.buildUniqueMintingAccounts`
- `increaseAccountLevels.modifyMintedBlockCounts`
- `increaseAccountLevels.levelUpdates`
- `processBlockRewards`
- `processTransactions`
- `processGroupApprovalTransactions`
- `trustSnapshotChecks`
- `refreshTrustDerivationSnapshots`
- `freezeClosedPolls`
- `processAtFeesAndStates`
- `fetchLatestBlockReference`
- `saveBlock`
- `saveOnlineRewardSharePublicKeys`
- `linkTransactionsToBlock`
- `postBlockTidy`

## Test Plan

1. Let the current PR #84 Leap replay finish if possible, to preserve completion or failure evidence.
2. Build this branch on a fresh test VM or cloned Qube.
3. Start from a fresh Previewnet runtime and run archive fast-sync against the live seeds.
4. Capture `/admin/status` plus the `Archive fast-replay` INFO progress lines every few minutes.
5. Compare the recent top-level `process` average with the subphase averages.

## Interpretation

Likely conclusions:

- If `increaseAccountLevels.modifyMintedBlockCounts` dominates, test batched/deferred account minted
  count updates next.
- If many write-heavy subphases grow together, test whether the one huge HSQLDB transaction is the
  main cost.
- If local non-consensus indexes dominate, test deferring or rebuilding those indexes after checkpoint
  verification.
- If cleanup dominates, test deferring cleanup until after replay.

Avoid skipping more validation as the first optimization. Current evidence does not point there, and
the archive checkpoint safety model depends on replaying derived state locally.

## Resume Checklist

When picking this up:

1. Check PR #84 first. It is the correctness/observability base branch
   (`codex/archive-replay-save-transactions`). Let any live PR #84 replay finish before replacing the
   test runtime unless the operator explicitly decides to stop it.
2. Check this branch and PR #85 (`codex/archive-replay-diagnostics`). It is intentionally based on
   PR #84 so the diagnostic diff is reviewable; retarget/rebase after PR #84 merges.
3. If using the Qubes lab, prefer a cloned/new Qube for this branch. The original Leap runtime was
   `~/qortium-pr84-replay-current` and should be treated as evidence from the PR #84 run.
4. Build with `mvn -B -DskipTests package`, run a fresh Previewnet runtime, and collect only compact
   log/status samples:

   ```bash
   grep 'Archive fast-replay: replayed block' "$runtime/qortium.log" | tail -20
   curl -H "X-API-KEY: $api_key" http://127.0.0.1:24891/admin/status
   ```

5. Use the process-phase averages to choose exactly one next experiment. Do not combine batched
   account updates, replay commit changes, and deferred local indexes in the same first test.

## Safety Boundary

This branch is for evidence gathering. It should not change consensus behavior, checkpoint
verification, transaction processing order, or the atomic replay safety model. Any experimental
optimization flags should go on a later branch and default off.
