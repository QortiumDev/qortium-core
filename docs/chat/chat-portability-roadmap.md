# Portable Chat Core Roadmap

Status: active planning and implementation tracker  
Baseline: Qortium Core `2f9af28879319d6be28d7918c4039657960d7d1b`
(`v1.7.1`)  
Order: Qortium Core first, Qortium Home second, Chat app last

## Purpose

This roadmap tracks the Qortium Core work needed before Home can provide the
same Qortium chat features through local, authenticated custom, unauthenticated
custom, and public nodes.

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

## Current foundation

Core already provides:

- public/open group history, counts, active chats, unsigned CHAT construction,
  signed transaction processing, `chatReference`, and public-node protection;
- QDM1 direct-message envelope/crypto and restricted local helper APIs;
- QPGC v1 membership epochs, messages, signed announcements, key requests,
  relays, rotation requests, local key cache, and restricted local helper APIs;
- public QDN fetch/ranged streaming and public upload construction; and
- read-side group membership/details plus restricted join/leave builders.

Core does not need new runtime behavior for public group replies, edits,
deletes, reactions, avatars, public QDN embeds, or QDM1 transport. Those are
later Home/Chat bridge and presentation tasks.

## Completion tracker

| ID | Core milestone | Status | Blocks Home work |
| --- | --- | --- | --- |
| C0 | Freeze shared QDM1, QPGC, CHAT, and later QENC vectors | In progress | Home clean-room crypto and byte attestation |
| C1 | Store/index parsed QPGC epoch and key metadata | Planned | Bounded control/history reads |
| C2 | Add bounded public QPGC control and atomic state APIs | Planned | Public/custom private-group recovery |
| C3 | Retain accepted announcements while retained messages depend on them | Planned | Restart, new install, and node-switch recovery |
| C4 | Enforce/report QPGC v1 member/public-key limits | Planned | Honest private-group availability UI |
| C5 | Add protected public unsigned join/leave builders | Planned | Portable Home join/leave actions |
| C6 | Correct and freeze the QENC group-attachment contract | Deferred until attachment tranche | Private group/direct attachments |

## C0 — Shared protocol contracts and golden vectors

Progress (2026-08-17): the first versioned fixture now freezes QDM1 direct
encryption plus QPGC membership, message encryption, member key wrapping, key
announcements, current/specific key requests, and rotation requests. Relayed
control context, CHAT transaction bytes, and QENC remain open within C0.

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
- negative parsing cases: wrong group/epoch/key, reordered/duplicate/missing
  wrapper, bad signature/tag/nonce, trailing bytes, and oversized data; and
- QENC recipient and corrected group-header fixtures before C6 begins.

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

### Public control API

```http
GET /chat/private/group/control
  ?txGroupId=12
  &types=KEY_ANNOUNCEMENT,KEY_REQUEST,ROTATION_REQUEST
  &afterCursor=<opaque-created_when-signature-cursor>
  &limit=25
```

Contract:

- positive existing closed group;
- explicit unique control types only; never `MESSAGE`;
- mutually exclusive before/after cursors;
- default 25, hard maximum 100;
- stable `(created_when, signature)` cursor;
- maximum 1 MiB encoded response;
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

- group ID and closed/open state;
- epoch ID or `null`;
- sorted member public keys and count;
- whether all public keys are known;
- QPGC version and `maxV1Members: 39`;
- `available`; and
- structured unavailable reason.

The state response fails coherently for a missing/open group, no members,
unknown member public key, or more than 39 members. It must not publish an epoch
as usable when encryption cannot cover every member.

### Public abuse bounds

- Add a public work class for these chat reads instead of leaving them as
  unmetered allowlisted GET requests.
- Initial design ceiling: 120 requests/minute/IP, burst 30, and global
  concurrency 16, subject to focused load-test adjustment.
- Reject oversized response construction before allocating or encoding it.

### Completion gate

- Restricted public profiles expose only the bounded read endpoints.
- Invalid type/cursor/group requests fail before repository scanning.
- Relay, historical epoch, same-timestamp cursor, response-size, and rate-limit
  tests pass.
- Generic public chat history continues hiding all control traffic.

### Corresponding Home work after Core

These are Home-internal node calls, not raw actions for Chat. Home will:

- probe selected-node support and advertise only high-level private-group chat
  actions;
- fetch, parse, and independently verify signed controls;
- bind data to the selected account/network/route revision;
- keep reads side-effect free; and
- expose explicit high-level key request/resolve/relay/rotation actions without
  returning controls or keys to the QDN app.

## C3 — Dependency-aware announcement retention

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

### Core changes

- Add protected public builder routes for JOIN_GROUP and LEAVE_GROUP rather than
  opening the existing unrestricted/local builders.
- Return unsigned bytes only.
- Reuse normal transaction validation and public builder work controls.
- Include the Qortium MemoryPoW-fee nonce layout.
- Keep invite, approval, ban, kick, and role builders out of this first write
  tranche.

### Completion gate

- Public and unauthenticated custom profiles can build, but never sign, a
  selected account's exact join/leave intent.
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

This does not block initial Home chat work, but it must finish before private
attachments.

### Core/spec changes

- Replace the stale four-byte group-key reference with the QPGC context:
  `groupId:uint32 | epochId:32 | keyId:32 | contentNonce:12`.
- Derive a domain-separated attachment key instead of reusing the QPGC message
  key directly.
- Freeze exact KDF salt/info, AES-GCM AAD, nonce generation, ciphertext layout,
  and full serialized bytes.
- Count the complete encrypted envelope against the 1 MiB private attachment
  service limit.
- Direct attachments wrap to both recipient and sender so sent files remain
  reopenable.

### Corresponding Home work after Core

- Add native source-token selection and encrypted publish/decrypt/view/stream
  flows.
- Encrypt filename, MIME type, and other sensitive metadata.
- Return an immutable signature/hash-bound descriptor to Chat, never keys.
- Use an authorized ranged proxy on Android rather than whole-file buffering.

## Planned Core PR sequence

1. **Protocol fixtures:** C0 documentation, deterministic primitives, and
   shared QDM1/QPGC/CHAT fixtures.
2. **Portable QPGC reads:** C1-C4 repository migration, bounded APIs, retention,
   limits, and full regression coverage.
3. **Portable participation:** C5 public unsigned join/leave builders.
4. **Private attachments:** C6 only when the Home attachment tranche is ready.

Each PR must update `QORTIUM-CHANGELOG.md`, use a matching changelog/commit
title, run focused tests with `-DskipJUnitTests=false`, and finish with the clean
full deterministic suite. Maven runs are serialized.

## Home follow-on roadmap

Home begins only after the relevant Core contract is merged:

1. consume C0 vectors and implement trusted QDM1/QPGC crypto on desktop and
   Android;
2. add route-aware action discovery and structured errors;
3. use C2/C3 for portable private-group read/recovery/send flows;
4. use C5 for route-independent join/leave;
5. preserve and attest `chatReference` for public/private edit, delete, and
   reaction actions;
6. provide Qortium and Qortal resource viewer/stream/save/publish parity; and
7. implement C6 private attachments only after its wire contract is frozen.

Chat follows Home and consumes high-level actions only. It never calls the raw
QPGC control endpoint, performs wallet crypto, or receives reusable secret
material.

## Final completion gate

- Qortium private groups recover and send through local, authenticated custom,
  unauthenticated custom, and public nodes on desktop and Android.
- Account, node, route, lock, membership, missing-key, member-limit, malformed,
  retention-gap, and ambiguous-broadcast states fail safely and visibly.
- Open/public chat behavior and generic control-envelope filtering remain
  unchanged.
- No private key, API key, group key, plaintext private message, or unrestricted
  node URL crosses into Chat.
- Qortal Core remains untouched.
