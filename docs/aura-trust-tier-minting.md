# Aura Trust-Tier Minting

This note records a proposed direction for using a BrightID/Aura-style trust
graph to reduce farm-account influence in Qortium without requiring constant
manual policing of minters.

This document tracks the first implementation and the remaining design work for
Aura-style trust tiers in Qortium.

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

## First Implementation Choices

The first implementation adds the deterministic account-status and vote-weight
foundation only:

- trust status is stored on Qortium accounts in repository state
- accounts default to Unverified
- Suspicious accounts cannot mint, even if they remain in the minting group
- vote tallies use the account's current trust status at tally time
- account, poll-vote, and app-rating responses expose read-only audit fields
  so raw `blocksMinted`, trust status, multiplier, and effective vote weight
  can be compared
- no admin API, config loader, or BrightID/Aura importer sets trust status yet

The next implementation layer adds directed account ratings as chain data:

- accounts can rate known public-key accounts with Aura-style signed confidence
  values from `-4` through `4`
- rating `0` clears the rater's active edge for that target
- account-rating summaries expose positive and negative confidence counts
- these edges do not change trust status, minting eligibility, or vote weight
  until a later deterministic trust-tier derivation rule is added

The current implementation layer adds a decentralized trust preview:

- the preview uses only active on-chain `RATE_ACCOUNT` edges
- the preview is exposed through `GET /account-ratings/trust-preview`
- account ratings are category-aware, using Aura-style Subject, Player,
  Trainer, and Manager categories
- current minting-group members are the decentralized seed set for the preview
  instead of Aura's hardcoded team-owner seed
- a seed account's positive Manager influence is divided across that account's
  outgoing positive Manager ratings by confidence, preserving the budget-like
  behavior that limits unlimited positive boosting in Aura's manager layer
- the preview derives Manager, Trainer, Player, and Subject scores in sequence:
  minting seed weight feeds Manager ratings, Manager score feeds Trainer
  ratings, Trainer score feeds Player ratings, and Player score feeds Subject
  ratings
- the Subject level is mapped back to Qortium's simple Gold, Silver, Bronze,
  Unverified, and Suspicious statuses as a preview-only derived status
- the older inbound/outbound confidence counts, mutual positive relationships,
  and stored-status evaluator impacts are still exposed for audit context
- the preview does not change stored trust status, minting eligibility, poll
  vote weight, or resource-rating weight

This means a trust-status change affects open poll tallies immediately. Polls
with an end time stop accepting votes at the closing block, and Qortium stores
a frozen tally snapshot at that block so later trust-status or `blocksMinted`
changes do not move the closed result.

## Why This Fits Qortium

This approach reuses the parts of Qortal/Qortium that already exist:

- `Account.canMint(...)` already gates minting through configured minting-group
  membership.
- `Block.increaseAccountLevels()` already increments `blocksMinted` for valid
  minting participants.
- poll vote APIs and repository queries already aggregate vote weight from
  `blocksMinted`.

The first implementation is intentionally small in concept:

- keep the minting group as the community's explicit admission control
- add a trust status for each account
- block Suspicious accounts from minting
- multiply raw `blocksMinted` by trust status when votes are tallied

That gives Qortium a way to reduce farm-account governance influence without
turning every duplicate-account question into a public investigation.

## Consensus Boundary

Consensus code must not depend on live BrightID, Aura, or other external
services during block validation. Every node must be able to reach the same
answer from deterministic chain data and local state derived from chain data.

For that reason, the preferred Qortium direction is a native on-chain trust
graph, not live external lookups, trusted imports, or authority-controlled
status updates. The preview API is intentionally non-consensus so the community
can inspect graph behavior, including farm-ring behavior, before any derived
status rule affects minting or governance.

## Implementation Sketch

1. Add a Qortium account trust status model with values for Gold, Silver,
   Bronze, Unverified, and Suspicious.
2. Persist current trust status in repository state.
3. Extend mint eligibility so Suspicious accounts fail the minting check even
   when they are still in a configured minting group.
4. Add a vote-weight helper that converts raw `blocksMinted` to effective vote
   weight using the current trust multiplier.
5. Update poll vote aggregation to use effective vote weight instead of raw
   `blocksMinted`.
6. Expose read-only audit fields on account and voting responses so users can
   see the raw and effective weights.
7. Add tests for mint eligibility, vote weighting, audit fields, and
   trust-status changes.
8. Add a read-only decentralized trust preview that summarizes active
   account-rating evidence without changing consensus state.

Later implementation steps should evaluate the preview results against real and
simulated graph behavior before planning any consensus derivation from ratings
to Gold, Silver, Bronze, Unverified, or Suspicious account status.

## Test Scenarios

The first implementation should cover at least these cases:

- Gold, Silver, Bronze, and Unverified accounts can mint only when they are in
  the minting group.
- Suspicious accounts cannot mint even when they are in the minting group.
- raw `blocksMinted` still increases for eligible minting accounts according to
  the existing block or batch reward rules.
- vote tallies apply 100%, 50%, 25%, and 0% multipliers correctly.
- an Unverified account's vote is recorded but contributes zero weight.
- a Suspicious account's existing raw `blocksMinted` does not create effective
  vote weight while the account remains Suspicious.
- account and vote APIs expose the raw `blocksMinted`, trust status,
  multiplier, and effective vote weight used by the current tally.
- trust-status changes affect open poll tallies immediately while Qortium uses
  current-status weighting.
- polls can optionally close at a defined end time, after which new votes and
  vote changes are rejected and final weights are frozen.
- account trust previews expose inbound and outbound confidence distributions,
  mutual positive relationships, evaluator impacts, positive scores, negative
  scores, and net evidence without changing stored trust status or effective
  vote weight.

## Open Decisions

- Which fully decentralized scoring rule should turn on-chain rating evidence
  into trust status, if preview behavior looks acceptable?
- At what height should a newly accepted Suspicious status begin blocking
  online-account validation and block minting?
- Should the 100%, 50%, and 25% multipliers be fixed consensus constants or
  configurable chain parameters?
