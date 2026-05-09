# Reverse ACCT Trade Design

This note records the intended direction for reverse cross-chain trades before
building the next ACCT version.

## Current direction

`BitcoinyACCTv4` is a `SELL_LOCAL` trade:

- the maker deploys an AT funded with a local-chain asset
- the taker creates the foreign-chain HTLC
- the taker redeems the local-chain asset from the AT by revealing the secret
- the maker uses the revealed secret to redeem the foreign-chain HTLC

This works well for ordinary and split-fill offers where the maker is selling a
local-chain asset.

## Reverse direction

The reverse direction is `SELL_FOREIGN`:

- the maker offers foreign-chain funds
- the taker pays a local-chain asset into an AT
- the maker creates a foreign-chain HTLC for that specific taker
- the taker redeems the foreign-chain HTLC by revealing the secret
- the maker uses the revealed secret to claim the local-chain asset held by the
  AT

This should be implemented as a new ACCT version, not by expanding v4's state
machine. The trade direction changes who escrows first, which chain confirms
first, and which party is protected by each timeout. Keeping it separate makes
the protocol easier to audit.

## Proposed single-fill flow

1. Maker publishes a `SELL_FOREIGN` offer with foreign chain, foreign amount,
   requested local asset id, requested local amount, timeout policy, and the
   maker's foreign refund/claim details.
2. Taker responds with their local trade address and foreign receiving details.
3. A local-chain AT is deployed or assigned for that fill, configured with the
   requested local asset id and expected local amount.
4. Taker sends the requested local asset amount to the AT.
5. The AT accepts only a payment-like transaction that pays the configured local
   asset amount, records the payment sender as the taker, and exposes a locked
   state.
6. Maker sees the locked AT state and publishes the foreign-chain HTLC for the
   taker.
7. Taker redeems the foreign-chain HTLC, revealing the secret.
8. Maker submits the secret to the AT and receives the locked local-chain asset.
9. If the maker never publishes a usable foreign HTLC, the AT refunds the local
   asset to the recorded taker after the timeout.

The first implementation should be single-fill only. Reverse split fills should
come after this, because each partial fill likely needs its own foreign-chain
HTLC and its own local AT state.

## Important constraints

- The AT must bind the local escrow to the sender of the accepted local payment,
  not to an arbitrary later message.
- The AT must carry `localAssetId`, `localAmount`, and `tradeDirection =
  SELL_FOREIGN` through trade summaries, bot state, and API responses.
- Wrong asset ids, wrong amounts, and ambiguous multi-asset payments should not
  move the AT into a locked state.
- Maker foreign HTLC details should be verified by the taker before any local
  funds become claimable by the maker.
- Refund and redeem paths need separate tests for native asset and issued
  assets, because native balance also pays AT execution fees.

## Feasibility already covered

The deterministic AT asset tests cover the local-chain primitives needed by this
design: reading incoming transfer asset ids and amounts, summing matching
multi-payment entries, detecting ambiguous mixed-asset payments, and paying
arbitrary spendable assets from an AT.

The reverse-trade feasibility test adds the missing protocol-specific primitive:
a later taker payment of a chosen local asset can be accepted by an AT, tied to
the payment sender, and refunded by AT logic to that same sender.
