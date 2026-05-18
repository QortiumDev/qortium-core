# Lite Node Proof And Anchoring Plan

This note defines the Phase 4 direction for Qortium lite-node trust. It
separates the next practical protocol improvement from the larger consensus
work needed for true cryptographic state proofs.

## Goal

Lite nodes should not have to trust an arbitrary peer's answer for wallet-facing
data. Phase 3 already reduced that risk by requiring multiple peers to agree.
Phase 4 should add a clear chain context for each answer first, then later add
real state commitments that let lite nodes verify answers cryptographically.

The important distinction is:

- anchored peer agreement means peers agree on the data and the block or chain
  tip that the data came from
- cryptographic proof means a lite node can verify the data against a root hash
  committed into a signed block

Anchored peer agreement is useful, but it is not the same as a full state proof.

## Phase 4A: Anchored Lite Responses

The first Phase 4A implementation adds anchor metadata to lite-data responses.
Each response now carries a response status, the returned data when available,
and the chain context used by the serving peer. The current anchor records the
block height, block signature, and block timestamp. If a later state root is
available, that root should also become part of the anchor.

Lite nodes now require peers to agree on both:

- the returned account, balance, name, or transaction data
- the anchor metadata for that response

This keeps lite nodes from treating stale data and current data as the same
answer. It also makes API behavior more honest: a result can be described as
peer-agreed data anchored to a particular block context, not as fully verified
state.

The lite-data capability version is now `LITE_DATA = 2` for this anchored
response format. Unknown account, balance, and name answers use anchored
response messages instead of unanchored generic unknown replies.

Phase 4A does not prove balances, account data, names, or address history.
Without a committed state root and proof path, the serving peers are still
attesting to the derived data.

## Full State Proof Direction

True verification needs a state commitment in the chain. The simplest starting
point is one combined state root committed into each block. A state root is a
small hash that represents the full canonical state after that block.

The Phase 4B state-root design is tracked in
`docs/lite-node-state-root-design.md`. That document is the implementation
target for the later consensus work that adds committed roots and proof-bearing
lite responses.

The committed state should eventually cover at least:

- account data
- account balances
- registered names
- name ownership lookup data

Address transaction history is different. Proving that one transaction exists
is easier than proving that a returned address-history list is complete. If
lite nodes need complete address-history proofs, Qortium will need a committed
address-transaction index or a different wallet history strategy.

With state roots in place, a full node can answer a lite request with:

- the requested value
- the block height and block signature
- the block's state root
- a proof path that lets the lite node recompute the same root from the value

The lite node verifies the proof locally. If the recomputed root matches the
root committed in the block, the peer could not have changed that value without
breaking the hash check.

## Expected Cost

Adding one combined state root to each block should increase serialized block
size by roughly one hash, normally 32 bytes, plus small serialization overhead.
Using several separate roots would multiply that cost by the number of roots,
so one combined root is the preferred starting design.

The larger cost is not block size. Full nodes would need to:

- update an authenticated state tree whenever block processing changes state
- recompute and verify the state root for each block
- store enough tree data to generate lite-node proofs
- handle orphaning or reorganizing the state tree alongside normal repository
  state

This should be implemented as incremental updates to changed keys, not by
rehashing the whole database for every block.

Lite-response messages will also become larger once proofs are included. Proof
size depends on the tree design and state depth, but should be expected to be
much larger than the block root itself.

## Recommended Path

1. Keep Phase 4A anchored lite responses as the current bounded protocol
   improvement. This helps freshness and chain-context checks without changing
   consensus.
2. Design the authenticated state tree as a separate consensus milestone before
   changing block serialization. The initial design is tracked in
   `docs/lite-node-state-root-design.md`.
3. Start the state-root design with one combined root, canonical state keys, and
   deterministic value serialization.
4. Add proof-bearing lite responses only after full nodes can compute, validate,
   store, and serve state proofs from committed roots.

Until the state-root milestone is complete, lite APIs and client-facing docs
should describe returned lite data as peer-agreed and anchored, not fully
cryptographically verified.
