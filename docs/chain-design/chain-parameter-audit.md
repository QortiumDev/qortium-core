# Chain Parameter Audit

Date: 2026-05-20

Branch: `main`

Review context: current Qortium fork cleanup after the 6.1.5 integration,
single-node testnet work, documentation reorganization, and trusted-seed launch
model decision.

## Purpose

Qortium's direction is to become a cleaner baseline for teams that want to fork
Qortal without inheriting Qortal's chain-specific history, governance seed data,
branding, and runtime assumptions.

This document is an inventory and priority guide. It does not by itself change
runtime behavior. Production chain config edits, genesis edits, test fixture
cleanup, API compatibility changes, and broad branding work should remain
separate commits.

## Current Status

Several earlier fork-cleanup targets are now mostly complete:

- the protocol native asset ID is named `Asset.NATIVE` in core code
- the main `blockchain.json` no longer seeds a QORT native asset
- runtime native-asset bootstrap is possible through a development-group
  approved `ISSUE_ASSET` transaction when asset ID `0` does not exist
- mainnet and testnet peer-network magic values are chain-configured and use
  Qortium defaults
- runtime file names, default ports, startup scripts, Docker names, and build
  metadata now use Qortium defaults
- QDN app names have moved to neutral `qdn://`, `qdnRequest`, and `.qdn`
  surfaces without legacy Qortal aliases
- the trust-network launch model is now documented as a trusted-seed model,
  with early Minting group members acting as the practical trust seed set
- the first on-chain parameter update path exists for development-group
  approved block reward changes with explicit activation heights

The remaining high-priority work is not the native asset constant. The next
cleanup target is the production chain configuration and the genesis/governance
shape it implies.

## Highest Priority

### Production Chain Defaults

`src/main/resources/blockchain.json` is still the main production chain
configuration. It currently has Qortium naming, but several values still look
like inherited or partially edited launch parameters rather than deliberate
Qortium defaults:

- `mempowTransactionUpdatesTimestamp` is set to an inherited timestamp
- `blockRewardBatchStartHeight` is set to an inherited high activation height
- `featureTriggers.transactionV6Timestamp` is deferred far into the future
- the reward curve still uses inherited height intervals and reward values
- account-level share bins, activation thresholds, and block-count levels still
  carry inherited economic assumptions
- development and minting group IDs are fixed as consensus-facing group IDs

Recommendation:

- decide whether `src/main/resources/blockchain.json` is Qortium's intended
  launch config or a neutral fork template
- for a clean Qortium launch config, make feature activation heights and
  timestamps explicit launch choices instead of inherited values
- review the reward curve, account levels, share bins, and fee settings as
  economics, not cleanup trivia
- keep any production chain-config edits separate from test fixture cleanup

### Genesis Governance And Trusted Seeds

The main genesis currently creates `development` and `minting` groups from the
null account public key. Those groups are open, use `PCT40` approval, and are
referenced by `devGroupIds` and `mintingGroupIds`.

The trusted-seed launch decision means this is acceptable only if it is an
intentional launch model: early Minting group membership is socially trusted,
and that group is the practical trust seed set. It should not be treated as an
accidental leftover from Qortal.

Recommendation:

- choose and document the intended main genesis profile before editing it
- valid profiles include no seed governance, public bootstrap groups, one seed
  admin group, or custom fork-supplied groups
- if Qortium keeps public development and minting groups in the main config,
  document that as the default trusted-seed launch profile
- if Qortium wants a reusable neutral template, move launch-specific groups into
  an example config or generator before calling the baseline reusable

## Completed Or Lower-Risk Tracks

### Native Asset Identity

Core code now uses `Asset.NATIVE = 0L` for the protocol native asset ID. The old
`Asset.QORT` compatibility alias has been removed, and the main genesis no
longer creates a QORT asset.

The remaining native-asset work is mostly display and API wording:

- avoid hardcoded QORT labels in schema text and examples
- source future display labels from chain or application identity metadata if a
  user-facing asset ticker is needed
- keep `Asset.NATIVE` as the protocol primitive unless there is a separate
  migration plan

### Network, Runtime, And Build Identity

Network magic, runtime defaults, Docker/startup paths, jar naming, Maven
artifact identity, and Swagger title are already Qortium-oriented.

The Java package namespace and main class now use `org.qortium`, matching the
Maven artifact identity and active Qortium runtime naming.

### Test Chain Fixtures

The `src/test/resources/test-chain-v2*.json` fixtures intentionally seed
deterministic accounts, native asset ID `0`, funded balances, fixture groups,
and reward-share keys for tests. Some fixture names still use `dev-group` and
`minter-group`, and many addresses are inherited deterministic test identities.

Recommendation:

- keep deterministic keys, balances, and reward-share records where tests need
  them
- rename fixture labels and comments to neutral terms where this does not
  obscure test intent
- do not mix test fixture cleanup with production `blockchain.json` or genesis
  changes

### Cross-Chain And API Naming

The active cross-chain API schema now uses local-chain wording instead of QORT
wording for supported trade modes. Some deeper cross-chain classes and ACCT
names still reflect inherited implementation history, but those are not
immediate consensus parameters.

Recommendation:

- keep schema descriptions and display text neutral
- keep ACCT registry cleanup separate from supported foreign-chain inventory

### UI, QDN, Documentation, And Branding

The top-level README, documentation index, and many user-facing Qortium paths
have been refreshed. Some inherited Qortal wording remains in upstream
reference docs, QDN/Q-App compatibility text, UI/tray resources, icons,
installer material, comments, and tests.

Recommendation:

- leave upstream reference docs clearly labeled rather than rewriting their
  historical content
- handle tray/UI/icon/installer branding in a dedicated packaging pass
- defer full Java package rename to a mechanical package-identity commit

## Suggested Implementation Order

1. Refresh production `blockchain.json` defaults: activation heights,
   timestamps, reward curve, account levels, fee assumptions, and share bins.
2. Decide and clean the main genesis governance profile: no seed governance,
   public bootstrap groups, one seed admin group, or custom fork-supplied
   groups.
3. Clean test fixtures separately from production genesis and economics.
4. Clean API/schema wording and cross-chain naming without breaking public
   fields unless a compatibility strategy is chosen.
5. Run broad branding, UI, installer, and Java package cleanup after consensus
   and runtime assumptions are no longer hardcoded.

## Working Rules For This Cleanup

- Treat production `blockchain.json` edits as consensus-facing changes.
- Keep each logical change in its own commit with a matching
  `QORTIUM-CHANGELOG.md` entry.
- Do not combine production genesis changes with fixture renames.
- Do not combine API compatibility decisions with comment or branding cleanup.
- Run focused tests for any code/config behavior change; docs-only updates need
  at least `git diff --check`.
