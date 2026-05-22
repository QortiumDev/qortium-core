# On-Chain Chain Parameters

Status: Chain builder reference

Date: 2026-05-22

## Purpose

Qortium can now apply a narrow set of consensus parameter changes from approved
on-chain transactions instead of requiring every operator to install a newly
built jar for each change.

This is intentionally small at first. The first supported parameter is the
height-based block reward. Fee schedules, reward split tables, trust-network
policy values, timestamp-based settings, and larger structured parameter sets
should be added only after each format and validation rule is made explicit.

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

The activation height must still be in the future when the development group
approval decision is applied. If a proposal was valid when submitted but the
approval takes too long and its activation height has already arrived, the
approval settles as invalid and the parameter is not applied.

Once approved, the repository stores the update as an overlay keyed by parameter
ID and activation height. Consensus lookups check the approved overlay first and
fall back to `blockchain.json` when no approved update applies.

## Version 1 Parameter

`BLOCK_REWARD` is parameter ID `1`.

Its value is exactly 8 bytes: a signed long integer using the same atomic amount
units as the existing block reward schedule. Negative values are invalid.

The approved block reward applies at its activation height and remains effective
until another approved `BLOCK_REWARD` update with a later activation height
overrides it.

## Public API

`GET /chain-parameters` lists the chain parameters that this node knows how to
build and explain through the public API. Version 1 only reports
`BLOCK_REWARD`.

`GET /chain-parameters/updates` lists chain-parameter proposals as readable
proposal summaries. The endpoint can filter by parameter ID, approval status,
approval group ID, activation-height range, confirmation status, limit, offset,
and reverse order.

For `BLOCK_REWARD`, each proposal summary includes the raw canonical bytes, the
decoded amount, the current group-approval status, the current yes and no vote
counts, the current approval-authority count, and whether that approved proposal
is the effective overlay at the node's current height.

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
  "activationHeight": 250000,
  "reward": "5.00000000"
}
```

The response is the raw unsigned transaction encoded in Base58. It still needs
to be signed and submitted like other raw transactions. If `fee` is omitted, the
builder uses the recommended transaction fee. Advanced callers can still provide
an explicit `fee` and optional `nonce`.

`GET /chain-parameters/block-reward/{height}` returns the effective block
reward for a height after applying any approved overlay that is active at that
height.

`GET /chain-parameters/effective/{parameterId}?height={height}` returns the
approved overlay record for callers that need the raw canonical value.

Version 1 does not expose a public generic binary-value proposal builder. Each
new supported parameter should get its own typed builder so humans and tools can
work with normal values while consensus continues to store deterministic bytes.

## Format Policy

The transaction stores canonical binary values, not JSON.

That keeps byte serialization, signatures, repository storage, and consensus
parsing deterministic. Future structured parameters should define their own
canonical binary layout before they are accepted on chain.
