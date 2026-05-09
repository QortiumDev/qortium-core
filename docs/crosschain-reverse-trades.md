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
- the maker commits a secret hash in the offer AT
- the taker reserves the offer by supplying a foreign-chain redeem key
- the maker creates and funds a foreign-chain HTLC for that specific taker
- the taker verifies the funded HTLC before locking local assets into the AT
- the maker claims the local-chain asset by revealing the secret on Qortium
- the taker uses the revealed secret to redeem the foreign-chain HTLC

This should be implemented as a new ACCT version, not by expanding v4's state
machine. The trade direction changes who escrows first, which chain confirms
first, and which party is protected by each timeout. Keeping it separate makes
the protocol easier to audit.

## Implemented single-fill flow

1. Maker publishes a `SELL_FOREIGN` offer with foreign chain, foreign amount,
   requested local asset id, requested local amount, timeout policy, and the
   maker's foreign refund key and secret hash.
2. The maker's trade bot deploys a `BitcoinyACCTv5` AT with zero initial local
   asset funding and a native fee reserve for AT execution.
3. Taker responds with their local-chain public key and foreign receiving
   address.
4. The response API creates taker trade-bot state and returns an unsigned
   zero-payment reservation `MESSAGE` transaction containing the taker's
   foreign redeem key.
5. The taker signs and broadcasts the reservation transaction.
6. The AT records the reservation sender and taker foreign redeem key, then
   becomes non-fillable by other takers.
7. Maker sees the reservation, builds and funds a foreign-chain HTLC using the
   maker secret hash and the taker's redeem key, then declares the HTLC locktime
   to the AT. If the maker cannot fund and declare the HTLC before the reservation
   expires, the maker trade bot cancels the AT.
8. Taker verifies the declared HTLC is actually funded and still has enough
   timeout remaining, then calls `/crosschain/tradebot/locklocal` to build the
   unsigned local asset lock transaction.
9. The taker signs and broadcasts that local lock transaction. The AT accepts
   only the reserved taker, configured local asset, and exact local amount.
10. Maker submits the secret to the AT and receives the locked local-chain
   asset. That reveal is public on Qortium.
11. Taker reads the revealed secret and redeems the foreign-chain HTLC.
12. If the maker never reveals the secret, the AT refunds the local asset to the
   taker before the maker's foreign HTLC refund time.

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
- The maker's foreign HTLC timeout must be longer than the local AT refund
  window plus the configured safety margin so the taker has time to redeem after
  the maker reveals the secret.
- The maker trade address can cancel an offer while it is `OFFERING`,
  `RESERVED`, or `FOREIGN_LOCKED`, but cancellation is rejected after the taker
  has locked local assets and the AT enters `TRADING`.
- Refund and redeem paths need separate tests for native asset and issued
  assets, because native balance also pays AT execution fees.

## API shape

- `/crosschain/tradebot/create` defaults to `SELL_LOCAL`. A reverse offer sets
  `tradeDirection` to `SELL_FOREIGN`, passes a local-chain receiving address in
  `receivingAddress`, passes the maker foreign wallet key in `foreignKey`, uses
  `fundingLocalAmount = 0`, provides a positive `nativeFeeReserve`, and uses a
  `tradeTimeout` of at least 120 minutes.
- `/crosschain/tradebot/respond` uses the AT's parsed `tradeDirection` to choose
  the response path. For `SELL_FOREIGN`, `responderPublicKey` is required,
  `receivingAddress` is the taker's foreign-chain receiving address, and the
  response is Base58-encoded unsigned reservation transaction bytes rather than
  the `"true"`/`"false"` result used by the existing `SELL_LOCAL` bot path.
- `/crosschain/tradebot/locklocal` is the second taker-side signing step for
  `SELL_FOREIGN`. It returns Base58-encoded unsigned local asset lock
  transaction bytes only after the maker's foreign HTLC has been declared,
  verified as funded, and checked for timeout safety. The safety check requires
  the foreign locktime to leave the local refund window plus 30 minutes.
- `BitcoinyACCTv4` remains the latest default Bitcoiny ACCT for ordinary
  `SELL_LOCAL` split-fill offers. `BitcoinyACCTv5` is selected explicitly for
  reverse offers.

## Current limits

- v5 reverse trades are single-fill only.
- The maker funds one foreign HTLC per taker reservation.
- A reservation expires after 30 minutes if the maker cannot fund and declare
  the foreign HTLC. The maker bot cancels the AT instead of leaving the offer
  reserved indefinitely.
- Split reverse fills should be a later ACCT version or an explicit extension,
  because each partial fill still needs an independent foreign HTLC.
- The initial taker response only reserves the offer; the caller signs and
  broadcasts the local asset lock transaction later, after foreign HTLC
  verification.
- A public reverse offer cannot lock foreign funds at offer creation because a
  BTC-like HTLC must include the taker's foreign redeem key. Private
  known-taker lock-at-create offers can be designed separately if needed.

## Feasibility already covered

The deterministic AT asset tests cover the local-chain primitives needed by this
design: reading incoming transfer asset ids and amounts, summing matching
multi-payment entries, detecting ambiguous mixed-asset payments, and paying
arbitrary spendable assets from an AT.

The reverse-trade feasibility test adds the missing protocol-specific primitive:
a later taker payment of a chosen local asset can be accepted by an AT, tied to
the payment sender, and refunded by AT logic to that same sender.
