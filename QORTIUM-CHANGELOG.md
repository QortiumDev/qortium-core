# Qortium Change Log

This is the main human-readable record of the Qortium fork effort.
It is written for non-developers first, with the goal of making each change
easy to follow without reading code.

## What Qortium Is

Qortium is the working name for a stripped-down and cleaned-up fork of Qortal
Core.

The aim is to keep the parts that are useful as a starting foundation while
reducing inherited complexity, removing project-specific assumptions, and
making the codebase easier for other teams to understand and adapt into their
own chain.

## Early Goals

- keep the history clean and easy to read
- make each logical change its own commit
- explain every meaningful change in plain language
- separate Qortium-specific direction from upstream Qortal messaging
- turn stable architectural decisions into tracked documentation over time

## How To Use This File

- update this file with every intentional Qortium commit
- use one entry per commit
- make each entry title match the commit message exactly
- keep each entry to one combined plain-language description
- keep entries understandable to non-developers
- use this file as the public narrative of the fork, alongside the technical
  git history

## Change Entries

### 2026-05-13 - i18n: translate remaining validation messages

Translated the remaining validation messages that were still written in English inside non-English language files. This keeps user-facing transaction error text more consistent across supported languages without changing validation behavior or consensus rules.

### 2026-05-13 - core: add poll end times

Added optional end times for polls so new polls can close at a defined timestamp while older and open-ended polls keep their existing behavior. Poll creation now stores the close time on chain, poll and app-rating APIs expose it, and vote transactions are rejected once the containing block reaches the poll's end time, including attempts to change an existing vote. This only stops further voting; freezing the final trust-weighted results remains the next planned step.

### 2026-05-13 - api: expose trust-tier vote audit fields

Added read-only trust-tier audit fields to account, poll-vote, and app-rating responses so users can see how raw `blocksMinted` becomes effective voting weight. Existing `totalWeight` and `voteWeight` fields continue to report trust-weighted values, while new raw totals and per-option raw weights show the original `blocksMinted` totals, and full poll-vote responses now include each voter's trust status, multiplier, raw weight, and effective weight for easier review.

### 2026-05-13 - core: add account trust-tier vote weights

Added the first account trust-tier foundation for Qortium minting and governance, along with the Aura trust-tier planning note that records the local BrightID reference repos and the practical difference between BrightID, Bitu, and Aura for this design. Accounts now have a stored trust status that defaults to Unverified, Suspicious accounts are blocked from minting even if they remain in the minting group, and poll vote weight now uses the account's current trust-tier multiplier instead of raw `blocksMinted` alone. The planning note also records the repository-only first slice, the need for deterministic accepted trust-status updates instead of live external lookups, and the later idea of poll end times that can lock vote weights when a poll closes.

### 2026-05-11 - qdn: add service capability registry

Moved QDN app-library rating service policy into a dedicated capability registry instead of keeping accepted services hidden inside the ratings API. App, website, plugin, extension, and game ratings are now handled through the same registry-backed path, unsupported service filters fail clearly, and tests cover the registry rules and app-rating API filtering.

### 2026-05-11 - core: harden QDN update authority

Made QDN auto-update authority stricter and more operator-visible. Approved update manifests must now pin the exact QDN binary transaction, mutable name/identifier fallback lookup is rejected, and `/admin/update` reports manifest approval metadata, pinned binary metadata, active development-group details, and the configured auto-update mode. Operators can choose `OFF`, `CHECK_ONLY`, `NOTIFY`, or `INSTALL`, with legacy `autoUpdateEnabled` settings still mapped for compatibility.

### 2026-05-11 - crosschain: extract ACCT registry

Separated ACCT code-hash, ACCT name, filtered ACCT map, and ACCT trade-bot lookup into a dedicated registry while keeping trade-presence and crosschain validation just as strict as before. Foreign-chain registration now stays focused on chain/network lookup, inactive ACCT versions remain unregistered, and new tests lock down the allowed ACCT mappings before later ACCT work continues.

### 2026-05-11 - gui: add native tray abstraction

Reworked system tray handling behind a safe node-tray abstraction with Linux StatusNotifierItem support, AWT fallback, and no-op behavior when the desktop tray is unavailable or tests run headless. Core notification paths no longer depend directly on AWT tray types, GUI tests now cover headless tray safety and factory fallback behavior, and settings metadata is marked internal so settings loading stays stable under MOXy.

### 2026-05-11 - api: add group search endpoint

Added a group search API backed by repository and HSQLDB search support. Clients can now search group names and descriptions, page through results, reverse sort order, and filter by all, open, or closed groups while receiving the same member count and owner primary-name enrichment as the existing group list APIs.

### 2026-05-11 - crosschain: persist Bitcoiny Electrum server settings

Added persistent per-coin Electrum server settings for BTC-like chains. Runtime add/remove server API calls now update the active settings file before changing the live ElectrumX provider, generated server lists remain the default source, users can add custom servers, disable built-in servers, or replace defaults for a specific coin/network, and tests cover settings validation, server-list merging, and persisted add/remove behavior.

### 2026-05-11 - api: add guarded settings save endpoint

Added a restricted settings update path that can merge allowlisted changes into the active settings file, validate the result, and save it atomically. The new API rejects unknown, disallowed, or wrongly typed setting changes, reports which saved settings need a restart to fully take effect, and keeps broad settings persistence separate from later Electrum-specific server configuration work.

### 2026-05-11 - crosschain: harden foreign-foreign trade validation

Added shared foreign/foreign HTLC amount validation so maker create checks positive amounts, chain minimums, and P2SH-fee overflow consistently through direct and public API paths. Added filter helper coverage, reserved-state restart recovery coverage, and funding-amount assertions for both offered/requested HTLC funding paths.

### 2026-05-11 - crosschain: add foreign-foreign pair filters

Added exact offered/requested blockchain filters for foreign/foreign trade discovery. APIs and websockets can now distinguish directional pairs such as BTC-for-LTC from LTC-for-BTC while keeping the existing broad blockchain filter that matches either side of a foreign/foreign trade. Trade-bot state listing uses the same matching rules, the public tradebot response path now allows the maker and taker entries needed for a same-node foreign/foreign lifecycle, and tests cover broad, one-sided, exact, swapped, and local-asset-filtered offer results.

### 2026-05-11 - crosschain: guard foreign-foreign chain support

Added an explicit per-chain capability for BTC-like foreign/foreign trades so newly registered Bitcoiny chains are not automatically available for public foreign/foreign offers before their HTLC behavior is validated. Bitcoin and Litecoin are currently opted in because the deterministic foreign/foreign lifecycle and recovery coverage uses that pair, while other BTC-like chains now fail the existing invalid-criteria path until they are deliberately enabled.

### 2026-05-11 - crosschain: test foreign-foreign restart recovery

Added deterministic restart-style recovery coverage for BTC-like foreign/foreign trades. The new tests close and reopen repository scopes around persisted maker and taker trade-bot states, then verify that funding, HTLC declarations, maker redeem/secret reveal, taker redeem, and both refund paths continue without duplicate foreign-chain spends after the simulated restart boundary.

### 2026-05-11 - crosschain: test foreign-foreign public lifecycle

Added deterministic end-to-end coverage for the public foreign/foreign trade-bot path. The new test creates a BTC/LTC-style offer through the API, deploys the coordination AT, reserves it through the API, and then drives the mocked maker and taker HTLC lifecycle through funding, declarations, maker redeem, secret reveal, taker redeem, and final completion. The foreign/foreign design note and API schema wording now match the enabled single-fill public API surface.

### 2026-05-11 - crosschain: enable foreign-foreign tradebot API

Registered the foreign/foreign ACCT and trade-bot path so single-fill BTC-like foreign/foreign offers can be created and reserved through the public trade-bot API. Maker create now builds the unsigned coordination AT deployment without local-asset funding, taker respond now submits the reservation message from the generated trade key, and offer discovery can show foreign/foreign offers under either participating blockchain while keeping them out of local-asset filters and price estimates. Tests cover registry lookup, dispatcher routing, API create/respond validation, and offer filtering.

### 2026-05-10 - crosschain: submit foreign-foreign taker reservations from tradebot

Changed the internal foreign/foreign taker reservation path so the trade-bot submits the reservation MESSAGE using its generated taker trade key instead of returning an unsigned transaction for an external signer. Taker trade-bot state is now persisted only after the reservation message is accepted, matching the later autonomous AT messages that must use the same generated key. Tests cover successful reservation submission, rejected-message cleanup, and unchanged invalid-criteria handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker refund path

Added the taker-side failure-path handling for future foreign/foreign swaps. If the taker has funded and declared the requested-chain HTLC but the maker never redeems it or reveals the secret, the coordination AT now times out of trading as refunded, and the foreign/foreign trade-bot waits for the taker refund locktime before submitting or recognizing the requested-chain refund and marking the taker entry refunded. Deterministic tests cover AT timeout cleanup, waiting before refund, median-locktime safety, expired refund submission, refund-in-progress handling, and preferring a revealed secret over refund while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker refund path

Added the first internal failure-path handling for future foreign/foreign swaps. If the maker has funded and declared the offered-chain HTLC but the taker never declares a requested-chain HTLC, the foreign/foreign trade-bot now waits for the maker refund locktime, submits or recognizes the offered-chain refund, cancels the coordination AT, and only then marks the maker entry refunded. Deterministic tests cover waiting before refund, submitting an expired refund, and treating refund-in-progress as complete while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker offered-chain redeem

Added the internal taker completion path for future foreign/foreign swaps. After the maker reveals the swap secret, the foreign/foreign trade-bot can now recover the secret from either the Qortium coordination AT or the requested-chain HTLC redeem transaction, validate it against the committed hash, redeem the maker offered-chain HTLC, treat in-progress or already-redeemed offered-chain spends as complete, and finish the taker entry. Deterministic tests cover Qortium secret recovery, requested-chain secret recovery, invalid secret rejection, offered-chain redeem broadcast, redeem-in-progress idempotency, and waiting when no secret is available while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker redeem secret reveal

Added the internal maker completion path for future foreign/foreign swaps. After the taker has declared a funded requested-chain HTLC, the foreign/foreign trade-bot can now verify the requested-chain HTLC, redeem it with the maker secret, wait while redeem is in progress, reveal the secret to the coordination AT only after confirmed requested-chain redeem, and finish the maker entry once the AT records the redeem. Deterministic tests cover waiting for the taker HTLC, maker redeem broadcast, redeem-in-progress idempotency, delayed secret reveal, confirmed secret reveal, maker completion, and unsafe taker locktime handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign taker HTLC funding

Added the internal taker-side progress path for future foreign/foreign swaps. After the maker has declared a funded offered-chain HTLC, the foreign/foreign trade-bot can now verify the maker HTLC, derive a safety-margined taker locktime, fund the requested-chain HTLC, avoid rebroadcasting while funding is already in progress, and declare the taker locktime to the coordination AT. Deterministic tests cover waiting for the maker HTLC, requested-chain funding, funding-in-progress idempotency, funded HTLC declaration, and unsafe taker locktime handling while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign maker HTLC funding

Added the internal maker-side progress path for future foreign/foreign swaps. The foreign/foreign trade-bot can now advance a maker entry after AT confirmation, wait for a taker reservation, derive and fund the maker offered-chain HTLC, declare the maker locktime to the coordination AT, cancel stale or unsafe reservations, and keep active entries protected from deletion. Deterministic tests cover AT confirmation, undeployed expiry, funding, funding-in-progress idempotency, funded HTLC declaration, stale cancellation, unsafe locktime cancellation, and delete rules while the public API route remains disabled.

### 2026-05-10 - crosschain: add foreign-foreign tradebot create groundwork

Added the internal foreign/foreign trade-bot create and reserve groundwork while keeping the public API route disabled. The direct `BitcoinyForeignForeignTradeBot` path can now build unsigned maker DEPLOY_AT transactions and taker reservation MESSAGE transactions, validate supported BTC-like chain pairs, wallet keys, P2PKH receiving addresses, positive amounts, and minimum timeout, and persist offered/requested foreign-chain trade-bot state for later HTLC-driving work. New deterministic tests cover valid maker/taker state, repository round-trips, invalid criteria, and continued public-route rejection.

### 2026-05-10 - crosschain: harden foreign HTLC secret recovery

Updated BTC-like HTLC secret recovery to use the current decoded transaction provider path instead of the older raw-transaction address scan, only accepting confirmed redeem transactions that spend the expected P2SH script and reveal a 32-byte secret. Added deterministic tests for valid redeem secrets, refunds, wrong scripts, malformed scripts, unconfirmed redeems, and trade-bot secret resolver hooks. This prepares the foreign/foreign trade-bot to recover safely if a secret is revealed on a foreign chain before it is posted back to Qortium.

### 2026-05-10 - crosschain: persist foreign-foreign trade-bot state

Added the trade-bot persistence groundwork needed for future foreign/foreign swaps. Trade-bot state can now store separate offered and requested foreign-chain blockchains, public keys, wallet keys, amounts, locktimes, and receiving account info, JSON backup/import preserves those fields, and the shared Bitcoiny HTLC helper can build scripts from explicit refund/redeem roles. Foreign/foreign trade creation remains disabled until the trade-bot flow is implemented.

### 2026-05-10 - crosschain: implement inactive foreign-foreign ACCT state machine

Implemented the local coordination bytecode for inactive `BitcoinyForeignForeignACCTv1` offers, including reservation, maker/taker HTLC declarations, timeout-margin checks, secret reveal, cancellation, local-payment refund protection, trade data extraction, and deterministic tests. The new ACCT remains unregistered and unavailable through user-facing trade creation until the trade-bot/API wiring is implemented.

### 2026-05-10 - crosschain: add inactive foreign-foreign trade foundation

Prepared the API/data shape for future BTC-like foreign/foreign swaps by adding a `SELL_FOREIGN_FOR_FOREIGN` direction, dual offered/requested foreign-chain fields, and an inactive `BitcoinyForeignForeignACCTv1`/trade-bot skeleton. The new direction is recognized but deliberately rejected by user-facing trade creation until the real single-fill protocol is implemented.

### 2026-05-10 - crosschain: prepare foreign-foreign trade groundwork

Prepared for future BTC-like foreign/foreign atomic swaps without enabling a new trade type yet. Added a design note for Qortium-coordinated foreign/foreign trades, extracted shared Bitcoiny HTLC trade-bot support for script derivation, funded amount checks, status handling, funding, redeem, refund, and timeout safety, and kept the existing reverse `BitcoinyACCTv5` behavior wired through that shared helper.

### 2026-05-10 - crosschain: keep reverse split-fill ACCT v6 internal

Kept the unfinished `BitcoinyACCTv6` reverse split-fill ACCT as an internal work-in-progress instead of a user-facing protocol. The v6 bytecode and deterministic tests remain available for development, but v6 is no longer registered for public ACCT name or code-hash lookup, so normal APIs continue to expose the completed v4/v5 trade flows until v6 has matching trade-bot and API support.

### 2026-05-10 - test: stabilize split-fill ACCT v4 cancellation tests

Stabilized the deterministic `BitcoinyACCTv4` split-fill tests by using repository-sequenced transaction timestamps instead of wall-clock timestamps and by giving the cancellation-wait scenario enough test-chain time before the active fill expires. This keeps the test focused on the intended behavior: a cancelled split-fill offer should remain cancelled while active fills wait for refund, then finish cleanly after the refund window passes.

### 2026-05-10 - crosschain: add reverse split-fill ACCT foundation

Added `BitcoinyACCTv6` as the first reverse split-fill ACCT foundation, where one maker foreign-coin offer can be reserved in separate fill slots and each fill moves through taker reservation, maker foreign-lock declaration, taker local-asset lock, maker redeem, refund, or cancellation independently. The new parser is registered for recognized v6 ATs while the active trade-bot flow remains on v5 until the API and bot paths are wired in a later step. Deploy AT validation and repository storage limits now allow the larger ACCT bytecode/state sizes already needed by recent ACCT versions, and deterministic v5/v6 tests cover the deployed reverse flows without relying on live foreign-chain services.

### 2026-05-10 - test: expand reverse ACCT trade-bot coverage

Added deterministic `BitcoinyACCTv5` trade-bot tests for the foreign-first reverse trade flow. The tests now mock foreign-chain HTLC status and transaction broadcasts so the default suite can cover maker funding and foreign-lock declaration, stale reservation cancellation, unsafe locktime cancellation, taker local-lock transaction building, maker local-asset redemption and secret reveal, taker foreign HTLC redemption, and maker foreign HTLC refund handling without depending on live Electrum servers.

### 2026-05-09 - crosschain: make reverse ACCT trades foreign-first

Redesigned `BitcoinyACCTv5` reverse trades so the maker uses a maker-owned secret and funds a taker-specific foreign HTLC before the taker locks any local-chain asset. Reverse takers now reserve an offer with a zero-payment message, wait for the maker's funded foreign HTLC declaration, then use the new `/crosschain/tradebot/locklocal` API step to build the unsigned local asset lock transaction; the maker can only claim the local asset by revealing the secret on Qortium, which lets the taker redeem the foreign HTLC. The old local API balance-reservation safeguard was removed because public offers no longer require taker funds to be locked before the maker proves foreign liquidity, and the reverse trade flow now enforces a 30-minute foreign locktime safety margin, expires stale reservations, and lets the maker trade address cancel before local assets are locked. The reverse trade design docs and deterministic v5 tests cover the new reservation, foreign-lock, local-lock, redeem, refund, cancel, and premature-lock refund behavior.

### 2026-05-09 - crosschain: add reverse ACCT trades

Added the first reverse cross-chain trade implementation with `BitcoinyACCTv5`, where the maker offers BTC-like foreign funds and the taker escrows a selected local-chain asset into the AT. Trade creation can now request `SELL_FOREIGN` offers, the local API checks the maker's foreign wallet balance against existing open reverse offers for the same wallet key and rejects unverifiable wallet balances, the response API builds unsigned local escrow message transactions for takers to sign, the maker trade bot funds the foreign HTLC after the local escrow locks, and deterministic tests cover v5 trade data, lock/redeem/refund/cancel behavior, registry wiring, API-facing bot routing, reverse HTLC key-role ordering, and local reservation math.

### 2026-05-09 - crosschain: document reverse ACCT trade design

Added a reverse cross-chain trade design note for future `SELL_FOREIGN` ACCT flows where the maker offers foreign-chain funds and the taker escrows a chosen local-chain asset into an AT. The AT asset tests now include a deterministic feasibility check proving that a later local-asset payment can be detected by AT bytecode, bound to the payment sender, and refunded to that same sender, which confirms the core primitive needed before implementing the next ACCT version.

### 2026-05-09 - crosschain: expose ACCT trade direction

Added an explicit ACCT trade direction field so current offers and trades report that they are `SELL_LOCAL`, meaning the maker escrows a local-chain asset in the AT and the taker pays foreign-chain funds through the HTLC flow. This prepares the API and trade model for future reverse trades without changing current trade behavior.

### 2026-05-09 - refactor: rename ACCT trade roles to maker and taker

Renamed the crosschain ACCT trade-bot role terminology from Alice/Bob to maker/taker, including persisted trade-bot state names, HTLC API descriptions, comments, logs, and local helper names. The state numeric values are unchanged, keeping current behavior intact while making split-fill and future reverse-trade flows easier to reason about.

### 2026-05-09 - test: expand split-fill ACCT coverage

Expanded deterministic ACCT v4 coverage for split-fill offers by testing unfilled offer cancellation, active fill refund back into an offer, cancellation that waits for active fills to refund, and maker-side pending fill cleanup. The trade-bot now exposes a small package-level helper for stale pending fill state transitions so the behavior can be tested without relying on live foreign-chain calls.

### 2026-05-08 - crosschain: add split-fill ACCT offers

Added a new Bitcoiny ACCT v4 and trade-bot flow that lets one local-asset offer be filled in separate slots instead of forcing a single buyer to take the whole amount. Trade creation can now set minimum and maximum local fill amounts, response calls can request a specific fill size, offer summaries expose total, remaining, active, completed, and slot counts, and maker-side fill records are saved and backed up so active split fills can be tracked across restarts. BTC-like chains now create v4 offers by default while existing v3 and Pirate ACCT parsers remain available for recognized code hashes.

### 2026-05-08 - crosschain: support local asset ACCT trades

Updated the crosschain ACCT and trade-bot flow so offers can trade any spendable local-chain asset, not only the native asset. Trade creation, offer/trade summaries, price and websocket filters, trade-bot import/export data, AT payout bytecode, and repository storage now carry a `localAssetId` and `localAmount`; fresh Qortium chains use the new schema directly without keeping old Qortal trade-bot migration paths.

### 2026-05-08 - crosschain: add transparent Zcash Bitcoiny support

Added Zcash as a registered transparent-only Bitcoiny crosschain coin with ZEC mainnet chain identity, SLIP-44 wallet metadata, two-byte `t1`/`t3` address prefixes, SSL Electrum server metadata, and deterministic transaction-builder coverage. ZEC spends and HTLC redeem/refund transactions use ZIP225/v5 transparent serialization while shielded, unified, Sapling, and Orchard wallet flows remain outside the generic BTC-like path.

### 2026-05-08 - crosschain: add Bitcoin Cash testnet4 support

Added Bitcoin Cash TEST4 as a supported BCH network using BCHN testnet4 parameters, `bchtest:` CashAddr metadata, and generated Electrum server metadata from the 1209k `tbch4` list. BCH mainnet remains the default when no network override is configured.

### 2026-05-08 - crosschain: add Bitcoin testnet4 support

Added Bitcoin TEST4 as a supported BTC network using the BIP94 testnet4 genesis parameters, refreshed Electrum server metadata from the 1209k `tbtc4` list, and moved the default test settings from BTC TEST3 to BTC TEST4 while keeping TEST3 available for legacy fixtures. The Electrum server refresh tool can now restrict a refresh to selected networks, making it possible to update BTC TEST4 without rewriting existing BTC mainnet or TEST3 server entries.

### 2026-05-08 - crosschain: add Bitcoin Cash Bitcoiny support

Added Bitcoin Cash as a registered Bitcoiny crosschain coin with BCH wallet metadata, CashAddr address support, a fork-specific BIP122 trade reference, refreshed SSL Electrum servers, and common wallet/HTLC/ACCT coverage. Because BCH shares Bitcoin's genesis and uses fork-ID transaction signatures, the generic runtime now has a BCH signing path that keeps BCH trades distinct from BTC trades while filtering out CashToken-prefixed outputs so token UTXOs are not accidentally spent as plain BCH.

### 2026-05-07 - crosschain: add VerusCoin Bitcoiny support

Added VerusCoin as a registered transparent Bitcoiny crosschain coin using the shared chain-spec model, including VRSC wallet metadata, address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT coverage, and chain-spec documentation. Because VRSC shares KMD's genesis block, the generic Bitcoiny ACCT uses a Verus checkpoint-based BIP122 chain reference so VRSC trades cannot collide with KMD, while VerusID, PBaaS, reserve/multicurrency transactions, and shielded wallet behavior remain outside the generic BTC-like path.

### 2026-05-07 - crosschain: add Verge Bitcoiny support

Added Verge as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, XVG address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. Because XVG uses six decimal places and includes a transaction timestamp field in ordinary transparent transactions, the shared Bitcoiny runtime now has a timestamped legacy transaction builder/parser for XVG spends and HTLC redeem/refund transactions while leaving Verge stealth, messaging, and Tor/I2P routing features outside generic BTC-like support.

### 2026-05-07 - crosschain: add Peercoin Bitcoiny support

Added Peercoin as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, PPC address headers, SSL Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. Because PPC uses six decimal places and Peercoin's current transaction serialization, the shared Bitcoiny runtime now carries per-chain decimal metadata and builds PPC spend and HTLC transactions as version 3 while still being able to parse older pre-version-3 Peercoin transactions that include a transaction timestamp field.

### 2026-05-07 - crosschain: add LBRY Credits Bitcoiny support

Added LBRY Credits as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, LBC address headers, Electrum server refresh metadata, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This enables ordinary transparent LBC trade and wallet support while deliberately leaving LBRY claim, publish, update, and content-discovery features outside the generic BTC-like path; detected claim outputs are filtered out of normal wallet spend selection so they are not accidentally spent as plain LBC.

### 2026-05-07 - crosschain: add Komodo Bitcoiny support

Added Komodo as a registered transparent Bitcoiny crosschain coin with mainnet chain identity, SLIP-44 metadata, BIP122 routing, KMD address headers, Electrum server refresh metadata, and common registry coverage. Because KMD uses Sapling-era transaction rules, the generic Bitcoiny runtime now has a signed raw transaction wrapper and a Sapling-transparent signing path for KMD sends and HTLC redeem/refund transactions, while shielded KMD wallet behavior remains out of scope.

### 2026-05-07 - docs: document Zcash-family native wallet requirements

Documented why HUSH, Zcash, and other Sapling-family chains should remain planned rather than active until their Rust JNI wallet libraries, QDN wallet packages, Sapling parameter files, lightwalletd servers, wallet behavior, parser behavior, and live verification path are reproducible. Also clarified that KMD should use the generic BTC-like Bitcoiny path if the intended support is transparent Electrum-compatible KMD, and only use the Zcash-family path if native Sapling wallet support is actually required.

### 2026-05-07 - crosschain: add generic Zcash-family support

Moved Pirate Chain wallet, lightwalletd, QDN wallet-library loading, and raw Zcash-style transaction parsing onto shared Zcash-family foundations, leaving the existing Pirate classes as thin compatibility wrappers. This avoids copying the Pirate-specific wallet/controller/light-client code when later adding HUSH, Zcash, or other Sapling-family chains, while keeping Pirate Chain as the only active Zcash-family runtime for now and documenting the remaining transparent-parser boundary.

### 2026-05-07 - crosschain: add Firo Bitcoiny support

Added Firo as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including mainnet chain identity, SLIP-44 wallet metadata, transparent address headers, exact PoW limit parameters, SSL-first Electrum server seeds, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This enables ordinary transparent FIRO trade and wallet support while deliberately leaving Spark, Lelantus, Zerocoin, and exchange-address flows outside the generic BTC-like path.

### 2026-05-07 - crosschain: add Namecoin Bitcoiny support

Added Namecoin as a registered BTC-like crosschain coin using the shared Bitcoiny chain-spec model, including verified mainnet parameters, BIP122 chain identity, SLIP-44 wallet metadata, SSL-first Electrum server refresh support, common wallet/HTLC/ACCT test coverage, and chain-spec documentation. This adds ordinary NMC trade and wallet support while deliberately leaving Namecoin name registration/update features for a later pass; detected Namecoin name outputs are filtered out of normal wallet spend selection so name UTXOs are not accidentally spent as plain NMC.

### 2026-05-07 - crosschain: add Bitcoiny chain spec guardrails

Changed shared Bitcoiny trade ATs to store the active network's BIP122 chain reference instead of a Qortium-assigned numeric foreign-chain id, while keeping SLIP-44 coin types as wallet metadata for coins that have them. The chain-spec docs now list the registered mainnet BIP122 ids, planned SLIP-44 wallet metadata for Namecoin, Firo, and Komodo, a compatibility precheck, and a reusable coin-spec checklist for future BTC-like chain additions. The dependency provenance docs now mark altcoinj/libdohj as retired and document the remaining upstream bitcoinj signing boundary. The Bitcoiny spec tests now include a compact manifest check for each registered BTC-like chain's SLIP-44 metadata, BIP122 chain id, supported networks, mainnet genesis hash, address headers, and BIP32 headers so accidental identity changes or incomplete chain registration are caught before more coins are added. The broader crosschain tests also fixed a raw transaction parser edge case for valid empty input scripts and made the shared wallet balance fixture derive its expected balance from the mock provider instead of assuming only one funded wallet address.

### 2026-05-07 - crosschain: add internal Bitcoiny transaction model

Added a Qortium-owned Bitcoiny transaction value object that serializes legacy Bitcoin-like inputs, outputs, locktime, variable-length fields, and transaction IDs deterministically. The new tests round-trip parser fixtures through this write-side model, giving the project a covered transaction serialization layer before attempting to replace bitcoinj's remaining signing boundary.

### 2026-05-07 - crosschain: internalize Bitcoiny spend output lookup

Moved Bitcoiny spend preparation onto Qortium's deterministic key scanner for locating spendable outputs before bitcoinj signs the transaction. The remaining bitcoinj spend boundary now receives a precomputed UTXO list instead of driving wallet/keychain address discovery itself, and the old bitcoinj-specific key scanning helpers were removed while preserving existing single-recipient and multi-recipient spend behavior.

### 2026-05-07 - crosschain: remove legacy Bitcoiny wallet repair

Removed the old Bitcoiny wallet repair endpoint and its pre-2024 key-scanning path, which was only relevant to historical wallets from the inherited chain and depended on a bitcoinj UTXO provider. New Qortium baseline chains keep the normal deterministic wallet balance, address, and spend paths while dropping this legacy repair surface as part of the ongoing bitcoinj dependency reduction.

### 2026-05-07 - crosschain: internalize HTLC scriptSig building

Moved HTLC refund and redeem scriptSig construction onto Qortium-owned push-data serialization instead of bitcoinj ScriptBuilder and ScriptChunk helpers. HTLC transaction signing still uses bitcoinj at the transaction boundary, but the actual secret, signature, public-key, and redeem-script stack encoding is now shared through BitcoinyScript and covered by deterministic tests.

### 2026-05-07 - crosschain: internalize HTLC scriptSig parsing

Moved HTLC secret discovery onto Qortium-owned raw transaction and scriptSig push parsing for both Bitcoin-like and PirateChain HTLCs, using the generic Bitcoiny raw parser for Bitcoin-like transactions and the Pirate decoder for Zcash-style Pirate transactions. The shared Bitcoiny script helper now extracts push-data chunks directly, so HTLC read-only secret/status paths no longer need bitcoinj transaction or script parsing, while HTLC transaction signing remains on bitcoinj until the dedicated transaction builder is replaced.

### 2026-05-07 - crosschain: internalize Bitcoiny raw transaction parsing

Added a Qortium-owned raw transaction parser for Bitcoin-like transaction inputs, outputs, lock time, legacy encoding, and segwit witness skipping. Bitcoiny output resolution now reads raw transaction output scripts and values through this parser instead of constructing a bitcoinj `Transaction`, moving another read-only crosschain path off bitcoinj while keeping spend construction and signing on bitcoinj for the later transaction-builder replacement.

### 2026-05-06 - crosschain: internalize Bitcoiny wallet derivation

Added Qortium-owned BIP32 extended-key parsing and public child-key derivation for Bitcoin-like wallets, including Base58Check validation, secp256k1 public derivation, and deterministic P2PKH scan address generation. Wallet balance scans, transaction history scans, address-info lookups, unused receive-address selection, and spending-candidate previews now use the internal derivation path instead of bitcoinj Wallet/keychain APIs, while actual spend construction and signing remain on bitcoinj until the transaction-builder replacement is ready.

### 2026-05-06 - crosschain: add Bitcoiny address scripts

Added internal Bitcoiny address and script helpers for Base58Check addresses, segwit address decoding, and standard P2PKH/P2SH/witness output scripts. Wallet scanning, HTLC status checks, Litecoin P2SH normalization, crosschain API validation, and related tests now use those Qortium helpers for address hashes and scriptPubKeys instead of asking bitcoinj to build them, while transaction signing remains on bitcoinj for a later replacement step.

### 2026-05-06 - crosschain: remove altcoinj and internalize outputs

Removed the remaining altcoinj/libdohj dependency by moving PirateChain mainnet onto Qortium's shared static network parameter model and replacing the old libdohj parity tests for Litecoin, Dogecoin, DigiByte, and Ravencoin with deterministic static-parameter checks. Bitcoiny unspent-output and transaction-output lookups now return Qortium value objects instead of bitcoinj `TransactionOutput` objects, so HTLC redeem/refund code accepts resolved `UnspentOutput` data and only converts back to bitcoinj structures internally while signing.

### 2026-05-06 - crosschain: migrate Bitcoin to static params

Moved Bitcoin mainnet, testnet3, and regtest away from bitcoinj's network parameter classes and onto the shared `StaticBitcoinyParams` model, including Bitcoin's genesis transaction, network headers, DNS seeds, BIP32 headers, segwit address prefixes, regtest settings, and deterministic parity tests against the previous params. This keeps BTC on the same reusable parameter path as the other Bitcoiny coins while preserving the existing `MAIN`, `TEST3`, and `REGTEST` network choices.

### 2026-05-06 - crosschain: migrate Litecoin to static params

Moved Litecoin mainnet, testnet4, and regtest away from inherited libdohj parameter classes and onto the shared `StaticBitcoinyParams` model, including Litecoin's custom genesis transaction, scrypt chain hashes, network headers, DNS seeds, monetary settings, and deterministic tests for the static params. The Litecoin public test network is now named `TEST4` to match the genesis hash and Electrum servers already used by Qortium, and the old Litecoin P2SH address normalizer now uses shared static params instead of a libdohj subclass. Unsupported Dogecoin, DigiByte, Ravencoin, and Dash testnet/regtest choices are no longer advertised until those networks have real params and usable servers.

### 2026-05-06 - crosschain: migrate Dogecoin to static params

Moved Dogecoin mainnet and testnet away from the inherited libdohj parameter classes and onto the shared `StaticBitcoinyParams` model, including Dogecoin's custom genesis transaction, no-fixed-cap money setting, network headers, DNS seeds, and parity tests against the previous params. This also corrects the registered Dogecoin testnet genesis hash so the DOGE testnet spec points at Dogecoin's actual testnet instead of the inherited Litecoin testnet value, and tightens the shared static genesis handling so non-double-SHA chains like Dash and Ravencoin can expose their configured chain hashes consistently in tests.

### 2026-05-06 - crosschain: migrate DigiByte to static params

Moved DigiByte mainnet away from the inherited libdohj parameter class and onto the shared `StaticBitcoinyParams` model, reusing the custom genesis transaction/header support introduced for Ravencoin and adding parity tests against the previous DigiByte params. This continues reducing production reliance on altcoinj/libdohj-specific params classes while keeping runtime wallet, HTLC, and ACCT behavior covered by the shared Bitcoiny tests.

### 2026-05-06 - crosschain: migrate Ravencoin to static params

Moved Ravencoin mainnet away from the inherited libdohj parameter class and onto the shared `StaticBitcoinyParams` model, including custom genesis-header support, interval and monetary parameter hooks, and parity tests against the previous Ravencoin params. This is the first existing BTC-like coin migrated to the shared parameter model and establishes the safer pattern for moving the remaining coins away from altcoinj/libdohj-specific params classes.

### 2026-05-06 - crosschain: add Dash Bitcoiny support

Added Dash as the first new reusable BTC-like chain after the Bitcoiny registry cleanup, including shared static network parameters, registry/spec metadata, verified SSL Electrum seeds, shared deterministic wallet/HTLC/ACCT test coverage, and documentation for selecting the Dash network. The Electrum refresh tool now also preserves existing generated server source and response-time metadata when refreshing a single coin, preventing unrelated server-list churn.

### 2026-05-06 - crosschain: remove supported-blockchain facade

Removed the temporary `SupportedBlockchain` enum facade and moved the remaining wallet settings, PirateChain ACCT, PirateChain trade-bot, and test/helper lookups onto `ForeignBlockchainRegistry` entries. The registry now also owns ACCT-to-trade-bot routing, so new registered crosschain families can add runtime, ACCT, and trade-bot routing in one central lookup path instead of updating a separate trade-bot map.

### 2026-05-06 - crosschain: route foreign fees through registry

Moved foreign-fee offer scanning, local fee processing, backup/import, and ACCT lookup from the `SupportedBlockchain` facade to `ForeignBlockchainRegistry` entries. Foreign-fee handling now iterates the registry and processes any enabled registry entry backed by a `Bitcoiny` runtime, keeping this runtime path ready for new registered BTC-like chains without adding enum cases. Also cleaned the archive trade-bot import test so it commits imported repository changes before close, avoiding a repository leak diagnostic during focused verification.

### 2026-05-06 - crosschain: route API filters through registry

Moved crosschain API and websocket blockchain filters from `SupportedBlockchain` enum parameters to `ForeignBlockchainRegistry` string lookups. API callers can now use registry names or currency codes, websocket sessions normalize filters to canonical registry names, and the remaining trade/ledger/P2SH helper paths resolve ACCT and Bitcoiny instances through the registry instead of adding new enum dependencies. The shared API test helper now also fails when an expected API error is not thrown, making invalid-filter regression tests meaningful.

### 2026-05-06 - crosschain: route backend ACCT lookups through registry

Added direct `ForeignBlockchainRegistry` helpers for registered Bitcoiny entries, entry names, and required-name validation, then moved trade-bot, presence, and HTLC lookup paths off the temporary `SupportedBlockchain` facade. This keeps new backend crosschain code pointed at the data-driven registry while the enum remains only as compatibility plumbing for older callers.

### 2026-05-06 - crosschain: resolve Bitcoiny ACCT chains through registry

Moved Bitcoiny ACCT build and parse logic off the `SupportedBlockchain` facade so shared Bitcoiny trade ATs now accept registry entries and resolve stored foreign-chain ids through `ForeignBlockchainRegistry`. This keeps the core ACCT format path data-driven and makes future BTC-like coins depend on registry metadata instead of enum-specific build helpers.

### 2026-05-06 - crosschain: move Bitcoiny lifecycle into registry

Moved registered Bitcoiny runtime ownership out of the `SupportedBlockchain` enum and into `ForeignBlockchainRegistry`, which now builds BTC-like entries directly from `BitcoinyChainSpecs` and the PirateChain entry explicitly. Bitcoiny specs now carry canonical registry names, and `SupportedBlockchain` is reduced to a compatibility facade that delegates instance lookup, ACCT lookup, ids, currency codes, and test resets back to the registry.

### 2026-05-06 - crosschain: add foreign blockchain registry

Added a central foreign blockchain registry that resolves chain names, currency codes, Bitcoiny foreign-chain ids, and ACCT implementations from one place while keeping `SupportedBlockchain` as a temporary compatibility facade. Trade-bot offer creation now accepts a foreign blockchain string, so the API can move toward data-driven chain registration instead of requiring callers to serialize a Java enum. Bitcoiny wallet creation now also propagates the selected bitcoinj context consistently, which keeps registered multi-coin tests isolated when BTC-like chains run in one JVM.

### 2026-05-06 - crosschain: streamline Bitcoiny spec definitions

Condensed the Bitcoiny chain specification registry with a reusable builder so each BTC-like chain now supplies mostly metadata instead of repeating the same mainnet, testnet, regtest, fee, and Electrum refresh wiring. Remaining user-facing crosschain text was also made more generic so the API describes foreign-chain funds and supported Bitcoiny chains instead of naming only the originally supported coins.

### 2026-05-06 - crosschain: simplify Bitcoiny seed and API data

Moved Bitcoiny Electrum server fallback data fully into the generated JSON resource so the refresh tool no longer rewrites Java hardcoded server lists, and existing generated seeds are preserved as refresh input before 1209k and Electrum peer discovery add updates. The Bitcoiny trade and HTLC API models also drop old Bitcoin-only compatibility aliases in favor of generic foreign-chain fields, reducing baseline API clutter for new chains.

### 2026-05-06 - crosschain: parameterize Bitcoiny chain tests

Replaced the duplicated Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin crosschain test classes with one registered Bitcoiny test suite driven by per-chain fixtures, so new BTC-like coins can reuse the same deterministic wallet, fee, and HTLC coverage without another copied test wrapper. Bitcoiny chain lookup is now centralized in `SupportedBlockchain`, reducing repeated type checks across the API, trade-bot, foreign-fee, and helper-app paths.

### 2026-05-06 - crosschain: collapse Bitcoiny coin wrappers

Replaced the separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin runtime wrappers with one registry-backed Bitcoiny implementation that is created from each chain specification. BTC-like network selection now uses a generic `bitcoinyNetworks` settings map keyed by currency code, Bitcoin's default spend fee and Litecoin's address normalization live in the shared chain specs, and the generic Bitcoiny API route now validates supported chains through the registry instead of hardcoding every coin in the path.

### 2026-05-06 - crosschain: register Bitcoiny chain specs

Moved BTC-like chain metadata into a shared Bitcoiny chain specification registry so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin now describe their display names, ticker symbols, supported networks, fee defaults, minimum order amounts, and Electrum refresh metadata in one reusable place. The existing coin wrappers and settings remain compatible for now, but they now delegate their network definitions to the registry, giving future coins a single model to copy into instead of scattering metadata across wrappers and tools.

### 2026-05-06 - crosschain: centralize Bitcoiny chain definitions

Centralized the BTC-like chain lifecycle behind shared Bitcoiny chain definitions so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin no longer each maintain their own singleton setup. Built-in Electrum fallback servers now live in one `BitcoinyServers` helper, and the refresh tool updates those named fallback lists directly while preserving the generated JSON server resource as the preferred runtime source.

### 2026-05-05 - crosschain: condense Bitcoiny chain plumbing

Reduced duplicated Bitcoin-like chain code by moving common network metadata into shared static Bitcoiny network delegates, adding a generic send request model, and replacing the separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin API resources with one generic Bitcoiny resource that resolves chains by name or ticker. Litecoin address normalization now lives behind the shared Bitcoiny address hook so the generic send path can preserve its P2SH handling.

### 2026-05-05 - crosschain: configure Bitcoiny coins generically

Moved the remaining BTC-like coin wrappers onto a shared configured Bitcoiny implementation so Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin now reuse the same provider setup, fee, and minimum-order plumbing. The unused dynamic BitcoinyTBD experiment was removed, the supported-blockchain registry now carries stable currency codes, and the crosschain helper apps can resolve any configured Bitcoiny chain instead of being limited to hard-coded BTC and LTC switches.

### 2026-05-05 - crosschain: add generic Bitcoiny ACCTv3

Replaced the duplicated Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin ACCTv3 and trade-bot implementations with one shared Bitcoiny ACCTv3 path that stores the selected foreign blockchain in the AT data itself. Trade offers, trade-bot responses, websocket updates, HTLC helpers, price estimates, and foreign-fee handling now read the foreign chain from parsed trade data instead of inferring it from separate per-coin classes, while PirateChain remains on its dedicated ACCT path.

### 2026-05-05 - api: add manual QDN auto-update controls

Added restricted `GET /admin/update` and `POST /admin/update` endpoints so operators can manually check for and schedule the latest approved QDN auto-update even when automatic background updates are disabled. The manual path reuses the same approved development-group manifest lookup, QDN binary validation, SHA-256 verification, and apply-update restart flow as the background updater, with status details returned for unavailable, current, newer, and already-installing cases.

### 2026-05-05 - core: route auto-updates through QDN

Changed auto-update transport so approved update manifests now point to QDN-hosted `AUTO_UPDATE_BINARY` resources instead of GitHub auto-update branches. The updater starts from `autoUpdateEnabled` without requiring `autoUpdateRepos`, ignores legacy HTTP manifests, fetches the pinned QDN binary transaction by signature when available, verifies the SHA-256 hash of the XORed update bytes, and then uses the existing apply-update restart path. The publish/build helper scripts now create a local `qortium.update`, publish it to QDN, submit a compact dev-group `AUTO_UPDATE` manifest for approval, and document the new approval workflow.

### 2026-05-05 - test: tighten skipped-test guardrails

Added a default test hygiene check that fails if new `@Ignore` annotations are introduced, added a manual xvfb-backed GUI display workflow for splash and tray tests, and gave the JaCoCo coverage profile low starting thresholds for instruction, branch, and line coverage so regressions are visible without making the baseline unrealistic. PirateChain crosschain tests now avoid counting inherited BTC-like deterministic wallet and HTLC checks as skipped when those paths do not apply to ARRR, while adding deterministic Pirate HTLC script and P2SH address coverage.

### 2026-05-05 - test: add JaCoCo coverage reporting

Added an opt-in JaCoCo coverage profile to the Maven test workflow so deterministic test runs can produce HTML, XML, and CSV coverage reports without enforcing a pass/fail threshold yet. The CI test command now enables the repo's JUnit test property explicitly, generates the coverage report, and prints instruction, branch, and line coverage from the generated JaCoCo CSV summary, while the testing guide documents the local coverage command, report location, and MemoryPoW exclusion needed to keep full-suite coverage runs practical. The full-suite coverage pass also exposed and fixed poll-prefix pagination so `limit` and `offset` apply to polls instead of joined poll-option rows.

### 2026-05-05 - test: require explicit GUI display opt-in

Tightened the GUI test split so splash and system-tray display tests now require both a display-capable JVM and `-Dqortium.runGuiDisplayTests=true`, while the normal headless Maven path continues to exercise the splash no-op behavior. The testing guide now documents the GUI display opt-in command, including an `xvfb-run` example for automated display-backed runs.

### 2026-05-04 - test: tighten crosschain HTLC fixture coverage

Expanded BTC-like crosschain HTLC tests so the default suite builds deterministic in-memory fixtures for secret discovery and funded-status detection across supported coins, instead of relying on inherited live fixture checks for coins without known public HTLC data. Live PirateChain fixture checks now fail clearly when no configured light-client server returns the expected fixture data, and the testing guide now includes concrete commands for each opt-in integration test group.

### 2026-05-04 - test: add deterministic bootstrap and ElectrumX coverage

Added local deterministic coverage for bootstrap host HTTP checks and ElectrumX UTXO parsing so the default test suite validates more of the external-service logic without depending on public infrastructure. Bootstrap tests now exercise HEAD request status handling, archive size validation, and freshness validation against a loopback HTTP server, while ElectrumX tests now cover mock unspent-output parsing and confirmed/unconfirmed filtering. Explicit live ElectrumX runs also fail clearly if no Bitcoin testnet servers are configured.

### 2026-05-04 - test: improve opt-in test defaults

Made conditional test groups more useful in normal Maven runs by adding a headless splash no-op test, keeping graphical splash and tray checks scoped to display-capable runs, and extending MemoryPoW's default coverage with known full-buffer verification fixtures through difficulty 14 while leaving expensive compute benchmarks opt-in. Added a testing guide that lists the remaining opt-in properties for long MemoryPoW, GUI display, live bootstrap, live ElectrumX, live crosschain, and live repository integrity checks.

### 2026-05-04 - test: replace ignored tests with active coverage

Removed the remaining `@Ignore` annotations from the test suite by turning the skipped online-account API placeholder into deterministic empty-state API coverage, replacing the old nonce-compression diagnostic with active `ONLINE_ACCOUNTS_V3` nonce serialization checks, and changing the live repository name-integrity scan into an explicit opt-in check with real assertions. The name integrity tests now also cover deterministic reduced-name persistence without depending on a live repository.

### 2026-05-04 - test: make crosschain checks deterministic

Replaced ignored BTC-like crosschain stubs with deterministic mock-provider coverage for wallet scanning, spend building, wallet balances, median block time, and HTLC secret/status handling while keeping public ElectrumX, PirateChain, and other live crosschain checks behind explicit opt-in properties. The Dogecoin, Digibyte, Litecoin, Ravencoin, Bitcoin, HTLC, and PirateChain crosschain tests now provide default offline coverage without depending on unavailable testnet servers, and the opt-in PirateChain funded-HTLC checks now tolerate inconsistent light-client servers by using confirmed UTXOs and trying each configured live fixture server.

### 2026-05-04 - test: enforce clean transfer-privs recipients

Simplified TRANSFER_PRIVS processing for Qortium's new-chain rules so privilege transfers only create and populate a previously unused recipient account, removed the obsolete stored flag for transfers into existing recipients, and replaced the ignored transfer-privs test class with active coverage for clean-recipient success, orphan cleanup, funded non-genesis senders, and rejection of already-used recipient accounts.

### 2026-05-04 - test: make block serialization deterministic

Replaced the ignored random all-transaction block serialization test with deterministic round-trip coverage for an empty minted block and a minted block containing a payment transaction. The test now checks block length, serialized block metadata, transaction count, and embedded transaction bytes without trying to synthesize unrelated transaction setup inside a single fragile test.

### 2026-05-04 - test: unskip useful PoW and block timestamp checks

Converted MemoryPoW tests from a fully ignored class into fast deterministic default coverage, including an 8 MiB difficulty-8 compute smoke test. The long MemoryPoW benchmark is split by difficulty and remains available via `-Dqortium.runLongMempowTests=true`. The block timestamp API now returns genesis when it is the nearest block at or before the requested timestamp, and the previously ignored timestamp API test now asserts deterministic genesis and minted-block lookups.

### 2026-05-04 - test: harden live bootstrap host checks

Kept bootstrap host availability checks opt-in behind `-Dqortium.runLiveBootstrapChecks=true`, but changed explicit live runs to fail when no hosts are configured instead of silently skipping. Live checks now use bounded HTTP timeouts and can target release hosts directly with `-Dqortium.liveBootstrapHosts=https://host-one,https://host-two`.

### 2026-05-04 - test: keep ElectrumX coverage deterministic

Changed ElectrumX tests so default Maven runs exercise deterministic mock RPC responses instead of depending on public testnet ElectrumX servers, while the live server checks remain available with `-Dqortium.runLiveElectrumXTests=true`. ElectrumX header parsing now accepts numeric JSON counts from servers that return integers or numeric strings, retries another server after malformed block-header JSON, and connection logging no longer assumes a blockchain object is attached when tests construct the provider directly.

### 2026-05-04 - test: align arbitrary data and asset API checks

Updated arbitrary data tests to match the current PUT-only publishing behavior while still checking explicit PATCH rejection, made binary arbitrary-data diffs fall back to whole-file patches instead of failing on non-text input, adjusted raw on-chain arbitrary-data size checks for AES-GCM overhead, and fixed account persistence so existing genesis account rows gain their public key when the account later signs a transaction. The previously failing arbitrary-data and asset API test groups now pass without involving the external ElectrumX tests.

### 2026-05-04 - core: reserve asset 0 for explicit native bootstrap

Changed asset issuance so normal assets can be issued before the native asset exists while still starting at asset ID `1`, leaving asset ID `0` reserved for an explicit native bootstrap request. `ISSUE_ASSET` now carries an optional requested asset ID, only accepts `0` for development-group-approved native bootstrap, rejects repeated native bootstrap attempts after asset `0` exists, and keeps zero-quantity issuance limited to the native asset. Tests now cover normal pre-native asset issuance, native bootstrap after normal assets, one-time native creation, rejected non-native ID requests, and serialization round trips for the new field.

### 2026-05-03 - build: keep Maven plugins out of runtime shade

Removed Maven build plugins from the project's runtime dependency list while leaving them configured in the build plugin section, excluded Maven plugin artifacts that the AT dependency publishes transitively as compile dependencies, removed unused or redundant runtime dependencies for JavaMail, the direct servlet API, and Swagger's servlet initializer, excluded duplicate JAXB, JSON-P, and activation API jars where equivalent implementation/runtime jars remain available, and filtered Java module descriptors out of the shaded runtime jar. The package-info and build-helper plugins are still available during the build, but build-tool dependency trees and several duplicate API jars are no longer pulled into Qortium's shaded runtime jar.

### 2026-05-03 - build: clean import export unchecked warning

Tightened repository import/export JSON handling by typing the parsed result, validating exported array items before treating them as JSON objects, and using try-with-resources for backup file writes. This preserves the existing backup formats while removing the unchecked compiler notice from the HSQLDB import/export helper.

### 2026-05-03 - build: clean block archive reader unchecked warnings

Replaced raw archive file-list entries and unchecked casts in the block archive reader with typed map entries. Archive file selection and last-height calculation still use the same cached filename metadata, but the code now compiles without that unchecked warning.

### 2026-05-03 - build: clean name integrity comparator warning

Replaced the raw comparator used by the name database integrity checker with a typed transaction comparator. This preserves the existing block-height, timestamp, and signature ordering while removing the next unchecked compiler notice from the warning cleanup pass.

### 2026-05-03 - crypto: update X25519 field decode call

Updated the Ed25519 helper's X25519 field decoding call to Bouncy Castle's current no-offset overload. This preserves the same point-decoding behavior while removing the final Bouncy Castle deprecation warning introduced by the dependency update.

### 2026-05-03 - crosschain: update deprecated library API usage

Updated the Pirate Chain trade bot to call bitcoinj's current Bech32 encoding API explicitly, and documented the remaining non-dust-output overrides as intentional compatibility points with bitcoinj/libdohj's abstract network-parameter interface. This keeps cross-chain behavior unchanged while reducing avoidable dependency deprecation warnings.

### 2026-05-03 - build: clean remaining low-risk compiler notices

Removed raw map types from the API map adapter, replaced unchecked cache-filter test casts with checked helper methods, updated utility code to use current SevenZ and ICU Unicode APIs, and removed the deprecated trade-bot `xprv58` response fallback in favor of the existing `foreignKey` field. This keeps the baseline API stricter while reducing compiler noise before tackling the remaining third-party cross-chain deprecations.

### 2026-05-03 - api: replace deprecated optional SSL connector

Replaced the API service's deprecated Jetty `OptionalSslConnectionFactory` usage with the supported `DetectorConnectionFactory` plus `SslConnectionFactory` pattern. The API connector still detects TLS on the shared API port, routes encrypted connections through ALPN for HTTP/2 or HTTP/1.1, and falls through to HTTP/1.1 for clear-text requests.

### 2026-05-03 - build: clean low-risk compiler notices

Updated the AT logger wrapper to use Java's standard supplier type instead of Log4j's deprecated supplier interface, and marked the network-message clone helper's generic clone cast as intentionally unchecked. This removes low-risk compiler notices without changing message cloning or custom AT log-level behavior.

### 2026-05-03 - build: clarify HSQLDB varargs binds

Updated HSQLDB repository queries that bind typed arrays into varargs helper methods so the arrays are explicitly treated as SQL bind parameter lists. This preserves the existing query behavior while removing javac warnings about ambiguous non-varargs calls.

### 2026-05-03 - build: disable implicit javac annotation processing

Set the Maven compiler plugin to disable javac annotation processing explicitly. Qortium's normal build relies on Maven code-generation plugins for generated sources rather than javac annotation processors, so this keeps current behavior while avoiding javac's warning about future annotation-processing defaults.

### 2026-05-03 - crypto: use AES-GCM for QDN encryption

Replaced the baseline QDN/arbitrary-data AES file encryption format with `AES/GCM/NoPadding`. New encrypted payloads now store a 12-byte GCM nonce followed by ciphertext and the 16-byte authentication tag, removing the inherited CBC padding format and legacy AES fallback paths. Decryption now writes to a temporary file and only moves the plaintext into place after the GCM authentication tag verifies.

### 2026-05-03 - crypto: tighten TLS protocol defaults

Restricted API, gateway, domain-map, dev-proxy, and Electrum SSL setup to modern TLS defaults. Server-side Jetty SSL now uses a shared TLS policy that enables TLS 1.3/TLS 1.2, excludes legacy SSL/TLS protocol names, and includes HTTP/2-safe AEAD cipher suites. Electrum SSL sockets now request the `TLS` context instead of the legacy `SSL` alias and enable only TLS 1.3/TLS 1.2 when supported.

### 2026-05-03 - build: update Bouncy Castle to 1.84

Updated all Bouncy Castle runtime dependencies from `1.78.1` to `1.84`, covering the core provider, TLS/JSSE provider, PKIX certificate utilities, and shared utility jar. This keeps Qortium on the current Bouncy Castle release line before the follow-up TLS and encryption hardening changes.

### 2026-05-03 - network: replace vendored UPnP with jUPnP

Replaced the inherited locally vendored WaifUPnP jar with the maintained `org.jupnp:org.jupnp` Maven Central dependency. Added a small internal port-mapping abstraction so P2P and QDN networking no longer call a specific UPnP library directly, while keeping automatic router mapping best-effort and non-fatal if a router does not support it.

### 2026-05-03 - build: document and reproduce local dependency patches

Added a HSQLDB verification/rebuild script that downloads the official Maven Central HSQLDB jar, verifies checksums, and confirms Qortium's checked-in patched jar differs only by changing the manifest seal line from `Sealed: true` to `Sealed: false`. Expanded dependency provenance docs with HSQLDB checksums, the verification process, and the locally vendored WaifUPnP artifact so nonstandard dependencies now have tracked ownership and reproducibility notes.

### 2026-05-03 - build: own altcoinj dependency fork

Moved the inherited altcoinj Maven dependency from the IceBurst GitHub coordinate to the Qortium-controlled `QuickMythril/altcoinj` fork while keeping the same pinned bitcoinj `0.16` compatibility commit. Expanded dependency provenance notes with the bitcoinj -> libdohj -> altcoinj fork chain and the foreign-chain parameter support carried by that dependency.

### 2026-05-03 - build: own CIYAM AT dependency fork

Moved the CIYAM AT Maven dependency from the inherited IceBurst GitHub coordinate to the Qortium-controlled `QuickMythril/AT` fork while keeping the same pinned `v1.4.3` commit, making the change behavior-neutral. Added dependency provenance notes for the AT fork chain, the remaining inherited `altcoinj` fork, and the locally patched HSQLDB jar so future dependency ownership work has a tracked starting point.

### 2026-05-03 - core: allow ATs to use arbitrary spendable assets

Updated AT payment validation so AT accounts can receive any existing spendable asset while still rejecting unspendable assets. ATs can now pay arbitrary spendable assets through the asset-specific payout function, including native asset surplus after reserving the current round's maximum execution fees. Tests cover non-configured asset deposits, payouts, native surplus payouts, and unspendable asset rejection.

### 2026-05-03 - core: add asset-specific AT payment reads

Added AT chain functions for reading incoming payment amounts by asset id and counting payment entries addressed to the AT. New ATs can now process mixed configured-asset plus native-asset multipayments without changing the legacy single-amount/single-asset functions, which still report mixed-asset multipayments as ambiguous.

### 2026-05-03 - core: expose MULTI_PAYMENT entries to ATs

Added MULTI_PAYMENT transactions to AT inbox lookup and AT payment decoding. ATs now treat multipayments as payment-like incoming transactions, summing only entries addressed to the AT when they share one asset, while mixed-asset entries to the same AT remain ambiguous for the existing single-amount/single-asset AT functions. Tests cover single-entry multipayments, repeated AT recipients, mixed external recipients, mixed assets, and native fee top-ups.

### 2026-05-03 - core: expose MESSAGE payments to ATs

Updated AT transaction decoding so MESSAGE transactions with attached payments continue to report as messages while also exposing their payment amount and asset id through the existing amount and asset-id AT functions. Tests now cover configured-asset message payments, native message fee top-ups, and no-payment messages.

### 2026-05-03 - core: broaden AT asset support

Added a native fee reserve to DEPLOY_AT so an AT can be funded with both its configured working asset and asset `0` for execution fees in the same transaction. ATs now use their configured asset for default balance, payout, and final refund semantics while block processing continues to charge execution fees in the native asset, and new chain function codes let ATs inspect configured asset IDs, query asset balances, read incoming payment asset IDs, and pay non-native asset balances. External asset transfers to existing ATs now allow only the AT's configured asset plus native fee top-ups, and tests cover deploy funding, native fee separation, configured-asset payouts, rejected wrong-asset transfers, and transfer-asset visibility.

### 2026-05-03 - core: tighten reduced-name anti-spoofing

Changed reduced-name sanitizing so lowercase `i` joins the same reduced homoglyph class as uppercase `I`, lowercase `l`, `1`, and `|`, treats visually blank filler characters as whitespace before trimming and reduced-name generation, and strips control/format/private-use codepoints during normal name validation. This closes inherited spoofing gaps that allowed visually confusing names such as `sample-label` and `sample-Iabel`, names with trailing blank filler characters, or names with hidden bidirectional display controls to remain distinct, and adds coverage for names, groups, assets, and direct Unicode sanitizer behavior while leaving multi-character visual substitutions as a separate policy decision.

### 2026-05-03 - chat: restore general chat support

Restored public groupless CHAT transactions by removing the inherited API, peer-import, and websocket blocks against messages with no group and no direct recipient. General Chat continues to use the existing CHAT validation rules, including MemoryPoW, timestamp bounds, data-size limits, block lists, and recent-message rate limiting, and tests now cover import, retrieval, direct-chat separation, and active-chat listing for group 0.

### 2026-05-03 - core: enforce group invite expiries

Fixed group invite and ban expiry calculations so long time-to-live values do not overflow before being converted to milliseconds. Added timestamp-aware active-invite lookup for validation and group processing so expired invites can no longer be used to join closed groups or canceled as if they were still pending.

### 2026-05-03 - test: discard name integrity repair changes

Updated name integrity repair tests to explicitly discard temporary repository changes after their assertions. This keeps the tests focused on repair behavior while preventing repository-close warnings about uncommitted test work.

### 2026-05-03 - core: stop arbitrary name validation repairs

Removed ARBITRARY transaction pre-processing that rebuilt name table state before validation. Added coverage for named arbitrary publishes so validation rejects missing name state until an explicit name integrity rebuild restores the name.

### 2026-05-03 - core: stop name-sale validation repairs

Removed SELL_NAME, CANCEL_SELL_NAME, and BUY_NAME pre-processing that rebuilt name table state before validation. Updated name integrity tests so sale, cancel-sale, and buy-name validation reject missing current name state until an explicit name integrity rebuild restores the sale state.

### 2026-05-03 - core: stop update-name validation repairs

Removed UPDATE_NAME pre-processing that rebuilt name table state before validation. Updated integrity tests so missing current-name and destination-name state is only repaired by explicit name integrity rebuilds, keeping update-name validation focused on current repository state.

### 2026-05-03 - core: default transaction preProcess to no-op

Changed transaction pre-processing to default to a no-op in the base transaction class and removed redundant no-op overrides from individual transaction classes. This keeps preProcess overrides limited to transaction types that actually need preparatory behavior.

### 2026-05-03 - core: stop register-name validation repairs

Removed the REGISTER_NAME validation-time rebuild of name table state so register-name validation no longer mutates repository name data as a side effect. Updated name integrity tests to verify validation uses the current repository state, while explicit name integrity repair still restores missing name data and then causes duplicate registration attempts to be rejected.

### 2026-05-03 - i18n: synchronize translation bundles

Updated the translation checker so it discovers available language bundles instead of relying on a short hardcoded list. Fixed underscore locale loading for bundles such as zh_CN and zh_TW, removed stale translation keys, added missing current keys across the existing bundles, and cleaned malformed UTF-8 bundle headers so the checker now reports all discovered translation bundles as synchronized.

### 2026-05-03 - core: add explicit asset ownership transfers

Removed direct owner-change fields from UPDATE_GROUP and UPDATE_ASSET so those transactions only update mutable metadata and settings. Added explicit SELL_ASSET_OWNERSHIP, CANCEL_SELL_ASSET_OWNERSHIP, and BUY_ASSET_OWNERSHIP transactions, including public sales, direct recipient sales, zero-price direct gifts, orphan restoration, API builders, and native asset ownership-transfer rejection.

### 2026-05-03 - core: allow asset renames

Added optional asset renaming to UPDATE_ASSET, including reduced-name collision checks, updated transaction persistence and wire format, and orphan restoration for asset name history.

### 2026-05-03 - core: allow explicit primary name updates

Added an optional primary-name setting to UPDATE_NAME so owners can set or clear their primary name explicitly, with orphan restoration. Removed the restrictions that prevented updating or selling a primary name when the owner had multiple names.

### 2026-05-03 - core: allow group renames

Added optional group renaming to UPDATE_GROUP, including reduced-name collision checks, updated transaction persistence and wire format, and orphan restoration for group name history.

### 2026-05-03 - core: fix update group mutable settings

Made UPDATE_GROUP apply and orphan its approval block delay fields. UPDATE_GROUP no longer transfers group ownership or auto-adds the requested owner as a member/admin; ownership changes are reserved for future explicit group sale transactions.

### 2026-05-02 - core: support direct name sales

Added optional direct-sale recipients to name sale state so sellers can list a name for a specific buyer. Direct sales can use a zero price, but the recipient still has to submit a BUY_NAME transaction to accept the transfer; public name sales still require a positive price. Orphan handling now restores direct-sale recipient details for canceled and completed name sales.

### 2026-05-02 - core: enable publicize account bootstrap

Re-enabled PUBLICIZE as a fee-or-MemoryPoW transaction so new accounts can publish their public key without needing native coins first. Valid unseen addresses now return placeholder account data instead of an unknown-address error, while public-key lookups still return false until the account key has actually appeared on chain.

### 2026-05-02 - api: expose addresses in voting API responses

Added direct voter address fields to poll vote API data and VOTE_ON_POLL transaction JSON while keeping the existing voter public key fields. This lets clients display voter addresses without making a separate public-key lookup, and adds regression coverage confirming TRANSFER_PRIVS transaction JSON exposes both the original creator address and recipient address.

### 2026-05-02 - api: complete transfer privs API surface

Added the missing TRANSFER_PRIVS transaction API surface. Generic transaction responses can now expose typed transfer-privs data, and `/addresses/transferprivs` can build a validated raw unsigned TRANSFER_PRIVS transaction for clients to sign.

### 2026-05-02 - api: fix cross-chain current server reporting

Fixed cross-chain server configuration reporting so a disconnected foreign-chain provider no longer marks every configured server as current. The current-server flag now only appears when the provider actually has a matching current server.

### 2026-05-02 - api: expose minter address in block summaries

Added `minterAddress` to block summary API responses and populated it from reward-share minting state for range and signer-summary endpoints. This makes summary responses consistent with full block responses and avoids requiring clients to resolve minter public keys themselves; signer summaries also now preserve current repository results when there are no archived matches.

### 2026-05-02 - api: add local list discovery

Added an authenticated `GET /lists` API path that returns the known local list names in stable sorted order. This completes the basic list-management surface so clients can discover available node-local lists before fetching or updating a specific list.

### 2026-05-02 - test: remove stale feature trigger config

Removed obsolete feature trigger keys from the testnet chain config and mirrored test chain fixtures. Chain configs now only list the active `transactionV6Timestamp` feature trigger, matching the current baseline code and avoiding inherited no-op transition settings.

### 2026-05-02 - core: rename Ed25519 crypto extras

Renamed the inherited `Qortal25519Extras` Java helper to `Ed25519Extras` so the active crypto utility naming describes the Ed25519/X25519 and aggregate-signature behavior without carrying Qortal branding. This keeps the existing `org.qortal` package namespace unchanged.

### 2026-05-02 - installer: neutralize Windows installer identity

Updated the Windows installer project from inherited Qortal naming to Qortium naming. The Advanced Installer project, installer output, application executable, bundled JAR, shortcuts, log defaults, install directory, icon filename, visible installer artwork, and upgrade family now use Qortium identity while keeping the current Java main class namespace unchanged.

### 2026-05-02 - core: preserve QDN raw-data filenames

Changed QDN resource rebuilding so raw on-chain data can restore the original filename when transaction metadata records a single source file. Resources without metadata still use the existing `data` fallback, and rebuilt metadata paths are validated so they cannot escape the reader output directory.

### 2026-05-02 - docs: neutralize small helper examples

Updated narrow helper documentation that still used inherited Qortal or QORT examples. The database sqltool example now uses the Qortium URL ID, and the testnet genesis guidance now refers to native asset funds instead of QORT funds.

### 2026-05-02 - core: replace Qortal QDN category metadata

Replaced the inherited `QORTAL` arbitrary metadata category with a neutral `NETWORK` category. QDN helper validation and arbitrary metadata tests now use `NETWORK`, so new baseline chains no longer expose a Qortal-branded category in the active metadata vocabulary.

### 2026-05-02 - tools: neutralize auto-update release helpers

Updated auto-update and release helper defaults from inherited Qortal release names to Qortium names. Release-note generation now defaults to the Qortium repository, working directory, JAR, EXE, ZIP, and release title, and the auto-update documentation no longer points at the old Qortal mirror example.

### 2026-05-02 - core: neutralize TLS and small runtime examples

Neutralized remaining low-risk runtime examples and comments that still used inherited Qortal identity. Generated SSL certificate subjects now use Qortium-local names, account and resource comments use generic chain terminology, and internal HSQLDB notes describe the project-specific extension without Qortal branding.

### 2026-05-02 - tools: neutralize helper script naming and defaults

Updated helper scripts that still exposed inherited Qortal naming in active defaults. API helpers now use neutral example hosts and Qortium API-key paths, QDN publishing uses `QDN_PRIVKEY`, the transaction helper uses current native cross-chain field names, and the transaction helper no longer depends on removed account last-reference state.

### 2026-05-02 - runtime: neutralize log and testnet identity defaults

Updated runtime and testnet defaults that still used inherited Qortal naming. Log files now default to Qortium names, the testnet stop script now looks for and reports Qortium processes, and the testnet bootstrap native asset is labelled generically as `NATIVE` instead of using the old QORT identity.

### 2026-05-02 - api: neutralize public API labels and examples

Updated public API documentation labels and examples that still used inherited Qortal wording. The OpenAPI title now says Qortium API, name-transaction metadata examples use neutral chain wording, peer host examples use generic hostnames, and API-facing source comments no longer use Qortal as the public chain label.

### 2026-05-02 - core: neutralize runtime UI identity strings

Updated active runtime and GUI-facing identity strings from inherited Qortal wording to Qortium wording. Node version strings now use the `qortium-` prefix, the main controller thread is named Qortium, splash and system tray text now refer to Qortium Core, and the tray icon resource filenames have been moved to Qortium names while leaving the existing artwork unchanged.

### 2026-05-02 - test: fix repository interrupt test setup

Fixed the repository interrupt test so HSQLDB is explicitly allowed to call its test-only Java sleep routine under the test JVM. The test now also reports the underlying exception when setup or execution fails, shuts down its scheduler, and clears the deliberate interrupt before later repository or JUnit cleanup can inherit it.

### 2026-05-02 - core: neutralize database schema domain names

Renamed the inherited Qortal-branded HSQLDB domain types to reusable neutral names. Database schema definitions now use `AccountAddress`, `AccountPublicKey`, `PrivateKeySeed`, and `AssetAmount`, and the remaining runtime SQL casts were updated to match the neutral schema while leaving table names, column names, APIs, and Java package names unchanged.

### 2026-05-02 - core: neutralize QDN app compatibility names

Moved QDN app and content compatibility names from inherited Qortal terms to Qortium-neutral terms without legacy fallbacks. QDN links now use `qdn://`, injected app helpers use `qdnRequest`, resource metadata lives under `.qdn`, encrypted and raw QDN payload prefixes use `qdn*` names, and the default avatar identifier is now `qdn_avatar`.

### 2026-05-02 - core: neutralize AT API naming

Renamed the inherited Qortal-branded AT integration classes and chain-specific function-code enum to neutral `Chain*` names while preserving the underlying CIYAM AT function-code values and behavior. The chain address conversion function now uses the Java name `CONVERT_B_TO_CHAIN_ADDRESS` but still maps to function code `0x0512`, so generated AT bytecode semantics remain unchanged.

### 2026-05-02 - core: neutralize cross-chain native-side naming

Renamed active cross-chain native-side API fields, helper methods, ledger labels, and docs from inherited Qortal wording to neutral role-based wording. Cross-chain trade data now uses names such as `atAddress`, `creatorAddress`, `creatorTradeAddress`, `partnerAddress`, and `partnerReceivingAddress`, and ACCT helpers now refer to trade ATs without Qortal-specific method names.

### 2026-05-02 - core: neutralize runtime/build identity defaults

Moved the active runtime and build defaults from inherited Qortal names to Qortium names. New nodes now default to `QortiumKeyStore.jks`, `qortium-backup`, `qortium.jar`, `qortium.exe`, `qortium.update`, Qortium Docker paths and environment names, and `org.qortium:qortium` build coordinates, while the Java package namespace remains unchanged for a later dedicated refactor.

### 2026-05-02 - core: neutralize network identity defaults

Moved the peer network message magic out of inherited hardcoded QORT constants and into the chain configuration. Qortium's main and test chain configs now use `QRTM` and `qrtm` as their default network identifiers, and both normal peer networking and data networking read those values from the active chain config so future forks can choose their own network identity without editing Java constants.

### 2026-05-02 - core: remove native asset compatibility alias

Removed the temporary `Asset.QORT` Java compatibility alias now that production and test code use `Asset.NATIVE` for protocol asset ID `0`. This makes the native asset identity explicit in code and leaves any remaining historical QORT wording for separate documentation or branding cleanup.

### 2026-05-01 - core: refactor cross-chain native-side naming from QORT to native

Refactored the cross-chain trade and trade-bot surfaces so the chain-side asset is described as the native asset instead of QORT. Cross-chain request and response fields now use `nativeAmount`, `fundingNativeAmount`, and `nativeBalance`, trade-bot backups and repository storage use `nativeAmount` and `native_amount`, cross-chain labels now use `NATIVE` as the neutral display code, and the remaining cross-chain native balance checks now use `Asset.NATIVE`.

### 2026-05-01 - docs: neutralize native asset wording

Neutralized simple QORT wording in native-asset comments, API descriptions, translation strings, and demo UI text so those surfaces describe the chain's native asset without hardcoding the inherited QORT name. Cross-chain trade labels and `qortAmount`-style API fields remain unchanged for the dedicated cross-chain native-side refactor.

### 2026-05-01 - test/docs: replace test native asset aliases

Replaced the remaining direct test use of the deprecated `Asset.QORT` alias with `Asset.NATIVE` and cleaned up nearby test comments that were only describing native-asset balances or fees. This removes another layer of inherited QORT naming from the test suite while deliberately leaving cross-chain QORT trade labels for the later native-side cross-chain cleanup.

### 2026-05-01 - test/docs: neutralize native asset fixtures

Renamed the standard test-chain native asset fixture from QORT to NATIVE and moved shared test helper defaults to the neutral native asset constant. This keeps tests aligned with Qortium's asset `0` direction without changing production chain behavior, while the audit notes now distinguish the neutralized native fixture labels from broader inherited test identities that still need cleanup.

### 2026-05-01 - core: define native asset bootstrap rules

Defined runtime native asset bootstrap as a development-group-governed asset issuance instead of letting any first asset issue claim asset `0`. When no native asset exists, an ordinary `ISSUE_ASSET` transaction must now target one of the active configured development groups, pass group approval, and can still create asset `0` with either zero or positive initial quantity. The approval path remains coinless because `GROUP_APPROVAL` is covered by the shared MemoryPoW fee-alternative policy, and tests now cover rejection outside the development group plus approved bootstrap into native rewards.

### 2026-05-01 - core: introduce neutral native asset id

Introduced `Asset.NATIVE` as the neutral name for protocol asset ID `0` and moved core fee, reward, payment, and AT fee handling to that name while keeping `Asset.QORT` as a temporary compatibility alias for inherited surfaces that still need cleanup. Native asset bootstrap can now issue asset `0` with zero initial quantity, allowing a chain to define its native reward asset without preallocating any coins while keeping zero-quantity non-native assets invalid.

### 2026-05-01 - core: remove QORT genesis asset from main chain

Removed the inherited QORT asset issue from Qortium's main genesis configuration. A fresh main-chain baseline now starts without a pre-created native asset, so asset `0` can be created later by the first asset issuance while block rewards remain paused until that native asset exists.

### 2026-05-01 - test: cover no-native-asset bootstrap

Added a dedicated test-chain fixture with no genesis native asset or native balances, plus tests that mint safely before asset `0` exists, issue the first asset as asset `0`, and confirm rewards begin once that native asset exists. The MemoryPoW fee-policy tests now also include asset issuance as a representative normal transaction, keeping the coinless bootstrap path covered without making the test suite perform production-cost proof-of-work.

### 2026-05-01 - core: skip native rewards until native asset exists

Changed block reward distribution so Qortium does not create native asset balances before the native asset exists. This keeps minting safe for a future no-genesis-native-asset bootstrap: blocks can still be minted, account progress can still advance, and native rewards simply wait until asset `0` has been issued.

### 2026-05-01 - test: cover mempow fee alternatives for normal transactions

Added broader tests for the shared normal-transaction MemoryPoW fee policy across representative transaction shapes. The tests now check that normal transactions can use either a sufficient paid fee or a valid MemoryPoW nonce, while missing nonces, invalid nonces, and negative fees are still rejected, without changing production behavior.

### 2026-05-01 - core: allow zero-fee processing for mempow transactions

Made the transaction processing paths treat only positive declared fees as balance-moving fees, so a valid zero-fee MemoryPoW transaction does not debit or later refund any QORT. Positive low fees accepted through MemoryPoW are still charged exactly as signed, multipayment validation now relies on the shared payment balance check instead of a broken redundant fee check, and balance recording no longer creates artificial zero-value fee movements for zero-fee transactions.

### 2026-05-01 - core: validate mempow nonce for normal transactions

Made the normal transaction fee policy enforce the new MemoryPoW alternative. Eligible user-created transactions can now pass fee validation with either a sufficient paid fee or a valid MemoryPoW nonce, while missing, invalid, or negative-fee cases are rejected. Block validation now checks the same fee-or-mempow rule so this policy applies to confirmed transactions as well as unconfirmed imports.

### 2026-05-01 - core: add nonce support to normal transaction data

Added a shared MemoryPoW nonce field to normal transaction data so future fee-free transaction support can bind a nonce directly into each transaction's signed bytes and stored repository record. This only adds nonce carriage for eligible normal transaction types; it does not yet compute, validate, or accept MemoryPoW as a replacement for normal transaction fees.

### 2026-05-01 - core: define mempow fee policy for normal transactions

Defined the central Qortium policy for when ordinary user-submitted transactions will be allowed to use a MemoryPoW nonce instead of a paid fee. This commit only adds the shared policy hooks and keeps existing transaction behavior unchanged until later commits add nonce data and verification for eligible transaction types.

### 2026-05-01 - core: remove transaction reference from wire and schema

Removed the inherited general transaction reference from Qortium transaction data, raw transaction bytes, API lookup, chat responses, and database storage. Transaction signatures now cover a smaller baseline payload without the unused reference slot, while independent block, chat, group, name, and asset references remain in place for their own domain-specific state.

### 2026-05-01 - core: remove account last-reference state

Removed the inherited account last-reference record from Qortium accounts, APIs, lite-node account messages, and database schema. Transaction builders now use the deprecated transaction reference slot only as a neutral legacy field, so accounts no longer need a stored sequencing reference before creating or receiving transactions.

### 2026-05-01 - core: disable transaction reference semantics

Stopped using the inherited transaction reference as an account sequencing rule. Transactions no longer need a sender last-reference, processing no longer advances or reverts account last-reference values, and serialization keeps a neutral placeholder when the old field is missing or malformed. This keeps the legacy database and API field in place for now while preparing Qortium for participation models that do not require accounts to already own coins.

### 2026-04-30 - core: tighten null-owned group approval

Allowed null-owned groups to update their own group settings through the same approval path now used for admin, invite, kick, and ban management, instead of requiring the impossible null-account owner signature. Approval decision counting now ignores votes from accounts that are no longer current approval authorities, so old admin votes cannot keep counting after group authority changes.

### 2026-04-30 - test: align default test chain with Qortium genesis

Updated the default test-chain genesis to use the same neutral public development and minting groups as the main Qortium chain, removing inherited Alice-owned group setup, group updates, seeded minting joins, and the seeded reward-share from that fixture. Tests now use explicit bootstrap helpers when they need deterministic minting or development-admin state, keeping the fixture closer to a clean chain while preserving practical test setup.

### 2026-04-30 - test: declare dev groups in reward fixtures

Updated the specialized reward and minting test-chain fixtures to declare which group supplies development-admin reward recipients. This preserves their existing reward expectations now that block rewards use configured development groups instead of an implicit group 1 rule.

### 2026-04-30 - core: honor configured dev groups for block rewards

Changed block reward distribution to read development-admin reward recipients from the configured development group list instead of directly assuming group 1, and to skip the development-admin reward bucket when no real non-null admins exist. This lets a neutral chain start without seeded development admins while avoiding empty-recipient reward distribution failures.

### 2026-04-30 - core: let null-owned groups bootstrap from members

Made Qortium's seeded development and minting groups public so new accounts can join without preloaded privileged accounts, and changed null-owned group approval so members can approve group actions only while there are no usable non-null admins. Once a real admin exists, approval authority returns to the admin set, keeping the bootstrap path open without making public membership permanently equivalent to admin control.

### 2026-04-30 - core: keep reward orphaning on the active repository

Changed block reward recipient lookup to use the same repository connection that is currently processing or orphaning the block. This prevents reward orphaning from reading stale group-admin state while approved group-admin changes are being unwound, so tests that add or remove development admins can return cleanly to the genesis balance snapshot.

### 2026-04-30 - core: seed neutral development and minting groups

Updated Qortium's main genesis configuration to replace inherited Qortal reward-share, join, admin, and group-update seed entries with two neutral null-account-owned groups: `development` for dev approval and `minting` for minting eligibility. The native asset issue remains unchanged for now, while the active minting group configuration now points to the new minting group.

### 2026-04-30 - build: install patched HSQLDB before tests

Moved the local patched HSQLDB jar installation into Maven's `validate` phase so fresh workspaces install the repository's `Sealed: false` HSQLDB artifact before compile and test classpaths are built. This prevents DB-backed tests from loading the sealed Central jar and failing when Qortium's local `org.hsqldb.jdbc` classes are present.

### 2026-04-30 - core: configure auto-update dev groups

Changed Qortium so auto-update approval no longer depends on a hardcoded dev-group ID in code. The chain config now exposes `devGroupIds` using the same height-based multi-group structure as minting groups, with the current baseline still set to group `1` for compatibility while the larger genesis-governance replacement is designed. Auto-updates remain disabled by default, so operators must opt in before this configured update-discovery path is used.

### 2026-04-30 - docs: audit remaining hardcoded chain parameters

Recorded a focused audit of the remaining Qortal-specific chain parameters and branding assumptions still present in the Qortium baseline, including the main genesis governance entries, native asset naming, runtime ports and paths, build and package identity, test fixtures, Q-Apps naming, and cross-chain ACCT coupling. This gives the next cleanup work a tracked priority order before the project starts extracting those assumptions into clearer fork-facing configuration.

### 2026-04-25 - core: remove unused legacy transaction type placeholders

Removed the unused `AIRDROP` and `ENABLE_FORGING` transaction type placeholders from Qortium's active transaction enum. These legacy IDs no longer had transaction classes, data models, transformers, repository tables, API endpoints, or working validation paths, so removing the enum entries makes fresh chains treat those old numeric IDs as unsupported instead of exposing incomplete transaction types inherited from Qora-era designs.

### 2026-04-25 - core: remove legacy v1 ACCT support

Removed the old v1 cross-chain trade contracts for Bitcoin, Litecoin, and Dogecoin so Qortium's baseline only exposes the current v3 ACCT flow for those coins. This trims inherited compatibility code that a fresh Qortium chain does not need, removes legacy v1 trade-bot and API entry points, and keeps the remaining tests focused on the active cross-chain contract generation path before a later generic ACCT design is introduced.

### 2026-04-25 - core: remove genesis account-level seeding

Removed Qortal's genesis-only `ACCOUNT_LEVEL` transaction type and deleted the pre-leveled account entries from Qortium's main, testnet, and test-chain genesis configs. New chains now start accounts at level zero and rely on configured minting-group membership plus natural `blocksMinted` progression for level growth, while the runtime account-level system and reward bins remain available after accounts mint enough blocks.

### 2026-04-25 - core: allow group-member level-zero minting

Changed Qortium so a level-zero account that belongs to a configured minting group can create a reward-share and mint valid blocks, while block timing and chain-weight calculations use a minimum minting weight of one to avoid divide-by-zero behavior without pretending the account has gained a higher level. The baseline configuration now permits level-zero minting and reward-share creation, and online-account level reporting ignores unknown or ineligible reward-share keys instead of treating them as level zero.

### 2026-04-24 - core: use baseline fee and reward-share rules from genesis

Changed Qortium so the inherited historical schedules for standard transaction fees, name registration fees, and reward-share account limits are collapsed to Qortium's baseline values from timestamp zero, mirrored the same baseline into test chain configs, and updated the related tests so new chains start directly with a 0.01 QORT transaction fee, a 1.25 QORT name fee, and six reward shares per minting account, while reward-share recipients receive only their QORT payout percentage instead of also gaining `blocksMinted` progress or account-level increases from the minter's online reward-share.

### 2026-04-24 - core: remove stale Qora legacy remnants

Changed Qortium so the remaining legacy Qora wording and dead compatibility hooks are removed from the active baseline, including the obsolete broken-MD160 address mode, the final QORA migration table cleanup statement, and old test logging categories that still pointed at `org.qora` package names, leaving new chains with the current address format and cleaner fork-oriented defaults.

### 2026-04-24 - core: remove legacy QORA migration rewards

Changed Qortium so new chains no longer carry Qortal's legacy QORA holder reward migration system, removed the special QORA marker assets and imported QORA balances from the main and test chain configs, removed the reward-distribution code and repository state that tracked QORT-from-QORA payouts, and updated reward tests to use the normal account-level and admin reward flow without a QORA carryover bucket.

### 2026-04-24 - core: remove Qortal historical block fixes

Changed Qortium so fresh chains no longer apply Qortal's old hardcoded balance and name repair patches at inherited block heights, removed the historical patch classes and balance-delta files, removed the Qortal-specific checkpoint from the main chain config, and made checkpoint handling safe for fork configs that do not define checkpoints so Qortium is no longer tied to Qortal mainnet repair history.

### 2026-04-24 - core: allow arbitrary fee or mempow from genesis

Changed Qortium so ARBITRARY and QDN publish transactions can use either a normal sufficient fee or a zero-fee MemoryPoW nonce from genesis, removed the inherited optional-fee rollout trigger, and kept the broader MemoryPoW transaction timestamp untouched for the remaining message and publicize transaction rules.

### 2026-04-24 - core: remove account flags

Changed Qortium so the inherited account flag system is removed after founder privileges stopped using it, including the genesis-only flag transaction, the stored account flag field, flag transfer behavior, and the remaining no-op genesis entries, leaving account levels, group membership, and explicit chain records as the active ways to represent account status.

### 2026-04-24 - core: remove founder seed data

Changed Qortium so the inherited founder flag seed data is no longer included in the main chain, testnet, or mirrored test-chain genesis configs, updated the testnet setup notes to use reward shares and explicit account levels instead of founder flags, and renamed the remaining founder-focused reward fixture so it now describes the admin replacement reward behavior that is actually being tested.

### 2026-04-24 - core: remove founder privilege behavior

Changed Qortium so founder flags no longer grant a special effective minting level or a separate reward-share limit, leaving account level and the normal timestamp-based reward-share cap as the only active rules for those paths, and removed the inherited founder-only config fields from the main chain, testnet, and mirrored test-chain configs while updating tests to rely on explicit account levels instead of founder status.

### 2026-04-24 - core: use admin reward replacement from genesis

Changed Qortium so the reward path that replaced founder leftover rewards with the current minter-admin and dev-admin replacement model now applies from genesis, removed the inherited activation height from the main chain and mirrored test chain configs, and updated reward tests so founder flags no longer create a special block reward bucket while the existing admin split remains unchanged for now.

### 2026-04-20 - core: use V3 online accounts PoW from genesis

Changed Qortium so the online-accounts proof-of-work and timestamp cadence no longer preserve the inherited rollout from older settings to harder proof-of-work and then later lower-cost proof-of-work, collapsed that logic to the latest 10-minute online-account window and low-cost V3 MemoryPoW difficulty from genesis, removed the old timestamps from the main chain and mirrored test chain configs, and updated the online-accounts test to validate the new single baseline instead of forcing the obsolete pre-transition modulus path.

### 2026-04-20 - core: use minting group rules from genesis

Changed Qortium so minting-group membership is the baseline minting rule from genesis, removed the inherited temporary name-check rollout and later group-check activation triggers, updated block processing and online-account validation to use the minting-group path directly, and adjusted the test-chain genesis data so the default fork baseline includes a dedicated minting group from block 1.

### 2026-04-20 - core: remove ignoreLevelForRewardShareHeight trigger

Changed Qortium so minting level no longer gates ordinary minting or reward-share creation, removed the later trigger that had switched the chain onto that model, and kept the remaining name and minting-group rules as the live restrictions instead so the fork now treats group-based minting eligibility as the long-term baseline instead of preserving a later level-ignoring transition height.

### 2026-04-20 - core: remove onlineAccountMinterLevelValidationHeight trigger

Changed Qortium so the inherited online-account rule that rejects level-zero reward-share minters now applies from genesis until the later point where reward-share level is deliberately ignored again, and removed the separate activation height from the main chain and mirrored test chain configs so online-account block building and validation no longer preserve that extra historical branch.

### 2026-04-20 - core: remove transfer privs disable window

Changed Qortium so the temporary transfer-privileges freeze tied to the old penalty-fix intervention window is gone, removed the related start and end timestamps from the main chain and mirrored test chain configs, and left ordinary transfer-privs validation to follow the remaining stable rules instead of a historical February 2024 shutdown period.

### 2026-04-20 - core: remove rewardshare disable window

Changed Qortium so the temporary reward-share shutdown window is gone, removed the related disable and re-enable heights from the main chain and mirrored test chain configs, and left reward-share creation and cancellation to follow the remaining long-term validation rules instead of a historical block-height pause that had been inserted during later intervention work.

### 2026-04-20 - core: remove unconfirmableRewardSharesHeight trigger

Changed Qortium so reward-share transactions and transferred-privilege transactions are now blocked during the batch reward online-account capture and distribution window whenever batch rewards are active, instead of waiting for a separate later trigger height, and removed the related trigger from the main chain and mirrored test chain configs so that protection now follows the batch reward system directly.

### 2026-04-20 - core: remove rewardShareLimitTimestamp trigger

Changed Qortium so the extra inherited rule that reserved one reward-share slot for a self-share after a later timestamp is gone, leaving only the ordinary maximum reward-share cap in place for non-founder accounts, and removed the related trigger from the main chain and mirrored test chain configs so reward-share creation no longer depends on that additional policy cutoff.

### 2026-04-20 - core: use V2 reward shares from genesis

Changed Qortium so the fork no longer carries the old two-stage minting reward schedule, collapsed the account-level share table to the later V2 percentages from genesis, fixed the legacy QORA-holder reward share to its reduced 1% baseline from genesis too so the reward pool stays internally consistent, removed the old transition config from the main chain and mirrored test chain configs, and updated the reward tests to match the new single baseline instead of orphaning across the inherited activation boundary.

### 2026-04-20 - tests: fix batch reward minted-block assertions

Changed the batch reward test so it now measures Alice's minted-block count relative to the seeded starting value provided by the test chain's genesis `ACCOUNT_LEVEL` transactions instead of assuming pre-seeded accounts always start from zero minted blocks, which keeps the test aligned with the earlier `blocksMinted` seeding cleanup.

### 2026-04-20 - core: remove blocksMintedAdjustment state

Changed Qortium so the old split between real minted blocks and synthetic minted-block adjustments is gone, folded that synthetic progress into ordinary `blocksMinted` for genesis account-level bootstrapping and transferred privileges, removed the adjustment field from account state, lite-node account messages, sponsorship and mintership reports, and transfer-privs history, and removed the related trigger from the main chain config and mirrored test chain config so account level recalculation now always uses one consistent minted-block total.

### 2026-04-20 - core: remove fixBatchRewardHeight trigger

Changed Qortium so the later batch-reward compatibility gate is gone and the batch reward, share-bin, and minted-block accounting paths now follow the existing minting-group membership rules directly instead of preserving a separate later activation height, and removed the related trigger from the main chain config and mirrored test chain configs so the batch reward system no longer carries that extra historical branch.

### 2026-04-20 - core: remove shareBinFix trigger

Changed Qortium so minting reward share bins always use the corrected level-to-bin lookup from genesis instead of preserving the older off-by-one behavior that shifted even-numbered levels into the next reward bin until a later trigger height, and removed the related trigger from the main chain config and mirrored test chain configs so reward distribution no longer depends on that inherited compatibility branch.

### 2026-04-20 - data: remove blocksMintedPenalty state

Changed Qortium so the old blocks-minted penalty field and its remaining plumbing are fully gone, removed the stored account column, the lite-node account message field, the penalty-specific API endpoints and models, the dead penalty values from sponsorship and mintership reports, and the last unused penalty-fix config and test-fixture leftovers, which completes the cleanup after the earlier behavior-only removal and leaves the fork without any leftover self-sponsorship penalty state.

### 2026-04-20 - core: remove blocksMintedPenalty behavior

Changed Qortium so the remaining historical penalty value no longer affects founder minting privileges, reward-share eligibility, effective minting level, transfer-privs validation, account level recalculation, or poll vote weighting, while deliberately leaving the stored penalty field and related reporting/state plumbing in place for now so behavior is removed first without yet changing the underlying data model.

### 2026-04-20 - core: remove selfSponsorshipAlgoV3Height trigger

Changed Qortium so the final historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger and the last remaining self-sponsorship snapshot plumbing from the main chain config and mirrored test chain configs, dropped the V3-only cleanup code and tests, and removed the temporary reward-share confirmation freeze tied to that inherited intervention window because a new-chain fork does not need to preserve the final retrospective sweep.

### 2026-04-20 - core: remove selfSponsorshipAlgoV2Height trigger

Changed Qortium so the second historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger and V2-only snapshot/config plumbing from the main chain config and mirrored test chain configs, dropped the V2-only cleanup code and tests, and removed the temporary V2-era freezes on asset transfers, name registration, and reward-share confirmation because a new-chain fork does not need to preserve that inherited anti-abuse intervention window.

### 2026-04-20 - core: remove selfSponsorshipAlgoV1Height trigger

Changed Qortium so the first historical self-sponsorship penalty sweep no longer exists as a special one-time block event, removed the related trigger from the main chain config and mirrored test chain configs, and dropped the V1-only cleanup code and tests because a new-chain fork does not need to preserve that inherited retrospective run.

### 2026-04-20 - core: remove atValidateHeight trigger

Changed Qortium so AT deployment now depends only on structural validity instead of preserving the later upstream code-hash allowlist that restricted deployments to a curated set of approved AT programs, and removed the related trigger from the main chain config and mirrored test chain config so any structurally valid AT can be deployed from genesis.

### 2026-04-20 - core: remove adminQueryFixHeight trigger

Changed Qortium so group admin lookups always use the correct address-specific repository query instead of preserving the older upstream fallback that could return the wrong admin row for a group and corrupt cached admin references during orphaning, removed the related trigger from the main chain config and mirrored test chain configs, and tightened the group orphaning tests to verify the right admin reference is restored.

### 2026-04-20 - core: remove nullGroupMembershipHeight trigger

Changed Qortium so null-owned groups always use the approval-based membership rules instead of preserving the older upstream split where invites could bypass approval while kicks and bans were blocked until a later trigger height, and removed the related trigger from the main chain config and mirrored test chain configs so the null-owner governance model applies from genesis.

### 2026-04-20 - core: remove cancelSellNameValidationTimestamp trigger

Changed Qortium so cancel-sell-name transactions always require the target name to be actively for sale instead of preserving the older upstream exception that could let duplicate sale cancellations validate after the sale had already been removed, and removed the related trigger from the main chain config and mirrored test chain configs so the stricter rule applies from genesis.

### 2026-04-20 - core: remove chatReferenceTimestamp trigger

Changed Qortium so optional chat reply references are treated as part of the baseline CHAT transaction format instead of preserving the older upstream split between pre-reference and post-reference chat serialization, removed the related trigger from the main chain config and mirrored test chain configs, and kept the existing nullable chat-reference storage and search behavior available from genesis.

### 2026-04-20 - core: remove feeValidationFixTimestamp trigger

Changed Qortium so reward-share transactions always enforce the corrected fee-balance rule from genesis instead of preserving the older upstream exemption that let an initial self-share skip the normal “can this declared fee actually be paid” check, while keeping the existing zero-fee self-share behavior and removing the related trigger from the main chain config and mirrored test chain configs.

### 2026-04-20 - core: remove disableReferenceTimestamp trigger

Changed Qortium so transaction references are treated as a baseline fixed-size field instead of preserving the older upstream switch from strict last-reference matching to relaxed validation, removed the related trigger from the main chain config and mirrored test chain configs, and simplified transaction validation so any non-null 64-byte reference now follows the same rule from genesis.

### 2026-04-19 - core: remove transactionV5Timestamp trigger

Changed Qortium so transaction version 5 is treated as the baseline instead of preserving the older upstream switch from version 4 to version 5, removed the related trigger from the main chain config and all mirrored test chain configs, and cleaned up the arbitrary transaction and message-building paths so they now follow the baseline version rules directly.

### 2026-04-19 - core: remove calcChainWeightTimestamp trigger

Changed Qortium so chain-weight comparisons always use the same shared block range instead of preserving the older upstream rule that could keep counting extra blocks on a longer chain, and removed the related trigger from the main chain config and all mirrored test chain configs so peer sync decisions now follow the cleaner baseline behavior everywhere.

### 2026-04-19 - core: remove newBlockSigHeight trigger

Changed Qortium so block minter signatures always use the full previous block signature instead of preserving the older upstream split behavior that only used the first half of the parent signature, and removed the related trigger from the main chain config and all mirrored test chain configs so the fork starts with the stronger baseline rule everywhere.

### 2026-04-19 - core: remove atFindNextTransactionFix trigger

Changed Qortium so ATs always use the corrected “find the next transaction after this timestamp” behavior instead of preserving the old upstream off-by-one compatibility branch, and removed the related trigger from the main chain config and all mirrored test chain configs because a new-chain fork does not need to replay that historical bug.

### 2026-04-20 - core: allow multiple names from genesis

Changed Qortium so accounts can own multiple names from genesis instead of preserving the old one-name-per-account transition height, made primary-name bookkeeping active from genesis for name registration, rename, buy, and orphan flows, removed the dead `oneNamePerAccount` config flag and the old one-time primary-name migration hook, and updated chat-name lookups and naming tests to use the primary-name model as the baseline behavior.

### 2026-04-19 - network: move initial peers into settings

Changed Qortium so the initial peer list now comes from an optional
`initialPeers` array in `settings.json` instead of shipping built-in Qortal
seed nodes, which keeps first-run and bootstrap-created peer lists under the
operator’s control while still preserving the existing behavior of only seeding
peers when the local peer database is empty.

### 2026-04-19 - core: disable automatic bootstrap by default

Changed Qortium so automatic bootstrap recovery is off unless an operator
explicitly turns it on and provides bootstrap hosts in `settings.json`,
removed the built-in upstream bootstrap host defaults, made missing-host
bootstrap requests fail immediately instead of entering a broken retry path,
and kept the inherited manual/bootstrap path available only when someone has
deliberately configured it.

### 2026-04-19 - core: disable automatic updates by default

Changed Qortium so automatic updates are off unless an operator explicitly
turns them on and provides update mirror URLs in `settings.json`, which stops
the fork from shipping upstream Qortal update endpoints as defaults while still
leaving the inherited manual opt-in update flow available until a better
Qortium-specific update process is designed.

### 2026-04-19 - docs: add Qortium changelog baseline

Added the main human-readable Qortium change log, documented what Qortium is
and what the early goals of the fork are, and set the basic rule that each
future Qortium commit should add one plain-language entry that matches the git
commit message so people can follow the project history without reading code.
