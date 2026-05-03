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
