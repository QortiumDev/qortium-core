# Trust Network Client Integration Guide

This guide is for wallet, explorer, and dashboard builders that need to show
Qortium's account trust network clearly. It documents the current client-facing
workflow only. It does not add new consensus rules or API surfaces.

## Display Rules

Clients should treat raw `blocksMinted` as account history, not active voting
power. Active governance and resource-rating influence comes from effective
trust-weighted vote weight:

- Gold counts `100%` of raw `blocksMinted`
- Silver counts `70%` of raw `blocksMinted`
- Bronze counts `40%` of raw `blocksMinted`
- Unverified and Suspicious count `0%`

For normal account screens, start with:

```text
GET /account-ratings/trust-profile?target=...
```

Show the account's active trust status, vote multiplier, minting trust
allowance, Minting group seed membership, snapshot height, snapshot timestamp,
and category summaries. If a screen shows `blocksMinted`, show the effective
trust-weighted value beside it so users do not mistake raw history for active
influence.

For detailed audit screens, pair:

```text
GET /account-ratings/trust-explanation?target=...
GET /account-ratings/trust-policy
```

The explanation endpoint shows the account's stored or live evidence. The
policy endpoint shows the chain rules used to interpret that evidence,
including thresholds, caps, branch requirements, Suspicious requirements,
cooldown blocks, and vote multipliers.

## Rating Workflow

A rating screen should guide users through the same steps every time:

1. Load the target account's `trust-profile`.
2. Load `trust-explanation` and `trust-policy` if the user needs to inspect the
   evidence before rating.
3. Let the user choose one category:
   - `SUBJECT` affects the final account trust used for minting, voting, and
     resource-rating weight.
   - `PLAYER` affects who can influence Subject trust.
   - `TRAINER` affects who can influence Player trust.
   - `MANAGER` affects who can carry seed trust outward from the Minting group.
4. Let the user choose a rating value:
   - `1` through `4` for positive confidence.
   - `-1` through `-4` for negative confidence.
   - `0` to remove the user's active rating in that category.
5. Preview the proposed change:

```text
GET /account-ratings/preview?target=...&rater=...&category=...&rating=...
```

6. If needed, show the same-edge cooldown:

```text
GET /account-ratings/cooldown?target=...&rater=...&category=...
```

7. Build the unsigned transaction:

```text
POST /account-ratings/rate
```

8. Check whether the unsigned transaction can confirm in the next block:

```text
POST /transactions/confirmation-timing
```

9. Sign the transaction locally or with the existing transaction signing API,
   then submit it:

```text
POST /transactions/process
```

10. After confirmation, refresh `trust-profile` and `trust-explanation`.

The preview and cooldown APIs are user-interface aids. Final transaction
validation still decides whether the rating can be accepted, and block
processing decides when stored trust snapshots change.

## Pending Ratings

`RATE_ACCOUNT` transactions can be valid but temporarily unconfirmable during
the protected online-account and batch-distribution window. A client should
show that state as waiting, not failed.

Use `POST /transactions/confirmation-timing` on the raw unsigned transaction
before signing or submitting. If the response has:

- `transactionConfirmable: true`
- `confirmableAtCandidateHeight: false`
- `delayReason: "PROTECTED_ONLINE_ACCOUNT_WINDOW"`

then the transaction is valid in principle but is waiting for the protected
window to end.

After submission, pending ratings can be shown from:

```text
GET /transactions/unconfirmed?txType=RATE_ACCOUNT&creator=...
```

Explorers can also use transaction search with unconfirmed status:

```text
GET /transactions/search?txType=RATE_ACCOUNT&confirmationStatus=UNCONFIRMED&limit=20
```

When a delayed rating confirms, refresh the target account's `trust-profile`
and `trust-explanation`. Until then, the active stored trust result has not
changed, even if `preview` showed what the future result is likely to be.

## Explorer And Dashboard Views

For network-wide status, use:

```text
GET /account-ratings/trust-summary
```

This gives compact stored counts for trust status, seed membership, minting
allowance, raw `blocksMinted` weight, effective trust-weighted vote weight,
snapshot health, active rating counts, and latest trust-status change metadata.

For recent movement, use:

```text
GET /account-ratings/trust-changes
```

For broad account browsing or audits, use:

```text
GET /account-ratings/trust-derivation
GET /account-ratings/trust-snapshots
GET /account-ratings
GET /account-ratings/summary
```

Clients should not fetch every rating or snapshot just to build ordinary
account screens. Use the summary and profile endpoints first, then drill down
only when the user asks for evidence.

## Anti-Patterns

Avoid these client behaviors:

- showing raw `blocksMinted` as active voting power without the trust
  multiplier
- hiding Unverified votes instead of showing that they currently have zero
  effective weight
- treating delayed `RATE_ACCOUNT` transactions as failed when confirmation
  timing says they are waiting for a protected window
- assuming a preview result is the confirmed stored result
- calculating trust status from raw ratings in the client instead of using
  stored snapshots, profile, explanation, or policy endpoints
- hiding Suspicious status behind Minting group membership; Suspicious accounts
  cannot mint even if they remain in the group
