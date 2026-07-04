# Aura Trust-Tier Minting

This note records Qortium's BrightID/Aura-style trust graph design for reducing
farm-account influence without requiring constant manual policing of minters.

This document tracks the implemented trust-tier architecture and the remaining
pre-launch hardening work for Aura-style trust tiers in Qortium.

For a reader-facing explanation of the current system, see
`docs/trust/account-trust-network.md`.

For the current launch checklist, see
`docs/trust/trust-network-launch-readiness.md`.

For wallet and explorer integration guidance, see
`docs/trust/trust-network-client-integration.md`.

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
BrightID's trust graph, but it does not directly provide Qortium's Gold,
Silver, Bronze, Suspicious, and Unverified tiers.

Aura is the closest match for Qortium's needs. BrightID's app models Aura as a
verification with a `score` and `level`, and the Aura explorer displays those
levels as Gold, Silver, Bronze, Suspicious, or Unverified. Qortium uses that
tiered status idea without copying BrightID meetings, BrightID's full app flow,
or Bitu's score rules.

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
active edge for that target. Those active edges feed the deterministic
Qortium-native trust derivation described below.

## Active Rule

Minting-group membership remains the base permission. Aura-style status then
modifies what that account can earn or how much its earned history counts.

| Trust status | Minting effect | Voting multiplier |
| --- | --- | --- |
| Gold | Can mint if in the minting group | 100% |
| Silver | Can mint if in the minting group | 70% |
| Bronze | Can mint if in the minting group | 40% |
| Unverified | Can mint if in the minting group | 0% |
| Suspicious | Cannot mint, even if in the minting group | 0% |

This keeps `blocksMinted` as the familiar accumulated work signal. The
multiplier is applied when calculating effective voting weight. For example:

- a Gold account with 10,000 `blocksMinted` votes with 10,000 effective weight
- a Silver account with 10,000 `blocksMinted` votes with 7,000 effective weight
- a Bronze account with 10,000 `blocksMinted` votes with 4,000 effective weight
- an Unverified account can cast a vote, but contributes zero effective weight
- a Suspicious account is blocked from minting and contributes zero effective
  weight

The important distinction is that raw `blocksMinted` remains an account-history
counter. Qortium can then expose both raw `blocksMinted` and effective vote
weight so users can understand why a vote has the weight it has.

## Current Implementation Choices

The implementation keeps the deterministic trust-status and vote-weight
foundation entirely inside Qortium consensus state:

- active trust status comes from the stored Subject trust snapshot
- accounts without a Subject snapshot are treated as Unverified
- accounts with a Suspicious Subject snapshot cannot mint, even if they remain
  in the minting group
- open poll vote tallies use the active Subject snapshot at tally time
- frozen poll results store the active Subject snapshot and weight at close
  time
- account, poll-vote, and resource-rating responses expose read-only fields
  so raw `blocksMinted`, trust status, multiplier, and effective vote weight
  are visible without a separate manual account status

Directed account ratings are native chain data:

- accounts can rate known public-key accounts with Aura-style signed confidence
  values from `-4` through `4`
- rating `0` clears the rater's active edge for that target
- repeated changes or removals on the same rater, target, and category edge are
  limited by the chain-configured rating-change cooldown
- the cooldown is per edge, so one rater can still rate other targets and other
  raters are not delayed by someone else's rating
- during the online-account capture and reward-distribution blocks used for
  batch rewards, account-rating changes remain pending until the protected
  reward window ends
- batched rewards use distribution-height account state by design: the captured
  online self-share set decides who is eligible for the batch, while account
  levels, share-bin membership, minting-group membership, trust-derived minting
  status, and payout reward-share rows are read at the distribution block and
  applied to the whole batch window
- `GET /account-ratings/cooldown` lets clients show the latest change height,
  earliest allowed height, remaining block count, and whether the edge can be
  changed in the next block
- `GET /account-ratings/preview` lets clients show the validation result and
  live trust impact of a proposed rating or removal before the user signs it,
  without changing active ratings or stored snapshots
- account-rating summaries expose positive and negative confidence counts
- those active edges feed the stored trust snapshots used for minting,
  voting, and resource-rating weight

Qortium now has deterministic decentralized trust derivation APIs:

- the derived graph uses only active on-chain `RATE_ACCOUNT` edges
- the current derived graph can be listed through
  `GET /account-ratings/trust-derivation`, which lets clients filter by final
  derived status, minting seed membership, and category level without fetching
  one account at a time
- account ratings are category-aware, using Aura-style Subject, Player,
  Trainer, and Manager categories
- current minting-group members are the decentralized seed set for derivation
  instead of Aura's hardcoded team-owner seed
- this Minting group seed set is intentional for Qortium's trusted-seed launch
  model, where early minting membership is expected to be socially trusted
  rather than open permissionless membership from day one
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
  requirements, seed energy, manager hop count, account-rating change cooldown,
  active Subject weighting category, and vote multipliers are chain-configurable
  through `accountTrustSettings`
- the Subject level is mapped back to Qortium's simple Gold, Silver, Bronze,
  Unverified, and Suspicious statuses as a derived status
- the older inbound/outbound confidence counts, mutual positive relationships,
  and evaluator impacts are still exposed for audit context
- the graph is exposed for audit and the stored Subject snapshot is used for
  active voting, resource-rating weights, and Suspicious mint blocking

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
  status, vote-weight multiplier, minting trust allowance, seed membership, raw
  `blocksMinted`, effective trust-weighted vote weight, stored snapshot
  metadata, and per-category trust/rating-count summary in one compact response
- `GET /account-ratings/trust-policy` returns the active chain-configured trust
  policy, including seed-energy flow settings, Suspicious requirements, status
  vote multipliers, category thresholds, per-rating caps, and the rating-change
  cooldown
- `GET /account-ratings/cooldown` returns the current cooldown status for one
  rater, target, and category edge
- `GET /account-ratings/preview` compares one candidate rating or removal
  against the current live derivation using an in-memory overlay
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

This approach reuses the inherited core pieces that already exist:

- `Account.canMint(...)` already gates minting through configured minting-group
  membership.
- `Block.increaseAccountLevels()` already increments `blocksMinted` for valid
  minting participants.
- poll vote APIs and repository queries aggregate effective trust-weighted
  vote weight from raw `blocksMinted`.

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
behavior, while keeping the consensus-critical answer derived from local chain
state.

## Implementation Status

The core trust-network mechanics are implemented:

- Qortium has account trust statuses for Gold, Silver, Bronze, Unverified, and
  Suspicious.
- raw `blocksMinted` is converted to effective voting and rating weight through
  the active Subject trust multiplier.
- poll vote aggregation, frozen poll results, resource-rating summaries, and
  trust summaries use effective trust-weighted values where governance
  influence matters.
- account, voting, rating, and trust APIs expose raw and effective values so
  clients can audit the calculation.
- account ratings, trust snapshots, trust derivation, trust profile, trust
  explanation, trust policy, and trust summary APIs are available.
- the trust summary API includes snapshot completeness, active rating counts,
  and latest trust-status change metadata for explorer and admin health views.
- stored Subject snapshots are the single active trust source for minting
  trust allowance and weight multipliers.
- stored trust snapshots and trust-status change history have lifecycle
  coverage for initial population, refreshes, orphan cleanup, replacement, and
  repository reopen persistence.
- trust derivation thresholds, per-rating caps, branch requirements, Suspicious
  requirements, Manager energy-flow settings, account-rating change cooldown,
  active weighting category, and vote multipliers are chain-configurable.
- test-only permissive, current, and strict policy calibration scenarios show
  how the same evidence changes under different threshold, branch, and
  Suspicious-confidence choices. These profiles are examples for launch review,
  not new Qortium defaults.
- transaction-level calibration scenarios prove the visible account-rating
  evidence behaves the same way when it is submitted through signed
  `RATE_ACCOUNT` transactions, minted blocks, stored snapshot refreshes, rating
  removals, orphan handling, and minting eligibility checks.
- launch-profile trust-review scenarios show how the default policy handles
  no-evidence minting members, one trusted supporter, two independent
  supporters, same-branch evidence, Suspicious mint blocking, and removal-based
  recovery without changing the active defaults.
- signed honest onboarding scenarios prove no-evidence Minting group members,
  one-supporter audit evidence, Bronze, Silver, Gold, cooldown-gated support
  removal, and trust profile/explanation API reporting through the transaction
  path.
- account-rating impact preview tests prove valid candidates, removals, no-op
  ratings, unknown targets, cooldown blocks, and non-Subject categories are
  reported without mutating active ratings or stored snapshots.
- launch stress scenarios show how the current policy behaves when trusted
  supporters rate many accounts, mixed onboarding batches reach different
  tiers, support is removed after cooldown, Suspicious status recovers, and
  extra Minting group seed members dilute support outcomes.
- launch transition scenarios prove seed-set changes, category-chain breaks,
  evaluator-category trust loss, recovery, and block orphaning move stored
  snapshots and status-change history as expected.
- signed adversarial launch scenarios prove isolated farm rings, same-branch
  evidence, independent Suspicious blocking, cooldown blocking, and
  cooldown-based recovery through the transaction path.
- the current default launch trust policy is documented in the account trust
  network guide and pinned by tests so threshold, cap, branch, confidence, seed
  energy, hop count, and vote-multiplier drift is visible.

Remaining pre-launch work is now hardening rather than core construction:

- use `docs/trust/trust-network-launch-readiness.md` as the current checklist for
  launch verification and open policy review
- review the launch trust profile against any new realistic graph scenarios
  that are not covered by the current launch-profile tests
- treat the latest medium and large static and churn benchmark review as close
  to the documented baseline, with no immediate trust-derivation optimization
  currently required before launch
- keep reviewing trust-graph benchmark output and decide whether larger stress
  profiles or additional account-rating churn controls are needed if future
  assumptions change
- revisit seed eligibility if a future launch model opens Minting group
  membership beyond the current trusted-seed assumption
- keep docs aligned as launch policy is reviewed

## Test Coverage

Current trust-network coverage includes these cases:

- Gold, Silver, Bronze, and Unverified Subject snapshots can mint only when
  the account is in the minting group.
- Suspicious Subject snapshots cannot mint even when the account is in the
  minting group.
- missing Subject snapshots are treated as Unverified for minting eligibility.
- raw `blocksMinted` still increases for eligible minting accounts according to
  the existing block or batch reward rules.
- vote tallies apply 100%, 70%, 40%, and 0% derived Subject multipliers
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
- account-rating cooldown tests prove same-edge changes and removals are delayed
  by policy, while first ratings, different targets, different categories,
  disabled cooldowns, allowed later changes, and orphan rollback behave
  correctly.
- account trust explanation tests prove empty known accounts remain
  Unverified, stored and live explanations are distinguishable, and single,
  same-branch, and independent-branch negative ratings report the Suspicious
  checks clearly.
- trust explanation API coverage proves account trust screens can inspect
  configured thresholds and caps, branch and confidence requirements, and the
  strongest positive and negative impacts behind the current result.
- launch-profile scenario tests prove no-evidence minting members can mint with
  zero effective vote weight, one trusted Subject supporter leaves only audit
  score, two independent trusted supporters can create Gold weight,
  same-branch evidence cannot lift or block by itself, and removing one
  independent negative rating restores minting from Suspicious.
- signed honest onboarding scenario tests prove the ordinary transaction path:
  no-evidence Minting group members have zero vote weight, trusted support can
  reach Bronze, Silver, and Gold, removing support is cooldown-gated, and trust
  profile/explanation APIs show the current result.
- launch stress scenario tests prove multi-target support behavior, mixed
  onboarding tier spread, cooldown-gated support removal, Suspicious recovery,
  and Minting group seed dilution under the current launch defaults.
- launch transition scenario tests prove Minting group seed changes,
  Manager-to-Subject cascade breaks and recovery, evaluator-category trust loss,
  and signed support-removal orphaning.
- signed adversarial scenario tests prove the same launch safety assumptions
  through block processing, trust snapshot refreshes, status-change history, and
  account-rating cooldown API reporting.
