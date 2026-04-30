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
