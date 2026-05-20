# Private Group Chat Encryption Workflow

This document records the implemented V1 workflow for making closed-group chat
private by default in Qortium Core.

The 6.1.5 chat-store work supports this flow: chat messages are stored outside
the normal unconfirmed transaction pool, message payload bytes are treated as
opaque data, and the store preserves the fields needed for private chat policy
such as `txGroupId`, `recipient`, `chatReference`, `isText`, and `isEncrypted`.
Core now exposes local private group chat APIs for sending, listing, counting,
missing-key recovery, announcement relay, and key rotation.

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

Qortium will use shared random group keys for each membership epoch.

A membership epoch is the current closed-group membership snapshot. Core should
derive a deterministic epoch id from the group id and sorted current member
public keys, so every node can identify the same membership state without
storing extra consensus data.

If a member joins and later leaves, returning the group to the same member set,
the epoch id returns to the same value. This is an accepted V1 tradeoff: the
same current members are allowed to read current messages, and manual rotation
can create a fresh key when a group wants a clean break.

For each closed group and membership epoch:

- a group key is a random AES-256 key, not a deterministic value
- any current member can create and announce a group key for the current epoch
- more than one group key can be valid for the same epoch
- each encrypted message identifies the exact key id needed to decrypt it
- any member that has the referenced key can decrypt the message

The chain backs the current membership state. It does not need to choose one
consensus "latest" chat key. Messages name the key they use, and recipients
accept any valid signed key announcement that matches the current epoch and the
message's key id.

## Envelope Types

Encrypted group chat data should use a Qortium-owned versioned envelope inside
`ChatTransactionData.data`. The CHAT transaction format does not need to change
for the first implementation.

V1 envelope types:

- `MESSAGE`: encrypted user message content referencing a group id, epoch id,
  and key id
- `KEY_ANNOUNCEMENT`: signed group-key announcement with encrypted wrappers for
  current members
- `KEY_REQUEST`: signed request for a missing key id, or for any usable key for
  the current epoch
- `ROTATION_REQUEST`: signed request asking members to stop using older keys and
  create or use a fresh key

Normal encrypted closed-group messages should set `isEncrypted` to `true`.
Because transaction data contains ciphertext, clients should not rely on
`isText` to mean the stored payload is directly readable text.

Envelope metadata should identify the version, envelope type, group id,
membership epoch id, key id, creator or sender public key, and the data needed
for the specific envelope type.

## Key Announcements And Requests

When sending to a closed group, Core should compute the current membership epoch
and check whether the local node has a usable group key for that epoch.

If no usable key exists, Core creates a random group key and announces it to the
current members. The announcement must be signed by the announcing member and
must include encrypted wrappers for the current member public keys.

A key id is a commitment to the random group key and its group/epoch context.
Recipients verify a key announcement by checking that:

- the announcement signature is valid
- the signer is a current member for the announced epoch
- the group id and epoch id match the current group membership
- the announcement is addressed to current member public keys
- their wrapper decrypts to a key whose commitment matches the key id

Peers without the recipient private key can validate the announcement structure,
signature, and membership. Only actual recipients can prove their wrapper
decrypts to the announced key.

If a member receives a message but does not have the matching group key, their
node can send a signed `KEY_REQUEST` control envelope for that group, the
current membership epoch, and optionally a specific key id. If the request does
not name a key id, it asks for any usable key for the current epoch. Qortium
validates the requester signature and requires the requester to be the CHAT
transaction sender, so request publication is tied to the local account making
the request.

Any current member node can relay a signed key announcement it has already seen
for the current epoch. Nodes should not return raw group keys. The recipient
trusts a relayed announcement only after verifying the original signature and
decrypting its own wrapper.

Control envelopes are allowed for closed groups even after plaintext
closed-group chat is rejected, because they are chat-layer metadata rather than
user message content.

## Rotation And History

Membership changes naturally change the epoch id unless the group returns to an
identical member set. While a member is absent, messages are encrypted under the
different current epoch. If the old member set returns later, previously cached
keys for that identical member set can be reused unless the group rotates.

Manual rotation is also supported:

- a current member can rotate the group key their local node uses for new sends
- the group owner or an admin can publish a signed `ROTATION_REQUEST`
- rotation requests use the normal chat sender rate limit
- before sending, local nodes check the current epoch for the newest accepted
  rotation request and create a fresh key if their cached key is older

V1 keeps any-member rotation requests as a future policy option. Owner/admin
requests are the initial default because they are easier to reason about and
less likely to create key churn.

History follows the limits of shared keys:

- new members receive access to future messages only
- removed members lose access to messages sent while they are not members
- if an old identical member set returns, old keys for that set can still work
  unless the group rotates
- old messages cannot be clawed back from anyone who already had the old key
- if a member account or device is compromised, the real fix is to remove that
  member or rotate keys after the account is safe

This shared-key model has a larger blast radius than per-sender keys: one leaked
group key exposes all messages that used that key. Qortium accepts that V1
tradeoff because it makes key recovery and user experience simpler.

## Local API Workflow

Clients should not implement group encryption themselves. Core provides
restricted local APIs under `/chat/private/group`:

- `POST /send` sends an encrypted closed-group message and publishes a key
  announcement first when the local node needs a key for the current epoch.
- `POST /active` lists current closed groups for a local account and returns
  each group's latest private user message when readable.
- `POST /messages/count` counts retained private user messages matching the
  supplied inbox filters, including messages whose key is currently missing.
- `POST /messages` lists retained private user messages and decrypts each one
  when the local node has or can recover the matching key.
- `POST /decrypt` decrypts one stored private message by CHAT transaction
  signature.
- `POST /key-request` publishes a signed request for a missing group key.
- `POST /key-requests/resolve` scans stored current-epoch key requests and
  relays matching signed key announcements known to the local member node.
- `POST /key-announcement/relay` republishes a previously stored or cached
  signed key announcement without exposing the raw group key.
- `POST /rotate` creates a fresh local group key announcement for future sends
  in the current epoch.
- `POST /rotation-request` lets a group owner or admin ask current members to
  stop using older keys for future sends in the current epoch.

Core needs local access to the account private key for encryption, decryption,
signing, and key wrapping. These APIs therefore follow the existing
private-key-based local endpoint pattern. A later wallet or unlocked-account
design can hide private keys from callers more cleanly.

The recommended client read flow is:

1. Use `/active` to discover closed groups and their latest private-message
   status.
2. Use `/messages/count` for pagination and unread-style counts.
3. Use `/messages` for the inbox view.
4. If `/active` or `/messages` returns `MISSING_KEY`, publish `/key-request`
   with the returned epoch id and key id.
5. Ask another current member node to run `/key-requests/resolve` for that
   group, or have a known member relay a specific announcement with
   `/key-announcement/relay`.
6. Retry `/messages` after the relayed announcement is stored locally.

`/active` returns `DECRYPTED`, `MISSING_KEY`, or `NO_MESSAGES`. `/messages`
returns `DECRYPTED` or `MISSING_KEY` for each user message. `MISSING_KEY`
responses include the epoch id and key id needed for recovery.

The read APIs are side-effect free: they do not publish key requests or relays
automatically. `/key-request`, `/key-requests/resolve`,
`/key-announcement/relay`, `/rotate`, and `/rotation-request` are the explicit
side-effecting recovery and rotation steps.

The normal `/chat/messages`, `/chat/messages/count`, and
`/chat/active/{address}` endpoints remain compatibility/raw views. They filter
or expose private chat control traffic according to their legacy purpose, but
closed-group clients should use the private group APIs above.

## Validation Policy

Core validation should remain lightweight and compatible with normal peer
relay:

- open-group, direct, and general chat keep their current behavior
- closed-group user messages must use the Qortium private group envelope once
  the feature is active
- closed-group control envelopes must be structurally valid and signed by a
  current member when the envelope type requires a signer
- peer validation should not need plaintext or private group keys
- the chat store should not decrypt or index closed-group message content

If any current group member lacks a known public key, local send should fail
clearly instead of silently excluding that member.

## Implementation Status

V1 is implemented in Core with envelope serialization, AES-GCM message
encryption, membership epoch ids, signed key announcements, pairwise key
wrapping, process-local key caching, private group local APIs, closed-group
plaintext rejection, key request recovery, key announcement relay, local key
rotation, owner/admin rotation requests, and focused API/service/repository
tests.

The current test coverage includes send/read behavior, missing-key reporting,
stored-announcement rehydration, explicit key request recovery, multiple valid
keys in one epoch, membership epoch changes, manual rotation, owner/admin
rotation requests, plaintext rejection for closed groups, and open-group
compatibility.

## Non-Goals For V1

- Do not publish group chat keys through QDN.
- Do not require one consensus-backed latest chat key for a group epoch.
- Do not derive secret keys from public group state.
- Do not allow automatic old-history access for new members.
- Do not let the chat store decrypt or index closed-group plaintext.
- Do not require backwards compatibility with old closed-group chat history,
  because Qortium is intended as a baseline for new chains.
- Do not add any-member rotation requests until the owner/admin request policy
  has been implemented and tested.
