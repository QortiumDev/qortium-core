# Design: Dev-Group Approval Split (member-decided governance vs admin-decided operations)

Status: **Draft — agreed direction, pre-implementation** (2026-07-10)
Author: prepared with QuickMythril
Scope: Qortium Core group approval (`org.qortium.group`, `org.qortium.transaction`)
Target release: 1.4.0 (branch `1.4.0-prerelease`)
Feature trigger: `devGroupApprovalSplitHeight` = **60,000** (Previewnet; movable in a later release as long as nodes update before activation)

---

## 1. Problem & goal

The DEV group is null-owned and doubles as the approval authority for Qortium
auto-updates and chain-parameter updates. Today the group has **no admins**, so
group approval falls back to "all non-null members vote" for everything.

If we appoint admins under current rules, *all* approval authority — including
the votes that add or remove admins — transfers permanently to the admin set,
because the Core has exactly one authority set per group
(`Group.canApprove()` / `Group.countApprovalAuthorities()`): usable admins if
any exist, otherwise (for null-owned groups) all non-null members. Appointing
the first admins would therefore hand the admins control over their own
membership, kicks, bans, and the group's voting rules.

**Goal:** split approval authority by transaction type for dev groups, so that:

- **admins** approve the operational/technical artifacts the group exists to
  vouch for (auto-updates, chain parameters, assets, and anything else routed
  through the group for endorsement), and
- **members** decide governance — who the admins are, membership (kicks, bans,
  invites), and the group's own settings — so admins serve at the members'
  pleasure and cannot entrench themselves.

This is option **B2** from the community governance discussion
(2026-06-15 summary post; community responses 2026-06-25 — Native, Raven,
7R15M3G157U5, Corvid), refined here into a concrete per-transaction-type
matrix.

## 2. Principles

1. **Members keep ultimate sovereignty.** Every lever that controls *who
   governs* (admin add/remove, kicks, bans, invites) and *how votes work*
   (`UPDATE_GROUP`: approval threshold, block delays, isOpen) stays
   member-decided. Admins can never vote themselves unremovable.
2. **Admins gate what the group vouches for.** Routing a transaction through
   the dev group means asking the group to endorse an artifact; that technical
   review is the admins' job.
3. **One clean rule, no field inspection.** The category is decided purely by
   transaction type — no per-field branching inside consensus validation.
4. **Minimal consensus delta.** Same threshold enum, same one-address-one-vote
   counting, same expiry semantics, same no-admins fallback. Only the
   *authority set selection* becomes category-aware.
5. **Dev groups only.** Ordinary groups are untouched; the split keys off the
   existing height-indexed `devGroupIds` chain config.

## 3. Current behavior (baseline)

- ~19 transaction types are approval-capable. Setting a nonzero `txGroupId`
  on one of them routes it through that group for approval; with `txGroupId =
  NO_GROUP` (0) nothing here applies (`requireGroupForApproval` is false on
  Previewnet, so group 0 remains legal for everything).
- For a null-owned group, *every* approval-capable transaction — even one
  created by an admin — sits `PENDING` until the authority set approves it;
  admin auto-approval is deliberately disabled (`Transaction.setInitialApprovalStatus()`).
- Authority set (`Group.canApprove()`): non-null owner → admins; null owner →
  usable admins if any exist, else all non-null members.
- Counting (`Transaction.getApprovalDecision()`): one current authority
  address = one vote; integer count vs the group's PCT threshold
  (`approvals × 100 ≥ authorities × pct`). No stake/trust/blocksMinted
  weighting anywhere in group approval.
- A `false` vote records opposition but cannot reject; pending transactions
  end `APPROVED`, `EXPIRED` (via `maximumBlockDelay`), or `INVALID`.
- DEV group settings: null-owned, `PCT40`, min/max approval delay 10/1440
  blocks.
- Auto-updates are `ARBITRARY` transactions with `service = AUTO_UPDATE` in an
  active dev group; `AutoUpdate` only acts on ones with status `APPROVED`.
- `UPDATE_GROUP` payload: name, description, isOpen, approvalThreshold,
  min/max block delay. It **cannot** change the owner — the group cannot be
  "de-nulled" by any currently existing transaction.
- Proposal rights today: any group member can create `UPDATE_GROUP`,
  `ADD_GROUP_ADMIN`, `REMOVE_GROUP_ADMIN` for a null-owned group (they sit
  pending). But `GROUP_KICK`, `GROUP_BAN`, `CANCEL_GROUP_BAN`, `GROUP_INVITE`,
  `CANCEL_GROUP_INVITE` require the *proposer* to already be in the current
  authority set — so once admins exist, only admins could even propose a kick.

## 4. Design

### 4.1 Two approval categories

From `devGroupApprovalSplitHeight`, every approval-capable transaction type
belongs to one of two categories **when its `txGroupId` is an active dev
group**:

| Category | Authority | Transaction types |
|---|---|---|
| **GOVERNANCE** (member-decided) | all non-null members | `UPDATE_GROUP`, `ADD_GROUP_ADMIN`, `REMOVE_GROUP_ADMIN`, `GROUP_KICK`, `GROUP_BAN`, `CANCEL_GROUP_BAN`, `GROUP_INVITE`, `CANCEL_GROUP_INVITE` |
| **OPERATIONS** (admin-decided) | usable admins | everything else approval-capable, incl. `ARBITRARY` (auto-updates), `CHAIN_PARAMETER_UPDATE`, `ISSUE_ASSET`, `UPDATE_ASSET`, `DEPLOY_AT`, `REGISTER_NAME`, `UPDATE_NAME`, `CREATE_POLL`, `UPDATE_POLL`, `CREATE_GROUP`, `MESSAGE` |

The rule is: **the eight group-management types are member-decided; any other
transaction routed through a dev group for approval is admin-decided.** New
approval-capable types default to OPERATIONS unless explicitly classified as
governance.

Rationale for the two members of the table people question:

- `UPDATE_GROUP` is GOVERNANCE because its sensitive fields (approval
  threshold, min/max delay, isOpen) are the meta-parameters of the members'
  own votes. Admin control of those would allow entrenchment (e.g. raise the
  threshold to PCT100 so admin-removal can never pass). The cosmetic fields
  (name, description) ride along; per-field splits were rejected (Principle 3).
- `MESSAGE`, `CREATE_GROUP`, names, polls, ATs are not "dev features" — they
  are opt-in oddities that are *already* approval-gated today when someone
  sets a dev `txGroupId`. They land on the OPERATIONS side because approving
  them is vouching, and because it keeps the rule a single line.

### 4.2 Fallback when no usable admins exist (bootstrap)

Unchanged: if a null-owned dev group has zero usable admins, **both**
categories fall back to all non-null members — exactly today's behavior. This
is the bootstrap path for voting in the first admins. The split only engages
while at least one usable admin exists.

Admins are also members, so they vote in GOVERNANCE decisions as ordinary
members (one vote each).

### 4.3 Proposal rights

From the trigger height, **any group member may create** any of the eight
GOVERNANCE types targeting a dev group (the five kick/ban/invite validators
drop their "creator must be in the current authority set" requirement for
active dev groups; `UPDATE_GROUP` / `ADD_GROUP_ADMIN` / `REMOVE_GROUP_ADMIN`
already allow this). Proposals cost a fee and expire unapproved, so spam is
self-limiting.

OPERATIONS proposal rights are unchanged (any member, plus each type's
object-specific rules — name ownership, asset ownership, funding, etc.).

Note: an existing validator rule says an admin cannot be *kicked* while they
hold admin status (only the group owner could, and a null-owned group has no
usable owner). That stays: removing a misbehaving admin is a member-decided
`REMOVE_GROUP_ADMIN` first, then (if desired) a kick/ban.

### 4.4 Counting, thresholds, expiry — unchanged semantics

- One address = one vote; the group's single threshold (`PCT40` for the DEV
  group) applies to whichever denominator the category selects: GOVERNANCE
  counts non-null members, OPERATIONS counts usable admins.
- No weighting (see § 6), no per-category thresholds, no vote time-limit or
  "no"-vote rejection changes — all explicitly deferred.
- Note for operators: with 2 admins, one approval passes PCT40 (1/2 ≥ 40%).
  Plan to seat **at least 3 admins**; the threshold itself remains
  member-adjustable via GOVERNANCE `UPDATE_GROUP`.

### 4.5 Scope: dev groups only

The category logic applies only when the transaction's `txGroupId` is an
active dev group per the height-indexed `devGroupIds` chain config *and* the
group is null-owned. All other groups keep the existing single-authority
behavior. If a future need appears for ordinary groups, the same category
logic can be fed by a per-group setting (e.g. a `CREATE_GROUP`/`UPDATE_GROUP`
flag) — recorded as future work, not designed here.

### 4.6 Activation

- New `featureTriggers` entry `devGroupApprovalSplitHeight: 60000` in the
  Previewnet chain config. `featureTriggers` is excluded from the chain-config
  peering hash, so scheduling (or later moving) this height does not split
  peering — but it is still a consensus flag-day for approval counting: **all
  nodes must run 1.4.0+ before height 60,000** (same rollout discipline as the
  55,000 batch-rewards trigger; 60,000 leaves buffer after it).
- Approval decisions are evaluated per block against the current authority
  set, so from the trigger height the new categorization applies to **all**
  pending proposals, including ones submitted earlier. No migration or dual
  bookkeeping. A proposal mid-vote at the boundary may see its eligible-voter
  set change; with 10/1440-block delays the exposure window is ≤ ~1440 blocks
  and is handled operationally (don't leave sensitive proposals straddling
  the boundary).
- Before the trigger — and on any group with no usable admins — behavior is
  byte-for-byte today's.

## 5. Implementation sketch

1. **Category enum + mapping** — a `GroupApprovalCategory` (GOVERNANCE /
   OPERATIONS) resolved from `TransactionType` in one place (likely on the
   `TransactionType` enum or a static map beside `Group`), with OPERATIONS as
   the default.
2. **Authority selection becomes category-aware** — `Group.canApprove()` and
   `Group.countApprovalAuthorities()` (and the HSQLDB queries backing them)
   gain the pending transaction's type / category as input, applied only when
   (a) height ≥ `devGroupApprovalSplitHeight`, (b) the group is an active dev
   group, (c) the group is null-owned, and (d) usable admins exist.
   `GroupApprovalTransaction` validity ("is this signer allowed to vote on
   that pending tx") flows through the same path.
3. **Approval counting** — `Transaction.getApprovalDecision()` and the
   repository query that fetches latest votes filter qualifying voters by the
   category authority set.
4. **Proposal-right relaxation** — `GroupKickTransaction`,
   `GroupBanTransaction`, `CancelGroupBanTransaction`,
   `GroupInviteTransaction`, `CancelGroupInviteTransaction`: for active
   null-owned dev groups at/after the trigger, require group membership
   instead of current-authority membership.
5. **Chain config** — add `devGroupApprovalSplitHeight` to `featureTriggers`
   in `previewchain.json` (+ `BlockChain` accessor and feature-trigger test
   coverage).
6. **Tests** — extend `DevGroupAdminTests`: with admins seated, (a) an
   OPERATIONS tx (e.g. auto-update `ARBITRARY`) approvable only by admins and
   member votes don't count toward it; (b) a GOVERNANCE tx (e.g.
   `REMOVE_GROUP_ADMIN`, `GROUP_KICK`) approvable only by members at the
   member denominator, proposable by a non-admin member; (c) fallback with
   zero usable admins identical to pre-trigger behavior; (d) pre-trigger
   heights byte-identical to current behavior; (e) boundary case: proposal
   created before the trigger, decided after.

## 6. Deferred / out of scope (decisions of 2026-07-10)

| Item | Decision |
|---|---|
| Trust-adjusted `blocksMinted` weighting for member votes (community "option 3"; formula exists in `AccountTrustPolicy` and is used by poll freezing + resource ratings, but nowhere in group approval today) | Deferred — v1 stays one-address-one-vote |
| Per-category thresholds (e.g. stricter PCT for OPERATIONS) | Deferred — single group threshold for both |
| Vote time-limit / re-vote procedure (Native's request); "no"-vote rejection | Deferred — expiry via `maximumBlockDelay` unchanged |
| Split for ordinary (non-dev) groups via a per-group setting | Future work if a use case appears |
| Group ownership transfer ("de-nulling") | Not possible today; a future group-sale transaction must revisit this design |

## 7. Decision log

- 2026-06-15 — governance options framed (Codex session); "B2" named: admins
  handle some approvals, all members handle others; requires a Core change.
- 2026-06-25 — community QDN thread: consensus leaning B2 + admins drawn from
  contributing coders + vote time-limit; Raven & 7R15M3G157U5 preferred
  "B2 and 3" (weighting); Corvid's 60%-of-admins admin selection superseded
  by keeping admin add/remove member-wide.
- 2026-07-10 — matrix finalized (this doc): eight management types
  member-decided incl. `UPDATE_GROUP`, invites and cancel-ban member-wide;
  everything else admin-decided; no weighting, single threshold, dev groups
  only, expiry semantics unchanged; feature height 60,000.
