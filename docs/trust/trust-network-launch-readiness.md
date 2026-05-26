# Trust Network Launch Readiness Checklist

This checklist tracks the remaining launch review for Qortium's account trust
network. It is a coordination document, not a new policy proposal.

## Current Launch Defaults

The current launch profile is:

- active trust weighting category: `SUBJECT`
- vote and resource-rating multipliers: Gold `100%`, Silver `70%`, Bronze
  `40%`, Unverified `0%`, Suspicious `0%`
- account-rating cooldown: `1,440` blocks for the same rater, target, and
  category edge
- positive trust requirement: at least two independent seed-derived branches
- Suspicious requirement: at least two independent negative raters, at least
  two independent negative branches, and at least medium negative confidence
- Manager seed energy: `1,000,000`
- Manager energy hops: `4`
- reward batch size: `100` blocks
- protected online-account and batch-distribution window: last `10` blocks of
  each reward batch
- protected transactions: `RATE_ACCOUNT`, reward-share changes, and transfer
  privilege changes wait in the mempool until the protected window ends

These values are documented in `account-trust-network.md` and pinned by tests.
Changing them should be treated as a launch policy change.

Wallet and explorer builders should use
`docs/trust/trust-network-client-integration.md` as the current integration guide.

## Launch Trust Model

Qortium's current launch target is a trusted-seed model. The Minting group is
the practical trust seed set because early minting membership is expected to be
socially trusted by the launch community, not open permissionless membership
from day one.

Under this model, no separate seed-eligibility guard is required before launch.
The trust graph does not prove the first seed accounts are honest; it uses the
accepted launch seed set to expand, weight, and audit trust after launch. If a
future chain opens minting membership more broadly, seed eligibility should be
reviewed again before treating all Minting group members as trust seeds.

## Required Verification

Before treating the trust network as launch-ready, run the focused trust and
weighting tests:

```bash
mvn test -DskipJUnitTests=false -Dtest=AccountRatingsApiTests,AddressesApiTests,PollsApiTests,TransactionsApiTests,AccountRatingCooldownTests,AccountRatingTests,AccountTrustSnapshotTests,AccountTrustOnboardingScenarioTests,AccountTrustLaunchScenarioTests,AccountTrustLaunchStressScenarioTests,AccountTrustLaunchCommunityScenarioTests,AccountTrustTransitionScenarioTests,AccountTrustAdversarialScenarioTests,AccountTrustTransactionCalibrationScenarioTests,ResourceRatingTests
```

When changing launch defaults or client-facing trust APIs, also run the expanded
trust/API review suite:

```bash
mvn test -DskipJUnitTests=false -Dtest=AccountTrustPolicyTests,AccountTrustStatusTests,AccountTrustPolicyCalibrationScenarioTests,AccountTrustLaunchPolicyTests,AccountRatingsApiTests,AccountTrustExplanationApiTests,ResourceRatingsApiTests,AddressesApiTests,PollsApiTests,AccountRatingCooldownTests,AccountRatingTests,AccountTrustBootstrapWalkthroughTests,AccountTrustGraphBehaviorTests,AccountTrustScenarioTests,AccountTrustSnapshotTests,AccountTrustOnboardingScenarioTests,AccountTrustLaunchScenarioTests,AccountTrustLaunchStressScenarioTests,AccountTrustLaunchCommunityScenarioTests,AccountTrustTransitionScenarioTests,AccountTrustAdversarialScenarioTests,AccountTrustTransactionCalibrationScenarioTests,ResourceRatingTests
```

Run the long trust-network benchmark when launch assumptions change, especially
if the expected community graph is much larger or churnier than the current
reference profiles:

```bash
mvn test -DskipJUnitTests=false -Dqortium.runLongTrustNetworkTests=true -Dtest=AccountTrustScaleTests
```

For every final trust-policy review, also run:

```bash
git diff --check
```

## Latest Local Verification

The latest local verification run was completed on 2026-05-20:

- trust policy default tests passed:
  - command: `mvn test -DskipJUnitTests=false -Dtest=AccountTrustPolicyTests,AccountTrustStatusTests,AccountTrustLaunchPolicyTests`
  - result: 37 tests passed, 0 failures, 0 errors, 0 skipped
- transaction API sync-state tests passed:
  - command: `mvn test -DskipJUnitTests=false -Dtest=TransactionsApiTests`
  - result: 16 tests passed, 0 failures, 0 errors, 0 skipped
- full readiness suite passed:
  - command: `mvn test -DskipJUnitTests=false -Dtest=AccountRatingsApiTests,AddressesApiTests,PollsApiTests,TransactionsApiTests,AccountRatingCooldownTests,AccountRatingTests,AccountTrustSnapshotTests,AccountTrustOnboardingScenarioTests,AccountTrustLaunchScenarioTests,AccountTrustLaunchStressScenarioTests,AccountTrustLaunchCommunityScenarioTests,AccountTrustTransitionScenarioTests,AccountTrustAdversarialScenarioTests,AccountTrustTransactionCalibrationScenarioTests,ResourceRatingTests`
  - result: 148 tests passed, 0 failures, 0 errors, 0 skipped
  - note: subject-only Minting group farm ratings and same-branch farm support
    remain Unverified with zero effective weight
- expanded trust/API review suite passed:
  - command: `mvn test -DskipJUnitTests=false -Dtest=AccountTrustPolicyTests,AccountTrustStatusTests,AccountTrustPolicyCalibrationScenarioTests,AccountTrustLaunchPolicyTests,AccountRatingsApiTests,AccountTrustExplanationApiTests,ResourceRatingsApiTests,AddressesApiTests,PollsApiTests,AccountRatingCooldownTests,AccountRatingTests,AccountTrustBootstrapWalkthroughTests,AccountTrustGraphBehaviorTests,AccountTrustScenarioTests,AccountTrustSnapshotTests,AccountTrustOnboardingScenarioTests,AccountTrustLaunchScenarioTests,AccountTrustLaunchStressScenarioTests,AccountTrustLaunchCommunityScenarioTests,AccountTrustTransitionScenarioTests,AccountTrustAdversarialScenarioTests,AccountTrustTransactionCalibrationScenarioTests,ResourceRatingTests`
  - result: 213 tests passed, 0 failures, 0 errors, 0 skipped
- long trust-network benchmark passed:
  - command: `mvn test -DskipJUnitTests=false -Dqortium.runLongTrustNetworkTests=true -Dtest=AccountTrustScaleTests`
  - result: 3 tests passed, 0 failures, 0 errors, 0 skipped
  - medium static graph: 162 accounts, 1,704 ratings, 648 snapshots,
    32 ms derive, 29 ms refresh, 61 ms total
  - large static graph: 375 accounts, 9,910 ratings, 1,500 snapshots,
    109 ms derive, 83 ms refresh, 192 ms total
  - medium churn graph: 4 rounds, 192 changed ratings, 96 removed ratings,
    74 ms total refresh, 18 ms average refresh, 21 ms max refresh
  - large churn graph: 4 rounds, 480 changed ratings, 240 removed ratings,
    309 ms total refresh, 77 ms average refresh, 85 ms max refresh

These are local reference measurements, not consensus limits.

## Launch Decisions To Recheck

The core trust-network implementation is in place. The remaining review should
answer these questions before launch:

- Do the current realistic graph scenarios still match the expected launch
  community shape?
- Are the latest medium and large benchmark numbers close enough to the
  documented baseline?
- Is the same-edge `1,440` block cooldown enough churn control, or do launch
  assumptions justify an additional policy?
- Do wallet and explorer clients follow
  `docs/trust/trust-network-client-integration.md` for pending account-rating
  visibility, cooldown display, and effective vote-weight display?
- Are the current `100/70/40` vote multipliers still the preferred balance
  between farm resistance and meaningful early participation?
- Does the trusted-seed launch assumption still match the expected launch
  community? A 2026-05-17 pressure run found that subject-only Minting group
  farm ratings and same-branch farm support stay Unverified, but controlled
  independent Minting group seed branches can self-promote a farm target to
  Silver under the current seed rule.

## Ready When

The trust network can be treated as launch-ready when:

- the required focused tests pass
- benchmark output does not point to an immediate derivation or storage
  optimization
- launch reviewers accept the current policy defaults
- launch reviewers accept the trusted-seed model for the first network launch
- no new realistic graph scenario exposes an unwanted Gold, Silver, Bronze,
  Unverified, or Suspicious outcome
- clients follow `docs/trust/trust-network-client-integration.md` when explaining
  active trust status, pending rating timing, cooldowns, and effective vote
  weight without relying on raw `blocksMinted` alone
- `account-trust-network.md`, `aura-trust-tier-minting.md`, and this checklist
  agree on the current launch profile
