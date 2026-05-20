# Private Group Chat Encryption Plan

This document records the starting plan for making closed-group chat private by
default in Qortium Core.

The 6.1.5 chat-store work is now complete enough to support this design phase:
chat messages are stored outside the normal unconfirmed transaction pool,
message payload bytes are treated as opaque data, and the store preserves the
fields needed for future encryption policy such as `txGroupId`, `recipient`,
`chatReference`, `isText`, and `isEncrypted`.

## Current Problem

Closed groups currently control membership and approval, but their group chat is
not automatically private at Core level. A client can choose to encrypt a
closed-group chat message, but Core does not currently require that behavior for
closed groups or manage the group keys needed to make it consistent across
clients.

For Qortium, closed-group chat should eventually mean private group chat by
default. The implementation should be owned by Core so every compatible client
gets the same rule set and does not need to invent its own group-encryption
system.

## Hub Reference Model

The current Qortal Hub implementation is useful reference material, but it is
not the final Qortium Core design.

Hub currently:

- Publishes group key material through QDN `DOCUMENT_PRIVATE` resources.
- Uses identifiers such as `symmetric-qchat-group-{groupId}`.
- Lets group admins publish encrypted symmetric-key bundles for group members.
- Stores a versioned key object and encrypts each chat payload with the highest
  available group key.
- Supports encrypted chat messages, edits, reactions, and notifications by
  encrypting the client message object before CHAT submission.
- Allows a member with the current key bundle to decrypt older messages covered
  by that bundle.

This model proves the feature is practical, but it has tradeoffs that Qortium
should decide deliberately before moving the responsibility into Core.

## Qortium Direction

Qortium should design a Core-owned group chat encryption layer instead of
copying the Hub QDN key-publish flow directly.

The expected direction is:

- Closed-group CHAT submissions should require Core-managed encryption once the
  feature is enabled.
- Open-group CHAT behavior should remain unchanged unless a future feature
  explicitly adds optional open-group encryption.
- Chat payloads should remain opaque to the dedicated chat store; Core should
  validate encryption metadata and route messages without indexing plaintext.
- The encryption envelope should be Qortium-owned, versioned, and explicit
  enough for clients to detect, display, and migrate formats.
- Key lifecycle should follow group lifecycle events such as joins, leaves,
  kicks, bans, admin changes, and owner changes.

## Decisions To Make Before Code

The implementation should not start until these decisions are made and recorded:

- Payload envelope: the exact binary or structured format placed in CHAT
  `data`, including version, group id, key id, nonce, ciphertext, and any
  sender or algorithm metadata.
- Encryption algorithms: the symmetric encryption mode, nonce size, key size,
  authentication behavior, and whether existing Core crypto helpers are enough.
- Key distribution: how group members receive group chat keys without depending
  on Hub-specific QDN publishes as the authoritative mechanism.
- Key ownership: whether keys can be created or rotated by the owner only,
  admins, or another explicit authority.
- Rotation triggers: which group events force a new key and whether rotation is
  immediate, queued, or lazy on the next chat submission.
- New-member history: whether new members can decrypt old messages, only new
  messages after joining, or a bounded history chosen by admins.
- Removed-member access: whether leaving, kicked, or banned members lose access
  only to future keys or whether additional measures are needed.
- Plaintext policy: whether Core rejects plaintext closed-group CHAT
  submissions immediately after activation or allows a compatibility period.
- API shape: whether clients submit plaintext to a local Core API that encrypts
  it, submit an encrypted envelope produced by a helper API, or both.
- Recovery behavior: what happens when key material is missing, corrupted, or
  not yet available to a member.

## Recommended First Implementation Shape

The first Qortium implementation should be conservative:

- Add a versioned encrypted group chat envelope for closed-group messages.
- Add Core APIs that let local clients ask Core to prepare, encrypt, and submit
  closed-group chat without exposing group keys to app code unnecessarily.
- Reject plaintext closed-group CHAT submissions once the feature is active for
  a chain.
- Rotate keys forward when membership changes remove access, with old messages
  remaining decryptable only to members who already had the old key.
- Keep historical recovery simple at first: new members should receive future
  keys, not automatic access to all old closed-group chat history, unless a
  later design explicitly adds admin-approved history sharing.
- Keep the dedicated chat store as the authoritative transient message store and
  add only encryption-specific metadata or helper tables where needed for key
  lifecycle.

## Planning Sequence

1. Review the existing Core CHAT transaction format, group membership events,
   and wallet/key access points.
2. Define the encrypted payload envelope and key record model.
3. Define local API behavior for creating, encrypting, decrypting, and
   submitting closed-group chat.
4. Define validation behavior for closed-group plaintext rejection and encrypted
   envelope checks.
5. Define key rotation behavior for group membership and admin events.
6. Add tests for envelope parsing, key lifecycle, closed-group validation, API
   submission, REST reads, websocket delivery, peer ingress, and retention.

## Non-Goals For The First Pass

- Do not copy Hub's QDN `DOCUMENT_PRIVATE` key-publish flow into Core as-is.
- Do not add plaintext message indexing for closed-group chat.
- Do not make the dedicated chat store decrypt messages for normal reads.
- Do not require backwards compatibility with old closed-group chat history,
  because Qortium is intended as a baseline for new chains.
- Do not design admin-only encrypted group content unless it is needed for the
  chat-key lifecycle itself.
