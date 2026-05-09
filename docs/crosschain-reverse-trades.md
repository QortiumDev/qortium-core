# Reverse ACCT Trade Design

This note records the direction for reverse cross-chain trades and the first
single-fill implementation.

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

## Implemented single-fill flow

1. Maker publishes a `SELL_FOREIGN` offer with foreign chain, foreign amount,
   requested local asset id, requested local amount, timeout policy, and the
   maker's foreign refund/claim details.
2. The maker's trade bot deploys a `BitcoinyACCTv5` AT with zero initial local
   asset funding and a native fee reserve for AT execution.
3. Taker responds with their local-chain public key and foreign receiving
   address.
4. The response API creates taker trade-bot state and returns an unsigned
   local-chain `MESSAGE` transaction that pays the requested local asset amount
   to the AT with the v5 lock message attached.
5. The taker signs and broadcasts that local escrow transaction.
6. The AT accepts only a single payment in the configured local asset and exact
   amount, records the payment sender as the taker, and exposes a locked state.
7. Maker sees the locked AT state and publishes a foreign-chain HTLC funded by
   the maker, refundable by the maker, and redeemable by the taker.
8. Taker redeems the foreign-chain HTLC, revealing the secret.
9. Maker submits the secret to the AT and receives the locked local-chain asset.
10. If the maker never publishes a usable foreign HTLC, the AT refunds the local
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

## API shape

- `/crosschain/tradebot/create` defaults to `SELL_LOCAL`. A reverse offer sets
  `tradeDirection` to `SELL_FOREIGN`, passes a local-chain receiving address in
  `receivingAddress`, passes the maker foreign wallet key in `foreignKey`, uses
  `fundingLocalAmount = 0`, and provides a positive `nativeFeeReserve`. The
  local API checks the maker's foreign wallet balance and subtracts this node's
  active v5 maker-side reverse offers for the same wallet key before allowing a
  new offer. If that balance cannot be determined, offer creation is rejected.
- `/crosschain/tradebot/respond` uses the AT's parsed `tradeDirection` to choose
  the response path. For `SELL_FOREIGN`, `responderPublicKey` is required,
  `receivingAddress` is the taker's foreign-chain receiving address, and the
  response is Base58-encoded unsigned local escrow transaction bytes rather than
  the `"true"`/`"false"` result used by the existing `SELL_LOCAL` bot path.
- `BitcoinyACCTv4` remains the latest default Bitcoiny ACCT for ordinary
  `SELL_LOCAL` split-fill offers. `BitcoinyACCTv5` is selected explicitly for
  reverse offers.

## Current limits

- v5 reverse trades are single-fill only.
- The maker funds one foreign HTLC per accepted taker lock.
- Split reverse fills should be a later ACCT version or an explicit extension,
  because each partial fill still needs an independent foreign HTLC.
- The initial taker response prepares the local escrow transaction locally; the
  caller is responsible for signing and broadcasting it.
- Maker foreign-balance reservation is a local API safeguard, not a consensus
  rule. A modified client can still manually deploy overcommitted reverse ATs,
  and other nodes should treat remote maker liquidity as externally verifiable
  rather than guaranteed by the Qortium chain.

## Feasibility already covered

The deterministic AT asset tests cover the local-chain primitives needed by this
design: reading incoming transfer asset ids and amounts, summing matching
multi-payment entries, detecting ambiguous mixed-asset payments, and paying
arbitrary spendable assets from an AT.

The reverse-trade feasibility test adds the missing protocol-specific primitive:
a later taker payment of a chosen local asset can be accepted by an AT, tied to
the payment sender, and refunded by AT logic to that same sender.
