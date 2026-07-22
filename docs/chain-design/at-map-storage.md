# AT map storage

Status: IMPLEMENTED, 2026-07-21. Target: Qortium Previewnet block 70,000.

Persistent key/value storage for automated transactions, so an AT can keep state
that does not fit in its 2 KB data segment.

## Why

`DeployAtTransaction.MAX_AT_STATE_LENGTH` is 2048 bytes, which after the header,
stacks and registers leaves roughly 250 longs of data segment. That is enough for
counters and a few addresses, and not enough for any registry, allowlist, ledger or
order book. The concrete case that surfaced this: a faucet that must pay each
account once and only once cannot store the set of accounts it has already paid.

Signum hit the same ceiling and answered it with a side table rather than a larger
data segment (SIP-38, `SMART_ATS` fork, mainnet height 1,029,000). Their AT data
pages are still 10 × 256 bytes, essentially unchanged, while the map table carries
the real state. That is the model adopted here, with deliberate deviations noted
under "Where this differs from Signum".

## Function codes

Two new codes in the platform-specific range. The CIYAM spec splits that range into
`0x0500-0x05ff` for block/transaction information and `0x0600-0x06ff` for balances
and operations; the whole `0x06xx` block is currently unused by any AT
implementation, and storage operations belong there.

| Code | Name | Params | Returns |
|---|---|---|---|
| `0x0600` | `GET_MAP_VALUE_KEYS_IN_A` | 0 | yes |
| `0x0601` | `SET_MAP_VALUE_KEYS_IN_A` | 0 | no |

The read-any/write-self **semantics** follow Signum, but the register layout does
not — Signum's target-AT identifier is a 64-bit account id that fits in one register,
and Qortium's does not (see below), so copying its layout verbatim is not possible:

- `GET`: `A1` = key1, `A2` = key2, target AT **address in register `B`** (an empty
  `B` means self). Returns the value, or `0` if unset.
- `SET`: `A1` = key1, `A2` = key2, `A4` = value. Always writes to the calling AT and
  never consults `B`.

Note the deliberate asymmetry, copied from Signum: an AT may **read** any AT's map
but may only **write** its own. That gives synchronous oracle reads — one contract
publishes state, another reads it with no transaction between them — while keeping
every entry's provenance unambiguous.

`B` identifies the target AT for reads. Signum passes a 64-bit numeric account id in
an A-register; Qortium has no such id — the `ATs` table's primary key is the 25-byte
`AT_address` and there is no numeric row id to pass, so that approach is not merely
undesirable but unimplementable. Putting the address in `B` follows the register this
codebase already uses for every address operation (`PAY_TO_ADDRESS_IN_B`,
`GET_ACCOUNT_LEVEL_FROM_ACCOUNT_IN_B`, `getAccountFromB`), so an AT author needs no
new convention, and it leaves the full A register free for the two keys.

Implementation note: `getAccountFromB` must **not** be reused verbatim for the
"empty `B` = self" case. An all-zero `B` there resolves to a real account derived
from the all-zeros public key, not to the executing AT. The map functions need their
own resolver that checks for an all-zero `B` first and substitutes
`atData.getATAddress()`, only falling through to address resolution otherwise.

## Consensus commitment — the deviation that matters most

Signum commits `md5(at.getBytes())` over the serialized machine state into each
block, and **map entries are not part of that digest**. Two nodes can therefore
disagree about map contents while producing identical AT state hashes. The
divergence only surfaces later, when some contract reads a map value and branches
on it, by which point the fork is deep and its cause is far away.

Qortium commits the **current** map state. Each AT carries a 32-byte **map root**
computed over its live, nonzero entries. Entries are sorted lexicographically by
signed `key1` and then signed `key2`, matching Java/HSQLDB `BIGINT` ordering. Each
field is encoded as an 8-byte big-endian signed long and the canonical entry stream
contains no count, separators or zero-valued rows:

```
canonicalMap = concat(key1 || key2 || value for each sorted live entry)
mapRoot = SHA256(canonicalMap)
emptyMapRoot = SHA256(empty byte string)
```

The root is stored alongside the AT state and folded into the state hash:

```
stateHash = SHA256(stateData || mapRoot)
```

Properties this gives:

- Bounded — 32 bytes per AT regardless of how many entries it holds.
- Canonical — the same current key/value set always has the same root regardless of
  the history that produced it.
- Directly verifiable — recomputing the root from `ATMapEntries` and comparing it to
  the latest stored `mapRoot` verifies the serving table without replaying history.
- Consensus-visible — any divergence in the current map state changes the AT state
  hash in the block that commits that state.

Changing `stateHash` is consensus-visible, so it is gated (see "Activation").

The root commits final state, not transient writes that cancel each other within one
AT round. That is intentional: the map is consensus state, while the journal below
exists only to reverse a committed block. A verification tool can recompute every
AT's root directly from `ATMapEntries` and compare it with the latest `ATStateData`.

`mapRoot` is stored alongside the serialized machine state in `ATStateData` and the
`ATStates` repository row. Pre-activation rows have no root; the first active
execution treats the prior map as empty and uses `SHA256(empty)`. The root does not
need a new field in the block wire format because `stateHash` already commits it and
validators re-execute the AT to derive the same value.

## Storage and rollback

New table, mirroring how account trust snapshots handle reorgs:

```sql
CREATE TABLE ATMapEntries (
  AT_address  ATAddress   NOT NULL,
  key1        BIGINT      NOT NULL,
  key2        BIGINT      NOT NULL,
  value       BIGINT      NOT NULL,
  PRIMARY KEY (AT_address, key1, key2),
  FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE
)
```

Current values live in `ATMapEntries`. Because a reorg must restore *previous*
values rather than merely drop new ones, every actual mutation also appends to an
undo journal:

```sql
CREATE TABLE ATMapEntryChanges (
  AT_address    ATAddress NOT NULL,
  key1          BIGINT    NOT NULL,
  key2          BIGINT    NOT NULL,
  previousValue BIGINT,              -- NULL when the entry did not exist
  newValue      BIGINT,               -- NULL when the entry was deleted
  height        INTEGER   NOT NULL,
  sequence      INTEGER   NOT NULL,
  PRIMARY KEY (height, sequence),
  FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE
)
```

`sequence` is allocated globally across accepted map mutations in deterministic AT
execution order. A logical no-op (setting the effective value again, including
deleting an absent entry) creates no journal row and does not change the root. A
write rejected by the entry cap behaves the same way. Recording both previous and
new values makes the journal independently auditable while keeping reverse replay
simple.

Orphaning height H replays `ATMapEntryChanges` for that height in descending
`sequence`, restoring `previousValue` or deleting the row where `previousValue IS
NULL`, then deletes the change rows. The previous `mapRoot` comes back with the
previous `ATStateData` row, which `AT.revert` already restores.

## Validation overlay and atomicity

AT execution must not write either table directly. `Block.executeATs()` runs during
block construction and validation, before the block is accepted, so repository
writes there could leak from an invalid candidate. Instead, each `Block` owns one
in-memory map execution context shared by every `ChainATAPI` created for that block:

- A read checks the block overlay first and then the committed `ATMapEntries` table.
- A successful write changes only the calling AT's overlay, updates its effective
  entry count and appends a block-global pending journal item.
- Each AT round starts with a context checkpoint. If the round is discarded — for
  example because its serialized state exceeds the limit — all writes from that AT
  are removed before the next AT runs.
- Once a round is accepted, its final canonical current-state root is computed from
  the committed rows plus overlay and placed in that round's `ATStateData` before
  `stateHash` is calculated.

This overlay makes writes visible to later reads in the same AT and to other ATs
executing later in the block without exposing unvalidated state. AT order remains
pinned by `ORDER BY created_when ASC, AT_address DESC`; an earlier reader sees the
parent-block value, while a later reader sees an earlier writer's accepted overlay.

Only `Block.process()` applies the pending current rows and journal rows, alongside
the already-derived AT states, generated transactions and fees. They use the same
repository transaction and become durable together at the caller's normal commit.
Any processing failure rolls the whole transaction back. Orphaning reverses all map
changes for the height and AT states in the same repository transaction, so a reorg
cannot expose a map from one branch with machine state from another.

## Bootstrap, replay and pruning

`ATMapEntries` is current consensus state. It must be included in repository
backups/bootstrap snapshots and must never be trimmed or pruned with historical
`ATStatesData`. The latest retained AT state must include its `mapRoot`, including
for sleeping or finished ATs whose maps remain readable.

A node replaying blocks from at or before `atMapStorageHeight` reconstructs maps and
roots deterministically through normal AT execution. A bootstrap or archive replay
that starts after activation cannot reconstruct current entries from block state
hashes alone; its trusted starting snapshot must therefore include `ATMapEntries`
and the corresponding latest per-AT roots. After repository initialization, Core
recomputes every latest root from the current table and refuses to start if the
serving rows and retained AT states disagree.

`ATMapEntryChanges` is rollback history, not the source of current-state
verification. Journal rows must be retained for every height the node still claims
it can orphan. `AtStatesPruner` removes them only through the same safe AT-state
prune horizon. Pruning journal history never permits pruning `ATMapEntries` or the
latest root.

## Pricing and growth control

Signum charges the flat per-function step cost for a map write — a permanent
database row costs the same as reading a register — with no cap, no rent and no
pruning. SIP-38 explicitly defers "storage design, associated fees, and state
growth management" to a specification that, four years on, does not exist.

Qortium sets two limits up front, and they live in **different** places on purpose —
`ciyamAtSettings` is a static `blockchain.json` block parsed once at startup, whereas
`ChainParameter` is the on-chain governance mechanism whose values are updated by a
`ChainParameterUpdateTransaction` gated on an activation height:

- `mapEntryStepCost` — total steps charged for `SET_MAP_VALUE_KEYS_IN_A` when it
  creates a new entry. Reads, overwrites, deletes, logical no-ops and cap-rejected
  writes cost the ordinary function-call cost of 10 steps. A new live entry costs
  100 steps total. Kept in `ciyamAtSettings` alongside `feePerStep` and
  `maxStepsPerRound`, since it is a step-budget constant, not a value expected to
  change under governance.
- `maxMapEntriesPerAt` — hard ceiling on live entries per AT. A `SET` that would
  exceed it is a no-op; the AT continues rather than erroring, so a contract cannot
  be bricked by a full map, and authors are expected to check with a read first.
  Exposed as a **`ChainParameter`**, so the cap can be raised by governance vote
  without a release. It must **not** live in `ciyamAtSettings`: changing a value
  there alters the chain config hash and is a peering flag-day, which a cap we expect
  to tune over time cannot be.

Deleting is writing `0`: the value `0` is indistinguishable from unset by design, so
`GET` on a missing key returning `0` and a deleted key returning `0` are the same
observable, and the row is removed to reclaim space.

The stock CIYAM API currently prices an external-function opcode before the platform
function can inspect whether the key exists. QortiumDev/AT therefore adds a
backward-compatible platform pre-charge hook with a no-op/default implementation for
other hosts. Core uses that hook before execution to add 90 steps only when the SET
would create a nonzero entry within the cap; the ordinary function charge already
accounts for the first 10. If the remaining round budget cannot cover the full 100,
the function does not execute and no overlay mutation occurs. Overwrite, delete,
logical no-op and cap rejection receive no additional charge.

Starting values: `mapEntryStepCost` = **100** (10× the ordinary function call,
against `maxStepsPerRound` = 500, so at most 5 new entries per AT per round) and
`maxMapEntriesPerAt` = **500**.

The cap starts deliberately low because the ratchet only turns one way: raising it is
safe, but lowering it bricks any contract that has already grown past the new limit.
Governance can raise 500 once there is real usage to calibrate against; it could not
safely walk back 5000. Note this bounds total storage only as (number of ATs) × cap,
and on a fee-less chain the number of ATs is bounded only by MemPoW deploy cost — so
the cap is a spam ceiling, not a spam solution.

Because a cap-rejected SET is deliberately a no-op, contracts must not assume that
returning from SET means the entry exists. The SMPL faucet must use this sequence:

1. Check the claimant key is currently unset.
2. SET a nonzero claimed marker.
3. GET the same key from self and verify the marker was stored.
4. Only then issue the payment.

This SET-readback-before-PAY rule prevents a full map from turning the cap's no-op
semantics into repeated faucet payouts.

## Where this differs from Signum

| Aspect | Signum | Qortium |
|---|---|---|
| Map state in block digest | no | **yes**, via per-AT map root |
| Entry cap per AT | none | `maxMapEntriesPerAt` (governance-updatable `ChainParameter`) |
| New-entry pricing | flat function cost | `mapEntryStepCost` |
| Function codes | `0x0407`/`0x0408` (portable range) | `0x0600`/`0x0601` (platform range) |
| Target AT identifier | 64-bit account id in an A-register | AT address in register `B` |
| Unknown function code | returns `0` silently | throws `IllegalFunctionCodeException` |

The numbering difference is deliberate. Signum placed chain-specific functions
inside the spec's portable `0x01xx`-`0x04xx` range, which is a large part of why AT
bytecode is not portable between Signum and Qortal and never will be. Qortium keeps
chain-specific work in the sanctioned platform range, as Qortal has.

## Activation

Gated on a new feature trigger, `atMapStorageHeight`. Before it: both function codes
are unrecognised and throw, `mapRoot` is absent, and `stateHash` keeps its current
definition. From it: the codes execute, an empty map uses `SHA256(empty)`, and
`stateHash` includes the map root. Existing ATs acquire their first root on their
first active execution; ATs deployed at or after the trigger store the empty root in
their initial state.

`FunctionCode.valueOf` already routes the whole `0x0500-0x06ff` range to
`API_PASSTHROUGH`, so the function behavior lives in `ChainFunctionCode` and
`ChainATAPI`. The new-entry pricing distinction additionally requires the
backward-compatible QortiumDev/AT pre-charge hook described above. Adding a feature
trigger is not a peering flag-day, since `featureTriggers` is excluded from the
chain config hash, but every consensus node still has to run the supporting Core/AT
version with the same activation height before the trigger.

## Decided

- **Cap mechanism: a governance `ChainParameter`, not a per-AT allowance purchased at
  deploy.** The purchased-allowance model prices storage more honestly, but it needs a
  deploy-transaction-format change (a flag day) and a top-up transaction type, and it
  has nothing to charge against on Previewnet, where transactions carry `fee=0` with a
  MemPoW nonce — storage is currently unpriceable. A fixed governance-updatable cap
  becomes the free tier that a purchased allowance could extend later, once a fee
  market exists; retrofitting a cap onto purchased storage would be the harder
  migration. See "Pricing and growth control".

## Open questions

- Whether to expose a `GET_MAP_ENTRY_COUNT` so a contract can check its own headroom
  before writing, rather than discovering the cap by no-op. If added, note that a
  rejected over-cap `SET` must not change the overlay, count, journal or committed
  `mapRoot`.
- Pruning or rent for ATs that are finished and can never write again. Their entries
  are dead weight but may still be read by other contracts.
