# QDN Encrypted Data Envelope (v1)

This document specifies the **encrypted data envelope** that clients (Home / Hub /
Q-Apps) prepend to the data of a **private** QDN resource. It replaces the older
bare text prefix (`qdnEncryptedData…`) with a compact, structurally-verifiable
binary header.

## Why this exists

Core never holds the decryption key — **encryption and decryption happen entirely
client-side**, so private content stays private even from the node operator. The
trade-off is that Core **cannot cryptographically prove** a payload is real
ciphertext (it has no key, and good ciphertext is indistinguishable from random).

What Core *can* do is validate the **shape** of a well-defined envelope. That
robustly catches the failure that actually matters: an app accidentally publishing
**plaintext** as "private". Plaintext will not carry a valid envelope, so publishing
is rejected (`DATA_NOT_ENCRYPTED`).

This check runs only at **publish** and **read** time. It is **not** part of
consensus (arbitrary data is an opaque hash to the chain), so the format can evolve
without any coordinated network upgrade. Existing resources that use the legacy
prefix continue to work (see [Migration](#migration)).

## Audience model

A private resource is encrypted for one of three audiences. These are the choices a
client offers the user:

| Mode | Who can decrypt | Extra input the client needs |
|------|-----------------|------------------------------|
| **PUBLISHER** | only the publishing account | none (uses the publisher's own key) |
| **ACCOUNTS**  | a chosen set of accounts | `recipientPublicKeys` — the list |
| **GROUP**     | members of a Qortium group | `groupId` (resolves to a group key epoch) |

**PUBLISHER and ACCOUNTS use the same cryptography** — a single content key wrapped
to each recipient's public key. PUBLISHER is just "the only recipient is the
publisher". So at the wire level there are **two envelope modes**:

- `RECIPIENTS` (`0x01`) — covers PUBLISHER (1 recipient = self) and ACCOUNTS (N recipients)
- `GROUP` (`0x02`)

## Envelope layout (v1)

The envelope is the **raw bytes** of the resource's data file: a fixed header, a
mode-specific header, then the ciphertext. All integers are **big-endian**.

```
Offset  Size           Field        Notes
------  -------------  -----------  -------------------------------------------
0       4              magic        ASCII "QENC" = 0x51 0x45 0x4E 0x43
4       1              version      0x01
5       1              mode         0x01 = RECIPIENTS, 0x02 = GROUP
6       1              cipher       0x01 = AES-256-GCM
7       1              flags        reserved, set to 0x00
8       2 (uint16)     headerLen    byte length of the mode-specific header below
10      headerLen      header       key material + nonce (see modes below)
10+hl   (rest)         ciphertext   AES-256-GCM(contentKey, nonce, plaintext) + 16-byte tag
```

Fixed header = **10 bytes**. The mode-specific header and the ciphertext follow.

The **content** is always `AES-256-GCM(contentKey, contentNonce, plaintext)`; the
modes differ only in how `contentKey` is delivered to readers.

### Mode 0x01 — RECIPIENTS (PUBLISHER / ACCOUNTS)

One random `contentKey` is wrapped to each recipient's public key via X25519 ECDH. A
reader finds its own wrap by key id and unwraps `contentKey`.

```
header (headerLen = 46 + 68 * recipientCount):
  recipientCount     2 bytes    uint16, number of recipients (1..N)
  contentNonce       12 bytes   GCM IV for the content ciphertext
  ephemeralPublicKey 32 bytes   X25519 ephemeral public key (shared by all wraps)
  recipients[recipientCount], each entry (68 bytes):
     keyId           8 bytes    SHA-256(recipientPublicKey)[0:8] — lets a reader find its wrap
     wrapNonce       12 bytes   GCM IV for this wrap
     wrappedKey      48 bytes   AES-256-GCM(wrapKey, wrapNonce, contentKey) = 32-byte key + 16-byte tag
```

- `wrapKey = HKDF-SHA256(X25519(ephemeralPrivate, recipientPublic), info="qdn-enc-v1-wrap")`
- A reader recomputes `wrapKey = HKDF-SHA256(X25519(recipientPrivate, ephemeralPublic), …)`,
  decrypts its `wrappedKey` → `contentKey`, then decrypts the content.
- **PUBLISHER** = `recipientCount == 1` with the publisher's own public key as the recipient.
- **ACCOUNTS** = `recipientCount == N` over the chosen `recipientPublicKeys`.

### Mode 0x02 — GROUP

The content is encrypted directly with the group's shared key; no per-recipient wraps.

```
header (headerLen = 20):
  groupId            4 bytes    uint32 — the Qortium group
  groupKeyId         4 bytes    uint32 — which group key epoch/version
  contentNonce       12 bytes   GCM IV
ciphertext:          AES-256-GCM(groupKey, contentNonce, plaintext) + 16-byte tag
```

The `groupKey` for `(groupId, groupKeyId)` is obtained **client-side** from the group
key-announcement mechanism (the same announcements used by private group chat): a
member fetches the announcement, unwraps the group key with their own wallet key, and
caches it. Core stores the announcements but never holds the group key. Group access
is **forward-only**: rotating to a new `groupKeyId` protects *future* resources;
anything already published stays readable by whoever held the key at publish time.

## Multi-file (private apps, websites, galleries)

A private resource that is logically multi-file (an app, a website, an image
collection) is encrypted as **one envelope over the whole compressed archive** — the
client zips the directory, then encrypts that archive as the `plaintext` above. The
stored resource is therefore a **single encrypted blob**; after the client decrypts
it, it unzips into the directory and renders/uses it locally.

This keeps things simple and more private (the file list is hidden, not just the file
contents) and means private multi-file resources validate with the same single-file
envelope check — there is no separate per-file format. Because the resource is one opaque
blob to Core, there is no `?filepath=` support for private resources; the client decrypts,
unzips, and resolves paths itself. See
[multi-file-resources.md](multi-file-resources.md) for the full client guide (entry points,
default-file resolution, and app responsibilities).

## What Core validates (and what it does not)

Core validates **only the outer framing**, enough to reject plaintext:

- magic == `QENC`, version == `0x01`
- mode ∈ { `0x01`, `0x02` }, cipher == `0x01`
- `0 < headerLen ≤ 65535`, and the header plus **at least one ciphertext byte** are present

Core does **not** parse the header internals or the ciphertext, and cannot verify the
data actually decrypts. The header layouts above are a **client interop contract**,
not enforced by Core — different clients must agree on them to decrypt each other's
data. New `mode`/`cipher` ids must be added to Core before it will accept them.

> Note: Core inspects only the leading portion of the data (currently ~25 KB) to
> validate. The `RECIPIENTS` header grows with `recipientCount` (68 bytes each), so it
> must fit within that window — a practical ceiling of a few hundred recipients. For
> larger audiences, use `GROUP`.

## Public vs private services

- **Private** services (e.g. `*_PRIVATE`) require the data to be an envelope (or a
  legacy prefix). Plaintext → `DATA_NOT_ENCRYPTED`.
- **Public** services reject encrypted data → `DATA_ENCRYPTED`. Do not wrap public
  content in an envelope.

## Migration

Core accepts **both** formats:

1. the v1 envelope (preferred), and
2. the legacy ASCII prefixes `qdnEncryptedData` / `qdnGroupEncryptedData`.

Roll out in this order, with no consensus coordination required:

1. **Ship Core** that accepts envelope **or** legacy prefix (already done). Existing
   resources keep working.
2. **Update clients** to emit the v1 envelope for new publishes.
3. The legacy prefix can be accepted indefinitely; only deliberately sunset it once no
   resources rely on it.

## Client pseudocode

```js
// ---- common content encryption ----
const contentKey   = randomBytes(32);
const contentNonce = randomBytes(12);
const ciphertext   = aes256gcm.encrypt(contentKey, contentNonce, plaintext); // + 16-byte tag

function fixedHeader(mode, headerLen) {
  return bytes([
    0x51,0x45,0x4E,0x43,        // "QENC"
    0x01,                       // version
    mode,                       // 0x01 RECIPIENTS | 0x02 GROUP
    0x01,                       // cipher = AES-256-GCM
    0x00,                       // flags
    (headerLen >> 8) & 0xFF, headerLen & 0xFF,
  ]);
}

// ---- PUBLISHER / ACCOUNTS (mode 0x01) ----
// recipients = [publisherPublicKey]            for PUBLISHER
// recipients = recipientPublicKeys             for ACCOUNTS
const eph = x25519.generateKeyPair();
const entries = recipients.map(pub => {
  const wrapKey   = hkdfSha256(x25519.scalarMult(eph.private, pub), "qdn-enc-v1-wrap");
  const wrapNonce = randomBytes(12);
  const wrapped   = aes256gcm.encrypt(wrapKey, wrapNonce, contentKey); // 32 + 16 tag
  return concat(sha256(pub).slice(0,8) /*keyId*/, wrapNonce, wrapped);
});
const header = concat(uint16(recipients.length), contentNonce, eph.public /*32*/, ...entries);
const envelope = concat(fixedHeader(0x01, header.length), header, ciphertext);

// ---- GROUP (mode 0x02) ----
// groupKey resolved client-side from the group key announcement for (groupId, groupKeyId)
const ct2 = aes256gcm.encrypt(groupKey, contentNonce, plaintext);
const header2 = concat(uint32(groupId), uint32(groupKeyId), contentNonce);
const envelope2 = concat(fixedHeader(0x02, header2.length), header2, ct2);

// ---- read (RECIPIENTS) ----
// parse fixed header; headerLen = uint16(bytes[8..10]); header = bytes[10 .. 10+headerLen]
// recipientCount = uint16(header[0..2]); contentNonce = header[2..14]; eph = header[14..46]
// scan recipient entries for keyId == sha256(myPublicKey)[0:8]
// wrapKey = hkdfSha256(x25519(myPrivate, eph), "qdn-enc-v1-wrap")
// contentKey = aes256gcm.decrypt(wrapKey, wrapNonce, wrappedKey)
// plaintext  = aes256gcm.decrypt(contentKey, contentNonce, ciphertext)
```

The legacy format remains: `"qdnEncryptedData" + base64(ciphertext)` (recipient) or
`"qdnGroupEncryptedData" + base64(ciphertext)` (group).
