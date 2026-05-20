# Lite Node State Root Design

This document defines the next Phase 4 milestone for Qortium lite-node
verification. It is a design target for a later consensus implementation, not
an implementation record.

## Goal

Phase 4A lets lite nodes compare peer answers that are anchored to a chain tip.
That improves freshness checks, but the serving peers still attest to the data.

Phase 4B should let lite nodes verify selected wallet-facing data against a
state root committed into each block. A state root is a small hash that commits
to the canonical post-block state. A full node can then return a value plus a
proof path, and the lite node can recompute the same root without trusting the
peer's derived answer.

## Root Model

Qortium should start with one combined state root per block. The root is the
post-block root after all transactions, block rewards, name changes, and other
covered state changes from that block have been applied.

The first implementation should use one authenticated sparse Merkle tree:

- leaf key: `SHA-256(canonical state key)`
- leaf hash: `SHA-256(0x00 || leaf key || value hash)`
- branch hash: `SHA-256(0x01 || left child hash || right child hash)`
- empty child hash: 32 zero bytes
- root length: 32 bytes

This gives one deterministic root for all covered state categories and one
proof format for present and missing values.

## First Covered State

The first proof-bearing state root should cover the lite data already used by
wallet and name APIs:

- account data by address
- account balances by address and asset ID
- registered name data by reduced name
- name ownership index entries by owner address and reduced name

Address transaction history is deliberately excluded from the first state-root
milestone. Proving that a transaction exists is simpler than proving that an
address-history list is complete. Complete address-history proofs need a
separate committed address-transaction index design.

## Canonical Keys And Values

Every committed entry must use typed binary keys and deterministic binary
values. The encoding must not depend on SQL row order, object string output, or
JSON formatting.

Primitive encoding rules:

- integers and timestamps are signed big-endian values
- strings are UTF-8 bytes prefixed by a signed big-endian 32-bit length
- optional fields use one byte: `0x00` for absent and `0x01` followed by the
  encoded value for present
- addresses are the raw 25-byte address form used by the network messages
- public keys, signatures, and references are raw fixed-length bytes

Initial key types:

- `0x01 ACCOUNT_DATA`: key type byte, then raw address bytes
- `0x02 ACCOUNT_BALANCE`: key type byte, raw address bytes, then asset ID as
  signed 64-bit integer
- `0x03 NAME_DATA`: key type byte, then reduced name string
- `0x04 OWNER_NAME`: key type byte, raw owner address bytes, then reduced name
  string

Initial values:

- account data value: public key, default group ID, level, and blocks minted
- balance value: balance as signed big-endian 64-bit integer
- name value: name, reduced name, owner, data, registered timestamp, optional
  updated timestamp, sale status, optional sale price, optional sale recipient,
  reference, and creation group ID
- owner-name value: reduced name bytes, so the owner index can prove membership
  for a specific name

Unknown account, balance, and name answers must be proven by absence of the
corresponding key. Account-name list proofs must prove each returned
`OWNER_NAME` entry and also prove the page boundary rules chosen by the later
pagination design before the list can be called complete.

## Block Processing Rules

State-root updates are consensus state. The later implementation should update
the tree inside normal block processing and reverse those changes during orphan
handling.

Implementation requirements:

- calculate the block's state root after covered repository state changes are
  applied
- store the root in `BlockData` and block serialization
- validate imported blocks by recomputing the post-block root and comparing it
  to the committed root
- keep enough historical tree nodes to generate proofs for retained blocks
- delete or roll back tree updates when a block is orphaned
- rebuild the tree deterministically during repository rebuild/reindex flows

Because Qortium does not need compatibility with existing Qortium chains, the
block serialization change can be a clean consensus-format change instead of a
legacy fallback.

## Lite Proof Responses

Proof-bearing lite responses should be added only after full nodes compute,
validate, store, and serve committed state roots.

A proof-bearing response should include:

- response status: data or unknown
- block height, block signature, timestamp, and committed state root
- canonical state key
- canonical value bytes, or no value for an unknown proof
- sparse Merkle proof path for the key

The lite node verifies the proof by hashing the key and value, walking the proof
path, and checking that the recomputed root equals the root committed in the
anchoring block. Peer agreement remains useful for availability and chain-tip
selection, but a valid proof is what makes the returned value cryptographically
verified.

## Implementation Order

1. Add repository support for storing state-tree nodes and block roots.
2. Add canonical key/value serializers with deterministic unit tests.
3. Update block processing, orphaning, and reindex flows to maintain the tree.
4. Add the state root to block data, block serialization, and validation.
5. Add proof generation for account, balance, name, and owner-name keys.
6. Extend lite-data response messages to include proof data and local proof
   verification.

Until those steps are complete, lite APIs should continue to describe lite data
as peer-agreed and anchored, not fully verified.
