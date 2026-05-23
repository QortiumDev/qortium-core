# On-Chain Chain Parameters

Status: Chain builder reference

Date: 2026-05-22

## Purpose

Qortium can now apply a narrow set of consensus parameter changes from approved
on-chain transactions instead of requiring every operator to install a newly
built jar for each change.

This is intentionally small at first. The currently supported parameters are
the height-based block reward, the reward share-bin activation count, reward
share weights, the account rating change cooldown, trust status vote-weight
percentages, and the normal and name-registration transaction unit fees. Broader
trust-network policy values, timestamp-based settings, and larger structured
parameter sets should be added only after each format and validation rule is
made explicit.

## Approval Model

Parameter changes use the `CHAIN_PARAMETER_UPDATE` transaction type.

The transaction must use one of the development group IDs active at the block
height where it is submitted or approved. It always goes through group approval,
so a development group admin cannot bypass voting by signing the transaction
directly.

Only approved transactions affect chain behavior. Pending, expired, rejected,
or invalid proposals remain ordinary transaction history and do not create an
effective parameter overlay.

## Activation Model

Every update carries an `activationHeight`.

The activation height must leave a configured number of blocks between group
approval and activation. The main chain currently requires 1440 blocks; the
test chain uses 10 blocks so automated and local tests remain practical.

If a proposal is too close to activation when submitted, it is invalid. If it
was valid when submitted but approval takes long enough to consume the required
lead time, the approval settles as invalid and the parameter is not applied.

Once approved, the repository stores the update as an overlay keyed by parameter
ID and activation height. Consensus lookups check the approved overlay first and
fall back to `blockchain.json` when no approved update applies.

## Supported Parameters

`BLOCK_REWARD` is parameter ID `1`.

Its value is exactly 8 bytes: a signed long integer using the same atomic amount
units as the existing block reward schedule. Negative values are invalid.

The approved block reward applies at its activation height and remains effective
until another approved `BLOCK_REWARD` update with a later activation height
overrides it.

`MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN` is parameter ID `2`.

Its value is exactly 4 bytes: a signed integer count of online minters required
before a reward share bin above the floor is considered active. Negative values
are invalid.

`UNIT_FEE` is parameter ID `3`.

Its value is exactly 8 bytes: a signed long integer using the same atomic amount
units as the existing normal transaction fee schedule. Negative values are
invalid.

The approved unit fee applies to normal transaction fee validation at its
activation height and remains effective until another approved `UNIT_FEE` update
with a later activation height overrides it. Name-registration transactions keep
using their separate fee schedule.

`NAME_REGISTRATION_UNIT_FEE` is parameter ID `4`.

Its value is exactly 8 bytes: a signed long integer using the same atomic amount
units as the existing name-registration transaction fee schedule. Negative
values are invalid.

The approved name-registration unit fee applies only to `REGISTER_NAME`
transaction fee validation at its activation height and remains effective until
another approved `NAME_REGISTRATION_UNIT_FEE` update with a later activation
height overrides it. Normal transactions keep using their separate unit-fee
schedule.

`REWARD_SHARE_WEIGHTS` is parameter ID `5`.

Its value is exactly 40 bytes: ten signed integer weights for account levels 1
through 10. Negative weights are invalid, and the total weight must be greater
than zero. The level 1 weight must also be positive so the reward floor cannot
be removed by an approved weight table.

The approved reward share weights apply at their activation height, are
normalized into the active reward-share bins, and remain effective until another
approved `REWARD_SHARE_WEIGHTS` update with a later activation height overrides
them.

`ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS` is parameter ID `6`.

Its value is exactly 4 bytes: a signed integer count of blocks before the same
rater can change or remove a rating for the same target and category edge.
Negative values are invalid. A value of zero disables the cooldown.

The approved account rating cooldown applies to `RATE_ACCOUNT` transaction
validation by candidate block height, and remains effective until another
approved `ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS` update with a later activation
height overrides it.

`ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS` is parameter ID `7`.

Its value is exactly 20 bytes: five signed integer percentages ordered as
`SUSPICIOUS`, `UNVERIFIED`, `BRONZE`, `SILVER`, and `GOLD`. Each percentage
must be between 0 and 100, inclusive. At least one status must have a positive
vote-weight percentage.

The approved trust status vote weights apply to trust-weighted voting, resource
rating weights, account-trust summaries, and account-trust profile/policy API
views at their activation height. Account-trust derivation and explanation API
views also report the effective percentages for the current height. The weights
remain effective until another approved `ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS`
update with a later activation height overrides them.

## Public API

`GET /chain-parameters` lists the chain parameters that this node knows how to
build and explain through the public API. Each metadata entry includes the
current minimum activation delay that proposals for that parameter must satisfy,
plus structured validation hints for proposal builders.

Validation metadata includes numeric minimums for amount and integer
parameters. For integer-list parameters, it includes the required list length,
per-item bounds when known, ordered item labels, and flags for positive-total,
positive-first-value, or any-positive-value requirements.

`GET /chain-parameters/updates` lists chain-parameter proposals as readable
proposal summaries. The endpoint can filter by parameter ID, approval status,
approval group ID, activation-height range, confirmation status, limit, offset,
and reverse order.

For amount parameters such as `BLOCK_REWARD`, `UNIT_FEE`, and
`NAME_REGISTRATION_UNIT_FEE`, each proposal summary includes the raw canonical
bytes, the decoded amount, the current group-approval status, the current yes
and no vote counts, the current approval-authority count, and whether that
approved proposal is the effective overlay at the node's current height.

`GET /chain-parameters/effective-values?height={height}` lists every supported
parameter with the value effective at that height. Each entry says whether the
current value comes from `blockchain.json` config or from an approved on-chain
overlay, includes decoded display values, and includes the next approved future
overlay for that parameter when one is scheduled. If `height` is omitted, the
node uses its current chain height.

`POST /chain-parameters/block-reward/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the block reward. Callers provide the
reward as a normal decimal amount, and the API converts it to the canonical
8-byte value used by consensus.

Example request:

```json
{
  "timestamp": 1779451200000,
  "txGroupId": 1,
  "updaterPublicKey": "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP",
  "activationHeight": 251440,
  "reward": "5.00000000"
}
```

The response is the raw unsigned transaction encoded in Base58. It still needs
to be signed and submitted like other raw transactions. If `fee` is omitted, the
builder uses the recommended transaction fee. Advanced callers can still provide
an explicit `fee` and optional `nonce`. Choose an activation height far enough
ahead to cover both the expected approval settlement time and the configured
minimum activation delay.

`GET /chain-parameters/block-reward/{height}` returns the effective block
reward for a height after applying any approved overlay that is active at that
height.

`POST /chain-parameters/share-bin/min-accounts/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the reward share-bin activation count.
Callers provide the new integer count directly.

`GET /chain-parameters/share-bin/min-accounts/{height}` returns the effective
activation count for reward share bins at a height.

`POST /chain-parameters/reward-share-weights/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the reward share weights. Callers
provide ten integer weights for account levels 1 through 10.

`GET /chain-parameters/reward-share-weights/{height}` returns the effective
reward share weights for a height after applying any approved overlay that is
active at that height.

`POST /chain-parameters/account-rating/cooldown/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the account rating change cooldown.
Callers provide the cooldown as a block count.

`GET /chain-parameters/account-rating/cooldown/{height}` returns the effective
account rating change cooldown for a height after applying any approved overlay
that is active at that height.

`POST /chain-parameters/account-trust/status-vote-weights/update` builds an
unsigned `CHAIN_PARAMETER_UPDATE` transaction for trust status vote-weight
percentages. Callers provide five integer percentages ordered as `SUSPICIOUS`,
`UNVERIFIED`, `BRONZE`, `SILVER`, and `GOLD`.

`GET /chain-parameters/account-trust/status-vote-weights/{height}` returns the
effective trust status vote-weight percentages for a height after applying any
approved overlay that is active at that height.

`POST /chain-parameters/unit-fee/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the normal transaction unit fee. Callers
provide the fee as a normal decimal amount, and the API converts it to the
canonical 8-byte value used by consensus.

`GET /chain-parameters/unit-fee/{height}` returns the effective normal
transaction unit fee for a height after applying any approved overlay that is
active at that height.

`POST /chain-parameters/name-registration-unit-fee/update` builds an unsigned
`CHAIN_PARAMETER_UPDATE` transaction for the name-registration transaction unit
fee. Callers provide the fee as a normal decimal amount, and the API converts it
to the canonical 8-byte value used by consensus.

`GET /chain-parameters/name-registration-unit-fee/{height}` returns the
effective name-registration transaction unit fee for a height after applying any
approved overlay that is active at that height.

`GET /chain-parameters/effective/{parameterId}?height={height}` returns the
approved overlay record for callers that need the raw canonical value.

The API does not expose a public generic binary-value proposal builder. Each new
supported parameter should get its own typed builder so humans and tools can work
with normal values while consensus continues to store deterministic bytes.

## Format Policy

The transaction stores canonical binary values, not JSON.

That keeps byte serialization, signatures, repository storage, and consensus
parsing deterministic. Future structured parameters should define their own
canonical binary layout before they are accepted on chain.
