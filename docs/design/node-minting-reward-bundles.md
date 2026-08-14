# Previewnet Node Minting Reward Bundles

Status: implemented and locally validated for Previewnet. The first
bundle-aware batch payout is scheduled for height `100000`. This document does
not claim that the change has been released or deployed.

## Why This Change Is Needed

Core can store and try every locally configured minting key, but the legacy
online-account producer silently announces at most two eligible local keys.
The repository query that supplies those keys has no defined ordering. A third
key can therefore sign a block while remaining absent from the online-account
cohort used for reward and `blocksMinted` accounting.

Removing that two-key limit fixes the immediate omission, but the legacy flat
cohort would then give one running Core with ten keys ten independent reward
allocations while a Core with one key receives one. Previewnet instead needs a
consensus-visible declaration that groups a Core's keys into one node reward
allocation.

## Goals

- Have the honest local producer include every eligible minting key configured
  on a Core instance. Consensus can verify included keys but cannot prove that
  an operator disclosed every key it controls.
- Give each declared reward node one allocation within its account-level bin.
- Divide that node allocation among its eligible bundled minting accounts.
- Prevent one minting account from being credited more than once in a payout.
- Preserve the existing account-level bins, reward-share payouts, batch size,
  native-asset behavior, and trust-system responsibilities.
- Preserve historical replay and legacy behavior before activation.

## Explicit Non-Goals

- Proving that a reward-node identity corresponds to one physical computer,
  operator, household, IP address, or virtual machine.
- Preventing an operator from deliberately running several distinct Core
  instances with distinct reward-node identities.
- Using trust status as a monetary multiplier. Trust continues to gate minting
  eligibility and to weight governance where configured; it does not scale raw
  `blocksMinted` credit or native rewards.
- Changing the batch capture choice, share-bin percentages, level thresholds,
  external reward-share percentages, or native asset ID.

Previewnet's minting-group and trust rules remain the practical admission
boundary. Previewnet is intentionally a test network, so deliberate identity
splitting remains observable test behavior rather than a claim of Sybil-proof
hardware identity.

## Activation And Rollout

The chain-config feature trigger is:

```json
"onlineNodeRewardBundlesPayoutHeight": 100000
```

This name describes the first bundle-aware **payout** block. With the current
batch size of `100` and capture count of `10`:

- heights through `99989` use the legacy online-account representation;
- heights `99990` through `99999` are the bundle-aware capture window;
- height `100000` is the first bundle-aware payout;
- later capture windows and payouts use the bundle rules.

The trigger must be a batch-payout height and the capture-window transition is
the actual compatibility boundary. Every block-producing and validating
Previewnet Core must therefore update before height `99990`. Implementation,
activation metadata, and operator guidance ship in one release. If acceptance
or rollout cannot finish before `99990`, the configured payout height must be
moved to a later batch boundary before the capture window begins.

Configuration validation rejects a nonpositive trigger, a trigger that is not
a multiple of the configured batch size, or one whose capture start is not
strictly after the existing batch-reward activation boundary.

Other chain configurations omit the trigger and retain legacy behavior.

## Reward-Node Identity

Each Core runtime directory owns one persistent Ed25519 reward-node identity at
`${Settings.userPath}/reward-node/identity.key`. It is separate from the
transient chain and QDN handshake identities, which are
regenerated at process start and are unsuitable for consensus accounting.

Core creates a missing identity using secure randomness at any height, writes
it atomically with owner-only permissions, and reuses it across restarts. This
allows a genuinely new node to join after activation. Core never transmits the
private seed. An existing identity path that is corrupt, the wrong size,
unreadable, or a symbolic link fails closed for local online bundle production
while ordinary synchronization remains available.

Copying a data directory also copies its reward-node identity. Concurrent
instances using that identity are consequently treated as the same declared
reward node, not as extra allocations.

Two copied instances can sign different bundle statements for the same node
identity and epoch. A cache that knows several such statements first retains
the statement with the greatest valid member count, then breaks an equal-count
tie using the smallest canonical commitment hash by unsigned byte ordering,
independent of arrival order. Partial peer views can still construct different
candidate blocks, just as they can with different transaction pools, but each
block commits one choice and consensus validates that choice. A block may
contain only one bundle for a reward-node identity, so copied instances can
never create two allocations in the same cohort.

## Canonical Bundle

A bundle is a self-contained, relayable statement containing:

- protocol/domain version and chain identity;
- online-account epoch timestamp;
- reward-node public key;
- a sorted, duplicate-free list of eligible self-share public keys;
- the existing account MemoryPoW nonce for every member;
- an account signature from every member binding the timestamp, reward-node
  public key, and exact canonical bundle commitment;
- a reward-node signature over the role-separated approval defined below.

The account signature is essential: a relay that merely observes an account's
legacy timestamp signature must not be able to regroup that account under a
different reward node. Canonical ordering uses unsigned byte ordering. Counts,
lengths, members, and aggregate message size are strictly bounded before
allocation or cryptographic work.

The version-1 signing inputs are deliberately non-circular. All integers use
network-order (big-endian) encoding and all text uses length-prefixed UTF-8:

```text
member commitment preimage =
    "QORTIUM_ONLINE_NODE_REWARD_BUNDLE" ||
    protocol version ||
    network ID || genesis block signature || chain-config hash ||
    epoch timestamp || reward-node public key || member count ||
    sorted repeated(member public key || nonce)

member commitment = SHA-256(member commitment preimage)
member signature  = Ed25519(member private key, member commitment)

node approval preimage =
    "QORTIUM_ONLINE_NODE_REWARD_BUNDLE_NODE" ||
    member commitment ||
    repeated(member signature in corresponding sorted-member-key order)

node approval = SHA-256(node approval preimage)
node signature = Ed25519(reward-node private key, node approval)
```

The chain-config hash is the existing consensus fingerprint; adding the
hash-neutral activation trigger does not alter it. A bundle's canonical wire
form contains the protocol version, epoch, node public key, member count, each
ordered `(public key, nonce, member signature)` tuple, and the node signature.
The locally known chain identity is reconstructed into the signing preimages
during verification rather than repeated as attacker-controlled wire text.
Golden byte-vector tests pin every field boundary and both signatures.

Version 1 permits at most `1024` members in one bundle and at most `1024`
bundles in one message or block cohort. A block cohort permits at most `8192`
total member occurrences and `1 MiB` of encoded bundle payload. A gossip
message permits at most `16384` member occurrences and `2 MiB` of encoded
bundle payload. The existing, smaller applicable message or block limit always
wins. Aggregate counts and lengths are checked before per-member allocation,
MemoryPoW, or signature verification; all nested counts and lengths are checked
against remaining bytes. A bundle with more surviving payout members than the
batch size is permitted: its members receive `floor(100 / N) = 0` raw block
credit but can still share the node's monetary allocation. A larger declared
bundle that falls to `100` or fewer surviving members uses that smaller
denominator. This is the direct result of the approved round-down rule and is
covered explicitly rather than hidden behind another local key cap.

Gossip relays the whole signed bundle and preserves its origin-independent
proof. The immediate network peer is only a transport and is not treated as
the reward node.

Bundle gossip uses new `ONLINE_ACCOUNT_BUNDLES` and
`GET_ONLINE_ACCOUNT_BUNDLES` message types rather than changing the legacy
flat-account message, using wire IDs `86` and `87` respectively. A response is
`int32 bundleCount` followed by repeated
`(int32 bundleLength, canonicalBundleBytes)`. A request is a bounded count
followed by repeated `(int64 epoch, 32-byte reward-node public key, 32-byte
canonical commitment hash)` tuples. Grouping and node identity therefore
participate in convergence instead of hashing only the flat member keys. New
Core versions produce and gossip both legacy announcements and signed bundles
before `99990` so upgraded peers' caches are warm, but blocks select only the
height-authorized representation. Old peers can ignore the unknown messages
until the coordinated capture boundary.

## Block Commitment And Validation

The block version changes from `1` to `2` beginning at height `99990`, selected
from the parent height and configured payout trigger. `BlockTransformer` can
therefore select the wire format from the serialized version without already
knowing the block height. Version-2 blocks retain the existing unique encoded
online-account index set, but replace the legacy signature-count section with
a bounded bundle-payload length followed by:

```text
bundle count || repeated(bundle byte length || canonical bundle bytes)
```

The existing online timestamp and `online_accounts_signatures` repository
fields store the common epoch and versioned bundle payload, avoiding a schema
version bump. `BlockData` exposes a version-aware bundle-payload accessor so
callers never interpret version-2 payload bytes as a legacy signature count.
Signature trimming updates only version-1 rows because version-2 grouping is
permanent consensus data. Outside bundle-aware capture and payout blocks,
version-2 blocks carry no bundle payload. The archive serializer uses the same
version-selected form.

Bundle-aware capture blocks commit the node-to-account grouping, membership
proofs, nonces, and required signatures in that versioned representation. The
block minter signature covers the entire canonical bundle payload in addition
to the flattened account set. Its version-2 preimage is:

```text
reference || minter public key || block version ||
encoded-online-set length || encoded-online-set ||
bundle-present marker ||
[epoch || bundle-payload length || complete bundle payload]
```

Integers are big-endian and the bracketed fields are present only when marked.
The version-1 minter preimage remains byte-for-byte unchanged. The payout block
copies the complete selected bundle cohort from the chosen capture block, just
as the legacy payout copies its selected flat online-account cohort.

Validation independently reconstructs the canonical bytes and rejects:

- invalid node or account signatures;
- wrong chain/domain/version/timestamp bindings;
- unsorted or repeated members inside a bundle;
- invalid nonces or accounts not eligible at the capture height;
- repeated reward-node identities;
- malformed counts, lengths, or trailing bytes;
- bundle data outside a bundle-aware capture or payout block;
- legacy flat data where bundle data is required, or bundle data before the
  compatibility boundary.

Capture-block validation checks capture-height member eligibility, nonce, and
member proof against that block's state. Payout-block validation does not try
to reconstruct historical trust or group state from the present repository;
it requires byte-for-byte cohort equality with the deterministically selected
stored capture block, then reward processing applies the separate
entering-payout-state eligibility filter.

The exact selected capture block remains the current highest-online-account
count choice with its existing deterministic tie behavior. For this comparison,
the count is the number of **unique eligible self-share keys** in the flattened
encoded set, not the sum of overlapping memberships and not the number of
bundles. The encoded set must exactly equal the unique union of bundle members.
Changing capture selection is outside this feature.

## Duplicate Account Resolution

A minting account may appear in at most one effective bundle at payout. When
the same self-share key appears in several valid captured bundles:

1. Compare the bundles' **original declared eligible member counts**.
2. Assign the account to the smallest original bundle.
3. If sizes tie, assign it to the unsigned-lexicographically smallest
   reward-node public key.
4. Remove it from every losing bundle.
5. Drop any bundle left with no members.

Original counts do not change while resolving overlaps, avoiding circular
re-ranking. Every declared member was independently eligible at capture, so an
ineligible padding key cannot enlarge or shrink this comparison.

For example, if the same account is in a valid singleton bundle and a valid
ten-account bundle, the singleton retains it. The other bundle then has nine
effective members and divides its node allocation among those nine. The shared
account receives no second slice.

## Payout Ordering And Arithmetic

At a bundle-aware payout height, Core performs these steps deterministically:

1. Load the selected captured bundle cohort and resolve duplicate accounts by
   the rule above.
2. Recheck each remaining account against the state entering the payout block,
   before that block's transactions: payout-height minting-group and trust
   eligibility. Remove ineligible or Suspicious accounts and drop empty
   bundles. Unverified, Bronze, Silver, and Gold remain equally eligible for
   this accounting.
3. Let `N` be a bundle's surviving account count. Credit each account with
   `floor(batchSize / N)` raw `blocksMinted` blocks. With today's batch size,
   this is `floor(100 / N)`. Any integer remainder is deliberately uncredited.
4. Apply level changes resulting from those raw credits.
5. Assign each surviving bundle to the share bin containing its highest
   post-increase member level.
6. Evaluate `minAccountsToActivateShareBin` using the number of bundles in the
   bin, not the number of keys. Existing inactive-bin roll-down and reward-share
   normalization rules remain in force.
7. Divide a bin's amount equally among its bundles, rounding down atomic units.
8. Divide each bundle amount equally among its surviving minting accounts,
   again rounding down.
9. Apply each account's existing external reward-share recipients and
   percentages to that account's slice. Those recipients do not gain
   `blocksMinted` credit.

This produces the intended equal-level example: five reward nodes in the same
active bin each receive one fifth of that bin's amount. A one-key node gives its
key the whole node allocation. A two-key node gives each key half of its node
allocation. Adding keys to one bundle does not reduce another node's share.

Native block rewards and batch fees continue to use asset `0`. If asset `0`
does not exist, balance distribution is skipped while raw `blocksMinted` and
level progression still occur. Division dust and raw block-credit remainders
remain unassigned rather than being awarded by ordering. In bundle mode,
per-bin or per-bundle division shortfall is not reallocated into later bins;
the legacy cross-candidate shortfall adjustment remains unchanged only on the
legacy path.

## Orphaning And Historical Data

Orphaning a bundle-aware payout reverses the exact per-account raw credit and
balance deltas derived by the forward rules. It must not assume the legacy
uniform `+100` credit. Forward resolution uses the state entering the payout
block. During orphaning, after the payout block's transactions and group
decisions are undone, Core restores/rederives the height-`H - 1` trust snapshot
before reconstructing and reversing the reward plan; otherwise post-block trust
state could remove a different cohort. Repository and archive representations
preserve enough bundle grouping to replay, validate, serve, synchronize,
remint, and orphan the same result after restart or pruning.

Blocks before the capture boundary retain their existing bytes, signatures,
storage, API decoding, reward arithmetic, and orphan behavior. The inherited
two-key announcement limit remains only on that historical/legacy path; the
bundle producer uses every eligible local key.

## Acceptance Requirements

The activation PR is not ready for rollout until it demonstrates:

- red-before/green-after coverage of the hidden two-key omission through the
  production online-account computation path;
- persistent identity creation, reload, permissions, corruption failure, and
  copied-identity behavior, including order-independent conflicting-statement
  selection by greatest member count then smallest commitment, and one
  allocation per node identity;
- canonical bundle/message/block round trips and adversarial length, ordering,
  signature, nonce, chain-binding, duplicate-node, and duplicate-member cases;
- exact transition tests at `99989`, `99990`, `99999`, and `100000`, including
  rejection of the wrong representation on either side;
- one-key, multi-key, mixed-level, payout-ineligible, overlap, tie-break,
  external reward-share, missing-native-asset, rounding, and empty-bundle cases;
- exact forward/orphan symmetry for balances, levels, and nonuniform
  `blocksMinted` credits;
- repository restart, block/archive serialization, synchronization, reminting,
  and multi-node interoperability tests;
- a clean serialized full test suite, packaged-artifact inspection, independent
  consensus/security review, and an operator rollout check before height
  `99990`.

## Local Acceptance Evidence

As of 2026-08-14, the source implementation is complete on the feature branch:

- the focused identity, codec, message, configuration, block-format, bundle
  selection, reward, orphan, no-native-asset, and legacy reward matrix passed
  `96/96` tests with no failures, errors, or skips;
- the shipped Previewnet boundary regression accepts version 1 at `99989`,
  requires version 2 for capture blocks `99990` and `99999` and payout block
  `100000`, and rejects the opposite representation at each height;
- the clean serialized package run passed `3063` tests with no failures or
  errors and `67` skips;
- the resulting shaded JAR contains the reward identity, bundle protocol,
  resolver, payout planner, and version-2 block implementation; its bundled
  `previewchain.json` activates the first payout at `100000`, while the generic
  `blockchain.json` omits the trigger;
- independent consensus, security, test-design, and orphan-ordering reviews
  found no remaining source or PR-acceptance blocker; and
- `git diff --check` passed for the implementation range and completion-document
  diff.

This is source and local-artifact evidence only. Before release, operators must
run the exact candidate on multiple Previewnet nodes, verify bundle gossip and
block interoperability, confirm the live height still leaves enough time to
update every block producer before `99990`, and move the payout trigger to a
later batch boundary if that rollout cannot finish safely. No release,
deployment, live-network validation, or chain activation is claimed here.
