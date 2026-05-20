# Private Group Chat Encryption Design

This document records the chosen first design for making closed-group chat
private by default in Qortium Core.

The 6.1.5 chat-store work is complete enough to support this phase: chat
messages are stored outside the normal unconfirmed transaction pool, message
payload bytes are treated as opaque data, and the store preserves the fields
needed for future encryption policy such as `txGroupId`, `recipient`,
`chatReference`, `isText`, and `isEncrypted`.

## Goal

Closed groups should behave like private group chat without asking normal users
to understand key publishing, group-key rotation, or Hub-specific QDN resources.

When a local user sends a message to a closed group, Core should:

- discover the current group membership
- use Core-managed encryption automatically
- encrypt only for the members who are current at that time
- keep the dedicated chat store opaque
- leave open-group, direct, and general chat behavior unchanged

The first implementation should not copy Hub's QDN `DOCUMENT_PRIVATE` key-bundle
flow. QDN can remain useful for other data, but private group chat keys should
not depend on QDN publication.

## V1 Key Model

Qortium will use cached per-sender keys for each group membership epoch.

A membership epoch is the current closed-group membership snapshot. Core should
derive a deterministic epoch id from the group id and sorted current member
public keys, so every node can identify the same membership state without
storing extra consensus data.

For each closed group and membership epoch:

- each sender owns a random sender key for messages they send
- that sender key is cached locally
- messages from that sender are encrypted with the cached sender key
- the sender key is announced to current group members using pairwise encrypted
  key wrappers
- later messages from the same sender can reuse the cached sender key until the
  epoch changes or the sender rotates it

The sender key must be random, not derived from public group state. The
deterministic part is the membership epoch id, not the encryption key itself.

## Envelope Types

Encrypted group chat data should use a Qortium-owned versioned envelope inside
`ChatTransactionData.data`. The CHAT transaction format does not need to change
for the first implementation.

V1 envelope types:

- `MESSAGE`: encrypted user message content
- `KEY_ANNOUNCEMENT`: sender key wrapped for one or more current members
- `KEY_REQUEST`: request for a missing sender key for a group epoch
- `ROTATION_REQUEST`: signed request asking senders to rotate their keys

Normal encrypted closed-group messages should set `isEncrypted` to `true`.
Because transaction data contains ciphertext, clients should not rely on
`isText` to mean the stored payload is directly readable text.

Envelope metadata should identify the version, envelope type, group id,
membership epoch id, sender public key, sender key id, and the data needed for
the specific envelope type.

## Key Announcements And Requests

When sending to a closed group, Core should check whether the local sender has a
current sender key for the current membership epoch.

If no key exists, Core creates one and announces it to the current members. If a
key exists but has not been announced recently, Core may re-announce it before
or with a message.

If a member receives a message but does not have the matching sender key, their
node can send a signed `KEY_REQUEST` control envelope for that group, membership
epoch, sender, and key id.

In V1, key re-announcements are sender-owned: the original sender responds by
announcing that sender key to the requester. Forwarding another sender's key is
deferred to a later design because it increases trust and leakage concerns.

Control envelopes are allowed for closed groups even after plaintext
closed-group chat is rejected, because they are chat-layer metadata rather than
user message content.

## Rotation And History

Membership changes force a new membership epoch. Joins, leaves, kicks, and bans
therefore move future messages to new sender keys.

Manual rotation is also supported:

- a local user can rotate their own sender key at any time
- the group owner or an admin can publish a signed `ROTATION_REQUEST`
- rotation requests are rate-limited to prevent abuse
- seeing an accepted rotation request causes each sender to rotate their own key
  the next time they send

V1 keeps any-member rotation requests as a future policy option. Owner/admin
requests are the initial default because they are easier to reason about and
less likely to create key churn.

History follows forward secrecy limits:

- new members receive access to future messages only
- removed members lose access to future messages only
- old messages cannot be clawed back from anyone who already had the old key
- if a member account or device is compromised, the real fix is to remove that
  member, which forces a new membership epoch

## Planned Local APIs

Clients should not need to implement group encryption themselves. Core should
provide restricted local APIs for:

- sending an encrypted closed-group message
- decrypting closed-group messages for a local account
- requesting a missing sender key
- re-announcing the local sender key
- rotating the local sender key
- requesting group rotation as owner or admin

Core will need local access to the sender account's private key for encryption,
decryption, signing, and key wrapping. The first API can follow existing
private-key-based local endpoints. A later wallet or unlocked-account design can
hide private keys from callers more cleanly.

## Validation Policy

Core validation should remain lightweight and compatible with normal peer
relay:

- open-group, direct, and general chat keep their current behavior
- closed-group user messages must use the Qortium private group envelope once
  the feature is active
- closed-group control envelopes must be structurally valid and signed by a
  current member
- peer validation should not need plaintext or private group keys
- the chat store should not decrypt or index closed-group message content

If any current group member lacks a known public key, local send should fail
clearly instead of silently excluding that member.

## Implementation Sequence

1. Add envelope serialization and parsing tests.
2. Add chat-specific AES-GCM helpers with associated data.
3. Add membership epoch id computation.
4. Add a local sender-key cache for group id, epoch id, sender public key, key
   id, key bytes, and announcement timestamps.
5. Add pairwise key wrapping using the sender and recipient account public
   keys.
6. Add local APIs for send, decrypt, key request, key announcement, local
   rotation, and owner/admin rotation request.
7. Update `ChatService` validation for closed-group envelope requirements.
8. Add integration tests for send, read, missing-key recovery, membership
   changes, manual rotation, plaintext rejection, and open-group compatibility.

## Non-Goals For V1

- Do not publish group chat keys through QDN.
- Do not add one shared group-wide key controlled by owners or admins.
- Do not allow automatic old-history access for new members.
- Do not let the chat store decrypt or index closed-group plaintext.
- Do not require backwards compatibility with old closed-group chat history,
  because Qortium is intended as a baseline for new chains.
- Do not add any-member rotation requests until the owner/admin request policy
  has been implemented and tested.
