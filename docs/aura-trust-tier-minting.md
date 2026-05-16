# Aura Trust-Tier Minting

This note records a proposed direction for using a BrightID/Aura-style trust
graph to reduce farm-account influence in Qortium without requiring constant
manual policing of minters.

This document tracks the first implementation and the remaining design work for
Aura-style trust tiers in Qortium.

For a reader-facing explanation of the current system, see
`docs/account-trust-network.md`.

## Goal

Qortium already has a simple foundation for minting and vote weight:

- accounts must be allowed to mint before they can earn `blocksMinted`
- voting weight is based on the amount of `blocksMinted` an account has earned

The weakness is that a farm operator can try to create many minting accounts,
earn `blocksMinted` on all of them, and turn those accounts into governance
weight. Manual duplicate-account investigations create conflict and can still
be wrong.

The goal is to keep the existing `blocksMinted` voting model, but improve the
foundation underneath it by adding a trust-tier multiplier that makes many weak
or unverified accounts much less useful to an attacker.

## Reference Material

Local reference copies were cloned outside this repository under:

- `/home/user/git/brightid-reference/BrightID-Explorer`
- `/home/user/git/brightid-reference/aura`
- `/home/user/git/brightid-reference/aura-dashboard`
- `/home/user/git/brightid-reference/aura-frontend`
- `/home/user/git/brightid-reference/aura-verified`
- `/home/user/git/brightid-reference/BrightID-Node`
- `/home/user/git/brightid-reference/BrightID-AntiSybil`
- `/home/user/git/brightid-reference/BrightID`
- `/home/user/git/brightid-reference/BrightID-SmartContract`

External documentation references:

- BrightID GitBook: `https://brightid.gitbook.io/`
- Bitu verification: `https://brightid.gitbook.io/brightid/verifications/bitu-verification`
- Aura documentation: `https://brightid.gitbook.io/aura`

## BrightID, Bitu, and Aura Scope

BrightID is the base identity network, mobile app, node software, and social
graph. It is the broader system that stores and serves connection and
verification data.

Meets is the original video or meeting based BrightID verification path. It may
still help BrightID's own network, but it is not the part Qortium is trying to
reuse.

Bitu is a graph-position verification. BrightID's app models it as a `Bitu`
verification with a numeric `score`, direct reports, and reported connections.
Its user-facing guidance is based on making real "already known" connections
and avoiding incorrect strong connections to strangers, because reports and
penalties reduce the score. Bitu is useful background for understanding
BrightID's trust graph, but it does not directly provide the Gold, Silver,
Bronze, Suspicious, and Unverified tiers proposed here.

Aura is the closest match for Qortium's needs. BrightID's app models Aura as a
verification with a `score` and `level`, and the Aura explorer displays those
levels as Gold, Silver, Bronze, Suspicious, or Unverified. The current Qortium
proposal uses that tiered status idea. It does not require Qortium to copy
BrightID meetings, BrightID's full app flow, or Bitu's score rules.

The most directly relevant reference for this idea is the Aura graph displayed
by BrightID Explorer. In `BrightID-Explorer/scripts/aura.py`, the explorer reads
BrightID verifications where the verification name is `Aura`, then attaches the
verification `level` and `score` to each graph node. In
`BrightID-Explorer/js/aura.js`, the displayed node levels are treated as:

- `Gold`
- `Silver`
- `Bronze`
- `Sus`, displayed as Suspicious
- missing or unknown level, displayed as Unverified

The graph also carries trust-network relationship data such as honesty,
energy, and comments. Qortium does not need to copy BrightID's video-meeting
workflow to evaluate this idea. The useful part for Qortium is the graph-derived
account status.

BrightID-style connection data also separates active relationships from the
absence of a relationship. Qortium treats rating `0` as no active edge, rather
than a stored trust signal. The native account-rating transaction records signed
confidence from `-4` through `4`: positive values are positive confidence,
negative values are negative confidence, and `0` clears the rater's current
active edge for that target. This mirrors the useful trust-graph shape without
yet deriving Gold, Silver, Bronze, Suspicious, or Unverified account status from
those edges.

## Proposed Rule

Minting-group membership remains the base permission. Aura-style status then
modifies what that account can earn or how much its earned history counts.

| Trust status | Minting effect | Voting multiplier |
| --- | --- | --- |
| Gold | Can mint if in the minting group | 100% |
| Silver | Can mint if in the minting group | 50% |
| Bronze | Can mint if in the minting group | 25% |
| Unverified | Can mint if in the minting group | 0% |
| Suspicious | Cannot mint, even if in the minting group | 0% |

This keeps `blocksMinted` as the familiar accumulated work signal. The
multiplier is applied when calculating effective voting weight. For example:

- a Gold account with 10,000 `blocksMinted` votes with 10,000 effective weight
- a Silver account with 10,000 `blocksMinted` votes with 5,000 effective weight
- a Bronze account with 10,000 `blocksMinted` votes with 2,500 effective weight
- an Unverified account can cast a vote, but contributes zero effective weight
- a Suspicious account is blocked from minting and contributes zero effective
  weight

The important distinction is that raw `blocksMinted` remains an account-history
counter. Qortium can then expose both raw `blocksMinted` and effective vote
weight so users can understand why a vote has the weight it has.

## Current Implementation Choices

The current implementation keeps the deterministic trust-status and vote-weight
foundation entirely inside Qortium consensus state:

- active trust status comes from the stored Subject trust snapshot
- accounts without a Subject snapshot are treated as Unverified
- accounts with a Suspicious Subject snapshot cannot mint, even if they remain
  in the minting group
- vote tallies use the active Subject snapshot at tally time
- account, poll-vote, and resource-rating responses expose read-only fields
  so raw `blocksMinted`, trust status, multiplier, and effective vote weight
  are visible without a separate manual account status

The next implementation layer adds directed account ratings as chain data:

- accounts can rate known public-key accounts with Aura-style signed confidence
  values from `-4` through `4`
- rating `0` clears the rater's active edge for that target
- account-rating summaries expose positive and negative confidence counts
- these edges do not change trust status, minting eligibility, or vote weight
  until a later deterministic trust-tier derivation rule is added

A later implementation layer added deterministic decentralized trust
derivation APIs:

- the derived graph uses only active on-chain `RATE_ACCOUNT` edges
- the current derived graph can be listed through
  `GET /account-ratings/trust-derivation`, which lets clients filter by final
  derived status, minting seed membership, and category level without fetching
  one account at a time
- account ratings are category-aware, using Aura-style Subject, Player,
  Trainer, and Manager categories
- current minting-group members are the decentralized seed set for derivation
  instead of Aura's hardcoded team-owner seed
- the active Manager derivation is based on the recovered Aura node scorer at
  `https://github.com/Meta-Node/BrightID-Aura-Node`
- Manager energy starts from the minting seed set, flows for four positive
  Manager-rating hops, and splits each account's outgoing energy by confidence
- after those four hops, the final Manager energy is used to score Manager
  ratings; positive ratings add `confidence * energy`, and negative ratings
  subtract `confidence * energy * 4`
- this means Aura budgets Manager energy while it is flowing through the graph,
  but it does not split a rater's final category score again across every
  Manager, Trainer, Player, or Subject target they evaluate
- the derivation computes Manager, Trainer, Player, and Subject scores in sequence:
  minting seed weight feeds Manager ratings, Manager score feeds Trainer
  ratings, Trainer score feeds Player ratings, and Player score feeds Subject
  ratings
- the recovered Aura scorer does not apply a separate per-evaluator cap, so
  Qortium keeps raw category scores visible while adding its own capped
  level-decision score based on the Aura documentation's planned cap concept
- positive level decisions cap each evaluator's impact at half of the target
  level threshold; this means one evaluator cannot satisfy a positive level by
  itself, while raw score and raw impact remain available for audit
- positive level decisions also require at least two independent seed-derived
  trust branches, so same-branch raters cannot lift an account into a positive
  trust tier by themselves
- negative/Suspicious decisions use the same signed capped decision score, with
  Suspicious requiring at least two independent medium-confidence negative
  raters and at least two independent seed-derived trust branches before raw
  negative evidence can block minting
- Qortium defines a trust branch as the first positive Manager hop out of a
  Minting group seed, so one seed can grow several separately counted branches
  while same-branch negative raters cannot make a target Suspicious by
  themselves
- broad outbound positive-rating budgeting beyond Aura's Manager energy flow
  remains a possible future Qortium-specific policy decision, but it is not
  part of the current Aura-parity scoring model
- the implemented thresholds, per-rating caps, positive and Suspicious branch
  requirements, seed energy, manager hop count, active Subject weighting
  category, and vote multipliers are chain-configurable through
  `accountTrustSettings`
- the Subject level is mapped back to Qortium's simple Gold, Silver, Bronze,
  Unverified, and Suspicious statuses as a derived status
- the older inbound/outbound confidence counts, mutual positive relationships,
  and evaluator impacts are still exposed for audit context
- this layer originally exposed the graph for audit only, before the stored
  Subject snapshot was used for active voting and resource-rating weights

The latest implementation layer stores the current derived trust graph as
repository state and refreshes it only when trust inputs change:

- Qortium stores one current snapshot row for each account and category
- snapshot rows include the derived raw score, capped level-decision score,
  level-decision cap, level, mapped trust status, minting seed membership,
  inbound rating counts, and the block height and timestamp
  that produced the snapshot
- orphaning a trust-changing block refreshes the snapshot back to the previous
  chain state
- `GET /account-ratings/trust-derivation` reads the stored snapshot by default,
  while `live=true` recalculates the graph from active ratings for comparison
- `GET /account-ratings/trust-snapshots` exposes the raw stored rows directly
- `GET /account-ratings/trust-explanation` explains one known account's active
  trust status with the stored snapshot, configured thresholds and caps,
  Suspicious requirements, level checks, and top positive or negative rating
  impacts; `live=true` recalculates the current graph for comparison
- `GET /account-ratings/trust-profile` returns one known account's active trust
  status, vote-weight multiplier, minting trust allowance, seed membership,
  stored snapshot metadata, and per-category trust/rating-count summary in one
  compact response
- `GET /account-ratings/trust-policy` returns the active chain-configured trust
  policy, including seed-energy flow settings, Suspicious requirements, status
  vote multipliers, category thresholds, and per-rating caps
- missing Subject snapshots are treated as Unverified for active weight and
  mint-eligibility calculations

The current enforcement layer uses the stored Subject snapshot for active
voting, resource-rating weights, and Suspicious mint blocking:

- account info includes one active Subject trust status, snapshot height and
  timestamp, vote multiplier, and effective vote weight
- account-rating trust profile and explanation APIs show the active Subject
  status and evaluator impacts without a separate manual-status comparison
- open poll vote totals and vote details use the derived Subject status as the
  active Gold, Silver, Bronze, Unverified, or Suspicious multiplier
- frozen poll results store the active derived Subject status and vote weight
  at the closing block, so closed results stay anchored to the exact close-time
  data that was frozen
- resource-rating summaries and rating distributions use derived Subject
  weights for active weighted totals and averages
- minting eligibility uses derived Subject status alongside minting-group
  membership, so derived Suspicious blocks minting and all other derived
  statuses allow minting if the account is in the minting group

This means a stored Subject snapshot change affects open poll tallies and
resource-rating weighted summaries immediately, and a derived Suspicious
Subject snapshot prevents the account from minting. Polls with an end time stop
accepting votes at the closing block, and Qortium stores a frozen tally snapshot
at that block so later derived-trust or `blocksMinted` changes do not move the
closed result.

## Why This Fits Qortium

This approach reuses the parts of Qortal/Qortium that already exist:

- `Account.canMint(...)` already gates minting through configured minting-group
  membership.
- `Block.increaseAccountLevels()` already increments `blocksMinted` for valid
  minting participants.
- poll vote APIs and repository queries already aggregate vote weight from
  `blocksMinted`.

The active enforcement model is intentionally small in concept:

- keep the minting group as the community's explicit admission control
- derive one active Subject trust status for each account from on-chain ratings
- block Suspicious accounts from minting
- multiply raw `blocksMinted` by active Subject trust status when votes are
  tallied

That gives Qortium a way to reduce farm-account governance influence without
turning every duplicate-account question into a public investigation.

## Consensus Boundary

Consensus code must not depend on live BrightID, Aura, or other external
services during block validation. Every node must be able to reach the same
answer from deterministic chain data and local state derived from chain data.

For that reason, the preferred Qortium direction is a native on-chain trust
graph, not live external lookups, trusted imports, or authority-controlled
status updates. The trust profile, explanation, and derivation APIs remain
useful so the community can inspect graph behavior, including farm-ring
behavior, before any further derived-status rule affects minting or broader
consensus behavior.

## Implementation Path

1. Add a Qortium account trust status model with values for Gold, Silver,
   Bronze, Unverified, and Suspicious.
2. Add a vote-weight helper that converts raw `blocksMinted` to effective vote
   weight using the current trust multiplier.
3. Update poll vote aggregation to use effective vote weight instead of raw
   `blocksMinted`.
4. Expose read-only audit fields on account and voting responses so users can
   see the raw and effective weights.
5. Add tests for mint eligibility, vote weighting, audit fields, and
   trust-status changes.
6. Add read-only decentralized trust APIs that summarize active account-rating
   evidence and explain the derived graph.
7. Store the derived trust graph as block-anchored repository state.
8. Use the stored Subject snapshot for active poll vote weights, frozen poll
    close-time weights, and resource-rating weighted summaries.
9. Use the stored Subject snapshot for Suspicious mint blocking while keeping
    minting-group membership as the base permission.
10. Move trust derivation thresholds, per-rating caps, Suspicious requirements,
    Manager energy-flow settings, active weighting category, and vote
    multipliers into chain configuration so derived chains can tune the policy
    without code changes.
11. Add a read-only trust explanation endpoint that shows the stored active
    status, policy requirements, threshold/cap checks, and top rating impacts
    for one account, with optional live recalculation for comparison.
12. Remove the older manual account trust-status column and comparison fields
    so stored Subject snapshots are the single active trust source.

## Test Scenarios

The first implementation should cover at least these cases:

- Gold, Silver, Bronze, and Unverified Subject snapshots can mint only when
  the account is in the minting group.
- Suspicious Subject snapshots cannot mint even when the account is in the
  minting group.
- missing Subject snapshots are treated as Unverified for minting eligibility.
- raw `blocksMinted` still increases for eligible minting accounts according to
  the existing block or batch reward rules.
- vote tallies apply 100%, 50%, 25%, and 0% derived Subject multipliers
  correctly.
- an Unverified account's vote is recorded but contributes zero weight.
- a Suspicious account's existing raw `blocksMinted` does not create effective
  vote weight while the account remains Suspicious.
- account and vote APIs expose the raw `blocksMinted`, trust status,
  multiplier, and effective vote weight used by the current tally.
- stored Subject snapshot changes affect open poll tallies and resource-rating
  weighted summaries immediately.
- polls can optionally close at a defined end time, after which new votes and
  vote changes are rejected and final weights are frozen.
- account trust profile, explanation, derivation, and snapshot APIs expose
  inbound and outbound confidence distributions, active derived trust status,
  evaluator impacts, raw category scores, capped level-decision scores, mapped
  trust status, seed membership, and block anchoring; the stored Subject
  snapshot now supplies active poll and resource-rating weight plus minting
  eligibility status.
- isolated positive-rating rings with no path from the minting seed set remain
  Unverified with zero category scores.
- direct one-hop Manager ratings from seed accounts do not create Manager score
  by themselves; Manager score is based on energy that remains after four
  positive Manager-rating hops.
- Manager energy splits by confidence across outgoing positive Manager paths,
  and negative Manager ratings use the same final-energy source with the
  four-times flagging multiplier.
- negative Subject ratings from Unverified or zero-Player accounts do not make
  the target Suspicious or block minting.
- a single trusted negative Subject rating records raw negative evidence but
  does not derive Suspicious status or block minting by itself.
- two independent medium-confidence negative Subject ratings from trusted
  Player-level accounts can derive Suspicious status and block minting, and
  orphaning one of those ratings restores the prior snapshot and mint
  eligibility.
- one seed member's Manager energy is budgeted across its outgoing Manager
  paths, so several positive Manager paths split the seed budget instead of
  multiplying it.
- positive category levels require enough independent capped impact to reach
  their thresholds, preventing a single high-score evaluator from assigning a
  positive level by itself.
- positive category levels also require enough independent trust branches,
  preventing same-branch positive raters from assigning a positive level by
  themselves.
- Suspicious category decisions also require enough independent capped negative
  impact to reach their thresholds, preventing a single high-score evaluator
  from assigning Suspicious status by itself.
- trust policy tests pin the threshold, cap, seed-energy, manager-hop,
  Suspicious requirement, active weighting category, and level-to-status values
  separately from full graph behavior tests.
- chain-config tests prove trust policy values are required, validated, and
  actually drive both vote multipliers and level decisions.
- account trust explanation tests prove empty known accounts remain
  Unverified, stored and live explanations are distinguishable, and single,
  same-branch, and independent-branch negative ratings report the Suspicious
  checks clearly.
