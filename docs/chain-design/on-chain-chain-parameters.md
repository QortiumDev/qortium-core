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

## Format Policy

The transaction stores canonical binary values, not JSON.

That keeps byte serialization, signatures, repository storage, and consensus
parsing deterministic. Future structured parameters should define their own
canonical binary layout before they are accepted on chain.
