# Qortal 6.1.7 Upstream Comparison

This document inventories the upstream Qortal changes between the Qortal
`6.1.6` and `6.1.7` release points so Qortium can decide which work belongs in
the fork.

The inventory sections are intentionally neutral. They record what changed,
where it changed, and which review bucket each change belongs in. The triage
worksheet at the end is for Qortium decisions made during review.

**Headline:** 6.1.7 is a small **maintenance / hardening release**. Unlike
6.1.6, it introduces **no new feature triggers and no consensus hard forks** —
there is no `blockchain.json` / `BlockChain.java` activation-height change at
all. The bulk of the release is defensive bounds-checking on P2P message
deserialization (DoS hardening), one additive read-only API endpoint, and two
small QDN/storage bugfixes. The only change that touches consensus-adjacent
code is added length validation in two transaction transformers; it is almost
certainly safe but must be reasoned about against existing chain history before
porting (see the C-bucket note).

## Compared Range

- Base branch: `qortal-6.1.6`
- Base commit: `e83660c117d16cf2402c828d8042cb13f19a6a1a` (tag `v6.1.6`)
- Target branch: `qortal-6.1.7`
- Target commit: `8ad0197696df82007893cef1218b9e924246765f` (tag `v6.1.7`, `qortal/master`)
- Commits in range: 9 (5 non-merge, one of which is the version bump)
- Files changed: 14
- Total diff size: 248 insertions and 10 deletions
- All paths are on the upstream package root `org.qortal` — porting requires
  re-rooting to `org.qortium`.

## Activation Heights (read this first)

**None.** 6.1.7 adds no new feature triggers and changes no activation heights.
`BlockChain.java`, `blockchain.json`, and the test chain fixtures are untouched.
There is no network-coordinated rollout required for any item in this release.

## Change Areas

### A-bucket — P2P message deserialization hardening (non-consensus)

Commit `6ca682132` ("stronger message validation"), network-message portion.
Files: `network/message/GetTradePresencesMessage.java`,
`network/message/NamesMessage.java`,
`network/message/OnlineAccountsV3Message.java`,
`network/message/TradePresencesMessage.java`.

Each `fromByteBuffer(...)` reads a 4-byte entry count and previously trusted it
when sizing an `ArrayList` and looping. The change adds a guard that rejects the
message (throws `MessageException`) when the declared count is negative or when
`count * ENTRY_SIZE (+ trailing timestamp where applicable)` exceeds the bytes
actually remaining in the buffer. New per-message `ENTRY_SIZE` constants are
introduced from existing `Transformer.*` length constants:

- `GetTradePresencesMessage`: `count * PUBLIC_KEY_LENGTH + LONG_LENGTH`.
- `NamesMessage`: a composed `ENTRY_SIZE` (name, reduced name, owner address,
  data, timestamps, flags, sale price, reference signature, group id).
- `OnlineAccountsV3Message`: `SIGNATURE_LENGTH + PUBLIC_KEY_LENGTH + INT_LENGTH`
  (+ trailing `LONG_LENGTH`).
- `TradePresencesMessage`: `PUBLIC_KEY_LENGTH + SIGNATURE_LENGTH + ADDRESS_LENGTH`
  (+ trailing `LONG_LENGTH`).

This is a network-layer DoS mitigation: a malicious or corrupt peer can no
longer make a node pre-allocate a huge list or spin a long loop from a bogus
count. **Non-consensus** — it only affects peer message parsing, not block or
transaction validity. Two signatures gain `throws MessageException`
(`GetTradePresencesMessage`, `TradePresencesMessage`); callers already handle
`MessageException` for these message families.

### B-bucket — additive read-only API: group membership validation

Commit `7adc61890` ("added membership validation endpoint").
Files: new `api/model/GroupMembershipValidation.java`,
`api/resource/GroupsResource.java` (new endpoint),
`repository/GroupRepository.java` (+ interface methods),
`repository/hsqldb/HSQLDBGroupRepository.java` (+83 lines impl).

Adds a `GroupMembershipValidation` model and a new `GroupsResource` endpoint
backed by new `GroupRepository` query methods (HSQLDB implementation). Purely
additive, read-only API surface. **Non-consensus**, no schema migration to
existing tables (read queries only). Useful for clients that need to validate
membership without scanning the full member list.

### C-bucket — transaction transformer length bounds (consensus-adjacent)

Commit `6ca682132` ("stronger message validation"), transaction-transformer
portion. Files: `transform/transaction/ArbitraryTransactionTransformer.java`,
`transform/transaction/AtTransactionTransformer.java`.

These run inside transaction (de)serialization, so they sit on the consensus
path and deserve a closer read than the A-bucket network messages:

- `ArbitraryTransactionTransformer.fromByteBuffer`: after reading
  `secretLength`, reject when `secretLength > PRIVATE_KEY_LENGTH` (32); after
  reading `metadataHashLength`, reject when `> SHA256_LENGTH` (32). Valid
  arbitrary transactions already use a 32-byte secret and a 32-byte SHA-256
  metadata hash, so well-formed historical transactions are unaffected.
- `AtTransactionTransformer.fromByteBuffer`: for MESSAGE-type AT transactions,
  reject when `messageLength > SHA256_LENGTH` (32). **Note a second, subtler
  change:** the read is now guarded by `if (messageLength > 0)`, so a
  zero-length message yields `message == null` instead of the previous
  zero-length `byte[0]`. Confirm no downstream code distinguishes `null` from
  empty for AT messages.

**Consensus reasoning.** Because these run during transaction parsing, a stricter
parser that *throws* could in principle reject a previously-accepted transaction
on replay/resync and cause a fork. Risk is low — the bounds match the only
lengths real transactions use — but before porting, verify against actual chain
history that no on-chain arbitrary/AT transaction carries a secret, metadata
hash, or AT message field exceeding these caps. Qortium does not need to gate
this behind a feature trigger if the bounds provably hold for all existing data
(then it is a strict no-op for valid history and only rejects malformed bytes).
If any field can legitimately exceed 32 bytes, this must be height-gated instead.

### D-bucket — QDN / storage bugfixes (non-consensus)

- `9052d2ba1` ("save metadata to arbitrary cache when it comes from the
  relay-cache"). Files: `controller/arbitrary/ArbitraryDataFileListManager.java`,
  `controller/arbitrary/ArbitraryDataFileRequestThread.java`. When metadata
  arrives via the relay cache, it is now also written to the arbitrary data
  cache so subsequent lookups hit cache instead of re-fetching. QDN data-layer
  optimization; non-consensus.
- `246026def` ("pointing to the temp path correctly now for data storage
  calculations"). File: `controller/arbitrary/ArbitraryDataStorageManager.java`
  (one line). Corrects the path used when computing data-storage size. Pure
  bugfix; non-consensus.

### E-bucket — version bump

- `8ad019769` ("Bump version to 6.1.7"). File: `pom.xml`. Upstream version
  string only; Qortium maintains its own version and does not adopt this.

## Triage Worksheet (Qortium decisions)

| ID | Change | Bucket | Consensus? | Recommendation |
|----|--------|--------|------------|----------------|
| 7-A | P2P message count bounds (4 message classes) | A | No | **Port.** Cheap, safe DoS hardening; re-root to `org.qortium`. |
| 7-B | Group membership validation endpoint | B | No | **Port** (additive, read-only API). Confirm desired on Qortium API surface. |
| 7-C | Transaction transformer length bounds | C | Adjacent | **Port after verification.** Confirm no historical arbitrary/AT tx exceeds the 32-byte caps; check AT `null`-vs-empty message semantics. No trigger needed if bounds hold for all history. |
| 7-D1 | Metadata → arbitrary cache from relay-cache | D | No | **Port** (QDN optimization). |
| 7-D2 | Temp-path fix for storage-size calc | D | No | **Port** (one-line bugfix). |
| 7-E | Version bump to 6.1.7 | E | No | **Skip** (Qortium versions independently). |

**Net:** no hard forks, no activation-height coordination. Five small functional
changes; all are portable. Only 7-C requires a verification step before merge.
