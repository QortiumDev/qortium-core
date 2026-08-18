# Portable Chat Core Roadmap

Status: active planning and implementation tracker
Baseline: Qortium Core `076918d22ac88992f39128f8f60d326b2cbc8210`
(C0-C4 merged after `v1.7.1`)
Order: Qortium Core first, Qortium Home second, Chat app last

## Purpose

This roadmap tracks the Qortium Core work needed before Home can provide the
same Qortium chat features through local, authenticated custom, unauthenticated
custom, and public nodes.

Feature parity across those routes is a non-negotiable product requirement.
The default public-node profile must provide every safe protocol primitive Home
needs for public groups, closed/private groups, and direct messages. A public
node is a network-serving node, not a reduced read-only edition of Qortium.
Desktop and Android must expose the same end-user Chat features regardless of
whether Home uses a local, authenticated custom, unauthenticated custom, or
public node.

Node operators remain free to remove routes in their own settings. That is an
explicit operator opt-out which Home reports as a route capability error; it is
not the shipped default, the product target, or a second lower-capability Chat
tier.

The main Core gap is portable private-group key recovery. QPGC v1 already
encrypts closed-group messages and supplies local private APIs, but ordinary
public chat history deliberately hides key announcements and requests. A Home
client using a public node therefore cannot recover the wrapped key needed to
decrypt an otherwise retained private message.

This work exposes only bounded, signed protocol data. Core never returns a raw
group key or plaintext private message through a public endpoint. Home remains
the trusted account, private-key, encryption, decryption, approval, proof-of-
work, and signing boundary. Chat remains an untrusted QDN app and receives only
high-level plaintext results authorized for the selected account.

## Scope decisions

- Qortal Core is out of scope. Qortal General Chat was removed intentionally;
  no Qortal Core change or PR will restore native group-zero behavior.
- A future General-like Qortal surface may investigate the historical
  FreeChat/old-Qortal-Home `MESSAGE`-wrapped CHAT convention, but that belongs
  to later Home and Chat work.
- No block-consensus or chain-configuration change is planned here. CHAT is
  transient/off-block and QPGC metadata remains inside the existing CHAT data
  envelope.
- Public websocket support is optional. Bounded Home polling is sufficient for
  completion.
- QPGC v2 for more than 39 members is deferred. Older nodes reject unknown QPGC
  versions, so that would require a separately coordinated Core rollout.
- Retained CHAT remains a roughly 24-hour transient window, not a durable
  mailbox. The retention change below only keeps a key announcement while a
  retained message still depends on it.
- Default public access is capability-complete but least-authority: Core exposes
  bounded reads and unsigned builders, while Home performs account-bound crypto,
  proof of work, field attestation, signing, and approval on the user's device.
  Security controls limit abuse and secret exposure; they must not remove
  ordinary Chat features from the default public-node experience.

## Current foundation

Core already provides:

- public/open group history, counts, active chats, unsigned CHAT construction,
  signed transaction processing, `chatReference`, and public-node protection;
- QDM1 direct-message envelope/crypto, public encrypted CHAT history/build/
  process primitives, and local private-key convenience helpers;
- QPGC v1 membership epochs, messages, signed announcements, key requests,
  relays, rotation requests, local key cache, and local private-key convenience
  helpers;
- public QDN fetch/ranged streaming and public upload construction; and
- read-side group membership/details plus restricted join/leave builders.

Core does not need new runtime behavior for public group replies, edits,
deletes, reactions, avatars, public QDN embeds, or QDM1 transport. Those are
later Home/Chat bridge and presentation tasks.

## Completion tracker

| ID | Core milestone | Status | Blocks Home work |
| --- | --- | --- | --- |
| C0 | Freeze shared QDM1, QPGC, and CHAT vectors | Complete; QENC completed in C6 | Home clean-room crypto and byte attestation |
| C1 | Store/index parsed QPGC epoch and key metadata | Complete | Bounded control/history reads |
| C2 | Add default-enabled bounded public QPGC control and atomic state APIs | Complete | All-route private-group recovery |
| C3 | Retain accepted announcements while retained messages depend on them | Complete | Restart, new install, and node-switch recovery |
| C4 | Enforce/report QPGC v1 member/public-key/message limits | Complete | Honest private-group availability UI |
| C5 | Add default-enabled, abuse-protected public unsigned join/leave builders | Complete | All-route Home join/leave actions |
| C6 | Correct and freeze the QENC private-attachment contract | Complete | Private group/direct attachments |

## C0 — Shared protocol contracts and golden vectors

Progress (2026-08-17): the first versioned fixture now freezes QDM1 direct
encryption plus QPGC membership, message encryption, member key wrapping, key
announcements, current/specific key requests, and rotation requests. Relayed
control context and initial/revision CHAT transaction bytes are also frozen.
The fixture now also records exact shared-secret/AAD/KDF intermediates and
machine-readable positive/negative validation cases. C6 adds the separate QENC
v2 attachment fixture without changing the C0 chat-wire contract.

### Core changes

- Commit one versioned, language-neutral JSON fixture set under
  `src/test/resources/chat/interop/`.
- Make Core JUnit consume the committed bytes rather than comparing only Java
  round trips.
- Add deterministic lower-level crypto entry points where production currently
  generates randomness internally. Production wrappers continue using secure
  random nonces; tests inject fixed values.
- Record exact UTF-8 byte ceilings and serialization order.

Required fixture coverage:

- QDM1 fixed sender/recipient keys, shared secret, KDF/AAD, nonce,
  ciphertext/tag, full envelope, both directions, and wrong-key/tamper cases;
- QPGC fixed sorted membership, epoch ID, group key, key ID, member wraps,
  announcement signing bytes/signature, relayed outer sender, encrypted message,
  key request, and rotation request;
- Qortium CHAT unsigned/signed bytes with and without `chatReference`;
- negative parsing cases: wrong group/epoch/key, duplicate/missing wrapper,
  bad signature/tag/nonce, trailing bytes, and oversized data;
- positive canonicalization of reordered announcement wrappers; and
- QENC v2 recipient and corrected group-header fixtures completed in C6.

### Completion gate

- The fixtures are deterministic across repeated runs.
- Java production primitives reproduce the committed positive vectors exactly.
- Every negative vector fails at the intended validation layer.
- The fixture format is documented well enough for Home TypeScript tests to
  consume without reading Java implementation code.

### Corresponding Home work after Core

- Consume the same fixtures in Home tests.
- Implement QDM1 and QPGC crypto in trusted desktop and Android host code.
- Keep all private/group keys out of `qdnRequest`, QDN app storage, logs, and
  renderer error payloads.
- Decode and attest all unsigned CHAT bytes before signing.

## C1 — Indexed QPGC metadata and bounded repository queries

Status: complete. The local chat store now backfills and indexes QPGC type,
epoch, and optional key identifiers without a schema-version bump. QPGC service
paths use typed SQL queries capped at 100 rows, private-message counts remain
database-side, same-timestamp pages use a signature cursor, and an HSQLDB plan
test proves the composite lookup index is selected under a 256-row group spam
fixture.

### Core changes

- Add nullable parsed `private_group_epoch_id` and
  `private_group_key_id` columns to `ChatMessages`.
- Populate them when a valid QPGC envelope is accepted.
- Backfill still-retained QPGC rows during the repository upgrade.
- Add an index covering group, envelope type, epoch, key, timestamp, and
  signature.
- Replace `getGroupMessages()` plus Java filtering in QPGC paths with typed,
  cursor-bounded repository methods for:
  - control envelopes;
  - private messages;
  - a matching announcement for one group/epoch/key;
  - key requests; and
  - the latest applicable rotation request.
- Keep generic `/chat/messages`, counts, active chats, and websocket output
  filtering control envelopes exactly as they do now.

### Completion gate

- No public/private QPGC API fetches all retained group rows.
- Stable ordering is `(created_when, signature)`.
- Schema upgrade/backfill is idempotent and preserves current messages.
- Same-timestamp pagination has no duplicates or omissions.
- Query plans use the new index under a spammed-group fixture.

### Corresponding Home work after Core

None directly. These queries are Core internals used by C2 and existing local
helpers. Home must not infer availability until C2 is advertised by the selected
node.

## C2 — Public QPGC control and atomic state APIs

Status: complete. Core now exposes bounded, read-only control pages and one
blockchain-lock-consistent group state response through the default public chat
wildcard. The endpoints have no API-key or private-key dependency, return no
plaintext or group key, and are protected by a separate public work class.

### Public control API

```http
GET /chat/private/group/control
  ?txGroupId=12
  &types=KEY_ANNOUNCEMENT,KEY_REQUEST,ROTATION_REQUEST
  &afterCursor=<opaque-timestamp-signature-cursor>
  &limit=25
```

Contract:

- positive existing closed group;
- explicit unique control types only; never `MESSAGE`;
- mutually exclusive before/after cursors;
- default 25, hard maximum 100;
- stable `(created_when, signature)` cursor;
- optional exact Base58 epoch/key filters;
- conservative maximum 1 MiB encoded response budget;
- complete signed CHAT transaction bytes in Base58, plus safe classification
  metadata;
- no private key, raw group key, decrypted wrapper, or plaintext; and
- no side effects.

Home verifies the outer CHAT signature and the inner QPGC signature. A relayed
announcement's outer CHAT sender may differ from its original creator. Key and
rotation request identities must bind to the outer sender.

### Atomic state API

```http
GET /chat/private/group/state/{groupId}
```

Response fields:

- group ID, existence, and closed/open state;
- epoch ID or `null`;
- sorted member public keys and count;
- whether all public keys are known;
- addresses whose public keys are unknown or unusable;
- QPGC version and `maxV1Members: 39`;
- `available`; and
- structured unavailable reason.

The state response fails coherently for a missing/open group, no members,
unknown member public key, or more than 39 members. It must not publish an epoch
as usable when encryption cannot cover every member.

### Direct-message public-route parity

QDM1 does not need a new public decryption endpoint. Home can obtain the
recipient public key through the existing public address reads, query encrypted
direct CHAT rows through `GET /chat/messages?involving=...`, encrypt/decrypt
QDM1 locally, request exact unsigned bytes from `POST /chat/public/build`, and
submit the locally attested, PoW-computed, signed transaction through
`POST /transactions/process`.

C2 must nevertheless pin this as a supported default contract rather than an
accidental combination of routes:

- seed and ordinary-node profiles retain `GET /addresses/*`, `GET /chat/*`,
  `POST /chat/public/build`, and `POST /transactions/process` by default;
- public-build tests cover encrypted direct messages with and without
  `chatReference`, including exact recipient/sender/payload attestation;
- public history/active-chat tests cover both participants without exposing
  plaintext or private keys; and
- Home golden-vector tests prove that the same QDM1 conversation works through
  local, custom, and public routes on desktop and Android.

Core-side parity coverage is complete: the shipped profiles retain those four
public route families, unsigned direct CHAT construction preserves encrypted
payloads and `chatReference`, and generic public history/active-chat reads retain
the encrypted direct rows. Home golden-vector/platform coverage remains follow-on
work.

### Public abuse bounds

- Add a public work class for these chat reads instead of leaving them as
  unmetered allowlisted GET requests.
- Initial design ceiling: 120 requests/minute/IP, burst 30, and global
  concurrency 16, subject to focused load-test adjustment.
- Reject oversized response construction before allocating or encoding it.
- Add both endpoints to the shipped seed and ordinary-node public profiles by
  default. Extend settings migration and drift tests so existing default
  installations inherit them without a manual allowlist edit.
- Preserve an operator's explicit custom route removals. Home reports that
  opt-out accurately, but neither Core nor Chat treats it as the normal public
  node capability set.

### Completion gate

- Shipped seed and ordinary public-node profiles expose both bounded reads
  through `GET /chat/*` by default; settings migration preserves the profile and
  adds the new protection settings for upgrades.
- The endpoints expose signed protocol material and classification only,
  while Home still provides the complete private-group experience through local
  verification, key handling, decryption, encryption, PoW, and signing.
- Invalid type/cursor/group requests fail before repository scanning.
- Multi-type, forward/backward, same-timestamp, signed-byte, page-limit,
  state-failure, default-profile, and rate-limit tests pass.
- Generic public chat history, counts, active chats, and websockets continue
  hiding all control traffic.

### Corresponding Home work after Core

These are Home-internal node calls, not raw actions for Chat. Home will:

- probe selected-node support and advertise the complete high-level
  private-group feature family on local, custom, and public routes;
- fetch, parse, and independently verify signed controls;
- bind data to the selected account/network/route revision;
- keep reads side-effect free; and
- expose explicit high-level key request/resolve/relay/rotation actions without
  returning controls or keys to the QDN app.

An old node or an operator-disabled route produces a precise upgrade/policy
error. It does not define a supported half-capable public-node product mode.

## C3 — Dependency-aware announcement retention

Status: complete. Cleanup retains an accepted key announcement only while a
retained encrypted QPGC message references its exact group, membership epoch,
and key. Unmatched announcements and ordinary controls still expire normally,
and the announcement is deleted transactionally with its final dependent
message.

### Core changes

- Change cleanup so an accepted `KEY_ANNOUNCEMENT` older than the ordinary
  cutoff remains while any retained QPGC `MESSAGE` references the same
  `(groupId, epochId, keyId)`.
- Delete that announcement on the first cleanup after the final dependent
  message expires.
- Keep key requests and rotation requests on ordinary CHAT retention.
- Make cleanup indexed and transactional.
- Preserve the existing configured CHAT retention window; do not create an
  indefinite private history store.

### Completion gate

- Restart/new repository instances can recover every retained message whose
  announcement was accepted for that recipient.
- Cleanup retains exactly the dependencies and later removes them.
- Tampered/unaccepted announcements never gain extended retention.
- Cleanup remains bounded and rolls back atomically on failure.

### Corresponding Home work after Core

- Persist account-bound encrypted key material as an optimization and for local
  continuity.
- Treat Core-retained signed announcements as the portable recovery source.
- Report genuine retention gaps clearly; do not promise an offline mailbox.

## C4 — QPGC v1 availability limits

Status: complete. The envelope implementation derives the 39-recipient
announcement ceiling and 3,894-byte message plaintext ceiling from the exact
4,000-byte CHAT limit. Membership resolution counts before loading, returns
structured local availability reasons, and never silently drops a member whose
public key is missing. The atomic state response now reports both limits for
Home.

### Core changes

- Define one shared `MAX_V1_MEMBERS = 39` constant from the 4,000-byte CHAT
  envelope limit.
- Fail before key generation/announcement construction when a group is too
  large.
- Fail before encryption when any current member lacks a valid public key.
- Return structured unavailable reasons from the state API and local helpers.
- Enforce message plaintext limits in UTF-8 bytes after visible payload
  encoding; QPGC `MESSAGE` maximum plaintext is 3,894 bytes.

### Completion gate

- 39-member vector succeeds and 40-member vector fails before publish/PoW.
- Missing-key member lists fail closed without silently excluding a member.
- Boundary tests use multi-byte UTF-8 text.

### Corresponding Home work after Core

- Gate private-group composer/send controls on the state response.
- Display precise member-limit or missing-public-key status.
- Never fall back to plaintext closed-group chat.

## C5 — Public unsigned join/leave builders

Progress (2026-08-17): Core now exposes exact `POST /groups/public/join` and
`POST /groups/public/leave` routes. Both return signature-free transaction
bytes, retain the Qortium MemoryPoW-fee nonce field, use normal unconfirmed
validation, and enter the existing anonymous builder rate, concurrency, and
body-size controls. All three tracked Previewnet profiles enable only these two
group mutation routes. Untouched managed settings inherit them on upgrade;
customized public route lists remain operator-owned. Tests decode and attest
every target field, compute MemoryPoW, sign locally, process both transactions,
and pin `ALREADY_GROUP_MEMBER` and `NOT_GROUP_MEMBER` as identifiable states.

### Core changes

- Add abuse-protected public builder routes for JOIN_GROUP and LEAVE_GROUP
  rather than opening the existing private-key/local builders.
- Return unsigned bytes only.
- Reuse normal transaction validation and public builder work controls.
- Include the Qortium MemoryPoW-fee nonce layout.
- Keep invite, approval, ban, kick, and role builders out of this first write
  tranche.
- Ship the routes in seed and ordinary-node public profiles by default, with
  settings migration/drift coverage and operator custom-removal preservation.

### Completion gate

- Public and unauthenticated custom profiles can build, but never sign, a
  selected account's exact join/leave intent.
- Default and upgraded public-node settings expose both builders without a
  manual operator edit.
- Field-by-field decode/attestation fixtures cover group, account public key,
  timestamp, fee/nonce, and transaction type.
- Signed output processes normally through `/transactions/process`.
- Already-member/already-left failures are stable and identifiable.

### Corresponding Home work after Core

- Add `JOIN_GROUP` and `LEAVE_GROUP` bridge actions on desktop and Android.
- Decode and attest every unsigned field before signing.
- Recheck app/tab/account/network/route after approval and immediately before
  signing/broadcast.
- Normalize already-member/already-left as idempotent UI states.

## C6 — QENC private attachment contract

Status (2026-08-18): complete. QENC v2 is the required format for new private
chat attachments. QENC v1 and legacy-prefix recognition remain unchanged for
existing generic private resources.

### Core/spec changes

- Replace the stale four-byte group-key reference in new v2 resources with the QPGC context:
  `groupId:uint32 | epochId:32 | keyId:32 | contentNonce:12`.
- Derive a domain-separated attachment key instead of reusing the QPGC message
  key directly.
- Freeze exact KDF salt/info, AES-GCM AAD, nonce generation, ciphertext layout,
  and full serialized bytes.
- Count the complete encrypted envelope against the 1 MiB private attachment
  service limit.
- Direct attachments wrap to both recipient and sender so sent files remain
  reopenable.

### Implementation record

- `EncryptedDataEnvelope` recognizes v1 exactly as before and strictly parses
  v2 mode headers, flags, recipient counts/order, full group context, and a
  complete authentication tag.
- `PrivateChatAttachmentCrypto` is the Core reference for Ed25519-to-X25519
  recipient wrapping, domain-separated HKDF-SHA256, AES-256-GCM, group-key
  derivation, direct sender reopen, and the exact full-envelope ceiling.
- QATT v1 encrypts filename, media type, byte count, SHA-256, and file data in
  one authenticated plaintext container. Consumers still sniff and sanitize
  the decrypted file before display.
- `qenc-attachment-v2.json` was generated independently of the Java codec and
  is reproduced by Core tests byte for byte. It includes both participant
  decryptions, group context, KDF/AAD intermediates, full envelopes, boundary
  size, and negative mutations.
- `QCHAT_ATTACHMENT_PRIVATE` continues counting the entire stored resource
  against its existing 1 MiB limit; the reference codec applies the same bound
  before publication.

### Completion gate

- Both direct participants decrypt the deterministic recipient envelope and a
  third account cannot select a wrap.
- A current QPGC member decrypts only with the exact group, epoch, key id, and
  matching group key.
- Header/context/ciphertext/payload mutations fail at the structural, context,
  AEAD, or digest layer as appropriate.
- One byte over the complete 1 MiB envelope limit is rejected.

### Corresponding Home work after Core

- Add native source-token selection and encrypted publish/decrypt/view/stream
  flows.
- Encrypt filename, MIME type, and other sensitive metadata.
- Return an immutable signature/hash-bound descriptor to Chat, never keys.
- Use an authorized ranged proxy on Android rather than whole-file buffering.

## Planned Core PR sequence

1. **Portable QPGC foundations (complete):** C0-C1 documentation, deterministic
   fixtures, indexed metadata, and bounded repository queries.
2. **Portable QPGC reads (complete):** C2 bounded public controls/state,
   default-route protections, and direct-message public-route parity tests.
3. **Recovery durability and limits (complete):** C3-C4 dependency-aware retention,
   v1 member/public-key/plaintext limits, and full regression coverage.
4. **Portable participation (complete):** C5 public unsigned join/leave builders.
5. **Private attachments (complete):** C6 QENC v2 framing, crypto reference,
   encrypted payload, deterministic vectors, compatibility, and limits.

Each PR must update `QORTIUM-CHANGELOG.md`, use a matching changelog/commit
title, run focused tests with `-DskipJUnitTests=false`, and finish with the clean
full deterministic suite. Maven runs are serialized.

## Home follow-on roadmap

Home begins after the relevant Core contract is merged. With C0-C5 complete,
the trusted Home bridge is now the next active portability tranche:

1. consume C0 vectors and implement trusted QDM1/QPGC crypto on desktop and
   Android, including full direct-message and private-group use through public
   and custom nodes;
2. add route-aware action discovery and structured errors;
3. use C2/C3 for portable private-group read/recovery/send flows;
4. use C5 for route-independent join/leave;
5. preserve and attest `chatReference` for public/private edit, delete, and
   reaction actions;
6. provide Qortium and Qortal resource viewer/stream/save/publish parity; and
7. consume the now-frozen C6 QENC v2 fixture for private attachments without
   exposing attachment, group, or account keys to Chat.

Chat follows Home and consumes high-level actions only. It never calls the raw
QPGC control endpoint, performs wallet crypto, or receives reusable secret
material.

Home and Chat must not infer that a public route is intentionally read-only.
They expose the same group, private-group, and direct-message features as the
local route whenever the selected node runs the shipped capability-complete
profile. A route disabled by an operator is reported as an explicit local
policy exception.

## Final completion gate

- Qortium public groups, private groups, and direct messages read and send
  through local, authenticated custom, unauthenticated custom, and public nodes
  on desktop and Android.
- Replies, edits, deletes, reactions, membership-aware discovery, and the
  applicable public-resource embeds work consistently in those conversations
  on every route and platform.
- Shipped public-node defaults expose every required client-safe endpoint;
  operators may explicitly opt out without changing the platform default.
- Account, node, route, lock, membership, missing-key, member-limit, malformed,
  retention-gap, and ambiguous-broadcast states fail safely and visibly.
- Open/public chat behavior and generic control-envelope filtering remain
  unchanged.
- No private key, API key, group key, plaintext private message, or unrestricted
  node URL crosses into Chat.
- Qortal Core remains untouched.
