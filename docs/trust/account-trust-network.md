# Account Trust Network

Qortium uses an on-chain account trust network to reduce the influence of
farm accounts without requiring biometric identity checks, IP tracking, video
meetings, or constant manual investigations.

The trust network does not replace `blocksMinted`. Instead, it improves how
earned minting history is counted. An account still earns `blocksMinted` by
minting blocks, but its active Subject trust status decides how much of that
history counts as voting or rating weight.

For the current launch checklist, see
`docs/trust/trust-network-launch-readiness.md`.

## Why It Exists

The older blocks-minted-only model gives more governance weight to accounts that have
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

## Launch Trust Model

Qortium's first launch model assumes the early trust seeds are socially trusted
launch participants. In the current implementation, current Minting group
members are the trust seed set, so early Minting group membership is also the
launch community's practical seed selection process.

The trust network does not cryptographically prove that the first seed accounts
are honest. It starts from the launch community's accepted seed set, then uses
on-chain ratings to expand, weight, audit, and reduce trust as the network
develops. If a future chain opens Minting group membership to a broader or more
permissionless process, it should review whether trust seeds still belong in
that same group or need a separate eligibility rule.

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
delayed, ratings for a different target or category are unaffected, and one
person's rating does not delay anyone else's rating. Derived chains can set
`accountRatingChangeCooldownBlocks` to `0` if they want no consensus-level
cooldown.

During the online-account capture and reward-distribution blocks used for batch
rewards, `RATE_ACCOUNT` transactions are left pending instead of being
confirmed. They can confirm again when the protected reward window ends. This
keeps trust-changing ratings from changing minting eligibility inside the same
short window that decides a batch reward.

Batched reward payouts intentionally use distribution-height account state. The
batch captures the online self-share set from the selected online-account block,
but the account levels, share bins, minting-group membership, trust-derived
minting status, and payout reward-share rows used for distribution are the values
visible at the reward-distribution block. This keeps batching simple and
deterministic, but it means mid-window account-state changes can affect the
whole batch payout.

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

These are rating layers, not the final trust statuses shown to users. Gold,
Silver, Bronze, Unverified, and Suspicious are derived from the Subject layer.

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
| Silver | Can mint if in the Minting group | 70% |
| Bronze | Can mint if in the Minting group | 40% |
| Unverified | Can mint if in the Minting group | 0% |
| Suspicious | Cannot mint, even if in the Minting group | 0% |

Missing Subject snapshots are treated as Unverified.

The multiplier applies to raw `blocksMinted` when Qortium calculates effective
vote weight or weighted resource-rating influence. For example, an account
with 10,000 `blocksMinted` has 10,000 effective weight as Gold, 7,000 as
Silver, 4,000 as Bronze, and 0 as Unverified or Suspicious.

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
70%, Bronze at 40%, and Unverified or Suspicious accounts count at 0%.

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

Qortium's launch reward profile distributes minting rewards in 100-block
batches. The last 10 blocks before each batch payout carry online-account
signatures, and trust-rating and reward-share changes wait in the mempool until
that protected window ends.

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
Launch transition scenarios cover Minting group seed-set changes,
Manager-to-Subject cascade breaks and recovery, evaluator-category trust loss,
and signed support-removal orphaning.

There is also an opt-in benchmark for larger generated graphs and rating churn:

```bash
mvn test -DskipJUnitTests=false -Dqortium.runLongTrustNetworkTests=true -Dtest=AccountTrustScaleTests
```

A local benchmark run on the current implementation produced these reference
results:

| Profile | Accounts | Ratings | Snapshot rows | Derive | Refresh | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| medium | 162 | 1,704 | 648 | 19 ms | 26 ms | 45 ms |
| large | 375 | 9,910 | 1,500 | 89 ms | 93 ms | 182 ms |

These timings are machine-dependent and are not consensus limits or launch
guarantees. They are intended to give Qortium maintainers a repeatable baseline
for deciding whether larger stress profiles, additional account-rating churn
controls, or trust-derivation optimization are needed before launch.

The same opt-in command also prints churn benchmark timings. Those runs mutate
a generated trust graph across several rounds, refresh snapshots after each
round, and report total, average, and maximum refresh time so maintainers can
review whether repeated rating changes need additional launch rules:

| Profile | Accounts | Starting ratings | Ending ratings | Rounds | Changed | Removed | Snapshot rows | Total refresh | Average refresh | Max refresh |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| medium | 162 | 1,704 | 1,608 | 4 | 192 | 96 | 648 | 77 ms | 19 ms | 24 ms |
| large | 375 | 9,910 | 9,670 | 4 | 480 | 240 | 1,500 | 292 ms | 73 ms | 83 ms |

The latest local review remains close to the previous baseline and does not
currently point to a required pre-launch trust-derivation optimization. Larger
community assumptions, new realistic graph scenarios, or future benchmark drift
should reopen that decision.

## Polls And Frozen Results

Open poll results use the current active Subject snapshot and current
`blocksMinted` values when they are tallied.

Polls can also have an end time. When an ended poll closes, Qortium freezes the
final vote weights and vote details at the closing block. Later changes to
ratings, trust status, or `blocksMinted` do not move the closed result.

This keeps open polls responsive to current trust state while making closed
polls stable and auditable.

## Client API Guide

For a practical wallet and explorer integration checklist, see
`docs/trust/trust-network-client-integration.md`.

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
membership, raw `blocksMinted`, effective trust-weighted vote weight, snapshot
metadata, and per-category rating counts in one response.

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

## Trust Rating Workflow

A wallet or explorer should treat account ratings as an informed action, not as
a simple like or dislike button. A practical flow is:

1. Load `GET /account-ratings/trust-profile?target=...` for the target account
   so the user can see the current status, vote multiplier, minting allowance,
   seed membership, and category summary.
2. If the user needs more detail, load
   `GET /account-ratings/trust-explanation?target=...` and
   `GET /account-ratings/trust-policy` so the screen can show the evidence and
   the chain policy used to interpret it.
3. Ask the user which category they want to rate:
   - `Subject` for the final account trust that affects minting, voting, and
     resource-rating weight.
   - `Player` for accounts that should be able to influence Subject trust.
   - `Trainer` for accounts that should be able to influence Player trust.
   - `Manager` for accounts that should be able to carry seed trust outward
     from the Minting group.
4. Ask for confidence. Use `1` through `4` for positive trust, `-1` through
   `-4` for negative trust, and `0` to remove the user's active rating in that
   category.
5. Call `GET /account-ratings/preview` with the rater, target, category, and
   proposed rating. This shows whether the change is currently valid and what
   the live trust impact would be if it were confirmed.
6. Call `GET /account-ratings/cooldown` if the UI needs to show the exact
   latest change height, earliest allowed height, or remaining block count for
   that rater, target, and category edge.
7. Build the unsigned transaction with `POST /account-ratings/rate`,
   optionally ask `POST /transactions/confirmation-timing` whether that raw
   transaction is waiting for a protected reward window, then sign it locally
   or with the existing transaction signing API and submit it through
   `POST /transactions/process`.
8. After confirmation, refresh `trust-profile` and `trust-explanation`. Stored
   trust snapshots update when the rating is processed in a block, so the
   before-confirmation preview is not a replacement for the confirmed result.

The cooldown applies only to the same rater, target, and category edge. A
blocked change does not stop the same rater from rating another target or
category, and it does not delay anyone else's rating. During protected
online-account and reward-distribution blocks, a valid `RATE_ACCOUNT`
transaction may remain unconfirmed until the protected window ends. That is a
timing rule, not a rejection.

The preview and cooldown APIs are user-interface aids. Final transaction
validation still decides whether the transaction can be accepted, and block
processing still decides when the stored trust graph changes.

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
