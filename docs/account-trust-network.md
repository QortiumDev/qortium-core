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

The default launch policy also limits churn on the same rating edge. After a
rater changes or removes its rating for the same target and category, it must
wait 1,440 blocks before changing that same edge again. First ratings are not
delayed, and ratings for a different target or category are unaffected. Derived
chains can set `accountRatingChangeCooldownBlocks` to `0` if they want no
consensus-level cooldown.

Wallets and explorers can query `GET /account-ratings/cooldown` before building
a rating transaction. It reports the latest change height, earliest allowed
height, remaining block count, and whether the same rater, target, and category
edge can be changed in the next block.

They can also query `GET /account-ratings/preview` to show what a proposed
rating or rating removal would do before the user signs it. The preview checks
the same rating, no-op, self-rating, unknown-target, and cooldown rules as the
transaction path, then applies valid changes only in memory to compare the
current live trust result with the possible result.

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
- each first positive Manager hop out of a seed starts a trust branch that is
  carried through later positive trust
- final Manager score is used to score Trainer ratings
- Trainer score is used to score Player ratings
- Player score is used to score Subject ratings
- Subject level maps to Gold, Silver, Bronze, Unverified, or Suspicious

Raw scores remain visible for audit. Positive level decisions use configured
caps and independent trust-branch requirements so one evaluator or one
seed-derived branch cannot assign a positive level by itself. Suspicious
decisions also require enough independent negative raters and enough
independent trust branches at the configured confidence. This means accounts
from the same seed-derived branch can record evidence, but that same-branch
evidence cannot by itself lift or block another account.

If a trust-changing block is orphaned, the trust snapshot is refreshed back to
the previous chain state. Stored trust-status change history from orphaned
heights is removed with it, so explorers do not show movement that is no
longer part of the active chain.

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

Clients and chain builders should treat raw `blocksMinted` as history, not as
governance influence. Raw `blocksMinted` still appears in account records,
minting-level bookkeeping, reports, chain functions, and vote audit fields. For
polls, resource-rating summaries, and trust-summary totals, use the effective
weight fields that include the active Subject trust multiplier.

## Default Launch Trust Policy

Qortium's launch trust profile uses Subject trust as the active minting,
voting, and resource-rating weight category. Gold counts at 100%, Silver at
50%, Bronze at 25%, and Unverified or Suspicious accounts count at 0%.

The Manager seed starts with 1,000,000 energy and flows through four positive
Manager-rating hops. Positive trust levels require at least two independent
seed-derived branches. Suspicious decisions require at least two independent
negative raters, at least two independent negative branches, and at least
medium negative confidence.

| Category | Positive levels: threshold / per-rating cap | Suspicious threshold / cap |
| --- | --- | --- |
| Manager | L1: 1,000 / 500; L2: 200,000 / 100,000 | -1,000 / 500 |
| Trainer | L1: 500,000 / 250,000; L2: 1,000,000 / 500,000 | -500,000 / 250,000 |
| Player | L1: 1,000,000 / 500,000; L2: 2,000,000 / 1,000,000; L3: 3,000,000 / 1,500,000 | -1,000,000 / 500,000 |
| Subject | L1: 10,000,000 / 5,000,000; L2: 50,000,000 / 25,000,000; L3: 100,000,000 / 50,000,000; L4: 150,000,000 / 75,000,000 | -10,000,000 / 5,000,000 |

These values are pinned by tests as the current Qortium launch profile. Future
chains can still change them through `accountTrustSettings`, but changes should
be reviewed as policy choices rather than incidental config edits.

The default launch profile also uses a 1,440-block account-rating change
cooldown for each rater, target, and category edge. This slows rapid trust-edge
flipping without limiting a rater's ability to rate other accounts or
categories.

## Scale Expectations

Qortium's default test suite includes deterministic trust-graph scale and
rating-churn sanity tests. They prove that a multi-category synthetic graph can
be derived, changed, pruned, and stored with complete per-account snapshots.
It also includes signed-transaction honest onboarding scenarios for the launch
policy: no-evidence Minting group members, one-supporter audit evidence,
Bronze, Silver, and Gold progression, cooldown-gated support removal, and trust
profile and explanation API checks. Launch stress scenarios cover multi-target
support, mixed onboarding batches, support removal, Suspicious recovery, and
seed dilution as the Minting group grows. Signed adversarial scenarios cover
isolated farm rings, same-branch evidence, independent Suspicious blocking,
cooldown blocking, and cooldown-based recovery.

There is also an opt-in benchmark for larger generated graphs and rating churn:

```bash
mvn test -DskipJUnitTests=false -Dqortium.runLongTrustNetworkTests=true -Dtest=org.qortal.test.rating.AccountTrustScaleTests
```

A local benchmark run on the current implementation produced these reference
results:

| Profile | Accounts | Ratings | Snapshot rows | Derive | Refresh | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| medium | 162 | 1,704 | 648 | 17 ms | 24 ms | 41 ms |
| large | 375 | 9,910 | 1,500 | 89 ms | 86 ms | 175 ms |

These timings are machine-dependent and are not consensus limits or launch
guarantees. They are intended to give Qortium maintainers a repeatable baseline
for deciding whether larger stress profiles, additional account-rating churn
controls, or trust-derivation optimization are needed before launch.

The same opt-in command also prints churn benchmark timings. Those runs mutate
a generated trust graph across several rounds, refresh snapshots after each
round, and report total, average, and maximum refresh time so maintainers can
review whether repeated rating changes need additional launch rules.

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
| `GET /account-ratings/trust-summary` | Lightweight network-wide trust counts, health fields, rating counts, seed counts, and active vote-weight totals |
| `GET /account-ratings/trust-profile` | Primary account trust display for one account |
| `GET /account-ratings/trust-explanation` | Detailed explanation of why one account has its current status |
| `GET /account-ratings/trust-policy` | Chain-configured thresholds, caps, seed settings, rating-change cooldown, Suspicious rules, and vote multipliers |
| `GET /account-ratings/trust-derivation` | Network-wide stored or live derived trust rows |
| `GET /account-ratings/trust-snapshots` | Raw stored per-account, per-category snapshot rows |
| `GET /account-ratings/trust-changes` | Recent stored trust level and status movements for explorer and audit screens |
| `GET /account-ratings` | Active directed account ratings, with target, rater, and category filters |
| `GET /account-ratings/summary` | Inbound rating counts for one target account |
| `GET /account-ratings/cooldown` | Current cooldown status for one rater, target, and category edge |
| `GET /account-ratings/preview` | Read-only impact preview for one proposed account rating or removal |

For normal account screens, `trust-profile` should be the first choice. It
summarizes the active status, vote multiplier, minting trust allowance, seed
membership, snapshot metadata, and per-category rating counts in one response.

For explorer and dashboard screens, `trust-summary` gives a compact stored
snapshot overview without asking the client to fetch every trust row. It shows
how many stored active accounts sit in each trust status, how many are minting
seed members, how many of those seed members are still allowed to mint after
Suspicious filtering, how much raw `blocksMinted` weight exists, and how much
of that weight currently counts after trust multipliers. It also reports
snapshot completeness, active rating counts by category, and latest stored
trust-status change metadata so clients can show whether the trust graph looks
current and internally complete.

For diagnostic screens, pair `trust-explanation` with `trust-policy`. The
explanation shows one account's stored or live evidence; the policy endpoint
shows the chain rules used to interpret that evidence. Explorers that need to
show recent movement across the network should use `trust-changes` instead of
diffing full snapshot lists.

For rating transaction builders, use `preview` to show the likely trust impact
before signing, including whether the candidate rating can be submitted now.
Use `cooldown` when the UI only needs the time-based rule for one rating edge.
Neither endpoint replaces final transaction validation.

Trust explanations are intended to show why the current result happened, not
only what the result is. They include configured levels, thresholds, caps,
Suspicious thresholds, independent branch and rater requirements, confidence
requirements, and the strongest positive and negative impacts used for audit.

## Chain Builder Configuration

Qortium-derived chains can tune the trust network through
`accountTrustSettings` in the blockchain config.

The configurable policy includes:

- the active category used for voting and minting weight
- the starting Manager energy
- the number of Manager energy hops
- the minimum independent positive branches needed for positive trust levels
- Gold, Silver, Bronze, Unverified, and Suspicious vote multipliers
- the account-rating change cooldown for each rater, target, and category edge
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
