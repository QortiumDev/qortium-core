# QDN Encrypted Data Envelope (v1)

This document specifies the **encrypted data envelope** that clients (Home / Hub /
Q-Apps) prepend to the data of a **private** QDN resource. It replaces the older
bare text prefix (`qdnEncryptedData…`) with a compact, structurally-verifiable
binary header.

## Why this exists

Core never holds the decryption key — encryption and decryption happen entirely
client-side, so private content stays private even from the node operator. The
trade-off is that Core **cannot cryptographically prove** a payload is real
ciphertext (it has no key, and good ciphertext is indistinguishable from random).

What Core *can* do is validate the **shape** of a well-defined envelope. That
robustly catches the failure mode that actually matters: an app accidentally
publishing **plaintext** as "private". Plaintext will not carry a valid envelope,
so publishing is rejected (`DATA_NOT_ENCRYPTED`).

This check runs only at **publish** and **read** time. It is **not** part of
consensus (arbitrary data is an opaque hash to the chain), so the format can
evolve without any coordinated network upgrade. Existing resources that use the
legacy prefix continue to work (see [Migration](#migration)).

## Envelope layout (v1)

The envelope is the **raw bytes** of the resource's data file: a fixed header,
a mode-specific header, then the ciphertext. All integers are **big-endian**.

```
Offset  Size           Field        Notes
------  -------------  -----------  -------------------------------------------
0       4              magic        ASCII "QENC" = 0x51 0x45 0x4E 0x43
4       1              version      0x01
5       1              mode         0x01 = single-recipient, 0x02 = group
6       1              cipher       0x01 = AES-256-GCM
7       1              flags        reserved, set to 0x00
8       2 (uint16)     headerLen    byte length of the mode-specific header below
10      headerLen      header       key material + nonce (see modes below)
10+hl   (rest)         ciphertext   AEAD output incl. the 16-byte GCM tag
```

Fixed header = **10 bytes**. The mode-specific header and the ciphertext follow.

### Mode 0x01 — single-recipient (public-key)

Encrypted **for one recipient's wallet key**; only that recipient's private key
can decrypt.

```
header (headerLen = 44):
  ephemeralPublicKey   32 bytes   X25519 ephemeral public key
  nonce                12 bytes   AES-GCM IV
ciphertext:            AES-256-GCM(key, nonce, plaintext) + 16-byte tag
```

Key derivation (recommended): `shared = X25519(ephemeralPrivate, recipientPublic)`,
then `key = HKDF-SHA256(shared, info="qdn-enc-v1")`. The recipient recomputes
`shared = X25519(recipientPrivate, ephemeralPublic)`.

### Mode 0x02 — group

Encrypted with a **group shared secret**, so any current group member can decrypt.

```
header (headerLen = 16):
  groupKeyId           4 bytes    uint32 identifying the group key / epoch
  nonce                12 bytes   AES-GCM IV
ciphertext:            AES-256-GCM(groupKey, nonce, plaintext) + 16-byte tag
```

`groupKey` is distributed out-of-band by the group mechanism; Core is not involved.

## What Core validates (and what it does not)

Core validates **only the outer framing**, enough to reject plaintext:

- magic == `QENC`, version == `0x01`
- mode ∈ { `0x01`, `0x02` }, cipher == `0x01`
- `0 < headerLen ≤ 4096`, and the header plus **at least one ciphertext byte** are present

Core does **not** parse the header internals or the ciphertext, and cannot verify
the data actually decrypts. The header layouts above are a **client interop
contract**, not enforced by Core — different clients must agree on them to decrypt
each other's data. Keep `cipher`/`mode` ids in sync with Core when adding new
schemes (Core must learn a new id before it will accept it).

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

1. **Ship Core** that accepts envelope **or** legacy prefix (this release). Existing
   resources keep working.
2. **Update clients** to emit the v1 envelope for new publishes.
3. The legacy prefix can be accepted indefinitely; only deliberately sunset it once
   no resources rely on it.

## Client pseudocode

```js
// --- publish (single-recipient) ---
const eph = x25519.generateKeyPair();
const shared = x25519.scalarMult(eph.private, recipientPublicKey);
const key = hkdfSha256(shared, "qdn-enc-v1");           // 32 bytes
const nonce = randomBytes(12);
const ct = aes256gcm.encrypt(key, nonce, plaintext);     // includes 16-byte tag

const header = concat(eph.public /*32*/, nonce /*12*/);  // headerLen = 44
const fixed = bytes([
  0x51,0x45,0x4E,0x43,        // "QENC"
  0x01,                       // version
  0x01,                       // mode = single-recipient
  0x01,                       // cipher = AES-256-GCM
  0x00,                       // flags
  (header.length >> 8) & 0xFF, header.length & 0xFF,
]);
const envelope = concat(fixed, header, ct);              // <-- publish this as the data

// --- read ---
// fixed = envelope[0..10]; headerLen = uint16(fixed[8..10])
// header = envelope[10 .. 10+headerLen]; ct = envelope[10+headerLen ..]
// ephemeralPublic = header[0..32]; nonce = header[32..44]
// shared = x25519(recipientPrivate, ephemeralPublic)
// key = hkdfSha256(shared, "qdn-enc-v1"); plaintext = aes256gcm.decrypt(key, nonce, ct)
```

The legacy format remains: `"qdnEncryptedData" + base64(ciphertext)` (single
recipient) or `"qdnGroupEncryptedData" + base64(ciphertext)` (group).
