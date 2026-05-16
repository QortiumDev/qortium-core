# Account Trust Network

Qortium uses an on-chain account trust network to reduce the influence of
farm accounts without requiring biometric identity checks, IP tracking, video
meetings, or constant manual investigations.

The trust network does not replace `blocksMinted`. Instead, it improves how
earned minting history is counted. An account still earns `blocksMinted` by
minting blocks, but its active Subject trust status decides how much of that
history counts as voting or rating weight.

## Why It Exists

The basic Qortal-style model gives more governance weight to accounts that have
minted more blocks. That works best when each minting account represents a real
participant. It becomes weaker when one operator can run many accounts, earn
`blocksMinted` on all of them, and then use those accounts as a voting bloc.

Qortium keeps the simple minting-group foundation, but adds a decentralized
trust layer:

- the Minting group remains the base permission for minting
- accounts can rate other known public-key accounts on-chain
- the chain refreshes derived trust status when ratings or the Minting group
  seed set changes
- Suspicious accounts cannot mint
- Gold, Silver, Bronze, and Unverified accounts can still mint if they are in
  the Minting group, but their earned history has different governance weight

This turns account farming into a graph problem instead of a public duplicate
account investigation.

## Account Ratings

Account ratings are native chain transactions. A rater records one active
rating for one target account in one category.

Ratings use signed confidence:

| Rating | Meaning |
| --- | --- |
| `1` through `4` | Positive confidence, from low to very high |
| `-1` through `-4` | Negative confidence, from low to very high |
| `0` | Remove the rater's active rating for that target and category |

Rating `0` is not a neutral trust signal. It means there is no active rating
edge from that rater to that target in that category.

An account can only rate a known account public key. This keeps ratings tied to
accounts the chain can identify by public key, not just by address.

## Rating Categories

Qortium uses four rating categories inspired by Aura:

| Category | Purpose |
| --- | --- |
| Manager | Moves seed trust outward from Minting group members through positive Manager paths |
| Trainer | Uses Manager trust to evaluate accounts that can pass trust into Player trust |
| Player | Uses Trainer trust to evaluate accounts that can pass trust into Subject trust |
| Subject | Produces the final account trust status used by minting, voting, and resource-rating weight |

The active enforcement status comes from the stored Subject snapshot. The other
categories help decide who has enough graph position to influence the next
layer.

## How Trust Is Derived

The trust graph is deterministic chain state. It does not depend on BrightID,
Aura servers, biometrics, meetings, or any live external service.

When account ratings or the Minting group seed set changes, Qortium refreshes
stored trust snapshots from the current on-chain ratings and Minting group
membership:

- current Minting group members are the seed set
- Manager energy starts from that seed set
- positive Manager ratings move Manager energy through the graph for the
  configured number of hops
- final Manager score is used to score Trainer ratings
- Trainer score is used to score Player ratings
- Player score is used to score Subject ratings
- Subject level maps to Gold, Silver, Bronze, Unverified, or Suspicious

Raw scores remain visible for audit. Level decisions use configured caps so
one evaluator cannot assign a positive level by itself. Suspicious decisions
also require enough independent negative raters at the configured confidence.

If a trust-changing block is orphaned, the trust snapshot is refreshed back to
the previous chain state.

## Trust Status Effects

The default Qortium policy maps active Subject status to minting and voting
effects like this:

| Trust status | Minting effect | Voting/resource-rating multiplier |
| --- | --- | --- |
| Gold | Can mint if in the Minting group | 100% |
| Silver | Can mint if in the Minting group | 50% |
| Bronze | Can mint if in the Minting group | 25% |
| Unverified | Can mint if in the Minting group | 0% |
| Suspicious | Cannot mint, even if in the Minting group | 0% |

Missing Subject snapshots are treated as Unverified.

The multiplier applies to raw `blocksMinted` when Qortium calculates effective
vote weight or weighted resource-rating influence. For example, an account
with 10,000 `blocksMinted` has 10,000 effective weight as Gold, 5,000 as
Silver, 2,500 as Bronze, and 0 as Unverified or Suspicious.

Raw `blocksMinted` stays visible because it is still useful account-history
data. Effective weight is the value used for active governance and weighted
rating calculations.

## Polls And Frozen Results

Open poll results use the current active Subject snapshot and current
`blocksMinted` values when they are tallied.

Polls can also have an end time. When an ended poll closes, Qortium freezes the
final vote weights and vote details at the closing block. Later changes to
ratings, trust status, or `blocksMinted` do not move the closed result.

This keeps open polls responsive to current trust state while making closed
polls stable and auditable.

## Client API Guide

Clients should use different account-rating APIs for different jobs:

| Endpoint | Use |
| --- | --- |
| `GET /account-ratings/trust-profile` | Primary account trust display for one account |
| `GET /account-ratings/trust-explanation` | Detailed explanation of why one account has its current status |
| `GET /account-ratings/trust-policy` | Chain-configured thresholds, caps, seed settings, Suspicious rules, and vote multipliers |
| `GET /account-ratings/trust-derivation` | Network-wide stored or live derived trust rows |
| `GET /account-ratings/trust-snapshots` | Raw stored per-account, per-category snapshot rows |
| `GET /account-ratings` | Active directed account ratings, with target, rater, and category filters |
| `GET /account-ratings/summary` | Inbound rating counts for one target account |

For normal account screens, `trust-profile` should be the first choice. It
summarizes the active status, vote multiplier, minting trust allowance, seed
membership, snapshot metadata, and per-category rating counts in one response.

For diagnostic screens, pair `trust-explanation` with `trust-policy`. The
explanation shows one account's stored or live evidence; the policy endpoint
shows the chain rules used to interpret that evidence.

## Chain Builder Configuration

Qortium-derived chains can tune the trust network through
`accountTrustSettings` in the blockchain config.

The configurable policy includes:

- the active category used for voting and minting weight
- the starting Manager energy
- the number of Manager energy hops
- Gold, Silver, Bronze, Unverified, and Suspicious vote multipliers
- per-category level thresholds
- per-category per-rating caps
- per-category Suspicious thresholds and caps
- the minimum independent negative raters and confidence needed for Suspicious

Client software should read `GET /account-ratings/trust-policy` instead of
hardcoding Qortium's default values.

## Practical Rating Guidance

A positive rating should mean the rater is comfortable giving that target some
trust in the selected category. Higher confidence means stronger support.

A negative rating should mean the rater has reason to reduce that target's
trust in the selected category. Higher negative confidence is stronger
evidence, but Suspicious status still requires enough independent negative
evidence under the chain policy.

Use rating `0` when a previous rating should be removed. This is the right
choice when the rater no longer wants to express an active trust opinion.

The trust network is meant to reduce farm-account influence while keeping
decisions inspectable from chain data. It is not meant to prove human identity,
and it does not require users to reveal biometric, IP, or video-meeting data.
