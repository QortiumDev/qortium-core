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
