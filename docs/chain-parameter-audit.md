# Chain Parameter Audit

Date: 2026-04-30

Branch: `chain-parameter-audit`

Baseline commit: `e15be13b67f44df70c4d9f19723652721fa9d192`

Baseline build: `mvn clean package` passed on 2026-04-30.

## Purpose

Qortium's current direction is to become a cleaner baseline for teams that want
to fork Qortal without inheriting Qortal's chain-specific history, governance
seed data, branding, and runtime assumptions. This audit records the remaining
hardcoded areas found before the next extraction pass.

This document is an inventory and priority guide. It does not by itself change
runtime behavior.

## Highest Priority

### Main Chain Genesis And Governance

`src/main/resources/blockchain.json` no longer seeds a QORT native asset, but
it still contains consensus-defining genesis group data:

- neutral development and minting group creation entries, currently owned by the
  null account and open for public membership
- group seed choices that are still consensus-defining rather than generated
  from an explicit fork template

These are consensus-defining defaults. A reusable baseline should either ship a
neutral example genesis or make these values clearly supplied by a fork's chain
configuration process.

Initial recommendation:

- keep asset ID `0` as the native-asset protocol primitive
- let the first issued asset create asset ID `0` instead of seeding QORT in
  genesis
- stop treating `QORT` as the hardcoded name of the native-asset primitive
- continue reducing inherited main genesis seed data into a neutral baseline or
  generated fork template
- make the governance group seed model explicit enough that a new chain can
  choose no seed governance, public bootstrap groups, one seed admin group, or
  its own configured groups

### Native Asset Identity

`src/main/java/org/qortal/asset/Asset.java` now exposes `Asset.NATIVE = 0L`,
with `Asset.QORT` kept as a temporary compatibility alias. Many comments, docs,
test fixtures, and ACCT classes still describe the native asset as QORT.

The protocol still needs a stable native asset ID, but the display name should
not be fixed to QORT in fork-facing code.

Initial recommendation:

- keep using neutral naming in core code for the native asset ID
- preserve compatibility aliases only where needed during migration
- source display labels like `QORT` from chain config or application identity
  metadata
- update comments and docs after the runtime constant is neutralized

### Runtime Defaults And Data Locations

`src/main/java/org/qortal/settings/Settings.java` still hardcodes Qortal
defaults:

- P2P ports `12392` and `62392`
- API ports `12391` and `62391`
- developer proxy ports `12393` and `62393`
- `QortalKeyStore.jks`
- `qortal-backup`

Docker, startup scripts, and compose files also still use `/qortal`,
`qortal.jar`, `qortal-core`, `qortal-net`, and `QORTAL_*` environment names.

Initial recommendation:

- separate consensus chain parameters from application packaging identity
- add a single application identity source for product name, data directory,
  jar name, backup path, and default Docker names
- avoid changing network ports until the intended fork baseline values are
  decided

## Medium Priority

### Build, API, And Package Identity

`pom.xml` still builds `org.qortal:qortal`, uses the `org.qortal` Java package
namespace, sets Swagger output to `Qortal API Documentation`, and points the
main class at `org.qortal.controller.Controller`.

This is broad and touches imports throughout the tree. It is not the first
consensus-risk item, but it is part of making Qortium feel like a real baseline
instead of a lightly edited Qortal checkout.

Initial recommendation:

- defer full package rename until after chain parameters are extracted
- change low-risk build metadata first, such as artifact name and generated API
  title
- only rename Java packages in a dedicated mechanical commit after tests are
  stable

### Test Chain Fixtures

The `src/test/resources/test-chain-v2*.json` fixtures still seed `QORT`,
`dev-group`, `minter-group`, Qortal-style addresses, and fixed reward-share
keys. The shared Java test helpers also carry inherited fixture identities.

Some fixed keys are useful for deterministic tests, so the goal should be
neutral fixture naming rather than removing all fixture data.

Initial recommendation:

- keep deterministic keys where they are test mechanics
- rename fixture groups and comments to neutral terms
- make native asset labels follow the same path as production chain config
- avoid mixing test fixture cleanup with production genesis changes

### Cross-Chain ACCT Coupling

The v3 ACCT classes still describe trades in terms of Qortal-side addresses,
QORT amounts, `Asset.QORT`, `qortalAtAddress`, and `SupportedBlockchain`.

This area mixes two concerns:

- which external blockchains are supported
- which ACCT implementations are active for this chain

Initial recommendation:

- split the active ACCT registry from the broad supported-blockchain enum
- keep the existing v3 implementations while the registry is introduced
- later rename Qortal-specific field names only when API compatibility strategy
  is clear

## Lower Priority

### UI, Q-Apps, Documentation, And Branding

The repository still contains Qortal names and assets in the README, tray UI,
icons, Q-Apps browser helpers, demo resources, shell scripts, installer files,
and user-facing text.

These should be cleaned up, but most are not consensus parameters. The safest
approach is to handle them in branding-focused commits after the consensus and
runtime identity decisions are clearer.

Initial recommendation:

- update top-level README and user-visible Qortium messaging early
- replace tray labels and icons in a UI/packaging commit
- treat `qortal://` and `qortalRequest` as compatibility-sensitive Q-Apps API
  names until a migration strategy exists

### API Examples

Many API schema annotations still use Qortal public keys and addresses as
examples. These do not define runtime behavior, but they make the baseline look
chain-specific.

Initial recommendation:

- replace examples with neutral fixture identities
- keep example values syntactically valid for the current address format
- do this after fixture naming is settled

## Suggested Implementation Order

1. Make native asset display metadata come from chain configuration.
2. Continue replacing or templating the remaining main `blockchain.json` genesis
   seed data.
3. Clean test fixtures separately from production genesis.
4. Add application identity defaults for backup paths, data paths, jar naming,
   Docker names, and API documentation title.
6. Split ACCT registration from the supported external blockchain list.
7. Run broad branding and package cleanup after the core assumptions are no
   longer hardcoded.

## Current Notes

The branch was created with only `temp.txt` untracked before this audit. That
file appears to be local working notes and is intentionally not included here.
