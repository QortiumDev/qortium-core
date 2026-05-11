# Foreign/Foreign Trade Design

This note records the intended direction for BTC-like foreign/foreign atomic
swaps. It is groundwork only; no user-facing foreign/foreign trade type is
enabled yet.

## Target flow

The first implementation should be single-fill only:

1. Maker publishes a Qortium-coordinated offer selling foreign coin A for
   foreign coin B.
2. Maker commits `hash(secret)` in the offer metadata.
3. Taker reserves the offer and supplies their coin A redeem key plus their
   coin B refund key.
4. Maker creates and funds the coin A HTLC:
   - refund path: maker coin A key after maker locktime
   - redeem path: taker coin A key plus `secret`
5. Taker verifies the coin A HTLC is funded, has the expected amount, and has
   enough timeout remaining.
6. Taker creates and funds the coin B HTLC:
   - refund path: taker coin B key after taker locktime
   - redeem path: maker coin B key plus `secret`
7. Maker verifies the coin B HTLC, redeems it, and reveals `secret` on coin B.
8. Taker observes `secret` and redeems the coin A HTLC.
9. If either side fails before redeeming, each party refunds their own funded
   HTLC after its locktime.

The maker's refund locktime must be later than the taker's refund locktime by a
configured safety margin. That gives the taker time to learn `secret` from the
maker's coin B redeem transaction and redeem coin A before the maker can refund
coin A.

## Qortium coordination

Qortium should remain the coordination layer even though neither traded asset is
escrowed locally. The local chain should provide:

- public offer discovery
- reservation and cancellation state
- funded-HTLC declarations for both foreign chains
- trade-bot state transitions and recovery after restart
- optional publication of the revealed secret when useful for recovery
- spam cost through normal local-chain transactions

The initial ACCT should not hold local trade funds. It should only coordinate
offer state, participant keys, declared locktimes, and completion/cancel/refund
status.

## Shared HTLC support

The BTC-like implementation should reuse one helper for the common HTLC work:

- redeem-script construction and P2SH derivation
- minimum funded amount checks including P2SH fee
- HTLC status resolution
- funding, redeem, and refund transaction submission
- timeout safety checks

This keeps the future foreign/foreign trade bot from duplicating the reverse
trade logic already used by `BitcoinyACCTv5`.

## Current foundation

Foreign/foreign trades are represented as `SELL_FOREIGN_FOR_FOREIGN` but remain
inactive for users. The API/data model can carry separate offered and requested
foreign blockchains, amounts, and public-key-hash roles. The inactive
`BitcoinyForeignForeignACCTv1` now implements the local coordination state
machine for reservation, maker HTLC declaration, taker HTLC declaration, secret
reveal, and cancellation. It is intentionally not registered for ACCT lookup,
and the public tradebot API still rejects this direction until the trade-bot can
verify and drive the full foreign-chain HTLC flow.

The direct internal `BitcoinyForeignForeignTradeBot` path can now build maker
DEPLOY_AT transactions and submit taker reservation MESSAGE transactions from
the generated taker trade key. Those paths validate supported BTC-like chain
pairs, positive amounts, minimum timeout, maker/taker wallet keys, and P2PKH
receiving addresses, then persist trade-bot state with separate
offered/requested chain fields only after the reservation message is accepted.
The internal maker-side progress path now waits for AT confirmation,
accepts a taker reservation, funds the maker offered-chain HTLC, and declares the
maker locktime to the coordination AT. The internal taker-side progress path can
now verify the maker's offered-chain HTLC, derive a requested-chain locktime with
the required safety margin before the maker refund, fund the requested-chain
HTLC, avoid rebroadcasting while funding is already in progress, and declare the
taker locktime to the coordination AT. The internal maker completion path can now
verify the taker requested-chain HTLC, redeem it with the maker secret, wait
while redeem is still in progress, and reveal the secret to the coordination AT
only after the requested-chain redeem is confirmed. The internal taker completion
path can now recover the secret from either the Qortium coordination AT or the
requested-chain HTLC redeem transaction, validate it, redeem the maker
offered-chain HTLC, and finish the taker entry. If the maker has declared an
offered-chain HTLC but the taker never reaches the requested-chain HTLC stage,
the maker bot now waits until the offered-chain refund locktime is usable,
submits or recognizes the offered-chain refund, cancels the coordination AT, and
only then marks the maker entry refunded. If the taker declares a requested-chain
HTLC but the maker never redeems it or reveals the secret, the coordination AT
now times out of `TRADING` as refunded, and the taker bot waits until the
requested-chain refund locktime is usable before submitting or recognizing the
requested-chain refund and marking the taker entry refunded. Trade-bot
persistence can store
separate offered/requested foreign-chain wallet data, amounts, locktimes, and
receiving account info, and the shared Bitcoiny HTLC helper can build scripts
from explicit refund/redeem roles instead of only from local/foreign trade data.
Confirmed BTC-like HTLC redeem transactions can now be scanned through the
current provider transaction path to recover the 32-byte secret, which is needed
if the maker reveals the secret on the requested foreign chain before posting it
back to Qortium.

## Out of scope for the first pass

- split fills
- ARRR/HUSH/Zcash-family native shielded swaps
- Monero swaps
- direct peer negotiation without Qortium offer coordination
- public trade-bot/API enablement
