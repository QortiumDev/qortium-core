# AT map storage

Status: DESIGN, 2026-07-21. Target: Qortium Previewnet.

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

Qortium commits map state. Each AT carries a 32-byte **map root** maintained as a
hash chain over its own writes, in execution order:

```
mapRoot_0 = 32 zero bytes
mapRoot_n = SHA256(mapRoot_n-1 || key1 || key2 || value)      // 8-byte big-endian fields
```

The root is stored alongside the AT state and folded into the state hash:

```
stateHash = SHA256(stateData || mapRoot)
```

Properties this gives:

- Bounded — 32 bytes per AT regardless of how many entries it holds.
- Order-sensitive, which is correct: AT execution order is already pinned
  deterministically by `ORDER BY created_when ASC, AT_address DESC`.
- Any divergence in map writes changes the block's AT state hash immediately, at
  the block that caused it, instead of silently later.

Changing `stateHash` is consensus-visible, so it is gated (see "Activation").

Residual risk to be aware of: `mapRoot` is a hash chain over **write history**, not a
Merkle structure over the **current** key/value set. It detects divergence between
honest nodes (which execute deterministically and so write in identical order), but
it cannot independently attest to what `ATMapEntries` currently holds — nothing
consensus-checks that the local serving table matches the committed root. A bug in
undo-log replay on reorg, or in reconstruction during resync/bootstrap, could desync
a node's actual map contents from the agreed root and go undetected, exactly the
failure class this design closes at the digest level pushed down one layer. Two
mitigations to implement alongside: (1) a verification tool that recomputes `mapRoot`
from `ATMapEntries` + `ATMapEntryChanges` at a checkpoint and compares it to the
stored value, and (2) a defined reconstruction path for nodes that bootstrap from a
pruned/archived range rather than replaying every `SET` from genesis — `ATMapEntries`
holds current state and must be exempt from the `AT_TRIM_HEIGHT`/`AT_PRUNE_HEIGHT`
pruning that trims historical `ATStatesData`.

## Storage and rollback

New table, mirroring how account trust snapshots handle reorgs:

```sql
CREATE TABLE ATMapEntries (
  AT_address  ATAddress   NOT NULL,
  key1        BIGINT      NOT NULL,
  key2        BIGINT      NOT NULL,
  value       BIGINT      NOT NULL,
  height      INTEGER     NOT NULL,
  PRIMARY KEY (AT_address, key1, key2),
  FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE
)
```

Current values live in `ATMapEntries`. Because a reorg must restore *previous*
values rather than merely drop new ones, every write also appends to an undo log:

```sql
CREATE TABLE ATMapEntryChanges (
  AT_address    ATAddress NOT NULL,
  key1          BIGINT    NOT NULL,
  key2          BIGINT    NOT NULL,
  previousValue BIGINT,              -- NULL when the entry did not exist
  height        INTEGER   NOT NULL,
  sequence      INTEGER   NOT NULL,
  PRIMARY KEY (AT_address, key1, key2, height, sequence)
)
```

Orphaning height H replays `ATMapEntryChanges` for that height in reverse sequence,
restoring `previousValue` or deleting the row where `previousValue IS NULL`, then
deletes the change rows. The previous `mapRoot` comes back with the previous
`ATStateData` row, which `AT.revert` already restores.

Writes within a block are visible to later reads in the same block, including
reads by other ATs executing later in that block. This is what makes the oracle
pattern work, and it is why execution order must stay pinned.

## Pricing and growth control

Signum charges the flat per-function step cost for a map write — a permanent
database row costs the same as reading a register — with no cap, no rent and no
pruning. SIP-38 explicitly defers "storage design, associated fees, and state
growth management" to a specification that, four years on, does not exist.

Qortium sets two limits up front, and they live in **different** places on purpose —
`ciyamAtSettings` is a static `blockchain.json` block parsed once at startup, whereas
`ChainParameter` is the on-chain governance mechanism whose values are updated by a
`ChainParameterUpdateTransaction` gated on an activation height:

- `mapEntryStepCost` — steps charged for `SET_MAP_VALUE_KEYS_IN_A` when it creates a
  new entry. Overwriting an existing entry, or writing `0` to delete one, costs the
  ordinary function-call cost, since neither grows storage. Kept in `ciyamAtSettings`
  alongside `feePerStep` and `maxStepsPerRound`, since it is a step-budget constant,
  not a value expected to change under governance.
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

Suggested starting values, to be tuned against real step budgets before activation:
`mapEntryStepCost` = 100 (10× a function call, against `maxStepsPerRound` = 500, so
at most 5 new entries per AT per round), `maxMapEntriesPerAt` = **500**.

The cap starts deliberately low because the ratchet only turns one way: raising it is
safe, but lowering it bricks any contract that has already grown past the new limit.
Governance can raise 500 once there is real usage to calibrate against; it could not
safely walk back 5000. Note this bounds total storage only as (number of ATs) × cap,
and on a fee-less chain the number of ATs is bounded only by MemPoW deploy cost — so
the cap is a spam ceiling, not a spam solution.

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
are unrecognised and throw, and `stateHash` keeps its current definition. From it:
the codes execute and `stateHash` includes the map root.

No jar change is required — `FunctionCode.valueOf` routes the whole `0x0500-0x06ff`
range to `API_PASSTHROUGH`, so both codes are implemented entirely in
`ChainFunctionCode` and `ChainATAPI`. Adding a feature trigger is not a peering
flag-day, since `featureTriggers` is excluded from the chain config hash.

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
  rejected over-cap `SET` must **not** advance the write-hash-chain, so the count and
  the committed `mapRoot` stay consistent.
- Pruning or rent for ATs that are finished and can never write again. Their entries
  are dead weight but may still be read by other contracts.
