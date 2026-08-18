# QDN Encrypted Data Envelope

QENC is the binary envelope clients place around a private QDN resource. Core
does not receive the content, group, or account keys. It validates enough of the
outer framing to prevent accidental plaintext publication; Home performs the
cryptography and authorization.

QENC v2 is the interoperable format for new Qortium private chat attachments.
QENC v1 and the older text prefixes remain accepted unchanged for existing
generic private resources. New chat attachment implementations MUST emit v2.

All integers are unsigned big-endian. All protocol domain strings are exact
US-ASCII bytes without a terminator.

## QENC v2 outer envelope

```text
Offset  Size       Field
0       4          magic = ASCII "QENC"
4       1          version = 0x02
5       1          mode: 0x01 RECIPIENTS, 0x02 GROUP
6       1          cipher = 0x01 AES-256-GCM
7       1          flags = 0x00
8       2          headerLen
10      headerLen  exact mode header
10+hl   remaining  content ciphertext including the 16-byte GCM tag
```

The content ciphertext is:

```text
AES-256-GCM(contentKey, contentNonce, QATT plaintext,
            AAD = "QENC attachment content v2" || fixedHeader || modeHeader)
```

The fixed header in AAD is the exact first ten bytes, including `headerLen`.
The mode header is the exact serialized header. Any header mutation therefore
invalidates the content authentication tag.

Every content and wrap nonce is 12 cryptographically random bytes. A producer
MUST NOT reuse a nonce with the same AES key. Deterministic fixture nonces are
test inputs only.

## Mode 0x01 — RECIPIENTS

```text
headerLen = 46 + 68 * recipientCount

recipientCount       2 bytes   1..256
contentNonce         12 bytes
ephemeralPublicKey   32 bytes  raw X25519 public key
recipients[]         recipientCount entries, strictly sorted by keyId:
  keyId               8 bytes  SHA-256(recipient Ed25519 public key)[0:8]
  wrapNonce           12 bytes
  wrappedContentKey   48 bytes  32-byte key plus 16-byte GCM tag
```

Recipient key ids MUST be nonzero, unique, and in ascending unsigned byte order.
The ephemeral public key MUST be nonzero. Core enforces those canonical framing
rules so implementations cannot disagree about the serialized header.

For each recipient:

1. Convert the recipient's Qortium Ed25519 public key to X25519 using the same
   Edwards-to-Montgomery conversion as `Ed25519Extras.toX25519PublicKey`.
2. Compute `sharedSecret = X25519(ephemeralPrivateKey, recipientX25519PublicKey)`.
3. Build:

   ```text
   wrapInfo = "QENC attachment recipient wrap v2"
              || ephemeralPublicKey || keyId || contentNonce
   ```

4. Derive `wrapKey = HKDF32(sharedSecret,
   "QENC attachment recipient hkdf salt v2", wrapInfo)`.
5. Serialize `wrappedContentKey = AES-256-GCM(wrapKey, wrapNonce,
   contentKey, AAD=wrapInfo)`.

For decryption, an account converts its Ed25519 private seed to X25519 using the
same SHA-512-and-clamp conversion as `Ed25519Extras.toX25519PrivateKey`, finds
its key-id entry, derives the same wrap key, and unwraps the content key.

`HKDF32` is the one-block HKDF-SHA256 construction used by QDM1 and QPGC:

```text
prk = HMAC-SHA256(salt, inputKeyMaterial)
HKDF32 = HMAC-SHA256(prk, info || 0x01)
```

For a direct-message attachment, Home MUST use exactly the two distinct CHAT
participant public keys: sender and recipient. Wrapping to the sender is
required so the sender can reopen sent files. On read, Home MUST resolve and
attest both participants against the signed CHAT conversation before decrypting;
the app never supplies the authoritative key set.

The generic recipient mode still permits 1..256 entries for non-chat private
resources. Direct chat is the stricter two-participant profile above.

## Mode 0x02 — GROUP

```text
headerLen = 80

groupId       4 bytes  1..2147483647
epochId       32 bytes QPGC membership epoch id
keyId         32 bytes QPGC group-key id
contentNonce  12 bytes
```

`epochId` and `keyId` MUST be nonzero. The resolved 32-byte `groupKey` MUST
satisfy `PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey) ==
keyId`.

Attachments do not reuse the QPGC message key directly. Build:

```text
groupInfo = "QENC attachment group content v2"
            || groupId || epochId || keyId

contentKey = HKDF32(groupKey,
                    "QENC attachment group hkdf salt v2",
                    groupInfo)
```

Home obtains the QPGC key through the portable key-state/recovery flow, verifies
the group/epoch/key context, derives the attachment key, and keeps every key out
of app requests and results. A member removed after publication might retain a
previous epoch key and downloaded plaintext; rotation protects future content,
not already-disclosed bytes.

## QATT v1 encrypted plaintext

The plaintext inside QENC v2 is one QATT container, so sensitive metadata is
authenticated and encrypted with the file bytes:

```text
Offset  Size          Field
0       4             magic = ASCII "QATT"
4       1             version = 0x01
5       1             flags = 0x00
6       2             filenameLen (1..255 UTF-8 bytes)
8       2             mediaTypeLen (0..255 UTF-8 bytes)
10      4             dataLen (1..uint32)
14      32            SHA-256(data)
46      filenameLen   filename UTF-8
...     mediaTypeLen  media type UTF-8, or empty if unknown
...     dataLen       exact file bytes
```

The total decoded length MUST equal the declared lengths. UTF-8 MUST decode
strictly. Filenames cannot be `.`, `..`, contain a slash, backslash, or control
character. Media types cannot contain control characters. Consumers still
sanitize the filename and sniff bytes before display; encrypted publisher
metadata is not automatically trustworthy.

## Size and QDN service

`QCHAT_ATTACHMENT_PRIVATE` has a 1 MiB (1,048,576-byte) limit. The limit counts
the complete QENC envelope: fixed header, mode header, encrypted QATT framing
and metadata, file data, and GCM tag. It is not a 1 MiB plaintext-file limit.
The reference codec rejects an envelope one byte over this ceiling.

Ciphertext is published under an opaque identifier. The signed chat descriptor
must pin the resource transaction signature and/or ciphertext SHA-256; a mutable
coordinate alone is not an authenticated attachment identity. QDN still exposes
publisher, resource size, and publication timing. Encryption does not hide that
metadata.

## What Core validates

For v2 Core checks:

- exact magic, version, mode, cipher, zero flags, and `headerLen`;
- at least a complete 16-byte content authentication tag;
- the exact recipient count/header formula, 1..256 bound, nonzero X25519 key,
  and strictly sorted unique nonzero recipient key ids; or
- the exact 80-byte group header, positive signed-int-range group id, and
  nonzero epoch/key ids.

Core cannot authenticate ciphertext without a key. Home verifies all KDF, AAD,
context, QATT, digest, and AEAD conditions after authorization.

Core inspects the leading 25 KiB during QDN service validation. The maximum v2
recipient header is 17,454 bytes, so every supported header plus a full GCM tag
fits in that window. The complete resource-size check separately enforces the
service ceiling.

## Compatibility

QENC v1 keeps its historical behavior: Core recognizes its fixed fields, an
opaque nonempty mode header, and at least one following byte. Its stale
four-byte group-key concept is not redefined. Existing v1 and legacy-prefix
resources therefore remain readable and valid, but new private chat attachments
MUST use v2.

For archival interoperability, the previously documented v1 client layout was:

- RECIPIENTS: `recipientCount:2 | contentNonce:12 |
  ephemeralPublicKey:32 | N * (keyId:8 | wrapNonce:12 | wrappedKey:48)`, with
  `headerLen = 46 + 68*N`; and
- GROUP: `groupId:4 | groupKeyId:4 | contentNonce:12`, with `headerLen = 20`.

The old recipient guide named HKDF info `qdn-enc-v1-wrap` but did not freeze an
exact salt/AAD contract, and Core never parsed either mode header. That
underspecification is why v1 is preserved as historical data rather than used
for new interoperable chat attachments. The still older accepted text forms are
ASCII `qdnEncryptedData` or `qdnGroupEncryptedData` followed by their legacy
client ciphertext encoding.

The deterministic language-neutral fixture is
`src/test/resources/chat/interop/qenc-attachment-v2.json`. It freezes the QATT
bytes, both QENC modes, Ed25519/X25519 shared secrets, key ids, salts/info/AAD,
derived and wrapped keys, complete envelopes, sender reopen, the exact size
boundary, and negative mutations. Production nonces and keys remain random.
